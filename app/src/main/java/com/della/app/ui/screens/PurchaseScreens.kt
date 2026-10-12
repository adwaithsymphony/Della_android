package com.della.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.data.db.PayMethod
import com.della.app.data.db.PoStatus
import com.della.app.data.db.ProductRow
import com.della.app.data.db.Supplier
import com.della.app.data.repo.PoLineDraft
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
import com.della.app.ui.common.MoneyField
import com.della.app.ui.common.Panel
import com.della.app.ui.common.Pill
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.QtyField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.SelectField
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.flow.MutableStateFlow

fun poStatusColor(s: String) = when (s) {
    PoStatus.ORDERED -> Della.Info; PoStatus.PARTIAL -> Della.Warning; PoStatus.RECEIVED -> Della.Accent; else -> Della.TextFaint
}

class PurchasesViewModel(c: AppContainer) : DellaViewModel() {
    val orders = c.purchases.observeAll().state(emptyList())
}

@Composable
fun PurchasesScreen() {
    val vm = dellaViewModel { PurchasesViewModel(it) }
    val nav = LocalNav.current
    val orders by vm.orders.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("Open") }
    val shown = orders.filter {
        when (filter) {
            "Open" -> it.po.status == PoStatus.ORDERED || it.po.status == PoStatus.PARTIAL
            "Unpaid" -> it.po.status != PoStatus.CANCELLED && it.po.totalPaise > it.po.paidPaise
            else -> true
        }
    }
    Scaffold(
        containerColor = Della.Background,
        topBar = { DellaTopBar("Purchase orders", onBack = { nav.popBackStack() }, subtitle = "Stock increases only when goods are received") },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate(Routes.PO_NEW) }, containerColor = Della.Primary, contentColor = Della.Text) { Icon(Icons.Outlined.Add, "New order") } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ChipRow(listOf("Open", "Unpaid", "All"), filter, { it }, { filter = it }, Modifier.padding(horizontal = 16.dp))
            if (shown.isEmpty()) EmptyState(Icons.Outlined.LocalShipping, "No orders here", "Create an order to a supplier, then receive goods when they arrive.")
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                items(shown, key = { it.po.id }) { r ->
                    val po = r.po
                    ListRow(
                        "${po.number} · ${r.supplierName}",
                        "${Dates.day(po.createdAt)} · ${po.status.lowercase()}" + (if (po.supplierInvoiceNo.isNotBlank()) " · inv ${po.supplierInvoiceNo}" else "") +
                            if (po.totalPaise > po.paidPaise && po.status != PoStatus.CANCELLED) " · ${Money.format(po.totalPaise - po.paidPaise, false)} unpaid" else "",
                        trailing = Money.format(po.totalPaise, false), trailingSub = po.status.lowercase(), trailingColor = poStatusColor(po.status),
                        onClick = { nav.navigate(Routes.po(po.id)) },
                    )
                    Divider()
                }
            }
        }
    }
}

class PoNewViewModel(private val c: AppContainer) : DellaViewModel() {
    val suppliers = c.parties.observeSuppliers().state(emptyList())
    val products = c.catalog.observeProducts().state(emptyList())
    fun create(supplierId: Long?, lines: List<PoLineDraft>, notes: String, onDone: (Long) -> Unit) = act("Order created. Stock will update when goods are received.") {
        val id = c.purchases.createOrder(supplierId ?: throw com.della.app.data.repo.BusinessRuleException("Choose a supplier."), lines, notes)
        onDone(id)
    }
}

private class DraftLine(val product: ProductRow, qty: String, cost: String) {
    var qty by mutableStateOf(qty)
    var cost by mutableStateOf(cost)
}

