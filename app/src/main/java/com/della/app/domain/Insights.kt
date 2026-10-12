package com.della.app.domain

import com.della.app.data.db.MovementType
import com.della.app.data.db.ProductKind
import com.della.app.data.db.SaleStatus
import kotlin.math.abs
import kotlin.math.ceil

enum class Severity { HIGH, MEDIUM, INFO }

/**
 * A transparent, rule-based insight. `observed` holds facts computed from records;
 * `inference` (optional) is clearly labelled as a possible explanation that was NOT verified.
 */
data class Insight(
    val key: String,
    val category: String,
    val severity: Severity,
    val title: String,
    val observed: List<String>,
    val method: String,
    val inference: String?,
    val action: String,
)

/** Rule thresholds, shown in the UI so users can see exactly how insights are produced. */
object InsightRules {
    const val USAGE_WINDOW_DAYS = 14
    const val COVER_DAYS_ALERT = 2.0
    const val SALES_CHANGE_PCT = 15.0
    const val SALES_MIN_TRANSACTIONS = 10
    const val EXPENSE_RATIO = 1.5
    const val EXPENSE_MIN_PAISE = 100_000L  // ₹1,000 floor to ignore tiny categories
    const val WASTAGE_PCT = 10.0

    val description = listOf(
        "Stock risk: average daily units sold over the last $USAGE_WINDOW_DAYS days vs current stock. Flagged when stock covers less than ${COVER_DAYS_ALERT.toInt()} days or is at/below its reorder level.",
        "Sales change: revenue of the last 7 days vs the 7 days before. Flagged when the change is at least ${SALES_CHANGE_PCT.toInt()}% and both weeks have $SALES_MIN_TRANSACTIONS+ transactions.",
        "Expense anomaly: a category's spend in the last 30 days vs its average per 30 days over the prior 90 days. Flagged at ${EXPENSE_RATIO}× or more (and above ₹1,000).",
        "Purchasing need: ingredient or item at/below reorder level with no open purchase order.",
        "Margin: sellable items whose recorded cost is at or above the selling price, or missing a cost.",
        "Wastage: items whose recorded wastage in the last 14 days is ${WASTAGE_PCT.toInt()}%+ of units produced.",
        "Receivables: credit sales not yet paid.",
    )
}

class InsightEngine(private val analytics: AnalyticsRepository) {

