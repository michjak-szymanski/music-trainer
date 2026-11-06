package com.musictrainer.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.notation.Score
import com.musictrainer.notation.TICKS_PER_QUARTER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.max

enum class HandsMode(val label: String, val treble: Boolean, val bass: Boolean) {
    BOTH("Both hands", true, true),
    RIGHT("Right hand", true, false),
    LEFT("Left hand", false, true),
    NONE("No hands (listen)", false, false),
}

enum class PlayStyle(val label: String) { WAIT("Wait for me"), FOLLOW_TEMPO("Follow tempo") }

/** HIT = right key; TIMING = right key but early/late or held too short/long; MISSED = not played in time. */
enum class NoteMark { HIT, TIMING, MISSED }

/** Where notes the app plays itself (other hand, or everything in "No hands") are sent. */
interface NoteSink {
    fun noteOn(midi: Int, velocity: Int)
    fun noteOff(midi: Int)
    fun allOff() = Unit
}

@Serializable
data class PlayerRecord(
    val timestamp: Long,
    val pieceId: String,
    val title: String,
    val hits: Int,
    val wrong: Int,
    val missed: Int,
    val totalNotes: Int,
    val completed: Boolean,
    val bpm: Int,
    val fileBpm: Int,
    val style: PlayStyle,
    val hands: HandsMode,
    val durationMs: Long,
    /** Chords played clearly early or late (Wait for me with rhythm checking). */
    val rhythmErrors: Int = 0,
    /** Notes released much too early or held much too long. */
    val lengthErrors: Int = 0,
) {
    val errors: Int get() = wrong + missed
    val accuracyPercent: Int get() = if (hits + errors == 0) 0 else hits * 100 / (hits + errors)
    val timingErrors: Int get() = rhythmErrors + lengthErrors
    val score: Int get() = max(0, hits * 10 - wrong * 5 - missed * 5 - timingErrors * 3)
    /** Timing mistakes count half as much as wrong notes. */
    val stars: Int get() = com.musictrainer.model.starRating(hits, errors + timingErrors / 2)
}

/**
 * Play-along for a [Score]. In [PlayStyle.FOLLOW_TEMPO] the cursor moves at [bpm] and each chord must be
 * played within a timing window around its time; in [PlayStyle.WAIT] the cursor waits for the chord.
 * Notes of hands not being practised are played by the app through [sink].
 */
