package com.musictrainer.chords

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.player.NoteSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

enum class Interval(val semitones: Int, val short: String, val longName: String) {
    UNISON(0, "P1", "Unison"),
    MINOR_2ND(1, "m2", "Minor 2nd"),
    MAJOR_2ND(2, "M2", "Major 2nd"),
    MINOR_3RD(3, "m3", "Minor 3rd"),
    MAJOR_3RD(4, "M3", "Major 3rd"),
    PERFECT_4TH(5, "P4", "Perfect 4th"),
    TRITONE(6, "TT", "Tritone"),
    PERFECT_5TH(7, "P5", "Perfect 5th"),
    MINOR_6TH(8, "m6", "Minor 6th"),
    MAJOR_6TH(9, "M6", "Major 6th"),
    MINOR_7TH(10, "m7", "Minor 7th"),
    MAJOR_7TH(11, "M7", "Major 7th"),
    OCTAVE(12, "P8", "Octave"),
}

/** How the two notes are played. */
enum class IntervalDirection(val label: String) { UP("Ascending"), DOWN("Descending"), TOGETHER("Together") }

class IntervalLevel(val number: Int, val name: String, val intervals: List<Interval>, val directions: List<IntervalDirection>)

object IntervalLevels {
    private val i = Interval.entries
    private val up = listOf(IntervalDirection.UP)
    private val base = listOf(Interval.UNISON, Interval.PERFECT_5TH, Interval.OCTAVE)
    private val l2 = base + Interval.PERFECT_4TH
    private val l3 = l2 + listOf(Interval.MAJOR_3RD, Interval.MINOR_3RD)
    private val l4 = l3 + listOf(Interval.MAJOR_2ND, Interval.MINOR_2ND)
    private val l5 = l4 + listOf(Interval.MAJOR_6TH, Interval.MINOR_6TH)

    val all = listOf(
        IntervalLevel(1, "Octave, fifth, unison", base, up),
        IntervalLevel(2, "Adding the fourth", l2, up),
        IntervalLevel(3, "Thirds", l3, up),
        IntervalLevel(4, "Seconds", l4, up),
        IntervalLevel(5, "Sixths", l5, up),
        IntervalLevel(6, "Sevenths and the tritone", i, up),
        IntervalLevel(7, "Up and down", i, listOf(IntervalDirection.UP, IntervalDirection.DOWN)),
        IntervalLevel(8, "Up, down and together", i, IntervalDirection.entries),
    ).map { l -> IntervalLevel(l.number, l.name, l.intervals.sortedBy { it.semitones }, l.directions) }

    fun of(n: Int) = all[(n - 1).coerceIn(0, all.lastIndex)]
}

@Serializable
data class IntervalSettings(
    val level: Int = 1,
    val questions: Int = 10,
    val focusWeak: Boolean = true,
) {
    fun sanitized() = copy(level = level.coerceIn(1, IntervalLevels.all.size), questions = questions.coerceIn(1, 100))
}

/** One interval session (stored as [EarRecord]s, misses keyed by interval name). */
class IntervalSession(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val sink: () -> NoteSink,
    private val loadStats: () -> EarStats,
    private val saveStats: (EarStats) -> Unit,
    private val onFinished: (EarRecord) -> Unit,
) {
    enum class Phase { ASK, RIGHT, WRONG, DONE }

    var settings by mutableStateOf(IntervalSettings())
        private set
    var phase by mutableStateOf(Phase.ASK)
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var interval by mutableStateOf<Interval?>(null)
        private set
    var direction by mutableStateOf(IntervalDirection.UP)
        private set
    var answer by mutableStateOf<Interval?>(null)
        private set
    var lastRecord by mutableStateOf<EarRecord?>(null)
        private set
    val results = mutableStateListOf<Boolean>()
    /** The first note played. */
    var low = 60
        private set
    private val missed = HashMap<String, Int>()
    private var startedAt = 0L
    private var random: Random = Random.Default
    private var job: Job? = null
    private var sounding: List<Int> = emptyList()

    val level: IntervalLevel get() = IntervalLevels.of(settings.level)

    fun start(newSettings: IntervalSettings, seed: Long? = null) {
        stopSound()
        settings = newSettings.sanitized()
        random = seed?.let { Random(it) } ?: Random.Default
        index = 0; correct = 0; results.clear(); missed.clear(); lastRecord = null; interval = null
        startedAt = now()
        phase = Phase.RIGHT
        next()
    }

    fun next() {
        if (phase == Phase.ASK) return
        if (index >= settings.questions) { finish(true); return }
        index++
        val pool = level.intervals.filter { it != interval || level.intervals.size <= 2 }
        val iv = if (!settings.focusWeak) pool.random(random) else {
            val stats = loadStats()
            val w = pool.map { val r = stats.errorRateOf(it.name); 0.15 + r * r * 4 }
            var x = random.nextDouble() * w.sum()
            pool.withIndex().firstOrNull { (k, _) -> x -= w[k]; x <= 0 }?.value ?: pool.last()
        }
        interval = iv
        direction = level.directions.random(random)
        low = 52 + random.nextInt(13) // E3 … E4
        answer = null
        phase = Phase.ASK
        play(iv)
    }

    private fun notesOf(iv: Interval): List<Int> = when (direction) {
        IntervalDirection.UP, IntervalDirection.TOGETHER -> listOf(low, low + iv.semitones)
        IntervalDirection.DOWN -> listOf(low + iv.semitones, low)
    }

    fun replay() { interval?.let(::play) }
    fun playAnswer() { answer?.let(::play) }

    private fun play(iv: Interval) {
        stopSound()
        val out = sink()
        val notes = notesOf(iv)
        job = scope.launch {
            sounding = notes
            if (direction == IntervalDirection.TOGETHER) {
                notes.forEach { out.noteOn(it, 88) }; delay(1500); notes.forEach(out::noteOff)
            } else {
                out.noteOn(notes[0], 88); delay(800); out.noteOff(notes[0])
                out.noteOn(notes[1], 88); delay(1100); out.noteOff(notes[1])
            }
            sounding = emptyList()
        }
    }

    private fun stopSound() {
        job?.cancel()
        sounding.forEach { sink().noteOff(it) }
        sounding = emptyList()
    }

    fun choose(iv: Interval) {
        if (phase != Phase.ASK) return
        val t = interval ?: return
        answer = iv
        val right = iv == t
        saveStats(loadStats().recordId(t.name, right))
        results += right
        if (right) { correct++; phase = Phase.RIGHT } else {
            missed[t.name] = (missed[t.name] ?: 0) + 1
            phase = Phase.WRONG
            play(t)
        }
    }

    private fun finish(completed: Boolean) {
        stopSound()
        phase = Phase.DONE
        val rec = EarRecord(now(), settings.level, results.size, correct, missed.toMap(), now() - startedAt, completed)
        lastRecord = rec
        if (rec.questions > 0) onFinished(rec)
    }

    fun abandon() {
        stopSound()
        if (phase != Phase.DONE && results.isNotEmpty()) finish(false)
    }
}
