package com.musictrainer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictrainer.AppState
import com.musictrainer.chords.ChordLevels
import com.musictrainer.chords.ChordSession
import com.musictrainer.chords.ChordSettings
import com.musictrainer.chords.EarLevels
import com.musictrainer.chords.EarPlayStyle
import com.musictrainer.chords.EarRecord
import com.musictrainer.chords.IntervalLevels
import com.musictrainer.chords.PitchLevels
import com.musictrainer.chords.Progressions
import com.musictrainer.model.NotationFont
import com.musictrainer.model.fullNoteName
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------- chord finder

@Composable
fun ChordFinderScreen(state: AppState) {
    val s = state.chordSettings
    if (!state.chordStarted) {
        LevelPicker(
            "Chord finder",
            "A chord symbol appears; play it on your MIDI keyboard or the on-screen keyboard. " +
                (if (s.rootInBass) "The root must be the lowest note." else "Any inversion or voicing counts.") +
                " Wrong keys count as mistakes; the score comes at the end.",
            ChordLevels.all.map { it.name to it.qualities.joinToString(", ") { q -> q.longName } },
            s.level,
            bestStars = { n -> state.chordHistory.filter { it.level == n && it.completed }.maxOfOrNull { it.stars } ?: 0 },
            summary = "${s.questions} chords · change in Settings",
            onStart = { n -> state.updateChordSettings(s.copy(level = n)); state.startChords() },
        )
        return
    }
    val c = state.chords
    if (c.phase == ChordSession.Phase.DONE) {
        val r = c.lastRecord
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (r != null) TrainerResults(
                r.stars, r.score,
                listOf(
                    "${r.firstTry} of ${r.chords} found at the first try · ${r.accuracyPercent}%",
                    "${r.wrongKeys} wrong keys · ${r.skipped} shown",
                    "Time ${formatDuration(r.durationMs)} · ${((r.secondsPerChord * 10).roundToInt() / 10.0)} s per chord",
                ),
                hasNextLevel = c.settings.level < ChordLevels.all.size,
                onAgain = state::startChords,
                onNextLevel = { state.updateChordSettings(s.copy(level = c.settings.level + 1)); state.startChords() },
                onExit = state::leaveChords,
            )
        }
        return
    }
    val chord = c.chord ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keyboardMax = maxHeight * 0.42f
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val done = c.results.size
            TrainerHeader(
                "Level ${c.settings.level} · ${c.level.name}", "Chord finder", done.toFloat() / c.settings.questions,
                "Chord ${c.index}/${c.settings.questions} | First try ${c.firstTry}/$done | Wrong keys ${c.wrongKeys}",
                onExit = state::leaveChords,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val color = when (c.phase) {
                        ChordSession.Phase.CORRECT -> CorrectGreen
                        ChordSession.Phase.REVEALED -> TimingAmber
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(chord.symbol, fontSize = 88.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(chord.quality.longName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when (c.phase) {
                            ChordSession.Phase.CORRECT -> (if (c.wrongHere == 0) "Right! " else "Got it. ") + chord.noteNames.joinToString(" – ")
                            ChordSession.Phase.REVEALED -> "It's " + chord.noteNames.joinToString(" – ")
                            else -> c.lastWrong?.let { "Not in the chord: ${fullNoteName(it)}" } ?: "Play the chord"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (c.phase == ChordSession.Phase.ASK && c.lastWrong != null) WrongRed else color,
                    )
                    if (c.held.isNotEmpty()) Text("Holding: " + c.held.sorted().joinToString(" ") { fullNoteName(it) }, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = c::reveal, enabled = c.phase == ChordSession.Phase.ASK) { Text("Show me") }
                }
            }
            // A new chord: let go of the keys clicked down for the last one.
            LaunchedEffect(c.index) { state.releaseLatched() }
            // Keyboard: held keys blue, a wrong key flashes red, the answer shows in green.
            val flash = remember { Animatable(0f) }
            LaunchedEffect(c.wrongCount) { if (c.wrongCount > 0) { flash.snapTo(1f); flash.animateTo(0f, tween(700)) } }
            val highlights = buildMap<Int, Color> {
                if (c.phase != ChordSession.Phase.ASK) {
                    val bass = 48 + chord.root.pc
                    chord.quality.intervals.forEach { put(bass + it + if (bass + it < 53) 12 else 0, lerp(Color.White, CorrectGreen, 0.55f)) }
                }
                c.held.forEach { put(it, lerp(Color.White, Color(0xFF8AB4F8), 0.7f)) }
                // The red flash fades back to the key's own colour (blue while held).
                c.lastWrong?.let { if (flash.value > 0f) put(it, lerp(get(it) ?: Color.White, WrongRed, flash.value)) }
            }
            val ui = state.settings
            if (ui.showVirtualKeyboard) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "On-screen keyboard: click each note of the chord — keys stay down until you click them again. " +
                        "On a MIDI keyboard, just play the chord.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.latchedKeys.isNotEmpty()) OutlinedButton(onClick = state::releaseLatched) { Text("Release keys") }
            }
            if (ui.showVirtualKeyboard) VirtualKeyboard(
                low = if (ui.virtualKeys == 0) 48 else ui.virtualRange.first,
                high = if (ui.virtualKeys == 0) 83 else ui.virtualRange.last,
                // Clicks latch keys (see toggleLatchedKey); releasing the mouse does nothing.
                highlights = highlights, onKey = state::toggleLatchedKey, onKeyUp = {},
                savedHeightDp = ui.pianoHeightDp, showNames = ui.showKeyLabels, showOctaves = ui.showOctaveNumbers,
                maxTotal = keyboardMax, onHeightCommitted = { state.updateSettings(state.settings.copy(pianoHeightDp = it)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordFinderSettingsScreen(state: AppState) {
    val s = state.chordSettings
    fun set(n: ChordSettings) = state.updateChordSettings(n)
    TrainerSettingsPage(state) {
        Section("Chord finder") {
            LevelChips(ChordLevels.all.map { it.name }, s.level) { set(s.copy(level = it)) }
            NumberStepper("Chords per session", s.questions, "", step = 1) { set(s.copy(questions = it)) }
            SwitchRow("The root must be the lowest note", s.rootInBass) { set(s.copy(rootInBass = it)) }
            SwitchRow("Four- and five-note chords also count without their fifth", s.allowOmitFifth) { set(s.copy(allowOmitFifth = it)) }
        }
    }
}

@Composable
fun ChordFinderHistoryScreen(state: AppState) {
    TrainerHistory("Chord finder history", state.chordHistory.map { r ->
        "${r.timestamp}" to @Composable {
            HistoryLine(state, r.timestamp, r.stars, r.score, r.accuracyPercent, r.completed,
                "Level ${r.level} · ${r.firstTry}/${r.chords} first try · ${r.wrongKeys} wrong keys · ${r.skipped} shown · ${formatDuration(r.durationMs)}")
        }
    }, state::clearChordHistory)
}

// ---------------------------------------------------------------- shared quiz / settings pieces

@Composable
private fun HistoryLine(state: AppState, ts: Long, stars: Int, score: Int, accuracy: Int, completed: Boolean, detail: String) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.platform.formatDate(ts), fontWeight = FontWeight.Medium)
            StarRow(stars, size = 14.dp)
            Text("Score $score", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("$accuracy%", fontWeight = FontWeight.Bold)
            if (!completed) Text("stopped early", color = WrongRed)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrainerSettingsPage(state: AppState, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().dragToScroll(scroll).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GeneralSettings(state)
            content()
        }
        ColumnScrollbar(scroll)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelChips(names: List<String>, level: Int, onSelect: (Int) -> Unit) {
    Text("Level", fontWeight = FontWeight.Medium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n -> FilterChip(selected = level == i + 1, onClick = { onSelect(i + 1) }, label = { Text("${i + 1}. $n") }) }
    }
}

@Composable
private fun WeakFocus(on: Boolean, onChange: (Boolean) -> Unit, onReset: () -> Unit, what: String) {
    SwitchRow("Focus on the $what I get wrong most (off = truly random)", on, onChange)
    OutlinedButton(onClick = onReset) { Text("Forget my mistakes") }
}

/** Generic "listen and choose" quiz used by chord, progression and interval identification. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EarQuiz(
    title: String, subtitle: String, index: Int, questions: Int, correct: Int, answered: Int,
    asking: Boolean, right: Boolean?, prompt: String, detail: String?, playing: String? = null,
    options: List<Pair<String, String>>, targetId: String?, answerId: String?,
    onChoose: (String) -> Unit, onReplay: () -> Unit, onPlayAnswer: () -> Unit, onNext: () -> Unit, onExit: () -> Unit,
    extraButtons: @Composable () -> Unit = {},
    /** Small key name under each option (MIDI answers), or null. */
    keyHint: ((Int) -> String)? = null,
    midiHint: String? = null,
) {
    Column(Modifier.fillMaxSize().trainerKeys(onTap = onReplay), horizontalAlignment = Alignment.CenterHorizontally) {
        TrainerHeader(title, subtitle, answered.toFloat() / questions, "Question $index/$questions | Correct $correct/$answered", onExit)
        val scroll = rememberScrollState()
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp).widthIn(max = 900.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (right) { true -> "Right!"; false -> "Not quite"; null -> prompt },
                style = MaterialTheme.typography.headlineSmall,
                color = when (right) { true -> CorrectGreen; false -> WrongRed; null -> MaterialTheme.colorScheme.onSurfaceVariant },
            )
            detail?.let { Text(it, textAlign = TextAlign.Center) }
            // After answering, still say what is sounding (the correction replays the chord).
            if (right != null && playing != null) Text("Playing: $playing…", color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEachIndexed { index, (id, label) ->
                    val isTarget = !asking && id == targetId
                    val isWrongPick = !asking && id == answerId && id != targetId
                    val colors = when {
                        isTarget -> ButtonDefaults.buttonColors(containerColor = CorrectGreen, contentColor = Color.White)
                        isWrongPick -> ButtonDefaults.buttonColors(containerColor = WrongRed, contentColor = Color.White)
                        else -> ButtonDefaults.outlinedButtonColors()
                    }
                    val content: @Composable () -> Unit = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, fontSize = 18.sp)
                            keyHint?.let { Text(it(index), fontSize = 10.sp) }
                        }
                    }
                    if (isTarget || isWrongPick) Button(onClick = {}, colors = colors, modifier = Modifier.height(60.dp)) { content() }
                    else OutlinedButton(onClick = { onChoose(id) }, enabled = asking, modifier = Modifier.height(60.dp)) { content() }
                }
            }
            midiHint?.let { Text("🎹 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = onReplay) { Text("↻ Replay") }
            extraButtons()
            if (right == false) OutlinedButton(onClick = onPlayAnswer) { Text("Hear my answer") }
            Button(onClick = onNext, enabled = !asking) { Text(if (index >= questions) "Finish" else "Next ▶") }
        }
    }
}

