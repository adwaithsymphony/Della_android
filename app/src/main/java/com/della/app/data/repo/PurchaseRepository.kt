package com.della.app.data.repo

import androidx.room.withTransaction
import com.della.app.data.db.Customer
import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.Expense
import com.della.app.data.db.GoodsReceipt
import com.della.app.data.db.InvoiceScan
import com.della.app.data.db.InvoiceScanLine
import com.della.app.data.db.MovementType
import com.della.app.data.db.PayMethod
import com.della.app.data.db.Payment
import com.della.app.data.db.PaymentDirection
import com.della.app.data.db.PaymentRef
import com.della.app.data.db.PoStatus
import com.della.app.data.db.PurchaseOrder
import com.della.app.data.db.PurchaseOrderLine
import com.della.app.data.db.ScanStatus
import com.della.app.data.db.Supplier
import com.della.app.domain.Money

class PartyRepository(private val db: DellaDatabase, private val audit: Audit) {
    private val dao = db.partyDao()
    fun observeCustomers() = dao.observeCustomers()
    fun observeCustomer(id: Long) = dao.observeCustomer(id)
    fun observeSuppliers() = dao.observeSuppliers()
    suspend fun customers() = dao.customers()
    suspend fun suppliers() = dao.suppliers()
    suspend fun supplier(id: Long) = dao.supplier(id)

    suspend fun saveCustomer(c: Customer): Long = db.withTransaction {
        rule(c.name.isNotBlank()) { "Customer name is required." }
        rule(c.phone.isBlank() || c.phone.filter { it.isDigit() }.length in 7..15) { "Phone number looks invalid." }
        rule(c.email.isBlank() || c.email.contains("@")) { "Email looks invalid." }
        val clean = c.copy(name = c.name.trim(), phone = c.phone.trim(), email = c.email.trim())
        if (c.id == 0L) {
            val id = dao.insertCustomer(clean)
            audit.log("CUSTOMER_CREATED", "customer", id, clean.name)
            id
        } else {
            dao.updateCustomer(clean)
            audit.log("CUSTOMER_UPDATED", "customer", c.id, clean.name)
            c.id
        }
    }

    suspend fun saveSupplier(s: Supplier): Long = db.withTransaction {
        rule(s.name.isNotBlank()) { "Supplier name is required." }
        rule(s.email.isBlank() || s.email.contains("@")) { "Email looks invalid." }
        val clean = s.copy(name = s.name.trim())
        if (s.id == 0L) {
            val id = dao.insertSupplier(clean)
            audit.log("SUPPLIER_CREATED", "supplier", id, clean.name)
            id
        } else {
            dao.updateSupplier(clean)
            audit.log("SUPPLIER_UPDATED", "supplier", s.id, clean.name)
            s.id
        }
    }
}

data class PoLineDraft(val productId: Long, val qty: Long, val unitCostPaise: Long)

class PurchaseRepository(private val db: DellaDatabase, private val audit: Audit, private val ledger: StockLedger) {
    private val dao = db.purchaseDao()
    fun observeAll() = dao.observeAll()
    fun observePo(id: Long) = dao.observePo(id)
    fun observeLines(id: Long) = dao.observeLines(id)
    fun observeReceipts(id: Long) = dao.observeReceipts(id)
    fun observeForSupplier(id: Long) = dao.observeForSupplier(id)
    suspend fun productsOnOrder() = dao.productsOnOrder().toSet()

    /** Creating an order never changes stock; stock increases only on receipt. */
    suspend fun createOrder(supplierId: Long, lines: List<PoLineDraft>, notes: String, supplierInvoiceNo: String = "", at: Long = System.currentTimeMillis()): Long = db.withTransaction {
        rule(db.partyDao().supplier(supplierId) != null) { "Choose a supplier." }
        rule(lines.isNotEmpty()) { "Add at least one item." }
        rule(lines.all { it.qty > 0 }) { "Quantities must be positive." }
        rule(lines.all { it.unitCostPaise >= 0 }) { "Unit costs cannot be negative." }
        rule(lines.map { it.productId }.toSet().size == lines.size) { "Each product may appear only once per order." }
        lines.forEach { rule(db.catalogDao().product(it.productId) != null) { "A product on this order no longer exists." } }
        val total = lines.sumOf { it.qty * it.unitCostPaise }
        val number = "PO-%04d".format(dao.maxId() + 1)
        val poId = dao.insertPo(
            PurchaseOrder(number = number, supplierId = supplierId, status = PoStatus.ORDERED, supplierInvoiceNo = supplierInvoiceNo.trim(), totalPaise = total, notes = notes.trim(), createdAt = at, updatedAt = at)
        )
        dao.insertLines(lines.map { PurchaseOrderLine(poId = poId, productId = it.productId, qtyOrdered = it.qty, unitCostPaise = it.unitCostPaise) })
        val supplier = db.partyDao().supplier(supplierId)?.name
        audit.log("PO_CREATED", "purchase_order", poId, "$number to $supplier, ${lines.size} items, ${Money.format(total)}", at)
        poId
    }

