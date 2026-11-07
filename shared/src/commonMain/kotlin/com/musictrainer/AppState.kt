package com.musictrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.data.Repository
import com.musictrainer.library.Library
import com.musictrainer.library.PieceMeta
import com.musictrainer.library.buildScore
import com.musictrainer.model.AppMode
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.Score
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.player.HandsMode
import com.musictrainer.player.NoteSink
import com.musictrainer.player.PlayerController
import com.musictrainer.player.PlayerRecord
import com.musictrainer.player.PlayerSettings
import com.musictrainer.player.RecordedEvent
import com.musictrainer.player.Recorder
import com.musictrainer.player.RecordingInfo
import com.musictrainer.player.RecordingPlayer
import androidx.compose.runtime.snapshotFlow
import com.musictrainer.model.SessionRecord
import com.musictrainer.model.Settings
import com.musictrainer.session.SessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

enum class Screen(val title: String) {
    PRACTICE("🎹 Practice"), PROFESSOR("🎓 Professor"), DOCTOR("🩺 Doctor"), HISTORY("📈 History"), SETTINGS("⚙ Settings");

    companion object {
        /** The top tabs of every mode (Professor and Doctor are a row inside the Note player's Practice). */
        fun of(@Suppress("UNUSED_PARAMETER") mode: AppMode): List<Screen> = listOf(PRACTICE, HISTORY, SETTINGS)
    }
}

/** Pages of the Note player's Practice tab. */
enum class PlayerPage { LIBRARY, DETAIL, SHEET }

/** The row under the top tabs once a piece is open: the piece itself, or Professor / Doctor about it. */
enum class PieceView(val title: String) { PIECE("▶ Play"), PROFESSOR("🎓 Professor"), DOCTOR("🩺 Doctor") }

/** Keyboard-range detection: the user presses the lowest, then the highest key. */
enum class DetectStep { OFF, LOWEST, HIGHEST }

class AppState(val platform: Platform, private val scope: CoroutineScope) {
    private val repo = Repository(platform.store)

    var screen by mutableStateOf(Screen.PRACTICE)
    var settings by mutableStateOf(repo.loadSettings())
        private set
    val history = mutableStateListOf<SessionRecord>().apply { addAll(repo.loadHistory()) }

    val session = SessionController(scope, platform.now) { record ->
        history.add(0, record)
        repo.saveHistory(history.toList())
    }

    // ---- Note player ----
    val mode: AppMode get() = settings.appMode
    fun selectMode(m: AppMode) {
        if (m == mode) return
        player.stop(record = true)
        stopTrainers()
        if (screen !in Screen.of(m)) screen = Screen.PRACTICE
        updateSettings(settings.copy(appMode = m))
    }

    var playerSettings by mutableStateOf(repo.loadPlayerSettings())
        private set
    val playerHistory = mutableStateListOf<PlayerRecord>().apply { addAll(repo.loadPlayerHistory()) }
    val library = Library(platform.files, repo, scope)
    var playerPage by mutableStateOf(PlayerPage.LIBRARY)
    var openPiece by mutableStateOf<PieceMeta?>(null)
        private set
    var openScore by mutableStateOf<Score?>(null)
        private set
    var openError by mutableStateOf<String?>(null)
        private set
    /** Tempo chosen on the piece page (defaults to the file's). */
    var chosenBpm by mutableIntStateOf(120)

    val player = PlayerController(scope, platform.now, { noteSink() }) { record ->
        playerHistory.add(0, record)
        repo.savePlayerHistory(playerHistory.toList())
    }

    // ---- rhythm modes and chord finder ----
    val rhythmHistory = mutableStateListOf<com.musictrainer.rhythm.RhythmRecord>().apply { addAll(repo.loadRhythmHistory()) }
    private fun saveRhythm(r: com.musictrainer.rhythm.RhythmRecord) {
        rhythmHistory.add(0, r); repo.saveRhythmHistory(rhythmHistory.toList())
    }
    val dictation = com.musictrainer.rhythm.DictationSession(scope, platform.now, { noteSink() }, ::saveRhythm)
    val imitation = com.musictrainer.rhythm.ImitationSession(scope, platform.now, { noteSink() }, ::saveRhythm)
    val rhythmPlay = com.musictrainer.rhythm.PlaySession(scope, platform.now, { noteSink() }, ::saveRhythm)
    val rhythmSettings = androidx.compose.runtime.mutableStateMapOf<com.musictrainer.rhythm.RhythmMode, com.musictrainer.rhythm.RhythmSettings>().apply {
        com.musictrainer.rhythm.RhythmMode.entries.forEach { put(it, repo.loadRhythmSettings(it)) }
    }
    fun rhythmSession(m: com.musictrainer.rhythm.RhythmMode): com.musictrainer.rhythm.RhythmSession = when (m) {
        com.musictrainer.rhythm.RhythmMode.DICTATION -> dictation
        com.musictrainer.rhythm.RhythmMode.IMITATION -> imitation
        com.musictrainer.rhythm.RhythmMode.PLAY -> rhythmPlay
    }
    /** Whether a rhythm session has been started in [m] (else the Practice tab shows its start page). */
    val rhythmStarted = androidx.compose.runtime.mutableStateMapOf<com.musictrainer.rhythm.RhythmMode, Boolean>()
    fun updateRhythmSettings(m: com.musictrainer.rhythm.RhythmMode, s: com.musictrainer.rhythm.RhythmSettings) {
        val clean = s.sanitized()
        rhythmSettings[m] = clean
        repo.saveRhythmSettings(m, clean)
    }
    fun startRhythm(m: com.musictrainer.rhythm.RhythmMode) {
        stopTrainers()
        rhythmStarted[m] = true
        rhythmSession(m).start(rhythmSettings[m] ?: com.musictrainer.rhythm.RhythmSettings())
    }
    fun leaveRhythm(m: com.musictrainer.rhythm.RhythmMode) {
        rhythmSession(m).abandon()
        rhythmStarted[m] = false
    }
    fun clearRhythmHistory(m: com.musictrainer.rhythm.RhythmMode) {
        rhythmHistory.removeAll { it.mode == m }; repo.saveRhythmHistory(rhythmHistory.toList())
    }

