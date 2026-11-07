package com.musictrainer.chords

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.model.starRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Chord types: [intervals] in semitones above the root, [degrees] the scale step of each (0 = root,
 * 2 = third, 4 = fifth …) used to spell the notes.
 */
enum class ChordQuality(val suffix: String, val longName: String, val intervals: List<Int>, val degrees: List<Int>) {
    MAJOR("", "major", listOf(0, 4, 7), listOf(0, 2, 4)),
    MINOR("m", "minor", listOf(0, 3, 7), listOf(0, 2, 4)),
    DIMINISHED("dim", "diminished", listOf(0, 3, 6), listOf(0, 2, 4)),
    AUGMENTED("aug", "augmented", listOf(0, 4, 8), listOf(0, 2, 4)),
    SUS2("sus2", "suspended 2nd", listOf(0, 2, 7), listOf(0, 1, 4)),
    SUS4("sus4", "suspended 4th", listOf(0, 5, 7), listOf(0, 3, 4)),
    DOMINANT7("7", "dominant 7th", listOf(0, 4, 7, 10), listOf(0, 2, 4, 6)),
    MAJOR7("maj7", "major 7th", listOf(0, 4, 7, 11), listOf(0, 2, 4, 6)),
    MINOR7("m7", "minor 7th", listOf(0, 3, 7, 10), listOf(0, 2, 4, 6)),
    HALF_DIMINISHED("m7♭5", "half-diminished 7th", listOf(0, 3, 6, 10), listOf(0, 2, 4, 6)),
    DIMINISHED7("dim7", "diminished 7th", listOf(0, 3, 6, 9), listOf(0, 2, 4, 6)),
    SIXTH("6", "major 6th", listOf(0, 4, 7, 9), listOf(0, 2, 4, 5)),
    MINOR6("m6", "minor 6th", listOf(0, 3, 7, 9), listOf(0, 2, 4, 5)),
    ADD9("add9", "added 9th", listOf(0, 4, 7, 14), listOf(0, 2, 4, 1)),
    DOMINANT9("9", "dominant 9th", listOf(0, 4, 7, 10, 14), listOf(0, 2, 4, 6, 1)),
    MAJOR9("maj9", "major 9th", listOf(0, 4, 7, 11, 14), listOf(0, 2, 4, 6, 1)),
    MINOR9("m9", "minor 9th", listOf(0, 3, 7, 10, 14), listOf(0, 2, 4, 6, 1)),
}

private val LETTERS = "CDEFGAB"
private val LETTER_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/** A root as written: letter index (0 = C) and alteration (−1 flat, +1 sharp). */
data class Root(val letter: Int, val alter: Int) {
    val pc: Int get() = (LETTER_PC[letter] + alter).mod(12)
    val name: String get() = LETTERS[letter] + accidental(alter)
}

private fun accidental(a: Int) = when (a) { -2 -> "𝄫"; -1 -> "♭"; 1 -> "♯"; 2 -> "𝄪"; else -> "" }

private val NATURAL_ROOTS = (0..6).map { Root(it, 0) }
private val ALL_ROOTS = NATURAL_ROOTS + listOf(Root(0, 1), Root(1, -1), Root(2, -1), Root(3, 1), Root(4, -1), Root(5, -1), Root(6, -1))

/** The usual spelling of a root on pitch class [pc] (flats except F♯). */
fun preferredRoot(pc: Int): Root = listOf(
    Root(0, 0), Root(1, -1), Root(1, 0), Root(2, -1), Root(2, 0), Root(3, 0), Root(3, 1), Root(4, 0), Root(5, -1), Root(5, 0), Root(6, -1), Root(6, 0),
)[pc.mod(12)]

