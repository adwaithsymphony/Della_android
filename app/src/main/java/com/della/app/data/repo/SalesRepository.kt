package com.della.app.data.repo

import androidx.room.withTransaction
import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.MovementType
import com.della.app.data.db.PayMethod
import com.della.app.data.db.Payment
import com.della.app.data.db.PaymentDirection
import com.della.app.data.db.PaymentRef
import com.della.app.data.db.ProductKind
import com.della.app.data.db.Sale
import com.della.app.data.db.SaleLine
import com.della.app.data.db.SaleStatus
import com.della.app.domain.Money
import com.della.app.domain.TaxCalc

data class CartItem(val productId: Long, val qty: Long)

data class CheckoutRequest(
    val requestId: String,
    val items: List<CartItem>,
    val discountPaise: Long,
    val paymentMethod: String,
    /** Amount tendered now. For CREDIT sales this may be 0 or a part payment. */
    val amountPaidPaise: Long,
    val customerId: Long?,
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
)

data class PricedLine(
    val productId: Long,
    val name: String,
    val qty: Long,
    val unitPrice: Long,
    val unitCost: Long,
    val taxBps: Int,
    val gross: Long,
    val discount: Long,
    val net: Long,
    val tax: Long,
    val total: Long,
)

data class PricedCart(
    val lines: List<PricedLine>,
    val gross: Long,
    val discount: Long,
    val net: Long,
    val tax: Long,
    val total: Long,
)

class SalesRepository(private val db: DellaDatabase, private val audit: Audit, private val ledger: StockLedger) {
    private val dao = db.salesDao()

    fun observeRecent(limit: Int = 300) = dao.observeRecent(limit)
    fun observeSale(id: Long) = dao.observeSale(id)
    fun observeLines(id: Long) = dao.observeLines(id)
    fun observeForCustomer(customerId: Long) = dao.observeForCustomer(customerId)
    fun observeReceivables() = dao.observeReceivables()
    suspend fun paymentsFor(saleId: Long) = db.financeDao().paymentsFor(PaymentRef.SALE, saleId) +
        db.financeDao().paymentsFor(PaymentRef.SALE_REFUND, saleId)

    /** Pure pricing used by both the live cart preview and checkout, so they always agree. */
    suspend fun price(items: List<CartItem>, discountPaise: Long): PricedCart {
        val profile = db.profileDao().get()
        val mode = profile?.taxMode ?: "NONE"
        val defaultBps = profile?.defaultTaxBps ?: 0
        val products = items.map { item ->
            val p = db.catalogDao().product(item.productId) ?: throw BusinessRuleException("A product in the cart no longer exists.")
            p to item.qty
        }
        val grossLines = products.map { (p, q) -> p.pricePaise * q }
        val shares = TaxCalc.allocateDiscount(grossLines, discountPaise)
        val lines = products.mapIndexed { i, (p, q) ->
            val bps = p.taxBps ?: defaultBps
            val calc = TaxCalc.line(grossLines[i], shares[i], bps, mode)
            PricedLine(p.id, p.name, q, p.pricePaise, p.costPaise, bps, calc.gross, calc.discount, calc.net, calc.tax, calc.total)
        }
        return PricedCart(
            lines = lines,
            gross = lines.sumOf { it.gross },
            discount = lines.sumOf { it.discount },
            net = lines.sumOf { it.net },
            tax = lines.sumOf { it.tax },
            total = lines.sumOf { it.total },
        )
    }

