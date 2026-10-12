package com.della.app.ui.screens

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.della.app.AppContainer
import com.della.app.data.db.ScanStatus
import com.della.app.data.db.Supplier
import com.della.app.data.repo.InvoiceDraft
import com.della.app.data.repo.InvoiceLineDraft
import com.della.app.domain.Dates
import com.della.app.domain.InvoiceParser
import com.della.app.domain.Money
import com.della.app.ui.Routes
import com.della.app.ui.common.ChipRow
import com.della.app.ui.common.ConfirmDialog
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.Divider
import com.della.app.ui.common.EmptyState
import com.della.app.ui.common.ErrorList
import com.della.app.ui.common.Field
import com.della.app.ui.common.InfoRow
import com.della.app.ui.common.ListRow
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Messages
import com.della.app.ui.common.MoneyField
import com.della.app.ui.common.Panel
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.QtyField
import com.della.app.ui.common.SecondaryButton
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.common.SelectField
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.theme.Della
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition (ML Kit bundled Latin model). No network, no API key. */
suspend fun recognizeText(context: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val image = InputImage.fromFilePath(context, uri)
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it.text); recognizer.close() }
            .addOnFailureListener { cont.resumeWithException(it); recognizer.close() }
    } catch (e: Exception) {
        recognizer.close()
        cont.resumeWithException(e)
    }
}

class InvoicesViewModel(c: AppContainer) : DellaViewModel() {
    val scans = c.invoices.observeAll().state(emptyList())
}

@Composable
fun InvoicesScreen() {
    val vm = dellaViewModel { InvoicesViewModel(it) }
    val nav = LocalNav.current
    val scans by vm.scans.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DellaTopBar("Invoice capture", onBack = { nav.popBackStack() }, subtitle = "Review & approve — never auto-updates stock")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row {
                SecondaryButton("Camera", Modifier.weight(1f)) { nav.navigate(Routes.invoice(0, "OCR_CAMERA")) }
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Image", Modifier.weight(1f)) { nav.navigate(Routes.invoice(0, "OCR_IMPORT")) }
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Manual", Modifier.weight(1f)) { nav.navigate(Routes.invoice(0, "MANUAL")) }
            }
            Text("Approving an invoice creates a purchase order. Stock changes only when you receive the goods on that order.", style = MaterialTheme.typography.bodySmall, color = Della.TextFaint, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (scans.isEmpty()) EmptyState(Icons.Outlined.DocumentScanner, "No invoices captured", "Photograph or import a supplier invoice to extract its details.")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(scans, key = { it.id }) { s ->
                ListRow(
                    "${s.supplierName.ifBlank { "Unknown supplier" }} · ${s.invoiceNumber.ifBlank { "no number" }}",
                    "${Dates.dayTime(s.createdAt)} · ${s.source.lowercase().replace('_', ' ')}",
                    trailing = Money.format(s.totalPaise, false), trailingSub = s.status.lowercase(),
                    trailingColor = when (s.status) { ScanStatus.APPROVED -> Della.Accent; ScanStatus.REJECTED -> Della.TextFaint; else -> Della.Warning },
                    onClick = { nav.navigate(Routes.invoice(s.id)) },
                )
                Divider()
            }
        }
    }
}

class LineState(desc: String, productId: Long?, qty: String, cost: String) {
    var desc by mutableStateOf(desc)
    var productId by mutableStateOf(productId)
    var qty by mutableStateOf(qty)
    var cost by mutableStateOf(cost)
}

class InvoiceEditViewModel(private val c: AppContainer, private val initialId: Long, val source: String) : DellaViewModel() {
    val suppliers = c.parties.observeSuppliers().state(emptyList())
    val products = c.catalog.observeProducts().state(emptyList())

    var id by mutableStateOf(initialId)
    var status by mutableStateOf(ScanStatus.DRAFT)
    var linkedPo by mutableStateOf<Long?>(null)
    var supplierName by mutableStateOf("")
    var supplierId by mutableStateOf<Long?>(null)
    var invoiceNo by mutableStateOf("")
    var invoiceDate by mutableStateOf("")
    var total by mutableStateOf("")
    var rawText by mutableStateOf("")
    var ocrNotes by mutableStateOf<List<String>>(emptyList())
    var reading by mutableStateOf(false)
    val lines = mutableStateListOf<LineState>()
    val warnings = MutableStateFlow<List<String>>(emptyList())
    private var loaded = false

    suspend fun load() {
        if (loaded) return
        loaded = true
        if (initialId == 0L) return
        val s = c.invoices.scan(initialId) ?: return
        status = s.status; linkedPo = s.linkedPoId; supplierName = s.supplierName; supplierId = s.supplierId
        invoiceNo = s.invoiceNumber; invoiceDate = s.invoiceDate; total = if (s.totalPaise > 0) Money.toPlain(s.totalPaise) else ""; rawText = s.rawText
        lines.clear()
        c.invoices.lines(initialId).forEach { lines += LineState(it.description, it.productId, it.qty.toString(), Money.toPlain(it.unitCostPaise)) }
        refreshWarnings()
    }

