package com.musictrainer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.musictrainer.ui.HoverHint
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Hovering a counter shows its explanation (image in build/hover-hint.png). */
class HoverHintTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun showsTooltipOnHover() {
        ImageComposeScene(500, 240, Density(1f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(top = 150.dp, start = 40.dp)) {
                        HoverHint("Rhythm errors: chords played clearly early or late.") { Text("Rhythm 12") }
                    }
                }
            }
        }.use { scene ->
            var t = 0L
            fun frames(n: Int) = (1..n).map { Thread.sleep(5); t += 16_000_000L; scene.render(t) }.last()
            frames(5)
            scene.sendPointerEvent(PointerEventType.Enter, Offset(60f, 158f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(62f, 160f))
            File("build/hover-hint.png").writeBytes(frames(60).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
