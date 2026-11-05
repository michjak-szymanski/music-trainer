package com.musictrainer

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.data.FileStore
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import com.musictrainer.ui.App
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the whole app (fake MIDI + in-memory storage) to build/app-preview.png. */
class AppRenderTest {
    private class FakeMidi : MidiInput {
        override val devices = MutableStateFlow(listOf(MidiDeviceStatus("1", "Digital Piano")))
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
    fun rendersApp() {
        val platform = Platform("Test", FakeMidi(), MemoryStore(), System::currentTimeMillis, { "2026-11-05" })
        val out = File("build/app-preview.png")
        ImageComposeScene(1500, 950, Density(1.5f)) { App(platform) }.use { scene ->
            var image = scene.render(0)
            for (frame in 1..80) {
                Thread.sleep(10)
                image = scene.render(frame * 50_000_000L)
            }
            out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        assertTrue(out.length() > 0)
    }
}
