package com.della.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.della.app.AppContainer
import com.della.app.data.db.Customer
import com.della.app.data.db.PayMethod
import com.della.app.data.db.ProductKind
import com.della.app.data.db.ProductRow
import com.della.app.data.repo.CartItem
import com.della.app.data.repo.CheckoutRequest
import com.della.app.data.repo.PricedCart
import com.della.app.domain.Money
import com.della.app.ui.Routes
import com.della.app.ui.common.ChipRow
import com.della.app.ui.common.ConfirmDialog
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.EmptyState
import com.della.app.ui.common.InfoRow
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Messages
import com.della.app.ui.common.MoneyField
import com.della.app.ui.common.Panel
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.SelectField
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.common.fieldColors
import com.della.app.ui.theme.Della
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PosViewModel(private val c: AppContainer) : DellaViewModel() {
    val products = c.catalog.observeProducts().state(emptyList())
    val customers = c.parties.observeCustomers().state(emptyList())
    val profile = c.settings.observe().state(null)

    val cart = MutableStateFlow<Map<Long, Long>>(linkedMapOf())
    val discountInput = MutableStateFlow("")
    val discountIsPercent = MutableStateFlow(false)
    private var requestId = UUID.randomUUID().toString()

    /** Live priced cart, computed by the same function used at checkout. */
    val priced = combine(cart, discountInput, discountIsPercent) { a, b, d -> Triple(a, b, d) }.mapLatest { (items, disc, pct) ->
        if (items.isEmpty()) null
        else try {
            val gross = c.sales.price(items.map { CartItem(it.key, it.value) }, 0).gross
            c.sales.price(items.map { CartItem(it.key, it.value) }, discountPaise(disc, pct, gross))
        } catch (e: Exception) { null }
    }.state(null)

    fun discountPaise(input: String, percent: Boolean, gross: Long): Long =
        if (percent) Money.parseBps(input)?.let { Money.applyBps(gross, it) } ?: 0L
        else Money.parse(input) ?: 0L

    fun add(row: ProductRow) {
        val p = row.product
        val current = cart.value[p.id] ?: 0
        if (current + 1 > p.stockQty) {
            viewModelScope.launch { messages.emit("Only ${p.stockQty} ${p.unit} of ${p.name} in stock.") }
            return
        }
        cart.value = LinkedHashMap(cart.value).apply { put(p.id, current + 1) }
    }

    fun setQty(productId: Long, qty: Long) {
        val p = products.value.firstOrNull { it.product.id == productId }?.product ?: return
        val m = LinkedHashMap(cart.value)
        when {
            qty <= 0 -> m.remove(productId)
            qty > p.stockQty -> { viewModelScope.launch { messages.emit("Only ${p.stockQty} ${p.unit} in stock.") }; return }
            else -> m[productId] = qty
        }
        cart.value = m
    }

    fun addByCode(code: String): Boolean {
        val row = products.value.firstOrNull { it.product.barcode.equals(code.trim(), true) || it.product.sku.equals(code.trim(), true) }
        if (row != null && row.product.kind == ProductKind.SELLABLE) { add(row); return true }
        return false
    }

    fun clear() {
        cart.value = linkedMapOf(); discountInput.value = ""; discountIsPercent.value = false
    }

    fun checkout(method: String, tendered: Long, customerId: Long?, onSaved: (Long) -> Unit) {
        val items = cart.value.map { CartItem(it.key, it.value) }
        act {
            val gross = c.sales.price(items, 0).gross
            val id = c.sales.checkout(
                CheckoutRequest(requestId, items, discountPaise(discountInput.value, discountIsPercent.value, gross), method, tendered, customerId)
            )
            requestId = UUID.randomUUID().toString()
            clear()
            onSaved(id)
        }
    }
}

