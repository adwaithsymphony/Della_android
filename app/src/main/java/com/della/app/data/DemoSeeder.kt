package com.della.app.data

import androidx.room.withTransaction
import com.della.app.data.db.BusinessProfile
import com.della.app.data.db.Customer
import com.della.app.data.db.DellaDatabase
import com.della.app.data.db.Expense
import com.della.app.data.db.MovementType
import com.della.app.data.db.PayMethod
import com.della.app.data.db.Payment
import com.della.app.data.db.PaymentDirection
import com.della.app.data.db.PaymentRef
import com.della.app.data.db.ProductKind
import com.della.app.data.db.Supplier
import com.della.app.data.db.TaxMode
import com.della.app.data.repo.Audit
import com.della.app.data.repo.CartItem
import com.della.app.data.repo.CatalogRepository
import com.della.app.data.repo.CheckoutRequest
import com.della.app.data.repo.InvoiceDraft
import com.della.app.data.repo.InvoiceLineDraft
import com.della.app.data.repo.InvoiceRepository
import com.della.app.data.repo.PoLineDraft
import com.della.app.data.repo.ProductDraft
import com.della.app.data.repo.PurchaseRepository
import com.della.app.data.repo.SalesRepository
import com.della.app.data.repo.StockLedger
import com.della.app.domain.Dates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Builds the fictional "Macha Bakehouse" (Kochi) demonstration workspace.
 *
 * The seeder simulates ~10 weeks of operations through the SAME repository code used by the
 * app (checkout, receiving, stock ledger), so every balance, dashboard figure and insight is
 * reproducible from the stored records. It only ever runs against the isolated demo database.
 */
