package com.musictrainer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictrainer.AppState
import com.musictrainer.model.NotationFont
import com.musictrainer.notation.TimeSignature
import com.musictrainer.rhythm.DictationSession
import com.musictrainer.rhythm.ImitationSession
import com.musictrainer.rhythm.PlaySession
import com.musictrainer.rhythm.RHYTHM_SIGNATURES
import com.musictrainer.rhythm.RhythmEvent
import com.musictrainer.rhythm.RhythmLevels
import com.musictrainer.rhythm.RhythmMode
import com.musictrainer.rhythm.RhythmRecord
import com.musictrainer.rhythm.RhythmSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- shared building blocks

/** Title, progress bar and info line of a running drill. */
@Composable
internal fun TrainerHeader(title: String, subtitle: String, progress: Float, info: String, onExit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit, modifier = Modifier.padding(end = 16.dp)) { Text("← Exit") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
            Text(info, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Ticking elapsed time since [since] (for "Time: 12.3 s"). */
@Composable
internal fun elapsedSeconds(since: Long, running: Boolean, now: () -> Long): String {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(since, running) {
        while (running) { t = now() - since; kotlinx.coroutines.delay(100) }
        t = now() - since
    }
    val s = t / 100
    return "${s / 10}.${s % 10} s"
}

/** Start page: pick a level (best stars shown) and see the main settings. */
@Composable
internal fun LevelPicker(
    title: String,
    intro: String,
    levels: List<Pair<String, String>>,
    current: Int,
    bestStars: (Int) -> Int,
    summary: String,
    onStart: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().dragToScroll(scroll).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(intro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            levels.forEachIndexed { i, (name, desc) ->
                val n = i + 1
                Card(
                    Modifier.fillMaxWidth().clickable { onStart(n) },
                    border = if (n == current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("$n", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Bold)
                            if (desc.isNotEmpty()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StarRow(bestStars(n), size = 16.dp)
                        Text("▶", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        ColumnScrollbar(scroll)
    }
}

/** End-of-session card. */
@Composable
internal fun TrainerResults(
    stars: Int, score: Int, lines: List<String>, hasNextLevel: Boolean,
    onAgain: () -> Unit, onNextLevel: () -> Unit, onExit: () -> Unit,
) {
    Card(Modifier.widthIn(max = 460.dp).padding(8.dp)) {
        Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Session complete", style = MaterialTheme.typography.headlineSmall)
            StarRow(stars, size = 40.dp, animate = true)
            val shown = remember(score) { Animatable(0f) }
            LaunchedEffect(score) { shown.animateTo(score.toFloat(), tween(900)) }
            Text("${shown.value.toInt()}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("points", style = MaterialTheme.typography.labelLarge)
            lines.forEach { Text(it, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onExit) { Text("Exit") }
                OutlinedButton(onClick = onAgain) { Text("Again") }
                if (hasNextLevel) Button(onClick = onNextLevel) { Text("Next level") }
            }
        }
    }
}

/** A simple history list of session rows. */
@Composable
internal fun TrainerHistory(title: String, rows: List<Pair<String, @Composable () -> Unit>>, onClear: (() -> Unit)?) {
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().dragToScroll(list), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (onClear != null && rows.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("Clear history") }
                }
            }
            if (rows.isEmpty()) item { Text("No sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.first }) { (_, content) -> Card(Modifier.fillMaxWidth()) { Box(Modifier.padding(12.dp)) { content() } } }
        }
        ListScrollbar(list)
    }
}

/** The circle as a progress bar for what happens by itself next: a ring fills up, "Replay in 1.2 s" in the middle. */
@Composable
internal fun PendingRing(p: com.musictrainer.rhythm.RhythmSession.Pending?, now: () -> Long, size: Dp) {
    if (p == null) return
    var t by remember(p) { mutableLongStateOf(now()) }
    LaunchedEffect(p) { while (p.pausedLeft == null) { t = now(); kotlinx.coroutines.delay(30) } }
    if (p.pausedLeft != null) t = p.at - p.pausedLeft
    val total = (p.at - p.startedAt).coerceAtLeast(1)
    val frac = ((t - p.startedAt).toFloat() / total).coerceIn(0f, 1f)
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val stroke = 6.dp.toPx()
        drawArc(color.copy(alpha = 0.15f), 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(color, -90f, 360f * frac, false, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
    val left = ((p.at - t).coerceAtLeast(0) + 99) / 100
    Text((if (p.pausedLeft != null) "Paused — " else "") + "${p.label} in ${left / 10}.${left % 10} s", style = MaterialTheme.typography.titleMedium, color = color)
}

/** ⏸ / ▶ in the middle of the circle: pauses the countdown or the playing rhythm, and continues. */
@Composable
internal fun PauseButton(s: com.musictrainer.rhythm.RhythmSession) {
    val p = s.pending
    val paused = p?.pausedLeft != null || s.pausedPlayback
    // Only for the countdown to an automatic replay / next question — never while the rhythm plays.
    val active = p != null && !s.playback.playing
    if (!active) return
    LineIcon(if (paused) LineIconKind.PLAY else LineIconKind.PAUSE, onClick = { if (paused) s.resume() else s.pause() })
}

enum class LineIconKind { PLAY, PAUSE }

/** Pause / play drawn with thin lines only (same look as the progress ring), in a thin circle. */
@Composable
internal fun LineIcon(kind: LineIconKind, onClick: () -> Unit, size: Dp = 60.dp) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
    ) {
        val stroke = 2.5.dp.toPx()
        val r = this.size.minDimension / 2 - stroke
        val c = center
        drawCircle(color.copy(alpha = 0.6f), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val line = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (kind) {
            LineIconKind.PAUSE -> {
                val h = r * 0.42f; val gap = r * 0.2f
                for (x in listOf(c.x - gap, c.x + gap)) drawLine(color, androidx.compose.ui.geometry.Offset(x, c.y - h), androidx.compose.ui.geometry.Offset(x, c.y + h), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            LineIconKind.PLAY -> {
                val h = r * 0.42f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - h * 0.6f, c.y - h); lineTo(c.x + h * 0.85f, c.y); lineTo(c.x - h * 0.6f, c.y + h); close()
                }
                drawPath(path, color, style = line)
            }
        }
    }
}

/** The count-in number, big, popping in on every beat (in the middle of the circle / pad). */
@Composable
internal fun CountIn(beat: Int?) {
    if (beat == null) return
    val pop = remember { Animatable(1f) }
    LaunchedEffect(beat) { pop.snapTo(1.35f); pop.animateTo(1f, tween(220)) }
    Text("$beat", fontSize = 110.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.scale(pop.value))
}

/** The big round area in the middle: a tap pad (imitation, play) or the frame for the value buttons. */
@Composable
internal fun TapPad(size: Dp, pulses: Int, enabled: Boolean, onTap: () -> Unit, content: @Composable () -> Unit) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulses) { if (pulses > 0) { pulse.snapTo(1f); pulse.animateTo(0f, tween(220)) } }
    val ring = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.size(size).scale(1f + 0.04f * pulse.value).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f + 0.18f * pulse.value))
            .border(2.dp, if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else ring, CircleShape)
            .pointerInput(enabled) {
                if (enabled) awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTap() }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Space / Enter tap the pad (desktop keyboards); [onDigit] gets 1–9. */
@Composable
internal fun Modifier.trainerKeys(onTap: () -> Unit, onDigit: (Int) -> Unit = {}): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.Spacebar, Key.Enter -> { onTap(); true }
            Key.One, Key.NumPad1 -> { onDigit(1); true }
            Key.Two, Key.NumPad2 -> { onDigit(2); true }
            Key.Three, Key.NumPad3 -> { onDigit(3); true }
            Key.Four, Key.NumPad4 -> { onDigit(4); true }
            Key.Five, Key.NumPad5 -> { onDigit(5); true }
            Key.Six, Key.NumPad6 -> { onDigit(6); true }
            Key.Seven, Key.NumPad7 -> { onDigit(7); true }
            Key.Eight, Key.NumPad8 -> { onDigit(8); true }
            Key.Nine, Key.NumPad9 -> { onDigit(9); true }
            else -> false
        }
    }
}