    val chordHistory = mutableStateListOf<com.musictrainer.chords.ChordRecord>().apply { addAll(repo.loadChordHistory()) }
    var chordSettings by mutableStateOf(repo.loadChordSettings())
        private set
    var chordStarted by mutableStateOf(false)
    val chords = com.musictrainer.chords.ChordSession(scope, platform.now) { r ->
        chordHistory.add(0, r); repo.saveChordHistory(chordHistory.toList())
    }
    fun updateChordSettings(s: com.musictrainer.chords.ChordSettings) {
        chordSettings = s.sanitized(); repo.saveChordSettings(chordSettings)
    }
    fun startChords() { stopTrainers(); chordStarted = true; releaseLatched(); chords.start(chordSettings) }
    fun leaveChords() { chords.abandon(); chordStarted = false; releaseLatched() }

    /** On-screen keys held down by a click in the chord finder (a mouse can only press one key at a time). */
    val latchedKeys = mutableStateListOf<Int>()

    /** Chord finder, on-screen keyboard: a click holds a key down, another click releases it. */
    fun toggleLatchedKey(midi: Int) {
        if (midi in latchedKeys) { latchedKeys.remove(midi); onVirtualKeyUp(midi) }
        else { latchedKeys += midi; onVirtualKey(midi) }
    }

    fun releaseLatched() {
        latchedKeys.toList().forEach { latchedKeys.remove(it); onVirtualKeyUp(it) }
    }
    fun clearChordHistory() { chordHistory.clear(); repo.saveChordHistory(emptyList()) }

    val earHistory = mutableStateListOf<com.musictrainer.chords.EarRecord>().apply { addAll(repo.loadEarHistory()) }
    var earSettings by mutableStateOf(repo.loadEarSettings())
        private set
    var earStats by mutableStateOf(repo.loadEarStats())
        private set
    var earStarted by mutableStateOf(false)
    val ear = com.musictrainer.chords.EarSession(
        scope, platform.now, { noteSink() }, { earStats }, { earStats = it; repo.saveEarStats(it) },
    ) { r -> earHistory.add(0, r); repo.saveEarHistory(earHistory.toList()) }
    fun updateEarSettings(s: com.musictrainer.chords.EarSettings) { earSettings = s.sanitized(); repo.saveEarSettings(earSettings) }
    fun startEar() { stopTrainers(); earStarted = true; ear.start(earSettings) }
    fun leaveEar() { ear.abandon(); earStarted = false }
    fun clearEarHistory() { earHistory.clear(); repo.saveEarHistory(emptyList()) }
    fun resetEarStats() { earStats = com.musictrainer.chords.EarStats(); repo.saveEarStats(earStats) }

    val progHistory = mutableStateListOf<com.musictrainer.chords.EarRecord>().apply { addAll(repo.loadProgHistory()) }
    var progSettings by mutableStateOf(repo.loadProgSettings())
        private set
    var progStats by mutableStateOf(repo.loadProgStats())
        private set
    var progStarted by mutableStateOf(false)
    val progressions = com.musictrainer.chords.ProgressionSession(
        scope, platform.now, { noteSink() }, { progStats }, { progStats = it; repo.saveProgStats(it) },
    ) { r -> progHistory.add(0, r); repo.saveProgHistory(progHistory.toList()) }
    fun updateProgSettings(s: com.musictrainer.chords.ProgressionSettings) { progSettings = s.sanitized(); repo.saveProgSettings(progSettings) }
    fun startProgressions() { stopTrainers(); progStarted = true; progressions.start(progSettings) }
    fun leaveProgressions() { progressions.abandon(); progStarted = false }
    fun clearProgHistory() { progHistory.clear(); repo.saveProgHistory(emptyList()) }
    fun resetProgStats() { progStats = com.musictrainer.chords.EarStats(); repo.saveProgStats(progStats) }

    val pitchHistory = mutableStateListOf<com.musictrainer.chords.EarRecord>().apply { addAll(repo.loadPitchHistory()) }
    var pitchSettings by mutableStateOf(repo.loadPitchSettings())
        private set
    var pitchStats by mutableStateOf(repo.loadPitchStats())
        private set
    var pitchStarted by mutableStateOf(false)
    val pitch = com.musictrainer.chords.PitchSession(
        scope, platform.now, { noteSink() }, { pitchStats }, { pitchStats = it; repo.savePitchStats(it) },
    ) { r -> pitchHistory.add(0, r); repo.savePitchHistory(pitchHistory.toList()) }
    fun updatePitchSettings(s: com.musictrainer.chords.PitchSettings) { pitchSettings = s.sanitized(); repo.savePitchSettings(pitchSettings) }
    fun startPitch() { stopTrainers(); pitchStarted = true; pitch.start(pitchSettings) }
    fun leavePitch() { pitch.abandon(); pitchStarted = false }
    fun clearPitchHistory() { pitchHistory.clear(); repo.savePitchHistory(emptyList()) }
    fun resetPitchStats() { pitchStats = com.musictrainer.chords.EarStats(); repo.savePitchStats(pitchStats) }

