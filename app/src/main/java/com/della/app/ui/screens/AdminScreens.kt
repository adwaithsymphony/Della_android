package com.della.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.della.app.AppContainer
import com.della.app.Workspace
import com.della.app.data.ImportReport
import com.della.app.data.db.BusinessProfile
import com.della.app.data.db.TaxMode
import com.della.app.domain.Dates
import com.della.app.domain.Money
import com.della.app.ui.common.ChipRow
import com.della.app.ui.common.ConfirmDialog
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.EmptyState
import com.della.app.ui.common.ErrorList
import com.della.app.ui.common.Field
import com.della.app.ui.common.ListRow
import com.della.app.ui.common.LocalContainer
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Messages
import com.della.app.ui.common.Panel
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.SearchField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import kotlinx.coroutines.flow.MutableStateFlow

// ---------------------------------------------------------------- Audit
class AuditViewModel(c: AppContainer) : DellaViewModel() {
    val entries = c.db.auditDao().observe(2000).state(emptyList())
}

@Composable
fun AuditScreen() {
    val vm = dellaViewModel { AuditViewModel(it) }
    val nav = LocalNav.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val groups = mapOf(
        "Sales" to listOf("sale", "customer"), "Stock" to listOf("product"), "Purchasing" to listOf("purchase_order", "invoice", "supplier"),
        "Money" to listOf("expense"), "Settings" to listOf("settings", "data"),
    )
    val shown = entries.filter { e ->
        (filter == "All" || e.entityType in groups[filter].orEmpty()) && (query.isBlank() || e.summary.contains(query, true) || e.action.contains(query, true))
    }
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Audit history", onBack = { nav.popBackStack() }, subtitle = "Append-only record of important changes")
        Column(Modifier.padding(horizontal = 16.dp)) {
            SearchField(query, { query = it }, "Search history")
            ChipRow(listOf("All") + groups.keys, filter, { it }, { filter = it })
        }
        if (shown.isEmpty()) EmptyState(Icons.Outlined.History, "No entries", "Sales, corrections, stock changes, approvals and settings changes are logged here.")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(shown, key = { it.id }) { e ->
                ListRow(e.summary, "${Dates.dayTime(e.createdAt)} · ${e.action.replace('_', ' ').lowercase()}")
                Divider()
            }
        }
    }
}

// ---------------------------------------------------------------- Settings
class SettingsViewModel(private val c: AppContainer) : DellaViewModel() {
    val profile = MutableStateFlow<BusinessProfile?>(null)
    suspend fun load() { profile.value = c.settings.get() ?: BusinessProfile(businessName = "") }
    fun save(p: BusinessProfile) = act("Settings saved.") { c.settings.save(p); load() }
}

