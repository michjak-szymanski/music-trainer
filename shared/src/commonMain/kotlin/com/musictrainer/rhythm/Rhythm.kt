package com.musictrainer.rhythm

import com.musictrainer.notation.Meter
import com.musictrainer.notation.T16
import com.musictrainer.notation.TICKS_PER_QUARTER
import com.musictrainer.notation.TimeSignature
import kotlinx.serialization.Serializable
import kotlin.random.Random

private const val Q = TICKS_PER_QUARTER

/** A written rhythm value: [ticks] long, drawn as [base] with [dots]; [triplet] = 3 in the time of 2. */
data class RhythmValue(val ticks: Int, val base: Int, val dots: Int = 0, val rest: Boolean = false, val triplet: Boolean = false) {
    val label: String get() {
        val name = when (base) {
            4 * Q -> "whole"; 2 * Q -> "half"; Q -> "quarter"; Q / 2 -> "eighth"; Q / 4 -> "16th"; else -> "$ticks"
        }
        return (if (dots > 0) "dotted " else "") + (if (triplet) "triplet " else "") + name + (if (rest) " rest" else " note")
    }

    fun asRest() = copy(rest = true)

    companion object {
        val WHOLE = RhythmValue(4 * Q, 4 * Q)
        val DOTTED_HALF = RhythmValue(3 * Q, 2 * Q, 1)
        val HALF = RhythmValue(2 * Q, 2 * Q)
        val DOTTED_QUARTER = RhythmValue(Q * 3 / 2, Q, 1)
        val QUARTER = RhythmValue(Q, Q)
        val DOTTED_EIGHTH = RhythmValue(Q * 3 / 4, Q / 2, 1)
        val EIGHTH = RhythmValue(Q / 2, Q / 2)
        val SIXTEENTH = RhythmValue(T16, T16)
        val TRIPLET_EIGHTH = RhythmValue(Q / 3, Q / 2, triplet = true)
    }
}

/** One note or rest of a drill. */
data class RhythmEvent(val start: Int, val value: RhythmValue) {
    val end: Int get() = start + value.ticks
}

/** A rhythm to read, write down or tap: [bars] bars of [signature]. */
class RhythmDrill(val signature: TimeSignature, val bars: Int, val events: List<RhythmEvent>) {
    val meter = Meter(signature)
    val length: Int get() = meter.measureLength * bars
    val notes: List<RhythmEvent> get() = events.filter { !it.value.rest }
}

/** Cells fill one beat (or a few beats) of a bar; levels add new cells. */
internal class Cell(val beats: Int, val values: List<RhythmValue>)

private fun cell(beats: Int, vararg v: RhythmValue) = Cell(beats, v.toList())

/** A difficulty level: what rhythms may appear, for simple (x/4) and compound (x/8) meters. */
class RhythmLevel internal constructor(
    val number: Int, val name: String, val description: String, simple: List<Cell>, compound: List<Cell>,
    /** Custom levels: the chosen values (palette); generated values are also checked against notation rules. */
    private val chosen: List<RhythmValue>? = null,
) {
    internal val simpleCells = simple
    internal val compoundCells = compound
    internal val custom: Boolean get() = chosen != null

    /** Values that can appear in this level (for the dictation palette), longest first. */
    fun palette(meter: Meter): List<RhythmValue> =
        (chosen?.filter { v -> cells(meter).any { v in it.values } } ?: cells(meter).flatMap { it.values }.distinct())
            .sortedWith(compareBy<RhythmValue>({ it.rest }, { -it.ticks }))

    /** Whether bars of [signature] can be filled with this level's rhythms. */
    fun canFill(signature: TimeSignature): Boolean = RhythmGenerator.tryGenerate(this, signature, 1, Random(0)) != null

    internal fun cells(meter: Meter) = if (meter.compound) compoundCells else simpleCells
}

object RhythmLevels {
    private val W = RhythmValue.WHOLE
    private val DH = RhythmValue.DOTTED_HALF
    private val H = RhythmValue.HALF
    private val DQ = RhythmValue.DOTTED_QUARTER
    private val QN = RhythmValue.QUARTER
    private val DE = RhythmValue.DOTTED_EIGHTH
    private val E = RhythmValue.EIGHTH
    private val S = RhythmValue.SIXTEENTH
    private val T = RhythmValue.TRIPLET_EIGHTH