// ---------------------------------------------------------------- rhythm modes

private fun RhythmMode.intro() = when (this) {
    RhythmMode.DICTATION -> "Listen to the rhythm as often as you like, then write it down value by value. Each value you enter is final; when the bars are full you get the answer."
    RhythmMode.IMITATION -> "Listen to the rhythm, then tap it back on the pad (or any key / Space) after the count-in."
    RhythmMode.PLAY -> "Sight-read: after a one-bar count-in, tap each bar as it comes. The metronome keeps going and the staff moves on — no repeats."
}

private fun sigSummary(s: RhythmSettings) =
    "${s.signatures.joinToString(", ")} · ${s.bpm} BPM · " + (if (s.questions > 0) "${s.questions} ${if (s.bars > 1) "drills of ${s.bars} bars" else "drills"}" else "")

@Composable
fun RhythmPracticeScreen(state: AppState, mode: RhythmMode) {
    val settings = state.rhythmSettings[mode] ?: RhythmSettings()
    if (state.rhythmStarted[mode] != true) {
        val custom = com.musictrainer.rhythm.RhythmLevels.custom(settings.customValues)
        LevelPicker(
            title = mode.label,
            intro = mode.intro(),
            levels = RhythmLevels.all.map { it.name to it.description } + ("Custom" to "Your choice: ${custom.description} (change in Settings)"),
            current = if (settings.level == 0) RhythmLevels.all.size + 1 else settings.level,
            bestStars = { n ->
                val lv = if (n > RhythmLevels.all.size) 0 else n
                state.rhythmHistory.filter { it.mode == mode && it.level == lv && it.completed }.maxOfOrNull { it.stars } ?: 0
            },
            summary = if (mode == RhythmMode.PLAY) "${settings.signatures.joinToString(", ")} · ${settings.bpm} BPM · ${settings.questions} bars · change in Settings"
            else sigSummary(settings) + " · change in Settings",
            onStart = { n -> state.updateRhythmSettings(mode, settings.copy(level = if (n > RhythmLevels.all.size) 0 else n)); state.startRhythm(mode) },
        )
        return
    }
    when (mode) {
        RhythmMode.DICTATION -> DictationScreen(state, state.dictation)
        RhythmMode.IMITATION -> ImitationScreen(state, state.imitation)
        RhythmMode.PLAY -> RhythmPlayScreen(state, state.rhythmPlay)
    }
}

