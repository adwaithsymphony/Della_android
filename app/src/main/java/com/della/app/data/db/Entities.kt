package com.della.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Money is stored as Long paise (1 INR = 100 paise). Tax rates are stored as
 * Int basis points (500 = 5.00%). No floating point is used for persisted amounts.
 * Quantities are whole units (pieces, kg, trays…) as Long.
 */

object TaxMode {
    const val NONE = "NONE"
    const val EXCLUSIVE = "EXCLUSIVE"   // tax added on top of price
    const val INCLUSIVE = "INCLUSIVE"   // price already contains tax
}

object ProductKind {
    const val SELLABLE = "SELLABLE"
    const val INGREDIENT = "INGREDIENT"
}

object MovementType {
    const val OPENING = "OPENING"
    const val SALE = "SALE"
    const val SALE_REVERSAL = "SALE_REVERSAL"
    const val PURCHASE_RECEIPT = "PURCHASE_RECEIPT"
    const val PRODUCTION = "PRODUCTION"
    const val USAGE = "USAGE"
    const val WASTAGE = "WASTAGE"
    const val ADJUSTMENT = "ADJUSTMENT"
    const val IMPORT = "IMPORT"
}

object SaleStatus {
    const val COMPLETED = "COMPLETED"
    const val REVERSED = "REVERSED"
}

object PayMethod {
    const val CASH = "CASH"
    const val UPI = "UPI"
    const val CARD = "CARD"
    const val CREDIT = "CREDIT"   // sale on account: nothing received yet
    const val BANK = "BANK"
    val tender = listOf(CASH, UPI, CARD, BANK)
}

object PaymentDirection {
    const val IN = "IN"
    const val OUT = "OUT"
}

object PaymentRef {
    const val SALE = "SALE"
    const val SALE_REFUND = "SALE_REFUND"
    const val CUSTOMER_SETTLEMENT = "CUSTOMER_SETTLEMENT"
    const val PURCHASE = "PURCHASE"
    const val EXPENSE = "EXPENSE"
}

object PoStatus {
    const val ORDERED = "ORDERED"
    const val PARTIAL = "PARTIAL"
    const val RECEIVED = "RECEIVED"
    const val CANCELLED = "CANCELLED"
}

object ScanStatus {
    const val DRAFT = "DRAFT"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
}

