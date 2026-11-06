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

enum class Screen(val title: String) { PRACTICE("Practice"), HISTORY("History"), SETTINGS("Settings") }

/** Pages of the Note player's Practice tab. */
enum class PlayerPage { LIBRARY, DETAIL, SHEET }

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
        }
    }

    private fun baseSink(): NoteSink {
        val out = playerSettings.soundOutput
        if (out == PlayerSettings.APP_PIANO) return object : NoteSink {
            override fun noteOn(midi: Int, velocity: Int) = platform.sound.play(midi)
            override fun noteOff(midi: Int) = platform.sound.stop(midi)
            override fun allOff() = platform.sound.stopAll()
        }
        return object : NoteSink {
            override fun noteOn(midi: Int, velocity: Int) = platform.midiOut.noteOn(out, midi, velocity)
            override fun noteOff(midi: Int) = platform.midiOut.noteOff(out, midi)
            override fun allOff() = platform.midiOut.allNotesOff(out)
        }
    }

    fun updatePlayerSettings(new: PlayerSettings) {
        val old = playerSettings
        playerSettings = new.copy(
            stripeOpacity = new.stripeOpacity.coerceIn(0, Settings.MAX_STRIPE_OPACITY),
            zoomPercent = new.zoomPercent.coerceIn(PlayerSettings.MIN_ZOOM, PlayerSettings.MAX_ZOOM),
            countdownSeconds = new.countdownSeconds.coerceIn(0, PlayerSettings.MAX_COUNTDOWN),
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
            val score = bytes?.let { runCatching { buildScore(piece, it) } }
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
        player.hands = hands
        player.style = playerSettings.playStyle
        player.countIn = playerSettings.countIn
        player.countdownSeconds = playerSettings.countdownSeconds
        player.checkTiming = playerSettings.checkTiming
        player.bpm = chosenBpm.coerceIn(20, 400)
        playerPage = PlayerPage.SHEET
    }

    fun leaveSheet() {
        player.stop(record = true)
        playerPage = PlayerPage.DETAIL
    }

    fun backToLibrary() {
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
        if (settings.virtualKeySound) midiOutId?.let { platform.midiOut.noteOn(it, midi, 80) } ?: platform.sound.play(midi)
        onKey(midi)
    }

    /** Finger lifted from an on-screen key: damp its sound. */
    fun onVirtualKeyUp(midi: Int) {
        midiOutId?.let { platform.midiOut.noteOff(it, midi) } ?: platform.sound.stop(midi)
        onKeyUp(midi)
    }

    /** A key was released (MIDI or on-screen). */
    fun onKeyUp(midi: Int) {
        recorder.note(midi, false)
        if (screen == Screen.PRACTICE && mode == AppMode.PLAYER && playerPage == PlayerPage.SHEET) player.onNoteOff(midi)
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
                if (mode == AppMode.PLAYER) { if (playerPage == PlayerPage.SHEET) player.onNote(midi) }
                else session.onNotePlayed(midi)
            }
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