    fun draft(): InvoiceDraft = InvoiceDraft(
        id = id, supplierName = supplierName, supplierId = supplierId, invoiceNumber = invoiceNo, invoiceDate = invoiceDate,
        totalPaise = Money.parse(total) ?: 0, rawText = rawText, source = source,
        lines = lines.map { InvoiceLineDraft(it.desc, it.productId, it.qty.toLongOrNull() ?: 0, Money.parse(it.cost) ?: 0) },
    )

    fun validation(): List<String> = c.invoices.validationErrors(draft())

    fun refreshWarnings() { viewModelScope.launch { warnings.value = c.invoices.duplicateWarnings(draft()) } }

    fun applyOcr(context: Context, uri: Uri) {
        reading = true
        viewModelScope.launch {
            try {
                val text = recognizeText(context, uri)
                rawText = text
                val p = InvoiceParser.parse(text)
                ocrNotes = p.notes
                if (p.supplierName.isNotBlank()) supplierName = p.supplierName
                val sup = suppliers.value.firstOrNull { s ->
                    val a = s.name.lowercase(); val b = p.supplierName.lowercase()
                    b.isNotBlank() && (a.contains(b.take(8)) || b.contains(a.take(8)))
                }
                if (sup != null) { supplierId = sup.id; supplierName = sup.name }
                if (p.invoiceNumber.isNotBlank()) invoiceNo = p.invoiceNumber
                if (p.invoiceDate.isNotBlank()) invoiceDate = p.invoiceDate
                if (p.totalPaise > 0) total = Money.toPlain(p.totalPaise)
                val candidates = products.value.map { it.product.id to it.product.name }
                lines.clear()
                p.lines.forEach { l -> lines += LineState(l.description, InvoiceParser.bestMatch(l.description, candidates), l.qty.toString(), Money.toPlain(l.unitCostPaise)) }
                messages.emit(if (text.isBlank()) "No text found in the image. Try a clearer, well-lit photo or enter manually." else "Text extracted. Check every field before saving.")
                refreshWarnings()
            } catch (e: Exception) {
                ocrNotes = listOf("Text recognition failed: ${e.message}. You can enter the invoice manually.")
            } finally {
                reading = false
            }
        }
    }

    fun save(onDone: () -> Unit = {}) = act("Draft saved. Stock unchanged.", onDone) { id = c.invoices.saveDraft(draft()); refreshWarnings() }
    fun approve(onDone: (Long) -> Unit) = act("Approved. Purchase order created — receive goods to update stock.") {
        val po = c.invoices.approve(draft())
        status = ScanStatus.APPROVED; linkedPo = po
        onDone(po)
    }
    fun reject(reason: String, onDone: () -> Unit) = act("Invoice rejected.", onDone) {
        if (id == 0L) id = c.invoices.saveDraft(draft())
        c.invoices.reject(id, reason)
    }
}