@Composable
fun PosScreen() {
    val vm = dellaViewModel { PosViewModel(it) }
    Messages(vm)
    val nav = LocalNav.current
    val products by vm.products.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val priced by vm.priced.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var showCart by remember { mutableStateOf(false) }

    val sellables = products.filter { it.product.kind == ProductKind.SELLABLE }
    val categories = listOf("All") + sellables.mapNotNull { it.categoryName }.distinct().sorted()
    val visible = sellables.filter {
        (category == "All" || it.categoryName == category) &&
            (query.isBlank() || it.product.name.contains(query, true) || it.product.sku.contains(query, true) || it.product.barcode == query.trim())
    }

    if (showCart) {
        BackHandler { showCart = false }
        CartView(vm, priced, onClose = { showCart = false }, onSaved = { id -> showCart = false; nav.navigate(Routes.sale(id)) })
        return
    }

    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Sell", subtitle = "Tap items to add to the bill") {
            IconButton(onClick = { nav.navigate(Routes.SALES) }) { Icon(Icons.Outlined.History, "Sales history") }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search, or enter SKU / barcode", color = Della.TextFaint) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Della.TextMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (vm.addByCode(query)) query = "" }),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp),
            )
            ChipRow(categories, category, { it }, { category = it })
        }
        if (sellables.isEmpty()) {
            EmptyState(Icons.Outlined.Inventory2, "No sellable products", "Add products with a selling price to start billing.", action = "Add product", onAction = { nav.navigate(Routes.product(0)) })
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.product.id }) { row ->
                    ProductTile(row, inCart = cart[row.product.id] ?: 0) { vm.add(row) }
                }
            }
        }
        val count = cart.values.sum()
        if (count > 0) {
            Row(
                Modifier.fillMaxWidth().background(Della.Primary).clickable { showCart = true }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ShoppingBag, null, tint = Della.Text)
                Spacer(Modifier.width(10.dp))
                Text("$count item${if (count == 1L) "" else "s"}", style = MaterialTheme.typography.titleMedium, color = Della.Text, modifier = Modifier.weight(1f))
                Text(priced?.let { Money.format(it.total) } ?: "…", style = MaterialTheme.typography.titleMedium, color = Della.Text)
                Spacer(Modifier.width(10.dp))
                Text("Review ›", style = MaterialTheme.typography.labelLarge, color = Della.Text)
            }
        }
    }
}

