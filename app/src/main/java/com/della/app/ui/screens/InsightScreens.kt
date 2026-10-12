package com.della.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.domain.Dates
import com.della.app.domain.ForecastResult
import com.della.app.domain.Insight
import com.della.app.domain.InsightRules
import com.della.app.domain.Money
import com.della.app.domain.Severity
import com.della.app.ui.Routes
import com.della.app.ui.common.ChipRow
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.EmptyState
import com.della.app.ui.common.InfoRow
import com.della.app.ui.common.LineChart
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Panel
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(c: AppContainer) : DellaViewModel() {
    val insights = c.analytics.changes().mapLatest { c.insights.generate() }.flowOn(Dispatchers.Default).state(null)
}

fun severityColor(s: Severity) = when (s) { Severity.HIGH -> Della.Danger; Severity.MEDIUM -> Della.Warning; Severity.INFO -> Della.Info }

@Composable
fun InsightCard(i: Insight, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    var expanded by rememberSaveable(i.key) { mutableStateOf(false) }
    Panel(onClick = onClick ?: { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(severityColor(i.severity), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(i.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = Della.TextMuted, modifier = Modifier.weight(1f))
            Text(i.severity.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = severityColor(i.severity))
        }
        Spacer(Modifier.height(6.dp))
        Text(i.title, style = MaterialTheme.typography.titleMedium, color = Della.Text)
        if (compact) {
            Text(i.action, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, maxLines = 2)
            return@Panel
        }
        Spacer(Modifier.height(8.dp))
        Text("Observed", style = MaterialTheme.typography.labelMedium, color = Della.Accent)
        i.observed.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Della.Text) }
        if (i.inference != null) {
            Spacer(Modifier.height(8.dp))
            Text("Possible explanation (inferred, not verified)", style = MaterialTheme.typography.labelMedium, color = Della.Warning)
            Text(i.inference, style = MaterialTheme.typography.bodyMedium, color = Della.TextMuted, fontStyle = FontStyle.Italic)
        }
        Spacer(Modifier.height(8.dp))
        Text("Recommended action", style = MaterialTheme.typography.labelMedium, color = Della.Accent)
        Text(i.action, style = MaterialTheme.typography.bodyMedium, color = Della.Text)
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text("Method", style = MaterialTheme.typography.labelMedium, color = Della.TextMuted)
            Text(i.method, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
        } else {
            Text("Tap for method", style = MaterialTheme.typography.labelSmall, color = Della.TextFaint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun InsightsScreen() {
    val vm = dellaViewModel { InsightsViewModel(it) }
    val nav = LocalNav.current
    val insights by vm.insights.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("All") }
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Insights", subtitle = "Rule-based · computed from your records") {
            IconButton(onClick = { nav.navigate(Routes.FORECAST) }) { Icon(Icons.Outlined.Timeline, "Forecast") }
        }
        val list = insights
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (list == null) { Text("Evaluating rules…", color = Della.TextMuted); return@Column }
            val cats = listOf("All") + list.map { it.category }.distinct()
            ChipRow(cats, filter, { it }, { filter = it })
            val shown = list.filter { filter == "All" || it.category == filter }
            if (shown.isEmpty()) EmptyState(Icons.Outlined.CheckCircle, "Nothing flagged", "No rule thresholds are crossed right now. Rules are listed below.")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { shown.forEach { InsightCard(it) } }
            SectionLabel("Forecast")
            SecondaryButton("Open sales forecast", Modifier.fillMaxWidth()) { nav.navigate(Routes.FORECAST) }
            SectionLabel("How insights are produced")
            Panel {
                InsightRules.description.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, modifier = Modifier.padding(vertical = 2.dp)) }
                Spacer(Modifier.height(6.dp))
                Text("Insights never change your records. Facts are calculated; explanations marked 'inferred' are possibilities to check.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)
            }
        }
    }
}