    /**
     * One new concept per level; each level keeps everything from the one before. Simple meters (x/4) and
     * compound meters (x/8) have their own cells for each step.
     */
    private class Step(val name: String, val description: String, val simple: List<Cell>, val compound: List<Cell>)

    private val steps = listOf(
        Step("Quarters and halves", "Quarter and half notes", listOf(cell(1, QN), cell(2, H)), listOf(cell(1, DQ), cell(2, DH))),
        Step("Whole notes", "Adds the whole note (a full bar of 4/4)", listOf(cell(4, W)), emptyList()),
        Step("Quarter rests", "Adds the quarter rest (dotted-quarter rest in 6/8)", listOf(cell(1, QN.asRest())), listOf(cell(1, DQ.asRest()))),
        Step("Half rests", "Adds the half rest", listOf(cell(2, H.asRest())), emptyList()),
        Step("Dotted halves", "Adds the dotted half note (three beats; quarter + eighth in 6/8)", listOf(cell(3, DH)), listOf(cell(1, QN, E))),
        Step("Eighth notes", "Pairs of eighths on a beat (three in 6/8)", listOf(cell(1, E, E), cell(1, E, E)), listOf(cell(1, E, E, E), cell(1, E, E, E))),
        Step("Eighth rests", "An eighth rest with an eighth note", listOf(cell(1, E.asRest(), E), cell(1, E, E.asRest())),
            listOf(cell(1, E.asRest(), E, E), cell(1, E, E, E.asRest()), cell(1, QN, E.asRest()))),
        Step("Dotted quarter + eighth", "The dotted quarter followed by an eighth (eighth + quarter in 6/8)", listOf(cell(2, DQ, E)), listOf(cell(1, E, QN))),
        Step("Sixteenth notes", "Four sixteenths on a beat", listOf(cell(1, S, S, S, S)), listOf(cell(1, S, S, E, E))),
        Step("Eighth + two sixteenths", "Eighth + two sixteenths, and two sixteenths + eighth", listOf(cell(1, E, S, S), cell(1, S, S, E)),
            listOf(cell(1, E, S, S, E), cell(1, E, E, S, S))),
        Step("Dotted eighth + sixteenth", "The dotted rhythm: dotted eighth + sixteenth (and reversed)", listOf(cell(1, DE, S), cell(1, S, DE)),
            listOf(cell(1, DE, S, E), cell(1, E, DE, S))),
        Step("Syncopation", "Notes on the off-beat held over the beat: eighth–quarter–eighth, sixteenth–eighth–sixteenth",
            listOf(cell(2, E, QN, E), cell(1, S, E, S)), listOf(cell(1, S, S, S, S, E), cell(1, S, E, S, E))),
        Step("Triplets", "Three eighths in the time of two (six sixteenths in 6/8)", listOf(cell(1, T, T, T), cell(1, T, T, T)), listOf(cell(1, S, S, S, S, S, S))),
        Step("Sixteenth rests", "Rests inside the beat: everything mixed",
            listOf(cell(1, S.asRest(), S, E), cell(1, E, S.asRest(), S), cell(1, S, S, E.asRest())),
            listOf(cell(1, E.asRest(), S, S, E), cell(1, S.asRest(), S, E, E))),
    )

    val all: List<RhythmLevel> = steps.indices.map { i ->
        val upTo = steps.take(i + 1)
        RhythmLevel(i + 1, steps[i].name, steps[i].description, upTo.flatMap { it.simple }, upTo.flatMap { it.compound })
    }

    fun of(number: Int): RhythmLevel = all[(number - 1).coerceIn(0, all.lastIndex)]

    /** Values offered for a custom level, by id. */
    val customChoices: List<Pair<String, RhythmValue>> = listOf(
        "W" to W, "DH" to DH, "H" to H, "DQ" to DQ, "Q" to QN, "DE" to DE, "E" to E, "S" to S, "T" to T,
        "Wr" to W.asRest(), "Hr" to H.asRest(), "DQr" to DQ.asRest(), "Qr" to QN.asRest(), "Er" to E.asRest(), "Sr" to S.asRest(),
    )

