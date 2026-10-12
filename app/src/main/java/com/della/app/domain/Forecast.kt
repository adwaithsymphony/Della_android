package com.della.app.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

data class ForecastDay(val date: LocalDate, val expected: Long, val low: Long, val high: Long, val samples: Int)

data class ProductDemand(val name: String, val unit: String, val perDay: Double, val next7: Long, val stock: Long) {
    val coverDays: Double? get() = if (perDay > 0) stock / perDay else null
}

data class ForecastResult(
    val available: Boolean,
    val reason: String,
    val historyDays: Int,
    val days: List<ForecastDay>,
    val assumptions: List<String>,
    /** Mean absolute percentage error of this exact method on recent days, using only prior data. */
    val backtestMape: Double?,
    val backtestDays: Int,
    val demand: List<ProductDemand>,
)

/**
 * Seasonal-naive forecast: each future day's expected revenue is the median revenue of the
 * same weekday over the previous 8 weeks. The range is the spread of those same-weekday values.
 * Deliberately simple so every number can be checked by hand.
 */
class ForecastEngine(private val analytics: AnalyticsRepository) {
    companion object {
        const val MIN_HISTORY_DAYS = 21
        const val WEEKS = 8
        const val MIN_SAMPLES = 3
    }

    suspend fun forecast(horizon: Int = 7): ForecastResult {
        val today = Dates.today()
        val first = analytics.firstSaleDate()
        val historyDays = if (first == null) 0 else (today.toEpochDay() - first.toEpochDay()).toInt()
        if (first == null || historyDays < MIN_HISTORY_DAYS) {
            return ForecastResult(false, "Forecasts need at least $MIN_HISTORY_DAYS days of sales history; you have $historyDays.", historyDays, emptyList(), emptyList(), null, 0, emptyList())
        }
        // Use only complete days (exclude today, which is still in progress)
        val window = Period(maxOf(first, today.minusDays(WEEKS * 7L + 14)), today.minusDays(1), "history")
        val data = analytics.load(window)
        val daily = Calc.daily(data).associate { it.date to it.value }

        fun predict(target: LocalDate): ForecastDay? {
            val samples = (1..WEEKS).map { target.minusWeeks(it.toLong()) }
                .filter { !it.isBefore(first) && daily.containsKey(it) }
                .map { daily.getValue(it) }.sorted()
            if (samples.size < MIN_SAMPLES) return null
            val median = if (samples.size % 2 == 1) samples[samples.size / 2] else (samples[samples.size / 2 - 1] + samples[samples.size / 2]) / 2
            val low = if (samples.size >= 5) samples[1] else samples.first()
            val high = if (samples.size >= 5) samples[samples.size - 2] else samples.last()
            return ForecastDay(target, median, low, high, samples.size)
        }

        val days = (1..horizon).mapNotNull { predict(today.plusDays(it.toLong() - 1)) }

        // Honest backtest over the last 14 complete days
        val errors = (1..14).mapNotNull { back ->
            val d = today.minusDays(back.toLong())
            val actual = daily[d] ?: return@mapNotNull null
            val p = predict(d) ?: return@mapNotNull null
            if (actual <= 0) null else abs(p.expected - actual).toDouble() / actual
        }
        val mape = if (errors.size >= 5) errors.average() * 100 else null

        val usage = analytics.load(Period.lastDays(28).let { Period(it.from, today.minusDays(1), it.label) })
        val units = Calc.unitsPerProduct(usage)
        val demand = usage.products.filter { it.product.active && units.containsKey(it.product.id) }.map {
            val perDay = (units[it.product.id] ?: 0) / 28.0
            ProductDemand(it.product.name, it.product.unit, perDay, kotlin.math.round(perDay * 7).toLong(), it.product.stockQty)
        }.sortedByDescending { it.perDay }

        return ForecastResult(
            available = days.isNotEmpty(),
            reason = if (days.isEmpty()) "Not enough same-weekday history yet." else "",
            historyDays = historyDays,
            days = days,
            assumptions = listOf(
                "Expected value = median net revenue of the same weekday over the last $WEEKS weeks (at least $MIN_SAMPLES weeks needed).",
                "Range = spread of those past same-weekday values (excluding the single highest and lowest when 5+ weeks exist). It is not a statistical confidence interval.",
                "Assumes recent weekly patterns continue: no price changes, holidays, events, stock-outs or new products.",
                "Product demand = average daily units sold over the last 28 complete days × 7.",
                "Today (${today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}) is forecast as a whole day; sales already made today are not subtracted.",
            ),
            backtestMape = mape,
            backtestDays = errors.size,
            demand = demand,
        )
    }
}
