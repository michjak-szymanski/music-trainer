package com.musictrainer.ui

import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictrainer.model.Clef
import com.musictrainer.notation.Score
import com.musictrainer.notation.spell
import com.musictrainer.resources.Res
import com.musictrainer.resources.bravura
import org.jetbrains.compose.resources.Font

/** Vertical scroll offset of the page, in px. */
private class ScrollPosition {
    var value by mutableFloatStateOf(0f)
}

/** A wrongly played key shown on the staff at [step]; [alpha] fades it out. */
class WrongNote(val step: Int, val midi: Int, val alpha: Float)

/**
 * Engraved grand-staff view of [score]. The page scrolls so the system holding [currentStep] stays
 * in view; a translucent stripe marks the cursor, which moves [cursorFraction] of the way to the next
 * step (for tempo playback). [noteColors] colours noteheads, keyed by `step * 128 + midi`.
 */
@Composable
fun ScoreView(
    score: Score,
    currentStep: Int,
    modifier: Modifier = Modifier,
    cursorFraction: Float = 0f,
    noteColors: Map<Int, Color> = emptyMap(),
    stripeOpacity: Float = 0.10f,
    /** Called with the play step nearest to a click/tap on the sheet (null = clicks do nothing). */
    onStepClick: ((Int) -> Unit)? = null,
    /** Size multiplier (1 = default). */
    zoom: Float = 1f,
    /** A wrong key to show as a red note next to the current position. */
    wrongNote: WrongNote? = null,
    /** Draw the piece title above the music (off where the screen already shows it). */
    showTitle: Boolean = true,
    style: com.musictrainer.model.NotationStyle = com.musictrainer.model.NotationStyle(),
) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    val bravura = musicFont(style.font)
    val palette = sheetPalette(style.colors)
    val ink = palette.ink
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val space = with(density) { ((maxWidth / 100f).coerceIn(6.dp, 10.dp) * zoom).coerceIn(3.dp, 40.dp).toPx() }
        val layout = remember(score, widthPx, space, showTitle) { layoutScore(score, widthPx, space, showTitle) }
        val viewH = with(density) { maxHeight.toPx() }

        val step = currentStep.coerceIn(0, (layout.stepPos.size - 1).coerceAtLeast(0))
        val sysIndex = layout.stepPos.getOrNull(step)?.system ?: 0
        val scroll = remember(layout) { ScrollPosition() }
        LaunchedEffect(layout, sysIndex) {
            val sys = layout.systems.getOrNull(sysIndex) ?: return@LaunchedEffect
            val target = (sys.top - space * 2).coerceIn(0f, (layout.height - viewH).coerceAtLeast(0f))
            animate(scroll.value, if (sysIndex == 0) 0f else target, animationSpec = tween(450, easing = FastOutSlowInEasing)) { v, _ ->
                scroll.value = v
            }
        }

        // Manual scrolling (mouse wheel, drag, touch); following the cursor still moves the page.
        val scope = rememberCoroutineScope()
        val maxScroll = (layout.height - viewH).coerceAtLeast(0f)
        val scrollState = rememberScrollableState { delta ->
            val target = (scroll.value - delta).coerceIn(0f, maxScroll)
            val consumed = scroll.value - target
            scroll.value = target // applied immediately: smooth wheel, drag and fling
            consumed
        }
        val clickModifier = if (onStepClick == null) Modifier else Modifier.pointerInput(layout, onStepClick) {
            detectTapGestures { tap ->
                val py = tap.y + scroll.value
                val sys = layout.systems.indexOfFirst { py >= it.top && py <= it.bottom }.takeIf { it >= 0 }
                    ?: layout.systems.indices.minByOrNull { i -> minOf(kotlin.math.abs(layout.systems[i].top - py), kotlin.math.abs(layout.systems[i].bottom - py)) }
                    ?: return@detectTapGestures
                val nearest = layout.stepPos.withIndex().filter { it.value.system == sys }.minByOrNull { kotlin.math.abs(it.value.x - tap.x) }
                if (nearest != null) onStepClick(nearest.index)
            }
        }
        Canvas(Modifier.fillMaxSize().scrollable(scrollState, Orientation.Vertical).dragToScroll(scrollState, reverse = true).then(clickModifier)) {
            palette.paper?.let { drawRect(it) }
            translate(top = -scroll.value) {
                if (showTitle) {
                val title = tm.measure(score.title, TextStyle(fontSize = (space * 2f / (this.density * fontScale)).sp, fontWeight = FontWeight.Bold, color = ink))
                drawText(title, topLeft = Offset((size.width - title.size.width) / 2f, space * 1.2f))
                }

                // Cursor stripe.
                val pos = layout.stepPos.getOrNull(step)
                if (pos != null && stripeOpacity > 0f) {
                    val next = layout.stepPos.getOrNull(step + 1)
                    val sys = layout.systems[pos.system]
                    val cx = if (next != null && next.system == pos.system) pos.x + (next.x - pos.x) * cursorFraction.coerceIn(0f, 1f) else pos.x
                    val w = space * 2.6f
                    drawRoundRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.2f to accent.copy(alpha = stripeOpacity),
                            0.8f to accent.copy(alpha = stripeOpacity), 1f to Color.Transparent,
                            startY = sys.top, endY = sys.bottom,
                        ),
                        Offset(cx - w / 2, sys.top), Size(w, sys.bottom - sys.top), CornerRadius(space * 0.6f),
                    )
                }

                // The wrong key, as a red notehead just right of the step it was played at.
                if (wrongNote != null) layout.stepPos.getOrNull(wrongNote.step)?.let { pos ->
                    val sys = layout.systems[pos.system]
                    val staffClef = if (wrongNote.midi >= 60) Clef.TREBLE else Clef.BASS
                    // Same vertical layout as layoutScore: treble bottom line 7 spaces below the system top, bass 11 below that.
                    val bottom = sys.top + space * 7f + if (staffClef == Clef.BASS) space * 11f else 0f
                    val wp = spell(wrongNote.midi, score.key)
                    val p = wp.staffPosition(staffClef)
                    fun wy(pos: Int) = bottom - pos * space / 2
                    val color = WrongRed.copy(alpha = wrongNote.alpha)
                    val x = pos.x + space * 1.4f
                    val ledger = space * 0.16f
                    if (p <= -2) for (lp in -2 downTo p step 2) drawLine(color, Offset(x - space * 0.35f, wy(lp)), Offset(x + space * 1.53f, wy(lp)), ledger)
                    if (p >= 10) for (lp in 10..p step 2) drawLine(color, Offset(x - space * 0.35f, wy(lp)), Offset(x + space * 1.53f, wy(lp)), ledger)
                    glyph(tm, bravura, Smufl.HEAD_BLACK, x, wy(p), space * 4f, color)
                    if (wp.alter != 0) glyph(tm, bravura, if (wp.alter > 0) Smufl.SHARP else Smufl.FLAT, x - space * 1.3f, wy(p), space * 4f, color)
                }

                for (sys in layout.systems) {
                    if (sys.bottom < scroll.value || sys.top > scroll.value + size.height) continue
                    for (l in sys.lines) drawLine(ink, Offset(l.x1, l.y1), Offset(l.x2, l.y2), l.width, cap = StrokeCap.Butt)
                    for (b in sys.beams) {
                        val p = Path().apply {
                            moveTo(b.x1, b.y1); lineTo(b.x2, b.y2); lineTo(b.x2, b.y2 + b.thickness); lineTo(b.x1, b.y1 + b.thickness); close()
                        }
                        drawPath(p, ink)
                    }
                    for (t in sys.ties) drawTie(t, space, ink)
                    for (br in sys.braces) drawBrace(br, space, ink)
                    for (g in sys.glyphs) {
                        val color = if (g.step >= 0) noteColors[g.step * 128 + g.midi] ?: ink else ink
                        glyph(tm, bravura, g.code, g.x, g.y, space * 4f * g.scale, color)
                    }
                }
            }
        }
        // Scrollbar: visible whenever the page is taller than the view; drag the thumb to scroll.
        if (maxScroll > 0f) {
            val thumbH = (viewH * viewH / layout.height).coerceAtLeast(with(density) { 32.dp.toPx() })
            val thumbY = (scroll.value / maxScroll) * (viewH - thumbH)
            val barColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(
                Modifier.align(Alignment.TopEnd).width(14.dp).fillMaxHeight()
                    .pointerInput(layout, viewH) {
                        detectVerticalDragGestures { change, dy ->
                            change.consume()
                            val target = (scroll.value + dy * maxScroll / (viewH - thumbH)).coerceIn(0f, maxScroll)
                            scroll.value = target
                        }
                    }
            ) {
                val w = 6.dp.toPx()
                drawRoundRect(barColor.copy(alpha = 0.12f), Offset(size.width - w - 3.dp.toPx(), 0f), Size(w, size.height), CornerRadius(w / 2))
                drawRoundRect(barColor.copy(alpha = 0.55f), Offset(size.width - w - 3.dp.toPx(), thumbY), Size(w, thumbH), CornerRadius(w / 2))
            }
        }
    }
}

