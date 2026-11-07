package com.musictrainer.chords

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.model.starRating
import com.musictrainer.player.NoteSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Ear-training levels: the chord types that can be played, which are also the answer options. */
class EarLevel(val number: Int, val name: String, val qualities: List<ChordQuality>, val inversions: Boolean)

object EarLevels {
    private val q = ChordQuality.entries
    val all = listOf(
        EarLevel(1, "Major or minor", listOf(ChordQuality.MAJOR, ChordQuality.MINOR), false),
        EarLevel(2, "Major, minor, diminished", listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DIMINISHED), false),
        EarLevel(3, "All triads", listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DIMINISHED, ChordQuality.AUGMENTED), false),
        EarLevel(4, "Triads and suspended chords", q.take(6), false),
        EarLevel(5, "Triads in inversions", q.take(6), true),
        EarLevel(6, "Seventh chords", q.take(4) + listOf(ChordQuality.DOMINANT7, ChordQuality.MAJOR7, ChordQuality.MINOR7), false),
        EarLevel(7, "All sevenths and sixths", q.take(6) + q.subList(6, 13), true),
        EarLevel(8, "Every chord", q, true),
    )

    fun of(n: Int) = all[(n - 1).coerceIn(0, all.lastIndex)]
}

@Serializable
enum class EarPlayStyle(val label: String) { TOGETHER("Together"), ARPEGGIO("Broken (low to high)"), BOTH("Broken, then together") }

@Serializable
data class EarSettings(
    val level: Int = 1,
    val questions: Int = 10,
    val playStyle: EarPlayStyle = EarPlayStyle.BOTH,
    /** Ask chord types you get wrong more often (off = truly random). */
    val focusWeak: Boolean = true,
) {
    fun sanitized() = copy(level = level.coerceIn(1, EarLevels.all.size), questions = questions.coerceIn(1, 100))
}

/** How often each chord type was asked and missed, across sessions (drives [EarSettings.focusWeak]). */
@Serializable
data class EarStats(val asked: Map<String, Int> = emptyMap(), val wrong: Map<String, Int> = emptyMap()) {
    fun errorRate(q: ChordQuality): Double = errorRateOf(q.name)
    fun errorRateOf(id: String): Double = ((wrong[id] ?: 0) + 1.0) / ((asked[id] ?: 0) + 2.0)
    fun record(q: ChordQuality, correct: Boolean) = recordId(q.name, correct)
    fun recordId(id: String, correct: Boolean) = EarStats(
        asked + (id to (asked[id] ?: 0) + 1),
        if (correct) wrong else wrong + (id to (wrong[id] ?: 0) + 1),
    )
}

@Serializable
data class EarRecord(
    val timestamp: Long,
    val level: Int,
    val questions: Int,
    val correct: Int,
    /** Chord type name → times it was mistaken in this session. */
    val missed: Map<String, Int> = emptyMap(),
    val durationMs: Long,
    val completed: Boolean = true,
) {
    val accuracyPercent: Int get() = if (questions == 0) 0 else correct * 100 / questions
    val stars: Int get() = starRating(correct, questions - correct)
    val score: Int get() = maxOf(0, correct * 10 - (questions - correct) * 5)
}

/** Picks the next chord type: weighted towards weak ones (error rate squared), or uniformly. */
fun pickQuality(level: EarLevel, stats: EarStats, focusWeak: Boolean, random: Random, avoid: ChordQuality? = null): ChordQuality {
    val pool = level.qualities.filter { it != avoid || level.qualities.size == 1 }
    if (!focusWeak) return pool.random(random)
    val weights = pool.map { val r = stats.errorRate(it); 0.15 + r * r * 4 }
    var x = random.nextDouble() * weights.sum()
    for ((i, w) in weights.withIndex()) { x -= w; if (x <= 0) return pool[i] }
    return pool.last()
}

