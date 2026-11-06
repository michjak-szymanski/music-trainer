package com.musictrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Renders the Note player's library, piece page and sheet (mid-play) to build/player-*.png. */
class PlayerRenderTest {
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

    @Test
    fun rendersPlayerPages() {
        val platform = Platform("Test", FakeMidi(), MemoryStore(), System::currentTimeMillis, { "2026-11-06 10:00" })
        lateinit var state: AppState
        ImageComposeScene(1400, 950, Density(1.2f)) {
            val scope = rememberCoroutineScope()
            state = androidx.compose.runtime.remember { AppState(platform, scope) }
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { PlayerPracticeScreen(state) } }
        }.use { scene ->
            var t = 0L
            fun frames(n: Int) = (1..n).map { Thread.sleep(4); t += 16_000_000L; scene.render(t) }.last()
            fun save(name: String, n: Int = 40) = File("build/player-$name.png").writeBytes(frames(n).encodeToData(EncodedImageFormat.PNG)!!.bytes)

            save("library")
            state.openPiece(state.library.builtIns.first { it.id == "demo:rhythm" })
            frames(10)
            assertEquals(PlayerPage.DETAIL, state.playerPage)
            save("detail")

            state.updatePlayerSettings(state.playerSettings.copy(playStyle = PlayStyle.WAIT))
            state.play(HandsMode.BOTH)
            state.player.start()
            frames(5)
            // Play the first few chords, one wrong note.
            repeat(4) {
                state.player.nextKeys.forEach(state.player::onNote)
                frames(3)
            }
            state.player.onNote(76) // wrong: E5 instead of D5
            save("sheet", n = 20)
        }
    }
}
