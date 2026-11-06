package com.musictrainer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictrainer.AppState
import com.musictrainer.model.Clef
import com.musictrainer.model.POINTS_CORRECT
import com.musictrainer.model.POINTS_WRONG
import com.musictrainer.model.SessionRecord
import com.musictrainer.model.accuracyPercent
import com.musictrainer.model.fullNoteName
import com.musictrainer.model.isBlackKey
import com.musictrainer.model.score
import com.musictrainer.session.Phase

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeScreen(state: AppState) {
    val s = state.session
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val keyboardMax = maxHeight * 0.6f
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (s.phase == Phase.FINISHED) s.settings.sessionLength else s.index + 1
            Text("Note $shown / ${s.settings.sessionLength}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${s.clef.label} clef · ${s.clef.other.label.lowercase()} in ${s.notesUntilClefChange}",
                style = MaterialTheme.typography.titleMedium,
            )
            RollingNumber(s.correct, "✓ ", CorrectGreen, 20.sp)
            RollingNumber(s.wrong, "✗ ", WrongRed, 20.sp)
            RollingNumber(score(s.correct, s.wrong), "Score ", MaterialTheme.colorScheme.onSurface, 16.sp, bold = false)
            OutlinedButton(onClick = { s.restart(state.settings) }) { Text("Restart session") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            StaffView(
                s, Modifier.fillMaxSize(), stripeOpacity = state.settings.stripeOpacity / 100f,
                style = state.settings.notationStyle.effective(s.sessionNumber),
            )
            // Results pop in over the staff with a springy scale + fade.
            androidx.compose.animation.AnimatedVisibility(
                visible = s.phase == Phase.FINISHED && s.lastRecord != null,
                enter = fadeIn(tween(250)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 300f), initialScale = 0.6f),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f),
            ) {
                s.lastRecord?.let { ResultsCard(it) { s.restart(state.settings) } }
            }
        }

        val target = s.note
        val message = when {
            s.phase == Phase.CORRECT -> "Correct!"
            s.phase == Phase.WRONG && target != null ->
                "Wrong: you played ${s.playedNote?.midi?.let(::fullNoteName)}, the note was ${fullNoteName(target.midi)}"
            s.phase == Phase.WAITING -> "Play the note shown on the ${if (s.clef == Clef.TREBLE) "treble" else "bass"} staff"
            else -> ""
        }
        val messageColor by animateColorAsState(
            when (s.phase) {
                Phase.CORRECT -> CorrectGreen
                Phase.WRONG -> WrongRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        AnimatedContent(
            targetState = message,
            transitionSpec = { (fadeIn(tween(200)) + slideInVertically { it / 2 }) togetherWith fadeOut(tween(150)) },
            modifier = Modifier.fillMaxWidth(),
        ) { text ->
            Text(
                text,
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                fontWeight = if (s.phase == Phase.WAITING) FontWeight.Normal else FontWeight.Bold,
                color = messageColor,
            )
        }

        // Pressed key flashes blue and fades back out.
        val flash = remember { Animatable(0f) }
        var flashedKey by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(state.pressCount) {
            if (state.pressCount == 0) return@LaunchedEffect
            flashedKey = state.pressedKey
            flash.snapTo(1f)
            flash.animateTo(0f, tween(450))
        }
        val highlights = buildMap<Int, Color> {
            flashedKey?.let { if (flash.value > 0f) put(it, lerp(Color.White, Color(0xFF8AB4F8), flash.value)) }
            if (target != null) when (s.phase) {
                Phase.CORRECT -> put(target.midi, CorrectGreen)
                Phase.WRONG -> {
                    s.playedNote?.let { put(it.midi, WrongRed) }
                    put(target.midi, CorrectGreen)
                }
                else -> Unit
            }
        }
        val ui = state.settings
        AnimatedVisibility(
            visible = ui.showVirtualKeyboard,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            VirtualKeyboard(
                low = ui.virtualRange.first,
                high = ui.virtualRange.last,
                highlights = highlights,
                onKey = state::onVirtualKey,
            onKeyUp = state::onVirtualKeyUp,
                savedHeightDp = state.settings.pianoHeightDp,
                showNames = ui.showKeyLabels,
                showOctaves = ui.showOctaveNumbers,
                maxTotal = keyboardMax,
                onHeightCommitted = { state.updateSettings(state.settings.copy(pianoHeightDp = it)) },
            )
        }
    }
    }
}

/**
 * On-screen keyboard with a drag handle on top to resize it (double-click / double-tap the
 * handle to go back to automatic size). When the keys would be too narrow to tap (e.g. a phone
 * in portrait), the range is split into two stacked rows: upper half on top, lower half below.
 */
