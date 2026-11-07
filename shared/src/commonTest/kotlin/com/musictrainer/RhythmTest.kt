package com.musictrainer

import com.musictrainer.notation.Meter
import com.musictrainer.player.NoteSink
import com.musictrainer.rhythm.DictationSession
import com.musictrainer.rhythm.ImitationSession
import com.musictrainer.rhythm.RHYTHM_SIGNATURES
import com.musictrainer.rhythm.RhythmEvent
import com.musictrainer.rhythm.RhythmGenerator
import com.musictrainer.rhythm.RhythmLevels
import com.musictrainer.rhythm.RhythmRecord
import com.musictrainer.rhythm.RhythmSettings
import com.musictrainer.rhythm.RhythmValue
import com.musictrainer.rhythm.checkDictation
import com.musictrainer.rhythm.checkImitation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RhythmTest {
    private object Silent : NoteSink {
        override fun noteOn(midi: Int, velocity: Int) = Unit
        override fun noteOff(midi: Int) = Unit
    }

    @Test
    fun everyLevelAndMeterFillsBarsExactly() {
        val r = Random(1)
        for (level in RhythmLevels.all) for (sig in RHYTHM_SIGNATURES) repeat(40) {
            val d = RhythmGenerator.generate(level, sig, 2, r)
            val meter = Meter(sig)
            var t = 0
            for (e in d.events) {
                assertEquals(t, e.start, "gap/overlap in level ${level.number} $sig: ${d.events}")
                t = e.end
                // Nothing crosses a bar line.
                assertEquals(e.start / meter.measureLength, (e.end - 1) / meter.measureLength, "crosses bar: $e")
            }
            assertEquals(2 * meter.measureLength, t)
            for (b in 0 until 2) assertTrue(d.events.any { !it.value.rest && it.start / meter.measureLength == b }, "bar of rests only")
        }
    }

    @Test
    fun customLevelsUseOnlyTheChosenValues() {
        val r = Random(9)
        val sets = listOf(
            listOf("Q", "E"), listOf("H", "Q", "Qr"), listOf("DQ", "E"), listOf("Q", "T"), listOf("E", "S", "Sr"),
            listOf("DQ", "Q", "E", "Er"), listOf("DE", "S", "Q"), listOf("W", "H", "Q", "Hr", "Wr"), listOf("E", "Q", "DQ", "S", "T", "DE", "Er", "Qr"),
        )
        for (ids in sets) {
            val level = RhythmLevels.custom(ids)
            val allowed = RhythmLevels.customChoices.filter { it.first in ids }.map { it.second }.toSet()
            for (sig in RHYTHM_SIGNATURES) {
                if (!level.canFill(sig)) continue
                repeat(30) {
                    val d = RhythmGenerator.tryGenerate(level, sig, 2, r)!!
                    val meter = Meter(sig)
                    var t = 0
                    for (e in d.events) {
                        assertTrue(e.value in allowed, "$ids $sig: ${e.value} not chosen")
                        assertEquals(t, e.start); t = e.end
                        assertEquals(e.start / meter.measureLength, (e.end - 1) / meter.measureLength, "crosses bar: $e")
                    }
                    assertEquals(d.length, t)
                }
            }
        }
        // Quarters and eighths fill every simple meter; halves alone can't fill 3/4.
        assertTrue(RhythmLevels.custom(listOf("Q", "E")).canFill(RHYTHM_SIGNATURES[1]))
        assertFalse(RhythmLevels.custom(listOf("H")).canFill(RHYTHM_SIGNATURES[1]))
        assertTrue(RhythmLevels.custom(listOf("H")).canFill(RHYTHM_SIGNATURES[2]))
        // Dotted quarters fill 6/8; the palette shows only what can appear.
        val six = RhythmLevels.custom(listOf("DQ", "Q", "E"))
        assertTrue(six.canFill(RHYTHM_SIGNATURES[3]))
        assertEquals(setOf(RhythmValue.DOTTED_QUARTER, RhythmValue.QUARTER, RhythmValue.EIGHTH), six.palette(Meter(RHYTHM_SIGNATURES[3])).toSet())
    }

    @Test
    fun metronomeIsAShortClickNotAPianoNote() {
        for (tone in com.musictrainer.audio.ClickTone.entries) {
            val synth = com.musictrainer.audio.PianoSynth()
            synth.click(tone, accent = false, volume = 0.35f)
            val out = ShortArray(com.musictrainer.audio.PianoSynth.SAMPLE_RATE / 2) // half a second
            synth.render(out)
            val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(peak in 500..16000, "$tone peak $peak") // audible but soft
            // Gone within ~0.35 s (a piano note rings for seconds).
            val tail = out.copyOfRange((0.35 * com.musictrainer.audio.PianoSynth.SAMPLE_RATE).toInt(), out.size).maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(tail < 30, "$tone still sounding: $tail")
            assertTrue(synth.isIdle, "$tone voice finished")
        }
    }

    @Test
    fun correctDictationWaitsForNextUnlessAutoNextIsOn() = runTest {
        // Default: stays on the answer until Next.
        val stay = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        stay.start(RhythmSettings(questions = 3, nextAfterSuccess = false), seed = 5)
        runCurrent()
        for (e in stay.drill!!.events) stay.write(e.value)
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, stay.question)
        stay.abandon()
        // Switched on: moves on by itself, starting with a count-in.
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        s.start(RhythmSettings(questions = 3, nextAfterSuccess = true), seed = 5)
        runCurrent()
        for (e in s.drill!!.events) s.write(e.value)
        assertTrue(s.result!!.allCorrect)
        assertEquals(1, s.question)
        advanceTimeBy(2000); runCurrent()
        assertEquals(2, s.question) // next question without pressing Next
        assertTrue(s.playback.countInBeat != null || s.playback.playing, "it starts by counting in")
        s.abandon()
    }

    @Test
    fun dictationReplaysUntilTheFirstValueIsWritten() = runTest {
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        s.start(RhythmSettings(questions = 2, bpm = 120), seed = 5)
        val d = s.drill!!
        val pass = 60L + 2 * (d.meter.measureLength * 500L / 48) // count-in + the bar (ms)
        advanceTimeBy(pass + 200); runCurrent()
        assertFalse(s.playback.playing) // first playing done, pausing
        advanceTimeBy(3500); runCurrent()
        assertFalse(s.playback.playing, "default wait is 4 s")
        advanceTimeBy(700); runCurrent()
        assertTrue(s.playback.playing, "replayed by itself")
        s.write(d.events.first().value) // start writing: no more replays after this one
        advanceTimeBy(pass + 3000); runCurrent()
        assertFalse(s.playback.playing)
        s.abandon()
    }

    @Test
    fun replayAfterAMistakeLetsYouWriteAgainWithoutChangingTheScore() = runTest {
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        s.start(RhythmSettings(questions = 2, level = 6, bpm = 60), seed = 3)
        val d = s.drill!!
        // A wrong first try: whole bar of quarters/eighths that doesn't match.
        while (s.result == null) s.write(s.level.palette(d.meter).first { s.canWrite(it) && !it.rest && it != d.events[s.written.size.coerceAtMost(d.events.lastIndex)].value })
        val right = s.notesRight; val total = s.notesTotal
        assertFalse(s.result!!.allCorrect)
        s.listen() // Replay after the mistake
        assertTrue(s.written.isEmpty() && s.result == null, "answer cleared, values can be written again")
        // …and the automatic replays carry on while nothing is written.
        advanceTimeBy(60L + 2 * (d.meter.measureLength * 1000L / 48) + 4500); runCurrent()
        assertTrue(s.playback.playing || s.pending != null, "auto-replay after Replay")
        while (s.playback.countInBeat != null) { advanceTimeBy(100); runCurrent() } // not during a count-in
        assertTrue(s.canWrite(d.events.first().value))
        for (e in d.events) s.write(e.value)
        assertTrue(s.result!!.allCorrect)
        assertEquals(right, s.notesRight); assertEquals(total, s.notesTotal) // the first try counts
        s.abandon()
    }

    @Test
    fun pauseFreezesTheCountdownAndResumeContinues() = runTest {
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        s.start(RhythmSettings(questions = 2, bpm = 120), seed = 5)
        val d = s.drill!!
        val pass = 60L + 2 * (d.meter.measureLength * 500L / 48)
        advanceTimeBy(pass + 1000); runCurrent()
        assertTrue(s.pending != null) // waiting for the automatic replay
        s.pause()
        advanceTimeBy(20_000); runCurrent()
        assertFalse(s.playback.playing, "paused: no replay")
        assertTrue(s.pending?.pausedLeft != null)
        s.resume()
        advanceTimeBy(3200); runCurrent()
        assertTrue(s.playback.playing, "resumed: replays when the rest of the wait is over")
        // Pausing the rhythm itself, then playing again.
        s.pause(); assertFalse(s.playback.playing); assertTrue(s.pausedPlayback)
        s.resume(); runCurrent(); assertTrue(s.playback.playing)
        s.abandon()
    }

    @Test
    fun replayClearsNotesAlreadyEntered() = runTest {
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        s.start(RhythmSettings(questions = 2), seed = 5)
        s.write(s.drill!!.events.first().value)
        assertTrue(s.written.isNotEmpty())
        s.listen()
        assertTrue(s.written.isEmpty(), "Replay starts the question afresh")
        s.abandon()
    }

    @Test
    fun nothingCanBeWrittenDuringTheCountIn() = runTest {
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) {}
        s.start(RhythmSettings(questions = 2, bpm = 60), seed = 5)
        advanceTimeBy(100); runCurrent()
        assertTrue(s.playback.countInBeat != null, "counting in")
        val v = s.drill!!.events.first().value
        assertFalse(s.canWrite(v))
        s.write(v); assertTrue(s.written.isEmpty())
        advanceTimeBy(5000); runCurrent() // count-in (4 beats at 60 BPM) is over
        assertTrue(s.canWrite(v))
        s.abandon()
    }

    @Test
    fun levelsAddNewValues() {
        val m = Meter(RHYTHM_SIGNATURES[2])
        val l1 = RhythmLevels.of(1).palette(m)
        assertTrue(RhythmValue.QUARTER in l1 && RhythmValue.HALF in l1)
        assertFalse(RhythmValue.EIGHTH in l1)
        assertTrue(RhythmValue.EIGHTH in RhythmLevels.of(6).palette(m))
        assertFalse(RhythmValue.EIGHTH in RhythmLevels.of(5).palette(m))
        // One new concept per level: level 3 only adds the quarter rest.
        assertEquals(RhythmLevels.of(2).palette(m).toSet() + RhythmValue.QUARTER.asRest(), RhythmLevels.of(3).palette(m).toSet())
        assertTrue(RhythmValue.TRIPLET_EIGHTH in RhythmLevels.of(13).palette(m))
    }

    @Test
    fun dictationIsCheckedByTimeAndValue() {
        val d = RhythmGenerator.generate(RhythmLevels.of(1), RHYTHM_SIGNATURES[2], 1, Random(3))
        assertTrue(checkDictation(d, d.events).allCorrect)
        val wrong = listOf(RhythmEvent(0, RhythmValue.WHOLE))
        if (d.events.size > 1) assertFalse(checkDictation(d, wrong).allCorrect)
    }

    @Test
    fun dictationSessionHasNoUndoAndScoresWhenFull() = runTest {
        val records = ArrayList<RhythmRecord>()
        val s = DictationSession(this, { testScheduler.currentTime }, { Silent }) { records += it }
        s.start(RhythmSettings(questions = 1), seed = 5)
        val d = s.drill!!
        // Write the right answer value by value.
        for (e in d.events) { assertTrue(s.canWrite(e.value), "can't write ${e.value} at ${s.position}"); s.write(e.value) }
        assertTrue(s.result!!.allCorrect)
        assertFalse(s.canWrite(RhythmValue.QUARTER)) // done: nothing more can be written
        s.nextQuestion()
        assertTrue(s.sessionDone)
        assertEquals(1, records.single().perfect)
        advanceUntilIdle()
    }

    @Test
    fun tapsAreMatchedWithinTheirWindow() {
        val d = RhythmGenerator.generate(RhythmLevels.of(1), RHYTHM_SIGNATURES[2], 1, Random(7))
        val mpt = 500.0 / 48 // 120 BPM
        val perfect = d.notes.map { (it.start * mpt).toLong() + 20 }
        val r = checkImitation(d, perfect, mpt)
        assertTrue(r.passed)
        assertEquals(20, r.precisionMs)
        val sloppy = checkImitation(d, perfect.drop(1) + 1_000_000, mpt)
        assertEquals(1, r.marks.size - sloppy.hits)
        assertEquals(1, sloppy.extraTaps)
    }

    @Test
    fun imitationListensThenRecordsTaps() = runTest {
        val records = ArrayList<RhythmRecord>()
        val s = ImitationSession(this, { testScheduler.currentTime }, { Silent }) { records += it }
        s.start(RhythmSettings(questions = 1, bpm = 120), seed = 11)
        runCurrent()
        assertEquals(ImitationSession.Phase.LISTEN, s.phase)
        val d = s.drill!!
        val mpt = 500.0 / 48
        val bar = (d.meter.measureLength * mpt).toLong()
        // Listening: count-in bar + the drill (+ start margin).
        advanceTimeBy(60 + 2 * bar + 50); runCurrent()
        assertEquals(ImitationSession.Phase.YOUR_TURN, s.phase)
        // Your turn: count-in, then tap every note on time.
        val turnStart = testScheduler.currentTime - 50 + 60 + bar
        for (n in d.notes) {
            val at = turnStart + (n.start * mpt).toLong()
            advanceTimeBy(at - testScheduler.currentTime); runCurrent()
            s.onTap()
        }
        advanceUntilIdle()
        assertEquals(ImitationSession.Phase.RESULT, s.phase)
        assertTrue(s.result!!.passed, "offsets ${s.result!!.marks.map { it.offsetMs }} extra ${s.result!!.extraTaps}")
    }
}
