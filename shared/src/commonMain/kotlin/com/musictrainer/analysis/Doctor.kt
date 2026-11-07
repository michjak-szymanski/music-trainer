package com.musictrainer.analysis

import com.musictrainer.model.SpelledNote
import com.musictrainer.model.isBlackKey
import com.musictrainer.player.BarStat
import com.musictrainer.player.HandsMode
import com.musictrainer.player.PlayStyle
import com.musictrainer.player.PlayerRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bar (0-based) with all its results in the period. */
class ProblemBar(val bar: Int, val stat: BarStat) {
    val attempts: Int get() = stat.hits + stat.wrong + stat.missed
    val errorRate: Double get() = if (attempts == 0) 0.0 else stat.mistakes.toDouble() / (attempts + stat.timing)
}

/** A wrong note played instead of the expected one, [count] times. */
class Confusion(val expected: Int, val played: Int, val count: Int)

/** A piece with its accuracy in the period (all-pieces view). */
class PieceSummary(val pieceId: String, val title: String, val sessions: Int, val accuracy: Int, val minutes: Int)

/** Advice; [loop] = bars to loop (1-based display handled by the UI), [pieceId] when it is about one piece. */
class Suggestion(val text: String, val loop: IntRange? = null, val pieceId: String? = null)

class DoctorReport(
    val sessions: Int,
    val days: Int,
    val minutes: Int,
    val accuracy: Int,
    /** Accuracy of the first and the last third of the sessions (trend), or null with too few sessions. */
    val trend: Pair<Int, Int>?,
    val wrong: Int, val missed: Int, val rhythm: Int, val length: Int,
    /** Accuracy by hands mode (only modes played). */
    val byHands: Map<HandsMode, Int>,
    val problemBars: List<ProblemBar>,
    val confusions: List<Confusion>,
    val pieces: List<PieceSummary>,
    val setTempo: Int?, val actualTempo: Int?,
    val suggestions: List<Suggestion>,
    /** Sessions in the period without per-bar details (recorded before 2.5). */
    val withoutDetails: Int,
    /** All bars with results (one piece), for comparing with the piece's patterns. */
    val barTotals: Map<Int, BarStat> = emptyMap(),
    /** Mistakes per play step (one piece). */
    val stepMistakes: Map<Int, Int> = emptyMap(),
    /** Wrong keys by place: (step, played MIDI) → count (one piece). */
    val wrongAt: Map<Pair<Int, Int>, Int> = emptyMap(),
)

/** A pattern of the piece and how you do in it compared with the rest. */
class WeakPattern(val finding: Finding, val rate: Double, val elsewhere: Double, val mistakes: Int)