@Composable
fun SettingsScreen() {
    val vm = dellaViewModel { SettingsViewModel(it) }
    Messages(vm)
    val nav = LocalNav.current
    val container = LocalContainer.current
    val loaded by vm.profile.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
    val p = loaded ?: return
    var name by rememberSaveable(p) { mutableStateOf(p.businessName) }
    var owner by rememberSaveable(p) { mutableStateOf(p.ownerName) }
    var phone by rememberSaveable(p) { mutableStateOf(p.phone) }
    var address by rememberSaveable(p) { mutableStateOf(p.address) }
    var gstin by rememberSaveable(p) { mutableStateOf(p.gstin) }
    var taxMode by rememberSaveable(p) { mutableStateOf(p.taxMode) }
    var tax by rememberSaveable(p) { mutableStateOf(if (p.defaultTaxBps > 0) Money.pct(p.defaultTaxBps).removeSuffix("%") else "") }
    var footer by rememberSaveable(p) { mutableStateOf(p.receiptFooter) }
    var alerts by rememberSaveable(p) { mutableStateOf(p.lowStockAlerts) }
    var credit by rememberSaveable(p) { mutableStateOf(p.allowCreditSales) }
    var switchTo by remember { mutableStateOf<Workspace?>(null) }
    val bps = if (taxMode == TaxMode.NONE) 0 else Money.parseBps(tax)

    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Settings", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionLabel("Workspace")
            Panel {
                Text(container.workspace.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (container.isDemo) "You are viewing fictional demonstration data. Your real business data is kept in a separate database."
                    else "Your real business data. The demo is kept in a separate database and never mixes with it.",
                    style = MaterialTheme.typography.bodySmall, color = Della.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                val other = if (container.isDemo) Workspace.BUSINESS else Workspace.DEMO
                SecondaryButton("Switch to ${other.label}", Modifier.fillMaxWidth()) { switchTo = other }
            }
            SectionLabel("Business profile")
            Field(name, { name = it }, "Business name")
            Field(owner, { owner = it }, "Owner / manager")
            Field(phone, { phone = it }, "Phone")
            Field(address, { address = it }, "Address", singleLine = false, minLines = 2)
            Field(gstin, { gstin = it.uppercase() }, "GSTIN (optional, printed on receipts)")
            Text("Currency: Indian Rupee (₹ INR)", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, modifier = Modifier.padding(vertical = 6.dp))

            SectionLabel("Tax")
            ChipRow(listOf(TaxMode.NONE, TaxMode.EXCLUSIVE, TaxMode.INCLUSIVE), taxMode, {
                when (it) { TaxMode.NONE -> "No tax"; TaxMode.EXCLUSIVE -> "Added to price"; else -> "Included in price" }
            }, { taxMode = it })
            if (taxMode != TaxMode.NONE) Field(tax, { tax = it.filter { c -> c.isDigit() || c == '.' } }, "Default tax rate %", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal,
                supporting = "Products can override this. Each sale stores the rate used at that time.", isError = tax.isNotEmpty() && Money.parseBps(tax) == null)
            Text("DELLA calculates tax as configured here; it does not verify GST compliance. Confirm rates with your accountant.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint)

            SectionLabel("Preferences")
            ToggleRow("Stock alerts", "Stock-risk and reorder insights and alerts", alerts) { alerts = it }
            ToggleRow("Allow pay-later sales", "Bill customers on credit; tracked as money owed, not cash", credit) { credit = it }
            Field(footer, { footer = it }, "Receipt footer")
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton("Save settings", Modifier.fillMaxWidth().padding(16.dp), enabled = bps != null) {
            vm.save(p.copy(businessName = name, ownerName = owner, phone = phone, address = address, gstin = gstin, taxMode = taxMode,
                defaultTaxBps = bps ?: 0, receiptFooter = footer, lowStockAlerts = alerts, allowCreditSales = credit))
        }
    }
    switchTo?.let { w ->
        ConfirmDialog(
            "Switch to ${w.label}?", "DELLA will restart and open that workspace. Nothing is copied between workspaces." +
                if (w == Workspace.DEMO) " If the demo has not been generated yet, it is created now." else "",
            "Switch", onConfirm = { container.switchWorkspace(w) }, onDismiss = { switchTo = null },
        )
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Della.Primary, checkedThumbColor = Della.Text))
    }
}

// ---------------------------------------------------------------- Data
class DataViewModel(private val c: AppContainer) : DellaViewModel() {
    val report = MutableStateFlow<ImportReport?>(null)
    fun export(uri: Uri) = act { val n = c.transfer.exportCsvZip(uri); messages.emit("Exported $n CSV files.") }
    fun importProducts(uri: Uri) = act { report.value = c.transfer.importProducts(uri) }
    fun backup(uri: Uri) = act("Backup saved. Keep it somewhere safe — it contains all business records.") { c.transfer.backup(uri) }
    fun restore(uri: Uri) = act {
        val candidate = c.transfer.prepareRestore(uri)
        c.closeDatabase()
        c.transfer.installRestore(candidate)
        c.restart()
    }
}