    val intervalHistory = mutableStateListOf<com.musictrainer.chords.EarRecord>().apply { addAll(repo.loadIntervalHistory()) }
    var intervalSettings by mutableStateOf(repo.loadIntervalSettings())
        private set
    var intervalStats by mutableStateOf(repo.loadIntervalStats())
        private set
    var intervalStarted by mutableStateOf(false)
    val intervals = com.musictrainer.chords.IntervalSession(
        scope, platform.now, { noteSink() }, { intervalStats }, { intervalStats = it; repo.saveIntervalStats(it) },
    ) { r -> intervalHistory.add(0, r); repo.saveIntervalHistory(intervalHistory.toList()) }
    fun updateIntervalSettings(s: com.musictrainer.chords.IntervalSettings) { intervalSettings = s.sanitized(); repo.saveIntervalSettings(intervalSettings) }
    fun startIntervals() { stopTrainers(); intervalStarted = true; intervals.start(intervalSettings) }
    fun leaveIntervals() { intervals.abandon(); intervalStarted = false }
    fun clearIntervalHistory() { intervalHistory.clear(); repo.saveIntervalHistory(emptyList()) }
    fun resetIntervalStats() { intervalStats = com.musictrainer.chords.EarStats(); repo.saveIntervalStats(intervalStats) }

    /** Stops whatever rhythm/chord session runs (keeps results of answered questions). */
    private fun stopTrainers() {
        com.musictrainer.rhythm.RhythmMode.entries.forEach { if (rhythmStarted[it] == true) rhythmSession(it).abandon(); rhythmStarted[it] = false }
        if (chordStarted) { chords.abandon(); chordStarted = false }
        if (earStarted) { ear.abandon(); earStarted = false }
        if (progStarted) { progressions.abandon(); progStarted = false }
        if (pitchStarted) { pitch.abandon(); pitchStarted = false }
        if (intervalStarted) { intervals.abandon(); intervalStarted = false }
    }

    // ---- loops (Note player) ----
    private val allLoops = androidx.compose.runtime.mutableStateMapOf<String, List<com.musictrainer.player.PieceLoop>>().apply { putAll(repo.loadLoops()) }
    /** "🔁" in the sheet header: off pauses every loop without forgetting them. */
    var loopsActive by mutableStateOf(true)
        private set

    fun loopsOf(pieceId: String): List<com.musictrainer.player.PieceLoop> = allLoops[pieceId].orEmpty()

    private fun saveLoops(pieceId: String, loops: List<com.musictrainer.player.PieceLoop>) {
        if (loops.isEmpty()) allLoops.remove(pieceId) else allLoops[pieceId] = loops.sortedBy { it.startBar }
        repo.saveLoops(allLoops.toMap())
        applyLoops()
    }

    /** Hands the enabled loops of the open piece to the player (none while looping is paused). */
    private fun applyLoops() {
        player.loops = if (!loopsActive) emptyList()
        else loopsOf(player.pieceId).filter { it.enabled }.mapNotNull { stepsOf(it) }
    }

    private var lastId = 0L
    /** A unique id for loops, notes and bookmarks (the time, but never the same twice). */
    private fun newId(): Long {
        val known = (allLoops.values.flatten().map { it.id } + allMarks.values.flatten().map { it.id }).maxOrNull() ?: 0L
        lastId = maxOf(platform.now(), lastId + 1, known + 1)
        return lastId
    }

    /** Adds a loop (switched off) and returns its id, or null without a piece. */
    /** The notes (play steps) a loop covers: its own, or all the notes of its bars. */
    fun stepsOf(loop: com.musictrainer.player.PieceLoop): IntRange? {
        val steps = player.score?.steps ?: return null
        if (loop.byNotes) return loop.fromStep.coerceAtMost(steps.lastIndex)..loop.toStep.coerceAtMost(steps.lastIndex)
        val first = steps.indexOfFirst { it.measure >= loop.startBar - 1 }.takeIf { it >= 0 } ?: return null
        val last = steps.indexOfLast { it.measure <= loop.endBar - 1 }.takeIf { it >= first } ?: return null
        return first..last
    }

    /** [loop] set to notes [a]..[b], with its bars kept in step. */
    private fun withSteps(loop: com.musictrainer.player.PieceLoop, a: Int, b: Int): com.musictrainer.player.PieceLoop {
        val steps = player.score?.steps ?: return loop
        val from = minOf(a, b).coerceIn(0, steps.lastIndex); val to = maxOf(a, b).coerceIn(0, steps.lastIndex)
        return loop.copy(fromStep = from, toStep = to, startBar = steps[from].measure + 1, endBar = steps[to].measure + 1)
    }

    fun addLoop(startBar: Int, endBar: Int, name: String = ""): Long? {
        val id = player.pieceId.takeIf { it.isNotEmpty() } ?: return null
        // New loops start switched on (click a loop on the sheet to turn it off).
        val loop = com.musictrainer.player.PieceLoop(newId(), minOf(startBar, endBar), maxOf(startBar, endBar), true, name.trim())
        saveLoops(id, loopsOf(id) + loop)
        return loop.id
    }

    /** The loop open in the editor banner (start / end / move buttons), or null. */
    var loopEditId by mutableStateOf<Long?>(null)
        private set

    /** The loop as it was before editing (null: a new loop, which Cancel removes). */
    private var loopEditOriginal: com.musictrainer.player.PieceLoop? = null