    /**
     * A level made of the chosen value [ids]: every way to fill a beat (or two beats, with a value across
     * the beat such as dotted quarter + eighth) with them, plus single values lasting whole beats.
     */
    fun custom(ids: Collection<String>): RhythmLevel {
        val values = customChoices.filter { it.first in ids }.map { it.second }
        fun cellsFor(beat: Int): List<Cell> {
            // Tokens: values, with triplet eighths only as a group of three.
            val tokens = values.filter { !it.triplet }.map { listOf(it) } + values.filter { it.triplet }.map { listOf(it, it, it) }
            val out = LinkedHashMap<List<RhythmValue>, Cell>()
            for (v in values) if (!v.triplet && v.ticks % beat == 0) out[listOf(v)] = Cell(v.ticks / beat, listOf(v))
            fun search(seq: List<RhythmValue>, total: Int, target: Int, maxLen: Int) {
                if (total == target) {
                    var t = 0
                    val crosses = target == beat || seq.any { v -> val a = t; t += v.ticks; a < beat && t > beat }
                    if (crosses && seq.size > 1) out.getOrPut(seq) { Cell(target / beat, seq) }
                    return
                }
                if (seq.size >= maxLen) return
                for (tk in tokens) {
                    val len = tk.sumOf { it.ticks }
                    if (total + len <= target) search(seq + tk, total + len, target, maxLen)
                }
            }
            search(emptyList(), 0, beat, 6)
            search(emptyList(), 0, 2 * beat, 4)
            return out.values.toList()
        }
        val names = values.joinToString(", ") { it.label.removeSuffix(" note") }
        return RhythmLevel(0, "Custom", names, cellsFor(Q), cellsFor(Q * 3 / 2), chosen = values)
    }
}

/** Time signatures the rhythm modes offer. */
val RHYTHM_SIGNATURES = listOf(
    TimeSignature(2, 4), TimeSignature(3, 4), TimeSignature(4, 4), TimeSignature(6, 8), TimeSignature(9, 8), TimeSignature(12, 8),
)

object RhythmGenerator {
    /**
     * A drill of [bars] bars. Newer material of the level is favoured so each level really practises what
     * it adds; long values only start where they can be written as one symbol.
     */
    fun generate(level: RhythmLevel, signature: TimeSignature, bars: Int, random: Random = Random.Default): RhythmDrill =
        tryGenerate(level, signature, bars, random)
            ?: tryGenerate(RhythmLevels.of(1), signature, bars, random)!! // custom values that can't fill this meter

    /** A drill, or null when the level's rhythms can't fill a bar of [signature]. */
    fun tryGenerate(level: RhythmLevel, signature: TimeSignature, bars: Int, random: Random = Random.Default): RhythmDrill? {
        val meter = Meter(signature)
        val cells = level.cells(meter)
        val previous = if (level.number > 1) RhythmLevels.of(level.number - 1).cells(meter).size else 0
        val beatsPerBar = meter.measureLength / meter.beat
        val events = ArrayList<RhythmEvent>()
        repeat(bars) { bar ->
            var barEvents: List<RhythmEvent>
            var attempts = 0
            do {
                barEvents = fillBar(cells, previous, meter, beatsPerBar, bar * meter.measureLength, random, level.custom) ?: return null
                attempts++
                // Each bar needs at least one note; avoid bars that are only rests.
            } while (attempts < 20 && barEvents.all { it.value.rest })
            events += barEvents
        }
        return RhythmDrill(signature, bars, events)
    }

