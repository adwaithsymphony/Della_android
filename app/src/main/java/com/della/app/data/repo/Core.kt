package com.della.app.data.repo

import com.della.app.data.db.AuditEntry
import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.StockMovement

/** A user-facing business rule violation (shown verbatim in the UI). */
class BusinessRuleException(message: String) : Exception(message)

fun rule(condition: Boolean, message: () -> String) {
    if (!condition) throw BusinessRuleException(message())
}

/** Writes audit entries. Callers invoke it inside the same transaction as the change. */
class Audit(private val db: DellaDatabase) {
    suspend fun log(action: String, entityType: String, entityId: Long?, summary: String, at: Long = System.currentTimeMillis()) {
        db.auditDao().insert(AuditEntry(action = action, entityType = entityType, entityId = entityId, summary = summary, createdAt = at))
    }
}

/**
 * The only code path that changes a product's stock balance. It must run inside a
 * transaction: it writes the ledger movement and the cached balance together and
 * refuses any movement that would make stock negative.
 */
class StockLedger(private val db: DellaDatabase) {
    suspend fun move(
        productId: Long,
        delta: Long,
        type: String,
        refType: String? = null,
        refId: Long? = null,
        unitCostPaise: Long = 0,
        note: String = "",
        at: Long = System.currentTimeMillis(),
    ): Long {
        rule(delta != 0L) { "Quantity must not be zero." }
        val product = db.catalogDao().product(productId) ?: throw BusinessRuleException("Product no longer exists.")
        val newBalance = product.stockQty + delta
        rule(newBalance >= 0) {
            "Not enough stock for ${product.name}: ${product.stockQty} ${product.unit} available, ${-delta} needed."
        }
        db.catalogDao().setStock(productId, newBalance, at)
        return db.stockDao().insert(
            StockMovement(
                productId = productId, qtyDelta = delta, balanceAfter = newBalance, type = type,
                refType = refType, refId = refId, unitCostPaise = unitCostPaise, note = note, createdAt = at,
            )
        )
    }
}