    fun editLoop(id: Long) {
        loopPick = null; fragmentPick = null
        loopEditOriginal = loopsOf(player.pieceId).firstOrNull { it.id == id }
        loopEditId = id
    }
    fun doneLoopEdit() { loopEditId = null; loopEditOriginal = null }

    /** Cancel in the editor: a new loop goes away, an existing one gets its old bars back. */
    fun cancelLoopEdit() {
        val id = loopEditId ?: return
        val original = loopEditOriginal
        val current = loopsOf(player.pieceId).firstOrNull { it.id == id }
        if (current != null) { if (original == null) deleteLoop(current) else updateLoop(original) }
        doneLoopEdit()
    }

    enum class LoopPart { START, END, WHOLE }

    /** Moves the edited loop's start, end or the whole loop by [delta] notes (kept inside the piece). */
    fun nudgeLoop(part: LoopPart, delta: Int) {
        val loop = loopsOf(player.pieceId).firstOrNull { it.id == loopEditId } ?: return
        val range = stepsOf(loop) ?: return
        val last = (player.score?.steps?.size ?: return) - 1
        val (a, b) = when (part) {
            LoopPart.START -> (range.first + delta).coerceIn(0, range.last) to range.last
            LoopPart.END -> range.first to (range.last + delta).coerceIn(range.first, last)
            LoopPart.WHOLE -> {
                val d = delta.coerceIn(-range.first, last - range.last)
                (range.first + d) to (range.last + d)
            }
        }
        val moved = withSteps(loop, a, b)
        if (moved != loop) updateLoop(moved)
    }

    fun updateLoop(loop: com.musictrainer.player.PieceLoop) {
        val id = player.pieceId
        saveLoops(id, loopsOf(id).map { if (it.id == loop.id) loop.copy(startBar = minOf(loop.startBar, loop.endBar), endBar = maxOf(loop.startBar, loop.endBar)) else it })
    }

    /** Human-readable place of a loop: "bar 4, beat 2 – bar 6, beat 1" for note loops, else its bars. */
    fun loopPlace(loop: com.musictrainer.player.PieceLoop): String {
        val score = player.score ?: return loop.label
        val r = stepsOf(loop) ?: return loop.label
        if (!loop.byNotes) return if (loop.startBar == loop.endBar) "Bar ${loop.startBar}" else "Bars ${loop.startBar}–${loop.endBar}"
        fun at(st: Int): String {
            val s = score.steps[st]; val m = score.measures.getOrNull(s.measure) ?: return "bar ${s.measure + 1}"
            return "bar ${s.measure + 1}, beat ${(s.time - m.start) / m.meter.beat + 1}"
        }
        return (if (r.first == r.last) at(r.first) else "${at(r.first)} – ${at(r.last)}").replaceFirstChar { it.uppercase() }
    }

    fun loopTitle(loop: com.musictrainer.player.PieceLoop): String = loop.name.ifBlank { loopPlace(loop) }

    fun deleteLoop(loop: com.musictrainer.player.PieceLoop) {
        val id = player.pieceId
        saveLoops(id, loopsOf(id).filter { it.id != loop.id })
    }

    fun toggleLoopsActive() { loopsActive = !loopsActive; applyLoops() }

    /** Moves playback (or the start point) to the first bar of [loop], enabling it. */
    fun jumpToLoop(loop: com.musictrainer.player.PieceLoop) {
        if (!loop.enabled) updateLoop(loop.copy(enabled = true))
        if (!loopsActive) toggleLoopsActive()
        player.seek(stepsOf(loop)?.first ?: return)
    }

    /** Plays [loop] to hear how it sounds: switched on, from its first note, in Listen (back with Both / Right / Left). */
    fun listenToLoop(loop: com.musictrainer.player.PieceLoop) {
        jumpToLoop(loop)
        if (player.running) player.switchHands(HandsMode.NONE)
        else { player.switchHands(HandsMode.NONE); player.start() }
    }

    // ---- Professor / Doctor: a row inside Practice, about the piece open there ----
    var doctorDays by mutableIntStateOf(7)
    var pieceView by mutableStateOf(PieceView.PIECE)
        private set

    fun showPieceView(v: PieceView) {
        if (v != PieceView.PIECE) player.stop(record = true) // looking at the analysis, not playing
        pieceView = v
    }

    /** Opens [piece] in the Practice tab, ready to play from [bar] (1-based). */
    fun practiceFrom(piece: PieceMeta, bar: Int) {
        scope.launch {
            val score = if (openPiece?.id == piece.id) openScore else library.bytes(piece)?.let { runCatching { buildScore(piece, it, playerSettings.defaultBpm) }.getOrNull() }
            score ?: return@launch
            player.stop(record = true)
            openPiece = piece
            openScore = score
            chosenBpm = score.bpm
            screen = Screen.PRACTICE
            pieceView = PieceView.PIECE
            play(HandsMode.BOTH)
            jumpToBar(bar)
        }
    }

    // ---- notes and bookmarks (Note player) ----
    private val allMarks = androidx.compose.runtime.mutableStateMapOf<String, List<com.musictrainer.player.PieceMark>>().apply { putAll(repo.loadMarks()) }

    fun marksOf(pieceId: String): List<com.musictrainer.player.PieceMark> = allMarks[pieceId].orEmpty()

    private fun saveMarks(pieceId: String, marks: List<com.musictrainer.player.PieceMark>) {
        if (marks.isEmpty()) allMarks.remove(pieceId) else allMarks[pieceId] = marks.sortedWith(compareBy({ it.bar }, { it.id }))
        repo.saveMarks(allMarks.toMap())
    }

