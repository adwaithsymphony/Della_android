package com.della.app.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.data.db.PayMethod
import com.della.app.data.db.Payment
import com.della.app.data.db.SaleStatus
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
import com.della.app.ui.common.InfoRow
import com.della.app.ui.common.ListRow
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Messages
import com.della.app.ui.common.Panel
import com.della.app.ui.common.Pill
import com.della.app.ui.common.SearchField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.flow.MutableStateFlow

class SalesHistoryViewModel(c: AppContainer) : DellaViewModel() {
    val sales = c.sales.observeRecent(1000).state(emptyList())
}

@Composable
fun SalesHistoryScreen() {
    val vm = dellaViewModel { SalesHistoryViewModel(it) }
    val nav = LocalNav.current
    val sales by vm.sales.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val shown = sales.filter { r ->
        (query.isBlank() || r.sale.number.contains(query, true) || (r.customerName?.contains(query, true) == true)) &&
            when (filter) {
                "Unpaid" -> r.sale.status == SaleStatus.COMPLETED && r.sale.totalPaise > r.sale.paidPaise
                "Reversed" -> r.sale.status == SaleStatus.REVERSED
                else -> true
            }
    }
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Sales history", onBack = { nav.popBackStack() }, subtitle = "Latest ${sales.size} sales")
        Column(Modifier.padding(horizontal = 16.dp)) {
            SearchField(query, { query = it }, "Sale number or customer")
            ChipRow(listOf("All", "Unpaid", "Reversed"), filter, { it }, { filter = it })
        }
        if (shown.isEmpty()) EmptyState(Icons.Outlined.ReceiptLong, "No sales", "Completed sales appear here.")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(shown, key = { it.sale.id }) { r ->
                val s = r.sale
                ListRow(
                    title = "${s.number} · ${Money.format(s.totalPaise)}",
                    subtitle = "${Dates.dayTime(s.createdAt)} · ${s.paymentMethod.lowercase()}" + (r.customerName?.let { " · $it" } ?: ""),
                    trailing = when {
                        s.status == SaleStatus.REVERSED -> "Reversed"
                        s.totalPaise > s.paidPaise -> "Due ${Money.format(s.totalPaise - s.paidPaise, false)}"
                        else -> "Paid"
                    },
                    trailingColor = when {
                        s.status == SaleStatus.REVERSED -> Della.Danger
                        s.totalPaise > s.paidPaise -> Della.Warning
                        else -> Della.Accent
                    },
                    onClick = { nav.navigate(Routes.sale(s.id)) },
                )
                Divider()
            }
        }
    }
}

class SaleDetailViewModel(private val c: AppContainer, val id: Long) : DellaViewModel() {
    val sale = c.sales.observeSale(id).state(null)
    val lines = c.sales.observeLines(id).state(emptyList())
    val profile = c.settings.observe().state(null)
    val payments = MutableStateFlow<List<Payment>>(emptyList())
    val customerName = MutableStateFlow<String?>(null)

    suspend fun refresh() {
        payments.value = c.sales.paymentsFor(id).sortedBy { it.createdAt }
        c.db.salesDao().sale(id)?.customerId?.let { cid -> customerName.value = c.db.partyDao().customer(cid)?.name }
    }

    fun reverse(reason: String, method: String) = act("Sale reversed. Stock restored and refund recorded.") {
        c.sales.reverse(id, reason, method)
        refresh()
    }
}