    /**
     * Receives goods against an order. Each quantity must not exceed what is still outstanding,
     * so the same delivery cannot be booked twice. Stock and the product's latest cost update here.
     */
    suspend fun receive(poId: Long, quantities: Map<Long, Long>, note: String, at: Long = System.currentTimeMillis()): Long = db.withTransaction {
        val po = dao.po(poId) ?: throw BusinessRuleException("Order not found.")
        rule(po.status == PoStatus.ORDERED || po.status == PoStatus.PARTIAL) { "${po.number} is ${po.status.lowercase()} and cannot receive goods." }
        val lines = dao.lines(poId).associateBy { it.id }
        val toReceive = quantities.filterValues { it > 0 }
        rule(toReceive.isNotEmpty()) { "Enter the quantity received for at least one item." }
        var value = 0L
        val receiptId = dao.insertReceipt(GoodsReceipt(poId = poId, valuePaise = 0, note = note.trim(), createdAt = at))
        toReceive.forEach { (lineId, qty) ->
            val line = lines[lineId] ?: throw BusinessRuleException("Order line not found.")
            val outstanding = line.qtyOrdered - line.qtyReceived
            val product = db.catalogDao().product(line.productId)!!
            rule(qty <= outstanding) { "${product.name}: only $outstanding ${product.unit} still outstanding." }
            dao.updateLine(line.copy(qtyReceived = line.qtyReceived + qty))
            ledger.move(line.productId, qty, MovementType.PURCHASE_RECEIPT, "goods_receipt", receiptId, line.unitCostPaise, po.number, at)
            if (line.unitCostPaise > 0 && line.unitCostPaise != product.costPaise) {
                db.catalogDao().product(line.productId)?.let { db.catalogDao().updateProduct(it.copy(costPaise = line.unitCostPaise, updatedAt = at)) }
            }
            value += qty * line.unitCostPaise
        }
        val after = dao.lines(poId)
        val status = if (after.all { it.qtyReceived >= it.qtyOrdered }) PoStatus.RECEIVED else PoStatus.PARTIAL
        dao.updatePo(po.copy(status = status, updatedAt = at))
        dao.updateReceipt(GoodsReceipt(id = receiptId, poId = poId, valuePaise = value, note = note.trim(), createdAt = at))
        audit.log("GOODS_RECEIVED", "purchase_order", poId, "${po.number}: received ${toReceive.size} line(s) worth ${Money.format(value)}; order now ${status.lowercase()}", at)
        receiptId
    }

    suspend fun cancel(poId: Long, reason: String): Unit = db.withTransaction {
        val po = dao.po(poId) ?: throw BusinessRuleException("Order not found.")
        rule(po.status == PoStatus.ORDERED || po.status == PoStatus.PARTIAL) { "Only open orders can be cancelled." }
        rule(reason.isNotBlank()) { "A reason is required." }
        // For partially received orders, the received part stays in stock; the remainder is cancelled
        val lines = dao.lines(poId)
        val receivedValue = lines.sumOf { it.qtyReceived * it.unitCostPaise }
        val newStatus = if (lines.any { it.qtyReceived > 0 }) PoStatus.RECEIVED else PoStatus.CANCELLED
        dao.updatePo(po.copy(status = newStatus, totalPaise = receivedValue, notes = (po.notes + "\nCancelled remainder: " + reason.trim()).trim(), updatedAt = System.currentTimeMillis()))
        audit.log("PO_CANCELLED", "purchase_order", poId, "${po.number} cancelled (${reason.trim()})" + if (newStatus == PoStatus.RECEIVED) "; closed at received quantities" else "")
    }

