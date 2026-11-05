package com.musictrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.model.Settings
import com.musictrainer.session.Phase
import com.musictrainer.session.SessionController
import com.musictrainer.ui.StaffView
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the staff after a few answers to an image (build/staff-preview.png) for visual checks. */
@OptIn(ExperimentalCoroutinesApi::class)
class StaffRenderTest {
    @Test
    fun rendersScrollingTrailWithClefChange() = runTest {
        val session = SessionController(this, { testScheduler.currentTime }) {}
        session.start(Settings(notesPerClef = 3, mistakePauseMs = 500))
        repeat(7) { i ->
            val target = session.note!!.midi
            session.onNotePlayed(if (i % 3 == 1) target + 2 else target)
            advanceTimeBy(1000)
            runCurrent()
        }
        session.onNotePlayed(session.note!!.midi + 1) // current note answered wrong
        assertEquals(7, session.trail.size)
        assertEquals(Phase.WRONG, session.phase)

        val out = File("build/staff-preview.png")
        ImageComposeScene(1600, 500, Density(1.5f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) {
                Surface { StaffView(session, Modifier.fillMaxSize()) }
            }
        }.use { scene ->
            var image = scene.render(0)
            for (frame in 1..120) {
                Thread.sleep(10) // let the font resource load
                image = scene.render(frame * 50_000_000L)
            }
            out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        assertTrue(out.length() > 0)
    }
}
