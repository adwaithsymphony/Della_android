# DELLA for Android

**Data-Enabled Local Business & Livelihood Assistant** — an offline-first business app for independent bakeries and other small shops. Kotlin · Jetpack Compose · Material 3 · Room · ViewModel · Coroutines · Navigation Compose.

## Build the signed APK

Requirements: JDK 17 and the Android SDK (platform 35). Android Studio Ladybug or newer works out of the box.

```bash
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk dist/DELLA-release.apk
```

The release build is signed with the bundled **development** keystore (`keystore/della-dev.jks`, credentials in `keystore.properties`) so it installs directly. Before any Play Store release, replace it with your own private key, or set `DELLA_STORE_FILE`, `DELLA_STORE_PASSWORD`, `DELLA_KEY_ALIAS`, `DELLA_KEY_PASSWORD`.

**CI:** `.github/workflows/build-apk.yml` builds the APK on every push to `main`, uploads it as an artifact and commits it to a `dist` branch (or the failing log to `ci-logs`).

## What's inside

| Area | Highlights |
|---|---|
| Overview | Today/yesterday, 14-day trend, month revenue, gross profit, expenses, net cash, inventory health, top insights, recent activity — all recalculated from stored records on every change |
| Sell (POS) | Product grid, SKU/barcode entry, cart, cart discount (₹ or %), configurable tax (none / added / included), cash with change, UPI, card, pay-later with part payment, confirmation, receipts (share as text), idempotent checkout |
| Sales | History, filters, receipt detail, payments, controlled reversal (reason required; stock restored; refund recorded; original kept) |
| Stock | Balances, ledger per item with balance check, production, usage, wastage, physical count, reasoned corrections, negative stock blocked |
| Products | CRUD, SKU/barcode uniqueness, categories, sellable vs ingredient, cost/price/margin, tax override, reorder level, archive-instead-of-delete when history exists |
| Purchasing | Suppliers, purchase orders, partial/full receiving (stock only increases on receipt, over-receipt blocked), last-cost update, cancellation, supplier payments |
| Invoice capture | Camera or image import → on-device OCR (ML Kit, offline) → editable fields and lines → product matching → validation, duplicate detection → explicit approval creates a PO. Never changes stock by itself. Manual entry always available |
| Customers | Records, search, linked sales, outstanding balance, payments applied to oldest bills |
| Expenses & cash | Categories, paid/unpaid, pay later; cash flow from actual payment records; revenue ≠ profit ≠ cash kept distinct |
| Analytics | Periods, trend, transactions, average bill, best sellers, product margins, categories, busiest hours, expenses, cash flow, inventory movement, purchasing |
| Insights | Rule-based: stock cover, reorder needs, week-over-week sales, expense anomalies, margin problems, wastage, receivables — each with observed facts, method, labelled inferences and an action |
| Forecast | Same-weekday median over 8 weeks with spread, honest backtest error, product demand; requires 21+ days of history |
| Ask DELLA | Deterministic read-only Q&A over local data with stated sources |
| Audit | Append-only log of sales, reversals, stock changes, receiving, approvals, settings |
| Data | CSV export (zip), product CSV import, validated full backup/restore, safe reset |

## Demo workspace

On first launch DELLA generates **Macha Bakehouse** (fictional, Kochi) by simulating ~10 weeks of baking, sales, wastage, purchasing, receiving, payments and expenses through the same repository code the app uses — so every figure is reproducible from the records. Demo data lives in a separate database (`della_demo.db`) from real data (`della_business.db`); switch in Settings. A banner marks demo screens.

## Honest boundaries

- **Assistant**: no language model is bundled or called; it answers recognised questions with computed figures and says when it can't. Adding an LLM would require a server you control — never embed keys in the APK.
- **OCR**: Latin-script printed invoices from images; PDFs are not read. Extraction is heuristic and always reviewed.
- **Tax**: calculated as configured; DELLA does not certify GST compliance.
- Quantities are whole units (pieces, kg, trays…).
- Data stays on the device (excluded from Android cloud backup); use the backup export.
