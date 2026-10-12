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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.della.app.data.db.MovementType
import com.della.app.data.db.ProductKind
import com.della.app.domain.Calc
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
import com.della.app.ui.common.Meter
import com.della.app.ui.common.Metric
import com.della.app.ui.common.MetricRow
import com.della.app.ui.common.Panel
import com.della.app.ui.common.QtyField
import com.della.app.ui.common.SearchField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della

fun movementLabel(type: String) = when (type) {
    MovementType.OPENING -> "Opening stock"
    MovementType.SALE -> "Sale"
    MovementType.SALE_REVERSAL -> "Sale reversal"
    MovementType.PURCHASE_RECEIPT -> "Goods received"
    MovementType.PRODUCTION -> "Production"
    MovementType.USAGE -> "Kitchen usage"
    MovementType.WASTAGE -> "Wastage"
    MovementType.ADJUSTMENT -> "Correction"
    MovementType.IMPORT -> "Import"
    else -> type
}

class InventoryViewModel(c: AppContainer) : DellaViewModel() {
    val products = c.catalog.observeProducts().state(emptyList())
    val movements = c.inventory.observeRecent(150).state(emptyList())
}

@Composable
fun InventoryScreen() {
    val vm = dellaViewModel { InventoryViewModel(it) }
    val nav = LocalNav.current
    val products by vm.products.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Stock") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    val health = Calc.inventory(products)

    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Stock", subtitle = "${health.activeItems} items · ${Money.compact(health.valueAtCost)} at cost") {
            IconButton(onClick = { nav.navigate(Routes.PURCHASES) }) { Icon(Icons.Outlined.LocalShipping, "Purchase orders") }
            IconButton(onClick = { nav.navigate(Routes.product(0)) }) { Icon(Icons.Outlined.Add, "Add product") }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            ChipRow(listOf("Stock", "Movements"), tab, { it }, { tab = it })
        }
        if (products.isEmpty()) {
            EmptyState(Icons.Outlined.Inventory2, "No items", "Add products and ingredients to track stock.", action = "Add product", onAction = { nav.navigate(Routes.product(0)) })
            return@Column
        }
        if (tab == "Stock") {
            val shown = products.filter { r ->
                val p = r.product
                (query.isBlank() || p.name.contains(query, true) || p.sku.contains(query, true)) && when (filter) {
                    "Low" -> p.stockQty in 1..p.reorderLevel
                    "Out" -> p.stockQty == 0L
                    "Sellable" -> p.kind == ProductKind.SELLABLE
                    "Ingredients" -> p.kind == ProductKind.INGREDIENT
                    else -> true
                }
            }.sortedWith(compareBy({ if (it.product.stockQty == 0L) 0 else if (it.product.stockQty <= it.product.reorderLevel) 1 else 2 }, { it.product.name }))
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                item {
                    MetricRow {
                        Metric("Low", "${health.lowStock.size}", Modifier.weight(1f), subColor = Della.Warning, sub = "≤ reorder", onClick = { filter = "Low" })
                        Metric("Out", "${health.outOfStock.size}", Modifier.weight(1f), subColor = Della.Danger, sub = "zero stock", onClick = { filter = "Out" })
                        Metric("Retail value", Money.compact(health.valueAtPrice), Modifier.weight(1f), sub = "sellables")
                    }
                    if (health.itemsWithoutCost > 0) Text("${health.itemsWithoutCost} item(s) have no cost, so valuation is understated.", style = MaterialTheme.typography.bodySmall, color = Della.Warning, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    SearchField(query, { query = it }, "Find item")
                    ChipRow(listOf("All", "Low", "Out", "Sellable", "Ingredients"), filter, { it }, { filter = it })
                }
                items(shown, key = { it.product.id }) { r ->
                    val p = r.product
                    val out = p.stockQty == 0L
                    val low = !out && p.stockQty <= p.reorderLevel
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ListRow(
                                title = p.name,
                                subtitle = "${if (p.kind == ProductKind.INGREDIENT) "Ingredient" else r.categoryName ?: "Item"} · reorder at ${p.reorderLevel}",
                                trailing = "${p.stockQty} ${p.unit}",
                                trailingSub = if (out) "Out" else if (low) "Low" else Money.format(p.stockQty * p.costPaise, false),
                                trailingColor = if (out) Della.Danger else if (low) Della.Warning else Della.Text,
                                onClick = { nav.navigate(Routes.stockItem(p.id)) },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val frac = if (p.reorderLevel > 0) p.stockQty.toFloat() / (p.reorderLevel * 3) else 1f
                        Meter(frac, if (out) Della.Danger else if (low) Della.Warning else Della.Primary)
                    }
                    Divider()
                }
            }
        } else {
            if (movements.isEmpty()) EmptyState(Icons.Outlined.Inventory2, "No movements", "Every stock change is recorded here.")
            else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                item { Text("Latest ${movements.size} stock movements (ledger)", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, modifier = Modifier.padding(vertical = 6.dp)) }
                items(movements, key = { it.movement.id }) { r ->
                    val m = r.movement
                    ListRow(
                        title = r.productName,
                        subtitle = "${movementLabel(m.type)} · ${Dates.dayTime(m.createdAt)}" + if (m.note.isNotBlank()) " · ${m.note}" else "",
                        trailing = "${if (m.qtyDelta > 0) "+" else ""}${m.qtyDelta} ${r.unit}",
                        trailingSub = "bal ${m.balanceAfter}",
                        trailingColor = if (m.qtyDelta > 0) Della.Accent else Della.Text,
                        onClick = { nav.navigate(Routes.stockItem(m.productId)) },
                    )
                    Divider()
                }
            }
        }
    }
}