@Composable
fun AlertsScreen() {
    val vm = dellaViewModel { InsightsViewModel(it) }
    val nav = LocalNav.current
    val insights by vm.insights.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Alerts", onBack = { nav.popBackStack() }, subtitle = "Items needing attention")
        val list = insights?.filter { it.severity != Severity.INFO }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                list == null -> Text("Checking…", color = Della.TextMuted)
                list.isEmpty() -> EmptyState(Icons.Outlined.CheckCircle, "All clear", "No high or medium alerts from current data.")
                else -> list.forEach { i ->
                    InsightCard(i, compact = true) {
                        when {
                            i.key.startsWith("stock-") -> nav.navigate(Routes.stockItem(i.key.removePrefix("stock-").toLong()))
                            i.key.startsWith("buy-") -> nav.navigate(Routes.PO_NEW)
                            i.key.startsWith("margin-") -> nav.navigate(Routes.product(i.key.removePrefix("margin-").toLong()))
                            i.key.startsWith("exp-") -> nav.navigate(Routes.EXPENSES)
                            i.key == "receivables" -> nav.navigate(Routes.CUSTOMERS)
                            else -> nav.navigate(Routes.INSIGHTS)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ForecastViewModel(c: AppContainer) : DellaViewModel() {
    val result = c.analytics.changes().mapLatest { c.forecast.forecast() }.flowOn(Dispatchers.Default).state(null)
}

@Composable
fun ForecastScreen() {
    val vm = dellaViewModel { ForecastViewModel(it) }
    val nav = LocalNav.current
    val r: ForecastResult? by vm.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Forecast", onBack = { nav.popBackStack() }, subtitle = "Transparent, simple projection")
        val f = r
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (f == null) { Text("Calculating…", color = Della.TextMuted); return@Column }
            if (!f.available) {
                EmptyState(Icons.Outlined.Timeline, "Not enough history", f.reason)
                return@Column
            }
            SectionLabel("Next ${f.days.size} days revenue (excl. tax)")
            Panel {
                Text(Money.format(f.days.sumOf { it.expected }, false), style = MaterialTheme.typography.displaySmall)
                Text("Range ${Money.format(f.days.sumOf { it.low }, false)} – ${Money.format(f.days.sumOf { it.high }, false)}", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                Spacer(Modifier.height(12.dp))
                LineChart(
                    values = f.days.map { it.expected }, labels = f.days.map { it.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } },
                    low = f.days.map { it.low }, high = f.days.map { it.high }, format = { Money.compact(it) },
                )
                Spacer(Modifier.height(10.dp))
                f.days.forEach { d ->
                    InfoRow("${Dates.short(d.date)} (${d.samples} wks of data)", "${Money.format(d.expected, false)}  ·  ${Money.compact(d.low)}–${Money.compact(d.high)}")
                }
            }
            SectionLabel("Track record")
            Panel {
                if (f.backtestMape != null) {
                    Text("Off by ${"%.0f".format(f.backtestMape)}% on average", style = MaterialTheme.typography.titleMedium)
                    Text("Measured by forecasting each of the last ${f.backtestDays} complete days using only data available before that day, then comparing with actual revenue.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                } else Text("Not enough recent days to measure accuracy yet.", color = Della.TextMuted)
            }
            SectionLabel("Product demand · next 7 days")
            Panel {
                f.demand.take(12).forEachIndexed { i, d ->
                    val cover = d.coverDays
                    InfoRow(d.name, "~${d.next7} ${d.unit} · stock ${d.stock}" + (cover?.let { " (${"%.1f".format(it)}d)" } ?: ""), if (cover != null && cover < 2) Della.Warning else Della.Text)
                    if (i < minOf(11, f.demand.lastIndex)) Divider()
                }
            }
            SectionLabel("Assumptions")
            Panel { f.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, modifier = Modifier.padding(vertical = 2.dp)) } }
            Text("Based on ${f.historyDays} days of history. Forecasts are estimates, not guarantees.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
