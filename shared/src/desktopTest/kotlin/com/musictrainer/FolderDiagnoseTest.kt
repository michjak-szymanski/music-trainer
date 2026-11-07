package com.musictrainer

import com.musictrainer.library.FORMAT_MUSESCORE
import com.musictrainer.library.formatOf
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.MuseScoreParser
import java.io.File
import kotlin.test.Test
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.use

/** Diagnostic: why files in a real folder fail to load (set -Ddiag.folder=...). Prints only. */
class FolderDiagnoseTest {
    @Test
    fun diagnose() {
        val dir = File(System.getenv("DIAG_FOLDER") ?: return)
        dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in com.musictrainer.library.MusicFiles.SUPPORTED_EXTENSIONS }.forEach { f ->
            val r = runCatching { com.musictrainer.library.pieceMeta(f.path, f.name, 0, f.readBytes()).let { "\"${it.title}\" ${it.notes} bpm=" + (if (f.extension == "mscz") com.musictrainer.notation.MuseScoreParser.parse(f.readBytes(), "x").bpm else 0) } }
            println("DIAG ${if (r.isSuccess) "OK  " else "FAIL"} ${f.relativeTo(dir)} :: ${r.getOrNull() ?: r.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message} @ ${it.stackTrace.firstOrNull { s -> s.className.startsWith("com.musictrainer") }}" }}")
        }
    }

    @Test
    fun renderOne() {
        val f = File(System.getenv("DIAG_RENDER") ?: return)
        val score = MuseScoreParser.parse(f.readBytes(), f.nameWithoutExtension)
        androidx.compose.ui.ImageComposeScene(1300, 700, androidx.compose.ui.unit.Density(1f)) {
            androidx.compose.material3.MaterialTheme(colorScheme = com.musictrainer.ui.appColorScheme(dark = true)) {
                androidx.compose.material3.Surface { com.musictrainer.ui.ScoreView(score, 0, androidx.compose.ui.Modifier.fillMaxSize(), stripeOpacity = 0f) }
            }
        }.use { scene ->
            var image = scene.render(0)
            for (i in 1..40) { Thread.sleep(5); image = scene.render(i * 16_000_000L) }
            File("build/diag-render.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
