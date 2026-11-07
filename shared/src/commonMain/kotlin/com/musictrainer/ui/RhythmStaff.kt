package com.musictrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.musictrainer.model.NotationFont
import com.musictrainer.notation.Meter
import com.musictrainer.notation.TICKS_PER_QUARTER
import com.musictrainer.rhythm.RhythmEvent
import kotlin.math.max
import kotlin.math.min

private const val PERC_CLEF = ""
private const val HEAD_WHOLE = ""
private const val HEAD_HALF = ""
private const val HEAD_BLACK = ""
private const val FLAG_8 = ""
private const val FLAG_16 = ""
private const val REST_WHOLE = ""
private const val REST_HALF = ""
private const val REST_QUARTER = ""
private const val REST_8 = ""
private const val REST_16 = ""
private const val DOT = ""
private const val TUPLET_3 = ""

/**
 * A one-line percussion staff showing [events] in [meter] for bars [firstBar] until [firstBar] + [bars].
 * [colors] tints events (results), [highlight] puts a soft box behind one event, [cursorTick] draws the
 * input cursor, [playTick] a playhead; [anchor] adds the downbeat note after the last bar (dictation).
 */
@Composable
fun RhythmStaff(
    meter: Meter,
    events: List<RhythmEvent>,
    modifier: Modifier = Modifier,
    firstBar: Int = 0,
    bars: Int = 1,
    colors: Map<RhythmEvent, Color> = emptyMap(),
    highlight: RhythmEvent? = null,
    cursorTick: Int? = null,
    playTick: Int? = null,
    anchor: Boolean = false,
) {
    val tm = rememberTextMeasurer()
    val font = musicFont(NotationFont.BRAVURA)
    val ink = MaterialTheme.colorScheme.onSurface
    val box = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val sp = min(size.height / 9f, size.width / 60f)
        val lineY = size.height * 0.6f
        val em = 4 * sp
        val lw = max(1f, sp * 0.12f)
        val left = sp * 0.5f
        // Clef and time signature.
        glyph(tm, font, PERC_CLEF, left + sp * 0.6f, lineY, em, ink)
        val sigX = left + sp * 3.4f
        glyph(tm, font, digits(meter.signature.numerator), sigX, lineY - sp, em, ink)
        glyph(tm, font, digits(meter.signature.denominator), sigX, lineY + sp, em, ink)
        val musicStart = left + sp * 5.8f
        val anchorW = if (anchor) sp * 4f else 0f
        val right = size.width - sp * 0.5f
        val barW = (right - musicStart - anchorW) / bars
        val pad = sp * 1.2f
        val firstTick = firstBar * meter.measureLength
        val lastTick = firstTick + bars * meter.measureLength
        fun x(tick: Int): Float {
            val b = ((tick - firstTick) / meter.measureLength).coerceIn(0, bars)
            val inBar = (tick - firstTick) - b * meter.measureLength
            val bx = musicStart + b * barW
            return if (b == bars) bx + pad else bx + pad + inBar.toFloat() / meter.measureLength * (barW - 2 * pad)
        }
        // The staff line and bar lines.
        drawLine(ink, Offset(left, lineY), Offset(right, lineY), lw)
        for (b in 1..bars) {
            val bx = musicStart + b * barW
            drawLine(ink, Offset(bx, lineY - sp * 1.5f), Offset(bx, lineY + sp * 1.5f), lw * 1.2f)
        }
        val visible = events.filter { it.start >= firstTick && it.start < lastTick }
        // Highlight box behind one event.
        highlight?.takeIf { it in visible }?.let { e ->
            val x0 = x(e.start) - sp * 0.9f
            val x1 = if (e.end >= lastTick || (e.end - firstTick) % meter.measureLength == 0) musicStart + ((e.end - firstTick + meter.measureLength - 1) / meter.measureLength) * barW - sp * 0.3f else x(e.end) - sp * 0.9f
            drawRoundRect(box, Offset(x0, lineY - sp * 4.2f), Size(max(sp * 2f, x1 - x0), sp * 5.8f), CornerRadius(sp * 0.6f))
        }
        // Beams: notes of an eighth or shorter within one beat.
        val beamable = { e: RhythmEvent -> !e.value.rest && e.value.base <= TICKS_PER_QUARTER / 2 }
        val groups = ArrayList<List<RhythmEvent>>()
        run {
            var g = ArrayList<RhythmEvent>()
            var beatOf = -1
            for (e in visible) {
                val beat = e.start / meter.beat
                if (!beamable(e) || beat != beatOf || e.end > (beat + 1) * meter.beat) {
                    if (g.size >= 2) groups += g
                    g = ArrayList(); beatOf = -1
                }
                if (beamable(e) && e.end <= (beat + 1) * meter.beat) { g += e; beatOf = beat }
            }
            if (g.size >= 2) groups += g
        }
        val beamed = groups.flatten().toSet()
        val headW = sp * 1.18f
        val stemH = sp * 3.4f
        fun stemX(e: RhythmEvent) = x(e.start) + headW - lw / 2
        for (e in visible) {
            val c = colors[e] ?: ink
            val ex = x(e.start)
            val v = e.value
            if (v.rest) {
                val g = when {
                    v.base >= 4 * TICKS_PER_QUARTER -> REST_WHOLE
                    v.base >= 2 * TICKS_PER_QUARTER -> REST_HALF
                    v.base >= TICKS_PER_QUARTER -> REST_QUARTER
                    v.base >= TICKS_PER_QUARTER / 2 -> REST_8
                    else -> REST_16
                }
                val rx = if (v.base >= 2 * TICKS_PER_QUARTER) ex - sp * 0.1f else ex
                glyph(tm, font, g, rx, if (g == REST_WHOLE) lineY - sp * 0f else lineY, em, c)
                if (v.dots > 0) glyph(tm, font, DOT, rx + sp * 1.6f, lineY - sp * 0.5f, em, c)
                continue
            }
            val head = when {
                v.base >= 4 * TICKS_PER_QUARTER -> HEAD_WHOLE
                v.base >= 2 * TICKS_PER_QUARTER -> HEAD_HALF
                else -> HEAD_BLACK
            }
            glyph(tm, font, head, ex, lineY, em, c)
            if (v.dots > 0) glyph(tm, font, DOT, ex + headW + sp * 0.4f, lineY - sp * 0.5f, em, c)
            if (head != HEAD_WHOLE) {
                val sx = stemX(e)
                drawLine(c, Offset(sx, lineY - sp * 0.2f), Offset(sx, lineY - stemH), lw * 1.1f)
                if (e !in beamed && v.base <= TICKS_PER_QUARTER / 2) {
                    glyph(tm, font, if (v.base <= TICKS_PER_QUARTER / 4) FLAG_16 else FLAG_8, sx - lw / 2, lineY - stemH, em, c)
                }
            }
        }
        // Beams and tuplet numbers.
        val beamH = sp * 0.5f
        for (g in groups) {
            val c = g.map { colors[it] ?: ink }.distinct().singleOrNull() ?: ink
            val x0 = stemX(g.first()); val x1 = stemX(g.last())
            val top = lineY - stemH
            drawRect(c, Offset(x0 - lw / 2, top), Size(x1 - x0 + lw, beamH))
            val y2 = top + beamH * 1.6f
            for ((i, e) in g.withIndex()) {
                if (e.value.base > TICKS_PER_QUARTER / 4) continue
                val next = g.getOrNull(i + 1)?.takeIf { it.value.base <= TICKS_PER_QUARTER / 4 }
                val prev = g.getOrNull(i - 1)?.takeIf { it.value.base <= TICKS_PER_QUARTER / 4 }
                when {
                    next != null -> drawRect(c, Offset(stemX(e) - lw / 2, y2), Size(stemX(next) - stemX(e) + lw, beamH))
                    prev != null -> Unit
                    else -> {
                        // Partial beam pointing into the group.
                        val toRight = i == 0
                        val len = sp * 1.1f
                        val sx = stemX(e)
                        drawRect(c, Offset(if (toRight) sx - lw / 2 else sx - len, y2), Size(len + lw / 2, beamH))
                    }
                }
            }
            if (g.any { it.value.triplet }) glyph(tm, font, TUPLET_3, (x0 + x1) / 2 - sp * 0.5f, top - sp * 0.6f, em * 0.8f, c)
        }
        // Lone triplet notes (not beamed): number above.
        for (e in visible) if (e.value.triplet && e !in beamed && (e.start % meter.beat) == 0) {
            glyph(tm, font, TUPLET_3, x(e.start), lineY - stemH - sp * 0.8f, em * 0.8f, colors[e] ?: ink)
        }
        // The next downbeat after the last bar (fixes the length of the last note).
        if (anchor) {
            val ax = musicStart + bars * barW + sp * 1.2f
            glyph(tm, font, HEAD_BLACK, ax, lineY, em, ink)
            drawLine(ink, Offset(ax + headW - lw / 2, lineY - sp * 0.2f), Offset(ax + headW - lw / 2, lineY - stemH), lw * 1.1f)
        }
        cursorTick?.let { t ->
            if (t in firstTick..lastTick) {
                val cx = if (t == lastTick) musicStart + bars * barW - sp * 0.5f else x(t) - sp * 0.5f
                drawLine(accent, Offset(cx, lineY - sp * 3.6f), Offset(cx, lineY + sp * 1.8f), lw * 2.4f)
            }
        }
        playTick?.let { t ->
            if (t in firstTick until lastTick) {
                val px = x(t)
                drawLine(accent.copy(alpha = 0.6f), Offset(px, lineY - sp * 4f), Offset(px, lineY + sp * 1.8f), lw * 1.6f)
            }
        }
    }
}