@Composable
private fun EarDone(r: EarRecord?, names: (String) -> String, hasNext: Boolean, onAgain: () -> Unit, onNextLevel: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (r == null) { Button(onClick = onExit) { Text("Back") }; return@Box }
        TrainerResults(
            r.stars, r.score,
            buildList {
                add("${r.correct} of ${r.questions} right · ${r.accuracyPercent}%")
                if (r.missed.isNotEmpty()) add("Missed: " + r.missed.entries.sortedByDescending { it.value }.joinToString(", ") { "${names(it.key)} ×${it.value}" })
                add("Time ${formatDuration(r.durationMs)}")
            },
            hasNext, onAgain, onNextLevel, onExit,
        )
    }
}

@Composable
private fun EarHistory(state: AppState, title: String, list: List<EarRecord>, names: (String) -> String, onClear: () -> Unit) {
    TrainerHistory(title, list.map { r ->
        "${r.timestamp}" to @Composable {
            HistoryLine(state, r.timestamp, r.stars, r.score, r.accuracyPercent, r.completed,
                "Level ${r.level} · ${r.correct}/${r.questions} right" +
                    (if (r.missed.isNotEmpty()) " · missed " + r.missed.keys.joinToString(", ") { names(it) } else "") + " · ${formatDuration(r.durationMs)}")
        }
    }, onClear)
}