private fun DrawScope.glyph(tm: TextMeasurer, font: FontFamily, code: String, x: Float, baseline: Float, emPx: Float, color: Color) {
    val layout = tm.measure(code, TextStyle(fontFamily = font, fontSize = (emPx / (density * fontScale)).sp, color = color))
    drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
}

/** A tie: a thin crescent between two noteheads, curving away from the stems. */
private fun DrawScope.drawTie(t: TiePrim, space: Float, color: Color) {
    val len = t.x2 - t.x1
    if (len <= 1f) return
    val h = (len * 0.18f).coerceIn(space * 0.35f, space * 1.1f) * (if (t.below) 1 else -1)
    val thick = space * 0.18f * (if (t.below) 1 else -1)
    val p = Path().apply {
        moveTo(t.x1, t.y1)
        cubicTo(t.x1 + len * 0.25f, t.y1 + h, t.x2 - len * 0.25f, t.y2 + h, t.x2, t.y2)
        cubicTo(t.x2 - len * 0.25f, t.y2 + h - thick, t.x1 + len * 0.25f, t.y1 + h - thick, t.x1, t.y1)
        close()
    }
    drawPath(p, color)
}

/** Grand-staff brace, drawn as a filled curly bracket. */
private fun DrawScope.drawBrace(b: BracePrim, space: Float, color: Color) {
    val h = b.bottom - b.top
    val mid = b.top + h / 2
    val xr = b.x
    val xl = b.x - space * 1.5f
    val w = space
    val p = Path().apply {
        moveTo(xr, b.top)
        cubicTo(xr - w * 1.7f, b.top + h * 0.06f, xr + w * 0.2f, mid - h * 0.14f, xl, mid)
        cubicTo(xr + w * 0.2f, mid + h * 0.14f, xr - w * 1.7f, b.bottom - h * 0.06f, xr, b.bottom)
        cubicTo(xr - w * 1.1f, b.bottom - h * 0.09f, xr + w * 0.75f, mid + h * 0.12f, xl, mid)
        cubicTo(xr + w * 0.75f, mid - h * 0.12f, xr - w * 1.1f, b.top + h * 0.09f, xr, b.top)
        close()
    }
    drawPath(p, color)
}