    /** Fills one bar beat by beat, backtracking when a choice leads to a dead end; null if impossible. */
    private fun fillBar(cells: List<Cell>, previous: Int, meter: Meter, beats: Int, barStart: Int, random: Random, check: Boolean): List<RhythmEvent>? {
        fun weight(i: Int, c: Cell) = when {
            check -> if (c.values.size >= 4) 1 else 2
            i >= previous -> 3
            c.beats > 1 -> 1
            else -> 2
        }
        fun fill(beat: Int): List<RhythmEvent>? {
            if (beat == beats) return emptyList()
            // Weighted random order, each candidate tried once.
            val pool = cells.withIndex().filter { (_, c) -> fits(c, beat, beats, meter) && (!check || writable(c, beat * meter.beat, meter)) }
                .map { (i, c) -> c to weight(i, c).toDouble() }.toMutableList()
            while (pool.isNotEmpty()) {
                var x = random.nextDouble() * pool.sumOf { it.second }
                val k = pool.indexOfFirst { x -= it.second; x <= 0 }.let { if (it < 0) pool.lastIndex else it }
                val c = pool.removeAt(k).first
                val rest = fill(beat + c.beats) ?: continue
                var t = barStart + beat * meter.beat
                val here = c.values.map { v -> RhythmEvent(t, v).also { t += v.ticks } }
                return here + rest
            }
            return null
        }
        return fill(0)
    }

    /** Every value of [c] at [start] (within the bar) is a single, correctly placed symbol. */
    private fun writable(c: Cell, start: Int, meter: Meter): Boolean {
        var p = start
        for (v in c.values) {
            val ok = v.triplet || (v.rest && v.ticks == meter.measureLength && p == 0) ||
                com.musictrainer.notation.decompose(p, v.ticks, meter, v.rest) == listOf(v.ticks to v.dots)
            if (!ok) return false
            p += v.ticks
        }
        return true
    }

    /** Whether [c] can start on [beat]: fits the bar, keeps the middle of 4/4 visible, aligns long values. */
    private fun fits(c: Cell, beat: Int, beats: Int, meter: Meter): Boolean {
        if (beat + c.beats > beats) return false
        if (c.beats == 1) return true
        val start = beat * meter.beat
        val end = start + c.beats * meter.beat
        meter.halfBar?.let { hb -> if (start < hb && end > hb && !(start == 0 && end == meter.measureLength)) return false }
        val single = c.values.size == 1
        if (single && c.values[0].rest && c.beats == 2 && beat % 2 != 0) return false // half rest on beat 1 or 3
        if (single && c.beats == 4 && beats != 4) return false // whole note only in 4/4
        return true
    }
}

/** Result of comparing a written answer with the drill. */
class DictationResult(val correct: List<Boolean>, val matched: Int, val total: Int) {
    val allCorrect: Boolean get() = matched == total && correct.all { it }
}

/** Checks a dictation: an entry is right when the drill has the same value (note or rest) at the same time. */
fun checkDictation(drill: RhythmDrill, written: List<RhythmEvent>): DictationResult {
    val answer = drill.events.associateBy { it.start }
    val correct = written.map { w -> answer[w.start]?.let { a -> a.value.ticks == w.value.ticks && a.value.rest == w.value.rest } ?: false }
    return DictationResult(correct, correct.count { it }, drill.events.size)
}

/** One expected tap and what happened to it. [offsetMs] is null when it was missed. */
class TapMark(val event: RhythmEvent, val offsetMs: Long?)

class ImitationResult(val marks: List<TapMark>, val extraTaps: Int) {
    val hits: Int get() = marks.count { it.offsetMs != null }
    val missed: Int get() = marks.count { it.offsetMs == null }
    /** Mean absolute timing error of the hit notes, in ms. */
    val precisionMs: Long get() = marks.mapNotNull { it.offsetMs }.let { if (it.isEmpty()) 0 else it.sumOf { o -> kotlin.math.abs(o) } / it.size }
    val passed: Boolean get() = missed == 0 && extraTaps == 0
}

/**
 * Matches [taps] (ms from the drill's start) to the drill's notes at [msPerTick]. A tap counts for a note
 * when it is within the window around it: a quarter of the gap to the neighbouring notes, at most 150 ms.
 */
