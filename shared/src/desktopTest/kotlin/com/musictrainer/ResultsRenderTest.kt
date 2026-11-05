package com.musictrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.model.SessionRecord
import com.musictrainer.ui.ResultsCard
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the end-of-session card (after its animations) to build/results-preview.png. */
class ResultsRenderTest {
    @Test
    fun rendersResultsCardWithStars() {
        val record = SessionRecord(0, 44, 6, 50, true, 36, 96, 10, false, 1350) // 88% -> 4 stars
        val out = File("build/results-preview.png")
        ImageComposeScene(700, 640, Density(1.5f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { ResultsCard(record) {} } }
        }.use { scene ->
            var image = scene.render(0)
            for (frame in 1..150) image = scene.render(frame * 16_000_000L) // ~2.4 s of animation
            out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        assertTrue(out.length() > 0)
    }
}
