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
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.della.app.data.db.ProductKind
import com.della.app.data.repo.ProductDraft
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
import com.della.app.ui.common.MoneyField
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.QtyField
import com.della.app.ui.common.SearchField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.flow.MutableStateFlow

class ProductsViewModel(c: AppContainer) : DellaViewModel() {
    val products = c.catalog.observeProducts(includeInactive = true).state(emptyList())
}

@Composable
fun ProductsScreen() {
    val vm = dellaViewModel { ProductsViewModel(it) }
    val nav = LocalNav.current
    val products by vm.products.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Active") }
    val shown = products.filter { r ->
        val p = r.product
        (query.isBlank() || p.name.contains(query, true) || p.sku.contains(query, true) || p.barcode.contains(query) || (r.categoryName?.contains(query, true) == true)) &&
            when (filter) {
                "Active" -> p.active
                "Sellable" -> p.active && p.kind == ProductKind.SELLABLE
                "Ingredients" -> p.active && p.kind == ProductKind.INGREDIENT
                "Archived" -> !p.active
                else -> true
            }
    }
    androidx.compose.material3.Scaffold(
        containerColor = Della.Background,
        topBar = { DellaTopBar("Products", onBack = { nav.popBackStack() }, subtitle = "${products.count { it.product.active }} active") },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.product(0)) }, containerColor = Della.Primary, contentColor = Della.Text) { Icon(Icons.Outlined.Add, "Add product") }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SearchField(query, { query = it }, "Name, SKU, barcode or category")
                ChipRow(listOf("Active", "Sellable", "Ingredients", "Archived"), filter, { it }, { filter = it })
            }
            if (shown.isEmpty()) EmptyState(Icons.Outlined.Inventory2, if (products.isEmpty()) "No products yet" else "Nothing matches", "Products you sell and ingredients you buy both live here.", action = "Add product", onAction = { nav.navigate(Routes.product(0)) })
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                items(shown, key = { it.product.id }) { r ->
                    val p = r.product
                    val margin = if (p.pricePaise > 0 && p.costPaise > 0) Money.ratio(p.pricePaise - p.costPaise, p.pricePaise) + " margin" else if (p.kind == ProductKind.SELLABLE) "no cost set" else "ingredient"
                    ListRow(
                        title = p.name,
                        subtitle = "${p.sku} · ${r.categoryName ?: "Uncategorised"} · $margin",
                        trailing = if (p.kind == ProductKind.SELLABLE) Money.format(p.pricePaise) else Money.format(p.costPaise),
                        trailingSub = "${p.stockQty} ${p.unit}",
                        trailingColor = if (p.active) Della.Text else Della.TextFaint,
                        onClick = { nav.navigate(Routes.product(p.id)) },
                    )
                    Divider()
                }
            }
        }
    }
}

class ProductEditViewModel(private val c: AppContainer, private val id: Long) : DellaViewModel() {
    val categories = c.catalog.observeCategories().state(emptyList())
    val loaded = MutableStateFlow<ProductDraft?>(null)

    suspend fun load() {
        if (loaded.value != null) return
        if (id == 0L) {
            loaded.value = ProductDraft(name = "", sku = "", barcode = "", categoryName = "", kind = ProductKind.SELLABLE, unit = "pc", costPaise = 0, pricePaise = 0, taxBps = null, reorderLevel = 0, openingStock = 0)
        } else {
            val p = c.catalog.product(id) ?: return
            val cat = p.categoryId?.let { cid -> c.db.catalogDao().categories().firstOrNull { it.id == cid }?.name } ?: ""
            loaded.value = ProductDraft(p.id, p.name, p.sku, p.barcode, cat, p.kind, p.unit, p.costPaise, p.pricePaise, p.taxBps, p.reorderLevel, p.stockQty, p.active)
        }
    }

    suspend fun suggestSku(name: String) = c.catalog.nextSku(name)

    fun save(d: ProductDraft, onDone: () -> Unit) = act("Saved.", onDone) { c.catalog.save(d) }
    fun delete(onDone: () -> Unit) {
        act(onDone = onDone) { messages.emit(c.catalog.deleteOrArchive(id)) }
    }
}

