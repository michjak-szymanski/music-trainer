package com.musictrainer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.ui.ScoreView
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders every demo piece to build/score-<id>.png for visual checks of the engraving. */
class ScoreRenderTest {
    @Test
    fun rendersDemoPieces() {
        for (demo in DemoPieces.all) {
            val score = ScoreBuilder.build(MidiParser.parse(demo.bytes), demo.title)
            val out = File("build/score-${demo.id.removePrefix("demo:")}.png")
            ImageComposeScene(1400, 900, Density(1f)) {
                MaterialTheme(colorScheme = appColorScheme(dark = false)) {
                    Surface { ScoreView(score, currentStep = 0, modifier = Modifier.fillMaxSize()) }
                }
            }.use { scene ->
                var image = scene.render(0)
                for (frame in 1..40) { Thread.sleep(5); image = scene.render(frame * 16_000_000L) }
                out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            assertTrue(out.length() > 0)
        }
    }
}
