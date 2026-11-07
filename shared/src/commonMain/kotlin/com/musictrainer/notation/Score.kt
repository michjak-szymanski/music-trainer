package com.musictrainer.notation

import com.musictrainer.model.Clef
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// All score times are in ticks: 48 per quarter note (12 per 16th), so triplets and 32nds are whole numbers.
const val TICKS_PER_QUARTER = 48
/** Ticks per 16th note. */
const val T16 = TICKS_PER_QUARTER / 4

/** Key signature: number of sharps (+) or flats (−). */
data class KeySignature(val fifths: Int) {
    /** Alteration (−1, 0, +1) the key gives to [letter] (0 = C … 6 = B). */
    fun alterationFor(letter: Int): Int = when {
        fifths > 0 && SHARP_ORDER.indexOf(letter) in 0 until fifths -> 1
        fifths < 0 && FLAT_ORDER.indexOf(letter) in 0 until -fifths -> -1
        else -> 0
    }

    companion object {
        val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
        val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3) // B E A D G C F
    }
}

private val LETTER_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/** A pitch as written: letter (0 = C … 6 = B), alteration (−1…+1) and octave of the letter. */
data class WrittenPitch(val midi: Int, val letter: Int, val alter: Int, val octave: Int) {
    val diatonic: Int get() = octave * 7 + letter
    fun staffPosition(clef: Clef): Int = diatonic - clef.bottomLineDiatonic
}

/**
 * Spells [midi] in [key]: notes of the key's scale use the key's spelling (including E♯ in F♯ major);
 * white keys outside it are naturals; chromatic black keys follow the key's direction.
 */
fun spell(midi: Int, key: KeySignature): WrittenPitch {
    val pc = midi.mod(12)
    for (letter in 0..6) {
        val alter = key.alterationFor(letter)
        if (alter != 0 && (LETTER_PC[letter] + alter).mod(12) == pc) return written(midi, letter, alter)
    }
    val natural = LETTER_PC.indexOf(pc)
    if (natural >= 0) return written(midi, natural, 0)
    val sharp = when {
        key.fifths > 0 -> true
        key.fifths < 0 -> false
        else -> pc != 3 && pc != 10 // C major / A minor: C♯ F♯ G♯ but E♭ B♭
    }
    return if (sharp) written(midi, LETTER_PC.indexOf(pc - 1), 1) else written(midi, LETTER_PC.indexOf(pc + 1), -1)
}

private fun written(midi: Int, letter: Int, alter: Int) =
    WrittenPitch(midi, letter, alter, (midi - alter - LETTER_PC[letter]).floorDiv(12) - 1)

/** Beat structure of a time signature. */
class Meter(val signature: TimeSignature) {
    val measureLength: Int = max(1, signature.numerator * 4 * TICKS_PER_QUARTER / signature.denominator)
    val compound: Boolean = signature.numerator % 3 == 0 && signature.numerator > 3 && signature.denominator >= 8
    val beat: Int = if (compound) 3 * 4 * TICKS_PER_QUARTER / signature.denominator else max(1, 4 * TICKS_PER_QUARTER / signature.denominator)
    /** 4/4 and 12/8: the middle of the bar must stay visible. */
    val halfBar: Int? = if ((signature.numerator == 4 && !compound) || (compound && signature.numerator == 12)) measureLength / 2 else null
}

/** One written note of a chord. [accidental] is the alteration to print (0 = natural sign) or null for none. */
class ScoreNote(
    val pitch: WrittenPitch,
    val accidental: Int?,
    val tieToNext: Boolean,
    val tieFromPrev: Boolean,
)

/** A tuplet an element belongs to, e.g. 3 in the time of 2 ([actual] = 3, [normal] = 2). */
data class TupletInfo(val id: Int, val actual: Int, val normal: Int, val depth: Int = 0)