@Composable
fun InvoiceEditScreen(id: Long, source: String) {
    val vm = dellaViewModel("invoice-$id-$source") { InvoiceEditViewModel(it, id, source) }
    Messages(vm)
    val nav = LocalNav.current
    val context = LocalContext.current
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val warnings by vm.warnings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmApprove by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var autoLaunched by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let { vm.applyOcr(context, it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.applyOcr(context, it) } }
    fun launchCamera() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "invoice-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        captureUri = uri
        try { camera.launch(uri) } catch (e: Exception) { vm.ocrNotes = listOf("No camera app available. Import an image or enter manually.") }
    }

    LaunchedEffect(Unit) {
        vm.load()
        if (id == 0L && !autoLaunched) {
            autoLaunched = true
            when (source) { "OCR_CAMERA" -> launchCamera(); "OCR_IMPORT" -> picker.launch("image/*") }
        }
    }

    val editable = vm.status == ScanStatus.DRAFT
    val errors = vm.validation()
    val lineSum = vm.lines.sumOf { (it.qty.toLongOrNull() ?: 0) * (Money.parse(it.cost) ?: 0) }

    Column(Modifier.fillMaxSize()) {
        DellaTopBar(if (vm.id == 0L) "New invoice" else "Invoice", onBack = { nav.popBackStack() }, subtitle = "Status: ${vm.status.lowercase()}")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (editable) Row {
                SecondaryButton("Scan photo", Modifier.weight(1f), enabled = !vm.reading) { launchCamera() }
                Spacer(Modifier.width(8.dp))
                SecondaryButton("From image", Modifier.weight(1f), enabled = !vm.reading) { picker.launch("image/*") }
            }
            if (vm.reading) Text("Reading text on device…", color = Della.Accent, modifier = Modifier.padding(vertical = 8.dp))
            if (vm.ocrNotes.isNotEmpty()) { Spacer(Modifier.height(8.dp)); ErrorList(vm.ocrNotes, Della.Warning) }
            if (vm.status == ScanStatus.APPROVED && vm.linkedPo != null) {
                Spacer(Modifier.height(8.dp))
                Panel(onClick = { nav.navigate(Routes.po(vm.linkedPo!!)) }) {
                    Text("Approved → purchase order created", style = MaterialTheme.typography.titleMedium, color = Della.Accent)
                    Text("Open the order to receive goods. Stock has not been changed by this invoice.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                }
            }

            SectionLabel("Invoice details")
            SelectField("Supplier", suppliers, suppliers.firstOrNull { it.id == vm.supplierId }, { it.name }, { s: Supplier -> vm.supplierId = s.id; vm.supplierName = s.name; vm.refreshWarnings() }, placeholder = vm.supplierName.ifBlank { "Match to a supplier" })
            if (vm.supplierId == null && vm.supplierName.isNotBlank()) Text("Read as \"${vm.supplierName}\" — select the matching supplier, or add it under Suppliers.", style = MaterialTheme.typography.bodySmall, color = Della.Warning)
            Field(vm.invoiceNo, { vm.invoiceNo = it; vm.refreshWarnings() }, "Invoice number", enabled = editable)
            Field(vm.invoiceDate, { vm.invoiceDate = it }, "Invoice date (YYYY-MM-DD)", enabled = editable)
            MoneyField(vm.total, { vm.total = it; vm.refreshWarnings() }, "Invoice total")

            SectionLabel("Line items")
            vm.lines.forEachIndexed { i, l ->
                Panel(Modifier.padding(vertical = 4.dp), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(l.desc, { l.desc = it }, "Description", Modifier.weight(1f), enabled = editable)
                        if (editable) IconButton(onClick = { vm.lines.removeAt(i) }) { Icon(Icons.Outlined.Delete, "Remove line", tint = Della.TextMuted) }
                    }
                    SelectField("Product", products, products.firstOrNull { it.product.id == l.productId }, { it.product.name }, { l.productId = it.product.id }, placeholder = "Link to product")
                    Row {
                        QtyField(l.qty, { l.qty = it }, "Qty", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        MoneyField(l.cost, { l.cost = it }, "Unit cost", Modifier.weight(1f))
                    }
                }
            }
            if (editable) TextButton(onClick = { vm.lines += LineState("", null, "1", "") }) { Text("+ Add line", color = Della.Accent) }
            InfoRow("Lines total", Money.format(lineSum), if ((Money.parse(vm.total) ?: 0) == lineSum) Della.Accent else Della.Warning)

            if (editable) {
                SectionLabel("Checks before approval")
                if (errors.isEmpty() && warnings.isEmpty()) Text("✓ Ready to approve", color = Della.Accent, style = MaterialTheme.typography.bodyMedium)
                ErrorList(errors, Della.Warning)
                if (warnings.isNotEmpty()) { Spacer(Modifier.height(6.dp)); ErrorList(warnings.map { "Possible duplicate: $it" }) }
            }
            if (vm.rawText.isNotBlank()) {
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide extracted text" else "Show extracted text", color = Della.TextMuted) }
                if (showRaw) Panel { Text(vm.rawText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted) }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (editable) Column(Modifier.padding(16.dp)) {
            PrimaryButton("Approve → create purchase order", Modifier.fillMaxWidth(), enabled = !busy && errors.isEmpty() && warnings.isEmpty()) { confirmApprove = true }
            Spacer(Modifier.height(8.dp))
            Row {
                SecondaryButton("Save draft", Modifier.weight(1f), enabled = !busy) { vm.save() }
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Reject", Modifier.weight(1f), color = Della.Danger, enabled = !busy) { rejecting = true }
            }
        }
    }
    if (confirmApprove) ConfirmDialog(
        "Approve invoice ${vm.invoiceNo}?",
        "Creates a purchase order to ${vm.supplierName} for ${Money.format(lineSum)} with ${vm.lines.size} line(s). Stock does NOT change until you receive the goods on that order.",
        "Approve", onConfirm = { confirmApprove = false; vm.approve { } }, onDismiss = { confirmApprove = false },
    )
    if (rejecting) ConfirmDialog(
        "Reject invoice?", "Keeps the capture for history, marked rejected. Nothing else changes.", "Reject", destructive = true,
        onConfirm = { rejecting = false; vm.reject(rejectReason) { nav.popBackStack() } }, onDismiss = { rejecting = false },
        content = { Field(rejectReason, { rejectReason = it }, "Reason (e.g. duplicate, wrong supplier)") },
    )
}
