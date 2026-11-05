package com.musictrainer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.model.Settings
import com.musictrainer.session.SessionController
import com.musictrainer.ui.StaffView
import com.musictrainer.ui.appColorScheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for notes jumping left for a frame when the staff scrolls: the answered
 * (green) note must slide left smoothly, never jump ahead and back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollSmoothnessTest {
    private fun leftmostGreenX(image: Image): Int? {
        val bmp = Bitmap.makeFromImage(image)
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
            val c = bmp.getColor(x, y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (g > 130 && r < 90 && b < 120) return x
        }
        return null
    }

    @Test
    fun answeredNoteSlidesLeftWithoutJumping() = runTest {
        val session = SessionController(this, { testScheduler.currentTime }) {}
        session.start(Settings(notesPerClef = 50))
        ImageComposeScene(1200, 400, Density(1f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = true)) {
                Surface { StaffView(session, Modifier.fillMaxSize()) }
            }
        }.use { scene ->
            var t = 0L
            fun frame(): Image { t += 16_000_000L; return scene.render(t) }
            repeat(30) { Thread.sleep(5); frame() } // fonts + entrance animation settle

            session.onNotePlayed(session.note!!.midi) // correct -> green note
            repeat(50) { frame() } // correct-answer animation (650 ms) finishes
            val before = leftmostGreenX(frame())!!

            advanceTimeBy(800); runCurrent() // session moves on to the next note
            val xs = (1..40).map { leftmostGreenX(frame())!! }

            // First frame after the change must start where the note was, not one step further left.
            assertTrue(xs.first() >= before - 25, "jumped: before=$before first=${xs.first()} all=$xs")
            // And it only ever moves left.
            assertTrue(xs.zipWithNext().all { (a, b) -> b <= a + 1 }, "not monotonic: $xs")
            assertTrue(xs.last() < before - 60, "did not scroll: before=$before after=${xs.last()}")
        }
    }
}
