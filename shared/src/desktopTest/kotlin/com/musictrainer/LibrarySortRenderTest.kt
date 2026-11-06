package com.musictrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.data.FileStore
import com.musictrainer.data.Repository
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import com.musictrainer.player.HandsMode
import com.musictrainer.player.LibrarySort
import com.musictrainer.player.PlayStyle
import com.musictrainer.player.PlayerRecord
import com.musictrainer.player.PlayerSettings
import com.musictrainer.ui.PlayerPracticeScreen
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Library sorted by "Last played" (image in build/library-sorted.png). */
class LibrarySortRenderTest {
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
    fun rendersSortedByLastPlayed() {
        val store = MemoryStore()
        val repo = Repository(store)
        fun rec(id: String, day: Int, hits: Int) =
            PlayerRecord(1_780_000_000_000 + day * 86_400_000L, id, id, hits, 100 - hits, 0, 100, true, 100, 100, PlayStyle.WAIT, HandsMode.BOTH, 60_000)
        repo.savePlayerHistory(listOf(rec("demo:scale", 3, 96), rec("demo:ode", 1, 80), rec("demo:ode", 2, 90)))
        repo.savePlayerSettings(PlayerSettings(librarySort = LibrarySort.LAST_PLAYED, librarySortDescending = true))
        val platform = Platform("Test", FakeMidi(), store, System::currentTimeMillis, { java.time.Instant.ofEpochMilli(it).toString().take(10) })
        ImageComposeScene(1100, 520, Density(1f)) {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(platform, scope) }
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { PlayerPracticeScreen(state) } }
        }.use { scene ->
            var image = scene.render(0)
            for (i in 1..10) { Thread.sleep(5); image = scene.render(i * 16_000_000L) }
            File("build/library-sorted.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