@Composable
fun ProductEditScreen(id: Long) {
    val vm = dellaViewModel("product-$id") { ProductEditViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val d = loaded ?: return

    var name by rememberSaveable(d) { mutableStateOf(d.name) }
    var sku by rememberSaveable(d) { mutableStateOf(d.sku) }
    var barcode by rememberSaveable(d) { mutableStateOf(d.barcode) }
    var category by rememberSaveable(d) { mutableStateOf(d.categoryName) }
    var kind by rememberSaveable(d) { mutableStateOf(d.kind) }
    var unit by rememberSaveable(d) { mutableStateOf(d.unit) }
    var cost by rememberSaveable(d) { mutableStateOf(if (d.costPaise > 0) Money.toPlain(d.costPaise) else "") }
    var price by rememberSaveable(d) { mutableStateOf(if (d.pricePaise > 0) Money.toPlain(d.pricePaise) else "") }
    var tax by rememberSaveable(d) { mutableStateOf(d.taxBps?.let { Money.pct(it).removeSuffix("%") } ?: "") }
    var reorder by rememberSaveable(d) { mutableStateOf(if (d.reorderLevel > 0) d.reorderLevel.toString() else "") }
    var opening by rememberSaveable(d) { mutableStateOf("") }
    var active by rememberSaveable(d) { mutableStateOf(d.active) }
    var confirmDelete by remember { mutableStateOf(false) }
    var skuSuggest by remember { mutableStateOf("") }
    LaunchedEffect(name) { if (id == 0L && name.length >= 3) skuSuggest = vm.suggestSku(name) }

    val costP = Money.parse(cost) ?: 0
    val priceP = Money.parse(price) ?: 0
    val isNew = id == 0L

    Column(Modifier.fillMaxSize()) {
        DellaTopBar(if (isNew) "New product" else "Edit product", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ChipRow(listOf(ProductKind.SELLABLE, ProductKind.INGREDIENT), kind, { if (it == ProductKind.SELLABLE) "Sold to customers" else "Ingredient / supply" }, { kind = it })
            Field(name, { name = it }, "Name")
            Row {
                Field(sku, { sku = it.uppercase() }, "SKU", Modifier.weight(1f), supporting = if (isNew && sku.isBlank() && skuSuggest.isNotBlank()) "Suggested: $skuSuggest" else null)
                if (isNew && sku.isBlank() && skuSuggest.isNotBlank()) androidx.compose.material3.TextButton(onClick = { sku = skuSuggest }, modifier = Modifier.padding(top = 12.dp)) { Text("Use", color = Della.Accent) }
            }
            Field(barcode, { barcode = it.filter { c -> c.isLetterOrDigit() } }, "Barcode (optional)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            Field(category, { category = it }, "Category", supporting = categories.takeIf { it.isNotEmpty() }?.joinToString(limit = 6) { it.name }?.let { "Existing: $it" })
            Field(unit, { unit = it }, "Unit (pc, kg, litre, tray…)")
            SectionLabel("Pricing")
            Row {
                MoneyField(cost, { cost = it }, "Unit cost", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                if (kind == ProductKind.SELLABLE) MoneyField(price, { price = it }, "Selling price", Modifier.weight(1f))
            }
            if (kind == ProductKind.SELLABLE) {
                if (priceP > 0) Text(
                    if (costP > 0) "Margin ${Money.format(priceP - costP)} per unit (${Money.ratio(priceP - costP, priceP)} of price, before tax)" else "Add a cost to see margin and profit.",
                    style = MaterialTheme.typography.bodySmall, color = if (costP >= priceP && costP > 0) Della.Danger else Della.TextMuted,
                )
                Field(tax, { tax = it.filter { c -> c.isDigit() || c == '.' } }, "Tax rate override %", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal, supporting = "Leave empty to use the business default from Settings.")
            }
            SectionLabel("Stock")
            QtyField(reorder, { reorder = it }, "Reorder level", supporting = "Alerts when stock is at or below this.")
            if (isNew) QtyField(opening, { opening = it }, "Opening stock", supporting = "Recorded as an opening movement in the stock ledger.")
            else {
                Text("Current stock: ${d.openingStock} ${d.unit}. Change stock from Stock → item (production, usage, wastage or count), so every change is recorded.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Open stock ledger", Modifier.fillMaxWidth()) { nav.navigate(Routes.stockItem(id)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Active", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(active, { active = it }, colors = SwitchDefaults.colors(checkedTrackColor = Della.Primary, checkedThumbColor = Della.Text))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(16.dp)) {
            PrimaryButton(if (busy) "Saving…" else "Save", Modifier.fillMaxWidth(), enabled = !busy) {
                vm.save(
                    ProductDraft(
                        id = id, name = name, sku = sku, barcode = barcode, categoryName = category, kind = kind, unit = unit,
                        costPaise = costP, pricePaise = if (kind == ProductKind.SELLABLE) priceP else 0,
                        taxBps = if (tax.isBlank()) null else Money.parseBps(tax) ?: -1,
                        reorderLevel = reorder.toLongOrNull() ?: 0, openingStock = opening.toLongOrNull() ?: 0, active = active,
                    )
                ) { nav.popBackStack() }
            }
            if (!isNew) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Delete or archive", Modifier.fillMaxWidth(), color = Della.Danger) { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) ConfirmDialog(
        "Remove ${d.name}?", "Products with sales or stock history are archived (hidden but kept for records). Products with no history are deleted.",
        "Remove", onConfirm = { confirmDelete = false; vm.delete { nav.popBackStack() } }, onDismiss = { confirmDelete = false }, destructive = true,
    )
}