    fun addMark(pieceId: String, bar: Int, text: String, bookmark: Boolean, fromStep: Int = -1, toStep: Int = -1) {
        if (pieceId.isEmpty() || text.isBlank() && !bookmark) return
        saveMarks(pieceId, marksOf(pieceId) + com.musictrainer.player.PieceMark(newId(), bar, text.trim(), bookmark, fromStep, toStep))
    }

    /** Sheet: choosing a loop's bars by clicking them (new loop, or editing [LoopPick.editId]). */
    var loopPick by mutableStateOf<LoopPick?>(null)
        private set

    fun startLoopPick(editId: Long? = null) { fragmentPick = null; pendingFragment = null; loopEditId = null; loopPick = LoopPick(editId) }
    fun cancelLoopPick() { loopPick = null }

    /**
     * A note clicked on the sheet while choosing a loop: the first click sets the start, the second the end;
     * then the loop is made and the editor opens (start / end / move by notes).
     */
    fun pickLoopStep(step: Int) {
        val pick = loopPick ?: return
        val steps = player.score?.steps ?: return
        if (step !in steps.indices) return
        val first = pick.firstStep
        if (first == null) { loopPick = pick.copy(firstStep = step); return }
        loopPick = null
        val existing = pick.editId?.let { id -> loopsOf(player.pieceId).firstOrNull { it.id == id } }
        loopEditOriginal = existing
        val loop = existing ?: com.musictrainer.player.PieceLoop(newId(), 1, 1, true).also { saveLoops(player.pieceId, loopsOf(player.pieceId) + it) }
        updateLoop(withSteps(loop, first, step))
        loopEditId = loop.id
    }

    /** A note clicked while the editor is open: before the start moves the start there, otherwise the end. */
    fun clickLoopStep(step: Int) {
        val loop = loopsOf(player.pieceId).firstOrNull { it.id == loopEditId } ?: return
        val range = stepsOf(loop) ?: return
        updateLoop(if (step < range.first) withSteps(loop, step, range.last) else withSteps(loop, range.first, step))
    }

    /** For tests and older callers: the first note of bar [bar] (1-based). */
    fun pickLoopBar(bar: Int) { player.score?.steps?.indexOfFirst { it.measure >= bar - 1 }?.takeIf { it >= 0 }?.let(::pickLoopStep) }
    fun clickLoopBar(bar: Int) {
        val steps = player.score?.steps ?: return
        val loop = loopsOf(player.pieceId).firstOrNull { it.id == loopEditId } ?: return
        val range = stepsOf(loop) ?: return
        // A bar before the loop: from its first note; otherwise up to its last note.
        if (bar - 1 < steps[range.first].measure) steps.indexOfFirst { it.measure >= bar - 1 }.takeIf { it >= 0 }?.let(::clickLoopStep)
        else steps.indexOfLast { it.measure <= bar - 1 }.takeIf { it >= 0 }?.let(::clickLoopStep)
    }

    /** Sheet: choosing the first and last note of a fragment for a new note / bookmark. */
    var fragmentPick by mutableStateOf<com.musictrainer.player.FragmentPick?>(null)
        private set
    /** The chosen fragment (steps), waiting for its text. */
    var pendingFragment by mutableStateOf<Pair<com.musictrainer.player.FragmentPick, IntRange>?>(null)
        private set

    fun startFragmentPick(bookmark: Boolean) { loopPick = null; fragmentPick = com.musictrainer.player.FragmentPick(bookmark); pendingFragment = null }
    fun cancelFragmentPick() { fragmentPick = null; pendingFragment = null }

    /** The note clicked for a new note / bookmark: ask for its text. */
    fun pickStep(step: Int) {
        val pick = fragmentPick ?: return
        fragmentPick = null
        pendingFragment = pick to step..step
    }

    fun saveFragment(text: String) {
        val (pick, range) = pendingFragment ?: return
        val score = player.score ?: return
        val bar = (score.steps.getOrNull(range.first)?.measure ?: 0) + 1
        addMark(player.pieceId, bar, text, pick.bookmark, range.first, range.last)
        pendingFragment = null
    }

    /** Where a mark starts, for the player: its first note, or its bar. */
    fun jumpToMark(m: com.musictrainer.player.PieceMark) {
        if (m.isFragment && player.score?.steps?.size?.let { m.fromStep < it } == true) player.seek(m.fromStep) else jumpToBar(m.bar)
    }

    fun updateMark(pieceId: String, mark: com.musictrainer.player.PieceMark) =
        saveMarks(pieceId, marksOf(pieceId).map { if (it.id == mark.id) mark else it })

    fun deleteMark(pieceId: String, mark: com.musictrainer.player.PieceMark) =
        saveMarks(pieceId, marksOf(pieceId).filter { it.id != mark.id })

    /** Moves playback (or the start point) to [bar] (1-based). */
    fun jumpToBar(bar: Int) {
        val score = player.score ?: return
        player.seek(score.steps.indexOfFirst { it.measure >= bar - 1 }.coerceAtLeast(0))
    }

    /** Opens the sheet for the piece page's piece and puts the start point on [bar] (from Professor / Doctor / notes). */
    fun playFromBar(bar: Int, hands: com.musictrainer.player.HandsMode = com.musictrainer.player.HandsMode.BOTH) {
        play(hands)
        jumpToBar(bar)
    }

