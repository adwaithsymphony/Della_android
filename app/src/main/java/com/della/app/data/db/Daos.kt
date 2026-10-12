package com.della.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ProductRow(
    @Embedded val product: Product,
    val categoryName: String?,
)

data class LineWithSale(
    @Embedded val line: SaleLine,
    val createdAt: Long,
    val status: String,
    val customerId: Long?,
)

data class MovementRow(
    @Embedded val movement: StockMovement,
    val productName: String,
    val unit: String,
)

data class PoRow(
    @Embedded val po: PurchaseOrder,
    val supplierName: String,
)

data class PoLineRow(
    @Embedded val line: PurchaseOrderLine,
    val productName: String,
    val unit: String,
)

data class SaleRow(
    @Embedded val sale: Sale,
    val customerName: String?,
)

data class CustomerStats(
    @Embedded val customer: Customer,
    val saleCount: Int,
    val totalPaise: Long,
    val outstandingPaise: Long,
    val lastSaleAt: Long?,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM business_profile WHERE id = 1")
    fun observe(): Flow<BusinessProfile?>

    @Query("SELECT * FROM business_profile WHERE id = 1")
    suspend fun get(): BusinessProfile?

    @Upsert
    suspend fun upsert(profile: BusinessProfile)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY name")
    suspend fun categories(): List<Category>

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun categoryByName(name: String): Category?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: Category): Long

    @Query(
        """SELECT p.*, c.name AS categoryName FROM products p
           LEFT JOIN categories c ON c.id = p.categoryId
           WHERE (:includeInactive OR p.active = 1)
           ORDER BY p.active DESC, p.name COLLATE NOCASE"""
    )
    fun observeProducts(includeInactive: Boolean): Flow<List<ProductRow>>

    @Query("SELECT p.*, c.name AS categoryName FROM products p LEFT JOIN categories c ON c.id = p.categoryId ORDER BY p.name COLLATE NOCASE")
    suspend fun allProducts(): List<ProductRow>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun product(id: Long): Product?

    @Query("SELECT * FROM products WHERE id = :id")
    fun observeProduct(id: Long): Flow<Product?>

    @Query("SELECT * FROM products WHERE sku = :sku COLLATE NOCASE LIMIT 1")
    suspend fun productBySku(sku: String): Product?

    @Query("SELECT * FROM products WHERE barcode = :barcode AND barcode != '' LIMIT 1")
    suspend fun productByBarcode(barcode: String): Product?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProduct(product: Product): Long

    @Update
    suspend fun updateProduct(product: Product)

    @Query("UPDATE products SET stockQty = :qty, updatedAt = :now WHERE id = :id")
    suspend fun setStock(id: Long, qty: Long, now: Long)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: Long)

    @Query(
        """SELECT (SELECT COUNT(*) FROM stock_movements WHERE productId = :id)
             + (SELECT COUNT(*) FROM sale_lines WHERE productId = :id)
             + (SELECT COUNT(*) FROM purchase_order_lines WHERE productId = :id)"""
    )
    suspend fun productReferenceCount(id: Long): Int
}

@Dao
interface StockDao {
    @Insert
    suspend fun insert(movement: StockMovement): Long

