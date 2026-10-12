package com.della.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.della.app.ui.common.DemoBanner
import com.della.app.ui.common.LocalContainer
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.LocalSnackbar
import com.della.app.ui.screens.AlertsScreen
import com.della.app.ui.screens.AnalyticsScreen
import com.della.app.ui.screens.AssistantScreen
import com.della.app.ui.screens.AuditScreen
import com.della.app.ui.screens.CustomerDetailScreen
import com.della.app.ui.screens.CustomerEditScreen
import com.della.app.ui.screens.CustomersScreen
import com.della.app.ui.screens.DataScreen
import com.della.app.ui.screens.ExpenseEditScreen
import com.della.app.ui.screens.ExpensesScreen
import com.della.app.ui.screens.ForecastScreen
import com.della.app.ui.screens.InsightsScreen
import com.della.app.ui.screens.InventoryScreen
import com.della.app.ui.screens.InvoiceEditScreen
import com.della.app.ui.screens.InvoicesScreen
import com.della.app.ui.screens.MoreScreen
import com.della.app.ui.screens.OverviewScreen
import com.della.app.ui.screens.PoDetailScreen
import com.della.app.ui.screens.PoNewScreen
import com.della.app.ui.screens.PosScreen
import com.della.app.ui.screens.ProductEditScreen
import com.della.app.ui.screens.ProductsScreen
import com.della.app.ui.screens.PurchasesScreen
import com.della.app.ui.screens.SaleDetailScreen
import com.della.app.ui.screens.SalesHistoryScreen
import com.della.app.ui.screens.SettingsScreen
import com.della.app.ui.screens.StockItemScreen
import com.della.app.ui.screens.SupplierEditScreen
import com.della.app.ui.screens.SuppliersScreen
import com.della.app.ui.theme.Della

object Routes {
    const val OVERVIEW = "overview"
    const val POS = "pos"
    const val INVENTORY = "inventory"
    const val INSIGHTS = "insights"
    const val MORE = "more"
    const val PRODUCTS = "products"
    const val SALES = "sales"
    const val CUSTOMERS = "customers"
    const val SUPPLIERS = "suppliers"
    const val PURCHASES = "purchases"
    const val PO_NEW = "po_new"
    const val INVOICES = "invoices"
    const val EXPENSES = "expenses"
    const val EXPENSE_NEW = "expense_new"
    const val ANALYTICS = "analytics"
    const val FORECAST = "forecast"
    const val ASSISTANT = "assistant"
    const val AUDIT = "audit"
    const val SETTINGS = "settings"
    const val DATA = "data"
    const val ALERTS = "alerts"
    fun product(id: Long) = "product/$id"
    fun stockItem(id: Long) = "stock/$id"
    fun sale(id: Long) = "sale/$id"
    fun customer(id: Long) = "customer/$id"
    fun customerEdit(id: Long) = "customer_edit/$id"
    fun supplierEdit(id: Long) = "supplier_edit/$id"
    fun po(id: Long) = "po/$id"
    fun invoice(id: Long, source: String = "MANUAL") = "invoice/$id?source=$source"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.OVERVIEW, "Overview", Icons.Outlined.SpaceDashboard),
    Tab(Routes.POS, "Sell", Icons.Outlined.PointOfSale),
    Tab(Routes.INVENTORY, "Stock", Icons.Outlined.Inventory2),
    Tab(Routes.INSIGHTS, "Insights", Icons.Outlined.Insights),
    Tab(Routes.MORE, "More", Icons.Outlined.Apps),
)

@Composable
fun DellaRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val container = LocalContainer.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showTabs = tabs.any { it.route == route }

    CompositionLocalProvider(LocalNav provides nav, LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = Della.Background,
            snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, containerColor = Della.PanelHigh, contentColor = Della.Text, actionColor = Della.Accent) } },
            bottomBar = {
                if (showTabs) NavigationBar(containerColor = Della.Panel, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Della.Accent, selectedTextColor = Della.Accent,
                                indicatorColor = Della.PanelHigh, unselectedIconColor = Della.TextMuted, unselectedTextColor = Della.TextMuted,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (container.isDemo) DemoBanner(container.workspace.label)
                NavHost(nav, startDestination = Routes.OVERVIEW, modifier = Modifier.weight(1f)) {
                    composable(Routes.OVERVIEW) { OverviewScreen() }
                    composable(Routes.POS) { PosScreen() }
                    composable(Routes.INVENTORY) { InventoryScreen() }
                    composable(Routes.INSIGHTS) { InsightsScreen() }
                    composable(Routes.MORE) { MoreScreen() }
                    composable(Routes.PRODUCTS) { ProductsScreen() }
                    composable("product/{id}", listOf(navArgument("id") { type = NavType.LongType })) { ProductEditScreen(it.arguments!!.getLong("id")) }
                    composable("stock/{id}", listOf(navArgument("id") { type = NavType.LongType })) { StockItemScreen(it.arguments!!.getLong("id")) }
                    composable(Routes.SALES) { SalesHistoryScreen() }
                    composable("sale/{id}", listOf(navArgument("id") { type = NavType.LongType })) { SaleDetailScreen(it.arguments!!.getLong("id")) }
                    composable(Routes.CUSTOMERS) { CustomersScreen() }
                    composable("customer/{id}", listOf(navArgument("id") { type = NavType.LongType })) { CustomerDetailScreen(it.arguments!!.getLong("id")) }
                    composable("customer_edit/{id}", listOf(navArgument("id") { type = NavType.LongType })) { CustomerEditScreen(it.arguments!!.getLong("id")) }
                    composable(Routes.SUPPLIERS) { SuppliersScreen() }
                    composable("supplier_edit/{id}", listOf(navArgument("id") { type = NavType.LongType })) { SupplierEditScreen(it.arguments!!.getLong("id")) }
                    composable(Routes.PURCHASES) { PurchasesScreen() }
                    composable(Routes.PO_NEW) { PoNewScreen() }
                    composable("po/{id}", listOf(navArgument("id") { type = NavType.LongType })) { PoDetailScreen(it.arguments!!.getLong("id")) }
                    composable(Routes.INVOICES) { InvoicesScreen() }
                    composable(
                        "invoice/{id}?source={source}",
                        listOf(navArgument("id") { type = NavType.LongType }, navArgument("source") { type = NavType.StringType; defaultValue = "MANUAL" }),
                    ) { InvoiceEditScreen(it.arguments!!.getLong("id"), it.arguments!!.getString("source") ?: "MANUAL") }
                    composable(Routes.EXPENSES) { ExpensesScreen() }
                    composable(Routes.EXPENSE_NEW) { ExpenseEditScreen() }
                    composable(Routes.ANALYTICS) { AnalyticsScreen() }
                    composable(Routes.FORECAST) { ForecastScreen() }
                    composable(Routes.ASSISTANT) { AssistantScreen() }
                    composable(Routes.AUDIT) { AuditScreen() }
                    composable(Routes.SETTINGS) { SettingsScreen() }
                    composable(Routes.DATA) { DataScreen() }
                    composable(Routes.ALERTS) { AlertsScreen() }
                }
            }
        }
    }
}
