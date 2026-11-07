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
import kotlin.math.abs
import kotlin.random.Random

/** A chord of a progression: [roman] numeral, root [offset] in semitones above the key's tonic, [quality]. */
class ProgChord(val roman: String, val offset: Int, val quality: ChordQuality)

/** A progression to recognise; [id] is stable (statistics, history). */
class Progression(val id: String, val chords: List<ProgChord>, val minor: Boolean = false) {
    val label: String get() = chords.joinToString(" – ") { it.roman }
}

private object Major {
    val I = ProgChord("I", 0, ChordQuality.MAJOR)
    val ii = ProgChord("ii", 2, ChordQuality.MINOR)
    val iii = ProgChord("iii", 4, ChordQuality.MINOR)
    val IV = ProgChord("IV", 5, ChordQuality.MAJOR)
    val V = ProgChord("V", 7, ChordQuality.MAJOR)
    val vi = ProgChord("vi", 9, ChordQuality.MINOR)
    val I7 = ProgChord("Imaj7", 0, ChordQuality.MAJOR7)
    val ii7 = ProgChord("ii7", 2, ChordQuality.MINOR7)
    val iii7 = ProgChord("iii7", 4, ChordQuality.MINOR7)
    val IV7 = ProgChord("IVmaj7", 5, ChordQuality.MAJOR7)
    val V7 = ProgChord("V7", 7, ChordQuality.DOMINANT7)
    val vi7 = ProgChord("vi7", 9, ChordQuality.MINOR7)
}

private object Minor {
    val i = ProgChord("i", 0, ChordQuality.MINOR)
    val iio = ProgChord("ii°", 2, ChordQuality.DIMINISHED)
    val III = ProgChord("III", 3, ChordQuality.MAJOR)
    val iv = ProgChord("iv", 5, ChordQuality.MINOR)
    val vMinor = ProgChord("v", 7, ChordQuality.MINOR)
    val V = ProgChord("V", 7, ChordQuality.MAJOR)
    val VI = ProgChord("VI", 8, ChordQuality.MAJOR)
    val VII = ProgChord("VII", 10, ChordQuality.MAJOR)
    val V7 = ProgChord("V7", 7, ChordQuality.DOMINANT7)
    val iiø = ProgChord("iiø7", 2, ChordQuality.HALF_DIMINISHED)
}

object Progressions {
    private fun p(id: String, vararg c: ProgChord) = Progression(id, c.toList())
    private fun m(id: String, vararg c: ProgChord) = Progression(id, c.toList(), minor = true)

    private val basic = with(Major) { listOf(p("I-IV-I", I, IV, I), p("I-V-I", I, V, I), p("I-IV-V-I", I, IV, V, I)) }
    private val pop = with(Major) {
        listOf(p("I-vi-IV-V", I, vi, IV, V), p("I-V-vi-IV", I, V, vi, IV), p("ii-V-I", ii, V, I), p("I-IV-I-V", I, IV, I, V))
    }
    private val minorKeys = with(Minor) {
        listOf(m("i-iv-v-i", i, iv, vMinor, i), m("i-iv-V-i", i, iv, V, i), m("i-VI-III-VII", i, VI, III, VII), m("i-VII-VI-V", i, VII, VI, V))
    }
    private val more = with(Major) {
        listOf(p("vi-IV-I-V", vi, IV, I, V), p("I-iii-IV-V", I, iii, IV, V), p("I-IV-vi-V", I, IV, vi, V), p("IV-V-iii-vi", IV, V, iii, vi), p("I-vi-ii-V", I, vi, ii, V))
    }
    private val sevenths = with(Major) {
        listOf(p("ii7-V7-Imaj7", ii7, V7, I7), p("Imaj7-vi7-ii7-V7", I7, vi7, ii7, V7), p("iii7-vi7-ii7-V7", iii7, vi7, ii7, V7), p("IVmaj7-V7-iii7-vi7", IV7, V7, iii7, vi7)) +
            with(Minor) { listOf(m("iiø7-V7-i", iiø, V7, i)) }
    }

    class Level(val number: Int, val name: String, val progressions: List<Progression>)

    val levels = listOf(
        Level(1, "Tonic, subdominant, dominant", basic),
        Level(2, "Pop and ii–V–I", basic + pop),
        Level(3, "Minor keys", basic + pop.take(2) + minorKeys),
        Level(4, "More major progressions", basic + pop + more),
        Level(5, "Major and minor mixed", basic + pop + more + minorKeys),
        Level(6, "Seventh-chord progressions", pop + sevenths),
        Level(7, "Everything", basic + pop + more + minorKeys + sevenths),
    )

    fun level(n: Int) = levels[(n - 1).coerceIn(0, levels.lastIndex)]
}

/**
 * Piano voicing of a progression in the key of [tonic] (MIDI of the tonic around C3): the left hand plays
 * each root low, the right hand moves to the closest inversion of the next chord (smooth voice leading).
 */
