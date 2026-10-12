package com.della.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.data.db.Customer
import com.della.app.data.db.PayMethod
import com.della.app.data.db.SaleStatus
import com.della.app.data.db.Supplier
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
import com.della.app.ui.common.SearchField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.flow.MutableStateFlow

class CustomersViewModel(c: AppContainer) : DellaViewModel() {
    val customers = c.parties.observeCustomers().state(emptyList())
}

@Composable
fun CustomersScreen() {
    val vm = dellaViewModel { CustomersViewModel(it) }
    val nav = LocalNav.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val shown = customers.filter {
        (query.isBlank() || it.customer.name.contains(query, true) || it.customer.phone.contains(query)) &&
            (filter == "All" || (filter == "Owes money" && it.outstandingPaise > 0))
    }
    Scaffold(
        containerColor = Della.Background,
        topBar = { DellaTopBar("Customers", onBack = { nav.popBackStack() }, subtitle = "${customers.size} customers · ${Money.format(customers.sumOf { it.outstandingPaise })} owed") },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate(Routes.customerEdit(0)) }, containerColor = Della.Primary, contentColor = Della.Text) { Icon(Icons.Outlined.Add, "Add customer") } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SearchField(query, { query = it }, "Name or phone")
                ChipRow(listOf("All", "Owes money"), filter, { it }, { filter = it })
            }
            if (shown.isEmpty()) EmptyState(Icons.Outlined.People, "No customers", "Add regulars and accounts to track their purchases and credit.")
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                items(shown, key = { it.customer.id }) { r ->
                    ListRow(
                        title = r.customer.name,
                        subtitle = "${r.saleCount} purchases" + (r.lastSaleAt?.let { " · last ${Dates.day(it)}" } ?: "") + if (r.customer.phone.isNotBlank()) " · ${r.customer.phone}" else "",
                        trailing = if (r.outstandingPaise > 0) "Owes ${Money.format(r.outstandingPaise, false)}" else Money.format(r.totalPaise, false),
                        trailingSub = if (r.outstandingPaise > 0) null else "lifetime",
                        trailingColor = if (r.outstandingPaise > 0) Della.Warning else Della.Text,
                        onClick = { nav.navigate(Routes.customer(r.customer.id)) },
                    )
                    Divider()
                }
            }
        }
    }
}

class CustomerDetailViewModel(private val c: AppContainer, val id: Long) : DellaViewModel() {
    val customer = c.parties.observeCustomer(id).state(null)
    val sales = c.sales.observeForCustomer(id).state(emptyList())
    fun settle(amount: Long, method: String) = act("Payment recorded against oldest unpaid bills.") { c.sales.settleCustomer(id, amount, method) }
}

@Composable
fun CustomerDetailScreen(id: Long) {
    val vm = dellaViewModel("customer-$id") { CustomerDetailViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val customer by vm.customer.collectAsStateWithLifecycle()
    val sales by vm.sales.collectAsStateWithLifecycle()
    var settle by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PayMethod.CASH) }
    val c = customer
    val done = sales.filter { it.sale.status == SaleStatus.COMPLETED }
    val outstanding = done.sumOf { it.sale.totalPaise - it.sale.paidPaise }

    Column(Modifier.fillMaxSize()) {
        DellaTopBar(c?.name ?: "Customer", onBack = { nav.popBackStack() }, subtitle = c?.phone?.takeIf { it.isNotBlank() }) {
            IconButton(onClick = { nav.navigate(Routes.customerEdit(id)) }) { Icon(Icons.Outlined.Edit, "Edit") }
        }
        if (c == null) return@Column
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                MetricRow {
                    Metric("Purchases", "${done.size}", Modifier.weight(1f))
                    Metric("Lifetime", Money.compact(done.sumOf { it.sale.totalPaise }), Modifier.weight(1f))
                    Metric("Owes", Money.compact(outstanding), Modifier.weight(1f), subColor = Della.Warning, sub = if (outstanding > 0) "unpaid bills" else "nothing due")
                }
                if (c.notes.isNotBlank()) Text(c.notes, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, modifier = Modifier.padding(top = 8.dp))
                if (outstanding > 0) {
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Record payment received", Modifier.fillMaxWidth()) { settle = true }
                }
                SectionLabel("Sales")
                if (sales.isEmpty()) Text("No sales linked yet. Choose this customer when billing.", color = Della.TextMuted)
            }
            items(sales, key = { it.sale.id }) { r ->
                val s = r.sale
                ListRow(
                    "${s.number} · ${Money.format(s.totalPaise)}", "${Dates.dayTime(s.createdAt)} · ${s.paymentMethod.lowercase()}",
                    trailing = when { s.status == SaleStatus.REVERSED -> "Reversed"; s.totalPaise > s.paidPaise -> "Due ${Money.format(s.totalPaise - s.paidPaise, false)}"; else -> "Paid" },
                    trailingColor = when { s.status == SaleStatus.REVERSED -> Della.Danger; s.totalPaise > s.paidPaise -> Della.Warning; else -> Della.Accent },
                    onClick = { nav.navigate(Routes.sale(s.id)) },
                )
                Divider()
            }
        }
    }
    if (settle) {
        val amt = Money.parse(amount)
        ConfirmDialog(
            "Payment from ${c?.name}", "Outstanding ${Money.format(outstanding)}. The payment is applied to the oldest unpaid bills first and counted as cash received today.",
            "Record", confirmEnabled = amt != null && amt in 1..outstanding,
            onConfirm = { settle = false; vm.settle(amt!!, method); amount = "" }, onDismiss = { settle = false },
            content = {
                MoneyField(amount, { amount = it }, "Amount received")
                androidx.compose.material3.TextButton(onClick = { amount = Money.toPlain(outstanding) }) { Text("Full amount", color = Della.Accent) }
                ChipRow(PayMethod.tender, method, { it.lowercase() }, { method = it })
            },
        )
    }
}

