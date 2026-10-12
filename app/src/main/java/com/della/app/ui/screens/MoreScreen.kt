package com.della.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.della.app.ui.Routes
import com.della.app.ui.common.BrandTopBar
import com.della.app.ui.common.Divider
import com.della.app.ui.common.ListRow
import com.della.app.ui.common.LocalContainer
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.Panel
import com.della.app.ui.common.SectionLabel
import com.della.app.ui.theme.Della

private data class Entry(val title: String, val sub: String, val icon: ImageVector, val route: String)

@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    val container = LocalContainer.current
    val groups = listOf(
        "Selling" to listOf(
            Entry("Sales history", "Receipts, reversals, unpaid bills", Icons.Outlined.ReceiptLong, Routes.SALES),
            Entry("Customers", "Accounts and purchase history", Icons.Outlined.People, Routes.CUSTOMERS),
            Entry("Products", "Catalogue, prices, costs, SKUs", Icons.Outlined.Inventory2, Routes.PRODUCTS),
        ),
        "Buying" to listOf(
            Entry("Purchase orders", "Order and receive goods", Icons.Outlined.LocalShipping, Routes.PURCHASES),
            Entry("Suppliers", "Supplier directory", Icons.Outlined.Storefront, Routes.SUPPLIERS),
            Entry("Invoice capture", "Scan or enter supplier invoices", Icons.Outlined.DocumentScanner, Routes.INVOICES),
            Entry("Expenses", "Running costs and bills", Icons.Outlined.Payments, Routes.EXPENSES),
        ),
        "Understanding" to listOf(
            Entry("Analytics", "Trends, margins, cash flow", Icons.Outlined.BarChart, Routes.ANALYTICS),
            Entry("Forecast", "Next 7 days with assumptions", Icons.Outlined.Timeline, Routes.FORECAST),
            Entry("Ask DELLA", "Questions answered from your data", Icons.Outlined.ChatBubbleOutline, Routes.ASSISTANT),
        ),
        "Business" to listOf(
            Entry("Settings", "Profile, tax, preferences, workspace", Icons.Outlined.Settings, Routes.SETTINGS),
            Entry("Data & backup", "Export, import, backup, restore, reset", Icons.Outlined.Storage, Routes.DATA),
            Entry("Audit history", "Who changed what, when", Icons.Outlined.History, Routes.AUDIT),
        ),
    )
    Column(Modifier.fillMaxSize()) {
        BrandTopBar(container.workspace.label)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            groups.forEach { (title, entries) ->
                SectionLabel(title)
                Panel(padding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                    entries.forEachIndexed { i, e ->
                        ListRow(e.title, e.sub, leading = e.icon, trailing = "›", trailingColor = Della.TextFaint, onClick = { nav.navigate(e.route) })
                        if (i < entries.lastIndex) Divider()
                    }
                }
            }
            Text("DELLA 1.0 · data stored on this device", color = Della.TextFaint, modifier = Modifier.padding(top = 20.dp))
        }
    }
}