@Composable
fun PoNewScreen() {
    val vm = dellaViewModel { PoNewViewModel(it) }
    Messages(vm)
    val nav = LocalNav.current
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var supplier by remember { mutableStateOf<Supplier?>(null) }
    var notes by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<DraftLine>() }
    var picking by remember { mutableStateOf<ProductRow?>(null) }

    val total = lines.sumOf { (it.qty.toLongOrNull() ?: 0) * (Money.parse(it.cost) ?: 0) }
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("New purchase order", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (suppliers.isEmpty()) {
                Text("Add a supplier first.", color = Della.Warning)
                SecondaryButton("Add supplier", Modifier.fillMaxWidth()) { nav.navigate(Routes.supplierEdit(0)) }
            }
            SelectField("Supplier", suppliers, supplier, { it.name }, { supplier = it })
            SectionLabel("Items")
            lines.forEachIndexed { i, l ->
                Panel(Modifier.padding(vertical = 4.dp), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.product.product.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Outlined.Delete, "Remove", tint = Della.TextMuted) }
                    }
                    Row {
                        QtyField(l.qty, { l.qty = it }, "Qty (${l.product.product.unit})", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        MoneyField(l.cost, { l.cost = it }, "Unit cost", Modifier.weight(1f))
                    }
                }
            }
            val available = products.filter { p -> lines.none { it.product.product.id == p.product.id } }
            SelectField("Add item", available, picking, { "${it.product.name} (stock ${it.product.stockQty} ${it.product.unit})" }, { row ->
                lines.add(DraftLine(row, maxOf(1L, row.product.reorderLevel * 2 - row.product.stockQty).toString(), if (row.product.costPaise > 0) Money.toPlain(row.product.costPaise) else ""))
                picking = null
            }, placeholder = "Choose a product or ingredient")
            Field(notes, { notes = it }, "Notes (optional)")
            Spacer(Modifier.height(8.dp))
            InfoRow("Order total", Money.format(total), bold = true)
            Text("Creating the order does not change stock. Receive the goods on the order when they arrive.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton(if (busy) "Saving…" else "Create order", Modifier.fillMaxWidth().padding(16.dp), enabled = !busy && lines.isNotEmpty() && supplier != null) {
            vm.create(supplier?.id, lines.map { PoLineDraft(it.product.product.id, it.qty.toLongOrNull() ?: 0, Money.parse(it.cost) ?: -1) }, notes) { id ->
                nav.popBackStack(); nav.navigate(Routes.po(id))
            }
        }
    }
}

class PoDetailViewModel(private val c: AppContainer, val id: Long) : DellaViewModel() {
    val po = c.purchases.observePo(id).state(null)
    val lines = c.purchases.observeLines(id).state(emptyList())
    val receipts = c.purchases.observeReceipts(id).state(emptyList())
    fun receive(q: Map<Long, Long>, note: String, onDone: () -> Unit) = act("Goods received. Stock updated.", onDone) { c.purchases.receive(id, q, note) }
    fun pay(amount: Long, method: String) = act("Supplier payment recorded.") { c.purchases.recordPayment(id, amount, method) }
    fun cancel(reason: String) = act("Order cancelled.") { c.purchases.cancel(id, reason) }
}