@Composable
private fun SessionDone(state: AppState, mode: RhythmMode, r: RhythmRecord?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val s = state.rhythmSettings[mode] ?: RhythmSettings()
        if (r == null) { Button(onClick = { state.leaveRhythm(mode) }) { Text("Back") }; return@Box }
        TrainerResults(
            stars = r.stars, score = r.score,
            lines = buildList {
                add("${r.notesRight} of ${r.notesTotal} notes right · ${r.accuracyPercent}%")
                add(if (mode == RhythmMode.PLAY) "${r.perfect} of ${r.questions} bars perfect" else "${r.perfect} of ${r.questions} drills perfect")
                if (mode != RhythmMode.DICTATION) add("Precision ${r.precisionMs} ms · ${r.extraTaps} extra taps")
                add("Time ${formatDuration(r.durationMs)}")
            },
            hasNextLevel = s.level in 1 until RhythmLevels.all.size,
            onAgain = { state.startRhythm(mode) },
            onNextLevel = { state.updateRhythmSettings(mode, s.copy(level = s.level + 1)); state.startRhythm(mode) },
            onExit = { state.leaveRhythm(mode) },
        )
    }
}

@Composable
private fun DictationScreen(state: AppState, d: DictationSession) {
    if (d.sessionDone) { SessionDone(state, RhythmMode.DICTATION, d.lastRecord); return }
    val drill = d.drill ?: return
    val palette = d.level.palette(drill.meter)
    val time = elapsedSeconds(d.startedAt, !d.sessionDone, state.platform.now)
    Column(
        Modifier.fillMaxSize().trainerKeys(onTap = d::listen, onDigit = { i -> palette.getOrNull(i - 1)?.let(d::write) }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrainerHeader(
            levelTitle(d.settings.level, d.level.name), "Rhythm dictation",
            progress = (d.question - if (d.result == null) 1 else 0).toFloat() / d.settings.questions,
            info = "Question ${d.question}/${d.settings.questions} | Correct ${d.perfect}/${d.question - if (d.result == null) 1 else 0} | Time $time",
            onExit = { state.leaveRhythm(RhythmMode.DICTATION) },
        )
        val result = d.result
        // One staff only. While writing: your values. After a mistake: the right rhythm, each value green if you
        // wrote it there, red if not — "Show what I wrote" switches the same staff to your answer.
        var mine by remember(d.question) { mutableStateOf(false) }
        val showAnswer = result != null && !result.allCorrect && !mine
        val events = if (showAnswer) drill.events else d.written.toList()
        val colors: Map<RhythmEvent, Color> = when {
            result == null -> emptyMap()
            showAnswer -> drill.events.associateWith { a ->
                if (d.written.any { w -> w.start == a.start && w.value.ticks == a.value.ticks && w.value.rest == a.value.rest }) CorrectGreen else WrongRed
            }
            else -> d.written.zip(result.correct).associate { (e, ok) -> e to if (ok) CorrectGreen else WrongRed }
        }
        RhythmStaff(
            drill.meter, events, Modifier.fillMaxWidth().widthIn(max = 1000.dp).height(120.dp).padding(horizontal = 12.dp),
            bars = drill.bars, colors = colors, cursorTick = if (result == null) d.position else null, anchor = true,
        )
        if (result != null && !result.allCorrect) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (mine) "What you wrote" else "The right rhythm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { mine = !mine }) { Text(if (mine) "Show the right rhythm" else "Show what I wrote") }
        }
        Text(
            when {
                result?.allCorrect == true -> "All correct!"
                result != null -> "${result.matched} of ${result.total} right"
                d.playback.countInBeat != null -> "Get ready…"
                d.playback.playing -> "Listen…"
                else -> "Write the rhythm: pick values (keys 1–${palette.size}, or MIDI white keys from C4). Space replays."
            },
            style = MaterialTheme.typography.titleLarge,
            color = when { result?.allCorrect == true -> CorrectGreen; result != null -> WrongRed; else -> MaterialTheme.colorScheme.onSurfaceVariant },
            modifier = Modifier.padding(8.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val circle = minOf(maxWidth, maxHeight) * 0.78f
            val btn = (circle * 0.24f).coerceIn(52.dp, 84.dp)
            val font = musicFont(NotationFont.BRAVURA)
            Box(Modifier.size(circle).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                CountIn(d.playback.countInBeat)
                PendingRing(d.pending, state.platform.now, circle)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 56.dp)) {
                    PauseButton(d)
                    if (d.pausedPlayback) Text("Paused", color = MaterialTheme.colorScheme.primary)
                }
            }
            palette.forEachIndexed { i, v ->
                val angle = -PI / 2 + i * 2 * PI / palette.size
                val r = circle / 2
                val enabled = d.canWrite(v)
                Box(
                    Modifier.offset(x = r * cos(angle).toFloat(), y = r * sin(angle).toFloat()).size(btn).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(enabled = enabled) { d.write(v) },
                    contentAlignment = Alignment.Center,
                ) {
                    val c = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    RhythmValueIcon(v, c, Modifier.size(btn * 0.7f))
                    if (v.triplet) Text("3", fontSize = 11.sp, color = c, modifier = Modifier.align(Alignment.TopStart).padding(start = btn * 0.2f, top = btn * 0.12f))
                    Text("${i + 1}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd).padding(end = btn * 0.18f, top = btn * 0.1f))
                    Text(com.musictrainer.whiteKeyName(i), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = btn * 0.06f))
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = d::listen) {
                Text(if (result?.allCorrect == false) "↻ Replay and try again" else if (d.written.isNotEmpty()) "↻ Start over" else if (d.playback.playing) "↻ Restart" else "↻ Replay")
            }
            Button(onClick = d::nextQuestion, enabled = result != null) { Text(if (d.question >= d.settings.questions) "Finish" else "Next ▶") }
        }
    }
}

