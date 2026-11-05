package com.musictrainer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.musictrainer.ui.Piano
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the on-screen keyboard without and with labels to build/piano-preview.png. */
class PianoRenderTest {
    @Test
    fun rendersKeyLabels() {
        val out = File("build/piano-preview.png")
        ImageComposeScene(1400, 330, Density(1.5f)) {
            Column {
                Piano(36, 96, emptyMap(), {}, Modifier.fillMaxWidth().height(100.dp).padding(bottom = 6.dp), showNames = false, showOctaves = true)
                Piano(36, 96, emptyMap(), {}, Modifier.fillMaxWidth().height(110.dp), showNames = true, showOctaves = false)
            }
        }.use { scene ->
            out.writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        assertTrue(out.length() > 0)
    }
}
