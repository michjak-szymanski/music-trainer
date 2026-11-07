package com.musictrainer

import com.musictrainer.analysis.Doctor
import com.musictrainer.analysis.Professor
import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.player.BarStat
import com.musictrainer.player.HandsMode
import com.musictrainer.player.NoteSink
import com.musictrainer.player.PlayStyle
import com.musictrainer.player.PlayerController
import com.musictrainer.player.PlayerRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisTest {
    private fun demo(id: String) = DemoPieces.all.first { it.id == id }.let { ScoreBuilder.build(MidiParser.parse(it.bytes), it.title) }

    @Test
    fun professorFindsTheFormOfOdeToJoy() {
        val a = Professor.analyze(demo("demo:ode"))
        demo("demo:ode").measures.last().let { m -> println("PROF lastbar " + m.staves.map { (k, v) -> "$k=" + v.map { e -> "${e.start}/${e.duration}:" + e.notes.map { it.pitch.midi } } }) }
        println("PROF form=${a.formString} repeats=${a.repeats.map { "${it.first}->${it.again}/${it.shift}" }} key=${a.keyName} harmony=${a.harmony}")
        println("PROF patterns=${a.patterns.map { "${it.hand}:${it.title}" }} hard=${a.hardSpots.map { it.bars }} plan=${a.plan.map { it.text }}")
        // Ode to Joy: the first phrase comes back (A … A).
        val letters = a.form.map { it.second.trimEnd('′') }
        assertTrue(letters.count { it == "A" } >= 2, "form ${a.formString}")
        assertTrue(a.repeats.isNotEmpty() || letters.distinct().size < letters.size)
        assertTrue(a.keyName.contains("major"), a.keyName)
        assertTrue(a.harmony.any { it.startsWith("I ") } && a.harmony.any { it.startsWith("V") }, "harmony ${a.harmony}")
        assertTrue(a.plan.isNotEmpty())
        assertTrue(a.difficulty in 1..5)
    }

    @Test
    fun instrumentNamesAreNotTitles() {
        for (n in listOf("CFX Concert Grand", "Grand Piano 1", "Piano RH", "Acoustic Grand", "Steinway D", "Strings", "MIDI 01", "Yamaha C7"))
            assertTrue(com.musictrainer.library.looksLikeInstrument(n), n)
        for (n in listOf("Für Elise", "Moonlight Sonata", "Canon in D", "Concerto in A minor", "Greensleeves", "The Godfather Theme"))
            assertTrue(!com.musictrainer.library.looksLikeInstrument(n), n)
    }

    @Test
    fun professorRunsOnEveryDemo() {
        for (d in DemoPieces.all) {
            val a = Professor.analyze(ScoreBuilder.build(MidiParser.parse(d.bytes), d.title))
            assertEquals(a.bars, a.harmony.size)
            assertTrue(a.form.sumOf { it.first.count() } == a.bars)
        }
    }

    private fun rec(t: Long, hands: HandsMode, hits: Int, wrong: Int, bars: Map<Int, BarStat> = emptyMap(), conf: Map<String, Int> = emptyMap()) =
        PlayerRecord(t, "p", "Piece", hits, wrong, 0, hits + wrong, true, 100, 100, PlayStyle.WAIT, hands, 60_000, bars = bars, confusions = conf)

    @Test
    fun doctorFindsProblemBarsAndConfusions() {
        val day = 86_400_000L
        val now = 100 * day
        val records = listOf(
            rec(now - 10 * day, HandsMode.BOTH, 10, 10), // outside 7 days
            rec(now - 3 * day, HandsMode.RIGHT, 90, 5, mapOf(4 to BarStat(5, 4, 1, 0), 5 to BarStat(6, 3, 0, 0), 1 to BarStat(20, 0, 0, 0)), mapOf("66>65" to 4)),
            rec(now - 2 * day, HandsMode.LEFT, 60, 30, mapOf(4 to BarStat(4, 5, 0, 1)), mapOf("66>65" to 2)),
            rec(now - 1 * day, HandsMode.BOTH, 70, 20, mapOf(9 to BarStat(10, 1, 0, 0))),
        )
        val r = Doctor.report(records, now - 7 * day, "p")
        assertEquals(3, r.sessions)
        assertEquals(4, r.problemBars.first().bar) // bar 5 (0-based 4) is the worst
        assertEquals(66 to 65, r.confusions.first().let { it.expected to it.played })
        assertEquals(6, r.confusions.first().count)
        assertTrue(r.suggestions.any { "F4 instead of F♯4" in it.text }, r.suggestions.map { it.text }.toString())
        assertTrue(r.suggestions.any { "left hand is weaker" in it.text }, r.suggestions.map { it.text }.toString())
        assertTrue(r.suggestions.any { it.loop != null && 4 in it.loop!! })
        assertEquals(0, Doctor.report(records, now + day, "p").sessions)
    }

    @Test
    fun doctorFindsThePatternsYouStruggleWith() {
        val score = demo("demo:ode")
        val a = Professor.analyze(score)
        val runs = a.patterns.first { it.title == "Scale runs" }
        val runBars = runs.bars.flatMap { it.toList() }.toSet()
        // Many mistakes in the scale-run bars, few elsewhere.
        val bars = (0 until a.bars).associateWith { b -> if (b in runBars) BarStat(6, 5, 1, 0) else BarStat(12, 0, 0, 0) }
        val r = Doctor.report(listOf(rec(1000, HandsMode.RIGHT, 80, 20, bars)), 0, "p")
        val weak = Doctor.weakPatterns(r, a)
        assertTrue(weak.any { it.finding.title == "Scale runs" && it.rate > it.elsewhere }, weak.map { it.finding.title }.toString())
    }

    private object Silent : NoteSink {
        override fun noteOn(midi: Int, velocity: Int) = Unit
        override fun noteOff(midi: Int) = Unit
    }

    @Test
    fun sessionsRecordMistakesPerBar() = runTest {
        val score = demo("demo:ode")
        val records = ArrayList<PlayerRecord>()
        val p = PlayerController(this, { testScheduler.currentTime }, { Silent }) { records += it }.apply { load(score, "ode"); countIn = false }
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT; p.checkTiming = false
        p.start(); runCurrent()
        val first = p.nextKeys.first()
        p.onNote(first + 1); runCurrent() // a wrong key in bar 1
        p.onNote(first); runCurrent()
        p.stop(record = true)
        val r = records.single()
        assertEquals(1, r.bars[0]?.wrong)
        assertEquals(1, r.bars[0]?.hits)
        assertEquals(1, r.confusions["$first>${first + 1}"])
    }
}
