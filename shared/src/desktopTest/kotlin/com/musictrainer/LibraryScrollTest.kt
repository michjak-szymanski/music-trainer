package com.musictrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.data.FileStore
import com.musictrainer.data.Repository
import com.musictrainer.library.META_VERSION
import com.musictrainer.library.PieceMeta
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import com.musictrainer.model.AppMode
import com.musictrainer.ui.PlayerPracticeScreen
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The piece list scrolls with the mouse wheel (images in build/library-scroll-*.png). */
class LibraryScrollTest {
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun wheelScrollsThePieceList() {
        val store = MemoryStore()
        Repository(store).saveLibraryCache((1..40).map { PieceMeta("p$it", "Piece number $it", "piece$it.mid", 60_000, 100, 1, metaVersion = META_VERSION) })
        val platform = Platform("Test", FakeMidi(), store, System::currentTimeMillis, { "2026-11-06" })
        ImageComposeScene(1000, 700, Density(1f)) {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(platform, scope).also { it.selectMode(AppMode.PLAYER) } }
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { PlayerPracticeScreen(state) } }
        }.use { scene ->
            var t = 0L
            fun frame() = run { Thread.sleep(5); t += 16_000_000L; scene.render(t) }
            repeat(10) { frame() }
            val before = frame().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/library-scroll-before.png").writeBytes(before)
            repeat(10) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(500f, 400f), scrollDelta = Offset(0f, 3f))
                frame()
            }
            repeat(20) { frame() }
            val after = frame().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/library-scroll-after.png").writeBytes(after)
            assertTrue(!before.contentEquals(after), "the list did not move")
        }
    }
}
