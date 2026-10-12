package com.della.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.data.db.AuditEntry
import com.della.app.domain.Calc
import com.della.app.domain.CashFlow
import com.della.app.domain.Dates
import com.della.app.domain.DayValue
import com.della.app.domain.ExpenseSummary
import com.della.app.domain.Insight
import com.della.app.domain.InventoryHealth
import com.della.app.domain.Money
import com.della.app.domain.Period
import com.della.app.domain.SalesSummary
import com.della.app.domain.Severity
import com.della.app.ui.Routes
import com.della.app.ui.common.BarChart
import com.della.app.ui.common.BrandTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.EmptyState
import com.della.app.ui.common.ListRow
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Metric
import com.della.app.ui.common.MetricRow
import com.della.app.ui.common.Panel
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

data class OverviewState(
    val businessName: String,
    val configured: Boolean,
    val today: SalesSummary,
    val yesterday: SalesSummary,
    val month: SalesSummary,
    val trend: List<DayValue>,
    val monthExpenses: ExpenseSummary,
    val monthCash: CashFlow,
    val inventory: InventoryHealth,
    val insights: List<Insight>,
    val hasAnyData: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModel(private val c: AppContainer) : DellaViewModel() {
    val state = c.analytics.changes().mapLatest {
        val today = c.analytics.load(Period.today())
        val month = c.analytics.load(Period.monthToDate())
        val trend = c.analytics.load(Period.lastDays(14))
        val profile = c.settings.get()
        OverviewState(
            businessName = profile?.businessName ?: "Your business",
            configured = profile?.configured == true,
            today = Calc.summary(today),
            yesterday = Calc.summary(c.analytics.load(Period.yesterday())),
            month = Calc.summary(month),
            trend = Calc.daily(trend),
            monthExpenses = Calc.expenses(month),
            monthCash = Calc.cashFlow(month),
            inventory = Calc.inventory(today.products),
            insights = c.insights.generate(),
            hasAnyData = today.products.isNotEmpty() || month.sales.isNotEmpty(),
        )
    }.flowOn(Dispatchers.Default).state(null)

    val activity = c.db.auditDao().observe(6).state(emptyList())
}

@Composable
fun OverviewScreen() {
    val vm = dellaViewModel { OverviewViewModel(it) }
    val nav = LocalNav.current
    val s by vm.state.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val st = s

    Column(Modifier.fillMaxSize()) {
        BrandTopBar(subtitle = st?.businessName ?: " ") {
            IconButton(onClick = { nav.navigate(Routes.ASSISTANT) }) { Icon(Icons.Outlined.ChatBubbleOutline, "Ask DELLA") }
            IconButton(onClick = { nav.navigate(Routes.ALERTS) }) {
                val n = st?.insights?.count { it.severity != Severity.INFO } ?: 0
                BadgedBox(badge = { if (n > 0) Badge(containerColor = Della.Warning, contentColor = Della.Background) { Text("$n") } }) {
                    Icon(Icons.Outlined.NotificationsNone, "Alerts")
                }
            }
        }
        if (st == null) {
            Text("Calculating from your records…", color = Della.TextMuted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (!st.configured) {
                Spacer(Modifier.height(8.dp))
                Panel(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Text("Set up your business", style = MaterialTheme.typography.titleMedium)
                    Text("Add your business name and choose how tax is applied before your first sale.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                }
            }
            if (!st.hasAnyData) {
                EmptyState(Icons.Outlined.ChatBubbleOutline, "No records yet", "Add products, then make sales from Sell. Every figure here is calculated from what you record.", action = "Add products", onAction = { nav.navigate(Routes.PRODUCTS) })
                return@Column
            }

            // Today ------------------------------------------------------------
            SectionLabel("Today · ${Dates.short(Dates.today())}")
            val chg = Calc.change(st.today.revenue, st.yesterday.revenue)
            Panel {
                Text("Revenue", style = MaterialTheme.typography.labelMedium, color = Della.TextMuted)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Money.format(st.today.revenue, false), style = MaterialTheme.typography.displaySmall, color = Della.Text)
                    Spacer(Modifier.padding(4.dp))
                    Text("excl. tax", style = MaterialTheme.typography.labelSmall, color = Della.TextFaint, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    "Yesterday ${Money.format(st.yesterday.revenue, false)}" + (chg?.let { " · ${if (it >= 0) "+" else ""}${"%.0f".format(it)}% so far" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Della.TextMuted,
                )
                Spacer(Modifier.height(14.dp))
                Divider()
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    MiniStat("Sales", "${st.today.transactions}", Modifier.weight(1f))
                    MiniStat("Avg bill", Money.format(st.today.avgTicket, false), Modifier.weight(1f))
                    MiniStat("Items", "${st.today.units}", Modifier.weight(1f))
                    MiniStat("Unpaid", Money.format(st.today.outstanding, false), Modifier.weight(1f))
                }
            }

            // Trend --------------------------------------------------------------
            SectionLabel("Last 14 days", action = "Analytics", onAction = { nav.navigate(Routes.ANALYTICS) })
            Panel {
                BarChart(
                    values = st.trend.map { it.value }, labels = st.trend.map { it.date.dayOfMonth.toString() },
                    format = { Money.compact(it) }, highlightLast = true,
                )
                Spacer(Modifier.height(6.dp))
                Text("Daily revenue excl. tax. Highlighted bar is today (in progress).", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)
            }

            // Month -------------------------------------------------------------
            SectionLabel("This month")
            MetricRow {
                Metric("Revenue", Money.compact(st.month.revenue), Modifier.weight(1f), sub = "${st.month.transactions} sales")
                Metric("Gross profit", Money.compact(st.month.grossProfit), Modifier.weight(1f), sub = "${Money.ratio(st.month.grossProfit, st.month.revenue)} margin · est.")
            }
            Spacer(Modifier.height(10.dp))
            MetricRow {
                Metric("Expenses", Money.compact(st.monthExpenses.total), Modifier.weight(1f), sub = if (st.monthExpenses.unpaid > 0) "${Money.compact(st.monthExpenses.unpaid)} unpaid" else "all paid", onClick = { nav.navigate(Routes.EXPENSES) })
                Metric("Net cash", Money.compact(st.monthCash.net), Modifier.weight(1f), sub = "in ${Money.compact(st.monthCash.totalIn)} · out ${Money.compact(st.monthCash.totalOut)}", subColor = if (st.monthCash.net < 0) Della.Danger else Della.TextMuted)
            }
            Spacer(Modifier.height(6.dp))
            Text("Gross profit = revenue − recorded cost of items sold. Cash counts money actually received and paid, so credit sales appear only when paid.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)

            // Inventory -----------------------------------------------------------
            SectionLabel("Inventory health", action = "Stock", onAction = { nav.navigate(Routes.INVENTORY) })
            MetricRow {
                Metric("Low", "${st.inventory.lowStock.size}", Modifier.weight(1f), sub = "at/below reorder", subColor = if (st.inventory.lowStock.isNotEmpty()) Della.Warning else Della.TextMuted, onClick = { nav.navigate(Routes.INVENTORY) })
                Metric("Out", "${st.inventory.outOfStock.size}", Modifier.weight(1f), sub = "of ${st.inventory.activeItems} items", subColor = if (st.inventory.outOfStock.isNotEmpty()) Della.Danger else Della.TextMuted, onClick = { nav.navigate(Routes.INVENTORY) })
                Metric("Value", Money.compact(st.inventory.valueAtCost), Modifier.weight(1f), sub = "at cost")
            }

            // Insights ------------------------------------------------------------
            SectionLabel("Insights", action = "All", onAction = { nav.navigate(Routes.INSIGHTS) })
            if (st.insights.isEmpty()) {
                Panel { Text("No rule is flagged right now.", color = Della.TextMuted, style = MaterialTheme.typography.bodyMedium) }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                st.insights.take(3).forEach { InsightCard(it, compact = true) { nav.navigate(Routes.INSIGHTS) } }
            }

            // Activity ------------------------------------------------------------
            SectionLabel("Recent activity", action = "Audit log", onAction = { nav.navigate(Routes.AUDIT) })
            Panel(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                activity.forEachIndexed { i, a: AuditEntry ->
                    ListRow(a.summary, "${Dates.dayTime(a.createdAt)} · ${a.action.replace('_', ' ').lowercase()}")
                    if (i < activity.lastIndex) Divider()
                }
            }
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Della.TextMuted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = Della.Text, maxLines = 1)
    }
}