// ---------------------------------------------------------------- chord identification

private fun qualityName(id: String) = runCatching { com.musictrainer.chords.ChordQuality.valueOf(id).longName }.getOrDefault(id)

@Composable
fun ChordEarScreen(state: AppState) {
    val s = state.earSettings
    if (!state.earStarted) {
        LevelPicker(
            "Chord identification", "The app plays a chord; choose its type. Higher levels add more chord types (and inversions) to choose from.",
            EarLevels.all.map { l -> l.name to "${l.qualities.size} options" + if (l.inversions) " · inversions" else "" }, s.level,
            bestStars = { n -> state.earHistory.filter { it.level == n && it.completed }.maxOfOrNull { it.stars } ?: 0 },
            summary = "${s.questions} chords · ${s.playStyle.label} · " + (if (s.focusWeak) "focus on weak chords" else "random") + " · change in Settings",
            onStart = { n -> state.updateEarSettings(s.copy(level = n)); state.startEar() },
        )
        return
    }
    val e = state.ear
    if (e.phase == com.musictrainer.chords.EarSession.Phase.DONE) {
        EarDone(e.lastRecord, ::qualityName, e.settings.level < EarLevels.all.size, state::startEar,
            { state.updateEarSettings(s.copy(level = e.settings.level + 1)); state.startEar() }, state::leaveEar)
        return
    }
    val q = e.quality ?: return
    val asking = e.phase == com.musictrainer.chords.EarSession.Phase.ASK
    EarQuiz(
        "Level ${e.settings.level} · ${e.level.name}", "Chord identification", e.index, e.settings.questions, e.correct, e.results.size,
        asking, if (asking) null else e.phase == com.musictrainer.chords.EarSession.Phase.RIGHT,
        prompt = e.playingPart?.let { "Playing: $it…" } ?: "Which chord is it?",
        playing = e.playingPart,
        detail = if (asking) null else "It was ${q.longName}: " + com.musictrainer.chords.Chord(com.musictrainer.chords.preferredRoot(e.root), q).let { ch ->
            "${ch.symbol} = " + ch.noteNames.joinToString(" ")
        } +
            (if (e.phase == com.musictrainer.chords.EarSession.Phase.WRONG) " (you chose ${e.answer?.longName})" else ""),
        options = e.level.qualities.map { it.name to it.longName },
        targetId = q.name, answerId = e.answer?.name,
        onChoose = { id -> e.choose(com.musictrainer.chords.ChordQuality.valueOf(id)) },
        onReplay = e::replay, onPlayAnswer = e::playAnswer, onNext = e::next, onExit = state::leaveEar,
        midiHint = "MIDI: play any chord of that type (any root or inversion) to answer.",
        extraButtons = {
            OutlinedButton(onClick = { e.playAs(EarPlayStyle.ARPEGGIO) }) { Text("▶ Broken") }
            OutlinedButton(onClick = { e.playAs(EarPlayStyle.TOGETHER) }) { Text("▶ Block") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordEarSettingsScreen(state: AppState) {
    val s = state.earSettings
    TrainerSettingsPage(state) {
        Section("Chord identification") {
            LevelChips(EarLevels.all.map { it.name }, s.level) { state.updateEarSettings(s.copy(level = it)) }
            NumberStepper("Chords per session", s.questions, "", step = 1) { state.updateEarSettings(s.copy(questions = it)) }
            Text("How chords are played")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EarPlayStyle.entries.forEach { p -> FilterChip(selected = s.playStyle == p, onClick = { state.updateEarSettings(s.copy(playStyle = p)) }, label = { Text(p.label) }) }
            }
            WeakFocus(s.focusWeak, { state.updateEarSettings(s.copy(focusWeak = it)) }, state::resetEarStats, "chord types")
        }
    }
}

@Composable
fun ChordEarHistoryScreen(state: AppState) = EarHistory(state, "Chord identification history", state.earHistory, ::qualityName, state::clearEarHistory)

// ---------------------------------------------------------------- progression identification

private fun progressionName(id: String) = id.replace("-", " – ")

@Composable
fun ProgressionScreen(state: AppState) {
    val s = state.progSettings
    if (!state.progStarted) {
        LevelPicker(
            "Progression identification", "The app plays a chord progression on the piano (bass + right-hand chords, random key); choose which one it is.",
            Progressions.levels.map { l -> l.name to "${l.progressions.size} options" }, s.level,
            bestStars = { n -> state.progHistory.filter { it.level == n && it.completed }.maxOfOrNull { it.stars } ?: 0 },
            summary = "${s.questions} progressions · ${s.tempo} chords per minute · " + (if (s.focusWeak) "focus on weak ones" else "random") + " · change in Settings",
            onStart = { n -> state.updateProgSettings(s.copy(level = n)); state.startProgressions() },
        )
        return
    }
    val p = state.progressions
    if (p.phase == com.musictrainer.chords.ProgressionSession.Phase.DONE) {
        EarDone(p.lastRecord, ::progressionName, p.settings.level < Progressions.levels.size, state::startProgressions,
            { state.updateProgSettings(s.copy(level = p.settings.level + 1)); state.startProgressions() }, state::leaveProgressions)
        return
    }
    val prog = p.progression ?: return
    val asking = p.phase == com.musictrainer.chords.ProgressionSession.Phase.ASK
    val keyName = com.musictrainer.model.SpelledNote.spell(p.tonic, preferFlat = true).name.filter { !it.isDigit() && it != '-' } + if (prog.minor) " minor" else " major"
    EarQuiz(
        "Level ${p.settings.level} · ${p.level.name}", "Progression identification", p.index, p.settings.questions, p.correct, p.results.size,
        asking, if (asking) null else p.phase == com.musictrainer.chords.ProgressionSession.Phase.RIGHT,
        prompt = if (p.playingChord >= 0) "Listening… chord ${p.playingChord + 1}" else "Which progression is it?",
        detail = if (asking) null else "It was ${prog.label} in $keyName" +
            (if (p.phase == com.musictrainer.chords.ProgressionSession.Phase.WRONG) " (you chose ${p.answer?.label})" else ""),
        options = p.level.progressions.map { it.id to it.label },
        targetId = prog.id, answerId = p.answer?.id,
        onChoose = { id -> p.level.progressions.firstOrNull { it.id == id }?.let(p::choose) },
        onReplay = p::replay, onPlayAnswer = p::playAnswer, onNext = p::next, onExit = state::leaveProgressions,
        keyHint = { i -> com.musictrainer.whiteKeyName(i) },
        midiHint = "MIDI: the white keys from middle C choose the answers (C4 = first, D4 = second …).",
    )
}

@Composable
fun ProgressionSettingsScreen(state: AppState) {
    val s = state.progSettings
    TrainerSettingsPage(state) {
        Section("Progression identification") {
            LevelChips(Progressions.levels.map { it.name }, s.level) { state.updateProgSettings(s.copy(level = it)) }
            NumberStepper("Progressions per session", s.questions, "", step = 1) { state.updateProgSettings(s.copy(questions = it)) }
            NumberStepper("Tempo", s.tempo, "chords per minute", step = 5) { state.updateProgSettings(s.copy(tempo = it)) }
            WeakFocus(s.focusWeak, { state.updateProgSettings(s.copy(focusWeak = it)) }, state::resetProgStats, "progressions")
        }
    }
}

@Composable
fun ProgressionHistoryScreen(state: AppState) = EarHistory(state, "Progression identification history", state.progHistory, ::progressionName, state::clearProgHistory)

// ---------------------------------------------------------------- relative pitch (intervals)

private fun intervalName(id: String) = runCatching { com.musictrainer.chords.Interval.valueOf(id).longName }.getOrDefault(id)

@Composable
fun IntervalScreen(state: AppState) {
    val s = state.intervalSettings
    if (!state.intervalStarted) {
        LevelPicker(
            "Relative pitch", "Two notes sound; name the interval between them.",
            IntervalLevels.all.map { l -> l.name to "${l.intervals.size} options · " + l.directions.joinToString(", ") { it.label.lowercase() } }, s.level,
            bestStars = { n -> state.intervalHistory.filter { it.level == n && it.completed }.maxOfOrNull { it.stars } ?: 0 },
            summary = "${s.questions} intervals · " + (if (s.focusWeak) "focus on weak intervals" else "random") + " · change in Settings",
            onStart = { n -> state.updateIntervalSettings(s.copy(level = n)); state.startIntervals() },
        )
        return
    }
    val iv = state.intervals
    if (iv.phase == com.musictrainer.chords.IntervalSession.Phase.DONE) {
        EarDone(iv.lastRecord, ::intervalName, iv.settings.level < IntervalLevels.all.size, state::startIntervals,
            { state.updateIntervalSettings(s.copy(level = iv.settings.level + 1)); state.startIntervals() }, state::leaveIntervals)
        return
    }
    val t = iv.interval ?: return
    val asking = iv.phase == com.musictrainer.chords.IntervalSession.Phase.ASK
    EarQuiz(
        "Level ${iv.settings.level} · ${iv.level.name}", "Relative pitch", iv.index, iv.settings.questions, iv.correct, iv.results.size,
        asking, if (asking) null else iv.phase == com.musictrainer.chords.IntervalSession.Phase.RIGHT,
        prompt = "Which interval? (${iv.direction.label.lowercase()})",
        detail = if (asking) null else "It was a ${t.longName.lowercase()}: ${fullNoteName(iv.low)} – ${fullNoteName(iv.low + t.semitones)}" +
            (if (iv.phase == com.musictrainer.chords.IntervalSession.Phase.WRONG) " (you chose ${iv.answer?.longName?.lowercase()})" else ""),
        options = iv.level.intervals.map { it.name to it.longName },
        targetId = t.name, answerId = iv.answer?.name,
        onChoose = { id -> iv.choose(com.musictrainer.chords.Interval.valueOf(id)) },
        onReplay = iv::replay, onPlayAnswer = iv::playAnswer, onNext = iv::next, onExit = state::leaveIntervals,
        keyHint = { i -> fullNoteName(60 + iv.level.intervals[i].semitones).substringBefore(" /") },
        midiHint = "MIDI: play the interval up from middle C — C4 is unison, E4 a major 3rd, C5 an octave.",
    )
}

@Composable
fun IntervalSettingsScreen(state: AppState) {
    val s = state.intervalSettings
    TrainerSettingsPage(state) {
        Section("Relative pitch") {
            LevelChips(IntervalLevels.all.map { it.name }, s.level) { state.updateIntervalSettings(s.copy(level = it)) }
            NumberStepper("Intervals per session", s.questions, "", step = 1) { state.updateIntervalSettings(s.copy(questions = it)) }
            WeakFocus(s.focusWeak, { state.updateIntervalSettings(s.copy(focusWeak = it)) }, state::resetIntervalStats, "intervals")
        }
    }
}

@Composable
fun IntervalHistoryScreen(state: AppState) = EarHistory(state, "Relative pitch history", state.intervalHistory, ::intervalName, state::clearIntervalHistory)

// ---------------------------------------------------------------- perfect pitch

private fun pitchName(id: String) = id.toIntOrNull()?.let { fullNoteName(it) } ?: id

private val LETTER_PCS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/** MIDI of the natural note at diatonic index [d] (octave × 7 + letter). */
private fun naturalMidi(d: Int) = (d.floorDiv(7) + 1) * 12 + LETTER_PCS[d.mod(7)]

@Composable
fun PitchScreen(state: AppState) {
    val s = state.pitchSettings
    if (!state.pitchStarted) {
        LevelPicker(
            "Perfect pitch", "A note sounds, with no reference; find it on the staff. Level 1 asks only for the octave; then come middle C and single notes around it, adding more notes and octaves level by level.",
            PitchLevels.all.map { it.name to it.description }, s.level,
            bestStars = { n -> state.pitchHistory.filter { it.level == n && it.completed }.maxOfOrNull { it.stars } ?: 0 },
            summary = "${s.questions} notes · " + (if (s.focusWeak) "focus on weak notes" else "random") + " · change in Settings",
            onStart = { n -> state.updatePitchSettings(s.copy(level = n)); state.startPitch() },
        )
        return
    }
    val p = state.pitch
    if (p.phase == com.musictrainer.chords.PitchSession.Phase.DONE) {
        EarDone(p.lastRecord, ::pitchName, p.settings.level < PitchLevels.all.size, state::startPitch,
            { state.updatePitchSettings(s.copy(level = p.settings.level + 1)); state.startPitch() }, state::leavePitch)
        return
    }
    val target = p.target ?: return
    val asking = p.phase == com.musictrainer.chords.PitchSession.Phase.ASK
    var alter by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().trainerKeys(onTap = p::replay), horizontalAlignment = Alignment.CenterHorizontally) {
        TrainerHeader(
            "Level ${p.settings.level} · ${p.level.name}", "Perfect pitch", p.results.size.toFloat() / p.settings.questions,
            "Note ${p.index}/${p.settings.questions} | Correct ${p.correct}/${p.results.size}", state::leavePitch,
        )
        Text(
            when {
                asking -> if (p.level.octaveOnly) "Which octave was it? Click anywhere in it on the staff." else "Which note was it? Click it on the staff."
                p.level.octaveOnly && p.phase == com.musictrainer.chords.PitchSession.Phase.RIGHT ->
                    "Right! Octave ${com.musictrainer.chords.octaveOf(target)} (it was ${fullNoteName(target)})"
                p.level.octaveOnly -> "It was octave ${com.musictrainer.chords.octaveOf(target)} (${fullNoteName(target)}) — you chose octave ${p.answer?.let { com.musictrainer.chords.octaveOf(it) }}"
                p.phase == com.musictrainer.chords.PitchSession.Phase.RIGHT -> "Right! ${fullNoteName(target)}"
                else -> "It was ${fullNoteName(target)} — you chose ${p.answer?.let { fullNoteName(it) }}"
            },
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp),
            color = when { asking -> MaterialTheme.colorScheme.onSurfaceVariant; p.phase == com.musictrainer.chords.PitchSession.Phase.RIGHT -> CorrectGreen; else -> WrongRed },
        )
        if (p.level.accidentals) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Accidental:")
            listOf(0 to "♮ none", 1 to "♯ sharp", -1 to "♭ flat").forEach { (a, l) -> FilterChip(selected = alter == a, onClick = { alter = a }, label = { Text(l) }) }
        }
        Text(
            if (p.level.octaveOnly) "🎹 MIDI: play any key in that octave" else "🎹 MIDI: play the note on your keyboard",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PitchStaff(
            Modifier.weight(1f).fillMaxWidth().widthIn(max = 700.dp).padding(16.dp),
            alter = if (p.level.accidentals) alter else 0,
            enabled = asking,
            octaveBands = p.level.octaveOnly,
            shown = if (asking) emptyList() else listOfNotNull(target to CorrectGreen, p.answer?.takeIf { it != target }?.let { it to WrongRed }),
            onPick = { midi -> p.choose(midi); alter = 0 },
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = p::replay) { Text("↻ Replay") }
            if (p.phase == com.musictrainer.chords.PitchSession.Phase.WRONG) OutlinedButton(onClick = p::playAnswer) { Text("Hear my answer") }
            Button(onClick = p::next, enabled = !asking) { Text(if (p.index >= p.settings.questions) "Finish" else "Next ▶") }
        }
    }
}