/** A chord or rest with a single written value. Rests have no notes. */
class ScoreElement(
    val start: Int,
    /** Sounding length in ticks. */
    val duration: Int,
    val dots: Int,
    val notes: List<ScoreNote>,
    val wholeMeasureRest: Boolean = false,
    /** Index of the [PlayStep] this element starts, or −1 (rests, tied continuations). */
    val stepIndex: Int = -1,
    /** Written undotted value in ticks when it differs from what [duration] implies (tuplets). */
    writtenBase: Int = -1,
    /** Voice within the staff (0 = upper / main). */
    val voice: Int = 0,
    /** Clef in effect for this element, or null for the staff's default. */
    val clef: Clef? = null,
    val tuplet: TupletInfo? = null,
    val visible: Boolean = true,
    /** All tuplets this element is in, outermost first (nested tuplets). */
    val tuplets: List<TupletInfo> = listOfNotNull(tuplet),
) {
    val isRest: Boolean get() = notes.isEmpty()
    /** Undotted written value in ticks: 192 whole, 96 half, 48 quarter, 24 eighth, 12 16th, 6 32nd, 3 64th. */
    val base: Int = if (writtenBase > 0) writtenBase else when (dots) {
        1 -> duration * 2 / 3
        2 -> duration * 4 / 7
        else -> duration
    }
    /** Beam group id within its measure and staff, or −1 for unbeamed. */
    var beamGroup: Int = -1
        internal set
}

/**
 * A bar. [staves] is keyed by staff (TREBLE = upper staff, BASS = lower staff); the clef actually used
 * by an element may differ ([ScoreElement.clef]).
 */
class ScoreMeasure(
    val index: Int,
    val start: Int,
    val meter: Meter,
    val showTimeSignature: Boolean,
    val staves: Map<Clef, List<ScoreElement>>,
    /** Actual length in ticks (differs from the meter for pickup bars). */
    val length: Int = meter.measureLength,
    /** Key from this bar on, or null for the score's key. */
    val key: KeySignature? = null,
    val showKeySignature: Boolean = false,
)

/** A moment where at least one note starts; the player must play its pitches. */
class PlayStep(
    val time: Int,
    val measure: Int,
    val pitches: Map<Clef, Set<Int>>,
    /** How long each pitch sounds, in units (for playback). */
    val lengths: Map<Int, Int> = emptyMap(),
) {
    val all: Set<Int> get() = pitches.values.flatten().toSet()

    fun required(treble: Boolean, bass: Boolean): Set<Int> =
        (if (treble) pitches[Clef.TREBLE].orEmpty() else emptySet()) + (if (bass) pitches[Clef.BASS].orEmpty() else emptySet())
}

class Score(
    val title: String,
    val key: KeySignature,
    val measures: List<ScoreMeasure>,
    val steps: List<PlayStep>,
    /** Tempo from the file, quarter notes per minute. */
    val bpm: Int,
    /** False when the file has no tempo marking (then [bpm] is a default). */
    val tempoFromFile: Boolean = true,
)

/** (duration, dots) note values that may be written, longest first. */
private val VALUES = listOf(16 to 0, 12 to 1, 8 to 0, 6 to 1, 4 to 0, 3 to 1, 2 to 0, 1 to 0).map { (d, dots) -> d * T16 to dots }

/**
 * Splits [length] units starting at [start] (within a measure) into written values following standard
 * engraving rules: show the beat structure, keep the middle of a 4/4 bar visible (except for notes
 * starting on beat 1 and the quarter–half–quarter syncopation), allow a quarter on the "and", and
 * write rests aligned to their own value, never dotted in simple meters.
 */
fun decompose(start: Int, length: Int, meter: Meter, rest: Boolean): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    var p = start
    var r = length
    while (r > 0) {
        val v = VALUES.firstOrNull { (d, dots) -> d <= r && allowed(p, d, dots, meter, rest) } ?: (T16 to 0)
        out += v
        p += v.first
        r -= v.first
    }
    return out
}

