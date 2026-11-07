package com.musictrainer

import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.MidiWriter
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.player.HandsMode
import com.musictrainer.player.NoteSink
import com.musictrainer.player.PlayStyle
import com.musictrainer.player.PlayerController
import com.musictrainer.player.PlayerRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTest {
    // RH: four quarters C5 D5 E5 F5; LH: two halves C3 G3. 120 bpm -> a quarter is 500 ms.
    private val piece = ScoreBuilder.build(
        MidiParser.parse(
            MidiWriter.write(
                listOf(DemoPieces.voice("C5:4 D5:4 E5:4 F5:4"), DemoPieces.voice("C3:8 G3:8")), bpm = 120,
            )
        ), "test",
    )

    private class Sink : NoteSink {
        val on = ArrayList<Int>()
        override fun noteOn(midi: Int, velocity: Int) { on += midi }
        override fun noteOff(midi: Int) = Unit
    }

    private fun TestScope.player(sink: Sink, records: MutableList<PlayerRecord>) =
        PlayerController(this, { testScheduler.currentTime }, { sink }) { records += it }.apply {
            load(piece, "test"); countIn = false
        }

    @Test
    fun waitModeAdvancesOnlyWhenTheChordIsComplete() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.WAIT
        p.start(); runCurrent()
        assertEquals(setOf(72, 48), p.nextKeys)
        p.onNote(72); assertEquals(0, p.cursorStep) // half the chord
        p.onNote(61); assertEquals(1, p.wrong) // wrong key
        p.onNote(48); runCurrent()
        assertEquals(1, p.currentStep) // next chord can be played right away
        assertEquals(0, p.cursorStep) // while the stripe glides over the quarter note
        advanceTimeBy(520); runCurrent()
        assertEquals(1, p.cursorStep)
        for (m in listOf(74, 76, 55, 77)) { p.onNote(m); runCurrent() }
        advanceUntilIdle()
        assertTrue(p.finished)
        assertEquals(6, records.single().hits)
        assertEquals(1, records.single().wrong)
    }

    @Test
    fun recordsSetAndActualTempoAsPercentOfOriginal() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT; p.checkTiming = false
        p.bpm = 60 // half the file's 120
        p.start(); runCurrent()
        // One quarter per second = 60 BPM = 50% of the original tempo.
        for (m in listOf(72, 74, 76, 77)) { p.onNote(m); runCurrent(); if (m != 77) { advanceTimeBy(1000); runCurrent() } }
        advanceUntilIdle()
        val r = records.single()
        assertEquals(50, r.setTempoPercent)
        assertTrue(r.actualTempoPercent in 45..55, "actual ${r.actualTempoPercent}%")
    }

    // Two bars of quarters (right hand only) for the loop tests.
    private val twoBars = ScoreBuilder.build(
        MidiParser.parse(MidiWriter.write(listOf(DemoPieces.voice("C5:4 D5:4 E5:4 F5:4 G5:4 A5:4 B5:4 C6:4")), bpm = 120)), "two bars",
    )

    @Test
    fun waitModeLoopsBackToTheLoopStart() = runTest {
        val p = PlayerController(this, { testScheduler.currentTime }, { Sink() }) {}.apply { load(twoBars, "two"); countIn = false }
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT; p.checkTiming = false
        p.loops = listOf(0..3) // repeat bar 1 (its four notes)
        p.start(); runCurrent()
        repeat(2) { round ->
            for (m in listOf(72, 74, 76, 77)) { assertEquals(setOf(m), p.nextKeys, "round $round"); p.onNote(m); runCurrent(); advanceTimeBy(500); runCurrent() }
            advanceTimeBy(600); runCurrent()
            assertEquals(setOf(72), p.nextKeys, "back at the loop start, not on to bar 2")
        }
        assertEquals(2, p.loopRepeats)
        assertTrue(p.running)
        p.loops = emptyList() // looping off: plays on into bar 2
        for (m in listOf(72, 74, 76, 77)) { p.onNote(m); runCurrent(); advanceTimeBy(500); runCurrent() }
        assertEquals(setOf(79), p.nextKeys)
    }

    @Test
    fun tempoModeLoopsOnTime() = runTest {
        val p = PlayerController(this, { testScheduler.currentTime }, { Sink() }) {}.apply { load(twoBars, "two"); countIn = false }
        p.style = PlayStyle.FOLLOW_TEMPO; p.hands = HandsMode.RIGHT
        p.loops = listOf(4..7) // repeat bar 2
        p.start(); runCurrent()
        advanceTimeBy(2000 + 2000 * 2 + 300); runCurrent() // bar 1, then bar 2 three times
        assertTrue(p.running, "still playing")
        assertEquals(2, p.loopRepeats)
        assertTrue(twoBars.steps[p.cursorStep].measure == 1, "cursor stays in bar 2: ${p.cursorStep}")
        p.stop(record = false)
        advanceUntilIdle()
    }

    @Test
    fun switchingHandsKeepsThePlace() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = PlayerController(this, { testScheduler.currentTime }, { Sink() }) { records += it }.apply { load(twoBars, "two"); countIn = false; countdownSeconds = 5 }
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT; p.checkTiming = false
        // Stopped: the start point stays.
        p.seek(5); p.switchHands(HandsMode.BOTH); assertEquals(5, p.startStep); assertEquals(HandsMode.BOTH, p.hands)
        p.switchHands(HandsMode.RIGHT)
        p.seek(0)
        p.start(); advanceTimeBy(5100); runCurrent() // countdown
        for (m in listOf(72, 74)) { p.onNote(m); runCurrent(); advanceTimeBy(600); runCurrent() }
        val at = p.cursorStep
        p.switchHands(HandsMode.NONE) // to Listen while playing
        runCurrent()
        assertTrue(p.running && p.hands == HandsMode.NONE)
        assertEquals(at, p.cursorStep, "carries on from the same note")
        assertTrue(p.countdown == null, "no countdown when switching")
        assertEquals(1, records.size) // the right-hand part so far was saved
        p.stop(record = false)
    }

    @Test
    fun listenModeLoopsToo() = runTest {
        val p = PlayerController(this, { testScheduler.currentTime }, { Sink() }) {}.apply { load(twoBars, "two"); countIn = false }
        p.hands = HandsMode.NONE
        p.loops = listOf(0..3) // bar 1
        p.start(); runCurrent()
        advanceTimeBy(2000 * 2 + 500); runCurrent()
        assertTrue(p.running, "still playing")
        assertTrue(p.loopRepeats >= 2, "repeats ${p.loopRepeats}")
        assertTrue(twoBars.steps[p.cursorStep].measure == 0, "stays in bar 1: ${p.cursorStep}")
        p.stop(record = false)
    }

    @Test
    fun rightHandOnlyLetsTheAppPlayTheLeftHand() = runTest {
        val sink = Sink()
        val records = ArrayList<PlayerRecord>()
        val p = player(sink, records)
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT
        p.start(); runCurrent()
        assertEquals(listOf(48), sink.on) // LH note of step 0 played by the app
        for (m in listOf(72, 74, 76, 77)) { p.onNote(m); runCurrent() }
        advanceUntilIdle()
        assertEquals(listOf(48, 55), sink.on)
        assertEquals(4, records.single().hits)
        assertEquals(4, records.single().totalNotes)
    }

    @Test
    fun followTempoScoresHitsMissesAndWrongNotes() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.FOLLOW_TEMPO; p.hands = HandsMode.RIGHT
        p.start(); runCurrent()
        p.onNote(72) // on time (t = 0)
        advanceTimeBy(500 + 600) // past D5's window: missed
        runCurrent()
        p.onNote(76) // E5 ~100 ms late: inside the window
        p.onNote(60) // wrong
        advanceTimeBy(300); runCurrent()
        p.onNote(77) // F5 100 ms early: inside the window
        advanceUntilIdle()
        val r = records.single()
        assertEquals(3, r.hits)
        assertEquals(1, r.missed)
        assertEquals(1, r.wrong)
        assertTrue(r.completed)
    }

    @Test
    fun seekingBeforeStartBeginsThereAndFromBeginningResets() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT
        p.seek(2) // clicked on E5
        p.start(); runCurrent()
        assertEquals(2, p.cursorStep)
        assertEquals(setOf(76), p.nextKeys)
        p.startFromBeginning(); runCurrent()
        assertEquals(0, p.cursorStep)
        assertEquals(setOf(72), p.nextKeys)
        p.stop(record = false)
    }

    @Test
    fun seekingWhilePlayingAtTempoContinuesFromThere() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.FOLLOW_TEMPO; p.hands = HandsMode.RIGHT
        p.start(); runCurrent()
        p.onNote(72)
        advanceTimeBy(100); runCurrent()
        p.seek(3) // jump to F5
        runCurrent()
        assertEquals(3, p.cursorStep)
        p.onNote(77) // on time at the new position
        advanceUntilIdle()
        val r = records.single()
        assertEquals(2, r.hits)
        assertEquals(0, r.missed) // the skipped chords don't count as missed
    }

    @Test
    fun repeatedNotesAreNotCutOffByTheEarlierRelease() = runTest {
        // E E E where each release falls after the next strike of the same key (as happens with real-time jitter).
        val base = ScoreBuilder.build(MidiParser.parse(MidiWriter.write(listOf(DemoPieces.voice("E4:4 E4:4 E4:8")), bpm = 120)), "rep")
        val q = com.musictrainer.notation.TICKS_PER_QUARTER
        val steps = listOf(0, q, 2 * q).mapIndexed { i, t ->
            com.musictrainer.notation.PlayStep(t, 0, mapOf(com.musictrainer.model.Clef.TREBLE to setOf(64)), mapOf(64 to if (i < 2) q + q / 4 else 2 * q))
        }
        val piece = com.musictrainer.notation.Score("rep", base.key, base.measures, steps, 120)
        val events = ArrayList<String>()
        val sink = object : NoteSink {
            override fun noteOn(midi: Int, velocity: Int) { events += "on" }
            override fun noteOff(midi: Int) { events += "off" }
        }
        val p = PlayerController(this, { testScheduler.currentTime }, { sink }) {}
        p.load(piece, "rep"); p.hands = HandsMode.NONE
        p.start(); advanceUntilIdle()
        // Stale releases of the first two strikes are dropped; only the last note is released.
        assertEquals(listOf("on", "on", "on", "off"), events)
    }

    @Test
    fun waitModeChecksRhythmAndNoteLengths() = runTest {
        // Right hand C5 D5 E5 F5 quarters at 120 bpm: 500 ms apart, each held ~500 ms.
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT; p.checkTiming = true
        p.start(); runCurrent()
        p.onNote(72); advanceTimeBy(480); p.onNoteOff(72) // held fine
        advanceTimeBy(20); p.onNote(74) // exactly on the beat
        assertEquals(0, p.rhythmErrors)
        advanceTimeBy(100); p.onNoteOff(74) // released far too early (100 of 500 ms)
        assertEquals(1, p.lengthErrors)
        advanceTimeBy(1100); p.onNote(76) // 1.2 s after D5 instead of 0.5 s: late
        assertEquals(1, p.rhythmErrors)
        assertEquals("Late by 0.7 s", p.timingMessage)
        advanceTimeBy(500); p.onNote(77) // back in time
        advanceUntilIdle()
        val r = records.single()
        assertEquals(4, r.hits)
        assertEquals(1, r.rhythmErrors)
        assertEquals(1, r.lengthErrors)
        assertTrue(r.score < 40) // timing mistakes cost points
    }

    @Test
    fun timingChecksCanBeSwitchedOff() = runTest {
        val records = ArrayList<PlayerRecord>()
        val p = player(Sink(), records)
        p.style = PlayStyle.WAIT; p.hands = HandsMode.RIGHT; p.checkTiming = false
        p.start(); runCurrent()
        for (m in listOf(72, 74, 76, 77)) { p.onNote(m); p.onNoteOff(m); advanceTimeBy(3000) }
        advanceUntilIdle()
        assertEquals(0, records.single().timingErrors)
    }

    @Test
    fun noHandsJustPlaysEverything() = runTest {
        val sink = Sink()
        val records = ArrayList<PlayerRecord>()
        val p = player(sink, records)
        p.hands = HandsMode.NONE
        p.start(); advanceUntilIdle()
        assertEquals(listOf(72, 48, 74, 76, 55, 77).sorted(), sink.on.sorted())
        assertTrue(records.isEmpty())
        assertNull(p.countInBeat)
    }
}
