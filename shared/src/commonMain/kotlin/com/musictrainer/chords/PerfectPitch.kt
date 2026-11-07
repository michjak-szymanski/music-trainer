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

/**
 * Perfect-pitch levels: which notes can sound ([pool], MIDI). [octaveOnly]: only the octave is asked (any
 * note in it counts); otherwise the exact note. [accidentals]: the answer staff offers ♯ / ♭.
 */
class PitchLevel(val number: Int, val name: String, val description: String, val pool: List<Int>, val octaveOnly: Boolean, val accidentals: Boolean)

object PitchLevels {
    private val white = setOf(0, 2, 4, 5, 7, 9, 11)
    private fun whites(range: IntRange) = range.filter { it.mod(12) in white }

    // Each level is harder than the one before.
    val all = listOf(
        PitchLevel(1, "Which octave?", "Hear a note, click anywhere in its octave", (36..83).toList(), octaveOnly = true, accidentals = false),
        PitchLevel(2, "Find middle C", "Middle C among the other Cs", listOf(36, 48, 60, 72), octaveOnly = false, accidentals = false),
        PitchLevel(3, "Middle C and G", "C4 and G4", listOf(60, 67), octaveOnly = false, accidentals = false),
        PitchLevel(4, "C, E and G", "The notes of the C major triad", listOf(60, 64, 67), octaveOnly = false, accidentals = false),
        PitchLevel(5, "Adding D and A", "C D E G A around middle C", listOf(60, 62, 64, 67, 69), octaveOnly = false, accidentals = false),
        PitchLevel(6, "All white keys", "C4 to B4", whites(60..71), octaveOnly = false, accidentals = false),
        PitchLevel(7, "Black keys too", "Every note from C4 to B4", (60..71).toList(), octaveOnly = false, accidentals = true),
        PitchLevel(8, "White keys, three octaves", "C3 to B5", whites(48..83), octaveOnly = false, accidentals = false),
        PitchLevel(9, "Every note, two octaves", "C3 to B4", (48..71).toList(), octaveOnly = false, accidentals = true),
        PitchLevel(10, "The whole range", "Every note from C2 to C6", (36..84).toList(), octaveOnly = false, accidentals = true),
    )

    fun of(n: Int) = all[(n - 1).coerceIn(0, all.lastIndex)]
}

@Serializable
data class PitchSettings(
    val level: Int = 1,
    val questions: Int = 10,
    val focusWeak: Boolean = true,
) {
    fun sanitized() = copy(level = level.coerceIn(1, PitchLevels.all.size), questions = questions.coerceIn(1, 100))
}

/** Whether answering [chosen] for the sounding [target] is right at [level]. */
fun pitchCorrect(level: PitchLevel, target: Int, chosen: Int): Boolean =
    if (level.octaveOnly) octaveOf(chosen) == octaveOf(target) else chosen == target

/** Octave number as in C4 = middle C (B♯/C♭ edge cases aside). */
fun octaveOf(midi: Int): Int = midi.floorDiv(12) - 1

/** One perfect-pitch session (stored as [EarRecord]s, misses keyed by MIDI number). */
class PitchSession(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val sink: () -> NoteSink,
    private val loadStats: () -> EarStats,
    private val saveStats: (EarStats) -> Unit,
    private val onFinished: (EarRecord) -> Unit,
) {
    enum class Phase { ASK, RIGHT, WRONG, DONE }

    var settings by mutableStateOf(PitchSettings())
        private set
    var phase by mutableStateOf(Phase.ASK)
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var target by mutableStateOf<Int?>(null)
        private set
    var answer by mutableStateOf<Int?>(null)
        private set
    var lastRecord by mutableStateOf<EarRecord?>(null)
        private set
    val results = mutableStateListOf<Boolean>()
    private val missed = HashMap<String, Int>()
    private var startedAt = 0L
    private var random: Random = Random.Default
    private var job: Job? = null
    private var sounding: Int? = null

    val level: PitchLevel get() = PitchLevels.of(settings.level)

    fun start(newSettings: PitchSettings, seed: Long? = null) {
        stopSound()
        settings = newSettings.sanitized()
        random = seed?.let { Random(it) } ?: Random.Default
        index = 0; correct = 0; results.clear(); missed.clear(); lastRecord = null; target = null
        startedAt = now()
        phase = Phase.RIGHT
        next()
    }

    fun next() {
        if (phase == Phase.ASK) return
        if (index >= settings.questions) { finish(true); return }
        index++
        val pool = level.pool.filter { it != target }.ifEmpty { level.pool }
        val t = if (!settings.focusWeak) pool.random(random) else {
            val stats = loadStats()
            val w = pool.map { val r = stats.errorRateOf("$it"); 0.15 + r * r * 4 }
            var x = random.nextDouble() * w.sum()
            pool.firstOrNull { x -= w[pool.indexOf(it)]; x <= 0 } ?: pool.last()
        }
        target = t
        answer = null
        phase = Phase.ASK
        play(t)
    }

    fun replay() { target?.let(::play) }
    fun playAnswer() { answer?.let(::play) }

    private fun play(midi: Int) {
        stopSound()
        val out = sink()
        job = scope.launch {
            sounding = midi
            out.noteOn(midi, 90)
            delay(1400)
            out.noteOff(midi)
            sounding = null
        }
    }

    private fun stopSound() {
        job?.cancel()
        sounding?.let { sink().noteOff(it) }
        sounding = null
    }

    fun choose(midi: Int) {
        if (phase != Phase.ASK) return
        val t = target ?: return
        answer = midi
        val right = pitchCorrect(level, t, midi)
        saveStats(loadStats().recordId("$t", right))
        results += right
        if (right) { correct++; phase = Phase.RIGHT } else {
            missed["$t"] = (missed["$t"] ?: 0) + 1
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
