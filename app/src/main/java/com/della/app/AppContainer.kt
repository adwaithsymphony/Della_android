package com.della.app

import android.content.Context
import android.content.Intent
import com.della.app.data.DataTransfer
import com.della.app.data.DemoSeeder
import com.della.app.data.db.DellaDatabase
import com.della.app.data.repo.Audit
import com.della.app.data.repo.CatalogRepository
import com.della.app.data.repo.FinanceRepository
import com.della.app.data.repo.InventoryRepository
import com.della.app.data.repo.InvoiceRepository
import com.della.app.data.repo.PartyRepository
import com.della.app.data.repo.PurchaseRepository
import com.della.app.data.repo.SalesRepository
import com.della.app.data.repo.SettingsRepository
import com.della.app.data.repo.StockLedger
import com.della.app.domain.AnalyticsRepository
import com.della.app.domain.BusinessAssistant
import com.della.app.domain.ForecastEngine
import com.della.app.domain.InsightEngine
import kotlin.system.exitProcess

/**
 * Two physically separate databases keep demonstration data isolated from real business data.
 * Switching workspace restarts the process so no object can hold a reference to the other DB.
 */
enum class Workspace(val fileName: String, val label: String) {
    DEMO("della_demo.db", "Demo · Macha Bakehouse"),
    BUSINESS("della_business.db", "My business"),
}

class AppContainer(private val context: Context) {
    private val prefs = context.getSharedPreferences("della_prefs", Context.MODE_PRIVATE)

    val workspace: Workspace = runCatching { Workspace.valueOf(prefs.getString("workspace", Workspace.DEMO.name)!!) }.getOrDefault(Workspace.DEMO)
    val isDemo: Boolean get() = workspace == Workspace.DEMO

    val db: DellaDatabase = DellaDatabase.open(context, workspace.fileName)
    val audit = Audit(db)
    val ledger = StockLedger(db)
    val settings = SettingsRepository(db, audit)
    val catalog = CatalogRepository(db, audit, ledger)
    val inventory = InventoryRepository(db, audit, ledger)
    val sales = SalesRepository(db, audit, ledger)
    val parties = PartyRepository(db, audit)
    val purchases = PurchaseRepository(db, audit, ledger)
    val invoices = InvoiceRepository(db, audit, purchases)
    val finance = FinanceRepository(db, audit)
    val analytics = AnalyticsRepository(db)
    val insights = InsightEngine(analytics)
    val forecast = ForecastEngine(analytics)
    val assistant = BusinessAssistant(db, analytics, insights, forecast)
    val transfer = DataTransfer(context, db, workspace.fileName, catalog, audit)

    fun demoSeeder() = DemoSeeder(db, catalog, sales, purchases, invoices, ledger, audit)

    /** Whether the workspace needs first-run work (demo generation or business setup). */
    suspend fun needsDemoSeed(): Boolean = isDemo && db.profileDao().get() == null

    fun switchWorkspace(target: Workspace) {
        prefs.edit().putString("workspace", target.name).commit()
        restart()
    }

    /** Deletes the CURRENT workspace's database file only, then restarts. */
    fun resetCurrentWorkspace() {
        db.close()
        context.deleteDatabase(workspace.fileName)
        restart()
    }

    fun closeDatabase() = db.close()

    fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        exitProcess(0)
    }
}