    @Query(
        """SELECT m.*, p.name AS productName, p.unit AS unit FROM stock_movements m
           JOIN products p ON p.id = m.productId
           ORDER BY m.createdAt DESC, m.id DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<MovementRow>>

    @Query(
        """SELECT m.*, p.name AS productName, p.unit AS unit FROM stock_movements m
           JOIN products p ON p.id = m.productId
           WHERE m.productId = :productId ORDER BY m.createdAt DESC, m.id DESC"""
    )
    fun observeForProduct(productId: Long): Flow<List<MovementRow>>

    @Query("SELECT * FROM stock_movements WHERE createdAt BETWEEN :from AND :to")
    suspend fun between(from: Long, to: Long): List<StockMovement>

    @Query("SELECT COALESCE(SUM(qtyDelta), 0) FROM stock_movements WHERE productId = :productId")
    suspend fun ledgerBalance(productId: Long): Long

    @Query(
        """SELECT m.*, p.name AS productName, p.unit AS unit FROM stock_movements m
           JOIN products p ON p.id = m.productId ORDER BY m.createdAt, m.id"""
    )
    suspend fun allRows(): List<MovementRow>
}

@Dao
interface SalesDao {
    @Insert
    suspend fun insertSale(sale: Sale): Long

    @Insert
    suspend fun insertLines(lines: List<SaleLine>)

    @Update
    suspend fun updateSale(sale: Sale)

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun sale(id: Long): Sale?

    @Query("SELECT * FROM sales WHERE id = :id")
    fun observeSale(id: Long): Flow<Sale?>

    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId ORDER BY id")
    suspend fun lines(saleId: Long): List<SaleLine>

    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId ORDER BY id")
    fun observeLines(saleId: Long): Flow<List<SaleLine>>

    @Query("SELECT COALESCE(MAX(id), 0) FROM sales")
    suspend fun maxId(): Long

    @Query("SELECT * FROM sales WHERE requestId = :requestId LIMIT 1")
    suspend fun saleByRequest(requestId: String): Sale?

    @Query(
        """SELECT s.*, c.name AS customerName FROM sales s LEFT JOIN customers c ON c.id = s.customerId
           ORDER BY s.createdAt DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<SaleRow>>

    @Query(
        """SELECT s.*, c.name AS customerName FROM sales s LEFT JOIN customers c ON c.id = s.customerId
           WHERE s.customerId = :customerId ORDER BY s.createdAt DESC"""
    )
    fun observeForCustomer(customerId: Long): Flow<List<SaleRow>>

    @Query("SELECT * FROM sales WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt")
    suspend fun between(from: Long, to: Long): List<Sale>

    @Query(
        """SELECT l.*, s.createdAt AS createdAt, s.status AS status, s.customerId AS customerId
           FROM sale_lines l JOIN sales s ON s.id = l.saleId
           WHERE s.createdAt BETWEEN :from AND :to"""
    )
    suspend fun linesBetween(from: Long, to: Long): List<LineWithSale>

    @Query("SELECT * FROM sales ORDER BY createdAt")
    suspend fun all(): List<Sale>

    @Query("SELECT * FROM sale_lines ORDER BY saleId, id")
    suspend fun allLines(): List<SaleLine>

    @Query("SELECT MIN(createdAt) FROM sales WHERE status = 'COMPLETED'")
    suspend fun firstSaleAt(): Long?

    @Query(
        """SELECT * FROM sales WHERE status = 'COMPLETED' AND totalPaise > paidPaise
           ORDER BY createdAt"""
    )
    fun observeReceivables(): Flow<List<Sale>>

    @Query(
        """SELECT * FROM sales WHERE status = 'COMPLETED' AND totalPaise > paidPaise
           AND customerId = :customerId ORDER BY createdAt"""
    )
    suspend fun receivablesFor(customerId: Long): List<Sale>
}

@Dao
interface PartyDao {
    @Query(
        """SELECT c.*,
             (SELECT COUNT(*) FROM sales s WHERE s.customerId = c.id AND s.status = 'COMPLETED') AS saleCount,
             (SELECT COALESCE(SUM(s.totalPaise),0) FROM sales s WHERE s.customerId = c.id AND s.status = 'COMPLETED') AS totalPaise,
             (SELECT COALESCE(SUM(s.totalPaise - s.paidPaise),0) FROM sales s WHERE s.customerId = c.id AND s.status = 'COMPLETED') AS outstandingPaise,
             (SELECT MAX(s.createdAt) FROM sales s WHERE s.customerId = c.id) AS lastSaleAt
           FROM customers c ORDER BY c.name COLLATE NOCASE"""
    )
    fun observeCustomers(): Flow<List<CustomerStats>>

    @Query("SELECT * FROM customers ORDER BY name COLLATE NOCASE")
    suspend fun customers(): List<Customer>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun customer(id: Long): Customer?

    @Query("SELECT * FROM customers WHERE id = :id")
    fun observeCustomer(id: Long): Flow<Customer?>

    @Insert
    suspend fun insertCustomer(c: Customer): Long

    @Update
    suspend fun updateCustomer(c: Customer)

    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE")
    fun observeSuppliers(): Flow<List<Supplier>>

    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE")
    suspend fun suppliers(): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE id = :id")
    suspend fun supplier(id: Long): Supplier?

    @Insert
    suspend fun insertSupplier(s: Supplier): Long

    @Update
    suspend fun updateSupplier(s: Supplier)
}

@Dao
interface PurchaseDao {
    @Insert
    suspend fun insertPo(po: PurchaseOrder): Long

    @Update
    suspend fun updatePo(po: PurchaseOrder)

    @Insert
    suspend fun insertLines(lines: List<PurchaseOrderLine>)

    @Update
    suspend fun updateLine(line: PurchaseOrderLine)

    @Insert
    suspend fun insertReceipt(r: GoodsReceipt): Long

    @Update
    suspend fun updateReceipt(r: GoodsReceipt)

    @Query("SELECT * FROM purchase_orders WHERE id = :id")
    suspend fun po(id: Long): PurchaseOrder?

    @Query("SELECT po.*, s.name AS supplierName FROM purchase_orders po JOIN suppliers s ON s.id = po.supplierId WHERE po.id = :id")
    fun observePo(id: Long): Flow<PoRow?>

    @Query("SELECT * FROM purchase_order_lines WHERE poId = :poId ORDER BY id")
    suspend fun lines(poId: Long): List<PurchaseOrderLine>

    @Query(
        """SELECT l.*, p.name AS productName, p.unit AS unit FROM purchase_order_lines l
           JOIN products p ON p.id = l.productId WHERE l.poId = :poId ORDER BY l.id"""
    )
    fun observeLines(poId: Long): Flow<List<PoLineRow>>