private fun allowed(p: Int, v: Int, dots: Int, m: Meter, rest: Boolean): Boolean {
    if (p + v > m.measureLength) return false
    val beat = m.beat
    val nextBeat = (p / beat + 1) * beat
    val crossesMiddle = m.halfBar?.let { p < it && p + v > it } ?: false
    if (rest) {
        // Aligned to their own value; a dotted beat rest in compound time aligns to the beat.
        val align = if (dots == 1 && m.compound && v == beat) beat else if (dots == 1) v * 2 / 3 else v
        if (p % align != 0) return false
        if (dots == 1 && !(m.compound && v == beat)) return false
        if (v > beat) {
            if (m.compound) return false
            return v == 2 * beat && p % (2 * beat) == 0 && m.measureLength % (2 * beat) == 0 && !crossesMiddle
        }
        return p + v <= nextBeat
    }
    if (p % beat != 0) {
        if (p + v <= nextBeat) return true
        // Syncopation: a beat-long note on the half beat ("1 and-2 and"), e.g. 8th–quarter–8th.
        return !m.compound && dots == 0 && v == beat && beat % 2 == 0 && p % (beat / 2) == 0 && !crossesMiddle
    }
    if (v <= beat) return true
    // Compound meters: a note longer than a beat must last whole beats (no half note across a 6/8 beat).
    if (m.compound && v % beat != 0) return false
    if (crossesMiddle) return p == 0 || (dots == 0 && v == 2 * beat && p == beat)
    return true
}

/** Groups eighths and shorter into beams: per beat, or per half bar for plain eighths in 4/4. */
internal fun assignBeams(elements: List<ScoreElement>, meter: Meter) {
    var next = 0
    for ((_, voice) in elements.groupBy { it.voice }) next = beamVoice(voice, meter, next)
}

/** Beams one voice; returns the next free group id. */
private fun beamVoice(elements: List<ScoreElement>, meter: Meter, firstId: Int): Int {
    fun windowAt(start: Int): Int {
        if (meter.compound || meter.halfBar == null || meter.beat != 4 * T16) return meter.beat
        val hb = meter.halfBar
        val half = start / hb * hb
        val inHalf = elements.filter { it.start >= half && it.start < half + hb }
        val allEighths = inHalf.isNotEmpty() && inHalf.all { !it.isRest && it.duration == 2 * T16 && it.dots == 0 } &&
            inHalf.sumOf { it.duration } == hb
        return if (allEighths) hb else meter.beat
    }
    var group = ArrayList<ScoreElement>()
    var groupWindow = -1
    var nextId = firstId
    fun flush() {
        if (group.size >= 2) { group.forEach { it.beamGroup = nextId }; nextId++ }
        group = ArrayList()
        groupWindow = -1
    }
    for (e in elements) {
        val beamable = !e.isRest && e.base <= 2 * T16 && e.visible
        if (!beamable) { flush(); continue }
        val w = windowAt(e.start)
        val windowStart = e.start / w * w
        val fits = e.start + e.duration <= windowStart + w
        if (windowStart != groupWindow || !fits) flush()
        if (fits) { group += e; groupWindow = windowStart }
    }
    flush()
    return nextId
}

/** Krumhansl–Kessler key finding over pitch classes weighted by duration. */
fun estimateKey(notes: List<MidiNote>): KeySignature {
    val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    val hist = DoubleArray(12)
    for (n in notes) hist[n.pitch.mod(12)] += (n.end - n.start).toDouble()
    fun corr(profile: DoubleArray, tonic: Int): Double {
        val xs = DoubleArray(12) { hist[(it + tonic) % 12] }
        val mx = xs.average(); val my = profile.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until 12) { val a = xs[i] - mx; val b = profile[i] - my; num += a * b; dx += a * a; dy += b * b }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / sqrt(dx * dy)
    }
    var best = -2.0
    var bestMajorTonic = 0
    for (t in 0 until 12) {
        val cMaj = corr(major, t)
        if (cMaj > best) { best = cMaj; bestMajorTonic = t }
        val cMin = corr(minor, t)
        if (cMin > best) { best = cMin; bestMajorTonic = (t + 3) % 12 }
    }
    // Major tonic pitch class -> sharps/flats (F♯ preferred over G♭).
    val fifths = intArrayOf(0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)
    return KeySignature(fifths[bestMajorTonic])
}

