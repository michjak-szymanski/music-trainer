package com.musictrainer.analysis

import com.musictrainer.model.Clef
import com.musictrainer.model.SpelledNote
import com.musictrainer.notation.Score
import com.musictrainer.notation.TICKS_PER_QUARTER
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A finding about the piece: what, which hand, and where (bar ranges, 0-based). */
class Finding(val title: String, val detail: String, val bars: List<IntRange>, val hand: String? = null)

/** Bars that repeat: [first] comes back as [again], [shift] semitones higher (0 = exactly). */
class Repeat(val first: IntRange, val again: IntRange, val shift: Int)

/** A step of the practice plan, optionally with bars to loop. */
class PlanStep(val text: String, val loop: IntRange? = null)

class HardSpot(val bars: IntRange, val score: Double, val reasons: List<String>)

class PieceAnalysis(
    val bars: Int,
    val keyName: String,
    val meter: String,
    val bpm: Int,
    val notesRight: Int,
    val notesLeft: Int,
    val rangeRight: String,
    val rangeLeft: String,
    /** 1 (easy) … 5 (hard). */
    val difficulty: Int,
    /** Phrases with their letters, e.g. (0..3, "A"), (4..7, "B"). */
    val form: List<Pair<IntRange, String>>,
    val repeats: List<Repeat>,
    /** Chord per bar, as roman numeral and symbol ("V7 (G7)"), "–" for empty bars. */
    val harmony: List<String>,
    val progressions: List<Finding>,
    val patterns: List<Finding>,
    val hardSpots: List<HardSpot>,
    val plan: List<PlanStep>,
    /** Share of bars that are an exact or transposed repeat of earlier bars, percent. */
    val repeatedPercent: Int,
) {
    val formString: String get() = form.joinToString(" ") { it.second }
}

/** "bars 3–4, 9" for 0-based ranges. */
fun barsText(ranges: List<IntRange>): String {
    if (ranges.isEmpty()) return ""
    val parts = ranges.take(8).map { if (it.first == it.last) "${it.first + 1}" else "${it.first + 1}–${it.last + 1}" }
    val more = if (ranges.size > 8) " …" else ""
    return (if (ranges.size == 1 && ranges[0].first == ranges[0].last) "bar " else "bars ") + parts.joinToString(", ") + more
}

/** Merges bar numbers into ranges: [1,2,3,7] → [1..3, 7..7]. */
fun toRanges(bars: Collection<Int>): List<IntRange> {
    val sorted = bars.toSortedSet().toList()
    val out = ArrayList<IntRange>()
    var start = -1; var prev = -2
    for (b in sorted) {
        if (b != prev + 1) { if (start >= 0) out += start..prev; start = b }
        prev = b
    }
    if (start >= 0) out += start..prev
    return out
}

private val SHARP_ORDER = intArrayOf(5, 0, 7, 2, 9, 4, 11) // F C G D A E B (pitch classes)
private val FLAT_ORDER = intArrayOf(11, 4, 9, 2, 7, 0, 5)

/** Pitch classes of the major scale of a key with [fifths] sharps (+) or flats (−). */
private fun scalePcs(fifths: Int): Set<Int> {
    val tonic = (fifths * 7).mod(12)
    return listOf(0, 2, 4, 5, 7, 9, 11).map { (tonic + it).mod(12) }.toSet()
}

private val MAJOR_KEYS = listOf("C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯")
private val MINOR_KEYS = listOf("A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯", "G♯", "D♯", "A♯")

private class Ev(val bar: Int, val start: Int, val dur: Int, val midi: Int, val staff: Clef, val dots: Int, val tuplet: Boolean, val base: Int)

