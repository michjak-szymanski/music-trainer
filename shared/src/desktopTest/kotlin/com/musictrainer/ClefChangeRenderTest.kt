package com.musictrainer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.model.Clef
import com.musictrainer.model.Settings
import com.musictrainer.session.SessionController
import com.musictrainer.ui.StaffView
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Right after the clef switches, the staff shows only the new clef (build/clef-change.png). */
class ClefChangeRenderTest {
    @Test
    fun leadingClefFollowsCurrentNote() = runTest {
        val session = SessionController(this, { testScheduler.currentTime }) {}
        session.start(Settings(notesPerClef = 3))
        ImageComposeScene(1200, 400, Density(1f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) { Surface { StaffView(session, Modifier.fillMaxSize()) } }
        }.use { scene ->
            var t = 0L
            fun frame() = run { t += 16_000_000L; scene.render(t) }
            repeat(30) { Thread.sleep(5); frame() }
            val first = session.clef
            // Answer 3 notes (one wrong) so the clef switches.
            repeat(3) { i ->
                session.onNotePlayed(if (i == 1) session.note!!.midi + 1 else session.note!!.midi)
                repeat(10) { frame() }
                advanceTimeBy(2000); runCurrent()
                if (i == 1) { session.onNotePlayed(session.note!!.midi); repeat(10) { frame() }; advanceTimeBy(2000); runCurrent() }
            }
            assertEquals(if (first == Clef.TREBLE) Clef.BASS else Clef.TREBLE, session.clef)
            var img = frame(); repeat(60) { img = frame() }
            File("build/clef-change.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
