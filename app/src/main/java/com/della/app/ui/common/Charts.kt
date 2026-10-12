package com.della.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.della.app.ui.theme.Della

/**
 * Vertical bars. The last bar can be highlighted (e.g. "today, in progress").
 * Shows the max value as a reference line label; labels shown sparsely to stay legible.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
    highlightLast: Boolean = false,
    height: Int = 150,
) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val description = labels.zip(values).joinToString { "${it.first}: ${format(it.second)}" }
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Bar chart. $description" }) {
        Text("max ${format(max)}", style = MaterialTheme.typography.labelSmall, color = Della.TextFaint)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.64f).coerceAtMost(28.dp.toPx())
            // baseline + max guide
            drawLine(Della.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(Della.Border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            values.forEachIndexed { i, v ->
                val h = (v.toFloat() / max) * size.height
                val color = if (highlightLast && i == values.lastIndex) Della.Accent else Della.Primary
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                    size = Size(barW, h.coerceAtLeast(if (v > 0) 2f else 0f)),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val step = when { labels.size <= 8 -> 1; labels.size <= 16 -> 2; labels.size <= 31 -> 5; else -> 7 }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % step == 0 || i == labels.lastIndex) {
                        Text(l, style = MaterialTheme.typography.labelSmall, color = Della.TextMuted, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                }
            }
        }
    }
}

/** Line chart with an optional shaded range band (used for forecasts). */
@Composable
fun LineChart(
    values: List<Long>,
    labels: List<String>,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
    low: List<Long>? = null,
    high: List<Long>? = null,
    height: Int = 160,
    lineColor: Color = Della.Accent,
) {
    val max = (listOfNotNull(values.maxOrNull(), high?.maxOrNull()).maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Line chart. " + labels.zip(values).joinToString { "${it.first}: ${format(it.second)}" } }) {
        Text("max ${format(max)}", style = MaterialTheme.typography.labelSmall, color = Della.TextFaint)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            if (values.isEmpty()) return@Canvas
            val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
            fun y(v: Long) = size.height - (v.toFloat() / max) * size.height * 0.95f
            drawLine(Della.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            if (low != null && high != null && low.size == values.size && high.size == values.size) {
                val band = Path().apply {
                    moveTo(0f, y(high[0]))
                    high.forEachIndexed { i, v -> lineTo(i * stepX, y(v)) }
                    for (i in low.indices.reversed()) lineTo(i * stepX, y(low[i]))
                    close()
                }
                drawPath(band, Della.Primary.copy(alpha = 0.28f))
            }
            val path = Path().apply {
                values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            values.forEachIndexed { i, v -> drawCircle(lineColor, 3.dp.toPx(), Offset(i * stepX, y(v))) }
        }
        Spacer(Modifier.height(6.dp))
        val step = when { labels.size <= 8 -> 1; labels.size <= 16 -> 2; else -> 5 }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.weight(1f), contentAlignment = if (i == 0) Alignment.CenterStart else if (i == labels.lastIndex) Alignment.CenterEnd else Alignment.Center) {
                    if (i % step == 0 || i == labels.lastIndex) Text(l, style = MaterialTheme.typography.labelSmall, color = Della.TextMuted, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Ranked horizontal bars, readable on narrow screens. */
@Composable
fun HorizontalBars(items: List<Pair<String, Long>>, format: (Long) -> String, modifier: Modifier = Modifier, color: Color = Della.Primary) {
    val max = (items.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Column(modifier.fillMaxWidth()) {
        items.forEach { (label, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Della.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(format(v), style = MaterialTheme.typography.bodyMedium, color = Della.TextMuted)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Della.PanelHigh, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth((v.toFloat() / max).coerceIn(0f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

/** Small proportional meter, e.g. stock vs reorder level. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(64.dp).height(6.dp).background(Della.PanelHigh, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
    }
}
