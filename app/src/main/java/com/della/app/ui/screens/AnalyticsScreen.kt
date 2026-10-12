package com.della.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.domain.Calc
import com.della.app.domain.CashFlow
import com.della.app.domain.DayValue
import com.della.app.domain.ExpenseSummary
import com.della.app.domain.Money
import com.della.app.domain.MovementSummary
import com.della.app.domain.Period
import com.della.app.domain.ProductPerformance
import com.della.app.domain.PurchasingSummary
import com.della.app.domain.SalesSummary
import com.della.app.ui.common.BarChart
import com.della.app.ui.common.ChipRow
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.HorizontalBars
import com.della.app.ui.common.InfoRow
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Metric
import com.della.app.ui.common.MetricRow
import com.della.app.ui.common.Panel
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

data class AnalyticsState(
    val period: Period,
    val summary: SalesSummary,
    val previous: SalesSummary,
    val daily: List<DayValue>,
    val products: List<ProductPerformance>,
    val categories: List<Pair<String, Long>>,
    val expenses: ExpenseSummary,
    val cash: CashFlow,
    val movements: List<MovementSummary>,
    val purchasing: PurchasingSummary,
    val hourly: List<Pair<Int, Int>>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(private val c: AppContainer) : DellaViewModel() {
    val periodKey = MutableStateFlow("30 days")

    fun periodFor(key: String) = when (key) {
        "7 days" -> Period.lastDays(7)
        "This month" -> Period.monthToDate()
        "Last month" -> Period.lastMonth()
        "90 days" -> Period.lastDays(90)
        else -> Period.lastDays(30)
    }

    val state = combine(c.analytics.changes(), periodKey) { _, k -> k }.mapLatest { key ->
        val p = periodFor(key)
        val d = c.analytics.load(p)
        val prev = c.analytics.load(p.previous())
        val catOf = d.products.associate { it.product.id to (it.categoryName ?: "Uncategorised") }
        AnalyticsState(
            period = p,
            summary = Calc.summary(d),
            previous = Calc.summary(prev),
            daily = Calc.daily(d),
            products = Calc.productPerformance(d),
            categories = Calc.productPerformance(d).groupBy { catOf[it.productId] ?: "Other" }.map { (k, v) -> k to v.sumOf { it.revenue } }.sortedByDescending { it.second },
            expenses = Calc.expenses(d),
            cash = Calc.cashFlow(d),
            movements = Calc.movements(d),
            purchasing = c.analytics.purchasing(p),
            hourly = Calc.hourly(d),
        )
    }.flowOn(Dispatchers.Default).state(null)
}

@Composable
fun AnalyticsScreen() {
    val vm = dellaViewModel { AnalyticsViewModel(it) }
    val nav = LocalNav.current
    val key by vm.periodKey.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Analytics", onBack = { nav.popBackStack() }, subtitle = s?.period?.let { "${it.label} · vs previous period" })
        Column(Modifier.padding(horizontal = 16.dp)) {
            ChipRow(listOf("7 days", "30 days", "This month", "Last month", "90 days"), key, { it }, { vm.periodKey.value = it })
        }
        val st = s ?: return@Column
        val sm = st.summary
        fun delta(a: Long, b: Long) = Calc.change(a, b)?.let { "${if (it >= 0) "+" else ""}${"%.0f".format(it)}% vs prev." } ?: "no prior data"
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionLabel("Sales")
            MetricRow {
                Metric("Revenue (excl. tax)", Money.compact(sm.revenue), Modifier.weight(1f), sub = delta(sm.revenue, st.previous.revenue))
                Metric("Transactions", "${sm.transactions}", Modifier.weight(1f), sub = delta(sm.transactions.toLong(), st.previous.transactions.toLong()))
            }
            Spacer(Modifier.height(10.dp))
            MetricRow {
                Metric("Average bill", Money.format(sm.avgTicket, false), Modifier.weight(1f), sub = delta(sm.avgTicket, st.previous.avgTicket))
                Metric("Gross margin", Money.ratio(sm.grossProfit, sm.revenue), Modifier.weight(1f), sub = "${Money.compact(sm.grossProfit)} gross profit")
            }
            Spacer(Modifier.height(10.dp))
            Panel {
                BarChart(st.daily.map { it.value }, st.daily.map { it.date.dayOfMonth.toString() }, { Money.compact(it) }, highlightLast = st.period.to == com.della.app.domain.Dates.today())
                Spacer(Modifier.height(10.dp))
                InfoRow("Billed incl. tax", Money.format(sm.billed))
                InfoRow("Tax collected", Money.format(sm.tax))
                InfoRow("Discounts given", Money.format(sm.discounts))
                InfoRow("Cost of goods sold", Money.format(sm.cogs))
                InfoRow("Gross profit (estimated)", Money.format(sm.grossProfit), bold = true)
                InfoRow("Not yet received (credit)", Money.format(sm.outstanding), if (sm.outstanding > 0) Della.Warning else Della.Text)
                InfoRow("Reversed sales", "${sm.reversedCount} · ${Money.format(sm.reversedValue)}")
                if (sm.linesWithoutCost > 0) Text("${sm.linesWithoutCost} sale lines had no cost recorded; gross profit is overstated.", style = MaterialTheme.typography.bodySmall, color = Della.Warning)
                if (st.period.to == com.della.app.domain.Dates.today()) Text("Period includes today, which is still in progress.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)
            }

            SectionLabel("Best sellers by revenue")
            Panel {
                if (st.products.isEmpty()) Text("No sales in this period.", color = Della.TextMuted)
                HorizontalBars(st.products.take(8).map { it.name to it.revenue }, { Money.compact(it) })
            }
            SectionLabel("Product margins")
            Panel {
                st.products.take(10).forEachIndexed { i, p ->
                    InfoRow("${p.name} · ${p.units} sold", "${Money.compact(p.profit)} · ${Money.ratio(p.profit, p.revenue)}")
                    if (i < minOf(9, st.products.lastIndex)) Divider()
                }
            }
            SectionLabel("By category")
            Panel { HorizontalBars(st.categories, { Money.compact(it) }, color = Della.Accent) }

            SectionLabel("Busiest hours (transactions)")
            Panel {
                BarChart(st.hourly.map { it.second.toLong() }, st.hourly.map { "${it.first}" }, { "$it" }, height = 110)
            }

            SectionLabel("Expenses")
            Panel {
                InfoRow("Total recorded", Money.format(st.expenses.total), bold = true)
                InfoRow("Unpaid", Money.format(st.expenses.unpaid), if (st.expenses.unpaid > 0) Della.Warning else Della.Text)
                Spacer(Modifier.height(8.dp))
                HorizontalBars(st.expenses.byCategory, { Money.compact(it) }, color = Della.Warning)
                Spacer(Modifier.height(8.dp))
                InfoRow("Estimated operating profit", Money.format(sm.grossProfit - st.expenses.total), bold = true)
                Text("Gross profit minus expenses dated in the period. Ingredient purchases are stock, counted as cost only when items are sold.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)
            }

            SectionLabel("Cash flow (actual payments)")
            Panel {
                InfoRow("Received from customers", Money.format(st.cash.inflowSales), Della.Accent)
                InfoRow("Paid to suppliers", "−" + Money.format(st.cash.outflowPurchases))
                InfoRow("Paid expenses", "−" + Money.format(st.cash.outflowExpenses))
                InfoRow("Refunds", "−" + Money.format(st.cash.outflowRefunds))
                Divider()
                InfoRow("Net cash movement", Money.format(st.cash.net), if (st.cash.net < 0) Della.Danger else Della.Accent, bold = true)
            }

            SectionLabel("Inventory movement")
            Panel {
                st.movements.forEach { m -> InfoRow("${movementLabel(m.type)} (${m.count})", "${if (m.qty > 0) "+" else ""}${m.qty} units · ${Money.compact(m.valueAtCost)}") }
                if (st.movements.isEmpty()) Text("No stock movements.", color = Della.TextMuted)
            }

            SectionLabel("Purchasing")
            Panel {
                InfoRow("Goods received", "${st.purchasing.receipts} deliveries · ${Money.format(st.purchasing.receivedValue)}")
                InfoRow("Open orders (all time)", "${st.purchasing.openOrders} · ${Money.format(st.purchasing.openOrderValue)} outstanding")
                InfoRow("Owed to suppliers", Money.format(st.purchasing.payables), if (st.purchasing.payables > 0) Della.Warning else Della.Text)
            }
        }
    }
}