/** A clickable grand staff (C2 … C6): hovering shows where the note would go, a click answers. */
@Composable
private fun PitchStaff(modifier: Modifier, alter: Int, enabled: Boolean, shown: List<Pair<Int, Color>>, octaveBands: Boolean = false, onPick: (Int) -> Unit) {
    val tm = rememberTextMeasurer()
    val font = musicFont(NotationFont.BRAVURA)
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surfaceContainerHigh
    var hover by remember { mutableStateOf<Int?>(null) }
    val lowD = 14 // C2
    val highD = 42 // C6
    Canvas(
        modifier.pointerInput(enabled, alter) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val pos = e.changes.first().position
                    val half = size.height / (highD - lowD + 4f)
                    val d = (((size.height - half * 2) - pos.y) / half).roundToInt() + lowD
                    hover = if (enabled && d in lowD..highD) d else null
                    if (e.type == PointerEventType.Exit) hover = null
                    if (enabled && e.type == PointerEventType.Press && d in lowD..highD) {
                        onPick(naturalMidi(d) + alter)
                        e.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        val half = size.height / (highD - lowD + 4f)
        val sp = half * 2
        fun y(d: Int) = (size.height - half * 2) - (d - lowD) * half
        val left = sp
        val right = size.width - sp
        val lw = max(1f, sp * 0.1f)
        // Octave bands (octave identification): C to B of each octave, shaded alternately and labelled.
        if (octaveBands) for (oct in 2..5) {
            val top = y(oct * 7 + 6) - half / 2
            val bottom = y(oct * 7) + half / 2
            drawRect(accent.copy(alpha = if (oct % 2 == 0) 0.10f else 0.04f), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top))
        }
        // Treble E4–F5 (diatonic 30–38) and bass G2–A3 (18–26).
        for (d in listOf(18, 20, 22, 24, 26, 30, 32, 34, 36, 38)) drawLine(ink, Offset(left, y(d)), Offset(right, y(d)), lw)
        drawLine(ink, Offset(left, y(18)), Offset(left, y(38)), lw)
        fun glyph(code: String, x: Float, base: Float, color: Color) {
            val layout = tm.measure(code, TextStyle(fontFamily = font, fontSize = (sp * 4 / (density * fontScale)).sp, color = color))
            drawText(layout, topLeft = Offset(x, base - layout.firstBaseline))
        }
        // Octave labels on top of the staff lines.
        if (octaveBands) for (oct in 2..5) {
            val top = y(oct * 7 + 6) - half / 2
            val bottom = y(oct * 7) + half / 2
            val label = tm.measure("Octave $oct (C$oct–B$oct)", TextStyle(color = accent, fontSize = (half * 0.9f / (density * fontScale)).sp))
            val lx = right - label.size.width - sp * 0.6f
            val ly = (top + bottom) / 2 - label.size.height / 2
            drawRoundRect(paper, Offset(lx - sp * 0.3f, ly - sp * 0.1f), androidx.compose.ui.geometry.Size(label.size.width + sp * 0.6f, label.size.height + sp * 0.2f), androidx.compose.ui.geometry.CornerRadius(sp * 0.3f))
            drawText(label, topLeft = Offset(lx, ly))
        }
        glyph("", left + sp * 0.4f, y(32), ink) // G clef on G4
        glyph("", left + sp * 0.4f, y(24), ink) // F clef on F3
        fun note(d: Int, a: Int, color: Color, shift: Float = 0f) {
            val x = size.width / 2 - sp * 0.7f + shift
            // Ledger lines.
            val ledgers = when {
                d >= 40 -> (40..d step 2)
                d <= 16 -> (d + d.mod(2)..16 step 2)
                d == 28 -> 28..28
                else -> IntRange.EMPTY
            }
            for (l in ledgers) drawLine(color, Offset(x - sp * 0.5f, y(l)), Offset(x + sp * 1.9f, y(l)), lw * 1.3f)
            glyph("", x, y(d), color)
            if (a != 0) glyph(if (a > 0) "" else "", x - sp * 1.4f, y(d), color)
        }
        fun diatonicOf(midi: Int): Pair<Int, Int> {
            val pc = midi.mod(12)
            val oct = midi.floorDiv(12) - 1
            val letter = LETTER_PCS.indexOf(pc)
            return if (letter >= 0) (oct * 7 + letter) to 0 else (oct * 7 + LETTER_PCS.indexOf(pc - 1)) to 1
        }
        hover?.let { note(it, alter, accent.copy(alpha = 0.45f)) }
        // The right note in the middle, a wrong answer next to it.
        for ((i, pair) in shown.withIndex()) { val (d, a) = diatonicOf(pair.first); note(d, a, pair.second, if (i == 0) 0f else sp * 4f) }
    }
}

@Composable
fun PitchSettingsScreen(state: AppState) {
    val s = state.pitchSettings
    TrainerSettingsPage(state) {
        Section("Perfect pitch") {
            LevelChips(PitchLevels.all.map { it.name }, s.level) { state.updatePitchSettings(s.copy(level = it)) }
            NumberStepper("Notes per session", s.questions, "", step = 1) { state.updatePitchSettings(s.copy(questions = it)) }
            WeakFocus(s.focusWeak, { state.updatePitchSettings(s.copy(focusWeak = it)) }, state::resetPitchStats, "notes")
        }
    }
}

@Composable
fun PitchHistoryScreen(state: AppState) = EarHistory(state, "Perfect pitch history", state.pitchHistory, ::pitchName, state::clearPitchHistory)