    @Query("SELECT * FROM goods_receipts WHERE poId = :poId ORDER BY createdAt DESC")
    fun observeReceipts(poId: Long): Flow<List<GoodsReceipt>>

    @Query(
        """SELECT po.*, s.name AS supplierName FROM purchase_orders po
           JOIN suppliers s ON s.id = po.supplierId ORDER BY po.createdAt DESC"""
    )
    fun observeAll(): Flow<List<PoRow>>

    @Query(
        """SELECT po.*, s.name AS supplierName FROM purchase_orders po
           JOIN suppliers s ON s.id = po.supplierId WHERE po.supplierId = :supplierId ORDER BY po.createdAt DESC"""
    )
    fun observeForSupplier(supplierId: Long): Flow<List<PoRow>>

    @Query("SELECT * FROM purchase_orders ORDER BY createdAt")
    suspend fun all(): List<PurchaseOrder>

    @Query("SELECT * FROM goods_receipts WHERE createdAt BETWEEN :from AND :to")
    suspend fun receiptsBetween(from: Long, to: Long): List<GoodsReceipt>

    @Query("SELECT COALESCE(MAX(id), 0) FROM purchase_orders")
    suspend fun maxId(): Long

    /** Products with an order still awaiting delivery. */
    @Query(
        """SELECT DISTINCT l.productId FROM purchase_order_lines l
           JOIN purchase_orders po ON po.id = l.poId
           WHERE po.status IN ('ORDERED','PARTIAL') AND l.qtyReceived < l.qtyOrdered"""
    )
    suspend fun productsOnOrder(): List<Long>

    @Query("SELECT * FROM purchase_orders WHERE status != 'CANCELLED' AND totalPaise > paidPaise")
    suspend fun payables(): List<PurchaseOrder>
}

@Dao
interface InvoiceDao {
    @Insert
    suspend fun insert(scan: InvoiceScan): Long

    @Update
    suspend fun update(scan: InvoiceScan)

    @Query("DELETE FROM invoice_scan_lines WHERE scanId = :scanId")
    suspend fun deleteLines(scanId: Long)

    @Insert
    suspend fun insertLines(lines: List<InvoiceScanLine>)

    @Query("SELECT * FROM invoice_scans WHERE id = :id")
    suspend fun scan(id: Long): InvoiceScan?

    @Query("SELECT * FROM invoice_scan_lines WHERE scanId = :scanId ORDER BY id")
    suspend fun lines(scanId: Long): List<InvoiceScanLine>

    @Query("SELECT * FROM invoice_scans ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InvoiceScan>>

    @Query("SELECT * FROM invoice_scans WHERE fingerprint = :fp AND id != :excludeId AND status != 'REJECTED'")
    suspend fun byFingerprint(fp: String, excludeId: Long): List<InvoiceScan>

    @Query("SELECT COUNT(*) FROM purchase_orders WHERE supplierInvoiceNo = :invoiceNo AND supplierInvoiceNo != '' AND supplierId = :supplierId")
    suspend fun poWithInvoiceNo(invoiceNo: String, supplierId: Long): Int
}

@Dao
interface FinanceDao {
    @Insert
    suspend fun insertPayment(p: Payment): Long

    @Query("SELECT * FROM payments WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt")
    suspend fun paymentsBetween(from: Long, to: Long): List<Payment>

    @Query("SELECT * FROM payments WHERE refType = :refType AND refId = :refId ORDER BY createdAt")
    suspend fun paymentsFor(refType: String, refId: Long): List<Payment>

    @Query("SELECT * FROM payments ORDER BY createdAt")
    suspend fun allPayments(): List<Payment>

    @Insert
    suspend fun insertExpense(e: Expense): Long

    @Update
    suspend fun updateExpense(e: Expense)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun expense(id: Long): Expense?

    @Query("SELECT * FROM expenses ORDER BY spentAt DESC")
    fun observeExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE spentAt BETWEEN :from AND :to ORDER BY spentAt")
    suspend fun expensesBetween(from: Long, to: Long): List<Expense>

    @Query("SELECT * FROM expenses ORDER BY spentAt")
    suspend fun allExpenses(): List<Expense>
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(e: AuditEntry): Long

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_log ORDER BY createdAt, id")
    suspend fun all(): List<AuditEntry>
}

@Dao
interface ChangeDao {
    /**
     * Emits whenever any business table changes; the value itself is only a trigger.
     * Dashboards re-derive every metric from persisted records on each emission.
     */
    @Query(
        """SELECT (SELECT COUNT(*) FROM sales) + (SELECT COUNT(*) FROM stock_movements)
             + (SELECT COUNT(*) FROM expenses) + (SELECT COUNT(*) FROM payments)
             + (SELECT COUNT(*) FROM products) + (SELECT COUNT(*) FROM purchase_orders)
             + (SELECT COUNT(*) FROM customers) + (SELECT COUNT(*) FROM business_profile)
             + (SELECT COUNT(*) FROM goods_receipts) + (SELECT COUNT(*) FROM invoice_scans)"""
    )
    fun observeChanges(): Flow<Long>
}