@Entity(tableName = "business_profile")
data class BusinessProfile(
    @PrimaryKey val id: Int = 1,
    val businessName: String,
    val ownerName: String = "",
    val phone: String = "",
    val address: String = "",
    val gstin: String = "",
    val currency: String = "INR",
    val taxMode: String = TaxMode.NONE,
    val defaultTaxBps: Int = 0,
    val receiptFooter: String = "Thank you for visiting!",
    val lowStockAlerts: Boolean = true,
    val allowCreditSales: Boolean = true,
    val configured: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["sku"], unique = true),
        Index(value = ["barcode"]),
        Index(value = ["categoryId"]),
        Index(value = ["name"]),
    ],
    foreignKeys = [
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sku: String,
    val barcode: String = "",
    val categoryId: Long? = null,
    val kind: String = ProductKind.SELLABLE,
    val unit: String = "pc",
    val costPaise: Long = 0,
    val pricePaise: Long = 0,
    /** null → use business default tax rate */
    val taxBps: Int? = null,
    /** Cached balance; always changed in the same transaction as a StockMovement. */
    val stockQty: Long = 0,
    val reorderLevel: Long = 0,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "stock_movements",
    indices = [Index(value = ["productId"]), Index(value = ["createdAt"]), Index(value = ["refType", "refId"])],
    foreignKeys = [
        ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class StockMovement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val qtyDelta: Long,
    val balanceAfter: Long,
    val type: String,
    val refType: String? = null,
    val refId: Long? = null,
    val unitCostPaise: Long = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "customers", indices = [Index(value = ["name"]), Index(value = ["phone"])])
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sales",
    indices = [
        Index(value = ["number"], unique = true), Index(value = ["requestId"], unique = true),
        Index(value = ["createdAt"]), Index(value = ["customerId"]), Index(value = ["status"]),
    ],
    foreignKeys = [
        ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    /** Idempotency key generated when the cart is opened; a retried checkout cannot create a second sale. */
    val requestId: String,
    val customerId: Long? = null,
    val status: String = SaleStatus.COMPLETED,
    /** Sum of qty × unit price before discount. */
    val grossPaise: Long,
    val discountPaise: Long,
    /** Revenue excluding tax, after discount. */
    val netPaise: Long,
    val taxPaise: Long,
    /** Amount the customer owes for this sale (net + tax). */
    val totalPaise: Long,
    /** Cash actually received against this sale (net of refunds). */
    val paidPaise: Long,
    val paymentMethod: String,
    val taxMode: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val reversedAt: Long? = null,
    val reversalReason: String? = null,
)

@Entity(
    tableName = "sale_lines",
    indices = [Index(value = ["saleId"]), Index(value = ["productId"])],
    foreignKeys = [
        ForeignKey(entity = Sale::class, parentColumns = ["id"], childColumns = ["saleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class SaleLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long,
    val productName: String,
    val qty: Long,
    val unitPricePaise: Long,
    /** Cost snapshot at time of sale, for margin calculations. */
    val unitCostPaise: Long,
    val discountPaise: Long,
    val taxBps: Int,
    val taxPaise: Long,
    val netPaise: Long,
    val totalPaise: Long,
)

@Entity(
    tableName = "payments",
    indices = [Index(value = ["createdAt"]), Index(value = ["refType", "refId"])],
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,
    val amountPaise: Long,
    val method: String,
    val refType: String,
    val refId: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "suppliers", indices = [Index(value = ["name"])])
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val contactPerson: String = "",
    val phone: String = "",
    val email: String = "",
    val gstin: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "purchase_orders",
    indices = [Index(value = ["number"], unique = true), Index(value = ["supplierId"]), Index(value = ["status"])],
    foreignKeys = [
        ForeignKey(entity = Supplier::class, parentColumns = ["id"], childColumns = ["supplierId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class PurchaseOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val supplierId: Long,
    val status: String = PoStatus.ORDERED,
    val supplierInvoiceNo: String = "",
    val totalPaise: Long = 0,
    val paidPaise: Long = 0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "purchase_order_lines",
    indices = [Index(value = ["poId"]), Index(value = ["productId"])],
    foreignKeys = [
        ForeignKey(entity = PurchaseOrder::class, parentColumns = ["id"], childColumns = ["poId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class PurchaseOrderLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val poId: Long,
    val productId: Long,
    val qtyOrdered: Long,
    val qtyReceived: Long = 0,
    val unitCostPaise: Long,
)

@Entity(
    tableName = "goods_receipts",
    indices = [Index(value = ["poId"]), Index(value = ["createdAt"])],
    foreignKeys = [
        ForeignKey(entity = PurchaseOrder::class, parentColumns = ["id"], childColumns = ["poId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class GoodsReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val poId: Long,
    val valuePaise: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "invoice_scans",
    indices = [Index(value = ["fingerprint"]), Index(value = ["status"])],
)
data class InvoiceScan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val supplierName: String = "",
    val supplierId: Long? = null,
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val totalPaise: Long = 0,
    val rawText: String = "",
    val source: String = "MANUAL",   // OCR_CAMERA, OCR_IMPORT, MANUAL
    val status: String = ScanStatus.DRAFT,
    /** Normalised supplier + invoice number + total, used for duplicate detection. */
    val fingerprint: String = "",
    val linkedPoId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val decidedAt: Long? = null,
)

@Entity(
    tableName = "invoice_scan_lines",
    indices = [Index(value = ["scanId"])],
    foreignKeys = [
        ForeignKey(entity = InvoiceScan::class, parentColumns = ["id"], childColumns = ["scanId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class InvoiceScanLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    val description: String,
    val productId: Long? = null,
    val qty: Long = 1,
    val unitCostPaise: Long = 0,
)

@Entity(tableName = "expenses", indices = [Index(value = ["spentAt"]), Index(value = ["category"])])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val description: String,
    val vendor: String = "",
    val amountPaise: Long,
    val paidPaise: Long,
    val method: String = PayMethod.CASH,
    val spentAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "audit_log", indices = [Index(value = ["createdAt"]), Index(value = ["entityType"])])
data class AuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val entityType: String,
    val entityId: Long? = null,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object ExpenseCategories {
    val all = listOf("Rent", "Wages", "Utilities", "Gas & fuel", "Packaging", "Repairs", "Marketing", "Transport", "Fees & licences", "Other")
}
