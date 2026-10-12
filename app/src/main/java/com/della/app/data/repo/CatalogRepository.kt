package com.della.app.data.repo

import androidx.room.withTransaction
import com.della.app.data.db.BusinessProfile
import com.della.app.data.db.Category
import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.MovementType
import com.della.app.data.db.Product
import com.della.app.data.db.ProductKind
import com.della.app.domain.Money
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val db: DellaDatabase, private val audit: Audit) {
    fun observe(): Flow<BusinessProfile?> = db.profileDao().observe()
    suspend fun get(): BusinessProfile? = db.profileDao().get()

    suspend fun save(updated: BusinessProfile) {
        rule(updated.businessName.isNotBlank()) { "Business name is required." }
        rule(updated.defaultTaxBps in 0..10_000) { "Tax rate must be between 0% and 100%." }
        db.withTransaction {
            val old = db.profileDao().get()
            val changes = buildList {
                if (old == null) add("profile created")
                else {
                    if (old.businessName != updated.businessName) add("name → ${updated.businessName}")
                    if (old.taxMode != updated.taxMode) add("tax mode ${old.taxMode} → ${updated.taxMode}")
                    if (old.defaultTaxBps != updated.defaultTaxBps) add("default tax ${Money.pct(old.defaultTaxBps)} → ${Money.pct(updated.defaultTaxBps)}")
                    if (old.gstin != updated.gstin) add("GSTIN updated")
                    if (old.phone != updated.phone || old.address != updated.address || old.ownerName != updated.ownerName) add("contact details updated")
                    if (old.lowStockAlerts != updated.lowStockAlerts) add("low-stock alerts ${if (updated.lowStockAlerts) "on" else "off"}")
                    if (old.allowCreditSales != updated.allowCreditSales) add("credit sales ${if (updated.allowCreditSales) "allowed" else "disabled"}")
                    if (old.receiptFooter != updated.receiptFooter) add("receipt footer updated")
                }
            }
            db.profileDao().upsert(updated.copy(id = 1, configured = true, updatedAt = System.currentTimeMillis()))
            if (changes.isNotEmpty()) audit.log("SETTINGS_CHANGED", "settings", 1, changes.joinToString("; "))
        }
    }
}

data class ProductDraft(
    val id: Long = 0,
    val name: String,
    val sku: String,
    val barcode: String,
    val categoryName: String,
    val kind: String,
    val unit: String,
    val costPaise: Long,
    val pricePaise: Long,
    val taxBps: Int?,
    val reorderLevel: Long,
    val openingStock: Long,
    val active: Boolean = true,
)

class CatalogRepository(private val db: DellaDatabase, private val audit: Audit, private val ledger: StockLedger) {
    private val dao = db.catalogDao()

    fun observeProducts(includeInactive: Boolean = false) = dao.observeProducts(includeInactive)
    fun observeCategories() = dao.observeCategories()
    fun observeProduct(id: Long) = dao.observeProduct(id)
    suspend fun product(id: Long) = dao.product(id)
    suspend fun findByCode(code: String): Product? =
        dao.productByBarcode(code.trim()) ?: dao.productBySku(code.trim())