    /** Adds a loop for the open piece (from the piece page: the player may not have it loaded yet). */
    fun addLoopFor(pieceId: String, startBar: Int, endBar: Int, name: String = "") {
        val loop = com.musictrainer.player.PieceLoop(newId(), minOf(startBar, endBar), maxOf(startBar, endBar), true, name.trim())
        saveLoops(pieceId, loopsOf(pieceId) + loop)
    }

    // ---- recordings ----
    /** "● Record" on the sheet: record the next sessions. */
    var recordEnabled by mutableStateOf(false)
    val recordings = mutableStateListOf<RecordingInfo>().apply { addAll(repo.loadRecordings()) }
    private val recorder = Recorder(platform.now)
    val recordingPlayer = RecordingPlayer(scope, platform.now)
    var recording by mutableStateOf(false)
        private set

    init {
        // Start/stop recording with the player; save when a recorded session ends.
        scope.launch {
            snapshotFlow { player.running }.collect { running ->
                if (running && recordEnabled && player.hands != HandsMode.NONE) {
                    recordingPlayer.stop()
                    recorder.begin(); recording = true
                } else if (!running && recorder.active) {
                    recording = false
                    saveRecording(recorder.end())
                }
            }
        }
    }

    private fun saveRecording(events: List<RecordedEvent>) {
        val score = player.score ?: return
        val notes = events.count { it.on }
        if (notes == 0) return
        val info = RecordingInfo(
            id = platform.now(), pieceId = player.pieceId, title = score.title,
            durationMs = events.maxOf { it.t }, notes = notes, hands = player.hands, bpm = player.bpm,
            // The session just ended has set lastRecord (it is cleared when a new session starts).
            session = player.lastRecord?.takeIf { it.pieceId == player.pieceId },
        )
        repo.saveRecordingEvents(info, events)
        recordings.add(0, info)
        repo.saveRecordings(recordings.toList())
    }

    fun playRecording(info: RecordingInfo) {
        player.stop(record = true)
        recordingPlayer.play(info.id, repo.loadRecordingEvents(info), noteSink())
    }

    fun deleteRecording(info: RecordingInfo) {
        if (recordingPlayer.playingId == info.id) recordingPlayer.stop()
        recordings.remove(info)
        repo.saveRecordings(recordings.toList())
        repo.deleteRecordingEvents(info)
    }

    private fun noteSink(): NoteSink {
        val base = baseSink()
        // Notes the app plays are part of what is recorded.
        return object : NoteSink {
            override fun noteOn(midi: Int, velocity: Int) { recorder.note(midi, true); base.noteOn(midi, velocity) }
            override fun noteOff(midi: Int) { recorder.note(midi, false); base.noteOff(midi) }
            override fun allOff() = base.allOff()
            override fun click(tone: com.musictrainer.audio.ClickTone, accent: Boolean, volume: Float) = base.click(tone, accent, volume)
        }
    }

    /** Rhythm, chord and ear modes (everything except the Note finder and the Note player). */
    private val trainerMode: Boolean get() = mode != AppMode.FEEDER && mode != AppMode.PLAYER

    /**
     * Where the app's notes go: the chosen MIDI device; in the trainer modes also the first connected MIDI
     * device when "App piano" is chosen (if enabled); otherwise the app piano.
     */
    private val outputId: String?
        get() = midiOutId ?: if (trainerMode && settings.trainerMidiOut) platform.midiOut.devices.value.firstOrNull()?.id else null

    private fun baseSink(): NoteSink {
        val out = outputId ?: PlayerSettings.APP_PIANO
        if (out == PlayerSettings.APP_PIANO) return object : NoteSink {
            override fun noteOn(midi: Int, velocity: Int) = platform.sound.play(midi)
            override fun noteOff(midi: Int) = platform.sound.stop(midi)
            override fun allOff() = platform.sound.stopAll()
            override fun click(tone: com.musictrainer.audio.ClickTone, accent: Boolean, volume: Float) = platform.sound.click(tone, accent, volume)
        }
        return object : NoteSink {
            override fun noteOn(midi: Int, velocity: Int) = platform.midiOut.noteOn(out, midi, velocity)
            override fun noteOff(midi: Int) = platform.midiOut.noteOff(out, midi)
            override fun allOff() = platform.midiOut.allNotesOff(out)
            // General MIDI drums: wood blocks (76 high / 77 low), side stick, metronome click / bell.
            override fun click(tone: com.musictrainer.audio.ClickTone, accent: Boolean, volume: Float) {
                val note = when (tone) {
                    com.musictrainer.audio.ClickTone.WOOD, com.musictrainer.audio.ClickTone.TICKTOCK -> if (accent) 76 else 77
                    com.musictrainer.audio.ClickTone.LOW -> 37
                    com.musictrainer.audio.ClickTone.BEEP -> if (accent) 34 else 33
                    com.musictrainer.audio.ClickTone.SOFT -> if (accent) 33 else 33
                }
                platform.midiOut.drum(out, note, (volume * 127).toInt().coerceIn(1, 127))
            }
        }
    }

    fun updatePlayerSettings(new: PlayerSettings) {
        val old = playerSettings
        playerSettings = new.copy(
            stripeOpacity = new.stripeOpacity.coerceIn(0, Settings.MAX_STRIPE_OPACITY),
            zoomPercent = new.zoomPercent.coerceIn(PlayerSettings.MIN_ZOOM, PlayerSettings.MAX_ZOOM),
            countdownSeconds = new.countdownSeconds.coerceIn(0, PlayerSettings.MAX_COUNTDOWN),
            defaultBpm = new.defaultBpm.coerceIn(20, 400),
        )
        player.countdownSeconds = playerSettings.countdownSeconds
        repo.savePlayerSettings(playerSettings)
        if (old.folders != new.folders) library.scan(new.folders)
        if (new.soundOutput != PlayerSettings.APP_PIANO && new.soundOutput != old.soundOutput) {
            platform.midiOut.allNotesOff(new.soundOutput) // opens the device ahead of playback
        }
    }