    suspend fun generate(): List<Insight> {
        val out = mutableListOf<Insight>()
        val usage = analytics.load(Period.lastDays(InsightRules.USAGE_WINDOW_DAYS))
        val products = usage.products.filter { it.product.active }
        val units = Calc.unitsPerProduct(usage)
        val onOrder = analytics.productsOnOrder()
        val stockAlerts = analytics.stockAlertsEnabled()

        // 1. Stock risk (sellables, sales-driven) ------------------------------------------
        for (row in products.filter { stockAlerts && it.product.kind == ProductKind.SELLABLE }) {
            val p = row.product
            val sold = units[p.id] ?: 0L
            val perDay = sold.toDouble() / InsightRules.USAGE_WINDOW_DAYS
            val cover = if (perDay > 0) p.stockQty / perDay else Double.POSITIVE_INFINITY
            val belowReorder = p.reorderLevel > 0 && p.stockQty <= p.reorderLevel
            if (perDay > 0 && (cover < InsightRules.COVER_DAYS_ALERT || belowReorder)) {
                val sev = if (p.stockQty == 0L || cover < 1.0) Severity.HIGH else Severity.MEDIUM
                out += Insight(
                    key = "stock-${p.id}", category = "Stock risk", severity = sev,
                    title = if (p.stockQty == 0L) "${p.name} is out of stock" else "${p.name} may run out in ~${"%.1f".format(cover)} days",
                    observed = listOf(
                        "Current stock: ${p.stockQty} ${p.unit} (reorder level ${p.reorderLevel}).",
                        "Sold $sold ${p.unit} in the last ${InsightRules.USAGE_WINDOW_DAYS} days ≈ ${"%.1f".format(perDay)} per day.",
                    ),
                    method = "Days of cover = current stock ÷ average daily units sold (${InsightRules.USAGE_WINDOW_DAYS}-day window).",
                    inference = null,
                    action = "Plan production or reorder of about ${ceil(perDay * 3).toLong().coerceAtLeast(1L)} ${p.unit} to cover the next 3 days at the recent rate.",
                )
            }
        }

        // 2. Purchasing needs (ingredients/items at reorder level without an open PO) -------
        for (row in products.filter { stockAlerts && it.product.kind == ProductKind.INGREDIENT }) {
            val p = row.product
            if (p.reorderLevel > 0 && p.stockQty <= p.reorderLevel && p.id !in onOrder) {
                val used = usage.movements.filter { it.productId == p.id && it.type == MovementType.USAGE }.sumOf { -it.qtyDelta }
                val suggested = maxOf(p.reorderLevel * 2 - p.stockQty, used, 1L)
                out += Insight(
                    key = "buy-${p.id}", category = "Purchasing", severity = if (p.stockQty == 0L) Severity.HIGH else Severity.MEDIUM,
                    title = "Reorder ${p.name}",
                    observed = listOfNotNull(
                        "Stock ${p.stockQty} ${p.unit} is at or below reorder level ${p.reorderLevel}.",
                        "No open purchase order includes this item.",
                        if (used > 0) "Recorded usage in the last ${InsightRules.USAGE_WINDOW_DAYS} days: $used ${p.unit}." else null,
                    ),
                    method = "Suggested quantity = larger of (2 × reorder level − stock) and recent ${InsightRules.USAGE_WINDOW_DAYS}-day usage.",
                    inference = null,
                    action = "Create a purchase order for about $suggested ${p.unit}" + if (p.costPaise > 0) " (≈ ${Money.format(suggested * p.costPaise)} at last cost)." else ".",
                )
            }
        }

        // 3. Sales change week over week ----------------------------------------------------
        val thisWeek = Calc.summary(analytics.load(Period.lastDays(7)))
        val lastWeekData = analytics.load(Period.lastDays(7).previous())
        val lastWeek = Calc.summary(lastWeekData)
        val chg = Calc.change(thisWeek.revenue, lastWeek.revenue)
        if (chg != null && abs(chg) >= InsightRules.SALES_CHANGE_PCT &&
            thisWeek.transactions >= InsightRules.SALES_MIN_TRANSACTIONS && lastWeek.transactions >= InsightRules.SALES_MIN_TRANSACTIONS
        ) {
            val thisData = analytics.load(Period.lastDays(7))
            val a = Calc.productPerformance(thisData).associateBy { it.productId }
            val b = Calc.productPerformance(lastWeekData).associateBy { it.productId }
            val drivers = (a.keys + b.keys).map { id ->
                val name = a[id]?.name ?: b[id]?.name ?: "?"
                name to ((a[id]?.revenue ?: 0) - (b[id]?.revenue ?: 0))
            }.sortedByDescending { if (chg < 0) -it.second else it.second }.take(2)
            val txChg = Calc.change(thisWeek.transactions.toLong(), lastWeek.transactions.toLong())
            val ticketChg = Calc.change(thisWeek.avgTicket, lastWeek.avgTicket)
            out += Insight(
                key = "sales-wow", category = "Sales change", severity = if (chg < 0) Severity.MEDIUM else Severity.INFO,
                title = "Revenue ${if (chg < 0) "down" else "up"} ${"%.0f".format(abs(chg))}% vs previous week",
                observed = listOf(
                    "Last 7 days: ${Money.format(thisWeek.revenue)} from ${thisWeek.transactions} sales; previous 7 days: ${Money.format(lastWeek.revenue)} from ${lastWeek.transactions}.",
                    "Transactions ${fmtPct(txChg)}, average bill ${fmtPct(ticketChg)}.",
                    "Largest product contributions: " + drivers.joinToString { "${it.first} (${if (it.second >= 0) "+" else ""}${Money.format(it.second, false)})" } + ".",
                ),
                method = "Compares net revenue (excl. tax, completed sales) of two consecutive 7-day windows.",
                inference = "Possible factors (not verified from data): " + when {
                    txChg != null && ticketChg != null && abs(txChg) > abs(ticketChg) -> "change in customer footfall rather than spend per visit; check local events, weather, holidays or opening hours."
                    else -> "change in basket size or pricing/discounting rather than number of customers."
                },
                action = if (chg < 0) "Review the products driving the decline and whether they were in stock all week." else "Check that production of the top contributors keeps pace with demand.",
            )
        }

        // 4. Expense anomalies --------------------------------------------------------------
        val recentExp = Calc.expenses(analytics.load(Period.lastDays(30))).byCategory.toMap()
        val today = Dates.today()
        val prior = analytics.load(Period(today.minusDays(119), today.minusDays(30), "prior 90 days"))
        val priorByCat = prior.expenses.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } / 3 }
        for ((cat, amt) in recentExp) {
            val base = priorByCat[cat] ?: 0
            if (base > 0 && amt >= InsightRules.EXPENSE_MIN_PAISE && amt >= base * InsightRules.EXPENSE_RATIO) {
                val items = analytics.load(Period.lastDays(30)).expenses.filter { it.category == cat }.sortedByDescending { it.amountPaise }.take(2)
                out += Insight(
                    key = "exp-$cat", category = "Expense anomaly", severity = Severity.MEDIUM,
                    title = "$cat spending is ${"%.1f".format(amt.toDouble() / base)}× its usual level",
                    observed = listOf(
                        "Last 30 days: ${Money.format(amt)}. Average per 30 days over the prior 90 days: ${Money.format(base)}.",
                        "Largest entries: " + items.joinToString { "${it.description} ${Money.format(it.amountPaise, false)} on ${Dates.day(it.spentAt)}" } + ".",
                    ),
                    method = "Ratio of last-30-day spend to the average 30-day spend in the previous 90 days.",
                    inference = "Could be a one-off (e.g. repair) or a price increase — the records do not say which.",
                    action = "Confirm whether this is one-off. If recurring, update pricing or budget assumptions.",
                )
            }
        }

        // 5. Margin problems ----------------------------------------------------------------
        for (row in products.filter { it.product.kind == ProductKind.SELLABLE }) {
            val p = row.product
            if (p.costPaise == 0L) {
                out += Insight(
                    key = "nocost-${p.id}", category = "Margin", severity = Severity.INFO,
                    title = "${p.name} has no recorded cost",
                    observed = listOf("Cost is ₹0, so its profit cannot be calculated and margins shown are overstated."),
                    method = "Checks product cost field.", inference = null,
                    action = "Enter an estimated unit cost in Products.",
                )
            } else if (p.costPaise >= p.pricePaise) {
                out += Insight(
                    key = "margin-${p.id}", category = "Margin", severity = Severity.HIGH,
                    title = "${p.name} sells at or below cost",
                    observed = listOf("Price ${Money.format(p.pricePaise)} vs recorded cost ${Money.format(p.costPaise)}."),
                    method = "Compares selling price with the latest recorded unit cost.", inference = null,
                    action = "Review the price or recipe cost.",
                )
            }
        }

        // 6. Wastage ------------------------------------------------------------------------
        val byProduct = usage.movements.groupBy { it.productId }
        for (row in products) {
            val ms = byProduct[row.product.id] ?: continue
            val produced = ms.filter { it.type == MovementType.PRODUCTION }.sumOf { it.qtyDelta }
            val wasted = ms.filter { it.type == MovementType.WASTAGE }.sumOf { -it.qtyDelta }
            if (produced >= 20 && wasted * 100.0 / produced >= InsightRules.WASTAGE_PCT) {
                out += Insight(
                    key = "waste-${row.product.id}", category = "Wastage", severity = Severity.MEDIUM,
                    title = "${row.product.name}: ${"%.0f".format(wasted * 100.0 / produced)}% of production wasted",
                    observed = listOf("Last ${InsightRules.USAGE_WINDOW_DAYS} days: produced $produced, wasted $wasted ${row.product.unit} (≈ ${Money.format(wasted * row.product.costPaise, false)} at cost)."),
                    method = "Wastage movements ÷ production movements over the window.",
                    inference = "Production may exceed demand on some days.",
                    action = "Reduce daily batch size toward average sales of ${"%.0f".format((units[row.product.id] ?: 0) / InsightRules.USAGE_WINDOW_DAYS.toDouble())} per day.",
                )
            }
        }

        // 7. Receivables --------------------------------------------------------------------
        val all = analytics.load(Period(today.minusDays(365), today, "year"))
        val unpaid = all.sales.filter { it.status == SaleStatus.COMPLETED && it.totalPaise > it.paidPaise }
        if (unpaid.isNotEmpty()) {
            val oldest = unpaid.minOf { it.createdAt }
            out += Insight(
                key = "receivables", category = "Cash", severity = if (today.toEpochDay() - Dates.toDate(oldest).toEpochDay() > 14) Severity.MEDIUM else Severity.INFO,
                title = "${Money.format(unpaid.sumOf { it.totalPaise - it.paidPaise })} owed by customers",
                observed = listOf("${unpaid.size} credit sale(s) unpaid; oldest from ${Dates.day(oldest)}.", "This amount is revenue but not yet cash."),
                method = "Sum of (bill total − amount received) on completed sales.", inference = null,
                action = "Follow up from Customers → record payments when received.",
            )
        }

        return out.sortedWith(compareBy<Insight> { it.severity.ordinal }.thenBy { it.category })
    }

    private fun fmtPct(v: Double?) = v?.let { "${if (it >= 0) "+" else ""}${"%.0f".format(it)}%" } ?: "n/a"
}
