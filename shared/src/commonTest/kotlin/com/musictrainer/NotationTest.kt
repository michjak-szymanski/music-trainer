package com.musictrainer

import com.musictrainer.model.Clef
import com.musictrainer.notation.KeySignature
import com.musictrainer.notation.Meter
import com.musictrainer.notation.MidiNote
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.MidiWriter
import com.musictrainer.notation.NoteSpec
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.notation.TimeSignature
import com.musictrainer.notation.T16
import com.musictrainer.notation.decompose as decomposeTicks
import com.musictrainer.notation.estimateKey
import com.musictrainer.notation.spell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotationTest {
    /** decompose() in 16ths for readable expectations. */
    private fun decompose(start: Int, length: Int, meter: Meter, rest: Boolean) =
        decomposeTicks(start * T16, length * T16, meter, rest).map { (d, dots) -> d / T16 to dots }
    private val common = Meter(TimeSignature(4, 4))
    private val waltz = Meter(TimeSignature(3, 4))
    private val sixEight = Meter(TimeSignature(6, 8))
    private val q = 120 // ticks per 16th at 480 ppq

    @Test
    fun midiRoundTrip() {
        val bytes = MidiWriter.write(
            listOf(listOf(NoteSpec(60, 0, 480), NoteSpec(64, 480, 240))),
            timeSignature = TimeSignature(3, 4), keyFifths = -2, bpm = 90,
        )
        val m = MidiParser.parse(bytes)
        assertEquals(480, m.ticksPerQuarter)
        assertEquals(listOf(60, 64), m.notes.map { it.pitch })
        assertEquals(480L, m.notes[0].end)
        assertEquals(TimeSignature(3, 4), m.timeSignatures.single().second)
        assertEquals(-2, m.keySignatures.single().second)
        assertEquals(90, m.initialBpm)
    }

    @Test
    fun noteValuesFollowBeatRules() {
        assertEquals(listOf(4 to 0), decompose(2, 4, common, rest = false)) // quarter on the "and"
        assertEquals(listOf(12 to 1), decompose(0, 12, common, rest = false)) // dotted half from beat 1
        assertEquals(listOf(8 to 0), decompose(4, 8, common, rest = false)) // quarter–HALF–quarter
        assertEquals(listOf(8 to 0, 4 to 0), decompose(4, 12, common, rest = false)) // dotted half on beat 2 is split
        assertEquals(listOf(2 to 0, 2 to 0), decompose(6, 4, common, rest = false)) // can't hide the middle of the bar
        assertEquals(listOf(3 to 1), decompose(1, 3, common, rest = false)) // dotted 8th after a 16th, within the beat
        assertEquals(listOf(6 to 1), decompose(0, 6, sixEight, rest = false)) // dotted quarter = one 6/8 beat
        assertEquals(listOf(6 to 1, 2 to 0), decompose(0, 8, sixEight, rest = false)) // no half note across 6/8 beats
        assertEquals(listOf(12 to 1), decompose(0, 12, waltz, rest = false))
    }

    @Test
    fun restsAreAlignedAndUndottedInSimpleMeters() {
        assertEquals(listOf(8 to 0), decompose(0, 8, common, rest = true)) // half rest on beat 1
        assertEquals(listOf(4 to 0, 4 to 0), decompose(4, 8, common, rest = true)) // beats 2–3: two quarter rests
        assertEquals(listOf(1 to 0, 2 to 0), decompose(1, 3, common, rest = true)) // 16th rest + 8th rest
        assertEquals(listOf(4 to 0, 2 to 0), decompose(0, 6, common, rest = true)) // no dotted quarter rest in 4/4
        assertEquals(listOf(6 to 1), decompose(6, 6, sixEight, rest = true)) // dotted quarter rest in 6/8
        assertEquals(listOf(4 to 0, 4 to 0), decompose(0, 8, waltz, rest = true)) // no half rest in 3/4
    }

    @Test
    fun spellingFollowsKey() {
        assertEquals(3 to 1, spell(66, KeySignature(1)).let { it.letter to it.alter }) // F♯ in G major
        assertEquals(-1, spell(70, KeySignature(-1)).alter) // B♭ in F major
        assertEquals(1, spell(65, KeySignature(6)).alter) // E♯ in F♯ major
        assertEquals(2, spell(65, KeySignature(6)).letter)
        assertEquals(-1, spell(63, KeySignature(0)).alter) // E♭ in C major
        assertEquals(1, spell(68, KeySignature(0)).alter) // G♯ in C major
        assertEquals(4, spell(61, KeySignature(0)).octave) // C♯4
        assertEquals(5, spell(71, KeySignature(-7)).octave) // C♭5 sounds as B4
    }

    private fun build(vararg tracks: List<NoteSpec>, sig: TimeSignature = TimeSignature(4, 4), key: Int = 0) =
        ScoreBuilder.build(MidiParser.parse(MidiWriter.write(tracks.toList(), timeSignature = sig, keyFifths = key)), "test")

    @Test
    fun eighthsBeamInHalfBarsAndSixteenthsPerBeat() {
        val eighths = build((0 until 8).map { NoteSpec(60 + it, it * 2 * q, 2 * q) })
        val groups = eighths.measures[0].staves.getValue(Clef.TREBLE).map { it.beamGroup }
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1), groups)

        val sixteenths = build((0 until 16).map { NoteSpec(60 + it % 5, it * q, q) })
        assertEquals(4, sixteenths.measures[0].staves.getValue(Clef.TREBLE).map { it.beamGroup }.distinct().size)

        // A quarter in the middle splits the eighths into per-beat groups and stays unbeamed.
        val mixed = build(listOf(NoteSpec(60, 0, 2 * q), NoteSpec(62, 2 * q, 2 * q), NoteSpec(64, 4 * q, 4 * q),
            NoteSpec(65, 8 * q, 2 * q), NoteSpec(67, 10 * q, 2 * q), NoteSpec(69, 12 * q, 4 * q)))
        assertEquals(listOf(0, 0, -1, 1, 1, -1), mixed.measures[0].staves.getValue(Clef.TREBLE).map { it.beamGroup })
    }

    @Test
    fun accidentalsTrackKeyAndMeasure() {
        // G major: F♮ needs a natural, the repeat in the same bar doesn't, the next bar needs it again; F♯ shows nothing.
        val s = build(listOf(NoteSpec(65, 0, 4 * q), NoteSpec(65, 4 * q, 4 * q), NoteSpec(66, 8 * q, 8 * q), NoteSpec(65, 16 * q, 16 * q)), key = 1)
        val m0 = s.measures[0].staves.getValue(Clef.TREBLE).flatMap { it.notes }.map { it.accidental }
        assertEquals(listOf(0, null, 1), m0) // F♮, F, F♯ (needs ♯ again after the natural)
        assertEquals(listOf(0), s.measures[1].staves.getValue(Clef.TREBLE).flatMap { it.notes }.map { it.accidental })
    }

    @Test
    fun notesAcrossBarlinesAreTied() {
        val s = build(listOf(NoteSpec(67, 12 * q, 8 * q)))
        val last = s.measures[0].staves.getValue(Clef.TREBLE).last()
        val first = s.measures[1].staves.getValue(Clef.TREBLE).first()
        assertTrue(last.notes.single().tieToNext)
        assertTrue(first.notes.single().tieFromPrev)
        assertEquals(4 * T16, last.duration)
        assertEquals(4 * T16, first.duration)
        assertEquals(1, s.steps.size) // tied continuation is not something to play again
    }

    @Test
    fun handsSplitByTrackAndStepsCombineBothStaves() {
        val s = build(listOf(NoteSpec(72, 0, 16 * q)), listOf(NoteSpec(48, 0, 8 * q), NoteSpec(55, 8 * q, 8 * q)))
        assertEquals(setOf(72), s.steps[0].pitches[Clef.TREBLE])
        assertEquals(setOf(48), s.steps[0].pitches[Clef.BASS])
        assertEquals(2, s.steps.size)
        assertEquals(setOf(55), s.steps[1].required(treble = false, bass = true))
        assertTrue(s.measures[0].staves.getValue(Clef.TREBLE).single().duration == 16 * T16)
    }

    @Test
    fun earlyReleasedNotesKeepTheirValue() {
        // Like Jingle Bells: notes held 90–95% of their value, chord changes every bar, a final whole note with no successor.
        val bass = (0 until 3).map { NoteSpec(48, it * 16 * q, (16 * q * 0.94).toInt()) } + NoteSpec(48, 48 * q, (16 * q * 0.9).toInt())
        val melody = listOf(NoteSpec(64, 0, (4 * q * 0.9).toInt()), NoteSpec(64, 4 * q, (4 * q * 0.9).toInt()), NoteSpec(64, 8 * q, (8 * q * 0.92).toInt()))
        val s = build(melody, bass)
        for (m in s.measures) assertEquals(1, m.staves.getValue(Clef.BASS).size, "bar ${m.index}: one whole note, no ties or rests")
        assertTrue(s.measures.all { m -> m.staves.getValue(Clef.BASS).single().duration == 16 * T16 })
        assertEquals(listOf(4, 4, 8), s.measures[0].staves.getValue(Clef.TREBLE).map { it.duration / T16 })
    }

    @Test
    fun writtenRestsAfterLongNotesStay() {
        // Half note, 8th rest, 8th, quarter (bar 4 of the rhythm study).
        val s = build(listOf(NoteSpec(67, 0, 8 * q), NoteSpec(77, 10 * q, 2 * q), NoteSpec(76, 12 * q, 4 * q)))
        val m = s.measures[0].staves.getValue(Clef.TREBLE)
        assertEquals(listOf(8, 2, 2, 4), m.map { it.duration / T16 })
        assertTrue(m[1].isRest)
        assertTrue(m[0].notes.none { it.tieToNext })
    }

    @Test
    fun staccatoNotesStillShowTheirRests() {
        // A quarter played as a short 8th followed by a real rest stays "8th + 8th rest" only when the gap is large.
        val s = build(listOf(NoteSpec(60, 0, 2 * q), NoteSpec(62, 8 * q, 8 * q)))
        assertEquals(listOf(false, true, true, false), s.measures[0].staves.getValue(Clef.TREBLE).map { it.isRest }.let { listOf(it[0]) + it.drop(1).take(2) + it.drop(3).take(1) })
    }

    @Test
    fun emptyMeasureGetsWholeRest() {
        val s = build(listOf(NoteSpec(60, 0, 4 * q)), listOf(NoteSpec(48, 16 * q, 16 * q)))
        assertTrue(s.measures[1].staves.getValue(Clef.TREBLE).single().wholeMeasureRest)
    }

    @Test
    fun keyIsEstimatedWhenMissing() {
        val dMajor = listOf(62, 64, 66, 67, 69, 71, 73, 74, 66, 69, 62).mapIndexed { i, p -> MidiNote(p, i * 480L, i * 480L + 400, 0, 0) }
        assertEquals(2, estimateKey(dMajor).fifths)
    }
}