/** One chord-identification session. */
class EarSession(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val sink: () -> NoteSink,
    private val loadStats: () -> EarStats,
    private val saveStats: (EarStats) -> Unit,
    private val onFinished: (EarRecord) -> Unit,
) {
    enum class Phase { ASK, RIGHT, WRONG, DONE }

    var settings by mutableStateOf(EarSettings())
        private set
    var phase by mutableStateOf(Phase.ASK)
        private set
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var quality by mutableStateOf<ChordQuality?>(null)
        private set
    var answer by mutableStateOf<ChordQuality?>(null)
        private set
    var lastRecord by mutableStateOf<EarRecord?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    /** What is sounding right now: "broken chord (low to high)" / "block chord", or null. */
    var playingPart by mutableStateOf<String?>(null)
        private set
    val results = mutableStateListOf<Boolean>()
    /** The chord as played (MIDI notes, low to high). */
    var notes by mutableStateOf<List<Int>>(emptyList())
        private set
    /** Root of the chord being asked (MIDI). */
    var root = 60
        private set
    private var inversion = 0
    private val missed = HashMap<String, Int>()
    private var startedAt = 0L
    private var random: Random = Random.Default
    private var job: Job? = null

    val level: EarLevel get() = EarLevels.of(settings.level)

    fun start(newSettings: EarSettings, seed: Long? = null) {
        stopSound()
        settings = newSettings.sanitized()
        random = seed?.let { Random(it) } ?: Random.Default
        index = 0; correct = 0; results.clear(); missed.clear(); lastRecord = null
        startedAt = now()
        next()
    }

    fun next() {
        if (phase == Phase.ASK && index > 0) return
        if (index >= settings.questions) { finish(true); return }
        index++
        val q = pickQuality(level, loadStats(), settings.focusWeak, random, avoid = quality.takeIf { level.qualities.size > 2 })
        quality = q
        answer = null
        root = 48 + random.nextInt(13) // C3 … C4
        inversion = if (level.inversions) random.nextInt(minOf(3, q.intervals.size)) else 0
        notes = voicing(q, root, inversion)
        phase = Phase.ASK
        play(notes)
    }

    /** The notes of [q] on this question's root and inversion. */
    private fun voicing(q: ChordQuality, root: Int, inversion: Int): List<Int> {
        val tones = q.intervals.map { root + it }.sorted().toMutableList()
        repeat(inversion) { tones += tones.removeAt(0) + 12 }
        return tones.sorted()
    }

    fun replay() = play(notes)

    /** Plays the chord one way only: broken (low to high) or as a block chord. */
    fun playAs(style: EarPlayStyle) = play(notes, style)

    /** After a mistake: hear the chord you picked, on the same root and inversion. */
    fun playAnswer() { answer?.let { play(voicing(it, root, minOf(inversion, it.intervals.size - 1))) } }

    private fun play(chord: List<Int>, style: EarPlayStyle = settings.playStyle) {
        stopSound()
        val out = sink()
        playing = true
        job = scope.launch {
            val arp = style != EarPlayStyle.TOGETHER
            if (arp) {
                playingPart = "broken chord (low to high)"
                for (n in chord) { out.noteOn(n, 85); delay(380) }
                delay(250)
                chord.forEach(out::noteOff)
            }
            if (style != EarPlayStyle.ARPEGGIO) {
                if (arp) delay(200)
                playingPart = "block chord"
                chord.forEach { out.noteOn(it, 88) }
                delay(1600)
                chord.forEach(out::noteOff)
            }
            playing = false
            playingPart = null
        }
    }

    private fun stopSound() {
        job?.cancel()
        if (playing) notes.forEach { sink().noteOff(it) }
        playing = false
        playingPart = null
    }

    fun choose(q: ChordQuality) {
        if (phase != Phase.ASK) return
        val target = quality ?: return
        answer = q
        val right = q == target
        saveStats(loadStats().record(target, right))
        results += right
        if (right) { correct++; phase = Phase.RIGHT } else {
            missed[target.name] = (missed[target.name] ?: 0) + 1
            phase = Phase.WRONG
            play(notes) // play the right chord again with the correction
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