fun checkImitation(drill: RhythmDrill, taps: List<Long>, msPerTick: Double): ImitationResult {
    val notes = drill.notes
    val times = notes.map { (it.start * msPerTick).toLong() }
    val used = BooleanArray(taps.size)
    val marks = notes.mapIndexed { i, n ->
        val gapBefore = if (i > 0) times[i] - times[i - 1] else Long.MAX_VALUE
        val gapAfter = if (i < times.lastIndex) times[i + 1] - times[i] else Long.MAX_VALUE
        val window = minOf(gapBefore, gapAfter).let { g -> if (g == Long.MAX_VALUE) 150L else minOf(150L, g * 2 / 5) }
        val best = taps.indices.filter { !used[it] && kotlin.math.abs(taps[it] - times[i]) <= window }
            .minByOrNull { kotlin.math.abs(taps[it] - times[i]) }
        if (best != null) used[best] = true
        TapMark(n, best?.let { taps[it] - times[i] })
    }
    return ImitationResult(marks, used.count { !it })
}

/** The level [s] asks for: one of [RhythmLevels.all], or the custom one (level 0). */
fun levelFor(s: RhythmSettings): RhythmLevel = if (s.level == 0) RhythmLevels.custom(s.customValues) else RhythmLevels.of(s.level)

/** Metronome sound (a click, never a piano note). Names are kept from older versions so saved settings still load. */
@Serializable
enum class MetronomeSound(val label: String, val tone: com.musictrainer.audio.ClickTone) {
    SOFT("Soft click", com.musictrainer.audio.ClickTone.SOFT),
    BRIGHT("Beep", com.musictrainer.audio.ClickTone.BEEP),
    WARM("Wood block", com.musictrainer.audio.ClickTone.WOOD),
    LOW("Low tick", com.musictrainer.audio.ClickTone.LOW),
    WOOD("Tick–tock", com.musictrainer.audio.ClickTone.TICKTOCK),
}

/** Settings shared by both rhythm modes (each mode keeps its own copy). */
@Serializable
data class RhythmSettings(
    val level: Int = 1,
    /** Time signatures to draw questions from, as "n/d". */
    val signatures: List<String> = listOf("4/4"),
    val bars: Int = 1,
    /** Value ids for the custom level (level 0); see [RhythmLevels.customChoices]. */
    val customValues: List<String> = listOf("H", "Q", "E", "Qr"),
    val bpm: Int = 80,
    val questions: Int = 8,
    /** Metronome clicks while the rhythm plays (the count-in always clicks). */
    val metronome: Boolean = true,
    /** Loudness of the metronome clicks, 0–100. */
    val metronomeVolume: Int = 35,
    val metronomeSound: MetronomeSound = MetronomeSound.SOFT,
    /** The first beat of each bar is louder and on its own pitch. */
    val accentDownbeat: Boolean = true,
    /** Dictation: play the rhythm again by itself until the first value is written. */
    val autoReplay: Boolean = true,
    /** Pause before each automatic replay, in tenths of a second (new name: the default went up to 4 s). */
    val replayWaitTenths: Int = 40,
    /** Most automatic replays per question (0 = no limit). */
    val maxReplays: Int = 0,
    /**
     * Go on to the next question by itself after a correct answer (dictation) / perfect round (imitation).
     * On by default (stored under a new name so the earlier "off" default doesn't carry over).
     */
    val nextAfterSuccess: Boolean = true,
    /** Wait before the next question, in tenths of a second. */
    val nextDelayTenths: Int = 16,
    /** While you tap (imitation, play): show where the music is. Off by default: it gives the timing away. */
    val showPlayhead: Boolean = false,
) {
    val signatureList: List<TimeSignature> get() =
        signatures.mapNotNull { s -> s.split('/').takeIf { it.size == 2 }?.let { (n, d) -> n.toIntOrNull()?.let { nn -> d.toIntOrNull()?.let { TimeSignature(nn, it) } } } }
            .ifEmpty { listOf(TimeSignature(4, 4)) }

    fun sanitized() = copy(
        level = level.coerceIn(0, RhythmLevels.all.size),
        customValues = customValues.ifEmpty { listOf("Q") }, bars = bars.coerceIn(1, 4), bpm = bpm.coerceIn(30, 220),
        questions = questions.coerceIn(1, 50), signatures = signatures.ifEmpty { listOf("4/4") },
        metronomeVolume = metronomeVolume.coerceIn(0, 100),
        replayWaitTenths = replayWaitTenths.coerceAtLeast(0), maxReplays = maxReplays.coerceIn(0, 50),
        nextDelayTenths = nextDelayTenths.coerceAtLeast(0),
    )
}