object Doctor {
    /** Analyses [records] since [since] (ms), for one piece ([pieceId]) or all (null). [dayOf] maps a time to a day number. */
    fun report(records: List<PlayerRecord>, since: Long, pieceId: String?, dayOf: (Long) -> Long = { it / 86_400_000L }): DoctorReport {
        val rs = records.filter { it.timestamp >= since && (pieceId == null || it.pieceId == pieceId) && it.hands != HandsMode.NONE }
            .sortedBy { it.timestamp }
        val hits = rs.sumOf { it.hits }
        val wrong = rs.sumOf { it.wrong }
        val missed = rs.sumOf { it.missed }
        val rhythm = rs.sumOf { it.rhythmErrors }
        val length = rs.sumOf { it.lengthErrors }
        fun acc(list: List<PlayerRecord>): Int {
            val h = list.sumOf { it.hits }; val e = list.sumOf { it.errors }
            return if (h + e == 0) 0 else h * 100 / (h + e)
        }
        val accuracy = acc(rs)
        val trend = if (rs.size >= 3) rs.take(maxOf(1, rs.size / 3)).let(::acc) to rs.takeLast(maxOf(1, rs.size / 3)).let(::acc) else null
        val byHands = rs.groupBy { it.hands }.mapValues { acc(it.value) }

        // Bars (only meaningful for one piece).
        val bars = HashMap<Int, BarStat>()
        if (pieceId != null) for (r in rs) for ((b, s) in r.bars) {
            val o = bars[b] ?: BarStat()
            bars[b] = BarStat(o.hits + s.hits, o.wrong + s.wrong, o.missed + s.missed, o.timing + s.timing)
        }
        val problemBars = bars.map { ProblemBar(it.key, it.value) }
            .filter { it.attempts >= 3 && it.stat.mistakes > 0 }
            .sortedWith(compareByDescending<ProblemBar> { it.errorRate }.thenByDescending { it.stat.mistakes })
            .take(8)

        val conf = HashMap<Pair<Int, Int>, Int>()
        for (r in rs) for ((k, c) in r.confusions) {
            val (e, p) = k.split('>').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: continue
            conf[e to p] = (conf[e to p] ?: 0) + c
        }
        val confusions = conf.entries.sortedByDescending { it.value }.take(6).map { Confusion(it.key.first, it.key.second, it.value) }

        val pieces = rs.groupBy { it.pieceId }.map { (id, list) ->
            PieceSummary(id, list.last().title, list.size, acc(list), (list.sumOf { it.durationMs } / 60_000).toInt())
        }.sortedBy { it.accuracy }

        val setTempo = rs.map { it.setTempoPercent }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val actual = rs.filter { it.actualTempoPercent > 0 }.map { it.actualTempoPercent }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val days = rs.map { dayOf(it.timestamp) }.toSet().size

        // ---- suggestions
        val s = ArrayList<Suggestion>()
        if (rs.isEmpty()) {
            s += Suggestion("No sessions in this period. Play the piece a few times and come back — I'll show where the mistakes are.")
        } else {
            // Be honest about what is known: older sessions only have totals.
            val undetailed = rs.count { it.bars.isEmpty() && it.wrongAt.isEmpty() && it.hits + it.errors > 0 }
            if (undetailed == rs.size) s += Suggestion(
                (if (rs.size == 1) "This session was" else "These sessions were") + " played before Musigium recorded where mistakes happen, so I only know the " +
                    "totals — not the bars or the wrong keys. Play the piece again and I'll show them on the sheet.",
            ) else if (undetailed > 0) s += Suggestion("$undetailed of ${rs.size} sessions are older and have totals only; the places on the sheet come from the newer ones.")
            // Problem spots: merge neighbouring bad bars.
            if (problemBars.isNotEmpty()) {
                val worst = problemBars.take(4).map { it.bar }
                for (r in toRanges(worst).take(3)) {
                    val pb = problemBars.filter { it.bar in r }
                    val mistakes = pb.sumOf { it.stat.mistakes }
                    val kinds = buildList {
                        if (pb.sumOf { it.stat.wrong } * 2 >= mistakes) add("wrong keys")
                        if (pb.sumOf { it.stat.missed } * 2 >= mistakes) add("missed notes")
                        if (pb.sumOf { it.stat.timing } * 2 >= mistakes) add("timing")
                    }
                    s += Suggestion(
                        "${barsText(listOf(r)).replaceFirstChar { it.uppercase() }}: $mistakes mistake${if (mistakes == 1) "" else "s"}" +
                            (if (kinds.isNotEmpty()) " (mostly ${kinds.joinToString(" and ")})" else "") +
                            ". Loop ${if (r.first == r.last) "it" else "them"} slowly until three clean repeats in a row, then speed up.",
                        loop = r, pieceId = pieceId,
                    )
                }
            }
            val total = wrong + missed + rhythm + length
            if (total > 0) {
                when {
                    wrong * 2 > total -> s += Suggestion("Most mistakes are wrong keys (${pct(wrong, total)}%). Read one beat ahead and say the note names; Wait for me mode removes the time pressure.")
                    missed * 2 > total -> s += Suggestion("Most mistakes are missed notes (${pct(missed, total)}%): the tempo is too fast for now. Lower it by 10–15% or use Wait for me.")
                    rhythm * 2 > total -> s += Suggestion("Rhythm is the main issue (${pct(rhythm, total)}% of mistakes). Count aloud, or clap the rhythm first — the Rhythm imitation mode trains exactly this.")
                    length * 2 > total -> s += Suggestion("Notes are often too short or too long (${pct(length, total)}%). Listen to the end of each note before moving on.")
                }
            }
            for (c in confusions.take(2)) {
                if (c.count < 3) continue
                val e = SpelledNote.spell(c.expected).name
                val p = SpelledNote.spell(c.played, preferFlat = c.played < c.expected).name
                val diff = c.played - c.expected
                val why = when {
                    abs(diff) == 12 -> "the right note in the wrong octave — check the ledger lines and the clef."
                    abs(diff) == 1 && (isBlackKey(c.expected) || isBlackKey(c.played)) -> "a sharp or flat was missed — check the key signature and accidentals."
                    abs(diff) <= 2 -> "a neighbouring key — look at the finger you plan to use."
                    else -> "a reading slip — name this note aloud a few times."
                }
                s += Suggestion("You played $p instead of $e ${c.count} times: $why")
            }
            val rh = byHands[HandsMode.RIGHT]; val lh = byHands[HandsMode.LEFT]; val both = byHands[HandsMode.BOTH]
            if (rh != null && lh != null && abs(rh - lh) >= 10) {
                val weak = if (lh < rh) "left" else "right"
                s += Suggestion("Your $weak hand is weaker (${minOf(rh, lh)}% vs ${maxOf(rh, lh)}%): give it a few extra rounds alone.")
            }
            if (both != null && (rh ?: 0) > 0 && (lh ?: 0) > 0 && both + 10 < minOf(rh ?: 100, lh ?: 100)) {
                s += Suggestion("Each hand alone is fine, but hands together drops to $both%: put them together in short loops of one or two bars.")
            }
            if (both != null && rh == null && lh == null && accuracy < 80) {
                s += Suggestion("You've only played hands together. Try each hand alone first (Right / Left on the piece page) — it's usually faster overall.")
            }
            if (actual != null && setTempo != null && actual < setTempo * 0.8) {
                s += Suggestion("You actually play at about $actual% of the original tempo, while the tempo is set to $setTempo%. Set it closer to your real speed, then raise it gradually.")
            }
            if (rs.any { it.style == PlayStyle.FOLLOW_TEMPO } && accuracy < 70) {
                s += Suggestion("In Follow tempo the accuracy is low: switch to Wait for me until the notes are secure.")
            }
            if (trend != null) {
                val (a, b) = trend
                if (b >= a + 5) s += Suggestion("Good progress: accuracy went from $a% to $b% over this period.")
                else if (b + 5 <= a) s += Suggestion("Accuracy dropped from $a% to $b%. Maybe you're tired or the tempo went up too fast — slow down a little.")
            }
            if (accuracy >= 95 && rs.lastOrNull()?.completed == true) s += Suggestion("Accuracy is ${accuracy}%: you're ready to raise the tempo by 5–10%.")
            if (days in 1..2 && rs.size >= 3) s += Suggestion("You practised on $days day${if (days == 1) "" else "s"} only. Short daily sessions work better than a few long ones.")
            if (pieceId == null) pieces.firstOrNull()?.takeIf { pieces.size > 1 && it.accuracy < 80 }?.let {
                s += Suggestion("Your weakest piece is \"${it.title}\" (${it.accuracy}%): open it and check its Doctor tab for the problem bars.", pieceId = it.pieceId)
            }
        }

        return DoctorReport(
            sessions = rs.size, days = days, minutes = (rs.sumOf { it.durationMs } / 60_000).toInt(), accuracy = accuracy, trend = trend,
            wrong = wrong, missed = missed, rhythm = rhythm, length = length, byHands = byHands,
            problemBars = problemBars, confusions = confusions, pieces = pieces,
            setTempo = setTempo, actualTempo = actual, suggestions = s,
            withoutDetails = rs.count { it.bars.isEmpty() && it.hits + it.errors > 0 },
            barTotals = bars.toMap(),
            stepMistakes = if (pieceId == null) emptyMap() else HashMap<Int, Int>().also { m -> rs.forEach { r -> r.stepMistakes.forEach { (k, v) -> m[k] = (m[k] ?: 0) + v } } },
            wrongAt = if (pieceId == null) emptyMap() else HashMap<Pair<Int, Int>, Int>().also { m ->
                rs.forEach { r ->
                    r.wrongAt.forEach { (k, v) ->
                        val (st, mi) = k.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@forEach
                        m[st to mi] = (m[st to mi] ?: 0) + v
                    }
                }
            },
        )
    }