object Professor {
    fun analyze(score: Score): PieceAnalysis {
        val n = score.measures.size
        val meter = score.measures.firstOrNull()?.meter
        val beat = meter?.beat ?: TICKS_PER_QUARTER
        // Every sounding note (tied continuations left out).
        val events = score.measures.flatMap { m ->
            m.staves.flatMap { (staff, list) ->
                list.flatMap { e ->
                    e.notes.filter { !it.tieFromPrev }.map { Ev(m.index, e.start, e.duration, it.pitch.midi, staff, e.dots, e.tuplets.isNotEmpty(), e.base) }
                }
            }
        }
        val byBar = events.groupBy { it.bar }
        val right = events.filter { it.staff == Clef.TREBLE }
        val left = events.filter { it.staff == Clef.BASS }

        // ---- key and mode (from the key signature; minor if the harmony starts/ends on the relative minor)
        val fifths = score.key.fifths
        val tonicMajor = (fifths * 7).mod(12)
        val harmonyChords = (0 until n).map { chordOfBar(byBar[it].orEmpty(), beat) }
        val relMinor = (tonicMajor + 9).mod(12)
        val firstLast = listOfNotNull(harmonyChords.firstOrNull { it != null }, harmonyChords.lastOrNull { it != null })
        val minor = firstLast.isNotEmpty() && firstLast.all { it.root == relMinor && it.minorLike }
        val tonic = if (minor) relMinor else tonicMajor
        val ki = (fifths + 7).coerceIn(0, 14)
        val keyName = if (minor) "${MINOR_KEYS[ki]} minor" else "${MAJOR_KEYS[ki]} major"

        // ---- repeats (exact and transposed runs of 2+ bars)
        val exact = (0 until n).map { exactKey(byBar[it].orEmpty()) }
        val shape = (0 until n).map { shapeKey(byBar[it].orEmpty()) }
        val low = (0 until n).map { b -> byBar[b]?.minOfOrNull { it.midi } }
        val repeats = ArrayList<Repeat>()
        fun runs(keys: List<String>, transposed: Boolean) {
            for (i in 0 until n) for (j in i + 1 until n) {
                if (keys[i].isEmpty() || keys[i] != keys[j]) continue
                if (i > 0 && keys[i - 1] == keys[j - 1] && keys[i - 1].isNotEmpty()) continue // not the start of a run
                var len = 0
                while (j + len < n && i + len < j && keys[i + len].isNotEmpty() && keys[i + len] == keys[j + len]) len++
                if (len < 2) continue
                val shift = if (transposed) (low[j] ?: 0) - (low[i] ?: 0) else 0
                if (transposed && shift == 0) continue
                repeats += Repeat(i until i + len, j until j + len, shift)
            }
        }
        runs(exact, false)
        runs(shape, true)
        // Keep the longest, drop ones inside others.
        // Keep the longest runs; drop a run whose repeated bars lie inside an already kept one.
        val kept = repeats.sortedByDescending { it.first.count() }.fold(ArrayList<Repeat>()) { acc, r ->
            if (acc.none { o -> r.again.first >= o.again.first && r.again.last <= o.again.last }) acc += r
            acc
        }.sortedBy { it.again.first }.take(12)
        val repeatedBars = kept.flatMap { it.again.toList() }.toSet()
        val repeatedPercent = if (n == 0) 0 else repeatedBars.size * 100 / n

        // ---- form: phrases of 4 bars (2 in short pieces), lettered by similarity
        val phraseLen = if (n >= 12) 4 else 2
        val phrases = (0 until n step phraseLen).map { it until minOf(n, it + phraseLen) }
        val reps = ArrayList<Pair<IntRange, Char>>()
        val form = phrases.map { ph ->
            var best: Pair<Char, Double>? = null
            for ((r, letter) in reps) {
                val sim = phraseSimilarity(ph, r, byBar)
                if (best == null || sim > best.second) best = letter to sim
            }
            val b = best
            when {
                b != null && b.second >= 0.85 -> ph to "${b.first}"
                b != null && b.second >= 0.55 -> ph to "${b.first}′"
                else -> { val letter = 'A' + reps.size; reps += ph to letter; ph to "$letter" }
            }
        }

        // ---- harmony and progressions: per half bar in bars of 4+ beats when the halves differ
        val halves: List<List<Chord>> = (0 until n).map { b ->
            val m = score.measures[b]
            val evs = byBar[b].orEmpty()
            val beats = m.length / beat
            val whole = harmonyChords[b]
            val half = m.length / 2
            // Split only where the left hand changes harmony between the halves.
            val bassA = evs.filter { it.staff == Clef.BASS && it.start < half }.map { it.midi.mod(12) }.toSet()
            val bassB = evs.filter { it.staff == Clef.BASS && it.start >= half }.map { it.midi.mod(12) }.toSet()
            if (beats >= 4 && m.length % 2 == 0 && bassA.isNotEmpty() && bassB.isNotEmpty() && bassA != bassB) {
                val first = chordOfBar(evs.filter { it.start < half }, beat, sevenths = false)
                val second = chordOfBar(evs.filter { it.start >= half }.map { Ev(it.bar, it.start - half, it.dur, it.midi, it.staff, it.dots, it.tuplet, it.base) }, beat, sevenths = false)
                if (first != null && second != null && first.symbol != second.symbol) listOf(first, second) else listOfNotNull(whole)
            } else listOfNotNull(whole)
        }
        val harmony = halves.map { cs ->
            if (cs.isEmpty()) "–" else cs.joinToString(" – ") { roman(it, tonic, minor) } + " (" + cs.joinToString(" – ") { it.symbol } + ")"
        }
        val numerals = halves.withIndex().flatMap { (b, cs) -> cs.map { b to roman(it, tonic, minor) } }
        val progressions = findProgressions(numerals, minor)

        // ---- patterns
        val patterns = ArrayList<Finding>()
        for ((hand, evs) in listOf("Right hand" to right, "Left hand" to left)) {
            val line = melodicLine(evs, top = hand == "Right hand")
            fun runFinding(title: String, detail: String, minLen: Int, ok: (Int) -> Boolean) {
                val bars = HashSet<Int>()
                var k = 0
                while (k < line.size - 1) {
                    val dir = (line[k + 1].midi - line[k].midi).let { if (it > 0) 1 else if (it < 0) -1 else 0 }
                    var end = k
                    while (end + 1 < line.size) {
                        val iv = line[end + 1].midi - line[end].midi
                        if (iv == 0 || (if (iv > 0) 1 else -1) != dir || !ok(abs(iv))) break
                        end++
                    }
                    if (dir != 0 && end - k + 1 >= minLen) for (x in k..end) bars += line[x].bar
                    k = if (end > k) end else k + 1
                }
                if (bars.isNotEmpty()) patterns += Finding(title, detail, toRanges(bars), hand)
            }
            runFinding("Scale runs", "five or more notes moving by step in one direction", 5) { it in 1..2 }
            runFinding("Broken chords / arpeggios", "four or more notes moving by thirds or fourths in one direction", 4) { it in 3..5 }
            // Repeated notes.
            run {
                val bars = HashSet<Int>()
                var k = 0
                while (k < line.size) {
                    var e = k
                    while (e + 1 < line.size && line[e + 1].midi == line[k].midi) e++
                    if (e - k + 1 >= 4) for (x in k..e) bars += line[x].bar
                    k = e + 1
                }
                if (bars.isNotEmpty()) patterns += Finding("Repeated notes", "the same key four or more times in a row", toRanges(bars), hand)
            }
            // Leaps.
            run {
                val bars = line.zipWithNext().filter { (a, b) -> abs(b.midi - a.midi) >= 10 }.map { it.second.bar }
                if (bars.isNotEmpty()) patterns += Finding("Big leaps", "jumps of a minor seventh or more", toRanges(bars), hand)
            }
            // Chords.
            run {
                val bars = evs.groupBy { it.bar to it.start }.filter { it.value.size >= 3 }.keys.map { it.first }
                if (bars.isNotEmpty()) patterns += Finding("Chords", "three or more notes at once", toRanges(bars), hand)
            }
            // Hand position shifts and wide spans.
            run {
                val centers = (0 until n).map { b -> evs.filter { it.bar == b }.takeIf { it.isNotEmpty() }?.let { l -> (l.minOf { it.midi } + l.maxOf { it.midi }) / 2 } }
                val shifts = (1 until n).filter { b -> centers[b] != null && centers[b - 1] != null && abs(centers[b]!! - centers[b - 1]!!) > 7 }
                if (shifts.isNotEmpty()) patterns += Finding("Hand position shifts", "the hand moves to a new position (more than a fifth)", toRanges(shifts), hand)
                val wide = (0 until n).filter { b -> evs.filter { it.bar == b }.let { l -> l.isNotEmpty() && l.maxOf { it.midi } - l.minOf { it.midi } > 12 } }
                if (wide.isNotEmpty()) patterns += Finding("Wide spans", "notes more than an octave apart within one bar", toRanges(wide), hand)
            }
        }
        // Alberti bass: low–high–middle–high in the left hand.
        run {
            val line = melodicLine(left, top = false)
            val bars = HashSet<Int>()
            for (k in 0..line.size - 4) {
                val (a, b, c, d) = line.subList(k, k + 4).map { it.midi }
                if (a < c && c < b && b == d) bars += line[k].bar
            }
            if (bars.size >= 1) patterns += Finding("Alberti bass", "left hand plays low–high–middle–high broken chords", toRanges(bars), "Left hand")
        }
        // Accidentals and rhythms (both hands).
        run {
            val scale = scalePcs(fifths) + if (minor) setOf((tonic + 11).mod(12)) else emptySet() // leading note in minor
            val acc = events.filter { it.midi.mod(12) !in scale }.map { it.bar }
            if (acc.isNotEmpty()) patterns += Finding("Accidentals", "notes outside the key", toRanges(acc))
            val dotted = events.filter { it.dots > 0 }.map { it.bar }
            if (dotted.isNotEmpty()) patterns += Finding("Dotted rhythms", "dotted notes: hold the dot, the next note comes late", toRanges(dotted))
            val tuplets = events.filter { it.tuplet }.map { it.bar }
            if (tuplets.isNotEmpty()) patterns += Finding("Triplets / tuplets", "notes in groups of three (or other tuplets)", toRanges(tuplets))
            val sync = events.filter { it.start % beat != 0 && (it.start % beat) + it.dur > beat && !it.tuplet }.map { it.bar }
            if (sync.isNotEmpty()) patterns += Finding("Syncopation", "notes starting off the beat and held over it", toRanges(sync))
            val fast = events.filter { it.base <= TICKS_PER_QUARTER / 4 }.map { it.bar }
            if (fast.isNotEmpty()) patterns += Finding("Fast notes", "sixteenth notes or shorter", toRanges(fast))
        }

        // ---- difficulty per bar
        val tempoFactor = (score.bpm / 100.0).coerceIn(0.6, 2.0)
        val leapBars = patterns.filter { it.title == "Big leaps" }.flatMap { f -> f.bars.flatMap { it.toList() } }
        val shiftBars = patterns.filter { it.title == "Hand position shifts" }.flatMap { f -> f.bars.flatMap { it.toList() } }
        val scale = scalePcs(fifths)
        data class BarScore(val score: Double, val reasons: List<String>)
        val barScores = (0 until n).map { b ->
            val evs = byBar[b].orEmpty()
            val onsets = evs.groupBy { it.start to it.staff }
            val chordNotes = onsets.values.filter { it.size >= 3 }.sumOf { it.size }
            val acc = evs.count { it.midi.mod(12) !in scale }
            val fast = evs.count { it.base <= TICKS_PER_QUARTER / 4 }
            val tup = evs.count { it.tuplet }
            val leaps = leapBars.count { it == b }
            val shifts = shiftBars.count { it == b }
            val reasons = buildList {
                if (evs.size >= 12) add("many notes")
                if (chordNotes >= 6) add("chords")
                if (leaps > 0) add("leaps")
                if (shifts > 0) add("hand shifts")
                if (acc >= 2) add("accidentals")
                if (fast >= 4) add("fast notes")
                if (tup > 0) add("triplets")
                val hands = evs.map { it.staff }.toSet().size
                if (hands == 2 && evs.size >= 8) add("both hands busy")
            }
            BarScore((evs.size + chordNotes * 0.5 + leaps * 3 + acc + fast * 0.8 + tup * 1.5 + shifts * 3) * tempoFactor, reasons)
        }
        val mean = barScores.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
        val sd = sqrt(barScores.map { (it.score - mean) * (it.score - mean) }.average().takeIf { !it.isNaN() } ?: 0.0)
        val hardBars = (0 until n).filter { barScores[it].score >= mean + 0.8 * sd && barScores[it].score > 4 }
        val hardSpots = toRanges(hardBars).flatMap { r -> r.chunked(4).map { it.first()..it.last() } }
            .map { r -> HardSpot(r, r.sumOf { barScores[it].score } / r.count(), r.flatMap { barScores[it].reasons }.distinct()) }
            .sortedByDescending { it.score }.take(4).sortedBy { it.bars.first }
        val difficulty = when {
            mean < 4 -> 1
            mean < 7 -> 2
            mean < 11 -> 3
            mean < 16 -> 4
            else -> 5
        }

        // ---- practice plan
        val plan = ArrayList<PlanStep>()
        val letters = form.map { it.second.trimEnd('′') }
        val distinct = letters.distinct()
        if (form.size >= 2 && distinct.size < form.size) {
            val counts = distinct.associateWith { l -> letters.count { it == l } }
            plan += PlanStep(
                "The form is ${form.joinToString(" ") { it.second }}: only ${distinct.size} different phrase${if (distinct.size == 1) "" else "s"} to learn " +
                    "(${distinct.joinToString(", ") { "$it ×${counts[it]}" }}).",
            )
            for (l in distinct.filter { (counts[it] ?: 0) > 1 }) {
                val ph = form.first { it.second.trimEnd('′') == l }.first
                val again = (counts[l] ?: 1) - 1
                plan += PlanStep("Learn phrase $l (${barsText(listOf(ph))}) first — it comes back $again more time${if (again == 1) "" else "s"}.", ph)
            }
            val once = distinct.filter { (counts[it] ?: 0) == 1 }
            if (once.isNotEmpty()) plan += PlanStep(
                "Then the phrase${if (once.size == 1) "" else "s"} that appear${if (once.size == 1) "s" else ""} once: " +
                    once.joinToString(", ") { l -> form.first { it.second.trimEnd('′') == l }.first.let { r -> "$l (${r.first + 1}–${r.last + 1})" } } + ".",
            )
        }
        kept.filter { it.shift == 0 }.take(3).forEach { r ->
            plan += PlanStep("${barsText(listOf(r.again)).replaceFirstChar { it.uppercase() }} repeat ${barsText(listOf(r.first))} exactly: learn them once.")
        }
        kept.filter { it.shift != 0 }.take(2).forEach { r ->
            plan += PlanStep(
                "${barsText(listOf(r.again)).replaceFirstChar { it.uppercase() }} are ${barsText(listOf(r.first))} moved ${intervalName(r.shift)}: " +
                    "same fingering and shape, just in a new place.",
            )
        }
        for (h in hardSpots) {
            plan += PlanStep(
                "${barsText(listOf(h.bars)).replaceFirstChar { it.uppercase() }} ${if (h.bars.first == h.bars.last) "is" else "are"} among the hardest" +
                    (if (h.reasons.isNotEmpty()) " (${h.reasons.joinToString(", ")})" else "") +
                    ": loop ${if (h.bars.first == h.bars.last) "it" else "them"} hands separately at about 70% tempo, then hands together.",
                h.bars,
            )
        }
        val find = { t: String -> patterns.filter { it.title == t } }
        if (find("Scale runs").isNotEmpty()) plan += PlanStep("There are scale runs (${find("Scale runs").joinToString("; ") { "${it.hand?.lowercase()}: ${barsText(it.bars)}" }}): practise the $keyName scale with a steady fingering first.")
        if (find("Alberti bass").isNotEmpty()) plan += PlanStep("The left hand uses Alberti bass: play each bar as a block chord first, then break it up.")
        if (find("Broken chords / arpeggios").isNotEmpty()) plan += PlanStep("Broken chords: find the chord shape under your hand first, then play it note by note.")
        if (find("Big leaps").isNotEmpty()) plan += PlanStep("Leaps (${find("Big leaps").joinToString("; ") { "${it.hand?.lowercase()}: ${barsText(it.bars)}" }}): practise just the jump, eyes ahead, until it lands without looking.")
        if (find("Accidentals").isNotEmpty()) plan += PlanStep("Accidentals in ${barsText(find("Accidentals").first().bars)}: mark them before playing; they're the most common wrong notes.")
        if (find("Dotted rhythms").isNotEmpty() || find("Syncopation").isNotEmpty()) plan += PlanStep("Tricky rhythm: clap or tap it with the metronome first (Rhythm imitation helps).")
        if (progressions.isNotEmpty()) plan += PlanStep("Harmony: ${progressions.first().title} appears ${progressions.first().detail}. Knowing these chords in $keyName makes reading faster.")
        val start = (score.bpm * 0.65).roundToInt()
        plan += PlanStep("Start at about $start BPM (65%) and raise it by 5 BPM after two clean run-throughs.")

        fun range(evs: List<Ev>) = if (evs.isEmpty()) "–" else "${SpelledNote.spell(evs.minOf { it.midi }).name}–${SpelledNote.spell(evs.maxOf { it.midi }).name}"
        return PieceAnalysis(
            bars = n, keyName = keyName, meter = meter?.signature?.let { "${it.numerator}/${it.denominator}" } ?: "–", bpm = score.bpm,
            notesRight = right.size, notesLeft = left.size, rangeRight = range(right), rangeLeft = range(left),
            difficulty = difficulty, form = form, repeats = kept, harmony = harmony, progressions = progressions,
            patterns = patterns, hardSpots = hardSpots, plan = plan, repeatedPercent = repeatedPercent,
        )
    }

