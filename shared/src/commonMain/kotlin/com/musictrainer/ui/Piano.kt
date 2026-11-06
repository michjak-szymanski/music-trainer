package com.musictrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.musictrainer.model.isBlackKey
import com.musictrainer.model.noteName

private class KeyLayout(val low: Int, val high: Int, val width: Float, val height: Float) {
    val whites = (low..high).filter { !isBlackKey(it) }
    val whiteW = width / whites.size
    val blackW = whiteW * 0.62f
    val blackH = height * 0.62f

    fun whiteRect(i: Int) = Rect(i * whiteW, 0f, (i + 1) * whiteW, height)

    fun blackRect(midi: Int): Rect {
        val leftWhite = whites.indexOf(midi - 1)
        val cx = (leftWhite + 1) * whiteW
        return Rect(cx - blackW / 2, 0f, cx + blackW / 2, blackH)
    }

    val blacks = (low..high).filter { isBlackKey(it) && (it - 1) >= low && (it + 1) <= high }

    fun keyAt(p: Offset): Int? {
        blacks.firstOrNull { blackRect(it).contains(p) }?.let { return it }
        val i = (p.x / whiteW).toInt()
        return whites.getOrNull(i)
    }
}

/**
 * On-screen keyboard spanning [low]..[high] (widened to white keys).
 * Shows highlighted keys and can be tapped as an alternative input. [showNames] labels every key;
 * [showOctaves] adds octave numbers (or, without names, marks just the C keys, e.g. "C4").
 */
@Composable
fun Piano(
    low: Int,
    high: Int,
    highlights: Map<Int, Color>,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onKeyUp: (Int) -> Unit = {},
    showNames: Boolean = false,
    showOctaves: Boolean = false,
) {
    val lo = if (isBlackKey(low)) low - 1 else low
    val hi = if (isBlackKey(high)) high + 1 else high
    val tm = rememberTextMeasurer()
    Canvas(
        modifier.pointerInput(lo, hi) {
            // Press plays the key, lifting the finger releases it (damper).
            awaitEachGesture {
                val down = awaitFirstDown()
                val key = KeyLayout(lo, hi, size.width.toFloat(), size.height.toFloat()).keyAt(down.position)
                key?.let(onKey)
                waitForUpOrCancellation()
                key?.let(onKeyUp)
            }
        }
    ) {
        val k = KeyLayout(lo, hi, size.width, size.height)
        val border = Color(0xFF555555)
        k.whites.forEachIndexed { i, midi ->
            val r = k.whiteRect(i)
            drawRect(highlights[midi] ?: Color.White, r.topLeft, r.size)
            drawRect(border, r.topLeft, r.size, style = Stroke(1f))
            val isC = midi % 12 == 0
            if ((showNames || (showOctaves && isC)) && k.whiteW > 10f) {
                val px = (k.whiteW * 0.38f).coerceAtMost(15f * density)
                val full = noteName(midi) // e.g. "D4"
                val name = when {
                    !showNames -> full
                    !showOctaves -> full.dropLast(1)
                    isC || k.whiteW > px * 2.4f -> full // octave number where it fits; always on C
                    else -> full.dropLast(1)
                }
                val layout = tm.measure(name, TextStyle(fontSize = (px / (density * fontScale)).sp, color = Color(0xFF555555)))
                drawText(layout, topLeft = Offset(r.center.x - layout.size.width / 2f, r.bottom - layout.size.height - 4f))
            }
        }
        for (midi in k.blacks) {
            val r = k.blackRect(midi)
            drawRoundRect(
                highlights[midi] ?: Color(0xFF1B1B1B),
                r.topLeft,
                Size(r.width, r.height),
                CornerRadius(r.width * 0.12f),
            )
            if (showNames && r.width > 9f) {
                val px = (r.width * 0.5f).coerceAtMost(13f * density)
                val layout = tm.measure(
                    noteName(midi).dropLast(1), // e.g. "C♯"
                    TextStyle(fontSize = (px / (density * fontScale)).sp, color = Color(0xFFBBBBBB)),
                )
                drawText(layout, topLeft = Offset(r.center.x - layout.size.width / 2f, r.bottom - layout.size.height - 3f))
            }
        }
    }
}
