package com.musictrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
 * Shows highlighted keys and can be tapped as an alternative input.
 */
@Composable
fun Piano(
    low: Int,
    high: Int,
    highlights: Map<Int, Color>,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lo = if (isBlackKey(low)) low - 1 else low
    val hi = if (isBlackKey(high)) high + 1 else high
    val tm = rememberTextMeasurer()
    Canvas(
        modifier.pointerInput(lo, hi) {
            detectTapGestures { p ->
                KeyLayout(lo, hi, size.width.toFloat(), size.height.toFloat()).keyAt(p)?.let(onKey)
            }
        }
    ) {
        val k = KeyLayout(lo, hi, size.width, size.height)
        val border = Color(0xFF555555)
        k.whites.forEachIndexed { i, midi ->
            val r = k.whiteRect(i)
            drawRect(highlights[midi] ?: Color.White, r.topLeft, r.size)
            drawRect(border, r.topLeft, r.size, style = Stroke(1f))
            if (midi % 12 == 0 && k.whiteW > 14f) {
                val layout = tm.measure(
                    noteName(midi),
                    TextStyle(fontSize = (k.whiteW * 0.42f / (density * fontScale)).coerceAtMost(13f).sp, color = Color(0xFF666666)),
                )
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
        }
    }
}
