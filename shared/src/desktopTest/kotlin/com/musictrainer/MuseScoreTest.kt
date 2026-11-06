package com.musictrainer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.musictrainer.model.Clef
import com.musictrainer.notation.MuseScoreParser
import com.musictrainer.notation.Score
import com.musictrainer.notation.TICKS_PER_QUARTER
import com.musictrainer.ui.ScoreView
import com.musictrainer.ui.appColorScheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MuseScoreTest {
    private val mscx = javaClass.getResource("/feature-test.mscx")!!.readBytes()

    @Test
    fun readsNotationExactly() {
        val s = MuseScoreParser.parse(mscx, "fallback")
        assertEquals("Feature test", s.title)
        assertEquals(2, s.key.fifths)
        assertEquals(90, s.bpm)
        assertEquals(4, s.measures.size)
        assertEquals(TICKS_PER_QUARTER, s.measures[0].length) // pickup bar
        assertEquals(3 * TICKS_PER_QUARTER, s.measures[1].length)

        val m1 = s.measures[1].staves.getValue(Clef.TREBLE)
        val triplet = m1.take(3)
        assertTrue(triplet.all { it.tuplet?.actual == 3 && it.base == TICKS_PER_QUARTER / 2 && it.duration == TICKS_PER_QUARTER / 3 })
        assertEquals(listOf(0, 16, 32), triplet.map { it.start })
        assertEquals(0, m1[3].notes.single().accidental) // F♮ as engraved
        assertEquals(3 to 0, m1[3].notes.single().pitch.let { it.letter to it.alter })
        assertTrue(m1[4].notes.single().tieToNext)

        val m2 = s.measures[2].staves.getValue(Clef.TREBLE)
        assertEquals(setOf(0, 1), m2.map { it.voice }.toSet())
        assertTrue(m2.first { it.voice == 0 }.notes.single().tieFromPrev)

        val bass2 = s.measures[2].staves.getValue(Clef.BASS)
        assertEquals(listOf(null, Clef.TREBLE, null), bass2.map { it.clef })
        assertTrue(s.measures[3].staves.getValue(Clef.BASS).single().wholeMeasureRest)
        assertEquals(1, s.measures[3].staves.getValue(Clef.TREBLE).single().dots)

        // The tied E5 isn't played again; notes of both voices are steps.
        assertTrue(s.steps.none { it.time == s.measures[2].start && 76 in it.all })
        assertTrue(s.steps.any { it.time == s.measures[2].start + TICKS_PER_QUARTER && 81 in it.all && 69 in it.all })
    }

    @Test
    fun readsZippedMscz() {
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("META-INF/container.xml"))
                z.write("<container><rootfiles><rootfile full-path=\"score.mscx\"/></rootfiles></container>".toByteArray())
                z.putNextEntry(ZipEntry("score.mscx"))
                z.write(mscx)
            }
        }.toByteArray()
        assertEquals("Feature test", MuseScoreParser.parse(zip, "x").title)
    }

    private fun render(score: Score, name: String) {
        ImageComposeScene(1400, 700, Density(1f)) {
            MaterialTheme(colorScheme = appColorScheme(dark = false)) { Surface { ScoreView(score, 0, Modifier.fillMaxSize()) } }
        }.use { scene ->
            var image = scene.render(0)
            for (f in 1..30) { Thread.sleep(5); image = scene.render(f * 16_000_000L) }
            File("build/$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun rendersFeatureTest() = render(MuseScoreParser.parse(mscx, "x"), "mscx-feature")

    /** Local check against real MuseScore files in MSCX_SAMPLES, if set (not part of the repository). */
    @Test
    fun rendersLocalSamples() {
        val dir = System.getenv("MSCX_SAMPLES")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension == "mscx" || x.extension == "mscz" }.orEmpty()) {
            val s = MuseScoreParser.parse(f.readBytes(), f.nameWithoutExtension)
            println("SAMPLE ${f.name}: ${s.measures.size} bars, ${s.steps.size} steps, key ${s.key.fifths}")
            render(s, "mscx-sample-${f.nameWithoutExtension}")
        }
    }
}