@Composable
internal fun VirtualKeyboard(
    low: Int,
    high: Int,
    highlights: Map<Int, Color>,
    onKey: (Int) -> Unit,
    savedHeightDp: Int,
    showNames: Boolean,
    showOctaves: Boolean,
    maxTotal: Dp,
    onHeightCommitted: (Int) -> Unit,
    onKeyUp: (Int) -> Unit = {},
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val whiteKeys = (low..high).count { !isBlackKey(it) }
        val split = maxWidth / whiteKeys < 26.dp
        val gap = 6.dp
        val rowKeys = if (split) (whiteKeys + 1) / 2 else whiteKeys
        // Automatic: proportions close to a real keyboard, but never crowding out the staff.
        val autoRow = (maxWidth / rowKeys * 4.5f).coerceIn(90.dp, 220.dp)
        val minTotal = if (split) 140.dp else 70.dp
        val upper = maxOf(minTotal, maxTotal)
        val autoTotal = (if (split) autoRow * 2 + gap else autoRow).coerceIn(minTotal, upper)

        var dragHeight by remember { mutableStateOf<Dp?>(null) }
        val total = (dragHeight ?: if (savedHeightDp > 0) savedHeightDp.dp else autoTotal).coerceIn(minTotal, upper)

        Column(Modifier.fillMaxWidth()) {
            ResizeHandle(
                onDrag = { deltaPx ->
                    val delta = with(density) { deltaPx.toDp() }
                    dragHeight = ((dragHeight ?: total) - delta).coerceIn(minTotal, upper) // drag up = taller
                },
                onDragEnd = {
                    dragHeight?.let { onHeightCommitted(it.value.roundToInt()) }
                    dragHeight = null
                },
                onReset = { onHeightCommitted(0) },
            )
            if (!split) {
                Piano(low, high, highlights, onKey, Modifier.fillMaxWidth().height(total), onKeyUp, showNames, showOctaves)
            } else {
                // Split on the C closest to the middle of the range.
                val mid = (low + high) / 2
                val splitC = (((mid + 6) / 12) * 12).coerceIn(low + 1, high)
                val row = (total - gap) / 2
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    Piano(splitC, high, highlights, onKey, Modifier.fillMaxWidth().height(row), onKeyUp, showNames, showOctaves)
                    Piano(low, splitC - 1, highlights, onKey, Modifier.fillMaxWidth().height(row), onKeyUp, showNames, showOctaves)
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onReset: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val reset by rememberUpdatedState(onReset)
    Box(
        Modifier.fillMaxWidth()
            .height(22.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            // One detector for both drag-to-resize and double-tap-to-reset.
            .pointerInput(Unit) {
                var lastTapAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - change.previousPosition.y
                        moved += abs(dy)
                        if (!dragging && moved > viewConfiguration.touchSlop) dragging = true
                        if (dragging && dy != 0f) {
                            drag(dy)
                            change.consume()
                        }
                    }
                    if (dragging) {
                        end()
                    } else {
                        val now = down.uptimeMillis
                        if (now - lastTapAt < viewConfiguration.doubleTapTimeoutMillis) {
                            reset()
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 5.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        )
    }
}

@Composable
internal fun ResultsCard(record: SessionRecord, onRestart: () -> Unit) {
    // Score counts up from 0 when the card appears.
    val shownScore = remember(record) { Animatable(0f) }
    LaunchedEffect(record) { shownScore.animateTo(record.score.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Card(Modifier.widthIn(max = 460.dp).padding(8.dp)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Session complete", style = MaterialTheme.typography.headlineSmall)
            StarRow(record.stars, size = 40.dp, animate = true)
            Text("${shownScore.value.roundToInt()}", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("points", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("✓ ${record.correct} correct", color = CorrectGreen, fontWeight = FontWeight.Bold)
                Text("✗ ${record.wrong} wrong", color = WrongRed, fontWeight = FontWeight.Bold)
            }
            Text("Accuracy ${accuracyPercent(record.correct, record.wrong)}%", style = MaterialTheme.typography.titleLarge)
            Text("Average response ${formatSeconds(record.avgResponseMs)}")
            Text(
                "Score = $POINTS_CORRECT × correct − $POINTS_WRONG × wrong",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRestart) { Text("Start new session") }
        }
    }
}

fun formatSeconds(ms: Long): String {
    val tenths = (ms + 50) / 100
    return "${tenths / 10}.${tenths % 10} s"
}

/** A number that rolls up/down when it changes. */
@Composable
private fun RollingNumber(value: Int, prefix: String, color: Color, size: TextUnit, bold: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, fontSize = size)
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                    (slideOutVertically { if (up) -it else it } + fadeOut())
            },
        ) { v ->
            Text("$v", color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, fontSize = size)
        }
    }
}
