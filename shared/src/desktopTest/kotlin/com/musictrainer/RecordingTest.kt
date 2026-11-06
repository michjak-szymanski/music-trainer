package com.musictrainer

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
import com.musictrainer.player.HandsMode
import com.musictrainer.player.PlayStyle
import com.musictrainer.ui.PlayerPracticeScreen
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records a session through the real app state, then checks it was saved and can be played back. */
class RecordingTest {
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
        override fun delete(name: String) { files.remove(name) }
    }

    @Test
    fun recordsAndPlaysBack() {
        val store = MemoryStore()
        val platform = Platform("Test", FakeMidi(), store, System::currentTimeMillis, { "2026-11-06" })
        lateinit var state: AppState
        ImageComposeScene(1200, 800, Density(1f)) {
            val scope = rememberCoroutineScope()
            state = remember { AppState(platform, scope) }
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { PlayerPracticeScreen(state) } }
        }.use { scene ->
            var t = 0L
            fun frames(n: Int) = repeat(n) { Thread.sleep(4); t += 16_000_000L; scene.render(t) }
            state.selectMode(com.musictrainer.model.AppMode.PLAYER)
            state.openPiece(state.library.builtIns.first { it.id == "demo:ode" })
            frames(5)
            state.updatePlayerSettings(state.playerSettings.copy(playStyle = PlayStyle.WAIT, checkTiming = false, countdownSeconds = 0))
            state.play(HandsMode.RIGHT)
            state.recordEnabled = true
            state.player.start()
            frames(5)
            assertTrue(state.recording)
            var guard = 0
            while (state.player.running && guard++ < 200) {
                for (k in state.player.nextKeys) { state.onKey(k); state.onKeyUp(k) }
                frames(2)
            }
            frames(5)
            assertTrue(!state.recording, "running=${state.player.running} finished=${state.player.finished} step=${state.player.cursorStep}/${state.player.score?.steps?.size} guard=$guard next=${state.player.nextKeys} hits=${state.player.hits}")
            val rec = state.recordings.single()
            assertEquals("demo:ode", rec.pieceId)
            assertTrue(rec.notes >= 30, "right-hand melody plus the left hand played by the app: ${rec.notes}")
            assertTrue(store.files.containsKey(rec.fileName))
            val session = rec.session!!
            assertEquals(state.player.lastRecord, session)
            assertTrue(session.hits > 0 && session.score > 0)

            state.playRecording(rec)
            frames(3)
            assertEquals(rec.id, state.recordingPlayer.playingId)
            state.recordingPlayer.stop()
            state.deleteRecording(rec)
            assertTrue(state.recordings.isEmpty() && !store.files.containsKey(rec.fileName))
        }
    }
}