/** A chord to find. */
data class Chord(val root: Root, val quality: ChordQuality) {
    val symbol: String get() = root.name + quality.suffix
    val pitchClasses: Set<Int> get() = quality.intervals.map { (root.pc + it).mod(12) }.toSet()
    /** The chord tones spelled correctly, e.g. "E♭ G B♭". */
    val noteNames: List<String> get() = quality.intervals.zip(quality.degrees).map { (iv, deg) ->
        val letter = (root.letter + deg) % 7
        val natural = LETTER_PC[letter]
        var alter = ((root.pc + iv) - natural).mod(12)
        if (alter > 6) alter -= 12
        LETTERS[letter] + accidental(alter)
    }
    /** The fifth, which may be left out of four- and five-note chords. */
    val fifthPc: Int? get() = quality.intervals.zip(quality.degrees).firstOrNull { it.second == 4 }?.let { (root.pc + it.first).mod(12) }
}

class ChordLevel(val number: Int, val name: String, val roots: List<Root>, val qualities: List<ChordQuality>)

object ChordLevels {
    private val triads = listOf(ChordQuality.MAJOR, ChordQuality.MINOR)
    private val sevenths = listOf(ChordQuality.DOMINANT7, ChordQuality.MAJOR7, ChordQuality.MINOR7)
    private val moreSevenths = listOf(ChordQuality.HALF_DIMINISHED, ChordQuality.DIMINISHED7, ChordQuality.SIXTH, ChordQuality.MINOR6)
    private val ninths = listOf(ChordQuality.ADD9, ChordQuality.DOMINANT9, ChordQuality.MAJOR9, ChordQuality.MINOR9)
    private val dimAug = listOf(ChordQuality.DIMINISHED, ChordQuality.AUGMENTED)
    private val sus = listOf(ChordQuality.SUS2, ChordQuality.SUS4)

    val all = listOf(
        ChordLevel(1, "Major triads on white keys", NATURAL_ROOTS, listOf(ChordQuality.MAJOR)),
        ChordLevel(2, "Major and minor triads on white keys", NATURAL_ROOTS, triads),
        ChordLevel(3, "Major and minor triads on every root", ALL_ROOTS, triads),
        ChordLevel(4, "Diminished and augmented triads", ALL_ROOTS, triads + dimAug),
        ChordLevel(5, "Suspended chords", ALL_ROOTS, triads + dimAug + sus),
        ChordLevel(6, "Seventh chords", ALL_ROOTS, triads + sus + sevenths),
        ChordLevel(7, "More sevenths and sixths", ALL_ROOTS, triads + sevenths + moreSevenths),
        ChordLevel(8, "Ninth chords", ALL_ROOTS, sevenths + moreSevenths + ninths),
    )

    fun of(n: Int) = all[(n - 1).coerceIn(0, all.lastIndex)]
}

@Serializable
data class ChordSettings(
    val level: Int = 1,
    val questions: Int = 10,
    /** The root must be the lowest note (otherwise any inversion counts). */
    val rootInBass: Boolean = false,
    /** Four- and five-note chords also count without their fifth. */
    val allowOmitFifth: Boolean = true,
) {
    fun sanitized() = copy(level = level.coerceIn(1, ChordLevels.all.size), questions = questions.coerceIn(1, 100))
}

@Serializable
data class ChordRecord(
    val timestamp: Long,
    val level: Int,
    val chords: Int,
    /** Found without a wrong key. */
    val firstTry: Int,
    val wrongKeys: Int,
    val skipped: Int,
    val durationMs: Long,
    val completed: Boolean = true,
) {
    val found: Int get() = chords - skipped
    val accuracyPercent: Int get() = if (chords == 0) 0 else firstTry * 100 / chords
    val stars: Int get() = starRating(firstTry, chords - firstTry)
    val score: Int get() = maxOf(0, firstTry * 10 + (found - firstTry) * 5 - wrongKeys * 2)
    val secondsPerChord: Double get() = if (chords == 0) 0.0 else durationMs / 1000.0 / chords
}