@Composable
fun SaleDetailScreen(id: Long) {
    val vm = dellaViewModel("sale-$id") { SaleDetailViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val context = LocalContext.current
    val sale by vm.sale.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val customerName by vm.customerName.collectAsStateWithLifecycle()
    var showReverse by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var refundMethod by remember { mutableStateOf(PayMethod.CASH) }
    LaunchedEffect(sale?.status, sale?.paidPaise) { vm.refresh() }

    val s = sale
    Column(Modifier.fillMaxSize()) {
        DellaTopBar(s?.number ?: "Sale", onBack = { nav.popBackStack() }) {
            if (s != null) IconButton(onClick = {
                val text = buildString {
                    appendLine(profile?.businessName ?: "Receipt")
                    if (!profile?.address.isNullOrBlank()) appendLine(profile?.address)
                    if (!profile?.gstin.isNullOrBlank()) appendLine("GSTIN ${profile?.gstin}")
                    appendLine("${s.number} · ${Dates.dayTime(s.createdAt)}")
                    if (s.status == SaleStatus.REVERSED) appendLine("*** REVERSED ***")
                    appendLine("------------------------------")
                    lines.forEach { appendLine("${it.productName} x${it.qty}  ${Money.format(it.unitPricePaise * it.qty)}") }
                    appendLine("------------------------------")
                    if (s.discountPaise > 0) appendLine("Discount  −${Money.format(s.discountPaise)}")
                    if (s.taxPaise > 0) appendLine("Tax  ${Money.format(s.taxPaise)}" + if (s.taxMode == "INCLUSIVE") " (included)" else "")
                    appendLine("TOTAL  ${Money.format(s.totalPaise)}")
                    appendLine("Paid  ${Money.format(s.paidPaise)} (${s.paymentMethod.lowercase()})")
                    if (s.totalPaise > s.paidPaise && s.status == SaleStatus.COMPLETED) appendLine("Balance due  ${Money.format(s.totalPaise - s.paidPaise)}")
                    profile?.receiptFooter?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share receipt"))
            }) { Icon(Icons.Outlined.Share, "Share receipt") }
        }
        if (s == null) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(profile?.businessName ?: "", style = MaterialTheme.typography.titleLarge)
                    if (!profile?.gstin.isNullOrBlank()) Text("GSTIN ${profile?.gstin}", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                    Text("${s.number} · ${Dates.dayTime(s.createdAt)}", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                    customerName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted) }
                    Spacer(Modifier.height(6.dp))
                    when {
                        s.status == SaleStatus.REVERSED -> Pill("REVERSED", Della.Danger)
                        s.totalPaise > s.paidPaise -> Pill("BALANCE DUE", Della.Warning)
                        else -> Pill("PAID")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Divider()
                lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(l.productName, style = MaterialTheme.typography.bodyMedium)
                            Text("${l.qty} × ${Money.format(l.unitPricePaise)}" + (if (l.discountPaise > 0) " · disc ${Money.format(l.discountPaise)}" else "") + (if (l.taxPaise > 0) " · tax ${Money.pct(l.taxBps)}" else ""), style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                        }
                        Text(Money.format(l.totalPaise), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
                Divider()
                InfoRow("Items", Money.format(s.grossPaise))
                if (s.discountPaise > 0) InfoRow("Discount", "−" + Money.format(s.discountPaise))
                InfoRow("Revenue (excl. tax)", Money.format(s.netPaise))
                InfoRow(if (s.taxMode == "INCLUSIVE") "Tax (included)" else "Tax", Money.format(s.taxPaise))
                InfoRow("Total", Money.format(s.totalPaise), bold = true)
                InfoRow("Received", Money.format(s.paidPaise))
                if (s.status == SaleStatus.COMPLETED && s.totalPaise > s.paidPaise) InfoRow("Outstanding", Money.format(s.totalPaise - s.paidPaise), Della.Warning)
                profile?.receiptFooter?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            SectionLabel("Payments")
            Panel(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                if (payments.isEmpty()) Text("No money received yet.", color = Della.TextMuted, modifier = Modifier.padding(vertical = 10.dp))
                payments.forEachIndexed { i, p ->
                    ListRow(
                        "${if (p.direction == "IN") "Received" else "Refunded"} ${Money.format(p.amountPaise)}",
                        "${Dates.dayTime(p.createdAt)} · ${p.method.lowercase()} · ${p.note}",
                        trailingColor = if (p.direction == "IN") Della.Accent else Della.Danger,
                    )
                    if (i < payments.lastIndex) Divider()
                }
            }

            if (s.status == SaleStatus.REVERSED) {
                SectionLabel("Reversal")
                Panel {
                    InfoRow("Reversed", s.reversedAt?.let { Dates.dayTime(it) } ?: "")
                    InfoRow("Reason", s.reversalReason ?: "")
                    Text("The original record is kept. Stock was returned and any money received was refunded.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                }
            } else {
                Spacer(Modifier.height(20.dp))
                SecondaryButton("Reverse this sale", Modifier.fillMaxWidth(), color = Della.Danger) { showReverse = true }
                Text("Use for mistakes, cancellations or full refunds. The sale stays in history marked as reversed.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    if (showReverse && s != null) {
        ConfirmDialog(
            title = "Reverse ${s.number}?",
            body = "Returns ${lines.sumOf { it.qty }} items to stock" + if (s.paidPaise > 0) " and records a refund of ${Money.format(s.paidPaise)}." else ". Nothing was received, so no refund is recorded.",
            confirm = "Reverse sale", destructive = true, confirmEnabled = reason.isNotBlank(),
            onConfirm = { showReverse = false; vm.reverse(reason, refundMethod); reason = "" },
            onDismiss = { showReverse = false },
            content = {
                Field(reason, { reason = it }, "Reason (required)")
                if (s.paidPaise > 0) ChipRow(listOf(PayMethod.CASH, PayMethod.UPI, PayMethod.CARD), refundMethod, { "Refund by ${it.lowercase()}" }, { refundMethod = it })
            },
        )
    }
}
