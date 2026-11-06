package com.musictrainer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import com.musictrainer.ui.NotationStyleSection
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The Settings "Notation style" section with its live preview (build/style-preview.png). */
class StylePreviewRenderTest {
    @Test
    fun rendersSectionWithPreview() {
        ImageComposeScene(900, 520, Density(1f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) {
                Surface { Column(Modifier.padding(16.dp)) { NotationStyleSection(NotationStyle(NotationFont.PETALUMA, SheetColors.PAPER)) {} } }
            }
        }.use { scene ->
            var image = scene.render(0)
            for (i in 1..40) { Thread.sleep(5); image = scene.render(i * 16_000_000L) }
            File("build/style-preview.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
