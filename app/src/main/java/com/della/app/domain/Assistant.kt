package com.della.app.domain

import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.ProductKind
import com.della.app.data.db.SaleStatus

data class AssistantAnswer(
    val text: String,
    /** Which records and calculation produced the answer. */
    val basis: String,
    val understood: Boolean = true,
)

/**
 * Deterministic business Q&A. It recognises intents with keyword rules and answers ONLY
 * with figures computed from the local database. It is read-only: it cannot change records.
 * No language model is used, so unrecognised questions get an honest "can't answer" reply.
 */
class BusinessAssistant(
    private val db: DellaDatabase,
    private val analytics: AnalyticsRepository,
    private val insights: InsightEngine,
    private val forecast: ForecastEngine,
) {
    val suggestions = listOf(
        "How were sales today?",
        "Revenue this month vs last month",
        "What are my best sellers?",
        "What is running low?",
        "How much stock of croissant?",
        "Expenses this month",
        "What was my gross profit last 30 days?",
        "Who owes me money?",
        "Forecast next week",
        "Any recommendations?",
    )

    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }

    private fun periodFrom(q: String, default: Period): Period = when {
        has(q, "yesterday") -> Period.yesterday()
        has(q, "today", "so far") -> Period.today()
        has(q, "this month", "month to date", "mtd") -> Period.monthToDate()
        has(q, "last month", "previous month") -> Period.lastMonth()
        has(q, "month") -> Period.monthToDate()
        has(q, "30 days", "last month's", "past month") -> Period.lastDays(30)
        has(q, "this week", "last 7", "past week", "week", "7 days") -> Period.lastDays(7)
        has(q, "90 days", "quarter") -> Period.lastDays(90)
        else -> default
    }

    suspend fun ask(raw: String): AssistantAnswer {
        val q = raw.lowercase().trim()
        if (q.isEmpty()) return AssistantAnswer("Ask me about sales, stock, expenses, profit, customers or forecasts.", "—", false)

        val products = db.catalogDao().allProducts()
        val named = products.filter { row ->
            val n = row.product.name.lowercase()
            q.contains(n) || n.split(" ").filter { it.length >= 4 }.any { w -> q.contains(w.trimEnd('s')) }
        }

        return when {
            has(q, "help", "what can you") -> AssistantAnswer(
                "I answer from your recorded data: sales and revenue for a period, transactions and average bill, best and slow sellers, stock levels and low stock, expenses, gross profit, cash flow, money owed, forecasts and recommendations.",
                "Built-in question rules (no AI model configured).",
            )

            has(q, "recommend", "insight", "suggest", "what should i", "advice", "problem") -> {
                val list = insights.generate()
                if (list.isEmpty()) AssistantAnswer("No rule is currently flagged — stock cover, sales trend, expenses and margins all look within thresholds.", "Insight rules on current records.")
                else AssistantAnswer(
                    list.take(5).joinToString("\n") { "• ${it.title} → ${it.action}" } + if (list.size > 5) "\n…and ${list.size - 5} more in Insights." else "",
                    "Rule-based insights (see Insights for evidence and method).",
                )
            }

            has(q, "forecast", "predict", "tomorrow", "next week", "expect") -> {
                val f = forecast.forecast()
                if (!f.available) AssistantAnswer(f.reason, "Forecast requirements.")
                else {
                    val total = f.days.sumOf { it.expected }
                    val tomorrow = f.days.getOrNull(1)
                    AssistantAnswer(
                        "Expected revenue for the next ${f.days.size} days: about ${Money.format(total, false)} (range ${Money.format(f.days.sumOf { it.low }, false)}–${Money.format(f.days.sumOf { it.high }, false)})." +
                            (tomorrow?.let { "\nTomorrow: ~${Money.format(it.expected, false)}." } ?: "") +
                            (f.backtestMape?.let { "\nOn the last ${f.backtestDays} days this method was off by ${"%.0f".format(it)}% on average." } ?: ""),
                        "Median of same weekday over last 8 weeks; see Forecast for assumptions.",
                    )
                }
            }

            has(q, "owe", "outstanding", "receivable", "credit", "unpaid", "due", "payable") -> {
                val unpaid = db.salesDao().between(0, Long.MAX_VALUE).filter { it.status == SaleStatus.COMPLETED && it.totalPaise > it.paidPaise }
                val names = db.partyDao().customers().associateBy { it.id }
                val byCustomer = unpaid.groupBy { it.customerId }.map { (id, s) -> (names[id]?.name ?: "Walk-in") to s.sumOf { it.totalPaise - it.paidPaise } }.sortedByDescending { it.second }
                val payables = db.purchaseDao().payables().sumOf { it.totalPaise - it.paidPaise }
                val unpaidExpenses = db.financeDao().allExpenses().sumOf { it.amountPaise - it.paidPaise }
                AssistantAnswer(
                    (if (byCustomer.isEmpty()) "No customer owes you money." else "Customers owe ${Money.format(byCustomer.sumOf { it.second })}:\n" + byCustomer.take(5).joinToString("\n") { "• ${it.first}: ${Money.format(it.second)}" }) +
                        "\nYou owe suppliers ${Money.format(payables)} and have ${Money.format(unpaidExpenses)} of unpaid expenses.",
                    "Completed sales where amount received < bill; purchase orders and expenses not fully paid.",
                )
            }

            has(q, "low", "running out", "reorder", "out of stock", "restock") -> {
                val health = Calc.inventory(products)
                val items = health.outOfStock + health.lowStock
                if (items.isEmpty()) AssistantAnswer("Nothing is at or below its reorder level.", "Current stock vs reorder level of ${health.activeItems} active items.")
                else AssistantAnswer(
                    "${items.size} item(s) need attention:\n" + items.take(10).joinToString("\n") { "• ${it.product.name}: ${it.product.stockQty} ${it.product.unit} (reorder at ${it.product.reorderLevel})" },
                    "Current stock vs reorder level of ${health.activeItems} active items.",
                )
            }

            named.isNotEmpty() && has(q, "stock", "how many", "left", "have", "inventory", "price", "cost", "sold", "sell") -> {
                val p = named.first().product
                val d = analytics.load(periodFrom(q, Period.lastDays(30)))
                val perf = Calc.productPerformance(d).firstOrNull { it.productId == p.id }
                AssistantAnswer(
                    "${p.name}: ${p.stockQty} ${p.unit} in stock (reorder at ${p.reorderLevel}). Price ${Money.format(p.pricePaise)}, cost ${Money.format(p.costPaise)}." +
                        if (p.kind == ProductKind.SELLABLE) "\n${d.period.label}: sold ${perf?.units ?: 0} ${p.unit} for ${Money.format(perf?.revenue ?: 0)} revenue." else "",
                    "Product record and its sales lines for ${d.period.label.lowercase()}.",
                )
            }

            has(q, "best", "top", "popular", "selling") && !has(q, "customer") -> {
                val d = analytics.load(periodFrom(q, Period.lastDays(30)))
                val perf = Calc.productPerformance(d)
                if (perf.isEmpty()) AssistantAnswer("No sales recorded for ${d.period.label.lowercase()}.", "Sales lines.")
                else AssistantAnswer(
                    "Best sellers, ${d.period.label.lowercase()} (by revenue):\n" + perf.take(5).mapIndexed { i, p -> "${i + 1}. ${p.name} — ${p.units} sold, ${Money.format(p.revenue, false)}" }.joinToString("\n"),
                    "Completed sale lines, net revenue excl. tax.",
                )
            }

            has(q, "slow", "worst", "least") -> {
                val d = analytics.load(periodFrom(q, Period.lastDays(30)))
                val perf = Calc.productPerformance(d).associateBy { it.productId }
                val sellables = products.filter { it.product.active && it.product.kind == ProductKind.SELLABLE }
                val ranked = sellables.map { it.product.name to (perf[it.product.id]?.units ?: 0) }.sortedBy { it.second }.take(5)
                AssistantAnswer("Slowest sellers, ${d.period.label.lowercase()}:\n" + ranked.joinToString("\n") { "• ${it.first}: ${it.second} sold" }, "Units sold per active sellable item.")
            }

            has(q, "customer") -> {
                val d = analytics.load(periodFrom(q, Period.lastDays(90)))
                val names = db.partyDao().customers().associateBy { it.id }
                val top = d.sales.filter { it.status == SaleStatus.COMPLETED && it.customerId != null }.groupBy { it.customerId }
                    .map { (id, s) -> (names[id]?.name ?: "?") to s.sumOf { it.totalPaise } }.sortedByDescending { it.second }.take(5)
                if (top.isEmpty()) AssistantAnswer("No sales linked to customers for ${d.period.label.lowercase()}.", "Sales with a customer attached.")
                else AssistantAnswer("Top customers, ${d.period.label.lowercase()}:\n" + top.joinToString("\n") { "• ${it.first}: ${Money.format(it.second, false)}" }, "Sales with a customer attached.")
            }

            has(q, "expense", "spend", "spent", "cost of running") -> {
                val d = analytics.load(periodFrom(q, Period.monthToDate()))
                val e = Calc.expenses(d)
                AssistantAnswer(
                    "Expenses, ${d.period.label.lowercase()}: ${Money.format(e.total)}" + (if (e.unpaid > 0) " (${Money.format(e.unpaid)} unpaid)" else "") + ".\n" +
                        e.byCategory.take(5).joinToString("\n") { "• ${it.first}: ${Money.format(it.second, false)}" },
                    "Expense records dated in the period.",
                )
            }

            has(q, "profit", "margin", "earn", "make money") -> {
                val d = analytics.load(periodFrom(q, Period.lastDays(30)))
                val s = Calc.summary(d)
                val e = Calc.expenses(d)
                AssistantAnswer(
                    "${d.period.label}: revenue ${Money.format(s.revenue)}, cost of goods ${Money.format(s.cogs)}, gross profit ${Money.format(s.grossProfit)} (${Money.ratio(s.grossProfit, s.revenue)} margin).\n" +
                        "After recorded expenses of ${Money.format(e.total)}, estimated operating profit is ${Money.format(s.grossProfit - e.total)}." +
                        if (s.linesWithoutCost > 0) "\nNote: ${s.linesWithoutCost} sale lines had no cost recorded, so profit is overstated." else "",
                    "Revenue excl. tax − unit cost at time of sale; expenses by date. Ingredient purchases are inventory, not expenses.",
                )
            }

            has(q, "cash", "money in", "received", "bank") -> {
                val d = analytics.load(periodFrom(q, Period.monthToDate()))
                val c = Calc.cashFlow(d)
                AssistantAnswer(
                    "Cash flow, ${d.period.label.lowercase()}: received ${Money.format(c.totalIn)}, paid out ${Money.format(c.totalOut)} (suppliers ${Money.format(c.outflowPurchases, false)}, expenses ${Money.format(c.outflowExpenses, false)}, refunds ${Money.format(c.outflowRefunds, false)}). Net ${Money.format(c.net)}.",
                    "Actual payment records by date (credit sales count only when paid).",
                )
            }

            has(q, "transaction", "bills", "orders", "average", "ticket", "basket") -> {
                val d = analytics.load(periodFrom(q, Period.today()))
                val s = Calc.summary(d)
                AssistantAnswer("${d.period.label}: ${s.transactions} transactions, average bill ${Money.format(s.avgTicket)}, ${s.units} items sold.", "Completed sales in the period.")
            }

            has(q, "sale", "revenue", "turnover", "how did", "how was", "how were", "doing", "business") -> {
                val p = periodFrom(q, Period.today())
                val s = Calc.summary(analytics.load(p))
                val compare = has(q, " vs", "versus", "compare", "compared")
                val prev = if (p.label == "This month") Period.lastMonth() else p.previous()
                val ps = Calc.summary(analytics.load(prev))
                val chg = Calc.change(s.revenue, ps.revenue)
                AssistantAnswer(
                    "${p.label}: revenue ${Money.format(s.revenue)} (excl. tax) from ${s.transactions} sales, average bill ${Money.format(s.avgTicket)}." +
                        (if (s.outstanding > 0) " ${Money.format(s.outstanding)} of it is not yet paid." else "") +
                        (if (s.reversedCount > 0) " ${s.reversedCount} sale(s) reversed." else "") +
                        "\n${prev.label.replaceFirstChar { it.uppercase() }}: ${Money.format(ps.revenue)}" + (chg?.let { " (${if (it >= 0) "+" else ""}${"%.0f".format(it)}%)" } ?: "") + "." +
                        if (compare && p.label == "This month") "\nNote: this month is incomplete, so it is compared with a full previous month." else "",
                    "Completed sales; revenue is net of discounts and excludes tax.",
                )
            }

            else -> AssistantAnswer(
                "I couldn't match that to a question I can answer from your records. Try asking about sales, best sellers, stock, expenses, profit, cash, money owed or a forecast.",
                "Built-in question rules — no AI language model is configured in this build.",
                understood = false,
            )
        }
    }
}
