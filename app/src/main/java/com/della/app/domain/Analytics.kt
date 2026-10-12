package com.della.app.domain

import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.Expense
import com.della.app.data.db.LineWithSale
import com.della.app.data.db.MovementType
import com.della.app.data.db.Payment
import com.della.app.data.db.PaymentDirection
import com.della.app.data.db.PaymentRef
import com.della.app.data.db.ProductKind
import com.della.app.data.db.ProductRow
import com.della.app.data.db.Sale
import com.della.app.data.db.SaleStatus
import com.della.app.data.db.StockMovement
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Period(val from: LocalDate, val to: LocalDate, val label: String) {
    val fromMs: Long get() = Dates.startOf(from)
    val toMs: Long get() = Dates.endOf(to)
    val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
    fun previous(): Period = Period(from.minusDays(days.toLong()), from.minusDays(1), "previous ${days} days")

    companion object {
        fun today() = Dates.today().let { Period(it, it, "Today") }
        fun yesterday() = Dates.today().minusDays(1).let { Period(it, it, "Yesterday") }
        fun lastDays(n: Int) = Dates.today().let { Period(it.minusDays(n - 1L), it, "Last $n days") }
        fun monthToDate() = Dates.today().let { Period(it.withDayOfMonth(1), it, "This month") }
        fun lastMonth() = Dates.today().withDayOfMonth(1).minusMonths(1).let { Period(it, it.plusMonths(1).minusDays(1), "Last month") }
    }
}

/**
 * Every figure here is derived from persisted records at query time.
 * Revenue = net sales excluding tax, after discounts, completed (non-reversed) sales only.
 * Gross profit = revenue − cost of goods sold, using the unit cost captured at the time of sale.
 */
data class SalesSummary(
    val period: Period,
    val transactions: Int,
    val revenue: Long,
    val tax: Long,
    val billed: Long,
    val discounts: Long,
    val collected: Long,
    val outstanding: Long,
    val cogs: Long,
    val linesWithoutCost: Int,
    val units: Long,
    val reversedCount: Int,
    val reversedValue: Long,
) {
    val grossProfit: Long get() = revenue - cogs
    val avgTicket: Long get() = if (transactions == 0) 0 else billed / transactions
}

data class DayValue(val date: LocalDate, val value: Long, val count: Int = 0)

data class ProductPerformance(
    val productId: Long,
    val name: String,
    val units: Long,
    val revenue: Long,
    val cogs: Long,
) {
    val profit: Long get() = revenue - cogs
}

data class CashFlow(
    val inflowSales: Long,
    val outflowRefunds: Long,
    val outflowPurchases: Long,
    val outflowExpenses: Long,
) {
    val totalIn: Long get() = inflowSales
    val totalOut: Long get() = outflowRefunds + outflowPurchases + outflowExpenses
    val net: Long get() = totalIn - totalOut
}

data class InventoryHealth(
    val activeItems: Int,
    val lowStock: List<ProductRow>,
    val outOfStock: List<ProductRow>,
    val valueAtCost: Long,
    val valueAtPrice: Long,
    val itemsWithoutCost: Int,
)

data class MovementSummary(val type: String, val qty: Long, val valueAtCost: Long, val count: Int)

data class ExpenseSummary(
    val total: Long,
    val paid: Long,
    val byCategory: List<Pair<String, Long>>,
) { val unpaid: Long get() = total - paid }

data class PurchasingSummary(
    val receivedValue: Long,
    val receipts: Int,
    val openOrders: Int,
    val openOrderValue: Long,
    val payables: Long,
)

/** A snapshot of raw records for a window, loaded once and analysed in memory. */
class Dataset(
    val period: Period,
    val sales: List<Sale>,
    val lines: List<LineWithSale>,
    val payments: List<Payment>,
    val expenses: List<Expense>,
    val movements: List<StockMovement>,
    val products: List<ProductRow>,
)

class AnalyticsRepository(private val db: DellaDatabase) {

    fun changes() = db.changeDao().observeChanges()

    suspend fun load(period: Period): Dataset = Dataset(
        period = period,
        sales = db.salesDao().between(period.fromMs, period.toMs),
        lines = db.salesDao().linesBetween(period.fromMs, period.toMs),
        payments = db.financeDao().paymentsBetween(period.fromMs, period.toMs),
        expenses = db.financeDao().expensesBetween(period.fromMs, period.toMs),
        movements = db.stockDao().between(period.fromMs, period.toMs),
        products = db.catalogDao().allProducts(),
    )

    suspend fun firstSaleDate(): LocalDate? = db.salesDao().firstSaleAt()?.let { Dates.toDate(it) }

    suspend fun purchasing(period: Period): PurchasingSummary {
        val receipts = db.purchaseDao().receiptsBetween(period.fromMs, period.toMs)
        val pos = db.purchaseDao().all()
        val open = pos.filter { it.status == "ORDERED" || it.status == "PARTIAL" }
        var openValue = 0L
        for (po in open) openValue += db.purchaseDao().lines(po.id).sumOf { (it.qtyOrdered - it.qtyReceived) * it.unitCostPaise }
        return PurchasingSummary(
            receivedValue = receipts.sumOf { it.valuePaise },
            receipts = receipts.size,
            openOrders = open.size,
            openOrderValue = openValue,
            payables = db.purchaseDao().payables().sumOf { it.totalPaise - it.paidPaise },
        )
    }