    /**
     * Completes a sale atomically: sale + lines + stock movements + payment + audit.
     * If anything fails (e.g. insufficient stock) nothing is written.
     * Re-submitting the same requestId returns the existing sale instead of duplicating it.
     */
    suspend fun checkout(req: CheckoutRequest): Long = db.withTransaction {
        dao.saleByRequest(req.requestId)?.let { return@withTransaction it.id }
        rule(req.items.isNotEmpty()) { "The cart is empty." }
        rule(req.items.all { it.qty > 0 }) { "Quantities must be positive." }
        rule(req.items.map { it.productId }.toSet().size == req.items.size) { "Duplicate cart lines." }
        rule(req.discountPaise >= 0) { "Discount cannot be negative." }
        rule(req.paymentMethod in PayMethod.tender || req.paymentMethod == PayMethod.CREDIT) { "Choose a payment method." }
        val profile = db.profileDao().get()
        val cart = price(req.items, req.discountPaise)
        rule(req.discountPaise <= cart.gross) { "Discount cannot exceed the cart value." }
        cart.lines.forEach { l ->
            val p = db.catalogDao().product(l.productId)!!
            rule(p.active) { "${p.name} is archived and cannot be sold." }
            rule(p.kind == ProductKind.SELLABLE) { "${p.name} is an ingredient, not a sellable item." }
        }
        val paid: Long
        if (req.paymentMethod == PayMethod.CREDIT) {
            rule(profile?.allowCreditSales != false) { "Credit sales are disabled in settings." }
            rule(req.customerId != null) { "Select a customer for a credit (pay later) sale." }
            rule(req.amountPaidPaise in 0..cart.total) { "Part payment must be between ₹0 and the bill total." }
            paid = req.amountPaidPaise
        } else {
            rule(req.amountPaidPaise >= cart.total) { "Amount received is less than the bill total." }
            paid = cart.total   // change is returned; only the bill amount is retained
        }
        val number = "S-%05d".format(dao.maxId() + 1)
        val saleId = dao.insertSale(
            Sale(
                number = number, requestId = req.requestId, customerId = req.customerId, status = SaleStatus.COMPLETED,
                grossPaise = cart.gross, discountPaise = cart.discount, netPaise = cart.net, taxPaise = cart.tax,
                totalPaise = cart.total, paidPaise = paid, paymentMethod = req.paymentMethod,
                taxMode = profile?.taxMode ?: "NONE", note = req.note, createdAt = req.at,
            )
        )
        dao.insertLines(cart.lines.map {
            SaleLine(
                saleId = saleId, productId = it.productId, productName = it.name, qty = it.qty,
                unitPricePaise = it.unitPrice, unitCostPaise = it.unitCost, discountPaise = it.discount,
                taxBps = it.taxBps, taxPaise = it.tax, netPaise = it.net, totalPaise = it.total,
            )
        })
        cart.lines.forEach {
            ledger.move(it.productId, -it.qty, MovementType.SALE, "sale", saleId, it.unitCost, number, req.at)
        }
        if (paid > 0) {
            val method = if (req.paymentMethod == PayMethod.CREDIT) PayMethod.CASH else req.paymentMethod
            db.financeDao().insertPayment(
                Payment(direction = PaymentDirection.IN, amountPaise = paid, method = method, refType = PaymentRef.SALE, refId = saleId, note = number, createdAt = req.at)
            )
        }
        audit.log("SALE_COMPLETED", "sale", saleId, "$number total ${Money.format(cart.total)} via ${req.paymentMethod}" + if (paid < cart.total) ", outstanding ${Money.format(cart.total - paid)}" else "", req.at)
        saleId
    }

    /**
     * Reverses a completed sale: restores stock, refunds the cash actually received and
     * keeps the original record (marked REVERSED) for history. A sale can only be reversed once.
     */
    suspend fun reverse(saleId: Long, reason: String, refundMethod: String): Unit = db.withTransaction {
        rule(reason.isNotBlank()) { "A reason is required to reverse a sale." }
        val sale = dao.sale(saleId) ?: throw BusinessRuleException("Sale not found.")
        rule(sale.status == SaleStatus.COMPLETED) { "${sale.number} has already been reversed." }
        val now = System.currentTimeMillis()
        dao.lines(saleId).forEach {
            ledger.move(it.productId, it.qty, MovementType.SALE_REVERSAL, "sale", saleId, it.unitCostPaise, "Reversal of ${sale.number}", now)
        }
        if (sale.paidPaise > 0) {
            db.financeDao().insertPayment(
                Payment(direction = PaymentDirection.OUT, amountPaise = sale.paidPaise, method = refundMethod, refType = PaymentRef.SALE_REFUND, refId = saleId, note = "Refund ${sale.number}", createdAt = now)
            )
        }
        dao.updateSale(sale.copy(status = SaleStatus.REVERSED, reversedAt = now, reversalReason = reason.trim()))
        audit.log("SALE_REVERSED", "sale", saleId, "${sale.number} reversed (${reason.trim()}); stock restored, refunded ${Money.format(sale.paidPaise)}", now)
    }

    /** Applies a customer payment to their oldest unpaid sales first. Returns amount applied. */
    suspend fun settleCustomer(customerId: Long, amountPaise: Long, method: String): Long = db.withTransaction {
        rule(amountPaise > 0) { "Enter an amount." }
        rule(method in PayMethod.tender) { "Choose how the money was received." }
        val open = dao.receivablesFor(customerId)
        val outstanding = open.sumOf { it.totalPaise - it.paidPaise }
        rule(outstanding > 0) { "This customer has nothing outstanding." }
        rule(amountPaise <= outstanding) { "Amount exceeds the outstanding balance of ${Money.format(outstanding)}." }
        var remaining = amountPaise
        val now = System.currentTimeMillis()
        for (s in open) {
            if (remaining == 0L) break
            val due = s.totalPaise - s.paidPaise
            val apply = minOf(due, remaining)
            dao.updateSale(s.copy(paidPaise = s.paidPaise + apply))
            db.financeDao().insertPayment(
                Payment(direction = PaymentDirection.IN, amountPaise = apply, method = method, refType = PaymentRef.SALE, refId = s.id, note = "Settlement ${s.number}", createdAt = now)
            )
            remaining -= apply
        }
        val name = db.partyDao().customer(customerId)?.name ?: "Customer"
        audit.log("CUSTOMER_PAYMENT", "customer", customerId, "$name paid ${Money.format(amountPaise)} via $method", now)
        amountPaise
    }
}
