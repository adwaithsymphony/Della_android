package com.della.app.domain

data class ParsedLine(val description: String, val qty: Long, val unitCostPaise: Long)

data class ParsedInvoice(
    val supplierName: String,
    val invoiceNumber: String,
    val invoiceDate: String,
    val totalPaise: Long,
    val lines: List<ParsedLine>,
    val notes: List<String>,
)

/**
 * Heuristic extraction from OCR text. Everything it returns is a *suggestion* shown in an
 * editable form; nothing is saved or approved automatically.
 */
object InvoiceParser {
    private val numberRx = Regex("""(?<![A-Za-z])\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")
    private val invNoRx = Regex("""(?i)\b(?:invoice|inv|bill)\s*(?:no|number|num|#)?\.?\s*[:#\-]?\s*([A-Z0-9][A-Z0-9/\-]{2,})""")
    private val dateRx = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b|\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val totalRx = Regex("""(?i)(grand\s*total|net\s*amount|amount\s*payable|total)""")
    private val skipHeader = Regex("""(?i)(invoice|bill|tax|gst|date|phone|ph:|tel|address|www|@|cash memo)""")

    private fun money(s: String): Long? = Money.parse(s.replace(",", ""))

    fun parse(text: String): ParsedInvoice {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val notes = mutableListOf<String>()

        val supplier = lines.take(4).firstOrNull { !skipHeader.containsMatchIn(it) && it.count { c -> c.isLetter() } >= 3 } ?: ""
        val invNo = invNoRx.find(text)?.groupValues?.get(1)?.trimEnd('.', ',') ?: ""
        val date = dateRx.find(text)?.let { m ->
            if (m.groupValues[4].isNotEmpty()) "${m.groupValues[4]}-${m.groupValues[5]}-${m.groupValues[6]}"
            else {
                val y = m.groupValues[3].let { if (it.length == 2) "20$it" else it }
                "$y-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[1].padStart(2, '0')}"
            }
        } ?: ""

        // Prefer "grand total" style lines; take the last amount on that line.
        val totalLine = lines.lastOrNull { totalRx.containsMatchIn(it) && it.contains(Regex("""(?i)grand|payable|net""")) }
            ?: lines.lastOrNull { totalRx.containsMatchIn(it) && !it.contains(Regex("""(?i)sub\s*total""")) }
        val total = totalLine?.let { l -> numberRx.findAll(l).mapNotNull { money(it.value) }.lastOrNull() } ?: 0L
        if (total == 0L) notes += "Total not found — please enter it."

        val items = mutableListOf<ParsedLine>()
        for (l in lines) {
            if (totalRx.containsMatchIn(l) || invNoRx.containsMatchIn(l) || dateRx.containsMatchIn(l)) continue
            val firstDigit = l.indexOfFirst { it.isDigit() }
            if (firstDigit < 3) continue
            val desc = l.substring(0, firstDigit).trim().trimEnd('x', 'X', '@', '-', ':').trim()
            if (desc.count { it.isLetter() } < 3) continue
            val nums = numberRx.findAll(l.substring(firstDigit)).mapNotNull { money(it.value) }.toList()
            when {
                nums.size >= 3 -> {
                    // qty, rate, amount — accept when qty × rate ≈ amount
                    val qty = nums[0] / 100; val rate = nums[1]; val amount = nums[2]
                    if (qty in 1..10_000 && kotlin.math.abs(qty * rate - amount) <= 100) items += ParsedLine(desc, qty, rate)
                    else items += ParsedLine(desc, 1, nums.last())
                }
                nums.size == 2 -> {
                    val qty = nums[0] / 100
                    if (nums[0] % 100 == 0L && qty in 1..10_000) items += ParsedLine(desc, qty, nums[1]) else items += ParsedLine(desc, 1, nums[1])
                }
                nums.size == 1 -> items += ParsedLine(desc, 1, nums[0])
            }
        }
        if (items.isEmpty()) notes += "No line items recognised — add them manually."
        if (invNo.isEmpty()) notes += "Invoice number not found."
        return ParsedInvoice(supplier, invNo, date, total, items.take(30), notes)
    }

    /** Simple token-overlap matching of an invoice description to a product name. */
    fun bestMatch(description: String, candidates: List<Pair<Long, String>>): Long? {
        val tokens = description.lowercase().split(Regex("""[^a-z]+""")).filter { it.length >= 3 }.toSet()
        if (tokens.isEmpty()) return null
        return candidates.map { (id, name) ->
            val nt = name.lowercase().split(Regex("""[^a-z]+""")).filter { it.length >= 3 }.toSet()
            id to tokens.count { t -> nt.any { it.startsWith(t.take(4)) || t.startsWith(it.take(4)) } }
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}
