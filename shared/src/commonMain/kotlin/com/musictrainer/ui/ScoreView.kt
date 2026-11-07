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

/** A loop drawn on the sheet: [bars] (0-based measure indices), [label], [active] (on and looping enabled). */
/** A wrong key [midi] played at [step], [label] (e.g. "×3") written beside it. */
class GhostNote(val step: Int, val midi: Int, val label: String, val alpha: Float = 1f)

/** A note or bookmark on the notes from step [from] to [to]. */
class FragmentMark(val from: Int, val to: Int, val label: String)

class LoopMark(val bars: IntRange, val label: String, val active: Boolean, val color: Color? = null, val bracket: Boolean = true, val steps: IntRange? = null, val id: Long = 0)

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
    /** Loops to mark: bar range (0-based), label, and whether it is active (shaded stronger). */
    loopMarks: List<LoopMark> = emptyList(),
    /** Notes and bookmarks: (bar, label) drawn above the bar. */
    barNotes: List<Pair<Int, String>> = emptyList(),
    /** Small text under each bar (e.g. chord symbols), by bar (0-based). */
    barCaptions: Map<Int, String> = emptyMap(),
    /** Notes / bookmarks on fragments: play steps from..to with a label. */
    fragments: List<FragmentMark> = emptyList(),
    /** A fragment being chosen (first note picked): highlighted. */
    pickedStep: Int? = null,
    /** Wrong keys to draw as red ghost notes beside their step, with a count. */
    ghosts: List<GhostNote> = emptyList(),
    /** Told which note the mouse is over (null when it leaves), e.g. to preview a loop before clicking. */
    onStepHover: ((Int?) -> Unit)? = null,
    /** A click on a loop's label or bracket (its [LoopMark.id]): e.g. switch it on or off. */
    onLoopClick: ((Long) -> Unit)? = null,
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
        /** The note nearest to a point on the canvas (on the system under it). */
        fun stepAt(x: Float, y: Float): Int? {
            val py = y + scroll.value
            val sys = layout.systems.indexOfFirst { py >= it.top && py <= it.bottom }.takeIf { it >= 0 }
                ?: layout.systems.indices.minByOrNull { i -> minOf(kotlin.math.abs(layout.systems[i].top - py), kotlin.math.abs(layout.systems[i].bottom - py)) }
                ?: return null
            return layout.stepPos.withIndex().filter { it.value.system == sys }.minByOrNull { kotlin.math.abs(it.value.x - x) }?.index
        }
        // Where loop labels / brackets were drawn (canvas coordinates incl. scroll), for clicks on them.
        val loopHits = remember { ArrayList<Pair<androidx.compose.ui.geometry.Rect, Long>>() }
        val clickModifier = (if (onStepClick == null && onLoopClick == null) Modifier else Modifier.pointerInput(layout, onStepClick, onLoopClick) {
            detectTapGestures { tap ->
                val hit = onLoopClick?.let { loopHits.lastOrNull { (r, _) -> r.contains(Offset(tap.x, tap.y + scroll.value)) } }
                if (hit != null) onLoopClick(hit.second) else onStepClick?.let { f -> stepAt(tap.x, tap.y)?.let(f) }
            }
        }).then(if (onStepHover == null) Modifier else Modifier.pointerInput(layout, onStepHover) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    when (e.type) {
                        androidx.compose.ui.input.pointer.PointerEventType.Exit -> onStepHover(null)
                        else -> e.changes.firstOrNull()?.position?.let { onStepHover(stepAt(it.x, it.y)) }
                    }
                }
            }
        })
        Canvas(Modifier.fillMaxSize().scrollable(scrollState, Orientation.Vertical).dragToScroll(scrollState, reverse = true).then(clickModifier)) {
            palette.paper?.let { drawRect(it) }
            translate(top = -scroll.value) {
                if (showTitle) {
                val title = tm.measure(score.title, TextStyle(fontSize = (space * 2f / (this.density * fontScale)).sp, fontWeight = FontWeight.Bold, color = ink))
                drawText(title, topLeft = Offset((size.width - title.size.width) / 2f, space * 1.2f))
                }

                // Labels above systems (loops, notes, bookmarks) share one row per system and never overlap.
                val taken = HashMap<Int, MutableList<ClosedFloatingPointRange<Float>>>()
                fun place(system: Int, x: Float, w: Float): Float {
                    var at = x
                    val row = taken.getOrPut(system) { ArrayList() }
                    var moved = true
                    while (moved) {
                        moved = false
                        for (r in row) if (at < r.endInclusive && at + w > r.start) { at = r.endInclusive + space * 0.6f; moved = true }
                    }
                    row += at..(at + w)
                    return at
                }
                // Loops: a soft band over their bars with a bracket and label on top (each system part).
                loopHits.clear()
                for (lm in loopMarks) {
                    // Note-precise loops: from just before the first note to just before the note after the last.
                    val boxes = lm.steps?.let { st ->
                        val a = layout.stepPos.getOrNull(st.first)
                        val b = layout.stepPos.getOrNull(st.last)
                        val next = layout.stepPos.getOrNull(st.last + 1)
                        if (a == null || b == null) emptyList() else (a.system..b.system).mapNotNull { si ->
                            val sys = layout.systems.getOrNull(si) ?: return@mapNotNull null
                            val x0 = if (si == a.system) a.x - space * 0.9f else sys.startX
                            val x1 = if (si == b.system) (if (next != null && next.system == si) next.x - space * 0.9f else maxOf(b.x + space * 2.2f, sys.endX.coerceAtMost(b.x + space * 6f))) else sys.endX
                            MeasureBox(si, x0, x1)
                        }
                    } ?: lm.bars.mapNotNull { layout.measureBoxes.getOrNull(it) }
                    val color = lm.color ?: if (lm.active) accent else ink
                    for ((sysIndex, parts) in boxes.groupBy { it.system }) {
                        val sys = layout.systems.getOrNull(sysIndex) ?: continue
                        val x0 = parts.minOf { it.x1 }
                        val x1 = parts.maxOf { it.x2 }
                        // Loops that are off get no band over the music, just a faint bracket and label.
                        if (lm.color != null || lm.active) drawRoundRect(color.copy(alpha = if (lm.color != null) 0.16f else 0.10f), Offset(x0, sys.top - space * 0.6f), Size(x1 - x0, sys.bottom - sys.top + space * 1.2f), CornerRadius(space * 0.5f))
                        // Bracket: a line with end ticks above the system.
                        // The staff's top line is 3 spaces below sys.top; the bracket sits just above the staff.
                        val by = sys.top + space * 0.6f
                        val stroke = space * 0.18f
                        val c = color.copy(alpha = if (lm.active) 0.9f else 0.45f)
                        if (lm.bracket) {
                            drawLine(c, Offset(x0, by), Offset(x1, by), stroke)
                            if (parts.first().x1 == boxes.first().x1) drawLine(c, Offset(x0, by), Offset(x0, by + space * 0.9f), stroke)
                            if (parts.last().x2 == boxes.last().x2) drawLine(c, Offset(x1, by), Offset(x1, by + space * 0.9f), stroke)
                        }
                        if (parts.first() === boxes.first() && lm.label.isNotEmpty()) {
                            val label = tm.measure((if (lm.bracket) "🔁 " else "") + lm.label, TextStyle(fontSize = (space * 1.3f / (this.density * fontScale)).sp, color = c, fontWeight = FontWeight.Bold))
                            val lx = place(sysIndex, x0 + space * 0.4f, label.size.width.toFloat())
                            drawText(label, topLeft = Offset(lx, by - label.size.height - space * 0.1f))
                            if (lm.id != 0L) loopHits += androidx.compose.ui.geometry.Rect(lx - space * 0.3f, by - label.size.height - space * 0.3f, lx + label.size.width + space * 0.3f, by + space * 0.3f) to lm.id
                        }
                        // The bracket line is clickable too.
                        if (lm.id != 0L && lm.bracket) loopHits += androidx.compose.ui.geometry.Rect(x0, by - space * 0.6f, x1, by + space * 1.0f) to lm.id
                    }
                }

                // Notes and bookmarks above their bars (stacked when several share a bar; above loop labels).
                for ((bar, items) in barNotes.groupBy { it.first }) {
                    val box = layout.measureBoxes.getOrNull(bar) ?: continue
                    val sys = layout.systems.getOrNull(box.system) ?: continue
                    for ((_, text) in items) {
                        val shown = if (text.length > 48) text.take(46) + "…" else text
                        val label = tm.measure(shown, TextStyle(fontSize = (space * 1.2f / (this.density * fontScale)).sp, color = TimingAmber, fontWeight = FontWeight.Medium))
                        val y = sys.top + space * 0.5f - label.size.height
                        val x = place(box.system, box.x1 + space * 0.2f, label.size.width + space * 0.8f)
                        drawRoundRect(TimingAmber.copy(alpha = 0.12f), Offset(x, y - space * 0.1f), Size(label.size.width + space * 0.8f, label.size.height + space * 0.2f), CornerRadius(space * 0.3f))
                        drawText(label, topLeft = Offset(x + space * 0.4f, y))
                    }
                }

                // Fragments: a soft orange band from the first to the last note (per system), label above.
                val noteInk = TimingAmber
                for (f in fragments) {
                    val a = layout.stepPos.getOrNull(f.from) ?: continue
                    val b = layout.stepPos.getOrNull(f.to.coerceAtMost(layout.stepPos.lastIndex)) ?: continue
                    for (si in a.system..b.system) {
                        val sys = layout.systems.getOrNull(si) ?: continue
                        val x0 = if (si == a.system) a.x - space * 0.8f else sys.startX
                        val x1 = if (si == b.system) b.x + space * 2f else sys.endX
                        drawRoundRect(noteInk.copy(alpha = 0.13f), Offset(x0, sys.top + space * 0.9f), Size(x1 - x0, sys.bottom - sys.top - space * 1.4f), CornerRadius(space * 0.5f))
                        if (si == a.system) {
                            val shown = if (f.label.length > 48) f.label.take(46) + "…" else f.label
                            val label = tm.measure(shown, TextStyle(fontSize = (space * 1.2f / (this.density * fontScale)).sp, color = noteInk, fontWeight = FontWeight.Medium))
                            val x = place(si, x0, label.size.width + space * 0.8f)
                            val y = sys.top + space * 0.5f - label.size.height
                            drawRoundRect(noteInk.copy(alpha = 0.12f), Offset(x, y - space * 0.1f), Size(label.size.width + space * 0.8f, label.size.height + space * 0.2f), CornerRadius(space * 0.3f))
                            drawText(label, topLeft = Offset(x + space * 0.4f, y))
                        }
                    }
                }
                // The first note of a fragment being chosen.
                pickedStep?.let { st ->
                    layout.stepPos.getOrNull(st)?.let { pos ->
                        val sys = layout.systems[pos.system]
                        drawRoundRect(noteInk.copy(alpha = 0.35f), Offset(pos.x - space * 0.8f, sys.top + space * 0.9f), Size(space * 2.8f, sys.bottom - sys.top - space * 1.4f), CornerRadius(space * 0.5f))
                    }
                }

                // Captions under bars (chord symbols in Professor).
                for ((bar, text) in barCaptions) {
                    val box = layout.measureBoxes.getOrNull(bar) ?: continue
                    val sys = layout.systems.getOrNull(box.system) ?: continue
                    val label = tm.measure(text, TextStyle(fontSize = (space * 1.25f / (this.density * fontScale)).sp, color = accent, fontWeight = FontWeight.Medium))
                    drawText(label, topLeft = Offset(box.x1 + space * 0.5f, sys.bottom - label.size.height - space * 0.2f))
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

                // Recorded wrong keys (Doctor): red ghost noteheads beside their step, with how often.
                for ((gi, g) in ghosts.withIndex()) layout.stepPos.getOrNull(g.step)?.let { pos ->
                    val sys = layout.systems[pos.system]
                    val staffClef = if (g.midi >= 60) Clef.TREBLE else Clef.BASS
                    val bottom = sys.top + space * 7f + if (staffClef == Clef.BASS) space * 11f else 0f
                    val wp = spell(g.midi, score.key)
                    val gp = wp.staffPosition(staffClef)
                    fun gy(p: Int) = bottom - p * space / 2
                    // Several wrong keys at one step: fan them out to the right.
                    val nth = ghosts.subList(0, gi).count { it.step == g.step }
                    val x = pos.x + space * (1.6f + nth * 2.6f)
                    val color = WrongRed.copy(alpha = 0.85f * g.alpha)
                    val ledger = space * 0.16f
                    if (gp <= -2) for (lp in -2 downTo gp step 2) drawLine(color, Offset(x - space * 0.35f, gy(lp)), Offset(x + space * 1.53f, gy(lp)), ledger)
                    if (gp >= 10) for (lp in 10..gp step 2) drawLine(color, Offset(x - space * 0.35f, gy(lp)), Offset(x + space * 1.53f, gy(lp)), ledger)
                    glyph(tm, bravura, Smufl.HEAD_BLACK, x, gy(gp), space * 4f, color)
                    if (wp.alter != 0) glyph(tm, bravura, if (wp.alter > 0) Smufl.SHARP else Smufl.FLAT, x - space * 1.2f, gy(gp), space * 3.4f, color)
                    val label = tm.measure(g.label, TextStyle(fontSize = (space * 1.05f / (this.density * fontScale)).sp, color = color, fontWeight = FontWeight.Bold))
                    drawText(label, topLeft = Offset(x + space * 0.1f, gy(gp) - space * 2.3f))
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