private fun digits(n: Int) = n.toString().map { (0xE080 + (it - '0')).toChar() }.joinToString("")

private fun DrawScope.glyph(tm: TextMeasurer, font: FontFamily, code: String, x: Float, baseline: Float, emPx: Float, color: Color) {
    val style = TextStyle(fontFamily = font, fontSize = (emPx / (density * fontScale)).sp, color = color)
    val layout = tm.measure(code, style)
    drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
}

/** A note value drawn centred in its box (palette buttons). */
@Composable
fun RhythmValueIcon(v: com.musictrainer.rhythm.RhythmValue, color: Color, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val font = musicFont(NotationFont.BRAVURA)
    Canvas(modifier) {
        val em = size.height * 0.62f
        val layout = tm.measure(rhythmValueGlyph(v), TextStyle(fontFamily = font, fontSize = (em / (density * fontScale)).sp, color = color))
        // Notes stand on the baseline with the stem up (about 0.9 em tall); rests are centred on it.
        val baseline = size.height / 2 + if (v.rest) em * 0.05f else em * 0.42f
        drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, baseline - layout.firstBaseline))
    }
}

/** A note value as one music-font string (for buttons): note with stem, flags and dot, or a rest. */
fun rhythmValueGlyph(v: com.musictrainer.rhythm.RhythmValue): String {
    val q = TICKS_PER_QUARTER
    val base = if (v.rest) when {
        v.base >= 4 * q -> REST_WHOLE; v.base >= 2 * q -> REST_HALF; v.base >= q -> REST_QUARTER
        v.base >= q / 2 -> REST_8; else -> REST_16
    } else when {
        v.base >= 4 * q -> ""; v.base >= 2 * q -> ""; v.base >= q -> ""
        v.base >= q / 2 -> ""; else -> ""
    }
    return base + if (v.dots > 0) "" else ""
}