fun voiceProgression(p: Progression, tonic: Int): List<List<Int>> {
    var upper: List<Int>? = null
    return p.chords.map { c ->
        val rootPc = (tonic + c.offset).mod(12)
        val bass = 36 + (rootPc - 36).mod(12) // C2 … B2
        val pcs = c.quality.intervals.map { (rootPc + it).mod(12) }.distinct()
        // Candidates: every inversion of the chord tones, placed from around F3 to C5.
        val candidates = (0 until pcs.size).flatMap { inv ->
            val order = pcs.drop(inv) + pcs.take(inv)
            (53..64).filter { it.mod(12) == order[0] }.map { start ->
                val v = ArrayList<Int>()
                var prev = start
                v += start
                for (pc in order.drop(1)) { var n = prev + 1; while (n.mod(12) != pc) n++; v += n; prev = n }
                v
            }
        }.filter { it.last() <= 77 }
        val chosen = upper?.let { prev ->
            candidates.minByOrNull { cand -> cand.sumOf { n -> prev.minOf { abs(it - n) } } + abs(cand.average() - prev.average()).toInt() }
        } ?: candidates.minByOrNull { abs(it.first() - 60) }!!
        upper = chosen
        listOf(bass) + chosen
    }
}

@Serializable
data class ProgressionSettings(
    val level: Int = 1,
    val questions: Int = 10,
    /** Chords per minute. */
    val tempo: Int = 60,
    val focusWeak: Boolean = true,
) {
    fun sanitized() = copy(level = level.coerceIn(1, Progressions.levels.size), questions = questions.coerceIn(1, 100), tempo = tempo.coerceIn(20, 160))
}

/** Picks a progression, weighted towards ones often mistaken (or uniformly). */
fun pickProgression(level: Progressions.Level, stats: EarStats, focusWeak: Boolean, random: Random, avoid: String?): Progression {
    val pool = level.progressions.filter { it.id != avoid }.ifEmpty { level.progressions }
    if (!focusWeak) return pool.random(random)
    val weights = pool.map { val r = stats.errorRateOf(it.id); 0.15 + r * r * 4 }
    var x = random.nextDouble() * weights.sum()
    for ((i, w) in weights.withIndex()) { x -= w; if (x <= 0) return pool[i] }
    return pool.last()
}

/** One progression-identification session (results are stored as [EarRecord]s). */
class ProgressionSession(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val sink: () -> NoteSink,
    private val loadStats: () -> EarStats,
    private val saveStats: (EarStats) -> Unit,
    private val onFinished: (EarRecord) -> Unit,
) {
    enum class Phase { ASK, RIGHT, WRONG, DONE }

    var settings by mutableStateOf(ProgressionSettings())
        private set
    var phase by mutableStateOf(Phase.ASK)
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var progression by mutableStateOf<Progression?>(null)
        private set
    var answer by mutableStateOf<Progression?>(null)
        private set
    /** Chord being sounded (index), or −1. */
    var playingChord by mutableIntStateOf(-1)
        private set
    var lastRecord by mutableStateOf<EarRecord?>(null)
        private set
    val results = mutableStateListOf<Boolean>()
    var tonic = 48
        private set
    private val missed = HashMap<String, Int>()
    private var startedAt = 0L
    private var random: Random = Random.Default
    private var job: Job? = null
    private var sounding: List<Int> = emptyList()

    val level: Progressions.Level get() = Progressions.level(settings.level)

    fun start(newSettings: ProgressionSettings, seed: Long? = null) {
        stopSound()
        settings = newSettings.sanitized()
        random = seed?.let { Random(it) } ?: Random.Default
        index = 0; correct = 0; results.clear(); missed.clear(); lastRecord = null; progression = null
        startedAt = now()
        phase = Phase.RIGHT
        next()
    }

    fun next() {
        if (phase == Phase.ASK) return
        if (index >= settings.questions) { finish(true); return }
        index++
        val p = pickProgression(level, loadStats(), settings.focusWeak, random, progression?.id)
        progression = p
        answer = null
        tonic = 43 + random.nextInt(12) // G2 … F♯3
        phase = Phase.ASK
        play(p)
    }

    fun replay() { progression?.let(::play) }
    fun playAnswer() { answer?.let(::play) }

    private fun play(p: Progression) {
        stopSound()
        val out = sink()
        val chordMs = 60_000L / settings.tempo
        job = scope.launch {
            for ((i, chord) in voiceProgression(p, tonic).withIndex()) {
                playingChord = i
                sounding = chord
                chord.forEach { out.noteOn(it, if (it < 48) 80 else 88) }
                delay(chordMs * 9 / 10)
                chord.forEach(out::noteOff)
                delay(chordMs / 10)
            }
            sounding = emptyList()
            playingChord = -1
        }
    }

    private fun stopSound() {
        job?.cancel()
        sounding.forEach { sink().noteOff(it) }
        sounding = emptyList()
        playingChord = -1
    }

    fun choose(p: Progression) {
        if (phase != Phase.ASK) return
        val target = progression ?: return
        answer = p
        val right = p.id == target.id
        saveStats(loadStats().recordId(target.id, right))
        results += right
        if (right) { correct++; phase = Phase.RIGHT } else {
            missed[target.id] = (missed[target.id] ?: 0) + 1
            phase = Phase.WRONG
            play(target)
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
