package com.musictrainer

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
import com.musictrainer.model.Settings
import com.musictrainer.ui.App
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Mouse-wheel scrolling inside the full app window (images in build/app-scroll-*.png). */
class AppScrollTest {
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
    fun wheelScrollsHistoryInsideTheApp() {
        val store = MemoryStore()
        val repo = Repository(store)
        repo.saveSettings(Settings(appMode = AppMode.FEEDER))
        repo.saveHistory((1..40).map { com.musictrainer.model.SessionRecord(1_700_000_000_000 + it * 86_400_000L, 40, 10, 50, true, 36, 96, 10, false, 1500) })
        val platform = Platform("Test", FakeMidi(), store, System::currentTimeMillis, { "2026-11-06" })
        ImageComposeScene(1200, 800, Density(1f)) { App(platform) }.use { scene ->
            var t = 0L
            fun frame() = run { Thread.sleep(5); t += 16_000_000L; scene.render(t) }
            repeat(10) { frame() }
            // Open the History tab.
            scene.sendPointerEvent(PointerEventType.Press, Offset(660f, 24f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(660f, 24f))
            repeat(30) { frame() }
            val before = frame().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/app-scroll-before.png").writeBytes(before)
            scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 400f))
            repeat(10) { scene.sendPointerEvent(PointerEventType.Scroll, Offset(600f, 400f), scrollDelta = Offset(0f, 3f)); frame() }
            repeat(20) { frame() }
            val afterImage = frame()
            val after = afterImage.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/app-scroll-after.png").writeBytes(after)
            assertTrue(!before.contentEquals(after), "the list did not move")

            // Drag (finger / mouse) upwards: the list should scroll further down.
            scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 600f))
            for (y in 590 downTo 250 step 20) { scene.sendPointerEvent(PointerEventType.Move, Offset(600f, y.toFloat())); frame() }
            scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 250f))
            repeat(10) { frame() }
            val dragged = frame().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/app-scroll-dragged.png").writeBytes(dragged)
            assertTrue(!after.contentEquals(dragged), "dragging did not scroll")
        }
    }
}