class DemoSeeder(
    private val db: DellaDatabase,
    private val catalog: CatalogRepository,
    private val sales: SalesRepository,
    private val purchases: PurchaseRepository,
    private val invoices: InvoiceRepository,
    private val ledger: StockLedger,
    private val audit: Audit,
) {
    private data class Item(
        val sku: String, val name: String, val category: String, val price: Int, val cost: Int,
        val demand: Double, val reorder: Long, val life: Int, // life: 1 = same-day, 2 = keeps 2 days, 0 = packaged batch
        val maxQty: Int = 2,
    )

    private data class Ingredient(val sku: String, val name: String, val unit: String, val cost: Int, val reorder: Long, val weekly: Int, val supplier: Int)

    private val items = listOf(
        Item("CRO-001", "Butter Croissant", "Viennoiserie", 90, 32, 22.0, 10, 1),
        Item("CRO-002", "Almond Croissant", "Viennoiserie", 120, 48, 9.0, 6, 1),
        Item("BRD-001", "Sourdough Loaf", "Breads", 220, 70, 7.0, 4, 2),
        Item("BRD-002", "Milk Bread", "Breads", 70, 24, 13.0, 6, 2),
        Item("BRD-003", "Multigrain Loaf", "Breads", 160, 55, 5.0, 3, 2),
        Item("CAK-001", "Kerala Plum Cake 500g", "Cakes", 380, 150, 2.5, 4, 0),
        Item("CAK-002", "Banana Walnut Slice", "Cakes", 75, 25, 11.0, 6, 2),
        Item("CAK-003", "Chocolate Truffle Pastry", "Cakes", 110, 38, 15.0, 8, 1),
        Item("CAK-004", "Chocolate Brownie", "Cakes", 80, 28, 13.0, 6, 2),
        Item("SAV-001", "Egg Puff", "Savouries", 30, 11, 38.0, 15, 1, maxQty = 4),
        Item("SAV-002", "Veg Puff", "Savouries", 25, 9, 28.0, 12, 1, maxQty = 4),
        Item("SAV-003", "Chicken Cutlet", "Savouries", 45, 18, 18.0, 8, 1, maxQty = 3),
        Item("COO-001", "Cardamom Cookies 200g", "Cookies", 150, 52, 5.5, 6, 0),
        Item("BEV-001", "Kerala Chai", "Beverages", 20, 6, 42.0, 0, 1, maxQty = 3),
        Item("BEV-002", "Cold Coffee", "Beverages", 90, 28, 9.0, 0, 1),
    )

    private val suppliers = listOf(
        Supplier(name = "Ernakulam Flour Mills", contactPerson = "Suresh K.", phone = "9847012345", gstin = "32AAAFE1234A1Z5"),
        Supplier(name = "Malabar Dairy Co-op", contactPerson = "Bindu R.", phone = "9846023456"),
        Supplier(name = "Spice Route Traders", contactPerson = "Hameed P.", phone = "9895034567", email = "orders@spiceroute.example"),
        Supplier(name = "Fresh Farm Eggs, Kakkanad", contactPerson = "Thomas V.", phone = "9744045678"),
        Supplier(name = "PackRight Kochi", contactPerson = "Nisha M.", phone = "9633056789", email = "sales@packright.example"),
    )

    private val ingredients = listOf(
        Ingredient("ING-FLR", "Maida Flour", "kg", 48, 25, 70, 0),
        Ingredient("ING-WWF", "Whole Wheat Flour", "kg", 55, 8, 12, 0),
        Ingredient("ING-BTR", "Butter (unsalted)", "kg", 520, 6, 14, 1),
        Ingredient("ING-MLK", "Milk", "litre", 56, 10, 42, 1),
        Ingredient("ING-SUG", "Sugar", "kg", 44, 10, 20, 2),
        Ingredient("ING-COC", "Cocoa Powder", "kg", 780, 1, 2, 2),
        Ingredient("ING-CAR", "Cardamom 100g", "pack", 320, 2, 2, 2),
        Ingredient("ING-YST", "Dry Yeast 500g", "pack", 210, 1, 2, 2),
        Ingredient("ING-EGG", "Eggs (tray of 30)", "tray", 190, 4, 10, 3),
        Ingredient("ING-PKG", "Cake Boxes (50)", "pack", 450, 2, 3, 4),
    )

    private val customerNames = listOf(
        "Anjali Menon" to "9846100001", "Rahul Nair" to "9846100002", "Fathima Rasheed" to "9846100003",
        "Joseph Kurian" to "9846100004", "Meera Pillai" to "9846100005", "Arjun Varma" to "9846100006",
        "Sneha Thomas" to "9846100007", "Vivek Iyer" to "9846100008", "Ayesha Khan" to "9846100009",
        "Dev Krishnan" to "9846100010",
    )
    private val corporateNames = listOf("Infopark Pantry (corporate)", "St. Teresa's Staff Canteen", "Greenleaf Homestay")

    suspend fun seed(days: Int = 70, onProgress: (Float) -> Unit = {}) {
        val rnd = Random(20261012)
        val today = Dates.today()
        val start = today.minusDays(days.toLong())
        val startMs = Dates.startOf(start) + 6 * 3_600_000L

        db.withTransaction {
            db.profileDao().upsert(
                BusinessProfile(
                    businessName = "Macha Bakehouse", ownerName = "Demo owner", phone = "+91 484 000 0000",
                    address = "Panampilly Nagar, Kochi, Kerala (fictional)", gstin = "",
                    taxMode = TaxMode.EXCLUSIVE, defaultTaxBps = 500,
                    receiptFooter = "Demo receipt — Macha Bakehouse is fictional.",
                    configured = true, updatedAt = startMs,
                )
            )
            audit.log("WORKSPACE_CREATED", "settings", 1, "Demo workspace generated (fictional data, ${days} days)", startMs)

            val supplierIds = suppliers.map { db.partyDao().insertSupplier(it.copy(createdAt = startMs)) }
            val customerIds = customerNames.map { (n, ph) -> db.partyDao().insertCustomer(Customer(name = n, phone = ph, createdAt = startMs)) }
            val corporateIds = corporateNames.map { db.partyDao().insertCustomer(Customer(name = it, notes = "Monthly account — settles weekly", createdAt = startMs)) }

            val itemIds = items.associate { it.sku to catalog.save(
                ProductDraft(name = it.name, sku = it.sku, barcode = "890" + it.sku.filter { c -> c.isDigit() }.padStart(3, '0') + (items.indexOf(it) + 10),
                    categoryName = it.category, kind = ProductKind.SELLABLE, unit = "pc", costPaise = it.cost * 100L,
                    pricePaise = it.price * 100L, taxBps = null, reorderLevel = it.reorder, openingStock = 0)
            ) }
            val ingIds = ingredients.associate { it.sku to catalog.save(
                ProductDraft(name = it.name, sku = it.sku, barcode = "", categoryName = "Ingredients", kind = ProductKind.INGREDIENT,
                    unit = it.unit, costPaise = it.cost * 100L, pricePaise = 0, taxBps = null, reorderLevel = it.reorder,
                    openingStock = (it.weekly * 1.5).roundToLong())
            ) }

            val totalWeight = items.sumOf { it.demand }
            val pendingReceipts = mutableListOf<Pair<LocalDate, Long>>()   // receive date → PO
            val unpaidPos = mutableListOf<Pair<LocalDate, Long>>()

            for (offset in 0..days) {
                val day = start.plusDays(offset.toLong())
                val isToday = day == today
                val nowMinutes = if (isToday) LocalTime.now(Dates.zone).let { it.hour * 60 + it.minute } else 24 * 60
                fun at(minuteOfDay: Int): Long = Dates.startOf(day) + minuteOfDay * 60_000L + rnd.nextInt(0, 59) * 1000L
                val dow = when (day.dayOfWeek) {
                    DayOfWeek.MONDAY -> 0.85; DayOfWeek.TUESDAY -> 0.9; DayOfWeek.WEDNESDAY -> 0.95
                    DayOfWeek.THURSDAY -> 1.0; DayOfWeek.FRIDAY -> 1.1; DayOfWeek.SATURDAY -> 1.3; else -> 1.25
                }
                val trend = 1.0 + 0.0025 * offset
                val txCount = (46 * dow * trend * (0.9 + rnd.nextDouble() * 0.2)).toInt()
                val expectedUnitsPerTx = 1.65 * 1.6

                // --- Goods arriving from earlier orders (07:00) ---
                pendingReceipts.filter { it.first == day && (!isToday || nowMinutes > 7 * 60) }.forEach { (_, poId) ->
                    val lines = db.purchaseDao().lines(poId).filter { it.qtyReceived < it.qtyOrdered }
                    val po = db.purchaseDao().po(poId)
                    if (lines.isEmpty() || po == null || po.status !in listOf("ORDERED", "PARTIAL")) return@forEach
                    val last = lines.last()
                    val lastRemaining = last.qtyOrdered - last.qtyReceived
                    val partial = po.status == "ORDERED" && lastRemaining >= 2 && rnd.nextDouble() < 0.12
                    val qty = lines.associate { l -> l.id to if (partial && l.id == last.id) lastRemaining / 2 else l.qtyOrdered - l.qtyReceived }
                    purchases.receive(poId, qty, if (partial) "Short delivery — balance promised" else if (po.status == "PARTIAL") "Balance delivered" else "Delivered in full", at(6 * 60 + 10))
                    if (partial) pendingReceipts += day.plusDays(2) to poId
                    else unpaidPos += day.plusDays(rnd.nextLong(0, 9)) to poId
                }
                pendingReceipts.removeAll { it.first == day && (!isToday || nowMinutes > 7 * 60) }

                // --- Supplier payments ---
                unpaidPos.filter { !it.first.isAfter(day) && day != today }.forEach { (_, poId) ->
                    val po = db.purchaseDao().po(poId)!!
                    val due = po.totalPaise - po.paidPaise
                    if (due > 0) purchases.recordPayment(poId, due, if (rnd.nextBoolean()) PayMethod.BANK else PayMethod.UPI)
                }
                unpaidPos.removeAll { !it.first.isAfter(day) && day != today }

                if (nowMinutes < 7 * 60) break

                // --- Morning production (06:30) ---
                for (it in items) {
                    val id = itemIds.getValue(it.sku)
                    val stock = db.catalogDao().product(id)!!.stockQty
                    val qty = when (it.life) {
                        0 -> if (stock < it.reorder) (if (it.sku == "COO-001") 30L else 12L) else 0L
                        else -> {
                            val expected = txCount * expectedUnitsPerTx * it.demand / totalWeight
                            val target = (expected * (1.05 + rnd.nextDouble() * 0.15)).roundToLong()
                            max(0L, target - stock)
                        }
                    }
                    if (qty > 0) ledger.move(id, qty, MovementType.PRODUCTION, "production", null, it.cost * 100L, "Morning bake", at(6 * 60 + 30))
                }

                // --- Ingredient usage (recorded at 06:45) ---
                for (ing in ingredients) {
                    val id = ingIds.getValue(ing.sku)
                    val stock = db.catalogDao().product(id)!!.stockQty
                    val planned = if (ing.weekly < 7) (if (rnd.nextDouble() < ing.weekly / 7.0) 1L else 0L)
                        else (ing.weekly / 7.0 * (0.8 + rnd.nextDouble() * 0.4)).roundToLong()
                    val use = minOf(stock, planned)
                    if (use > 0) ledger.move(id, -use, MovementType.USAGE, "usage", null, ing.cost * 100L, "Daily kitchen usage", at(6 * 60 + 45))
                }

                // --- Purchasing on Mondays & Thursdays (11:00) ---
                if ((day.dayOfWeek == DayOfWeek.MONDAY || day.dayOfWeek == DayOfWeek.THURSDAY) && (!isToday || nowMinutes > 11 * 60)) {
                    val bySupplier = ingredients.filter { ing ->
                        val p = db.catalogDao().product(ingIds.getValue(ing.sku))!!
                        val onOrder = ingIds.getValue(ing.sku) in purchases.productsOnOrder()
                        // Keep butter un-reordered in the final week so the demo shows a purchasing insight
                        val skip = ing.sku == "ING-BTR" && offset > days - 8
                        !onOrder && !skip && p.stockQty < ing.reorder * 2
                    }.groupBy { it.supplier }
                    bySupplier.forEach { (sIdx, list) ->
                        val poId = purchases.createOrder(
                            supplierId = supplierIds[sIdx],
                            lines = list.map { PoLineDraft(ingIds.getValue(it.sku), max(1L, (it.weekly * 1.2).roundToLong()), it.cost * 100L) },
                            notes = "Routine restock",
                            at = at(11 * 60),
                        )
                        // The most recent order stays open so the demo shows goods awaiting receipt
                        if (offset < days - 1) pendingReceipts += day.plusDays(1) to poId
                    }
                }

                // --- Sales through the day (08:00–21:00) ---
                val minutes = (0 until txCount).map {
                    val r = rnd.nextDouble()
                    when {
                        r < 0.35 -> rnd.nextInt(8 * 60, 11 * 60)
                        r < 0.55 -> rnd.nextInt(11 * 60, 15 * 60)
                        else -> rnd.nextInt(15 * 60, 21 * 60)
                    }
                }.sorted()
                var lastSaleAt = 0L
                for (m in minutes) {
                    if (m > nowMinutes) break
                    val lineCount = rnd.nextDouble().let { if (it < 0.5) 1 else if (it < 0.85) 2 else 3 }
                    val chosen = LinkedHashMap<Long, Long>()
                    repeat(lineCount) {
                        var pick = rnd.nextDouble() * totalWeight
                        val item = items.firstOrNull { pick -= it.demand; pick <= 0 } ?: items.last()
                        val id = itemIds.getValue(item.sku)
                        val stock = db.catalogDao().product(id)!!.stockQty - (chosen[id] ?: 0)
                        val q = minOf(stock, rnd.nextInt(1, item.maxQty + 1).toLong())
                        if (q > 0) chosen[id] = (chosen[id] ?: 0) + q
                    }
                    if (chosen.isEmpty()) continue
                    val corporate = rnd.nextDouble() < 0.025
                    val customer = when {
                        corporate -> corporateIds[rnd.nextInt(corporateIds.size)]
                        rnd.nextDouble() < 0.15 -> customerIds[rnd.nextInt(customerIds.size)]
                        else -> null
                    }
                    val cart = chosen.map { CartItem(it.key, it.value) }
                    val method = if (corporate) PayMethod.CREDIT else rnd.nextDouble().let { if (it < 0.55) PayMethod.UPI else if (it < 0.9) PayMethod.CASH else PayMethod.CARD }
                    val discount = if (customer != null && !corporate && rnd.nextDouble() < 0.2) {
                        val gross = cart.sumOf { c -> db.catalogDao().product(c.productId)!!.pricePaise * c.qty }
                        gross / 10 / 100 * 100   // 10% loyalty discount, whole rupees
                    } else 0L
                    val total = sales.price(cart, discount).total
                    val tender = when (method) {
                        PayMethod.CREDIT -> 0L
                        PayMethod.CASH -> ((total + 4999) / 5000) * 5000   // rounded up to ₹50 note, change given
                        else -> total
                    }
                    val saleAt = maxOf(at(m), lastSaleAt + 1_000)   // strictly increasing, so the ledger reads in order
                    lastSaleAt = saleAt
                    sales.checkout(CheckoutRequest(UUID.nameUUIDFromBytes("demo-$offset-$m-$saleAt".toByteArray()).toString(), cart, discount, method, tender, customer, at = saleAt))
                }

                // --- Close of day wastage (21:30) ---
                if (!isToday) for (it in items.filter { it.life > 0 }) {
                    val id = itemIds.getValue(it.sku)
                    val left = db.catalogDao().product(id)!!.stockQty
                    val waste = if (it.life == 1) left else left / 2
                    if (waste > 0) ledger.move(id, -waste, MovementType.WASTAGE, "close", null, it.cost * 100L, "Unsold at close", at(21 * 60 + 30))
                }

                // --- Corporate accounts settle on Saturdays (except the latest week) ---
                if (day.dayOfWeek == DayOfWeek.SATURDAY && offset < days - 6) {
                    corporateIds.forEach { cid ->
                        val due = db.salesDao().receivablesFor(cid).sumOf { it.totalPaise - it.paidPaise }
                        if (due > 0) sales.settleCustomer(cid, due, PayMethod.BANK)
                    }
                }

                // --- Expenses ---
                suspend fun expense(cat: String, desc: String, vendor: String, rupees: Int, minute: Int, paid: Boolean = true, method: String = PayMethod.BANK) {
                    val t = at(minute)
                    val amount = rupees * 100L
                    val id = db.financeDao().insertExpense(Expense(category = cat, description = desc, vendor = vendor, amountPaise = amount, paidPaise = if (paid) amount else 0, method = method, spentAt = t, createdAt = t))
                    if (paid) db.financeDao().insertPayment(Payment(direction = PaymentDirection.OUT, amountPaise = amount, method = method, refType = PaymentRef.EXPENSE, refId = id, note = desc, createdAt = t))
                    audit.log("EXPENSE_RECORDED", "expense", id, "$cat: $desc ₹$rupees" + if (!paid) " (unpaid)" else "", t)
                }
                if (!isToday || nowMinutes > 17 * 60) {
                    if (day.dayOfMonth == 1) expense("Rent", "Shop rent ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }}", "Landlord", 45_000, 10 * 60)
                    if (day.dayOfMonth == 6) expense("Utilities", "KSEB electricity bill", "KSEB", 9_200 + rnd.nextInt(0, 1800), 12 * 60, paid = offset < days - 3, method = PayMethod.UPI)
                    if (day.dayOfMonth == 10) expense("Utilities", "Water & internet", "Various", 1_850, 12 * 60, method = PayMethod.UPI)
                    if (day.dayOfWeek == DayOfWeek.SATURDAY) expense("Wages", "Weekly wages (4 staff)", "Staff", 28_000, 20 * 60, method = PayMethod.BANK)
                    if (offset % 6 == 2) expense("Gas & fuel", "Commercial LPG cylinder", "Indane distributor", 1_910, 9 * 60, method = PayMethod.CASH)
                    if (day.dayOfWeek == DayOfWeek.WEDNESDAY) expense("Packaging", "Paper bags & butter paper", "Broadway wholesale", 600 + rnd.nextInt(0, 300), 16 * 60, method = PayMethod.CASH)
                    if (day.dayOfMonth == 15) expense("Marketing", "Instagram promotion", "Meta", 2_500, 14 * 60, method = PayMethod.CARD)
                    if (day.dayOfMonth == 20) expense("Repairs", "Mixer servicing", "Kochi Bakery Equipments", 1_200, 15 * 60, method = PayMethod.CASH)
                    if (offset == days - 9) expense("Repairs", "Deck oven heating element replacement", "Kochi Bakery Equipments", 18_500, 13 * 60, method = PayMethod.UPI)
                }

                // --- Occasional corrections, kept in history ---
                if (offset == days - 20) {
                    val sugar = ingIds.getValue("ING-SUG")
                    val p = db.catalogDao().product(sugar)!!
                    if (p.stockQty >= 2) {
                        ledger.move(sugar, -2, MovementType.ADJUSTMENT, "count", null, p.costPaise, "Stock count: spillage found", at(18 * 60))
                        audit.log("STOCK_COUNT", "product", sugar, "${p.name}: counted ${p.stockQty - 2} (system had ${p.stockQty}, diff -2)", at(18 * 60))
                    }
                }

                onProgress(offset / days.toFloat() * 0.95f)
            }

            // Two reversals with reasons (only on past days, so today's dashboard is untouched)
            val reversible = db.salesDao().between(Dates.startOf(today.minusDays(12)), Dates.endOf(today.minusDays(2)))
            reversible.getOrNull(5)?.let { sales.reverse(it.id, "Wrong item billed — rebilled correctly", PayMethod.CASH) }
            reversible.getOrNull(reversible.size / 2)?.let { sales.reverse(it.id, "Customer cancelled order", PayMethod.UPI) }

            // Invoice capture examples: one approved into a PO, one awaiting review
            val pkg = ingIds.getValue("ING-PKG")
            val eggs = ingIds.getValue("ING-EGG")
            invoices.approve(
                InvoiceDraft(
                    supplierName = suppliers[4].name, supplierId = supplierIds[4], invoiceNumber = "PRK/2026/0418",
                    invoiceDate = Dates.iso(Dates.startOf(today.minusDays(1))), totalPaise = 4 * 45_000L,
                    rawText = "PackRight Kochi\nInvoice PRK/2026/0418\nCake Boxes (50) x4 @450.00\nTotal 1800.00",
                    source = "MANUAL", lines = listOf(InvoiceLineDraft("Cake Boxes (50)", pkg, 4, 45_000)),
                )
            )
            invoices.saveDraft(
                InvoiceDraft(
                    supplierName = "Fresh Farm Eggs", supplierId = supplierIds[3], invoiceNumber = "FFE-2291",
                    invoiceDate = Dates.iso(System.currentTimeMillis()), totalPaise = 6 * 19_000L,
                    rawText = "FRESH FARM EGGS KAKKANAD\nBill No: FFE-2291\nEgg tray 30s  6  190.00  1140.00\nGrand Total 1140.00",
                    source = "MANUAL", lines = listOf(InvoiceLineDraft("Egg tray 30s", eggs, 6, 19_000)),
                )
            )
            onProgress(1f)
        }
    }
}