    suspend fun recordPayment(poId: Long, amountPaise: Long, method: String): Unit = db.withTransaction {
        val po = dao.po(poId) ?: throw BusinessRuleException("Order not found.")
        rule(po.status != PoStatus.CANCELLED) { "Order is cancelled." }
        rule(method in PayMethod.tender) { "Choose a payment method." }
        val due = po.totalPaise - po.paidPaise
        rule(amountPaise in 1..due) { "Amount must be between ₹0.01 and the balance due ${Money.format(due)}." }
        db.financeDao().insertPayment(Payment(direction = PaymentDirection.OUT, amountPaise = amountPaise, method = method, refType = PaymentRef.PURCHASE, refId = poId, note = po.number))
        dao.updatePo(po.copy(paidPaise = po.paidPaise + amountPaise, updatedAt = System.currentTimeMillis()))
        audit.log("SUPPLIER_PAYMENT", "purchase_order", poId, "${po.number}: paid ${Money.format(amountPaise)} via $method")
    }
}

data class InvoiceLineDraft(val description: String, val productId: Long?, val qty: Long, val unitCostPaise: Long)

data class InvoiceDraft(
    val id: Long = 0,
    val supplierName: String,
    val supplierId: Long?,
    val invoiceNumber: String,
    val invoiceDate: String,
    val totalPaise: Long,
    val rawText: String,
    val source: String,
    val lines: List<InvoiceLineDraft>,
)

class InvoiceRepository(private val db: DellaDatabase, private val audit: Audit, private val purchases: PurchaseRepository) {
    private val dao = db.invoiceDao()
    fun observeAll() = dao.observeAll()
    suspend fun scan(id: Long) = dao.scan(id)
    suspend fun lines(id: Long) = dao.lines(id)

    fun fingerprint(supplier: String, invoiceNo: String, total: Long): String =
        listOf(supplier.lowercase().filter { it.isLetterOrDigit() }, invoiceNo.lowercase().filter { it.isLetterOrDigit() }, total.toString())
            .joinToString("|")

    /** Saving a draft stores what was read; it never touches stock or purchasing records. */
    suspend fun saveDraft(d: InvoiceDraft): Long = db.withTransaction {
        val fp = fingerprint(d.supplierName, d.invoiceNumber, d.totalPaise)
        val scan = InvoiceScan(
            id = d.id, supplierName = d.supplierName.trim(), supplierId = d.supplierId, invoiceNumber = d.invoiceNumber.trim(),
            invoiceDate = d.invoiceDate.trim(), totalPaise = d.totalPaise, rawText = d.rawText, source = d.source,
            status = ScanStatus.DRAFT, fingerprint = fp,
        )
        val id = if (d.id == 0L) dao.insert(scan) else {
            val existing = dao.scan(d.id) ?: throw BusinessRuleException("Draft not found.")
            rule(existing.status == ScanStatus.DRAFT) { "This invoice was already ${existing.status.lowercase()}." }
            dao.update(scan.copy(createdAt = existing.createdAt)); d.id
        }
        dao.deleteLines(id)
        dao.insertLines(d.lines.filter { it.description.isNotBlank() || it.productId != null }.map {
            InvoiceScanLine(scanId = id, description = it.description.trim(), productId = it.productId, qty = it.qty, unitCostPaise = it.unitCostPaise)
        })
        if (d.id == 0L) audit.log("INVOICE_CAPTURED", "invoice", id, "Invoice ${d.invoiceNumber.ifBlank { "(no number)" }} from ${d.supplierName.ifBlank { "unknown supplier" }} captured via ${d.source}")
        id
    }

    /** Human-readable warnings; approval is blocked while duplicates exist. */
    suspend fun duplicateWarnings(d: InvoiceDraft): List<String> {
        val out = mutableListOf<String>()
        if (d.invoiceNumber.isNotBlank()) {
            val fp = fingerprint(d.supplierName, d.invoiceNumber, d.totalPaise)
            dao.byFingerprint(fp, d.id).forEach { out += "Same supplier, invoice number and total as a ${it.status.lowercase()} capture from ${com.della.app.domain.Dates.day(it.createdAt)}." }
            if (d.supplierId != null && dao.poWithInvoiceNo(d.invoiceNumber.trim(), d.supplierId) > 0) {
                out += "A purchase order already references invoice ${d.invoiceNumber.trim()} from this supplier."
            }
        }
        return out
    }