    private suspend fun categoryId(name: String): Long? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return dao.categoryByName(n)?.id ?: dao.insertCategory(Category(name = n))
    }

    private suspend fun validate(d: ProductDraft) {
        rule(d.name.isNotBlank()) { "Product name is required." }
        rule(d.sku.isNotBlank()) { "SKU is required." }
        rule(d.sku.trim().matches(Regex("[A-Za-z0-9._-]{1,32}"))) { "SKU may only contain letters, digits, dot, dash and underscore (max 32)." }
        rule(d.unit.isNotBlank()) { "Unit is required (e.g. pc, kg, tray)." }
        rule(d.costPaise >= 0 && d.pricePaise >= 0) { "Prices cannot be negative." }
        rule(d.kind == ProductKind.INGREDIENT || d.pricePaise > 0) { "Selling price is required for sellable items." }
        rule(d.reorderLevel >= 0) { "Reorder level cannot be negative." }
        rule(d.taxBps == null || d.taxBps in 0..10_000) { "Tax rate must be between 0% and 100%." }
        val clash = dao.productBySku(d.sku.trim())
        rule(clash == null || clash.id == d.id) { "SKU ${d.sku} is already used by ${clash?.name}." }
        if (d.barcode.isNotBlank()) {
            val bc = dao.productByBarcode(d.barcode.trim())
            rule(bc == null || bc.id == d.id) { "Barcode is already used by ${bc?.name}." }
        }
    }

    suspend fun save(d: ProductDraft): Long = db.withTransaction {
        validate(d)
        val now = System.currentTimeMillis()
        if (d.id == 0L) {
            rule(d.openingStock >= 0) { "Opening stock cannot be negative." }
            val id = dao.insertProduct(
                Product(
                    name = d.name.trim(), sku = d.sku.trim().uppercase(), barcode = d.barcode.trim(),
                    categoryId = categoryId(d.categoryName), kind = d.kind, unit = d.unit.trim(),
                    costPaise = d.costPaise, pricePaise = d.pricePaise, taxBps = d.taxBps,
                    stockQty = 0, reorderLevel = d.reorderLevel, active = d.active, createdAt = now, updatedAt = now,
                )
            )
            if (d.openingStock > 0) {
                ledger.move(id, d.openingStock, MovementType.OPENING, "product", id, d.costPaise, "Opening stock", now)
            }
            audit.log("PRODUCT_CREATED", "product", id, "${d.name.trim()} (${d.sku.uppercase()}) price ${Money.format(d.pricePaise)}, opening stock ${d.openingStock}")
            id
        } else {
            val old = dao.product(d.id) ?: throw BusinessRuleException("Product not found.")
            val updated = old.copy(
                name = d.name.trim(), sku = d.sku.trim().uppercase(), barcode = d.barcode.trim(),
                categoryId = categoryId(d.categoryName), kind = d.kind, unit = d.unit.trim(),
                costPaise = d.costPaise, pricePaise = d.pricePaise, taxBps = d.taxBps,
                reorderLevel = d.reorderLevel, active = d.active, updatedAt = now,
            )
            dao.updateProduct(updated)
            val changes = buildList {
                if (old.pricePaise != updated.pricePaise) add("price ${Money.format(old.pricePaise)} → ${Money.format(updated.pricePaise)}")
                if (old.costPaise != updated.costPaise) add("cost ${Money.format(old.costPaise)} → ${Money.format(updated.costPaise)}")
                if (old.name != updated.name) add("renamed from ${old.name}")
                if (old.reorderLevel != updated.reorderLevel) add("reorder level ${old.reorderLevel} → ${updated.reorderLevel}")
                if (old.active != updated.active) add(if (updated.active) "re-activated" else "archived")
                if (old.taxBps != updated.taxBps) add("tax override changed")
            }
            audit.log("PRODUCT_UPDATED", "product", d.id, "${updated.name}: " + changes.ifEmpty { listOf("details edited") }.joinToString("; "))
            d.id
        }
    }

    /** Deletes products with no history; products with history are archived to preserve records. */
    suspend fun deleteOrArchive(id: Long): String = db.withTransaction {
        val p = dao.product(id) ?: throw BusinessRuleException("Product not found.")
        if (dao.productReferenceCount(id) == 0) {
            dao.deleteProduct(id)
            audit.log("PRODUCT_DELETED", "product", id, "${p.name} deleted (no history)")
            "${p.name} deleted."
        } else {
            dao.updateProduct(p.copy(active = false, updatedAt = System.currentTimeMillis()))
            audit.log("PRODUCT_ARCHIVED", "product", id, "${p.name} archived (has sales or stock history)")
            "${p.name} has history, so it was archived instead of deleted."
        }
    }

    suspend fun nextSku(prefix: String): String {
        val base = prefix.filter { it.isLetterOrDigit() }.uppercase().take(4).ifEmpty { "ITEM" }
        var n = 1
        while (dao.productBySku("$base-%03d".format(n)) != null) n++
        return "$base-%03d".format(n)
    }
}

class InventoryRepository(private val db: DellaDatabase, private val audit: Audit, private val ledger: StockLedger) {
    fun observeRecent(limit: Int = 200) = db.stockDao().observeRecent(limit)
    fun observeForProduct(productId: Long) = db.stockDao().observeForProduct(productId)

    /** Records production, usage, wastage or a correction. Reason is mandatory for corrections. */
    suspend fun adjust(productId: Long, delta: Long, type: String, reason: String): Long = db.withTransaction {
        rule(type in listOf(MovementType.PRODUCTION, MovementType.USAGE, MovementType.WASTAGE, MovementType.ADJUSTMENT)) { "Unsupported adjustment type." }
        rule(delta != 0L) { "Enter a quantity." }
        rule(type != MovementType.PRODUCTION || delta > 0) { "Production must add stock." }
        rule(type !in listOf(MovementType.USAGE, MovementType.WASTAGE) || delta < 0) { "Usage and wastage remove stock." }
        rule(type != MovementType.ADJUSTMENT || reason.isNotBlank()) { "A reason is required for stock corrections." }
        val p = db.catalogDao().product(productId) ?: throw BusinessRuleException("Product not found.")
        val id = ledger.move(productId, delta, type, "adjustment", null, p.costPaise, reason.trim())
        audit.log("STOCK_$type", "product", productId, "${p.name}: ${if (delta > 0) "+" else ""}$delta ${p.unit}${if (reason.isNotBlank()) " — ${reason.trim()}" else ""}")
        id
    }

    /** Physical count: sets stock to the counted quantity by posting the difference. */
    suspend fun recordCount(productId: Long, counted: Long, reason: String): Long? = db.withTransaction {
        rule(counted >= 0) { "Counted quantity cannot be negative." }
        val p = db.catalogDao().product(productId) ?: throw BusinessRuleException("Product not found.")
        val delta = counted - p.stockQty
        if (delta == 0L) null
        else {
            val id = ledger.move(productId, delta, MovementType.ADJUSTMENT, "count", null, p.costPaise, "Stock count: ${reason.ifBlank { "physical count" }}")
            audit.log("STOCK_COUNT", "product", productId, "${p.name}: counted $counted (system had ${p.stockQty}, diff ${if (delta > 0) "+" else ""}$delta)")
            id
        }
    }
}