    suspend fun productsOnOrder(): Set<Long> = db.purchaseDao().productsOnOrder().toSet()

    suspend fun stockAlertsEnabled(): Boolean = db.profileDao().get()?.lowStockAlerts != false
}

object Calc {
    fun summary(d: Dataset): SalesSummary {
        val done = d.sales.filter { it.status == SaleStatus.COMPLETED }
        val reversed = d.sales.filter { it.status == SaleStatus.REVERSED }
        val doneLines = d.lines.filter { it.status == SaleStatus.COMPLETED }
        return SalesSummary(
            period = d.period,
            transactions = done.size,
            revenue = done.sumOf { it.netPaise },
            tax = done.sumOf { it.taxPaise },
            billed = done.sumOf { it.totalPaise },
            discounts = done.sumOf { it.discountPaise },
            collected = done.sumOf { it.paidPaise },
            outstanding = done.sumOf { it.totalPaise - it.paidPaise },
            cogs = doneLines.sumOf { it.line.qty * it.line.unitCostPaise },
            linesWithoutCost = doneLines.count { it.line.unitCostPaise == 0L },
            units = doneLines.sumOf { it.line.qty },
            reversedCount = reversed.size,
            reversedValue = reversed.sumOf { it.totalPaise },
        )
    }

    fun daily(d: Dataset): List<DayValue> {
        val byDay = d.sales.filter { it.status == SaleStatus.COMPLETED }.groupBy { Dates.toDate(it.createdAt) }
        return generateSequence(d.period.from) { it.plusDays(1) }.takeWhile { !it.isAfter(d.period.to) }
            .map { day -> byDay[day].orEmpty().let { DayValue(day, it.sumOf { s -> s.netPaise }, it.size) } }
            .toList()
    }

    fun productPerformance(d: Dataset): List<ProductPerformance> =
        d.lines.filter { it.status == SaleStatus.COMPLETED }.groupBy { it.line.productId }.map { (id, ls) ->
            ProductPerformance(
                productId = id,
                name = ls.first().line.productName,
                units = ls.sumOf { it.line.qty },
                revenue = ls.sumOf { it.line.netPaise },
                cogs = ls.sumOf { it.line.qty * it.line.unitCostPaise },
            )
        }.sortedByDescending { it.revenue }

    fun unitsPerProduct(d: Dataset): Map<Long, Long> =
        d.lines.filter { it.status == SaleStatus.COMPLETED }.groupBy { it.line.productId }.mapValues { (_, v) -> v.sumOf { it.line.qty } }

    fun cashFlow(d: Dataset): CashFlow {
        fun sum(dir: String, vararg refs: String) = d.payments.filter { it.direction == dir && it.refType in refs }.sumOf { it.amountPaise }
        return CashFlow(
            inflowSales = sum(PaymentDirection.IN, PaymentRef.SALE, PaymentRef.CUSTOMER_SETTLEMENT),
            outflowRefunds = sum(PaymentDirection.OUT, PaymentRef.SALE_REFUND),
            outflowPurchases = sum(PaymentDirection.OUT, PaymentRef.PURCHASE),
            outflowExpenses = sum(PaymentDirection.OUT, PaymentRef.EXPENSE),
        )
    }

    fun expenses(d: Dataset): ExpenseSummary = ExpenseSummary(
        total = d.expenses.sumOf { it.amountPaise },
        paid = d.expenses.sumOf { it.paidPaise },
        byCategory = d.expenses.groupBy { it.category }.map { (k, v) -> k to v.sumOf { it.amountPaise } }.sortedByDescending { it.second },
    )

    fun inventory(products: List<ProductRow>): InventoryHealth {
        val active = products.filter { it.product.active }
        return InventoryHealth(
            activeItems = active.size,
            lowStock = active.filter { it.product.stockQty in 1..it.product.reorderLevel },
            outOfStock = active.filter { it.product.stockQty == 0L },
            valueAtCost = active.sumOf { it.product.stockQty * it.product.costPaise },
            valueAtPrice = active.filter { it.product.kind == ProductKind.SELLABLE }.sumOf { it.product.stockQty * it.product.pricePaise },
            itemsWithoutCost = active.count { it.product.costPaise == 0L },
        )
    }

    fun movements(d: Dataset): List<MovementSummary> =
        d.movements.groupBy { it.type }.map { (t, ms) ->
            MovementSummary(t, ms.sumOf { it.qtyDelta }, ms.sumOf { it.qtyDelta * it.unitCostPaise }, ms.size)
        }.sortedBy { listOf(MovementType.PURCHASE_RECEIPT, MovementType.PRODUCTION, MovementType.SALE, MovementType.SALE_REVERSAL, MovementType.USAGE, MovementType.WASTAGE, MovementType.ADJUSTMENT, MovementType.OPENING, MovementType.IMPORT).indexOf(it.type) }

    fun hourly(d: Dataset): List<Pair<Int, Int>> {
        val byHour = d.sales.filter { it.status == SaleStatus.COMPLETED }
            .groupBy { java.time.Instant.ofEpochMilli(it.createdAt).atZone(Dates.zone).hour }
        return (6..22).map { h -> h to (byHour[h]?.size ?: 0) }
    }

    /** Percentage change, or null when there is no meaningful base. */
    fun change(current: Long, previous: Long): Double? =
        if (previous <= 0) null else (current - previous) * 100.0 / previous
}