@Composable
fun PoDetailScreen(id: Long) {
    val vm = dellaViewModel("po-$id") { PoDetailViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val row by vm.po.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    var receiving by remember { mutableStateOf(false) }
    val recvQty = remember { mutableStateMapOf<Long, String>() }
    var recvNote by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var payAmount by remember { mutableStateOf("") }
    var payMethod by remember { mutableStateOf(PayMethod.BANK) }
    var cancelling by remember { mutableStateOf(false) }
    var cancelReason by remember { mutableStateOf("") }

    val r = row
    Column(Modifier.fillMaxSize()) {
        DellaTopBar(r?.po?.number ?: "Order", onBack = { nav.popBackStack() }, subtitle = r?.supplierName)
        if (r == null) return@Column
        val po = r.po
        val open = po.status == PoStatus.ORDERED || po.status == PoStatus.PARTIAL
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Money.format(po.totalPaise), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        Pill(po.status, poStatusColor(po.status))
                    }
                    InfoRow("Created", Dates.dayTime(po.createdAt))
                    if (po.supplierInvoiceNo.isNotBlank()) InfoRow("Supplier invoice", po.supplierInvoiceNo)
                    InfoRow("Paid", Money.format(po.paidPaise))
                    InfoRow("Balance due", Money.format(po.totalPaise - po.paidPaise), if (po.totalPaise > po.paidPaise) Della.Warning else Della.Text)
                    if (po.notes.isNotBlank()) Text(po.notes, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                }
                SectionLabel("Items")
            }
            items(lines, key = { it.line.id }) { l ->
                ListRow(
                    l.productName, "Ordered ${l.line.qtyOrdered} · received ${l.line.qtyReceived} ${l.unit} · ${Money.format(l.line.unitCostPaise)} each",
                    trailing = Money.format(l.line.qtyOrdered * l.line.unitCostPaise, false),
                    trailingSub = if (l.line.qtyReceived >= l.line.qtyOrdered) "received" else "${l.line.qtyOrdered - l.line.qtyReceived} due",
                    trailingColor = if (l.line.qtyReceived >= l.line.qtyOrdered) Della.Accent else Della.Text,
                )
                Divider()
            }
            item {
                Spacer(Modifier.height(16.dp))
                if (open) {
                    PrimaryButton("Receive goods", Modifier.fillMaxWidth()) {
                        recvQty.clear(); lines.forEach { recvQty[it.line.id] = (it.line.qtyOrdered - it.line.qtyReceived).toString() }; receiving = true
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (po.status != PoStatus.CANCELLED && po.totalPaise > po.paidPaise) {
                    SecondaryButton("Record payment to supplier", Modifier.fillMaxWidth()) { payAmount = Money.toPlain(po.totalPaise - po.paidPaise); paying = true }
                    Spacer(Modifier.height(8.dp))
                }
                if (open) SecondaryButton(if (po.status == PoStatus.PARTIAL) "Close order (cancel remainder)" else "Cancel order", Modifier.fillMaxWidth(), color = Della.Danger) { cancelling = true }
                SectionLabel("Deliveries")
                if (receipts.isEmpty()) Text("Nothing received yet.", color = Della.TextMuted)
            }
            items(receipts, key = { it.id }) { g ->
                ListRow("Received ${Money.format(g.valuePaise)}", "${Dates.dayTime(g.createdAt)}" + if (g.note.isNotBlank()) " · ${g.note}" else "")
                Divider()
            }
        }

        if (receiving) ConfirmDialog(
            "Receive goods", "Enter what actually arrived. Stock increases by these quantities and the product cost updates to the order price.",
            "Confirm receipt", confirmEnabled = recvQty.values.any { (it.toLongOrNull() ?: 0) > 0 },
            onConfirm = { receiving = false; vm.receive(recvQty.mapValues { it.value.toLongOrNull() ?: 0 }, recvNote) { recvNote = "" } },
            onDismiss = { receiving = false },
            content = {
                lines.filter { it.line.qtyReceived < it.line.qtyOrdered }.forEach { l ->
                    QtyField(recvQty[l.line.id] ?: "", { recvQty[l.line.id] = it }, "${l.productName} (max ${l.line.qtyOrdered - l.line.qtyReceived})")
                }
                Field(recvNote, { recvNote = it }, "Delivery note (optional)")
            },
        )
        if (paying) {
            val amt = Money.parse(payAmount)
            ConfirmDialog(
                "Pay ${r.supplierName}", "Records money paid out. Balance due ${Money.format(po.totalPaise - po.paidPaise)}.", "Record payment",
                confirmEnabled = amt != null && amt > 0 && amt <= po.totalPaise - po.paidPaise,
                onConfirm = { paying = false; vm.pay(amt!!, payMethod) }, onDismiss = { paying = false },
                content = { MoneyField(payAmount, { payAmount = it }, "Amount"); ChipRow(PayMethod.tender, payMethod, { it.lowercase() }, { payMethod = it }) },
            )
        }
        if (cancelling) ConfirmDialog(
            "Cancel ${po.number}?", if (po.status == PoStatus.PARTIAL) "Goods already received stay in stock; the remaining quantities are cancelled and the order total reduced to what was received." else "Nothing has been received, so stock is unaffected.",
            "Cancel order", destructive = true, confirmEnabled = cancelReason.isNotBlank(),
            onConfirm = { cancelling = false; vm.cancel(cancelReason) }, onDismiss = { cancelling = false },
            content = { Field(cancelReason, { cancelReason = it }, "Reason (required)") },
        )
    }
}