    fun addFolder() {
        scope.launch {
            val folder = platform.files.pickFolder() ?: return@launch
            if (folder !in playerSettings.folders) updatePlayerSettings(playerSettings.copy(folders = playerSettings.folders + folder))
        }
    }

    fun openPiece(piece: PieceMeta) {
        scope.launch {
            openError = null
            val bytes = library.bytes(piece)
            val score = bytes?.let { runCatching { buildScore(piece, it, playerSettings.defaultBpm) } }
            if (score == null || score.isFailure) {
                openError = "Couldn't open ${piece.fileName}: " + (score?.exceptionOrNull()?.message ?: "file not readable")
                return@launch
            }
            openPiece = piece
            openScore = score.getOrThrow()
            chosenBpm = openScore!!.bpm
            playerPage = PlayerPage.DETAIL
        }
    }

    fun play(hands: HandsMode) {
        val score = openScore ?: return
        val piece = openPiece ?: return
        player.load(score, piece.id)
        applyLoops()
        player.hands = hands
        player.style = playerSettings.playStyle
        player.countIn = playerSettings.countIn
        player.countdownSeconds = playerSettings.countdownSeconds
        player.checkTiming = playerSettings.checkTiming
        player.bpm = chosenBpm.coerceIn(20, 400)
        playerPage = PlayerPage.SHEET
    }

    /**
     * "Back" (mouse back button, Alt+←, Android back): one step up. Returns false when there is nowhere to go.
     */
    fun navigateBack(): Boolean {
        if (loopPick != null) { cancelLoopPick(); return true }
        if (loopEditId != null) { cancelLoopEdit(); return true }
        if (fragmentPick != null || pendingFragment != null) { cancelFragmentPick(); return true }
        if (screen != Screen.PRACTICE) { screen = Screen.PRACTICE; return true }
        val rm = mode.rhythmMode
        when {
            mode == AppMode.PLAYER -> when {
                playerPage == PlayerPage.LIBRARY -> return false
                pieceView != PieceView.PIECE -> pieceView = PieceView.PIECE
                playerPage == PlayerPage.SHEET -> leaveSheet()
                else -> backToLibrary()
            }
            rm != null -> if (rhythmStarted[rm] == true) leaveRhythm(rm) else return false
            mode == AppMode.CHORDS -> if (chordStarted) leaveChords() else return false
            mode == AppMode.CHORD_EAR -> if (earStarted) leaveEar() else return false
            mode == AppMode.PROGRESSION_EAR -> if (progStarted) leaveProgressions() else return false
            mode == AppMode.PERFECT_PITCH -> if (pitchStarted) leavePitch() else return false
            mode == AppMode.RELATIVE_PITCH -> if (intervalStarted) leaveIntervals() else return false
            else -> return false
        }
        return true
    }

    fun leaveSheet() {
        player.stop(record = true)
        playerPage = PlayerPage.DETAIL
    }

    fun backToLibrary() {
        pieceView = PieceView.PIECE
        recordingPlayer.stop()
        player.stop(record = true)
        playerPage = PlayerPage.LIBRARY
    }

    var detectStep by mutableStateOf(DetectStep.OFF)
        private set
    private var detectFirst = 0

    /** Key currently flashed on the on-screen piano. */
    var pressedKey by mutableStateOf<Int?>(null)
        private set
    var pressCount by mutableIntStateOf(0)
        private set
    private var pressJob: Job? = null

    fun startFirstSession() {
        session.start(settings)
        platform.mcp?.apply(settings.mcpEnabled, settings.mcpPort, settings.mcpLan)
        library.scan(playerSettings.folders)
    }

    fun updateSettings(new: Settings) {
        val sanitized = new.sanitized()
        val old = settings
        settings = sanitized
        repo.saveSettings(sanitized)
        if (old.mcpEnabled != sanitized.mcpEnabled || old.mcpPort != sanitized.mcpPort || old.mcpLan != sanitized.mcpLan) {
            platform.mcp?.apply(sanitized.mcpEnabled, sanitized.mcpPort, sanitized.mcpLan)
        }
        if (old.practiceKey() != sanitized.practiceKey()) session.restart(sanitized) else session.applyLive(sanitized)
    }

    fun startDetection() {
        detectStep = DetectStep.LOWEST
    }

    fun cancelDetection() {
        detectStep = DetectStep.OFF
    }

    fun clearHistory() {
        history.clear()
        repo.saveHistory(emptyList())
    }

    /** A tap on the on-screen piano: plays its sound, then counts as a key press. */
    /** The MIDI device chosen as sound output, or null for the app piano. */
    private val midiOutId: String?
        get() = playerSettings.soundOutput.takeIf { it != PlayerSettings.APP_PIANO }

    fun selectSoundOutput(id: String) = updatePlayerSettings(playerSettings.copy(soundOutput = id))

    fun onVirtualKey(midi: Int) {
        if (settings.virtualKeySound) outputId?.let { platform.midiOut.noteOn(it, midi, 80) } ?: platform.sound.play(midi)
        onKey(midi)
    }

    /** Finger lifted from an on-screen key: damp its sound. */
    fun onVirtualKeyUp(midi: Int) {
        outputId?.let { platform.midiOut.noteOff(it, midi) } ?: platform.sound.stop(midi)
        onKeyUp(midi)
    }