/** The event under the playhead (for the highlight box). */
private fun eventAt(events: List<RhythmEvent>, tick: Int): RhythmEvent? = if (tick < 0) null else events.lastOrNull { it.start <= tick && tick < it.end }

private fun offsetColor(ms: Long?): Color = when {
    ms == null -> WrongRed
    abs(ms) <= 45 -> CorrectGreen
    else -> TimingAmber
}

@Composable
private fun ImitationScreen(state: AppState, s: ImitationSession) {
    if (s.sessionDone) { SessionDone(state, RhythmMode.IMITATION, s.lastRecord); return }
    val drill = s.drill ?: return
    val turn = s.phase == ImitationSession.Phase.YOUR_TURN
    Column(Modifier.fillMaxSize().trainerKeys(onTap = s::onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        val done = s.question - if (s.phase == ImitationSession.Phase.RESULT) 0 else 1
        TrainerHeader(
            levelTitle(s.settings.level, s.level.name), "Rhythm imitation",
            progress = done.toFloat() / s.settings.questions,
            info = "Question ${s.question}/${s.settings.questions} | Perfect ${s.perfect}/$done | Precision ${s.precisionMs} ms",
            onExit = { state.leaveRhythm(RhythmMode.IMITATION) },
        )
        val m = s.matcher
        val colors = buildMap {
            if (m != null) drill.notes.forEachIndexed { i, e ->
                if (i in m.offsets) put(e, offsetColor(m.offsets[i])) else if (i in m.missed) put(e, WrongRed)
            }
        }
        RhythmStaff(
            drill.meter, drill.events, Modifier.fillMaxWidth().widthIn(max = 1000.dp).height(120.dp).padding(horizontal = 12.dp),
            bars = drill.bars, colors = colors,
            // While listening the box follows the music; while tapping only if the setting allows it.
            highlight = if (turn && !s.settings.showPlayhead) null else eventAt(drill.events, s.playback.playTick),
        )
        val r = s.result
        Text(
            when {
                r != null && r.passed -> "Perfect! Precision ${r.precisionMs} ms"
                r != null -> "${r.hits}/${r.marks.size} notes" + (if (r.extraTaps > 0) " · ${r.extraTaps} extra taps" else "") + " · ${r.precisionMs} ms"
                s.playback.countInBeat != null -> if (turn) "Your turn — get ready…" else "Get ready…"
                turn -> "Tap!"
                else -> "Listen…"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = when { r?.passed == true -> CorrectGreen; r != null -> TimingAmber; turn -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
            modifier = Modifier.padding(8.dp),
        )
        if (r != null) Text(
            r.marks.joinToString("  ") { it.offsetMs?.let { o -> (if (o > 0) "+" else "") + "$o" } ?: "miss" } + "  (ms, + = late)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val pad = minOf(maxWidth, maxHeight) * 0.8f
            TapPad(pad, s.tapCount, enabled = turn, onTap = s::onTap) {
                PendingRing(s.pending, state.platform.now, pad)
                Box(Modifier.offset(y = 56.dp)) { if (s.phase != ImitationSession.Phase.YOUR_TURN) PauseButton(s) }
                if (s.playback.countInBeat != null) CountIn(s.playback.countInBeat)
                else Text(if (turn) "TAP" else "", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = s::listenThenTap, enabled = s.phase == ImitationSession.Phase.RESULT) { Text("↻ Replay") }
            Button(onClick = s::nextQuestion, enabled = s.phase == ImitationSession.Phase.RESULT) { Text(if (s.question >= s.settings.questions) "Finish" else "Next ▶") }
        }
    }
}

@Composable
private fun RhythmPlayScreen(state: AppState, s: PlaySession) {
    if (s.sessionDone) { SessionDone(state, RhythmMode.PLAY, s.lastRecord); return }
    val drill = s.drill ?: return
    Column(Modifier.fillMaxSize().trainerKeys(onTap = s::onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        val m = s.matcher
        TrainerHeader(
            levelTitle(s.settings.level, s.level.name), "Rhythm play",
            progress = s.bar.toFloat() / drill.bars,
            info = "Bar ${s.bar + 1}/${drill.bars} | Hits ${m?.offsets?.size ?: 0} | Missed ${m?.missed?.size ?: 0} | Extra ${m?.extra ?: 0}",
            onExit = { s.stop(); state.leaveRhythm(RhythmMode.PLAY) },
        )
        val colors = buildMap {
            if (m != null) drill.notes.forEachIndexed { i, e ->
                if (i in m.offsets) put(e, offsetColor(m.offsets[i])) else if (i in m.missed) put(e, WrongRed)
            }
        }
        // Current bar and the next one; the view moves on bar by bar.
        val shown = minOf(2, drill.bars)
        val first = minOf(s.bar, drill.bars - shown)
        RhythmStaff(
            drill.meter, drill.events, Modifier.fillMaxWidth().widthIn(max = 1000.dp).height(130.dp).padding(horizontal = 12.dp),
            firstBar = first, bars = shown, colors = colors,
            playTick = s.playback.playTick.takeIf { it >= 0 && s.settings.showPlayhead },
        )
        Text(
            if (s.playback.countInBeat != null) "Get ready…" else if (s.running) "Tap the rhythm" else "",
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            TapPad(minOf(maxWidth, maxHeight) * 0.8f, s.tapCount, enabled = s.running, onTap = s::onTap) {
                if (s.playback.countInBeat != null) CountIn(s.playback.countInBeat)
                else Text("TAP", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            }
        }
        Row(Modifier.padding(16.dp)) {
            OutlinedButton(onClick = { s.stop(); state.leaveRhythm(RhythmMode.PLAY) }) { Text("■ Stop") }
        }
    }
}

@Composable
fun RhythmHistoryScreen(state: AppState, mode: RhythmMode) {
    val rows = state.rhythmHistory.filter { it.mode == mode }.map { r ->
        "${r.timestamp}" to @Composable {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.platform.formatDate(r.timestamp), fontWeight = FontWeight.Medium)
                    StarRow(r.stars, size = 14.dp)
                    Text("Score ${r.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("${r.accuracyPercent}%", fontWeight = FontWeight.Bold)
                    if (!r.completed) Text("stopped early", color = WrongRed)
                }
                Text(
                    (if (r.level == 0) "Custom" else "Level ${r.level}") + " · ${r.signatures.joinToString(", ")} · ${r.bpm} BPM · ${r.perfect}/${r.questions} perfect · " +
                        "${r.notesRight}/${r.notesTotal} notes" + (if (mode != RhythmMode.DICTATION) " · ${r.precisionMs} ms" else "") + " · ${formatDuration(r.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    TrainerHistory("${mode.label} history", rows) { state.clearRhythmHistory(mode) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RhythmSettingsScreen(state: AppState, mode: RhythmMode) {
    val s = state.rhythmSettings[mode] ?: RhythmSettings()
    fun set(n: RhythmSettings) = state.updateRhythmSettings(mode, n)
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().dragToScroll(scroll).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GeneralSettings(state)
            Section("${mode.label} — drills") {
                Text("Time signatures (each drill picks one of the selected)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RHYTHM_SIGNATURES.forEach { sig ->
                        val key = "${sig.numerator}/${sig.denominator}"
                        val on = key in s.signatures
                        FilterChip(selected = on, onClick = {
                            val next = if (on) s.signatures - key else s.signatures + key
                            if (next.isNotEmpty()) set(s.copy(signatures = next.sortedBy { k -> RHYTHM_SIGNATURES.indexOfFirst { "${it.numerator}/${it.denominator}" == k } }))
                        }, label = { Text(key) })
                    }
                }
                Text("Level", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RhythmLevels.all.forEach { l -> FilterChip(selected = s.level == l.number, onClick = { set(s.copy(level = l.number)) }, label = { Text("${l.number}. ${l.name}") }) }
                    FilterChip(selected = s.level == 0, onClick = { set(s.copy(level = 0)) }, label = { Text("Custom") })
                }
                if (s.level == 0) CustomRhythmValues(s) { set(it) }
                NumberStepper("Tempo", s.bpm, "beats per minute", step = 5) { set(s.copy(bpm = it)) }
                if (mode == RhythmMode.PLAY) NumberStepper("Bars per run", s.questions, "", step = 1) { set(s.copy(questions = it)) }
                else {
                    NumberStepper("Questions per session", s.questions, "", step = 1) { set(s.copy(questions = it)) }
                    NumberStepper("Bars per drill", s.bars, "", step = 1) { set(s.copy(bars = it)) }
                }
                if (mode != RhythmMode.PLAY) SwitchRow("Metronome clicks while the rhythm plays", s.metronome) { set(s.copy(metronome = it)) }
                if (mode == RhythmMode.DICTATION) {
                    SwitchRow("Replay the rhythm automatically until I start writing", s.autoReplay) { set(s.copy(autoReplay = it)) }
                    if (s.autoReplay) {
                        NumberStepper("Pause before each replay", s.replayWaitTenths, "seconds", step = 5, format = { "${it / 10}.${it % 10}" },
                            parse = { t -> t.replace(',', '.').toDoubleOrNull()?.let { (it * 10).toInt() } }) { set(s.copy(replayWaitTenths = it)) }
                        NumberStepper("Replays at most", s.maxReplays, "times (0 = no limit)", step = 1) { set(s.copy(maxReplays = it)) }
                    }
                }
                if (mode != RhythmMode.PLAY) {
                    SwitchRow(if (mode == RhythmMode.DICTATION) "Go to the next question by itself after a correct answer" else "Go to the next question by itself after a perfect round", s.nextAfterSuccess) { set(s.copy(nextAfterSuccess = it)) }
                    if (s.nextAfterSuccess) NumberStepper("Wait before the next question", s.nextDelayTenths, "seconds", step = 5, format = { "${it / 10}.${it % 10}" },
                        parse = { t -> t.replace(',', '.').toDoubleOrNull()?.let { (it * 10).toInt() } }) { set(s.copy(nextDelayTenths = it)) }
                }
                NumberStepper("Metronome volume", s.metronomeVolume, "%  (0 = silent)", step = 5) { set(s.copy(metronomeVolume = it)) }
                Text("Metronome sound")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.musictrainer.rhythm.MetronomeSound.entries.forEach { m ->
                        FilterChip(selected = s.metronomeSound == m, onClick = { set(s.copy(metronomeSound = m)) }, label = { Text(m.label) })
                    }
                    OutlinedButton(onClick = {
                        val p = state.rhythmSession(mode).playback
                        p.clickVolume = s.metronomeVolume; p.clickSound = s.metronomeSound; p.accentDownbeat = s.accentDownbeat
                        p.preview(beats = s.signatureList.first().let { com.musictrainer.notation.Meter(it).let { m -> m.measureLength / m.beat } }, bpm = s.bpm)
                    }) { Text("▶ Try it") }
                }
                SwitchRow("Accent the first beat of each bar", s.accentDownbeat) { set(s.copy(accentDownbeat = it)) }
                if (mode != RhythmMode.DICTATION) SwitchRow(
                    "Show the playhead while I tap (a hint: it shows exactly when each note comes)", s.showPlayhead,
                ) { set(s.copy(showPlayhead = it)) }
                Text(
                    "Tempo counts the beat of the time signature: quarter notes in x/4, dotted quarters in 6/8, 9/8 and 12/8. " +
                        "Taps can come from the pad, Space or Enter, or any key on your MIDI keyboard.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ColumnScrollbar(scroll)
    }
}

private fun levelTitle(level: Int, name: String) = if (level == 0) "Custom level" else "Level $level · $name"

/** Custom level: pick which note values and rests may appear. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRhythmValues(s: RhythmSettings, onChange: (RhythmSettings) -> Unit) {
    val font = musicFont(NotationFont.BRAVURA)
    Text("Values in your custom level", fontWeight = FontWeight.Medium)
    for (rests in listOf(false, true)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.musictrainer.rhythm.RhythmLevels.customChoices.filter { it.second.rest == rests }.forEach { (id, v) ->
            val on = id in s.customValues
            FilterChip(
                selected = on,
                onClick = { onChange(s.copy(customValues = if (on) s.customValues - id else s.customValues + id)) },
                leadingIcon = { RhythmValueIcon(v, MaterialTheme.colorScheme.onSurface, Modifier.size(28.dp)) },
                label = { Text(v.label.removeSuffix(" note").replaceFirstChar { it.uppercase() }) },
            )
        }
    }
    // Every chosen time signature must be fillable with the chosen values.
    val level = com.musictrainer.rhythm.RhythmLevels.custom(s.customValues)
    val bad = s.signatureList.filter { !level.canFill(it) }
    if (s.customValues.isEmpty()) Text("Pick at least one value.", color = WrongRed)
    else if (bad.isNotEmpty()) Text(
        "These values can't fill a bar of ${bad.joinToString(", ") { "${it.numerator}/${it.denominator}" }} — add shorter values " +
            "(e.g. quarter or eighth notes). Until then those bars use level 1.",
        color = TimingAmber,
    )
    Text(
        "Values are combined only where they can be written correctly (e.g. a dotted quarter comes with an eighth; " +
            "triplet eighths come in threes; dotted quarters fill a beat of 6/8).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    @Suppress("UNUSED_VARIABLE") val unused = font
}