class StockItemViewModel(private val c: AppContainer, val id: Long) : DellaViewModel() {
    val product = c.catalog.observeProduct(id).state(null)
    val ledger = c.inventory.observeForProduct(id).state(emptyList())

    fun adjust(type: String, qty: Long, reason: String, onDone: () -> Unit) = act("Stock updated.", onDone) {
        val delta = when (type) { MovementType.USAGE, MovementType.WASTAGE -> -qty; else -> qty }
        c.inventory.adjust(id, delta, type, reason)
    }

    fun count(counted: Long, reason: String, onDone: () -> Unit) = act(onDone = onDone) {
        val r = c.inventory.recordCount(id, counted, reason)
        messages.emit(if (r == null) "Count matches system stock — nothing changed." else "Count recorded; difference posted to the ledger.")
    }
}

@Composable
fun StockItemScreen(id: Long) {
    val vm = dellaViewModel("stock-$id") { StockItemViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val product by vm.product.collectAsStateWithLifecycle()
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var qty by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val p = product

    Column(Modifier.fillMaxSize()) {
        DellaTopBar(p?.name ?: "Item", onBack = { nav.popBackStack() }, subtitle = p?.sku)
        if (p == null) return@Column
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                Panel {
                    Text("${p.stockQty} ${p.unit}", style = MaterialTheme.typography.displaySmall)
                    Text("in stock · reorder at ${p.reorderLevel}", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Unit cost", Money.format(p.costPaise))
                    InfoRow("Value at cost", Money.format(p.stockQty * p.costPaise))
                    if (p.kind == ProductKind.SELLABLE) InfoRow("Selling price", Money.format(p.pricePaise))
                    val ledgerSum = ledger.sumOf { it.movement.qtyDelta }
                    InfoRow("Ledger check", if (ledgerSum == p.stockQty) "✓ balance = sum of ${ledger.size} movements" else "⚠ ledger sums to $ledgerSum", if (ledgerSum == p.stockQty) Della.Accent else Della.Danger)
                }
                SectionLabel("Record a change")
                Row {
                    if (p.kind == ProductKind.SELLABLE) {
                        SecondaryButton("Production", Modifier.weight(1f)) { dialog = MovementType.PRODUCTION }
                        Spacer(Modifier.width(8.dp))
                    } else {
                        SecondaryButton("Usage", Modifier.weight(1f)) { dialog = MovementType.USAGE }
                        Spacer(Modifier.width(8.dp))
                    }
                    SecondaryButton("Wastage", Modifier.weight(1f), color = Della.Warning) { dialog = MovementType.WASTAGE }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    SecondaryButton("Stock count", Modifier.weight(1f)) { dialog = "COUNT" }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Correction ±", Modifier.weight(1f), color = Della.TextMuted) { dialog = MovementType.ADJUSTMENT }
                }
                Text("Purchased goods are added by receiving a purchase order, not here.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint, modifier = Modifier.padding(top = 6.dp))
                SectionLabel("Ledger")
            }
            items(ledger, key = { it.movement.id }) { r ->
                val m = r.movement
                ListRow(
                    title = movementLabel(m.type) + if (m.note.isNotBlank()) " · ${m.note}" else "",
                    subtitle = Dates.dayTime(m.createdAt),
                    trailing = "${if (m.qtyDelta > 0) "+" else ""}${m.qtyDelta}",
                    trailingSub = "bal ${m.balanceAfter}",
                    trailingColor = if (m.qtyDelta > 0) Della.Accent else Della.Text,
                )
                Divider()
            }
        }
    }

    dialog?.let { type ->
        val isCount = type == "COUNT"
        val isAdj = type == MovementType.ADJUSTMENT
        val title = when (type) { "COUNT" -> "Physical stock count"; MovementType.ADJUSTMENT -> "Stock correction"; else -> movementLabel(type) }
        val n = qty.toLongOrNull()
        ConfirmDialog(
            title = title,
            body = when (type) {
                "COUNT" -> "Enter the quantity you counted. The difference from ${p?.stockQty} is recorded as a correction."
                MovementType.ADJUSTMENT -> "Positive adds, negative removes. A reason is required."
                MovementType.PRODUCTION -> "Adds freshly made items to stock."
                else -> "Removes stock."
            },
            confirm = "Record",
            confirmEnabled = n != null && (isCount || n != 0L) && (!isAdj || reason.isNotBlank()),
            onConfirm = {
                val q = n ?: 0
                val close = { dialog = null; qty = ""; reason = "" }
                if (isCount) vm.count(q, reason, close) else vm.adjust(type, q, reason, close)
            },
            onDismiss = { dialog = null; qty = ""; reason = "" },
            content = {
                QtyField(qty, { qty = it }, if (isCount) "Counted quantity" else "Quantity (${p?.unit})", allowNegative = isAdj)
                Field(reason, { reason = it }, if (isAdj) "Reason (required)" else "Note (optional)")
            },
        )
    }
}