    /** A key was released (MIDI or on-screen). */
    fun onKeyUp(midi: Int) {
        recorder.note(midi, false)
        if (screen == Screen.PRACTICE && mode == AppMode.PLAYER && playerPage == PlayerPage.SHEET) player.onNoteOff(midi)
        if (mode == AppMode.CHORDS) chords.onKeyUp(midi)
    }

    /** Called for every key press, from MIDI or from the on-screen piano. */
    fun onKey(midi: Int) {
        recorder.note(midi, true)
        flash(midi)
        when (detectStep) {
            DetectStep.LOWEST -> {
                detectFirst = midi
                detectStep = DetectStep.HIGHEST
            }
            DetectStep.HIGHEST -> {
                detectStep = DetectStep.OFF
                updateSettings(
                    settings.copy(
                        keyboardLowest = min(detectFirst, midi),
                        keyboardHighest = max(detectFirst, midi),
                    )
                )
            }
            DetectStep.OFF -> if (screen == Screen.PRACTICE) {
                val rm = mode.rhythmMode
                when {
                    mode == AppMode.PLAYER -> if (playerPage == PlayerPage.SHEET) player.onNote(midi)
                    rm == com.musictrainer.rhythm.RhythmMode.DICTATION -> if (rhythmStarted[rm] == true) {
                        // White keys from middle C choose the values: C4 = 1st, D4 = 2nd …
                        val d = dictation.drill
                        val i = whiteKeyIndex(midi)
                        if (d != null && i != null) dictation.level.palette(d.meter).getOrNull(i)?.let(dictation::write)
                    }
                    rm != null -> if (rhythmStarted[rm] == true) rhythmSession(rm).onTap()
                    mode == AppMode.CHORDS -> if (chordStarted) chords.onKeyDown(midi)
                    mode == AppMode.CHORD_EAR -> if (earStarted) earKey(midi)
                    mode == AppMode.PROGRESSION_EAR -> if (progStarted) {
                        whiteKeyIndex(midi)?.let { i -> progressions.level.progressions.getOrNull(i)?.let(progressions::choose) }
                    }
                    mode == AppMode.PERFECT_PITCH -> if (pitchStarted) pitch.choose(midi)
                    mode == AppMode.RELATIVE_PITCH -> if (intervalStarted) intervalKey(midi)
                    else -> session.onNotePlayed(midi)
                }
            }
        }
    }

    /**
     * Relative pitch by MIDI: the interval played up from middle C (C4 = unison … C5 = octave). Players
     * often press C4 first, so C4 alone only counts as unison if no other key follows within a moment.
     */
    private var unisonJob: Job? = null

    private fun intervalKey(midi: Int) {
        unisonJob?.cancel(); unisonJob = null
        val offered = intervals.level.intervals
        if (midi == 60) {
            unisonJob = scope.launch {
                delay(700)
                offered.firstOrNull { it.semitones == 0 }?.let(intervals::choose)
            }
            return
        }
        offered.firstOrNull { it.semitones == midi - 60 }?.let(intervals::choose)
    }

    /**
     * Chord identification by MIDI: the keys of a chord are collected until no new key comes for a moment
     * (so a four-note chord isn't judged at its first three notes); a chord of an offered type answers.
     */
    private val earHeld = mutableSetOf<Int>()
    private var earJob: Job? = null

    private fun earKey(midi: Int) {
        earHeld += midi
        earJob?.cancel()
        earJob = scope.launch {
            delay(400)
            val keys = earHeld.toSet()
            earHeld.clear()
            if (keys.size < 3 || ear.phase != com.musictrainer.chords.EarSession.Phase.ASK) return@launch
            val pcs = keys.map { it.mod(12) }.toSet()
            fun roots(q: com.musictrainer.chords.ChordQuality) = pcs.filter { root -> q.intervals.map { (root + it).mod(12) }.toSet() == pcs }
            val candidates = ear.level.qualities.filter { roots(it).isNotEmpty() }
            // Some types share their notes (Dm6 = Bm7♭5, C6 = Am7, diminished 7ths): notes that are a correct
            // voicing of the answer count for it; otherwise the type whose root is the lowest note wins.
            val bass = keys.min().mod(12)
            val match = candidates.firstOrNull { it == ear.quality }
                ?: candidates.firstOrNull { bass in roots(it) }
                ?: candidates.firstOrNull()
            // Not one of the offered chord types: ignore it and let them try again.
            match?.let(ear::choose)
        }
    }

    private fun flash(midi: Int) {
        pressedKey = midi
        pressCount++
        pressJob?.cancel()
        pressJob = scope.launch {
            delay(250)
            pressedKey = null
        }
    }
}

/** Index of [midi] among the white keys from middle C (C4 = 0, D4 = 1 …), or null for black keys / below C4. */
fun whiteKeyIndex(midi: Int): Int? {
    if (midi < 60 || midi.mod(12) in setOf(1, 3, 6, 8, 10)) return null
    return (60 until midi).count { it.mod(12) !in setOf(1, 3, 6, 8, 10) }
}

/** The white key for answer [index] (0 = C4), for the hints on answer buttons. */
fun whiteKeyName(index: Int): String {
    val letters = "CDEFGAB"
    return "${letters[index % 7]}${4 + index / 7}"
}

/** Choosing a loop on the sheet: [firstBar] once the start is clicked; [editId] when changing an existing loop. */
data class LoopPick(val editId: Long? = null, val firstBar: Int? = null, val firstStep: Int? = null)
