package com.musictrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.chords.EarSession
import com.musictrainer.chords.IntervalSession
import com.musictrainer.chords.PitchSession
import com.musictrainer.chords.ProgressionSession
import com.musictrainer.data.FileStore
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import com.musictrainer.model.AppMode
import com.musictrainer.rhythm.RhythmMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every trainer mode can be answered from a MIDI keyboard (keys sent through AppState.onKey). */
class TrainerMidiInputTest {
    private class FakeMidi : MidiInput {
        override val devices = MutableStateFlow(emptyList<MidiDeviceStatus>())
        override val error = MutableStateFlow<String?>(null)
        override val noteOns = MutableSharedFlow<Int>()
        override fun start() = Unit
        override fun rescan() = Unit
        override fun stop() = Unit
    }

    private class MemoryStore : FileStore {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, content: String) { files[name] = content }
    }

    /** Runs [body] with a live AppState (its coroutines need a composition's scope and frames). */
    private fun withState(body: (AppState, (Int) -> Unit) -> Unit) {
        val platform = Platform("Test", FakeMidi(), MemoryStore(), System::currentTimeMillis, { "today" })
        lateinit var state: AppState
        ImageComposeScene(400, 300, Density(1f)) {
            val scope = rememberCoroutineScope()
            state = remember { AppState(platform, scope) }
            MaterialTheme { Surface { Text("") } }
        }.use { scene ->
            var t = 0L
            val frames = { n: Int -> repeat(n) { Thread.sleep(5); t += 16_000_000L; scene.render(t) } }
            frames(2)
            body(state, frames)
        }
    }

    private fun key(state: AppState, midi: Int) { state.onKey(midi) }

    @Test
    fun backGoesOneStepUp() = withState { s, frames ->
        s.selectMode(AppMode.PLAYER)
        s.openPiece(s.library.builtIns.first()); Thread.sleep(200); frames(5)
        assertEquals(PlayerPage.DETAIL, s.playerPage)
        s.play(com.musictrainer.player.HandsMode.RIGHT)
        assertEquals(PlayerPage.SHEET, s.playerPage)
        s.showPieceView(PieceView.PROFESSOR)
        assertTrue(s.navigateBack()); assertEquals(PieceView.PIECE, s.pieceView)
        s.startLoopPick()
        assertTrue(s.navigateBack()); assertTrue(s.loopPick == null, "cancels picking first")
        assertTrue(s.navigateBack()); assertEquals(PlayerPage.DETAIL, s.playerPage)
        assertTrue(s.navigateBack()); assertEquals(PlayerPage.LIBRARY, s.playerPage)
        assertTrue(!s.navigateBack(), "nowhere further")
        s.screen = Screen.SETTINGS
        assertTrue(s.navigateBack()); assertEquals(Screen.PRACTICE, s.screen)
        s.selectMode(AppMode.CHORDS); s.startChords()
        assertTrue(s.navigateBack()); assertTrue(!s.chordStarted)
    }

    @Test
    fun perfectPitchAndIntervals() = withState { s, frames ->
        s.selectMode(AppMode.PERFECT_PITCH)
        s.updatePitchSettings(s.pitchSettings.copy(level = 6)); s.startPitch()
        key(s, s.pitch.target!!)
        assertEquals(PitchSession.Phase.RIGHT, s.pitch.phase)

        s.selectMode(AppMode.RELATIVE_PITCH)
        s.updateIntervalSettings(s.intervalSettings.copy(level = 6)); s.startIntervals()
        val iv = s.intervals.interval!!
        key(s, 60) // players often press C4 first …
        if (iv.semitones != 0) { key(s, 60 + iv.semitones); assertEquals(IntervalSession.Phase.RIGHT, s.intervals.phase) }
        else { Thread.sleep(800); frames(10); assertEquals(IntervalSession.Phase.RIGHT, s.intervals.phase) }
    }

    @Test
    fun chordIdentificationByPlayingAChord() = withState { s, frames ->
        s.selectMode(AppMode.CHORD_EAR)
        s.updateEarSettings(s.earSettings.copy(level = 7)); s.startEar()
        val q = s.ear.quality!!
        // The same type on another root, as a first inversion.
        val root = 62
        val notes = q.intervals.map { root + it }.let { it.drop(1) + (it.first() + 12) }
        notes.forEach { key(s, it) }
        Thread.sleep(500); frames(5)
        assertEquals(EarSession.Phase.RIGHT, s.ear.phase, "played $notes for ${q.longName}, answer ${s.ear.answer}")
    }

    @Test
    fun progressionsAndDictationByWhiteKeys() = withState { s, _ ->
        s.selectMode(AppMode.PROGRESSION_EAR)
        s.updateProgSettings(s.progSettings.copy(level = 2)); s.startProgressions()
        val i = s.progressions.level.progressions.indexOfFirst { it.id == s.progressions.progression!!.id }
        val whites = (60..96).filter { whiteKeyIndex(it) != null }
        key(s, whites[i])
        assertEquals(ProgressionSession.Phase.RIGHT, s.progressions.phase)

        s.selectMode(AppMode.RHYTHM_DICTATION)
        s.updateRhythmSettings(RhythmMode.DICTATION, s.rhythmSettings[RhythmMode.DICTATION]!!.copy(level = 6, bars = 1))
        s.startRhythm(RhythmMode.DICTATION)
        val d = s.dictation.drill!!
        val palette = s.dictation.level.palette(d.meter)
        for (e in d.events) key(s, whites[palette.indexOf(e.value)])
        assertTrue(s.dictation.result!!.allCorrect)
    }
}
