package com.musictrainer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.musictrainer.model.NotationFont
import com.musictrainer.model.NotationStyle
import com.musictrainer.model.SheetColors
import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.ui.ScoreView
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The rhythm study in each music font / sheet colour combination (build/notation-styles.png). */
class NotationStyleRenderTest {
    @Test
    fun rendersEachStyle() {
        val demo = DemoPieces.all.first { it.id == "demo:rhythm" }
        val score = ScoreBuilder.build(MidiParser.parse(demo.bytes), demo.title)
        val styles = listOf(
            NotationStyle(NotationFont.BRAVURA, SheetColors.THEME),
            NotationStyle(NotationFont.LELAND, SheetColors.CONTRAST),
            NotationStyle(NotationFont.PETALUMA, SheetColors.PAPER),
        )
        ImageComposeScene(1300, 900, Density(1f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) {
                Surface {
                    Column {
                        for (st in styles) ScoreView(score, 0, Modifier.fillMaxWidth().height(300.dp), stripeOpacity = 0f, style = st)
                    }
                }
            }
        }.use { scene ->
            var image = scene.render(0)
            for (i in 1..40) { Thread.sleep(5); image = scene.render(i * 16_000_000L) }
            File("build/notation-styles.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