/** Whether the held [keys] play [chord]. */
fun playsChord(chord: Chord, keys: Set<Int>, settings: ChordSettings): Boolean {
    if (keys.isEmpty()) return false
    val pcs = keys.map { it.mod(12) }.toSet()
    val target = chord.pitchClasses
    val ok = pcs == target || (settings.allowOmitFifth && target.size >= 4 && chord.fifthPc != null && pcs == target - chord.fifthPc!!)
    if (!ok) return false
    return !settings.rootInBass || keys.min().mod(12) == chord.root.pc
}

/** One chord-finder session: show a symbol, wait for the chord, move on. */
class ChordSession(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onFinished: (ChordRecord) -> Unit,
) {
    enum class Phase { ASK, CORRECT, REVEALED, DONE }

    var settings by mutableStateOf(ChordSettings())
        private set
    var chord by mutableStateOf<Chord?>(null)
        private set
    var phase by mutableStateOf(Phase.ASK)
        private set
    var index by mutableIntStateOf(0)
        private set
    var firstTry by mutableIntStateOf(0)
        private set
    var wrongKeys by mutableIntStateOf(0)
        private set
    var skipped by mutableIntStateOf(0)
        private set
    /** Wrong keys for the current chord. */
    var wrongHere by mutableIntStateOf(0)
        private set
    var lastWrong by mutableStateOf<Int?>(null)
        private set
    var wrongCount by mutableIntStateOf(0)
        private set
    var lastRecord by mutableStateOf<ChordRecord?>(null)
        private set
    val held = mutableStateListOf<Int>()
    /** Per-question outcome: true = first try, false = with mistakes or skipped. */
    val results = mutableStateListOf<Boolean>()
    private var startedAt = 0L
    private var random: Random = Random.Default
    private var job: Job? = null

    val level: ChordLevel get() = ChordLevels.of(settings.level)

    fun start(newSettings: ChordSettings, seed: Long? = null) {
        job?.cancel()
        settings = newSettings.sanitized()
        random = seed?.let { Random(it) } ?: Random.Default
        index = 0; firstTry = 0; wrongKeys = 0; skipped = 0; lastRecord = null
        results.clear(); held.clear()
        startedAt = now()
        next()
    }

    private fun next() {
        if (index >= settings.questions) { finish(true); return }
        index++
        val previous = chord
        var c: Chord
        do {
            c = Chord(level.roots.random(random), level.qualities.random(random))
        } while (c == previous && level.roots.size * level.qualities.size > 1)
        chord = c
        held.clear() // keys still down from the last chord don't count for this one
        wrongHere = 0; lastWrong = null
        phase = Phase.ASK
    }

    fun onKeyDown(midi: Int) {
        if (midi !in held) held += midi
        val c = chord ?: return
        if (phase != Phase.ASK) return
        if (midi.mod(12) !in c.pitchClasses) {
            wrongHere++; wrongKeys++; lastWrong = midi; wrongCount++
            return
        }
        check()
    }

    fun onKeyUp(midi: Int) {
        held.remove(midi)
        if (phase == Phase.ASK) check()
    }

    private fun check() {
        val c = chord ?: return
        if (!playsChord(c, held.toSet(), settings)) return
        phase = Phase.CORRECT
        if (wrongHere == 0) firstTry++
        results += wrongHere == 0
        advanceSoon(900)
    }

    /** Gives up on this chord: shows it and moves on. */
    fun reveal() {
        if (phase != Phase.ASK) return
        skipped++
        results += false
        phase = Phase.REVEALED
        advanceSoon(2200)
    }

    private fun advanceSoon(ms: Long) {
        job?.cancel()
        job = scope.launch { delay(ms); next() }
    }

    private fun finish(completed: Boolean) {
        job?.cancel()
        phase = Phase.DONE
        val rec = ChordRecord(now(), settings.level, if (completed) settings.questions else results.size, firstTry, wrongKeys, skipped, now() - startedAt, completed)
        lastRecord = rec
        if (rec.chords > 0) onFinished(rec)
    }

    fun abandon() {
        job?.cancel()
        if (phase != Phase.DONE && results.isNotEmpty()) finish(false)
    }
}