@Composable
fun DataScreen() {
    val vm = dellaViewModel { DataViewModel(it) }
    Messages(vm)
    val nav = LocalNav.current
    val container = LocalContainer.current
    val report by vm.report.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetText by remember { mutableStateOf("") }
    val stamp = Dates.iso(System.currentTimeMillis())
    val prefix = if (container.isDemo) "della-demo" else "della"

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importProducts) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(vm::backup) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pendingRestore = it }

    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Data & backup", onBack = { nav.popBackStack() }, subtitle = container.workspace.label)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("All data is stored only on this phone. Files are saved or opened through Android's file picker; DELLA needs no storage permission.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, modifier = Modifier.padding(top = 8.dp))

            SectionLabel("Export")
            Panel {
                Text("CSV export", style = MaterialTheme.typography.titleMedium)
                Text("Zip of products, sales, sale lines, stock ledger, payments, expenses, customers, suppliers, purchase orders and audit log. Amounts in rupees.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Export CSV (.zip)", Modifier.fillMaxWidth(), enabled = !busy) { exportLauncher.launch("$prefix-export-$stamp.zip") }
            }
            SectionLabel("Import")
            Panel {
                Text("Import products from CSV", style = MaterialTheme.typography.titleMedium)
                Text("Columns: name, sku, price (required); category, kind (sellable/ingredient), unit, cost, stock, reorder, barcode (optional). Existing SKUs are skipped, never overwritten. Opening stock is recorded in the ledger.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Choose CSV file", Modifier.fillMaxWidth(), enabled = !busy) { importLauncher.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/csv")) }
                report?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text("Created ${r.created} product(s); skipped ${r.skipped.size}.", color = Della.Accent, style = MaterialTheme.typography.bodyMedium)
                    ErrorList(r.skipped.take(20), Della.Warning)
                }
            }
            SectionLabel("Backup & restore")
            Panel {
                Text("Full backup", style = MaterialTheme.typography.titleMedium)
                Text("A complete, consistent copy of this workspace's database.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Save backup", Modifier.fillMaxWidth(), enabled = !busy) { backupLauncher.launch("$prefix-backup-$stamp.della") }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Restore from backup", Modifier.fillMaxWidth(), enabled = !busy, color = Della.Warning) { restoreLauncher.launch(arrayOf("*/*")) }
                Text("Restore replaces ALL data in the current workspace (${container.workspace.label}). The file is validated first.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint, modifier = Modifier.padding(top = 6.dp))
            }
            SectionLabel("Reset")
            Panel {
                Text(if (container.isDemo) "Reset demo" else "Erase business data", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (container.isDemo) "Deletes the demo database and regenerates fresh Macha Bakehouse data. Your business workspace is not touched."
                    else "Permanently deletes every record in your business workspace. Make a backup first. The demo is not touched.",
                    style = MaterialTheme.typography.bodySmall, color = Della.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(if (container.isDemo) "Reset demo data" else "Erase all business data", Modifier.fillMaxWidth(), color = Della.Danger) { confirmReset = true }
            }
        }
    }
    pendingRestore?.let { uri ->
        ConfirmDialog(
            "Replace current data?", "All records in ${container.workspace.label} will be replaced by the backup. DELLA restarts afterwards.",
            "Restore", destructive = true, onConfirm = { pendingRestore = null; vm.restore(uri) }, onDismiss = { pendingRestore = null },
        )
    }
    if (confirmReset) {
        val word = if (container.isDemo) "RESET" else "ERASE"
        ConfirmDialog(
            if (container.isDemo) "Reset demo?" else "Erase all business data?",
            "Type $word to confirm. This cannot be undone.", if (container.isDemo) "Reset" else "Erase",
            destructive = true, confirmEnabled = resetText.trim() == word,
            onConfirm = { confirmReset = false; container.resetCurrentWorkspace() }, onDismiss = { confirmReset = false; resetText = "" },
            content = { Field(resetText, { resetText = it }, "Type $word") },
        )
    }
}