class CustomerEditViewModel(private val c: AppContainer, val id: Long) : DellaViewModel() {
    val loaded = MutableStateFlow<Customer?>(null)
    suspend fun load() { if (loaded.value == null) loaded.value = if (id == 0L) Customer(name = "") else c.db.partyDao().customer(id) }
    fun save(cu: Customer, onDone: () -> Unit) = act("Customer saved.", onDone) { c.parties.saveCustomer(cu) }
}

@Composable
fun CustomerEditScreen(id: Long) {
    val vm = dellaViewModel("customer-edit-$id") { CustomerEditViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val cu = loaded ?: return
    var name by rememberSaveable(cu) { mutableStateOf(cu.name) }
    var phone by rememberSaveable(cu) { mutableStateOf(cu.phone) }
    var email by rememberSaveable(cu) { mutableStateOf(cu.email) }
    var notes by rememberSaveable(cu) { mutableStateOf(cu.notes) }
    Column(Modifier.fillMaxSize()) {
        DellaTopBar(if (id == 0L) "New customer" else "Edit customer", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Field(name, { name = it }, "Name")
            Field(phone, { phone = it }, "Phone", keyboard = KeyboardType.Phone)
            Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            Field(notes, { notes = it }, "Notes", singleLine = false, minLines = 3)
        }
        PrimaryButton("Save", Modifier.fillMaxWidth().padding(16.dp)) { vm.save(cu.copy(name = name, phone = phone, email = email, notes = notes)) { nav.popBackStack() } }
    }
}

class SuppliersViewModel(c: AppContainer) : DellaViewModel() {
    val suppliers = c.parties.observeSuppliers().state(emptyList())
}

@Composable
fun SuppliersScreen() {
    val vm = dellaViewModel { SuppliersViewModel(it) }
    val nav = LocalNav.current
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Della.Background,
        topBar = { DellaTopBar("Suppliers", onBack = { nav.popBackStack() }, subtitle = "${suppliers.size} suppliers") },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate(Routes.supplierEdit(0)) }, containerColor = Della.Primary, contentColor = Della.Text) { Icon(Icons.Outlined.Add, "Add supplier") } },
    ) { pad ->
        if (suppliers.isEmpty()) EmptyState(Icons.Outlined.Storefront, "No suppliers", "Add the businesses you buy ingredients and supplies from.", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
            items(suppliers, key = { it.id }) { s ->
                ListRow(s.name, listOf(s.contactPerson, s.phone, s.email).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No contact details" }, leading = Icons.Outlined.Storefront, onClick = { nav.navigate(Routes.supplierEdit(s.id)) })
                Divider()
            }
        }
    }
}

class SupplierEditViewModel(private val c: AppContainer, val id: Long) : DellaViewModel() {
    val loaded = MutableStateFlow<Supplier?>(null)
    val orders = c.purchases.observeForSupplier(id).state(emptyList())
    suspend fun load() { if (loaded.value == null) loaded.value = if (id == 0L) Supplier(name = "") else c.parties.supplier(id) }
    fun save(s: Supplier, onDone: () -> Unit) = act("Supplier saved.", onDone) { c.parties.saveSupplier(s) }
}

@Composable
fun SupplierEditScreen(id: Long) {
    val vm = dellaViewModel("supplier-$id") { SupplierEditViewModel(it, id) }
    Messages(vm)
    val nav = LocalNav.current
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val orders by vm.orders.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val s = loaded ?: return
    var name by rememberSaveable(s) { mutableStateOf(s.name) }
    var contact by rememberSaveable(s) { mutableStateOf(s.contactPerson) }
    var phone by rememberSaveable(s) { mutableStateOf(s.phone) }
    var email by rememberSaveable(s) { mutableStateOf(s.email) }
    var gstin by rememberSaveable(s) { mutableStateOf(s.gstin) }
    var notes by rememberSaveable(s) { mutableStateOf(s.notes) }
    Column(Modifier.fillMaxSize()) {
        DellaTopBar(if (id == 0L) "New supplier" else s.name, onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Field(name, { name = it }, "Business name")
            Field(contact, { contact = it }, "Contact person")
            Field(phone, { phone = it }, "Phone", keyboard = KeyboardType.Phone)
            Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            Field(gstin, { gstin = it.uppercase() }, "GSTIN (optional)")
            Field(notes, { notes = it }, "Notes", singleLine = false, minLines = 2)
            if (id != 0L) {
                SectionLabel("Purchase history")
                if (orders.isEmpty()) Text("No orders yet.", color = Della.TextMuted)
                orders.forEach { o ->
                    ListRow(o.po.number, "${Dates.day(o.po.createdAt)} · ${o.po.status.lowercase()}", trailing = Money.format(o.po.totalPaise, false), onClick = { nav.navigate(Routes.po(o.po.id)) })
                    Divider()
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton("New purchase order", Modifier.fillMaxWidth()) { nav.navigate(Routes.PO_NEW) }
            }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton("Save", Modifier.fillMaxWidth().padding(16.dp)) { vm.save(s.copy(name = name, contactPerson = contact, phone = phone, email = email, gstin = gstin, notes = notes)) { nav.popBackStack() } }
    }
}
