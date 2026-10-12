package com.della.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BusinessProfile::class, Category::class, Product::class, StockMovement::class,
        Customer::class, Sale::class, SaleLine::class, Payment::class,
        Supplier::class, PurchaseOrder::class, PurchaseOrderLine::class, GoodsReceipt::class,
        InvoiceScan::class, InvoiceScanLine::class, Expense::class, AuditEntry::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class DellaDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun catalogDao(): CatalogDao
    abstract fun stockDao(): StockDao
    abstract fun salesDao(): SalesDao
    abstract fun partyDao(): PartyDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun financeDao(): FinanceDao
    abstract fun auditDao(): AuditDao
    abstract fun changeDao(): ChangeDao

    companion object {
        /** Required tables used to validate a backup file before restore. */
        val REQUIRED_TABLES = listOf("business_profile", "products", "sales", "sale_lines", "stock_movements", "payments", "room_master_table")

        fun open(context: Context, fileName: String): DellaDatabase =
            Room.databaseBuilder(context.applicationContext, DellaDatabase::class.java, fileName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
    }
}