@Composable
private fun ProductTile(row: ProductRow, inCart: Long, onClick: () -> Unit) {
    val p = row.product
    val out = p.stockQty <= 0
    val low = !out && p.stockQty <= p.reorderLevel
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 104.dp)
            .background(if (inCart > 0) Della.PanelHigh else Della.Panel, shape)
            .border(1.dp, if (inCart > 0) Della.Accent else Della.Border, shape)
            .clickable(enabled = !out, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, color = if (out) Della.TextFaint else Della.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (inCart > 0) Box(Modifier.size(24.dp).background(Della.Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text("$inCart", style = MaterialTheme.typography.labelMedium, color = Della.Background)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(Money.format(p.pricePaise), style = MaterialTheme.typography.titleMedium, color = if (out) Della.TextFaint else Della.Accent)
        Text(
            if (out) "Out of stock" else "${p.stockQty} ${p.unit} left",
            style = MaterialTheme.typography.bodySmall, color = when { out -> Della.Danger; low -> Della.Warning; else -> Della.TextMuted },
        )
    }
}

@Composable
private fun CartView(vm: PosViewModel, priced: PricedCart?, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val products by vm.products.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val discount by vm.discountInput.collectAsStateWithLifecycle()
    val pct by vm.discountIsPercent.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var method by remember { mutableStateOf(PayMethod.UPI) }
    var tendered by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf<Customer?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val methods = PayMethod.tender.filter { it != PayMethod.BANK } + if (profile?.allowCreditSales != false) listOf(PayMethod.CREDIT) else emptyList()
    val total = priced?.total ?: 0
    val tenderedPaise = when (method) {
        PayMethod.CASH -> Money.parse(tendered) ?: total
        PayMethod.CREDIT -> Money.parse(tendered) ?: 0
        else -> total
    }
    val problems = buildList {
        if (method == PayMethod.CASH && tenderedPaise < total) add("Cash received is less than the total.")
        if (method == PayMethod.CREDIT && customer == null) add("Select a customer for pay-later.")
        if (method == PayMethod.CREDIT && tenderedPaise > total) add("Part payment exceeds total.")
        if (priced != null && priced.discount > priced.gross) add("Discount exceeds bill.")
    }

    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Current bill", onBack = onClose) {
            if (cart.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { vm.clear(); onClose() }) { Text("Clear", color = Della.Danger) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Panel(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                priced?.lines?.forEachIndexed { i, l ->
                    val p = products.firstOrNull { it.product.id == l.productId }?.product
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${Money.format(l.unitPrice)} each" + if (l.taxBps > 0 && profile?.taxMode != "NONE") " · tax ${Money.pct(l.taxBps)}" else "", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                        }
                        IconButton(onClick = { vm.setQty(l.productId, l.qty - 1) }) { Icon(Icons.Outlined.Remove, "Decrease") }
                        Text("${l.qty}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { vm.setQty(l.productId, l.qty + 1) }, enabled = p != null && l.qty < p.stockQty) { Icon(Icons.Outlined.Add, "Increase") }
                        Text(Money.format(l.gross, false), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    if (i < priced.lines.lastIndex) Divider()
                }
            }

            SectionLabel("Discount")
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoneyField(discount, { vm.discountInput.value = it }, if (pct) "Discount %" else "Discount amount", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ChipRow(listOf(false, true), pct, { if (it) "%" else "₹" }, { vm.discountIsPercent.value = it }, Modifier.width(110.dp))
            }

            SectionLabel("Customer")
            SelectField("Customer (optional)", listOf<Customer?>(null) + customers.map { it.customer }, customer, { it?.name ?: "Walk-in customer" }, { customer = it }, placeholder = "Walk-in customer")

            SectionLabel("Payment")
            ChipRow(methods, method, { when (it) { PayMethod.CREDIT -> "Pay later"; else -> it.lowercase().replaceFirstChar { c -> c.uppercase() } } }, { method = it; tendered = "" })
            if (method == PayMethod.CASH) MoneyField(tendered, { tendered = it }, "Cash received", supporting = if (tenderedPaise > total) "Change to return: ${Money.format(tenderedPaise - total)}" else "Leave empty for exact amount")
            if (method == PayMethod.CREDIT) MoneyField(tendered, { tendered = it }, "Paid now (optional)", supporting = "The unpaid balance is recorded against the customer, not as cash.")

            SectionLabel("Summary")
            Panel {
                priced?.let { pc ->
                    InfoRow("Items total", Money.format(pc.gross))
                    if (pc.discount > 0) InfoRow("Discount", "−" + Money.format(pc.discount), Della.Accent)
                    when (profile?.taxMode) {
                        "EXCLUSIVE" -> { InfoRow("Net", Money.format(pc.net)); InfoRow("Tax (added)", Money.format(pc.tax)) }
                        "INCLUSIVE" -> InfoRow("Includes tax", Money.format(pc.tax))
                        else -> InfoRow("Tax", "Not applied")
                    }
                    Divider()
                    InfoRow("Total", Money.format(pc.total), bold = true)
                }
            }
            Spacer(Modifier.height(8.dp))
            problems.forEach { Text("• $it", color = Della.Warning, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.padding(16.dp)) {
            PrimaryButton(
                if (busy) "Saving…" else "Charge ${Money.format(total)}", Modifier.fillMaxWidth(),
                enabled = priced != null && problems.isEmpty() && !busy,
            ) { confirm = true }
        }
    }

    if (confirm && priced != null) {
        ConfirmDialog(
            title = "Complete sale?",
            body = "${priced.lines.sumOf { it.qty }} items · ${Money.format(priced.total)} via ${if (method == PayMethod.CREDIT) "pay later" else method.lowercase()}" +
                (customer?.let { " · ${it.name}" } ?: "") +
                (if (method == PayMethod.CREDIT) "\nReceived now: ${Money.format(tenderedPaise)}. Outstanding: ${Money.format(total - tenderedPaise)}." else "") +
                "\nStock will be reduced immediately.",
            confirm = "Complete",
            onConfirm = { confirm = false; vm.checkout(method, tenderedPaise, customer?.id, onSaved) },
            onDismiss = { confirm = false },
        )
    }
}
