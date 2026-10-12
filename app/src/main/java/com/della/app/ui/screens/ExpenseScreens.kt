package com.della.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.data.db.Expense
import com.della.app.data.db.ExpenseCategories
import com.della.app.data.db.PayMethod
import com.della.app.domain.Dates
import com.della.app.domain.Money
import com.della.app.ui.Routes
import com.della.app.ui.common.ChipRow
import com.della.app.ui.common.ConfirmDialog
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.EmptyState
import com.della.app.ui.common.Field
import com.della.app.ui.common.ListRow
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Messages
import com.della.app.ui.common.Metric
import com.della.app.ui.common.MetricRow
import com.della.app.ui.common.MoneyField
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.SelectField
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import java.time.LocalDate

class ExpensesViewModel(private val c: AppContainer) : DellaViewModel() {
    val expenses = c.finance.observeExpenses().state(emptyList())
    fun pay(id: Long, method: String) = act("Marked as paid.") { c.finance.payExpense(id, method) }
}

@Composable
fun ExpensesScreen() {
    val vm = dellaViewModel { ExpensesViewModel(it) }
    Messages(vm)
    val nav = LocalNav.current
    val all by vm.expenses.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("This month") }
    var paying by remember { mutableStateOf<Expense?>(null) }
    var method by remember { mutableStateOf(PayMethod.CASH) }
    val today = Dates.today()
    val shown = all.filter {
        val d = Dates.toDate(it.spentAt)
        when (filter) {
            "This month" -> d.year == today.year && d.month == today.month
            "Last month" -> today.withDayOfMonth(1).minusMonths(1).let { m -> d.year == m.year && d.month == m.month }
            "Unpaid" -> it.paidPaise < it.amountPaise
            else -> true
        }
    }
    Scaffold(
        containerColor = Della.Background,
        topBar = { DellaTopBar("Expenses", onBack = { nav.popBackStack() }, subtitle = "Running costs — separate from stock purchases") },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate(Routes.EXPENSE_NEW) }, containerColor = Della.Primary, contentColor = Della.Text) { Icon(Icons.Outlined.Add, "Add expense") } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ChipRow(listOf("This month", "Last month", "Unpaid", "All"), filter, { it }, { filter = it })
                MetricRow {
                    Metric("Total", Money.compact(shown.sumOf { it.amountPaise }), Modifier.weight(1f), sub = "${shown.size} entries")
                    Metric("Unpaid", Money.compact(shown.sumOf { it.amountPaise - it.paidPaise }), Modifier.weight(1f), subColor = Della.Warning, sub = "payables")
                }
            }
            if (shown.isEmpty()) EmptyState(Icons.Outlined.Payments, "No expenses", "Record rent, wages, utilities and other running costs.")
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp)) {
                items(shown, key = { it.id }) { e ->
                    val unpaid = e.amountPaise - e.paidPaise
                    ListRow(
                        e.description, "${e.category} · ${Dates.day(e.spentAt)}" + (if (e.vendor.isNotBlank()) " · ${e.vendor}" else "") + if (unpaid == 0L) " · ${e.method.lowercase()}" else "",
                        trailing = Money.format(e.amountPaise, false), trailingSub = if (unpaid > 0) "unpaid · tap to pay" else "paid",
                        trailingColor = if (unpaid > 0) Della.Warning else Della.Text,
                        onClick = if (unpaid > 0) ({ paying = e }) else null,
                    )
                    Divider()
                }
            }
        }
    }
    paying?.let { e ->
        ConfirmDialog(
            "Pay ${e.description}?", "Records ${Money.format(e.amountPaise - e.paidPaise)} paid today.", "Mark paid",
            onConfirm = { paying = null; vm.pay(e.id, method) }, onDismiss = { paying = null },
            content = { ChipRow(PayMethod.tender, method, { it.lowercase() }, { method = it }) },
        )
    }
}

class ExpenseEditViewModel(private val c: AppContainer) : DellaViewModel() {
    fun save(category: String, desc: String, vendor: String, amount: Long, paid: Boolean, method: String, date: LocalDate, onDone: () -> Unit) =
        act("Expense recorded.", onDone) {
            val at = if (date == Dates.today()) System.currentTimeMillis() else Dates.startOf(date) + 12 * 3_600_000L
            c.finance.addExpense(category, desc, vendor, amount, paid, method, at)
        }
}

@Composable
fun ExpenseEditScreen() {
    val vm = dellaViewModel { ExpenseEditViewModel(it) }
    Messages(vm)
    val nav = LocalNav.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var desc by rememberSaveable { mutableStateOf("") }
    var vendor by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var paid by rememberSaveable { mutableStateOf(true) }
    var method by rememberSaveable { mutableStateOf(PayMethod.CASH) }
    var daysAgo by rememberSaveable { mutableStateOf(0) }
    val date = Dates.today().minusDays(daysAgo.toLong())
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("New expense", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SelectField("Category", ExpenseCategories.all, category, { it }, { category = it })
            Field(desc, { desc = it }, "Description")
            Field(vendor, { vendor = it }, "Paid to (optional)")
            MoneyField(amount, { amount = it }, "Amount")
            SectionLabel("Date")
            ChipRow((0..6).toList(), daysAgo, { if (it == 0) "Today" else if (it == 1) "Yesterday" else Dates.short(Dates.today().minusDays(it.toLong())) }, { daysAgo = it })
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Paid now", style = MaterialTheme.typography.bodyLarge)
                    Text(if (paid) "Recorded as cash out today" else "Recorded as unpaid (payable)", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                }
                Switch(paid, { paid = it }, colors = SwitchDefaults.colors(checkedTrackColor = Della.Primary, checkedThumbColor = Della.Text))
            }
            if (paid) ChipRow(PayMethod.tender, method, { it.lowercase() }, { method = it })
            Spacer(Modifier.height(8.dp))
            Text("Buying ingredients or stock? Use a purchase order instead so stock and costs stay accurate.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)
        }
        PrimaryButton(if (busy) "Saving…" else "Save expense", Modifier.fillMaxWidth().padding(16.dp), enabled = !busy) {
            vm.save(category ?: "", desc, vendor, Money.parse(amount) ?: 0, paid, method, date) { nav.popBackStack() }
        }
    }
}
