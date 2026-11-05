package com.musictrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class TrendPoint(val value: Double, val dateLabel: String, val hollow: Boolean = false)

/**
 * One measure over sessions (oldest left). [higherIsBetter] decides the wording of the
 * trend line, which compares the last 5 sessions with the 5 before them.
 */
@Composable
fun TrendChart(
    title: String,
    points: List<TrendPoint>,
    format: (Double) -> String,
    higherIsBetter: Boolean = true,
    fixedRange: ClosedFloatingPointRange<Double>? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                trendText(points, format, higherIsBetter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (points.size < 2) {
                Text("Play at least 2 sessions to see a trend.", style = MaterialTheme.typography.bodySmall)
            } else {
                LineCanvas(points, format, fixedRange, Modifier.fillMaxWidth().height(150.dp))
            }
        }
    }
}

private fun trendText(points: List<TrendPoint>, format: (Double) -> String, higherIsBetter: Boolean): String {
    if (points.size < 4) return "Latest: ${points.lastOrNull()?.let { format(it.value) } ?: "–"}"
    val n = min(5, points.size / 2)
    val recent = points.takeLast(n).map { it.value }.average()
    val before = points.dropLast(n).takeLast(n).map { it.value }.average()
    val diff = recent - before
    val scale = max(abs(before), 1e-9)
    if (abs(diff) / scale < 0.03) return "→ Steady: last $n sessions average ${format(recent)}"
    val up = diff > 0
    val better = up == higherIsBetter
    val arrow = if (up) "↑" else "↓"
    val word = if (better) "Improving" else "Getting worse"
    return "$arrow $word: last $n sessions average ${format(recent)} vs ${format(before)} before"
}

private fun niceStep(range: Double): Double {
    val raw = range / 3
    val mag = 10.0.pow(floor(log10(max(raw, 1e-9))))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

@Composable
private fun LineCanvas(
    points: List<TrendPoint>,
    format: (Double) -> String,
    fixedRange: ClosedFloatingPointRange<Double>?,
    modifier: Modifier,
) {
    val tm = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val series = colors.primary
    val grid = colors.outlineVariant
    val muted = colors.onSurfaceVariant
    val surface = colors.surfaceContainerHighest
    val ink = colors.onSurface
    val card = colors.surfaceContainerHighest
    var hover by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier.pointerInput(points) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    hover = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position
                }
            }
        }
    ) {
        val labelStyle = TextStyle(fontSize = 11.sp, color = muted)
        val values = points.map { it.value }
        var lo = fixedRange?.start ?: values.min()
        var hi = fixedRange?.endInclusive ?: values.max()
        if (fixedRange == null) {
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            val step = niceStep(hi - lo)
            lo = floor(lo / step) * step
            hi = ceil(hi / step) * step
        }
        val step = niceStep(hi - lo)
        val ticks = generateSequence(lo) { it + step }.takeWhile { it <= hi + step * 0.01 }.toList()

        val left = ticks.maxOf { tm.measure(format(it), labelStyle).size.width } + 8f
        val bottom = size.height - 18f
        val top = 8f
        val right = size.width - 8f
        fun x(i: Int) = left + (right - left) * i / (points.size - 1)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()

        // Recessive grid with y labels.
        for (t in ticks) {
            drawLine(grid, Offset(left, y(t)), Offset(right, y(t)), 1f)
            val l = tm.measure(format(t), labelStyle)
            drawText(l, topLeft = Offset(left - 6f - l.size.width, y(t) - l.size.height / 2f))
        }
        // First / last date on the x axis.
        val first = tm.measure(points.first().dateLabel, labelStyle)
        drawText(first, topLeft = Offset(left, bottom + 3f))
        val last = tm.measure(points.last().dateLabel, labelStyle)
        drawText(last, topLeft = Offset(right - last.size.width, bottom + 3f))

        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(i), y(p.value)) else path.lineTo(x(i), y(p.value))
        }
        drawPath(path, series, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Markers only when they don't crowd (selective, not every point when dense).
        val showMarkers = (right - left) / points.size > 10f
        val r = 4.dp.toPx()
        val hoverIndex = hover?.let { h ->
            ((h.x - left) / (right - left) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
        }
        points.forEachIndexed { i, p ->
            if (!showMarkers && i != points.lastIndex && i != hoverIndex) return@forEachIndexed
            val c = Offset(x(i), y(p.value))
            drawCircle(surface, r + 2f, c) // surface ring
            if (p.hollow) drawCircle(series, r - 1f, c, style = Stroke(2f)) else drawCircle(series, r, c)
        }

        // Hover / tap: crosshair + tooltip.
        if (hoverIndex != null) {
            val p = points[hoverIndex]
            val cx = x(hoverIndex)
            drawLine(muted, Offset(cx, top), Offset(cx, bottom), 1f)
            val valueLayout = tm.measure(format(p.value), TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink))
            val dateLayout = tm.measure(
                p.dateLabel + if (p.hollow) " (stopped early)" else "",
                TextStyle(fontSize = 11.sp, color = muted),
            )
            val w = max(valueLayout.size.width, dateLayout.size.width) + 16f
            val h = valueLayout.size.height + dateLayout.size.height + 10f
            val bx = (if (cx + 12f + w > right) cx - 12f - w else cx + 12f).coerceAtLeast(0f)
            val by = top
            drawRoundRect(card, Offset(bx, by), Size(w, h), CornerRadius(6f))
            drawRoundRect(grid, Offset(bx, by), Size(w, h), CornerRadius(6f), style = Stroke(1f))
            drawText(valueLayout, topLeft = Offset(bx + 8f, by + 5f))
            drawText(dateLayout, topLeft = Offset(bx + 8f, by + 5f + valueLayout.size.height))
        }
    }
}
