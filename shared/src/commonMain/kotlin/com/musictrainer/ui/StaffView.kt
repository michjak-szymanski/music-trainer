package com.musictrainer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.musictrainer.model.Accidental
import com.musictrainer.model.Clef
import com.musictrainer.model.Settings
import com.musictrainer.model.SpelledNote
import com.musictrainer.model.notePool
import com.musictrainer.resources.Res
import com.musictrainer.resources.bravura
import com.musictrainer.session.Phase
import com.musictrainer.session.SessionController
import org.jetbrains.compose.resources.Font
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// SMuFL code points in the Bravura font.
private const val G_CLEF = ""
private const val F_CLEF = ""
private const val WHOLE_NOTE = ""
private const val SHARP = ""
private const val FLAT = ""

val CorrectGreen = Color(0xFF2E9E4F)
val WrongRed = Color(0xFFD93A3A)
private val Gold = Color(0xFFF2B705)

/** Lowest / highest staff positions any note of the current settings can reach. */
private fun positionBounds(settings: Settings): Pair<Int, Int> {
    var lo = 0
    var hi = 8
    for (clef in Clef.entries) {
        for (midi in notePool(clef, settings)) {
            for (flat in listOf(false, true)) {
                val p = SpelledNote.spell(midi, flat).staffPosition(clef)
                lo = min(lo, p)
                hi = max(hi, p)
            }
        }
    }
    return lo to hi
}

@Composable
fun StaffView(session: SessionController, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val bravura = FontFamily(Font(Res.font.bravura))
    val settings = session.settings
    val bounds = remember(settings) { positionBounds(settings) }
    val ink = MaterialTheme.colorScheme.onSurface
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val progress = remember { Animatable(1f) }
    LaunchedEffect(session.feedbackId) {
        if (session.phase == Phase.CORRECT) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }

    Canvas(modifier) {
        val note = session.note ?: return@Canvas
        val clef = session.clef
        val phase = session.phase
        val t = progress.value

        // Fit the staff (centred on its middle line) plus room for ledger lines and labels.
        val above = max(bounds.second + 7, 12) - 4
        val below = 4 - min(bounds.first - 3, -4)
        val half = min(size.height / (2f * max(above, below)), size.width / 44f)
        val space = 2 * half
        val midY = size.height / 2f
        fun y(pos: Int): Float = midY - (pos - 4) * half

        val staffWidth = min(size.width * 0.92f, space * 24)
        val x0 = (size.width - staffWidth) / 2f
        val x1 = x0 + staffWidth
        val lineWidth = max(1f, half * 0.13f)
        for (i in 0..4) drawLine(ink, Offset(x0, y(i * 2)), Offset(x1, y(i * 2)), lineWidth)
        drawLine(ink, Offset(x0, y(0)), Offset(x0, y(8)), lineWidth)
        drawLine(ink, Offset(x1, y(0)), Offset(x1, y(8)), lineWidth)

        val glyphPx = 4 * space // SMuFL: 1 em = 4 staff spaces
        val clefX = x0 + space * 0.6f
        if (clef == Clef.TREBLE) glyph(textMeasurer, bravura, G_CLEF, clefX, y(2), glyphPx, ink)
        else glyph(textMeasurer, bravura, F_CLEF, clefX, y(6), glyphPx, ink)

        val noteX = x0 + staffWidth * 0.55f
        val pos = note.staffPosition(clef)
        val noteColor = when (phase) {
            Phase.CORRECT -> CorrectGreen
            Phase.WRONG -> WrongRed
            else -> ink
        }

        // The wrong key that was played, as a faded note to the right.
        val played = session.playedNote
        if (phase == Phase.WRONG && played != null) {
            val px = noteX + space * 3.2f
            drawNote(textMeasurer, bravura, played, clef, px, ::y, space, glyphPx, faded, lineWidth, 1f)
        }

        // Positive animation: expanding ring and sparks around the note.
        if (phase == Phase.CORRECT && t < 1f) {
            val center = Offset(noteX, y(pos))
            drawCircle(
                CorrectGreen.copy(alpha = 1f - t),
                radius = space * (1f + 3.5f * t),
                center = center,
                style = Stroke(width = half * 0.5f * (1f - t) + 1f),
            )
            for (i in 0 until 12) {
                val angle = i * (2 * PI / 12) + t
                val dist = space * (1.2f + 5f * t)
                drawCircle(
                    (if (i % 2 == 0) Gold else CorrectGreen).copy(alpha = 1f - t),
                    radius = half * 0.55f * (1f - t * 0.7f),
                    center = center + Offset((cos(angle) * dist).toFloat(), (sin(angle) * dist).toFloat()),
                )
            }
        }

        val pop = if (phase == Phase.CORRECT) 1f + 0.4f * sin(PI.toFloat() * t) else 1f
        drawNote(textMeasurer, bravura, note, clef, noteX, ::y, space, glyphPx, noteColor, lineWidth, pop)

        // Note name above the staff after an answer.
        if (phase == Phase.CORRECT || phase == Phase.WRONG) {
            val labelBaseline = y(max(pos, 8) + 4)
            val style = TextStyle(
                fontSize = (space * 1.6f / (density * fontScale)).sp,
                fontWeight = FontWeight.Bold,
                color = noteColor,
            )
            val layout = textMeasurer.measure(note.name, style)
            drawText(layout, topLeft = Offset(noteX - layout.size.width / 2f, labelBaseline - layout.firstBaseline))
        }
    }
}

private fun DrawScope.drawNote(
    tm: TextMeasurer,
    font: FontFamily,
    note: SpelledNote,
    clef: Clef,
    x: Float,
    y: (Int) -> Float,
    space: Float,
    glyphPx: Float,
    color: Color,
    lineWidth: Float,
    scale: Float,
) {
    val pos = note.staffPosition(clef)
    val ledgerHalf = space * 1.15f
    if (pos <= -2) for (lp in -2 downTo pos step 2) {
        drawLine(color, Offset(x - ledgerHalf, y(lp)), Offset(x + ledgerHalf, y(lp)), lineWidth * 1.3f)
    }
    if (pos >= 10) for (lp in 10..pos step 2) {
        drawLine(color, Offset(x - ledgerHalf, y(lp)), Offset(x + ledgerHalf, y(lp)), lineWidth * 1.3f)
    }
    scale(scale, pivot = Offset(x, y(pos))) {
        glyph(tm, font, WHOLE_NOTE, x, y(pos), glyphPx, color, centered = true)
        when (note.accidental) {
            Accidental.SHARP -> glyph(tm, font, SHARP, x - space * 2.3f, y(pos), glyphPx, color)
            Accidental.FLAT -> glyph(tm, font, FLAT, x - space * 2.1f, y(pos), glyphPx, color)
            Accidental.NONE -> Unit
        }
    }
}

/** Draws a music-font glyph with its baseline (SMuFL origin) at [baselineY]. */
private fun DrawScope.glyph(
    tm: TextMeasurer,
    font: FontFamily,
    text: String,
    x: Float,
    baselineY: Float,
    sizePx: Float,
    color: Color,
    centered: Boolean = false,
) {
    val style = TextStyle(fontFamily = font, fontSize = (sizePx / (density * fontScale)).sp, color = color)
    val layout = tm.measure(text, style)
    val left = if (centered) x - layout.size.width / 2f else x
    drawText(layout, topLeft = Offset(left, baselineY - layout.firstBaseline))
}
