package com.musictrainer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
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
import com.musictrainer.model.POINTS_CORRECT
import com.musictrainer.model.POINTS_WRONG
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
fun StaffView(session: SessionController, modifier: Modifier = Modifier, stripeOpacity: Float = 0.07f) {
    val textMeasurer = rememberTextMeasurer()
    val bravura = FontFamily(Font(Res.font.bravura))
    val settings = session.settings
    val bounds = remember(settings) { positionBounds(settings) }
    val ink = MaterialTheme.colorScheme.onSurface
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    // Reading-position stripe behind the current note: accent while waiting, green/red after answering.
    val stripeColor by animateColorAsState(
        when (session.phase) {
            Phase.CORRECT -> CorrectGreen
            Phase.WRONG -> WrongRed
            else -> MaterialTheme.colorScheme.primary
        },
        tween(250),
    )

    // Each animation is (re)created at its start value during composition, so the very first
    // frame after a change is already correct (snapping in an effect would lag one frame).
    val progress = remember(session.feedbackId) { Animatable(if (session.phase == Phase.CORRECT) 0f else 1f) }
    LaunchedEffect(session.feedbackId) {
        if (progress.value < 1f) progress.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
    }
    // New note entrance: slides in from the right, fades in and pops to size with a little overshoot.
    val appear = remember(session.index, session.note) { Animatable(0f) }
    LaunchedEffect(session.index, session.note) {
        appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
    }
    // Wrong answer: the note shakes horizontally.
    val shake = remember(session.feedbackId) { Animatable(if (session.phase == Phase.WRONG) 0f else 1f) }
    LaunchedEffect(session.feedbackId) {
        if (shake.value < 1f) shake.animateTo(1f, tween(450, easing = LinearEasing))
    }
    // Slide everything one note to the left when a new note appears (1 = one spacing to the right).
    val scroll = remember(session.index) { Animatable(if (session.index > 0) 1f else 0f) }
    LaunchedEffect(session.index) {
        if (scroll.value > 0f) scroll.animateTo(0f, tween(350, easing = FastOutSlowInEasing))
    }

    Canvas(modifier) {
        val note = session.note ?: return@Canvas
        val clef = session.clef
        val phase = session.phase
        val t = progress.value

        // Fit the staff (centred on its middle line) plus room for ledger lines and labels.
        val above = max(bounds.second + 7, 12) - 4
        val below = 4 - min(bounds.first - 3, -4)
        val half = min(size.height / (2f * max(above, below)), size.width / 40f)
        val space = 2 * half
        val midY = size.height / 2f
        fun y(pos: Int): Float = midY - (pos - 4) * half

        // The staff spans the full width.
        val x0 = space * 0.5f
        val x1 = size.width - space * 0.5f
        val lineWidth = max(1f, half * 0.13f)
        for (i in 0..4) drawLine(ink, Offset(x0, y(i * 2)), Offset(x1, y(i * 2)), lineWidth)
        drawLine(ink, Offset(x0, y(0)), Offset(x0, y(8)), lineWidth)
        drawLine(ink, Offset(x1, y(0)), Offset(x1, y(8)), lineWidth)

        val glyphPx = 4 * space // SMuFL: 1 em = 4 staff spaces
        val clefAreaEnd = x0 + space * 4.2f
        val spacing = space * 5f
        // Current note in the middle; earlier notes trail off to its left.
        val noteX = max((x0 + x1) / 2f, clefAreaEnd + spacing * 1.5f)
        val shift = scroll.value * spacing
        fun xBack(k: Int) = noteX - k * spacing + shift // k notes before the current one
        /** Fades notes out as they approach the leading clef. */
        fun alphaAt(x: Float) = ((x - clefAreaEnd - space) / spacing).coerceIn(0f, 1f)

        // Translucent vertical stripe marking the current note's position (fades out at top and bottom).
        val stripeHalf = space * 1.8f
        if (stripeOpacity > 0f) drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.25f to stripeColor.copy(alpha = stripeOpacity),
                0.75f to stripeColor.copy(alpha = stripeOpacity),
                1f to Color.Transparent,
            ),
            topLeft = Offset(noteX - stripeHalf, 0f),
            size = Size(stripeHalf * 2, size.height),
            cornerRadius = CornerRadius(space * 0.6f),
        )

        // Previously answered notes, newest nearest the current note.
        val trail = session.trail
        var leftmostClef = clef
        for (k in 1..trail.size) {
            val x = xBack(k)
            val a = alphaAt(x)
            if (a <= 0f) break
            val item = trail[trail.size - k]
            val newer = if (k == 1) clef else trail[trail.size - k + 1].clef
            if (newer != item.clef) {
                // Clef change between this note and the next one: small clef, as in printed music.
                val cx = x + spacing * 0.5f - space * 0.9f
                val ca = alphaAt(cx)
                if (newer == Clef.TREBLE) glyph(textMeasurer, bravura, G_CLEF, cx, y(2), glyphPx * 0.75f, ink.copy(alpha = ca))
                else glyph(textMeasurer, bravura, F_CLEF, cx, y(6), glyphPx * 0.75f, ink.copy(alpha = ca))
            }
            val color = (if (item.correct) CorrectGreen else WrongRed).copy(alpha = a)
            drawNote(textMeasurer, bravura, item.note, item.clef, x, ::y, space, glyphPx, color, lineWidth, 1f)
            if (!item.correct) {
                drawLabel(textMeasurer, item.note.name, x, y(max(item.note.staffPosition(item.clef), 8) + 3), space * 1.1f, color)
            }
            if (a >= 0.5f) leftmostClef = item.clef
        }

        // Leading clef: the clef of the leftmost visible note.
        val clefX = x0 + space * 0.6f
        if (leftmostClef == Clef.TREBLE) glyph(textMeasurer, bravura, G_CLEF, clefX, y(2), glyphPx, ink)
        else glyph(textMeasurer, bravura, F_CLEF, clefX, y(6), glyphPx, ink)

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

        val a = appear.value
        val pop = if (phase == Phase.CORRECT) 1f + 0.4f * sin(PI.toFloat() * t) else 1f
        val enterScale = 0.4f + 0.6f * a // overshoots past 1 with the spring
        val st = shake.value
        val shakeX = if (st < 1f) sin(st * PI.toFloat() * 6f) * (1f - st) * space * 0.6f else 0f
        val enterX = (1f - a.coerceIn(0f, 1f)) * spacing * 1.2f
        drawNote(
            textMeasurer, bravura, note, clef, noteX + shift + enterX + shakeX, ::y, space, glyphPx,
            noteColor.copy(alpha = noteColor.alpha * a.coerceIn(0f, 1f)), lineWidth, pop * enterScale,
        )

        // Floating points after an answer: "+10" rises from a correct note, "−5" sinks from a wrong one.
        val pointsT = if (phase == Phase.CORRECT) t else if (phase == Phase.WRONG) st else 1f
        if (pointsT < 1f) {
            val correctAnswer = phase == Phase.CORRECT
            val label = if (correctAnswer) "+$POINTS_CORRECT" else "−$POINTS_WRONG"
            val rise = if (correctAnswer) -space * 3f * pointsT else space * 1.5f * pointsT
            drawLabel(
                textMeasurer, label, noteX + space * 3f, y(pos) + rise, space * 1.3f,
                noteColor.copy(alpha = 1f - pointsT * pointsT), bold = true,
            )
        }

        // Note name above the staff after an answer.
        if (phase == Phase.CORRECT || phase == Phase.WRONG) {
            drawLabel(textMeasurer, note.name, noteX, y(max(pos, 8) + 4), space * 1.6f, noteColor, bold = true)
        }
    }
}

private fun DrawScope.drawLabel(
    tm: TextMeasurer,
    text: String,
    centerX: Float,
    baselineY: Float,
    sizePx: Float,
    color: Color,
    bold: Boolean = false,
) {
    val style = TextStyle(
        fontSize = (sizePx / (density * fontScale)).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        color = color,
    )
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(centerX - layout.size.width / 2f, baselineY - layout.firstBaseline))
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
