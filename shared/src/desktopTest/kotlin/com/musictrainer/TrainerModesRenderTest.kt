package com.musictrainer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.data.FileStore
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import com.musictrainer.model.AppMode
import com.musictrainer.rhythm.RhythmMode
import com.musictrainer.ui.App
import com.musictrainer.ui.ChordEarScreen
import com.musictrainer.ui.ChordFinderScreen
import com.musictrainer.ui.IntervalScreen
import com.musictrainer.ui.PitchScreen
import com.musictrainer.ui.ProgressionScreen
import com.musictrainer.ui.RhythmPracticeScreen
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders every new mode mid-session (build/mode-*.png) through the real app state. */
class TrainerModesRenderTest {
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

    private fun render(name: String, w: Int = 1100, h: Int = 800, setup: (AppState) -> Unit, act: (AppState) -> Unit = {}, screen: @androidx.compose.runtime.Composable (AppState) -> Unit) {
        val platform = Platform("Test", FakeMidi(), MemoryStore(), System::currentTimeMillis, { "2026-11-07" })
        lateinit var state: AppState
        ImageComposeScene(w, h, Density(1f)) {
            val scope = rememberCoroutineScope()
            state = remember { AppState(platform, scope).also(setup) }
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { screen(state) } }
        }.use { scene ->
            var t = 0L
            var img = scene.render(0)
            repeat(30) { Thread.sleep(5); t += 16_000_000L; img = scene.render(t) }
            act(state)
            repeat(40) { Thread.sleep(5); t += 16_000_000L; img = scene.render(t) }
            File("build/mode-$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun rhythmModes() {
        render("dictation-start", setup = { it.selectMode(AppMode.RHYTHM_DICTATION) }) { RhythmPracticeScreen(it, RhythmMode.DICTATION) }
        render("dictation", setup = {
            it.selectMode(AppMode.RHYTHM_DICTATION)
            it.updateRhythmSettings(RhythmMode.DICTATION, it.rhythmSettings[RhythmMode.DICTATION]!!.copy(level = 9, bars = 2))
            it.startRhythm(RhythmMode.DICTATION)
        }, act = { s ->
            s.dictation.playback.stop() // writing is blocked during the count-in
            val d = s.dictation.drill!!
            // Write the first bar right and stop part-way.
            d.events.takeWhile { it.end <= d.meter.measureLength }.forEach { e -> s.dictation.write(e.value) }
        }) { RhythmPracticeScreen(it, RhythmMode.DICTATION) }
        render("dictation-result", setup = {
            it.selectMode(AppMode.RHYTHM_DICTATION)
            it.updateRhythmSettings(RhythmMode.DICTATION, it.rhythmSettings[RhythmMode.DICTATION]!!.copy(level = 6, bars = 1))
            it.startRhythm(RhythmMode.DICTATION)
        }, act = { s ->
            s.dictation.playback.stop() // writing is blocked during the count-in
            val d = s.dictation.drill!!
            while (s.dictation.result == null) {
                val v = s.dictation.level.palette(d.meter).first { s.dictation.canWrite(it) && !it.rest }
                s.dictation.write(v)
            }
            assertTrue(s.dictation.result != null)
        }) { RhythmPracticeScreen(it, RhythmMode.DICTATION) }
        render("dictation-wrong", setup = {
            it.selectMode(AppMode.RHYTHM_DICTATION)
            it.updateRhythmSettings(RhythmMode.DICTATION, it.rhythmSettings[RhythmMode.DICTATION]!!.copy(level = 6, bars = 1))
            it.startRhythm(RhythmMode.DICTATION)
        }, act = { s ->
            s.dictation.playback.stop() // writing is blocked during the count-in
            val d = s.dictation.drill!!
            // Deliberately wrong: start with a value the drill doesn't start with, then fill up with quarters.
            val first = s.dictation.level.palette(d.meter).first { !it.rest && it != d.events.first().value && s.dictation.canWrite(it) && it.ticks <= 48 }
            s.dictation.write(first)
            while (s.dictation.result == null) s.dictation.write(s.dictation.level.palette(d.meter).first { s.dictation.canWrite(it) && !it.rest })
            assertTrue(s.dictation.result?.allCorrect == false)
        }) { RhythmPracticeScreen(it, RhythmMode.DICTATION) }
        render("imitation", setup = {
            it.selectMode(AppMode.RHYTHM_IMITATION)
            it.updateRhythmSettings(RhythmMode.IMITATION, it.rhythmSettings[RhythmMode.IMITATION]!!.copy(level = 8))
            it.startRhythm(RhythmMode.IMITATION)
        }) { RhythmPracticeScreen(it, RhythmMode.IMITATION) }
        render("rhythm-play", setup = {
            it.selectMode(AppMode.RHYTHM_PLAY)
            it.updateRhythmSettings(RhythmMode.PLAY, it.rhythmSettings[RhythmMode.PLAY]!!.copy(level = 11, signatures = listOf("6/8")))
            it.startRhythm(RhythmMode.PLAY)
        }) { RhythmPracticeScreen(it, RhythmMode.PLAY) }
    }

    @Test
    fun chordAndEarModes() {
        render("chord-finder", setup = { it.selectMode(AppMode.CHORDS); it.updateChordSettings(it.chordSettings.copy(level = 6)); it.startChords() },
            act = { s -> val c = s.chords.chord!!; s.onKey(60 + (c.root.pc + 1) % 12) }) { ChordFinderScreen(it) }
        render("chord-finder-clicks", setup = { it.selectMode(AppMode.CHORDS); it.updateChordSettings(it.chordSettings.copy(level = 2)); it.startChords() },
            act = { s ->
                // Click the chord's notes one by one on the on-screen keyboard: they stay down and the chord counts.
                val c = s.chords.chord!!
                c.pitchClasses.forEach { pc -> s.toggleLatchedKey(60 + pc) }
                assertTrue(s.chords.phase == com.musictrainer.chords.ChordSession.Phase.CORRECT, "held ${s.chords.held}")
            }) { ChordFinderScreen(it) }
        render("chord-ear", setup = { it.selectMode(AppMode.CHORD_EAR); it.updateEarSettings(it.earSettings.copy(level = 6)); it.startEar() },
            act = { s -> s.ear.choose(s.ear.level.qualities.first { q -> q != s.ear.quality }) }) { ChordEarScreen(it) }
        render("progression", setup = { it.selectMode(AppMode.PROGRESSION_EAR); it.updateProgSettings(it.progSettings.copy(level = 4)); it.startProgressions() }) { ProgressionScreen(it) }
        render("intervals", setup = { it.selectMode(AppMode.RELATIVE_PITCH); it.updateIntervalSettings(it.intervalSettings.copy(level = 7)); it.startIntervals() },
            act = { s -> s.intervals.choose(s.intervals.interval!!) }) { IntervalScreen(it) }
        render("pitch", setup = { it.selectMode(AppMode.PERFECT_PITCH); it.updatePitchSettings(it.pitchSettings.copy(level = 9)); it.startPitch() },
            act = { s -> s.pitch.choose(s.pitch.target!! + 2) }) { PitchScreen(it) }
        render("pitch-octave", setup = { it.selectMode(AppMode.PERFECT_PITCH); it.updatePitchSettings(it.pitchSettings.copy(level = 1)); it.startPitch() },
            act = { s -> s.pitch.choose(if (s.pitch.target!! >= 60) s.pitch.target!! - 12 else s.pitch.target!! + 12) }) { PitchScreen(it) }
        render("player-loops", w = 1300, setup = {
            it.selectMode(AppMode.PLAYER)
            it.openPiece(it.library.builtIns.first { p -> p.id == "demo:ode" })
        }, act = { s ->
            s.updatePlayerSettings(s.playerSettings.copy(countdownSeconds = 0))
            s.play(com.musictrainer.player.HandsMode.RIGHT)
            s.addLoop(1, 2); s.addLoop(5, 8, "Tricky bit")
            s.jumpToLoop(s.loopsOf(s.player.pieceId).last())
        }) { com.musictrainer.ui.PlayerPracticeScreen(it) }
        render("professor", w = 1400, h = 900, setup = {
            it.selectMode(AppMode.PLAYER)
            it.openPiece(it.library.builtIns.first { p -> p.id == "demo:ode" })
            it.showPieceView(com.musictrainer.PieceView.PROFESSOR)
        }) { st -> com.musictrainer.ui.PlayerPracticeScreen(st) }
        render("doctor", w = 1400, h = 900, setup = { st ->
            st.selectMode(AppMode.PLAYER)
            val now = System.currentTimeMillis()
            fun r(t: Long, h: com.musictrainer.player.HandsMode, hits: Int, wrong: Int, bars: Map<Int, com.musictrainer.player.BarStat>) =
                com.musictrainer.player.PlayerRecord(t, "demo:ode", "Ode to Joy", hits, wrong, 2, hits + wrong, true, 100, 100,
                    com.musictrainer.player.PlayStyle.WAIT, h, 90_000, bars = bars + (0..7).filter { it !in bars }.associateWith { com.musictrainer.player.BarStat(12, 0, 0, 0) },
                    confusions = mapOf("66>65" to 5), stepMistakes = mapOf(16 to 3, 17 to 1, 20 to 2, 22 to 1),
                    wrongAt = mapOf("17:65" to 3, "20:65" to 2, "16:62" to 1))
            st.playerHistory.addAll(listOf(
                r(now - 3 * 86_400_000L, com.musictrainer.player.HandsMode.RIGHT, 60, 4, mapOf(4 to com.musictrainer.player.BarStat(6, 3, 1, 0))),
                r(now - 86_400_000L, com.musictrainer.player.HandsMode.BOTH, 50, 14, mapOf(4 to com.musictrainer.player.BarStat(4, 4, 1, 1), 5 to com.musictrainer.player.BarStat(5, 3, 0, 0))),
            ))
            st.openPiece(st.library.builtIns.first { p -> p.id == "demo:ode" })
        }) { st -> com.musictrainer.ui.DoctorScreen(st) }
        render("doctor-old", w = 1400, h = 700, setup = { st ->
            st.selectMode(AppMode.PLAYER)
            st.playerHistory.add(com.musictrainer.player.PlayerRecord(System.currentTimeMillis() - 3600_000, "demo:ode", "Ode to Joy", 1, 7, 0, 8, false, 100, 100,
                com.musictrainer.player.PlayStyle.WAIT, com.musictrainer.player.HandsMode.BOTH, 30_000))
            st.openPiece(st.library.builtIns.first { p -> p.id == "demo:ode" })
        }) { st -> com.musictrainer.ui.DoctorScreen(st) }
        render("player-marks", w = 1300, setup = {
            it.selectMode(AppMode.PLAYER)
            it.openPiece(it.library.builtIns.first { p -> p.id == "demo:ode" })
        }, act = { s ->
            s.updatePlayerSettings(s.playerSettings.copy(countdownSeconds = 0))
            s.play(com.musictrainer.player.HandsMode.RIGHT)
            s.addMark(s.player.pieceId, 3, "Second phrase", bookmark = true)
            // A note on one note, chosen by clicking it.
            s.startFragmentPick(bookmark = false); s.pickStep(18); s.saveFragment("Keep the wrist loose")
            assertTrue(s.marksOf(s.player.pieceId).any { it.isFragment && it.fromStep == 18 && it.toStep == 18 })
            s.addLoop(5, 6)
            assertTrue(s.loopsOf(s.player.pieceId).all { it.enabled }, "new loops start on")
            s.updateLoop(s.loopsOf(s.player.pieceId).first().copy(enabled = true))
            // Listening (or playing again) keeps the loop on, and the player repeats it.
            s.play(com.musictrainer.player.HandsMode.NONE)
            assertTrue(s.loopsOf(s.player.pieceId).first().enabled, "Listen keeps the loop on")
            assertTrue(s.player.loops.isNotEmpty(), "the loop reaches the player in listen mode")
            s.play(com.musictrainer.player.HandsMode.RIGHT)
            // "Hear it": the loop plays in Listen mode, then back to the right hand at the same place.
            s.listenToLoop(s.loopsOf(s.player.pieceId).first())
            assertTrue(s.player.running && s.player.hands == com.musictrainer.player.HandsMode.NONE)
            s.player.switchHands(com.musictrainer.player.HandsMode.RIGHT)
            assertTrue(s.player.hands == com.musictrainer.player.HandsMode.RIGHT)
            s.player.stop(record = false)
            // Only opening a piece again switches its loops off.
            s.updateLoop(s.loopsOf(s.player.pieceId).first().copy(enabled = false))
            // A loop chosen on the sheet: click its first bar, then its last.
            s.startLoopPick()
            val st = s.player.score!!.steps
            s.pickLoopStep(st.indexOfFirst { it.measure == 5 }) // the start…
            assertTrue(s.loopEditId == null, "the editor waits for the end")
            s.pickLoopStep(st.indexOfLast { it.measure == 6 }) // …then the end: the loop is made, the editor opens
            assertTrue(s.loopEditId != null)
            assertTrue(s.loopsOf(s.player.pieceId).any { it.startBar == 6 && it.endBar == 7 && it.enabled })
            // Note by note: the start one note earlier, the end one later, the whole loop back two notes.
            val r0 = s.stepsOf(s.loopsOf(s.player.pieceId).first { it.id == s.loopEditId })!!
            s.nudgeLoop(AppState.LoopPart.START, -1); s.nudgeLoop(AppState.LoopPart.END, 1); s.nudgeLoop(AppState.LoopPart.WHOLE, -2)
            val r1 = s.stepsOf(s.loopsOf(s.player.pieceId).first { it.id == s.loopEditId })!!
            assertTrue(r1.first == r0.first - 3 && r1.last == r0.last - 1, "$r0 -> $r1")
            s.nudgeLoop(AppState.LoopPart.WHOLE, -1000) // stops at the first note
            assertTrue(s.stepsOf(s.loopsOf(s.player.pieceId).first { it.id == s.loopEditId })!!.first == 0)
            s.doneLoopEdit()
            // Cancel: a new loop disappears; an edited one gets its bars back.
            val before = s.loopsOf(s.player.pieceId).size
            s.startLoopPick(); s.pickLoopStep(30); s.pickLoopStep(31); s.cancelLoopEdit()
            assertTrue(s.loopsOf(s.player.pieceId).size == before, "cancelled new loop removed")
            val kept = s.loopsOf(s.player.pieceId).first()
            s.editLoop(kept.id); s.nudgeLoop(AppState.LoopPart.WHOLE, 1); s.cancelLoopEdit()
            assertTrue(s.loopsOf(s.player.pieceId).first { it.id == kept.id } == kept, "cancelled edit restored")
            s.startLoopPick(); s.pickLoopStep(5) // left half-way: the start is set, the banner asks for the end
        }) { com.musictrainer.ui.PlayerPracticeScreen(it) }
        render("menu", setup = { it.selectMode(AppMode.RHYTHM_PLAY) }) { App(it.platform) }
    }
}
