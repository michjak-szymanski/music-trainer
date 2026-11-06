package com.musictrainer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.library.formatOf
import com.musictrainer.library.FORMAT_MUSESCORE
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.MuseScoreParser
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.ui.ScoreView
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders a local piece (PIECE_RENDER = path) to build/local-piece.png for debugging. Not part of normal runs. */
class LocalPieceRender {
    @Test
    fun render() {
        val f = System.getenv("PIECE_RENDER")?.let(::File) ?: return
        val score = if (formatOf(f.name) == FORMAT_MUSESCORE) MuseScoreParser.parse(f.readBytes(), f.nameWithoutExtension)
        else ScoreBuilder.build(MidiParser.parse(f.readBytes()), f.nameWithoutExtension)
        ImageComposeScene((System.getenv("PIECE_W") ?: "1400").toInt(), 800, Density((System.getenv("PIECE_D") ?: "1.3").toFloat())) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { ScoreView(score, 0, Modifier.fillMaxSize()) } }
        }.use { scene ->
            var image = scene.render(0)
            for (i in 1..30) { Thread.sleep(5); image = scene.render(i * 16_000_000L) }
            File("build/local-piece.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