    private fun exactKey(evs: List<Ev>): String =
        evs.sortedWith(compareBy({ it.staff }, { it.start }, { it.midi })).joinToString(";") { "${it.staff.ordinal}:${it.start}:${it.dur}:${it.midi}" }

    /** Same rhythm and intervals (for transposed repeats). */
    private fun shapeKey(evs: List<Ev>): String {
        val base = evs.minOfOrNull { it.midi } ?: return ""
        return evs.sortedWith(compareBy({ it.staff }, { it.start }, { it.midi })).joinToString(";") { "${it.staff.ordinal}:${it.start}:${it.dur}:${it.midi - base}" }
    }

    private fun phraseSimilarity(a: IntRange, b: IntRange, byBar: Map<Int, List<Ev>>): Double {
        val sims = a.zip(b).map { (x, y) ->
            val sx = byBar[x].orEmpty().map { "${it.staff}:${it.start}:${it.midi}" }.toSet()
            val sy = byBar[y].orEmpty().map { "${it.staff}:${it.start}:${it.midi}" }.toSet()
            if (sx.isEmpty() && sy.isEmpty()) 1.0 else (sx intersect sy).size.toDouble() / (sx union sy).size
        }
        return if (a.count() != b.count()) sims.sum() / max(a.count(), b.count()) else sims.average()
    }