/**
 * Where a note written at [start] should end, given its played end and the next onset in the same staff.
 * Performed MIDI releases notes early (a whole note held ~95% of the bar); taken literally that becomes
 * "dotted half tied to dotted eighth + 16th rest". Longer notes snap their end to a coarser grid (8ths for
 * quarters and longer, quarters for halves and longer); only tiny leftover gaps before the next note are filled.
 */
internal fun cleanEnd(start: Int, playedEnd: Int, next: Int?): Int {
    val played = playedEnd - start
    val grid = when {
        played >= 7 * T16 -> 4 * T16
        played >= 3 * T16 + T16 / 2 -> 2 * T16
        else -> T16
    }
    var end = start + max(grid, ((played + grid / 2) / grid) * grid)
    if (next != null) {
        // A tiny leftover gap (a 16th after a long note) is articulation, not a written rest.
        if (next - end <= min(T16, (end - start) / 8)) end = next
        end = min(end, next)
    }
    return end
}

/** Builds an engraved [Score] from MIDI data. */
object ScoreBuilder {
    private class Chord(val start: Int, val end: Int, val pitches: List<Int>)
    private class Segment(val start: Int, val end: Int, val pitches: List<Int>, val tieFromPrev: Boolean, val tieToNext: Boolean, val onset: Boolean)

    fun build(midi: MidiData, title: String): Score {
        val unit = midi.ticksPerQuarter / 4.0
        fun q(t: Long) = (t / unit).roundToInt() * T16

        val key = midi.keySignatures.firstOrNull()?.second?.let(::KeySignature) ?: estimateKey(midi.notes)
        val signatures = midi.timeSignatures
            .map { q(it.first) to TimeSignature(it.second.numerator.coerceIn(1, 32), it.second.denominator.coerceIn(1, 16)) }
            .ifEmpty { listOf(0 to TimeSignature(4, 4)) }

        // Hands: two note tracks -> by track (higher one on the treble staff); otherwise split at middle C.
        val tracks = midi.notes.groupBy { it.track }
        val staffOf: (MidiNote) -> Clef = if (tracks.size == 2) {
            val upper = tracks.maxBy { (_, ns) -> ns.map { it.pitch }.average() }.key
            { n -> if (n.track == upper) Clef.TREBLE else Clef.BASS }
        } else {
            { n -> if (n.pitch >= 60) Clef.TREBLE else Clef.BASS }
        }

        // Chords per staff: notes starting together; a chord lasts until the next chord in that staff starts.
        val chords = Clef.entries.associateWith { clef ->
            val byStart = midi.notes.filter { staffOf(it) == clef }
                .map { Triple(it.pitch, q(it.start), max(q(it.start) + T16, q(it.end))) }
                .groupBy { it.second }
                .toSortedMap()
            val starts = byStart.keys.toList()
            starts.mapIndexed { i, s ->
                val group = byStart.getValue(s)
                val next = starts.getOrNull(i + 1)
                Chord(s, cleanEnd(s, group.maxOf { it.third }, next), group.map { it.first }.distinct().sorted())
            }
        }
        val totalEnd = chords.values.flatten().maxOfOrNull { it.end } ?: 16 * T16

        // Measures.
        fun signatureAt(t: Int) = signatures.last { it.first <= t }.second
        val measureStarts = ArrayList<Pair<Int, Meter>>()
        var t = 0
        while (t < totalEnd || measureStarts.isEmpty()) {
            val meter = Meter(signatureAt(t))
            measureStarts += t to meter
            t += meter.measureLength
        }
        fun measureOf(time: Int): Int {
            var lo = 0; var hi = measureStarts.lastIndex
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (measureStarts[mid].first <= time) lo = mid else hi = mid - 1 }
            return lo
        }

