package com.della.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.ProductKind
import com.della.app.data.repo.Audit
import com.della.app.data.repo.BusinessRuleException
import com.della.app.data.repo.CatalogRepository
import com.della.app.data.repo.ProductDraft
import com.della.app.domain.Dates
import com.della.app.domain.Money
import java.io.File
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImportReport(val created: Int, val skipped: List<String>)

/**
 * CSV export (zip of tables), product CSV import, and whole-database backup/restore.
 * Files are only read/written through Storage Access Framework URIs chosen by the user.
 */
class DataTransfer(
    private val context: Context,
    private val db: DellaDatabase,
    private val dbFileName: String,
    private val catalog: CatalogRepository,
    private val audit: Audit,
) {
    private fun csv(vararg v: Any?): String = v.joinToString(",") { field ->
        val s = field?.toString() ?: ""
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    suspend fun exportCsvZip(uri: Uri): Int {
        var files = 0
        context.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { zip ->
                fun entry(name: String, header: String, rows: List<String>) {
                    zip.putNextEntry(ZipEntry(name))
                    val w = OutputStreamWriter(zip, Charsets.UTF_8)
                    w.write(header + "\n")
                    rows.forEach { w.write(it + "\n") }
                    w.flush()
                    zip.closeEntry()
                    files++
                }
                val products = db.catalogDao().allProducts()
                entry("products.csv", "id,sku,name,category,kind,unit,cost_inr,price_inr,tax_pct_override,stock_qty,reorder_level,active,barcode",
                    products.map { r -> val p = r.product; csv(p.id, p.sku, p.name, r.categoryName, p.kind, p.unit, Money.toPlain(p.costPaise), Money.toPlain(p.pricePaise), p.taxBps?.let { Money.pct(it).removeSuffix("%") }, p.stockQty, p.reorderLevel, p.active, p.barcode) })
                entry("sales.csv", "id,number,date,status,customer_id,gross_inr,discount_inr,net_revenue_inr,tax_inr,total_inr,received_inr,payment_method,reversal_reason",
                    db.salesDao().all().map { s -> csv(s.id, s.number, Dates.stamp(s.createdAt), s.status, s.customerId, Money.toPlain(s.grossPaise), Money.toPlain(s.discountPaise), Money.toPlain(s.netPaise), Money.toPlain(s.taxPaise), Money.toPlain(s.totalPaise), Money.toPlain(s.paidPaise), s.paymentMethod, s.reversalReason) })
                entry("sale_lines.csv", "sale_id,product_id,product,qty,unit_price_inr,unit_cost_inr,discount_inr,tax_pct,tax_inr,net_inr,total_inr",
                    db.salesDao().allLines().map { l -> csv(l.saleId, l.productId, l.productName, l.qty, Money.toPlain(l.unitPricePaise), Money.toPlain(l.unitCostPaise), Money.toPlain(l.discountPaise), Money.pct(l.taxBps).removeSuffix("%"), Money.toPlain(l.taxPaise), Money.toPlain(l.netPaise), Money.toPlain(l.totalPaise)) })
                entry("stock_movements.csv", "id,date,product,type,qty_change,balance_after,unit_cost_inr,reference,note",
                    db.stockDao().allRows().map { r -> val m = r.movement; csv(m.id, Dates.stamp(m.createdAt), r.productName, m.type, m.qtyDelta, m.balanceAfter, Money.toPlain(m.unitCostPaise), listOfNotNull(m.refType, m.refId).joinToString(":"), m.note) })
                entry("payments.csv", "id,date,direction,amount_inr,method,reference_type,reference_id,note",
                    db.financeDao().allPayments().map { p -> csv(p.id, Dates.stamp(p.createdAt), p.direction, Money.toPlain(p.amountPaise), p.method, p.refType, p.refId, p.note) })
                entry("expenses.csv", "id,date,category,description,vendor,amount_inr,paid_inr,method",
                    db.financeDao().allExpenses().map { e -> csv(e.id, Dates.stamp(e.spentAt), e.category, e.description, e.vendor, Money.toPlain(e.amountPaise), Money.toPlain(e.paidPaise), e.method) })
                entry("customers.csv", "id,name,phone,email,notes", db.partyDao().customers().map { c -> csv(c.id, c.name, c.phone, c.email, c.notes) })
                entry("suppliers.csv", "id,name,contact,phone,email,gstin", db.partyDao().suppliers().map { s -> csv(s.id, s.name, s.contactPerson, s.phone, s.email, s.gstin) })
                entry("purchase_orders.csv", "id,number,supplier_id,status,supplier_invoice,total_inr,paid_inr,created",
                    db.purchaseDao().all().map { p -> csv(p.id, p.number, p.supplierId, p.status, p.supplierInvoiceNo, Money.toPlain(p.totalPaise), Money.toPlain(p.paidPaise), Dates.stamp(p.createdAt)) })
                entry("audit_log.csv", "id,date,action,entity,entity_id,summary",
                    db.auditDao().all().map { a -> csv(a.id, Dates.stamp(a.createdAt), a.action, a.entityType, a.entityId, a.summary) })
            }
        } ?: throw BusinessRuleException("Could not open the chosen file for writing.")
        audit.log("DATA_EXPORTED", "data", null, "CSV export of $files tables")
        return files
    }

    /** Minimal RFC-4180 CSV parser (quotes, escaped quotes, commas, newlines in quotes). */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                } else sb.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(sb.toString()); sb.clear() }
                '\r' -> {}
                '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = mutableListOf() }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /**
     * Imports products. Required columns: name, sku, price. Optional: category, kind, unit, cost,
     * stock (opening quantity), reorder, barcode. Existing SKUs are skipped (never overwritten).
     */
    suspend fun importProducts(uri: Uri): ImportReport {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw BusinessRuleException("Could not read the chosen file.")
        val rows = parseCsv(text.removePrefix("﻿"))
        if (rows.size < 2) throw BusinessRuleException("The file has no data rows.")
        val header = rows.first().map { it.trim().lowercase().replace(" ", "_") }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val iName = col("name", "product") ?: throw BusinessRuleException("Missing 'name' column.")
        val iSku = col("sku", "code") ?: throw BusinessRuleException("Missing 'sku' column.")
        val iPrice = col("price", "price_inr", "selling_price") ?: throw BusinessRuleException("Missing 'price' column.")
        val iCat = col("category"); val iKind = col("kind", "type"); val iUnit = col("unit")
        val iCost = col("cost", "cost_inr"); val iStock = col("stock", "stock_qty", "opening_stock")
        val iReorder = col("reorder", "reorder_level"); val iBarcode = col("barcode")
        var created = 0
        val skipped = mutableListOf<String>()
        db.withTransaction {
            rows.drop(1).forEachIndexed { idx, r ->
                fun v(i: Int?) = i?.let { r.getOrNull(it)?.trim() } ?: ""
                val line = idx + 2
                try {
                    val sku = v(iSku)
                    if (sku.isNotBlank() && db.catalogDao().productBySku(sku) != null) { skipped += "Row $line: SKU $sku already exists"; return@forEachIndexed }
                    val kind = if (v(iKind).lowercase().startsWith("ing")) ProductKind.INGREDIENT else ProductKind.SELLABLE
                    val price = if (v(iPrice).isBlank() && kind == ProductKind.INGREDIENT) 0L else Money.parse(v(iPrice)) ?: throw BusinessRuleException("invalid price '${v(iPrice)}'")
                    val cost = if (v(iCost).isBlank()) 0L else Money.parse(v(iCost)) ?: throw BusinessRuleException("invalid cost '${v(iCost)}'")
                    val stock = if (v(iStock).isBlank()) 0L else v(iStock).toLongOrNull()?.takeIf { it >= 0 } ?: throw BusinessRuleException("invalid stock '${v(iStock)}'")
                    val reorder = if (v(iReorder).isBlank()) 0L else v(iReorder).toLongOrNull()?.takeIf { it >= 0 } ?: throw BusinessRuleException("invalid reorder '${v(iReorder)}'")
                    catalog.save(ProductDraft(name = v(iName), sku = sku, barcode = v(iBarcode), categoryName = v(iCat), kind = kind,
                        unit = v(iUnit).ifBlank { "pc" }, costPaise = cost, pricePaise = price, taxBps = null, reorderLevel = reorder, openingStock = stock))
                    created++
                } catch (e: BusinessRuleException) {
                    skipped += "Row $line: ${e.message}"
                }
            }
            audit.log("DATA_IMPORTED", "product", null, "Product CSV import: $created created, ${skipped.size} skipped")
        }
        return ImportReport(created, skipped)
    }

    /** Copies a consistent snapshot of the database (TRUNCATE journal: single file) to the URI. */
    suspend fun backup(uri: Uri) {
        audit.log("BACKUP_CREATED", "data", null, "Database backup exported")
        // Holding a transaction blocks concurrent writers while the single DB file is copied.
        db.withTransaction {
            val file = context.getDatabasePath(dbFileName)
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw BusinessRuleException("Could not open the chosen file for writing.")
        }
    }

    /**
     * Validates a backup file (SQLite header + required DELLA tables + matching schema version)
     * into a temporary file. The caller then closes the database and calls [installRestore].
     */
    fun prepareRestore(uri: Uri): File {
        val tmp = File(context.cacheDir, "restore-candidate.db")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw BusinessRuleException("Could not read the chosen file.")
        val header = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") { tmp.delete(); throw BusinessRuleException("This is not a DELLA backup file.") }
        val check = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = mutableSetOf<String>()
            check.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            val missing = DellaDatabase.REQUIRED_TABLES.filter { it !in tables }
            if (missing.isNotEmpty()) throw BusinessRuleException("Backup is missing tables: ${missing.joinToString()}.")
            val version = check.version
            if (version != 1) throw BusinessRuleException("Backup schema version $version is not supported by this app version.")
            check.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst() && c.getString(0) != "ok") throw BusinessRuleException("Backup file is corrupted.") }
        } catch (e: BusinessRuleException) {
            check.close(); tmp.delete(); throw e
        }
        check.close()
        return tmp
    }

    /** Must be called after the Room database is closed. Keeps the previous DB as .bak until success. */
    fun installRestore(candidate: File) {
        val target = context.getDatabasePath(dbFileName)
        val bak = File(target.path + ".bak")
        if (target.exists()) target.copyTo(bak, overwrite = true)
        try {
            listOf("-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            candidate.copyTo(target, overwrite = true)
            candidate.delete()
            bak.delete()
        } catch (e: Exception) {
            if (bak.exists()) bak.copyTo(target, overwrite = true)
            throw e
        }
    }
}