    /** One note per onset: the top (right hand) or bottom (left hand) note. */
    private fun melodicLine(evs: List<Ev>, top: Boolean): List<Ev> =
        evs.groupBy { it.bar to it.start }.toSortedMap(compareBy({ it.first }, { it.second }))
            .values.map { group -> if (top) group.maxBy { it.midi } else group.minBy { it.midi } }

    class Chord(val root: Int, val quality: String) {
        val minorLike: Boolean get() = quality == "m" || quality == "m7"
        val symbol: String get() = PC_NAMES[root] + quality
    }

    private val PC_NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val TEMPLATES = listOf("" to listOf(0, 4, 7), "m" to listOf(0, 3, 7), "dim" to listOf(0, 3, 6), "7" to listOf(0, 4, 7, 10), "maj7" to listOf(0, 4, 7, 11), "m7" to listOf(0, 3, 7, 10))

    /** The chord that best explains a bar's notes (weighted by length; bass note as a tie-breaker). */
    private fun chordOfBar(evs: List<Ev>, beat: Int, sevenths: Boolean = true): Chord? {
        if (evs.isEmpty()) return null
        val w = DoubleArray(12)
        for (e in evs) w[e.midi.mod(12)] += e.dur.toDouble() + if (e.start % beat == 0) beat * 0.5 else 0.0
        val bass = evs.filter { it.start < beat }.minByOrNull { it.midi }?.midi?.mod(12) ?: evs.minBy { it.midi }.midi.mod(12)
        var best: Pair<Chord, Double>? = null
        for (root in 0 until 12) for ((q, iv) in TEMPLATES) {
            if (iv.size == 4 && !sevenths) continue
            val tones = iv.map { (root + it) % 12 }.toSet()
            // A seventh chord only when the seventh really sounds (not a passing note).
            if (iv.size == 4 && w[(root + iv[3]) % 12] < 0.3 * (w.maxOrNull() ?: 0.0)) continue
            var s = 0.0
            for (pc in 0 until 12) s += if (pc in tones) w[pc] else -0.6 * w[pc]
            if (iv.size == 4) s -= w.sum() * 0.08 // prefer triads unless the seventh is really there
            if (root == bass) s += w.sum() * 0.1
            if (tones.any { w[it] == 0.0 } && iv.size == 4) s -= w.sum() * 0.2
            if (best == null || s > best.second) best = Chord(root, q) to s
        }
        return best?.first
    }

