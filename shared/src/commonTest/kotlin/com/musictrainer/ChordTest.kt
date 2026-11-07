package com.musictrainer

import com.musictrainer.chords.Chord
import com.musictrainer.chords.ChordLevels
import com.musictrainer.chords.ChordQuality
import com.musictrainer.chords.ChordRecord
import com.musictrainer.chords.ChordSession
import com.musictrainer.chords.ChordSettings
import com.musictrainer.chords.EarLevels
import com.musictrainer.chords.EarStats
import com.musictrainer.chords.Progressions
import com.musictrainer.chords.Root
import com.musictrainer.chords.pickQuality
import com.musictrainer.chords.playsChord
import com.musictrainer.chords.voiceProgression
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChordTest {
    @Test
    fun chordsAreSpelledCorrectly() {
        assertEquals(listOf("E♭", "G", "B♭"), Chord(Root(2, -1), ChordQuality.MAJOR).noteNames)
        assertEquals(listOf("F♯", "A", "C"), Chord(Root(3, 1), ChordQuality.DIMINISHED).noteNames)
        assertEquals(listOf("B♭", "D", "F", "A♭"), Chord(Root(6, -1), ChordQuality.DOMINANT7).noteNames)
        assertEquals(listOf("C", "E", "G♯"), Chord(Root(0, 0), ChordQuality.AUGMENTED).noteNames)
        assertEquals("D♭m7♭5", Chord(Root(1, -1), ChordQuality.HALF_DIMINISHED).symbol)
    }

    @Test
    fun anyInversionCountsUnlessRootInBass() {
        val c = Chord(Root(0, 0), ChordQuality.MAJOR)
        val s = ChordSettings()
        assertTrue(playsChord(c, setOf(60, 64, 67), s))
        assertTrue(playsChord(c, setOf(52, 60, 67, 72), s)) // first inversion, doubled
        assertFalse(playsChord(c, setOf(52, 60, 67), s.copy(rootInBass = true)))
        assertFalse(playsChord(c, setOf(60, 64), s))
        assertFalse(playsChord(c, setOf(60, 64, 67, 70), s))
        val c7 = Chord(Root(4, 0), ChordQuality.DOMINANT7) // G B D F
        assertTrue(playsChord(c7, setOf(55, 59, 65), s)) // no fifth
        assertFalse(playsChord(c7, setOf(55, 59, 65), s.copy(allowOmitFifth = false)))
    }

    @Test
    fun chordSessionCountsFirstTriesAndMistakes() = runTest {
        val records = ArrayList<ChordRecord>()
        val s = ChordSession(this, { testScheduler.currentTime }) { records += it }
        s.start(ChordSettings(level = 3, questions = 2), seed = 3)
        // 1st: play it right away.
        val first = s.chord!!
        first.pitchClasses.forEach { s.onKeyDown(60 + it) }
        assertEquals(ChordSession.Phase.CORRECT, s.phase)
        first.pitchClasses.forEach { s.onKeyUp(60 + it) }
        advanceTimeBy(1000); runCurrent()
        // 2nd: a wrong key first.
        val second = s.chord!!
        val wrong = (0..11).first { it !in second.pitchClasses }
        s.onKeyDown(60 + wrong); s.onKeyUp(60 + wrong)
        second.pitchClasses.forEach { s.onKeyDown(48 + it) }
        assertEquals(ChordSession.Phase.CORRECT, s.phase)
        advanceTimeBy(1000); runCurrent()
        val r = records.single()
        assertEquals(1, r.firstTry); assertEquals(1, r.wrongKeys); assertEquals(2, r.chords)
    }

    @Test
    fun levelsGrow() {
        assertTrue(ChordLevels.all.zipWithNext().all { (a, b) -> a.qualities.size * a.roots.size <= b.qualities.size * b.roots.size || b.number == 8 })
        assertEquals(ChordQuality.entries.size, EarLevels.all.last().qualities.size) // the top level lists every chord
    }

    @Test
    fun weakChordTypesComeUpMoreOften() {
        val level = EarLevels.of(3)
        var stats = EarStats()
        repeat(20) { stats = stats.record(ChordQuality.DIMINISHED, false) }
        ChordQuality.entries.forEach { q -> repeat(20) { stats = stats.record(q, true) } }
        val r = Random(1)
        val focused = (1..2000).count { pickQuality(level, stats, true, r) == ChordQuality.DIMINISHED }
        val random = (1..2000).count { pickQuality(level, stats, false, r) == ChordQuality.DIMINISHED }
        assertTrue(focused > 1000, "focused $focused") // well above the uniform 25%
        assertTrue(random in 380..620, "random $random")
    }

    @Test
    fun progressionsAreVoicedLikeAPianist() {
        for (level in Progressions.levels) for (p in level.progressions) for (tonic in 43..54) {
            val chords = voiceProgression(p, tonic)
            assertEquals(p.chords.size, chords.size)
            for ((c, notes) in p.chords.zip(chords)) {
                val bass = notes.first()
                assertEquals((tonic + c.offset).mod(12), bass.mod(12), "bass is the root")
                assertTrue(bass in 36..47)
                val pcs = notes.drop(1).map { it.mod(12) }.toSet()
                assertEquals(c.quality.intervals.map { (tonic + c.offset + it).mod(12) }.toSet(), pcs, "${p.id} ${c.roman}")
                assertTrue(notes.drop(1).all { it in 53..77 })
            }
            // Right hand moves smoothly: no jumps of more than a sixth on average.
            chords.drop(1).map { it.drop(1) }.zipWithNext().forEach { (a, b) ->
                assertTrue(abs(a.average() - b.average()) <= 9, "${p.id}: $a -> $b")
            }
        }
    }
}
