package com.della.app.domain

import com.della.app.data.db.TaxMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Integer-paise money helpers. All rounding is half-up to the nearest paisa. */
object Money {
    /** ₹1,23,456.78 (Indian digit grouping). */
    fun format(paise: Long, showPaise: Boolean = true): String {
        val negative = paise < 0
        val abs = abs(paise)
        val rupees = abs / 100
        val p = abs % 100
        val body = groupIndian(rupees.toString()) + if (showPaise) ".%02d".format(p) else ""
        return (if (negative) "−₹" else "₹") + body
    }

    /** Compact form for tiles: ₹12.4K, ₹3.2L, ₹1.1Cr */
    fun compact(paise: Long): String {
        val rupees = paise / 100.0
        val a = abs(rupees)
        val sign = if (rupees < 0) "−" else ""
        return when {
            a >= 1_00_00_000 -> "$sign₹%.2fCr".format(a / 1_00_00_000)
            a >= 1_00_000 -> "$sign₹%.2fL".format(a / 1_00_000)
            a >= 10_000 -> "$sign₹%.1fK".format(a / 1000)
            else -> format(paise, showPaise = false)
        }
    }

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Parses "125", "125.5", "₹1,250.75" into paise. Returns null when invalid or negative. */
    fun parse(input: String): Long? {
        val cleaned = input.trim().replace("₹", "").replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd.signum() < 0 || bd.stripTrailingZeros().scale() > 2) return null
            bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    fun toPlain(paise: Long): String = BigDecimal.valueOf(paise, 2).toPlainString()

    fun pct(bps: Int): String {
        val whole = bps / 100
        val frac = bps % 100
        return if (frac == 0) "$whole%" else "$whole.${"%02d".format(frac).trimEnd('0')}%"
    }

    /** Parses "5", "12.5" (percent) into basis points. */
    fun parseBps(input: String): Int? {
        val cleaned = input.trim().removeSuffix("%").trim()
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd.signum() < 0 || bd > BigDecimal(100)) null
            else bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()
        } catch (e: Exception) {
            null
        }
    }

    /** value × bps / 10000, half-up. */
    fun applyBps(value: Long, bps: Int): Long =
        BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(bps.toLong()))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).toLong()

    /** Ratio as percentage string with one decimal, or "—" when the base is zero. */
    fun ratio(part: Long, whole: Long): String =
        if (whole == 0L) "—" else "%.1f%%".format(part * 100.0 / whole)
}

/** Result of pricing one cart line. */
data class LineCalc(
    val gross: Long,
    val discount: Long,
    val net: Long,      // revenue excl. tax
    val tax: Long,
    val total: Long,    // what the customer pays for the line
)

object TaxCalc {
    /**
     * Prices a line after its share of discount.
     * EXCLUSIVE: tax = net × rate; total = net + tax.
     * INCLUSIVE: the discounted price already includes tax; tax = price − price / (1 + rate).
     * NONE: no tax.
     */
    fun line(gross: Long, discount: Long, taxBps: Int, mode: String): LineCalc {
        val afterDiscount = gross - discount
        return when (mode) {
            TaxMode.EXCLUSIVE -> {
                val tax = Money.applyBps(afterDiscount, taxBps)
                LineCalc(gross, discount, afterDiscount, tax, afterDiscount + tax)
            }
            TaxMode.INCLUSIVE -> {
                val net = BigDecimal.valueOf(afterDiscount).multiply(BigDecimal.valueOf(10_000))
                    .divide(BigDecimal.valueOf(10_000L + taxBps), 0, RoundingMode.HALF_UP).toLong()
                LineCalc(gross, discount, net, afterDiscount - net, afterDiscount)
            }
            else -> LineCalc(gross, discount, afterDiscount, 0, afterDiscount)
        }
    }

    /**
     * Allocates a cart-level discount across lines in proportion to gross value.
     * The remainder from rounding goes to the largest line so allocations sum exactly.
     */
    fun allocateDiscount(grossLines: List<Long>, discount: Long): List<Long> {
        val totalGross = grossLines.sum()
        if (discount <= 0 || totalGross <= 0) return grossLines.map { 0L }
        val d = minOf(discount, totalGross)
        val shares = grossLines.map {
            BigDecimal.valueOf(it).multiply(BigDecimal.valueOf(d))
                .divide(BigDecimal.valueOf(totalGross), 0, RoundingMode.DOWN).toLong()
        }.toMutableList()
        val remainder = d - shares.sum()
        if (remainder != 0L) {
            val idx = grossLines.indices.maxByOrNull { grossLines[it] } ?: 0
            shares[idx] = shares[idx] + remainder
        }
        return shares
    }
}

object Dates {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dayTimeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    fun day(millis: Long): String = dayFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun dayTime(millis: Long): String = dayTimeFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun short(date: LocalDate): String = shortFmt.format(date)
    fun iso(millis: Long): String = isoFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun stamp(millis: Long): String = stampFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun toDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun startOf(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endOf(date: LocalDate): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    fun today(): LocalDate = LocalDate.now(zone)
}