    fun validationErrors(d: InvoiceDraft): List<String> = buildList {
        if (d.supplierId == null) add("Match the invoice to a supplier.")
        if (d.invoiceNumber.isBlank()) add("Invoice number is required.")
        if (d.lines.isEmpty()) add("Add at least one line item.")
        d.lines.forEachIndexed { i, l ->
            if (l.productId == null) add("Line ${i + 1}: link to a product.")
            if (l.qty <= 0) add("Line ${i + 1}: quantity must be positive.")
        }
        if (d.lines.map { it.productId }.filterNotNull().let { it.toSet().size != it.size }) add("Each product may appear only once.")
        val lineSum = d.lines.sumOf { it.qty * it.unitCostPaise }
        if (d.totalPaise > 0 && lineSum != d.totalPaise) {
            add("Line items add up to ${Money.format(lineSum)} but invoice total is ${Money.format(d.totalPaise)}. Correct the lines or total (taxes/charges can be added as a line).")
        }
    }

    /**
     * Explicit approval turns a verified invoice into a purchase order awaiting receipt.
     * Stock still does not change until goods are received on that order.
     */
    suspend fun approve(d: InvoiceDraft): Long {
        val errors = validationErrors(d)
        rule(errors.isEmpty()) { errors.first() }
        rule(duplicateWarnings(d).isEmpty()) { "Possible duplicate invoice — resolve before approving." }
        return db.withTransaction {
            val id = saveDraft(d)
            val scan = dao.scan(id)!!
            rule(scan.status == ScanStatus.DRAFT) { "Invoice already processed." }
            val poId = purchases.createOrder(
                supplierId = d.supplierId!!,
                lines = d.lines.map { PoLineDraft(it.productId!!, it.qty, it.unitCostPaise) },
                notes = "From invoice ${d.invoiceNumber.trim()} (${d.invoiceDate})",
                supplierInvoiceNo = d.invoiceNumber,
            )
            dao.update(scan.copy(status = ScanStatus.APPROVED, linkedPoId = poId, decidedAt = System.currentTimeMillis()))
            audit.log("INVOICE_APPROVED", "invoice", id, "Invoice ${d.invoiceNumber} approved → purchase order created (stock unchanged until received)")
            poId
        }
    }

    suspend fun reject(id: Long, reason: String): Unit = db.withTransaction {
        val scan = dao.scan(id) ?: throw BusinessRuleException("Invoice not found.")
        rule(scan.status == ScanStatus.DRAFT) { "Invoice already processed." }
        dao.update(scan.copy(status = ScanStatus.REJECTED, decidedAt = System.currentTimeMillis()))
        audit.log("INVOICE_REJECTED", "invoice", id, "Invoice ${scan.invoiceNumber.ifBlank { "#$id" }} rejected: ${reason.ifBlank { "no reason" }}")
    }
}

class FinanceRepository(private val db: DellaDatabase, private val audit: Audit) {
    private val dao = db.financeDao()
    fun observeExpenses() = dao.observeExpenses()

    suspend fun addExpense(category: String, description: String, vendor: String, amountPaise: Long, paidNow: Boolean, method: String, spentAt: Long): Long = db.withTransaction {
        rule(category.isNotBlank()) { "Choose a category." }
        rule(description.isNotBlank()) { "Description is required." }
        rule(amountPaise > 0) { "Amount must be greater than zero." }
        rule(spentAt <= System.currentTimeMillis() + 86_400_000L) { "Expense date cannot be in the future." }
        rule(!paidNow || method in PayMethod.tender) { "Choose a payment method." }
        val paid = if (paidNow) amountPaise else 0L
        val id = dao.insertExpense(Expense(category = category, description = description.trim(), vendor = vendor.trim(), amountPaise = amountPaise, paidPaise = paid, method = method, spentAt = spentAt))
        if (paid > 0) dao.insertPayment(Payment(direction = PaymentDirection.OUT, amountPaise = paid, method = method, refType = PaymentRef.EXPENSE, refId = id, note = description.trim(), createdAt = spentAt))
        audit.log("EXPENSE_RECORDED", "expense", id, "$category: ${description.trim()} ${Money.format(amountPaise)}" + if (paid == 0L) " (unpaid)" else "")
        id
    }

    suspend fun payExpense(id: Long, method: String): Unit = db.withTransaction {
        val e = dao.expense(id) ?: throw BusinessRuleException("Expense not found.")
        val due = e.amountPaise - e.paidPaise
        rule(due > 0) { "Already paid." }
        rule(method in PayMethod.tender) { "Choose a payment method." }
        dao.insertPayment(Payment(direction = PaymentDirection.OUT, amountPaise = due, method = method, refType = PaymentRef.EXPENSE, refId = id, note = e.description))
        dao.updateExpense(e.copy(paidPaise = e.amountPaise, method = method))
        audit.log("EXPENSE_PAID", "expense", id, "${e.description}: paid ${Money.format(due)} via $method")
    }
}
