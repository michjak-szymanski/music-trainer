package com.musictrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.data.Repository
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

    var detectStep by mutableStateOf(DetectStep.OFF)
        private set
    private var detectFirst = 0

    /** Key currently flashed on the on-screen piano. */
    var pressedKey by mutableStateOf<Int?>(null)
        private set
    var pressCount by mutableIntStateOf(0)
        private set
    private var pressJob: Job? = null

    fun startFirstSession() = session.start(settings)

    fun updateSettings(new: Settings) {
        val sanitized = new.sanitized()
        val old = settings
        settings = sanitized
        repo.saveSettings(sanitized)
        if (old.practiceKey() != sanitized.practiceKey()) session.restart(sanitized)
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
    fun onVirtualKey(midi: Int) {
        if (settings.virtualKeySound) platform.sound.play(midi)
        onKey(midi)
    }

    /** Called for every key press, from MIDI or from the on-screen piano. */
    fun onKey(midi: Int) {
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
            DetectStep.OFF -> if (screen == Screen.PRACTICE) session.onNotePlayed(midi)
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