    private val MAJOR_DEGREES = mapOf(0 to "I", 2 to "II", 4 to "III", 5 to "IV", 7 to "V", 9 to "VI", 11 to "VII", 1 to "♭II", 3 to "♭III", 6 to "♯IV", 8 to "♭VI", 10 to "♭VII")
    private val MINOR_DEGREES = mapOf(0 to "I", 2 to "II", 3 to "III", 5 to "IV", 7 to "V", 8 to "VI", 10 to "VII", 11 to "VII", 1 to "♭II", 4 to "♮III", 6 to "♯IV", 9 to "♮VI")

    fun roman(c: Chord, tonic: Int, minor: Boolean): String {
        val deg = (c.root - tonic).mod(12)
        val base = (if (minor) MINOR_DEGREES else MAJOR_DEGREES)[deg] ?: "?"
        val lower = c.quality.startsWith("m") && !c.quality.startsWith("maj") || c.quality == "dim"
        val numeral = if (lower) base.lowercase() else base
        return numeral + when (c.quality) {
            "dim" -> "°"; "7" -> "7"; "maj7" -> "maj7"; "m7" -> "7"; else -> ""
        }
    }

    /** [numerals]: (bar, roman numeral) in order, possibly two per bar. */
    private fun findProgressions(numerals: List<Pair<Int, String>>, minor: Boolean): List<Finding> {
        // Collapse repeated chords, remembering where each chord starts.
        val seq = ArrayList<Pair<String, Int>>()
        for ((b, r) in numerals) if (seq.isEmpty() || seq.last().first != r) seq += r to b
        val names = seq.map { it.first.removeSuffix("7").removeSuffix("maj") }
        val patterns = if (minor) listOf(
            listOf("i", "iv", "V", "i") to "i – iv – V – i", listOf("i", "VI", "III", "VII") to "i – VI – III – VII",
            listOf("ii°", "V", "i") to "ii° – V – i", listOf("V", "i") to "the cadence V – i",
        ) else listOf(
            listOf("I", "IV", "V", "I") to "I – IV – V – I", listOf("ii", "V", "I") to "ii – V – I", listOf("I", "V", "vi", "IV") to "I – V – vi – IV",
            listOf("I", "vi", "IV", "V") to "I – vi – IV – V", listOf("IV", "V", "I") to "IV – V – I", listOf("V", "I") to "the cadence V – I",
        )
        val out = ArrayList<Finding>()
        val used = HashSet<Int>()
        for ((pat, label) in patterns) {
            val at = (0..names.size - pat.size).filter { i -> pat.indices.all { names[i + it] == pat[it] } && (i until i + pat.size).none { it in used && pat.size == 2 } }
            if (at.isEmpty()) continue
            if (pat.size > 2) at.forEach { i -> (i until i + pat.size).forEach { used += it } }
            val bars = at.map { i -> seq[i].second..seq[i + pat.size - 1].second }
            out += Finding(label, "${at.size}× (${barsText(bars)})", bars)
        }
        return out
    }

    private fun intervalName(semitones: Int): String {
        val names = listOf("", "a semitone", "a tone", "a minor third", "a major third", "a fourth", "a tritone", "a fifth", "a minor sixth", "a major sixth", "a minor seventh", "a major seventh", "an octave")
        val a = abs(semitones) % 12
        val dir = if (semitones > 0) "up" else "down"
        return if (abs(semitones) == 12) "an octave $dir" else "${names[a]} $dir"
    }
}