class PlayerController(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val sink: () -> NoteSink,
    private val onFinished: (PlayerRecord) -> Unit,
) {
    var score by mutableStateOf<Score?>(null)
        private set
    var pieceId by mutableStateOf("")
        private set
    var hands by mutableStateOf(HandsMode.BOTH)
    var style by mutableStateOf(PlayStyle.WAIT)
    /** In Wait for me: also judge rhythm (time between chords at [bpm]) and how long notes are held. */
    var checkTiming = true
    var bpm by mutableIntStateOf(120)
    var countIn = true
    /** Seconds counted down before playing with Right, Left or Both hands (0 = off). */
    var countdownSeconds = 0

    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    /** Seconds left in the start countdown (shown down to 0), or null when not counting down. */
    var countdown by mutableStateOf<Int?>(null)
        private set
    /** Beats left in the count-in, or null when not counting in. */
    var countInBeat by mutableStateOf<Int?>(null)
        private set
    /** Step the cursor is on, and how far it has moved towards the next one. */
    var cursorStep by mutableIntStateOf(0)
        private set
    var cursorFraction by mutableFloatStateOf(0f)
        private set
    val marks = mutableStateMapOf<Int, NoteMark>() // step * 128 + midi
    var hits by mutableIntStateOf(0)
        private set
    var wrong by mutableIntStateOf(0)
        private set
    var missed by mutableIntStateOf(0)
        private set
    var rhythmErrors by mutableIntStateOf(0)
        private set
    var lengthErrors by mutableIntStateOf(0)
        private set
    /** Latest timing feedback, e.g. "Late by 0.3 s" (null when the last chord was in time). */
    var timingMessage by mutableStateOf<String?>(null)
        private set
    var timingCount by mutableIntStateOf(0)
        private set
    var lastWrong by mutableStateOf<Int?>(null)
        private set
    var wrongCount by mutableIntStateOf(0)
        private set
    var lastRecord by mutableStateOf<PlayerRecord?>(null)
        private set

    /** Where the next Start begins (moved by clicking the sheet). */
    var startStep by mutableIntStateOf(0)
        private set

    /** Step being judged (may lag behind the cursor in tempo mode while its window is open). */
    private var judge by mutableIntStateOf(0)
    /** The step whose notes the user has to play now. */
    val currentStep: Int get() = judge
    /** The notes that were expected when the last wrong key was pressed. */
    var lastWrongExpected by mutableStateOf<Set<Int>>(emptySet())
        private set
    private var autoIdx = 0
    private var startedAt = 0L
    private var t0 = 0L
    private var job: Job? = null

    private val steps get() = score?.steps.orEmpty()
    private val msPerUnit: Double get() = 60_000.0 / bpm / TICKS_PER_QUARTER
    private fun stepMs(i: Int) = steps[i].time * msPerUnit
    fun required(i: Int): Set<Int> = steps.getOrNull(i)?.required(hands.treble, hands.bass).orEmpty()
    private fun auto(i: Int): Set<Int> = steps.getOrNull(i)?.let { it.all - it.required(hands.treble, hands.bass) }.orEmpty()
    val totalRequired: Int get() = steps.indices.sumOf { required(it).size }
    /** Keys to press now (for hints on the keyboard). */
    val nextKeys: Set<Int> get() = required(judge).filter { marks[judge * 128 + it] != NoteMark.HIT }.toSet()

    fun load(newScore: Score, id: String) {
        stop(record = true)
        score = newScore
        pieceId = id
        bpm = newScore.bpm
        startStep = 0
        reset()
    }

    private fun reset() {
        job?.cancel()
        running = false; finished = false; countInBeat = null; countdown = null
        cursorStep = startStep; cursorFraction = 0f; judge = startStep; autoIdx = startStep
        marks.clear(); hits = 0; wrong = 0; missed = 0; lastWrong = null; lastRecord = null
        rhythmErrors = 0; lengthErrors = 0; timingMessage = null
        held.clear(); lastChordAt = null; chordStartedAt = null
    }

    /** Increments with every Start (seeds "mix it up" notation styles). */
    var runNumber by mutableIntStateOf(0)
        private set

    fun start() {
        runNumber++
        val s = score ?: return
        if (s.steps.isEmpty()) return
        stop(record = true)
        reset()
        running = true
        startedAt = now()
        job = scope.launch {
            if (countdownSeconds > 0 && hands != HandsMode.NONE) {
                for (n in countdownSeconds downTo 1) { countdown = n; delay(1000) }
                countdown = 0
                // "0" stays up briefly while playback begins.
                launch { delay(700); countdown = null }
                startedAt = now()
            }
            if (countIn && style == PlayStyle.FOLLOW_TEMPO && hands != HandsMode.NONE) {
                val meter = s.measures.first().meter
                val beatMs = (meter.beat * msPerUnit).toLong()
                for (b in meter.measureLength / meter.beat downTo 1) { countInBeat = b; delay(beatMs) }
                countInBeat = null
            }
            t0 = now() - stepMs(startStep).toLong()
            if (style == PlayStyle.FOLLOW_TEMPO || hands == HandsMode.NONE) runTempo() else runWait()
        }
    }

    fun stop(record: Boolean) {
        val wasRunning = running
        job?.cancel()
        glideJob?.cancel(); glideJob = null; pendingAuto = null
        sink().allOff()
        running = false; countInBeat = null; countdown = null
        if (record && wasRunning && !finished && hits + wrong + missed > 0) finish(completed = false)
    }

    private suspend fun CoroutineScope.runTempo() {
        val n = steps.size
        while (isActive) {
            val t = now() - t0
            while (autoIdx < n && stepMs(autoIdx) <= t) { playAuto(autoIdx); autoIdx++ }
            // Cursor follows time.
            var c = cursorStep
            while (c + 1 < n && stepMs(c + 1) <= t) c++
            cursorStep = c
            cursorFraction = if (c + 1 < n) ((t - stepMs(c)) / (stepMs(c + 1) - stepMs(c))).toFloat().coerceIn(0f, 1f) else 0f
            // Judge: close windows that have passed.
            while (judge < n && (required(judge).isEmpty() && t >= stepMs(judge) || t > stepMs(judge) + late(judge))) closeStep(judge)
            if (judge >= n && autoIdx >= n) {
                delay(lastLengthMs())
                finish(completed = true)
                return
            }
            delay(8)
        }
    }

    private suspend fun runWait() {
        playAuto(judge)
        skipAutoSteps()
    }

    /** Back to the first bar: while playing, playback restarts there; when stopped, it only rewinds. */
    fun startFromBeginning() {
        if (running) {
            stop(record = true)
            startStep = 0
            start()
        } else {
            seek(0)
        }
    }

    /**
     * Moves the cursor to [step]. When stopped, the next Start begins there; while playing, playback
     * continues from there (later marks are cleared, earlier results are kept).
     */
    fun seek(step: Int) {
        val n = steps.size
        if (n == 0) return
        val s = step.coerceIn(0, n - 1)
        marks.keys.filter { it / 128 >= s }.forEach { marks.remove(it) }
        cursorStep = s; cursorFraction = 0f; judge = s; autoIdx = s
        lastChordAt = null; chordStartedAt = null; held.clear()
        glideJob?.cancel(); glideJob = null; pendingAuto = null
        if (!running) {
            startStep = s
            finished = false
            return
        }
        sink().allOff()
        if (countInBeat != null || (countdown ?: 0) > 0) { startStep = s; return } // still counting: start from here
        if (style == PlayStyle.FOLLOW_TEMPO || hands == HandsMode.NONE) {
            t0 = now() - stepMs(s).toLong()
        } else {
            job?.cancel()
            playAuto(s)
            job = scope.launch { skipAutoSteps() }
        }
    }

    /** In wait mode, steps with nothing to play for the user just pass at tempo. */
    private suspend fun skipAutoSteps() {
        while (judge < steps.size && required(judge).isEmpty()) {
            if (judge + 1 >= steps.size) delay(lastLengthMs()) // last chord is the app's: let it sound
            advanceWait()
            glideJob?.join() // the stripe glides over the chord's length before the next one
        }
        if (judge >= steps.size) finish(completed = true)
    }

    /** Stripe gliding from the chord just played to the next one, at tempo. */
    private var glideJob: Job? = null
    /** Step whose app-played notes sound when the glide arrives. */
    private var pendingAuto: Int? = null

    /**
     * Wait for me: the chord at the judged step is done. The judged step moves on at once (the next chord
     * may be played right away), while the stripe glides there over the finished chord's length and the
     * other hand's notes sound when it arrives.
     */
    private fun advanceWait() {
        settleGlide()
        val from = judge
        judge++
        if (judge >= steps.size) {
            cursorStep = steps.lastIndex; cursorFraction = 0f
            return
        }
        val to = judge
        val gapMs = (stepMs(to) - stepMs(from)).toLong().coerceAtLeast(1)
        pendingAuto = to
        glideJob = scope.launch {
            val startAt = now()
            while (true) {
                val f = (now() - startAt).toFloat() / gapMs
                if (f >= 1f) break
                cursorStep = from
                cursorFraction = f
                delay(16)
            }
            glideJob = null
            settleGlide()
        }
    }

    /** Finishes a running glide immediately (the user was quicker, or the glide ended). */
    private fun settleGlide() {
        glideJob?.let { if (it.isActive) it.cancel() }
        glideJob = null
        val target = pendingAuto ?: return
        pendingAuto = null
        cursorStep = target
        cursorFraction = 0f
        playAuto(target)
    }

    // ---- rhythm and note-length checking (Wait for me) ----
    /** Keys held down: midi -> (step, pressed at, written length in ms). */
    private val held = HashMap<Int, Triple<Int, Long, Double>>()
    /** The user's previous completed chord: (step, time of its first key). */
    private var lastChordAt: Pair<Int, Long>? = null
    private var chordStartedAt: Long? = null

    private fun timingTolerance(expectedMs: Double) = maxOf(150.0, expectedMs * 0.3)

    private fun flagTiming(step: Int, message: String) {
        for (p in required(step)) if (marks[step * 128 + p] == NoteMark.HIT) marks[step * 128 + p] = NoteMark.TIMING
        timingMessage = message
        timingCount++
    }

    private fun seconds(ms: Double): String {
        val tenths = (ms / 100).toLong()
        return "${tenths / 10}.${tenths % 10} s"
    }

    /** A key was released: in Wait for me, check how long it was held against the written length. */
    fun onNoteOff(midi: Int) {
        val (step, at, expected) = held.remove(midi) ?: return
        if (!running || !checkTiming || style != PlayStyle.WAIT) return
        val heldMs = (now() - at).toDouble()
        if (expected >= 250 && heldMs < expected * 0.5) {
            lengthErrors++
            flagTiming(step, "Released too early: held ${seconds(heldMs)} of ${seconds(expected)}")
        } else if (heldMs > expected * 1.6 + 300) {
            lengthErrors++
            flagTiming(step, "Held too long: ${seconds(heldMs)} instead of ${seconds(expected)}")
        }
    }

    fun onNote(midi: Int) {
        if (!running || countInBeat != null || (countdown ?: 0) > 0 || hands == HandsMode.NONE) return
        val n = steps.size
        if (style == PlayStyle.WAIT) {
            if (judge >= n) return
            val key = judge * 128 + midi
            if (midi in required(judge) && marks[key] != NoteMark.HIT) {
                marks[key] = NoteMark.HIT; hits++
                val t = now()
                if (chordStartedAt == null) chordStartedAt = t
                held[midi] = Triple(judge, t, (steps[judge].lengths[midi] ?: TICKS_PER_QUARTER) * msPerUnit)
                if (required(judge).all { marks[judge * 128 + it] == NoteMark.HIT }) {
                    val startedAt = chordStartedAt ?: t
                    val prev = lastChordAt
                    if (checkTiming && prev != null) {
                        val expected = (steps[judge].time - steps[prev.first].time) * msPerUnit
                        val diff = (startedAt - prev.second) - expected
                        if (kotlin.math.abs(diff) > timingTolerance(expected)) {
                            rhythmErrors++
                            flagTiming(judge, (if (diff > 0) "Late by " else "Early by ") + seconds(kotlin.math.abs(diff)))
                        } else {
                            timingMessage = null
                        }
                    }
                    lastChordAt = judge to startedAt
                    chordStartedAt = null
                    advanceWait()
                    job = scope.launch { skipAutoSteps() }
                    if (judge >= n) finish(completed = true)
                }
            } else registerWrong(midi)
            return
        }
        val t = now() - t0
        for (cand in judge..minOf(judge + 1, n - 1)) {
            val key = cand * 128 + midi
            if (midi in required(cand) && marks[key] != NoteMark.HIT && t >= stepMs(cand) - early(cand)) {
                if (cand > judge) closeStep(judge) // moved on: the previous chord is incomplete
                marks[key] = NoteMark.HIT; hits++
                if (required(cand).all { marks[cand * 128 + it] == NoteMark.HIT }) judge = cand + 1
                return
            }
        }
        registerWrong(midi)
    }

    private fun registerWrong(midi: Int) {
        lastWrongExpected = nextKeys
        wrong++; lastWrong = midi; wrongCount++
    }

    private fun closeStep(i: Int) {
        for (p in required(i)) if (marks[i * 128 + p] != NoteMark.HIT) { marks[i * 128 + p] = NoteMark.MISSED; missed++ }
        judge = i + 1
    }

    private fun early(i: Int): Double = (if (i > 0) (stepMs(i) - stepMs(i - 1)) * 0.5 else 220.0).coerceIn(60.0, 220.0)
    private fun late(i: Int): Double = (if (i + 1 < steps.size) (stepMs(i + 1) - stepMs(i)) * 0.5 else 280.0).coerceIn(80.0, 280.0)
    private fun lastLengthMs(): Long = ((steps.lastOrNull()?.lengths?.values?.maxOrNull() ?: TICKS_PER_QUARTER) * msPerUnit).toLong()

    /** Increments every time a pitch is struck, so a release scheduled for an older strike can be ignored. */
    private val strikes = HashMap<Int, Int>()

    private fun playAuto(i: Int) {
        val step = steps.getOrNull(i) ?: return
        val notes = auto(i)
        if (notes.isEmpty()) return
        val out = sink()
        val ids = notes.associateWith { p -> (strikes[p] ?: 0) + 1 }
        strikes.putAll(ids)
        for (p in notes) out.noteOn(p, 80)
        val lengths = notes.associateWith { ((step.lengths[it] ?: TICKS_PER_QUARTER) * msPerUnit).toLong().coerceAtLeast(60) }
        val playedAt = now()
        scope.launch {
            for ((p, ms) in lengths.entries.sortedBy { it.value }) {
                delay((ms - (now() - playedAt)).coerceAtLeast(0))
                // A repeated note may already have been struck again (its release and the next strike fall on
                // the same moment); releasing now would cut the new note off.
                if (strikes[p] == ids[p]) out.noteOff(p)
            }
        }
    }

    private fun finish(completed: Boolean) {
        if (finished) return
        running = false
        finished = completed
        sink().allOff()
        if (hands == HandsMode.NONE) return // listening only: nothing to score
        val s = score ?: return
        val rec = PlayerRecord(
            timestamp = now(), pieceId = pieceId, title = s.title, hits = hits, wrong = wrong, missed = missed,
            totalNotes = totalRequired, completed = completed, bpm = bpm, fileBpm = s.bpm, style = style, hands = hands,
            durationMs = now() - startedAt, rhythmErrors = rhythmErrors, lengthErrors = lengthErrors,
        )
        lastRecord = rec
        onFinished(rec)
    }
}