        // Play steps: every time at which any note starts.
        val stepTimes = chords.values.flatten().map { it.start }.distinct().sorted()
        val stepIndex = stepTimes.withIndex().associate { (i, time) -> time to i }
        val steps = stepTimes.map { time ->
            val starting = chords.mapValues { (_, cs) -> cs.filter { it.start == time } }
            PlayStep(
                time, measureOf(time),
                starting.mapValues { (_, cs) -> cs.flatMap { it.pitches }.toSet() },
                starting.values.flatten().flatMap { c -> c.pitches.map { it to (c.end - c.start) } }.toMap(),
            )
        }

        // Split chords at barlines.
        val segments = Clef.entries.associateWith { HashMap<Int, MutableList<Segment>>() }
        for ((clef, cs) in chords) for (c in cs) {
            var s = c.start
            while (s < c.end) {
                val m = measureOf(s)
                val mEnd = measureStarts[m].first + measureStarts[m].second.measureLength
                val e = min(c.end, mEnd)
                segments.getValue(clef).getOrPut(m) { ArrayList() } +=
                    Segment(s, e, c.pitches, tieFromPrev = s > c.start, tieToNext = e < c.end, onset = s == c.start)
                s = e
            }
        }

        val measures = measureStarts.mapIndexed { mi, (mStart, meter) ->
            val prevSig = measureStarts.getOrNull(mi - 1)?.second?.signature
            val staves = Clef.entries.associateWith { clef ->
                buildStaffMeasure(segments.getValue(clef)[mi].orEmpty(), mStart, meter, key, stepIndex)
            }
            ScoreMeasure(mi, mStart, meter, showTimeSignature = prevSig != meter.signature, staves = staves)
        }
        return Score(title, key, measures, steps, midi.initialBpm, tempoFromFile = midi.tempos.isNotEmpty())
    }

    private fun buildStaffMeasure(
        segments: List<Segment>,
        mStart: Int,
        meter: Meter,
        key: KeySignature,
        stepIndex: Map<Int, Int>,
    ): List<ScoreElement> {
        if (segments.isEmpty()) {
            return listOf(ScoreElement(0, meter.measureLength, 0, emptyList(), wholeMeasureRest = true))
        }
        // (start, duration, dots, pitches or null for rest, tieFromPrev, tieToNext, step)
        data class Raw(val start: Int, val dur: Int, val dots: Int, val pitches: List<Int>?, val tieFrom: Boolean, val tieTo: Boolean, val step: Int)
        val raws = ArrayList<Raw>()
        fun rests(from: Int, to: Int) {
            var p = from
            for ((d, dots) in decompose(from, to - from, meter, rest = true)) { raws += Raw(p, d, dots, null, false, false, -1); p += d }
        }
        var cursor = 0
        for (seg in segments.sortedBy { it.start }) {
            val s = seg.start - mStart
            val e = seg.end - mStart
            if (s > cursor) rests(cursor, s)
            val pieces = decompose(s, e - s, meter, rest = false)
            var p = s
            pieces.forEachIndexed { i, (d, dots) ->
                raws += Raw(
                    p, d, dots, seg.pitches,
                    tieFrom = if (i == 0) seg.tieFromPrev else true,
                    tieTo = if (i == pieces.lastIndex) seg.tieToNext else true,
                    step = if (i == 0 && seg.onset) stepIndex[seg.start] ?: -1 else -1,
                )
                p += d
            }
            cursor = e
        }
        if (cursor < meter.measureLength) rests(cursor, meter.measureLength)

        // Accidentals: compare with the key signature and earlier notes on the same line in this measure.
        val state = HashMap<Int, Int>()
        val elements = raws.map { r ->
            val notes = r.pitches.orEmpty().map { midi ->
                val wp = spell(midi, key)
                val current = state[wp.diatonic] ?: key.alterationFor(wp.letter)
                val show = !r.tieFrom && wp.alter != current
                state[wp.diatonic] = wp.alter
                ScoreNote(wp, if (show) wp.alter else null, tieToNext = r.tieTo, tieFromPrev = r.tieFrom)
            }
            ScoreElement(r.start, r.dur, r.dots, notes, stepIndex = r.step)
        }
        assignBeams(elements, meter)
        return elements
    }
}