    /**
     * Which of the piece's patterns go worst: the mistake rate in a pattern's bars against the rest of the
     * piece. Only patterns with enough played notes, clearly worse than elsewhere, worst first.
     */
    fun weakPatterns(report: DoctorReport, analysis: PieceAnalysis): List<WeakPattern> {
        val bars = report.barTotals
        if (bars.isEmpty()) return emptyList()
        fun rate(sel: Collection<BarStat>): Double {
            val notes = sel.sumOf { it.hits + it.wrong + it.missed }
            return if (notes == 0) 0.0 else sel.sumOf { it.mistakes }.toDouble() / (notes + sel.sumOf { it.timing })
        }
        val candidates = analysis.patterns + analysis.hardSpots.map { Finding("Hard spots", it.reasons.joinToString(", "), listOf(it.bars)) }
        return candidates.mapNotNull { f ->
            val inside = f.bars.flatMap { it.toList() }.toSet()
            val inStats = bars.filterKeys { it in inside }.values
            val outStats = bars.filterKeys { it !in inside }.values
            val played = inStats.sumOf { it.hits + it.wrong + it.missed }
            if (played < 8) return@mapNotNull null
            val r = rate(inStats); val o = rate(outStats)
            if (r < 0.08 || r < o * 1.3) null else WeakPattern(f, r, o, inStats.sumOf { it.mistakes })
        }.sortedByDescending { it.rate - it.elsewhere }.take(6)
    }

    private fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b
}
