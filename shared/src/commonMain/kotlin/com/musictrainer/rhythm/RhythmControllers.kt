package com.musictrainer.rhythm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.model.starRating
import com.musictrainer.player.NoteSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/** The three rhythm modes. */
@Serializable
enum class RhythmMode(val label: String) { DICTATION("Rhythm dictation"), IMITATION("Rhythm imitation"), PLAY("Rhythm play") }

/** One finished rhythm session. */
@Serializable
data class RhythmRecord(
    val timestamp: Long,
    val mode: RhythmMode,
    val level: Int,
    val signatures: List<String>,
    val bpm: Int,
    /** Questions (dictation, imitation) or bars (play). */
    val questions: Int,
    /** Questions answered / bars played without a mistake. */
    val perfect: Int,
    val notesRight: Int,
    val notesTotal: Int,
    /** Taps that didn't belong to a note (imitation, play). */
    val extraTaps: Int = 0,
    /** Mean timing error of hit notes, ms (imitation, play). */
    val precisionMs: Long = 0,
    val durationMs: Long,
    val completed: Boolean = true,
) {
    val accuracyPercent: Int get() = if (notesTotal + extraTaps == 0) 0 else notesRight * 100 / (notesTotal + extraTaps)
    val stars: Int get() = starRating(notesRight, notesTotal - notesRight + extraTaps)
    val score: Int get() = maxOf(0, notesRight * 10 - (notesTotal - notesRight) * 5 - extraTaps * 5)
}

private const val RHYTHM_NOTE = 72
private const val TAP_NOTE = 84

/**
 * Plays drills: an optional count-in bar of clicks, then the rhythm (and metronome). The playhead is
 * published as [playTick] (−1 when idle) and the count-in as [countInBeat].
 */
class RhythmPlayback(private val scope: CoroutineScope, private val now: () -> Long, private val sink: () -> NoteSink) {
    /** Click loudness 0–100, tone and accent (from the settings). */
    var clickVolume = 35
    var clickSound = MetronomeSound.SOFT
    var accentDownbeat = true
    var playing by mutableStateOf(false)
        private set
    var playTick by mutableIntStateOf(-1)
        private set
    var countInBeat by mutableStateOf<Int?>(null)
        private set
    private var job: Job? = null

    fun msPerTick(drill: RhythmDrill, bpm: Int): Double = 60_000.0 / bpm / drill.meter.beat

    /**
     * Plays [drill]; [onStart] is called with the time (from [now]) at which tick 0 sounds, after the
     * count-in. [rhythm]: play the notes (off for sight-reading); [clicks]: metronome during the drill.
     */
    fun play(
        drill: RhythmDrill, bpm: Int, rhythm: Boolean, clicks: Boolean, countIn: Boolean = true,
        tail: Int = 0, onStart: (Long) -> Unit = {}, onTick: (Int) -> Unit = {}, onDone: () -> Unit = {},
    ) {
        stop()
        val mpt = msPerTick(drill, bpm)
        val beat = drill.meter.beat
        val beatsPerBar = drill.meter.measureLength / beat
        playing = true
        job = scope.launch {
            val out = sink()
            val start = now() + 60 + if (countIn) (beatsPerBar * beat * mpt).toLong() else 0
            // Count-in.
            if (countIn) for (b in 0 until beatsPerBar) {
                waitUntil(start - ((beatsPerBar - b) * beat * mpt).toLong())
                countInBeat = beatsPerBar - b
                click(out, b == 0)
            }
            onStart(start)
            // Actions in time order: clicks, notes on/off.
            class Action(val tick: Int, val order: Int, val run: () -> Unit)
            val actions = ArrayList<Action>()
            if (clicks) for (t in 0 until drill.length step beat) actions += Action(t, 0) { click(out, t % drill.meter.measureLength == 0) }
            if (rhythm) for (e in drill.notes) {
                actions += Action(e.start, 1) { out.noteOn(RHYTHM_NOTE, 92) }
                val off = e.start + maxOf((e.value.ticks * 0.92).toInt(), 2)
                actions += Action(off, -1) { out.noteOff(RHYTHM_NOTE) }
            }
            actions.sortWith(compareBy({ it.tick }, { it.order }))
            var i = 0
            val endTick = drill.length + tail
            countInBeat = null
            while (isActive) {
                val tick = ((now() - start) / mpt).toInt()
                while (i < actions.size && actions[i].tick <= tick) { actions[i].run(); i++ }
                if (tick >= 0 && tick != playTick) { playTick = tick.coerceAtMost(drill.length); onTick(tick) }
                if (tick >= endTick && i >= actions.size) break
                delay(4)
            }
            out.noteOff(RHYTHM_NOTE)
            playing = false
            playTick = -1
            onDone()
        }
    }

    private suspend fun waitUntil(t: Long) { val d = t - now(); if (d > 0) delay(d) }

    private fun click(out: NoteSink, downbeat: Boolean) {
        if (clickVolume <= 0) return
        val accent = downbeat && accentDownbeat
        // A real metronome click (synth or MIDI drum), never a piano note; accents a little louder.
        out.click(clickSound.tone, accent, (clickVolume / 100f * if (accent) 1f else 0.7f).coerceIn(0f, 1f))
    }

    /** One bar of clicks to hear the current metronome settings. */
    fun preview(beats: Int = 4, bpm: Int = 100) {
        stop()
        job = scope.launch {
            val out = sink()
            for (b in 0 until beats) { click(out, b == 0); delay(60_000L / bpm) }
        }
    }

    fun tapSound() {
        val out = sink()
        out.noteOff(TAP_NOTE); out.noteOn(TAP_NOTE, 80)
        scope.launch { delay(120); out.noteOff(TAP_NOTE) }
    }

    fun stop() {
        job?.cancel(); job = null
        if (playing) sink().noteOff(RHYTHM_NOTE)
        playing = false; playTick = -1; countInBeat = null
    }
}

/** Matches taps to notes while they happen: each note's window is a share of the gap to its neighbours. */
class TapMatcher(private val drill: RhythmDrill, private val msPerTick: Double) {
    private val notes = drill.notes
    private val times = notes.map { (it.start * msPerTick).toLong() }
    private val windows = notes.indices.map { i ->
        val before = if (i > 0) times[i] - times[i - 1] else Long.MAX_VALUE
        val after = if (i < times.lastIndex) times[i + 1] - times[i] else Long.MAX_VALUE
        minOf(before, after).let { g -> if (g == Long.MAX_VALUE) 150L else minOf(150L, g * 2 / 5) }
    }
    /** Offset (ms, + = late) per note index; absent = not hit yet. */
    val offsets = mutableStateMapOf<Int, Long>()
    /** Notes whose window has passed without a tap. */
    val missed = mutableStateListOf<Int>()
    var extra by mutableIntStateOf(0)
        private set
    private var checkedUpTo = 0

    fun tap(ms: Long) {
        val best = notes.indices.filter { it !in offsets && it !in missed && abs(ms - times[it]) <= windows[it] }
            .minByOrNull { abs(ms - times[it]) }
        if (best == null) extra++ else offsets[best] = ms - times[best]
    }

    /** Marks notes whose window ended before [ms] as missed. */
    fun advance(ms: Long) {
        while (checkedUpTo < notes.size && times[checkedUpTo] + windows[checkedUpTo] < ms) {
            if (checkedUpTo !in offsets) missed += checkedUpTo
            checkedUpTo++
        }
    }

    fun finish() = advance(Long.MAX_VALUE)

    fun result() = ImitationResult(notes.mapIndexed { i, n -> TapMark(n, offsets[i]) }, extra)
    fun noteIndexOf(e: RhythmEvent) = notes.indexOf(e)
}

/** Shared session bookkeeping for the rhythm modes. */
abstract class RhythmSession(
    protected val scope: CoroutineScope,
    protected val now: () -> Long,
    sink: () -> NoteSink,
    private val onFinished: (RhythmRecord) -> Unit,
) {
    abstract val mode: RhythmMode
    val playback = RhythmPlayback(scope, now, sink)
    var settings by mutableStateOf(RhythmSettings())
        protected set
    var question by mutableIntStateOf(0)
        protected set
    var drill by mutableStateOf<RhythmDrill?>(null)
        protected set
    var perfect by mutableIntStateOf(0)
        protected set
    var notesRight by mutableIntStateOf(0)
        protected set
    var notesTotal by mutableIntStateOf(0)
        protected set
    var extraTaps by mutableIntStateOf(0)
        protected set
    protected var offsetSum = 0L
    protected var offsetCount = 0
    var sessionDone by mutableStateOf(false)
        protected set
    var lastRecord by mutableStateOf<RhythmRecord?>(null)
        protected set
    var startedAt by mutableStateOf(0L)
        protected set
    protected var random: Random = Random.Default
    /** Automatic replay / next question waiting to happen (cancelled by any other move). */
    protected var autoNext: Job? = null

    /**
     * What is about to happen by itself, from [startedAt] until [at] (ms), for the circle's progress ring.
     * [pausedLeft]: paused with that many ms to go.
     */
    class Pending(val label: String, val startedAt: Long, val at: Long, val pausedLeft: Long? = null)
    var pending by mutableStateOf<Pending?>(null)
        private set
    private var pendingAction: (() -> Unit)? = null
    /** Playback stopped with ⏸ (▶ plays again). */
    var pausedPlayback by mutableStateOf(false)
        protected set

    /** Runs [action] after [ms] (shown as "[label] in …"), replacing anything pending. */
    protected fun schedule(label: String, ms: Long, action: () -> Unit) {
        cancelAuto()
        val t = now()
        pending = Pending(label, t, t + ms)
        pendingAction = action
        autoNext = scope.launch { delay(ms); autoNext = null; pending = null; pendingAction = null; action() }
    }

    protected fun cancelAuto() {
        autoNext?.cancel(); autoNext = null; pending = null; pendingAction = null; pausedPlayback = false
    }

    /** ⏸ in the middle of the circle: freezes the countdown, or stops the rhythm playing. */
    fun pause() {
        val p = pending
        if (p != null && p.pausedLeft == null) {
            autoNext?.cancel(); autoNext = null
            pending = Pending(p.label, p.startedAt, p.at, pausedLeft = (p.at - now()).coerceAtLeast(0))
        } else if (playback.playing) {
            playback.stop()
            pausedPlayback = true
        }
    }

    /** ▶: continues the countdown where it stopped, or plays the rhythm again. */
    open fun resume() {
        val p = pending
        val action = pendingAction
        if (p?.pausedLeft != null && action != null) {
            val t = now()
            // Keep the ring's progress: shift its start so the remaining time is what was left.
            pending = Pending(p.label, t - (p.at - p.startedAt - p.pausedLeft), t + p.pausedLeft)
            autoNext = scope.launch { delay(p.pausedLeft); autoNext = null; pending = null; pendingAction = null; action() }
        }
    }

    /** After a success: wait a moment, then the next question (which starts with its count-in). */
    protected fun advanceSoon(next: () -> Unit) {
        cancelAuto()
        if (!settings.nextAfterSuccess) return
        schedule("Next question", settings.nextDelayTenths * 100L, next)
    }

    val level: RhythmLevel get() = levelFor(settings)
    val precisionMs: Long get() = if (offsetCount == 0) 0 else offsetSum / offsetCount

    open fun start(newSettings: RhythmSettings, seed: Long? = null) {
        cancelAuto()
        playback.stop()
        settings = newSettings.sanitized()
        playback.clickVolume = settings.metronomeVolume
        playback.clickSound = settings.metronomeSound
        playback.accentDownbeat = settings.accentDownbeat
        random = seed?.let { Random(it) } ?: Random.Default
        question = 0; perfect = 0; notesRight = 0; notesTotal = 0; extraTaps = 0; offsetSum = 0; offsetCount = 0
        sessionDone = false; lastRecord = null
        startedAt = now()
    }

    protected fun newDrill(bars: Int = settings.bars): RhythmDrill =
        RhythmGenerator.generate(level, settings.signatureList.random(random), bars, random)

    protected fun addTiming(result: ImitationResult) {
        notesRight += result.hits; notesTotal += result.marks.size; extraTaps += result.extraTaps
        result.marks.forEach { m -> m.offsetMs?.let { offsetSum += abs(it); offsetCount++ } }
        if (result.passed) perfect++
    }

    protected fun finishSession(completed: Boolean = true) {
        playback.stop()
        sessionDone = true
        val rec = RhythmRecord(
            timestamp = now(), mode = mode, level = settings.level, signatures = settings.signatures, bpm = settings.bpm,
            questions = if (completed) settings.questions else question, perfect = perfect, notesRight = notesRight, notesTotal = notesTotal,
            extraTaps = extraTaps, precisionMs = precisionMs, durationMs = now() - startedAt, completed = completed,
        )
        lastRecord = rec
        if (notesTotal > 0) onFinished(rec)
    }

    /** Stops a running session, keeping its results if anything was answered. */
    fun abandon() {
        cancelAuto()
        playback.stop()
        if (!sessionDone && notesTotal > 0) finishSession(completed = false)
    }

    abstract fun onTap()
}

/** Rhythm dictation: listen as often as you like, then write the values one by one (no undo). */
class DictationSession(scope: CoroutineScope, now: () -> Long, sink: () -> NoteSink, onFinished: (RhythmRecord) -> Unit) :
    RhythmSession(scope, now, sink, onFinished) {
    override val mode = RhythmMode.DICTATION
    val written = mutableStateListOf<RhythmEvent>()
    var result by mutableStateOf<DictationResult?>(null)
        private set
    val position: Int get() = written.lastOrNull()?.end ?: 0

    override fun start(newSettings: RhythmSettings, seed: Long?) {
        super.start(newSettings, seed)
        nextQuestion()
    }

    fun nextQuestion() {
        cancelAuto()
        if (result != null || question == 0) {
            if (question >= settings.questions) { finishSession(); return }
            question++
            drill = newDrill(); written.clear(); result = null; autoReplays = 0; retrying = false
            listen()
        }
    }

    fun listen() {
        val d = drill ?: return
        cancelAuto()
        // Replay / Restart starts the question afresh: notes already entered are cleared. After a finished wrong
        // answer this is another try (the score keeps the first one).
        if (result?.allCorrect == false) retrying = true
        if (result?.allCorrect != true) { written.clear(); result = null }
        playback.play(d, settings.bpm, rhythm = true, clicks = settings.metronome, onDone = {
            // Nothing written yet: play it again (with its count-in) after a pause, up to the set number of times.
            val more = settings.maxReplays == 0 || autoReplays < settings.maxReplays
            if (settings.autoReplay && more && written.isEmpty() && result == null && drill === d) {
                schedule("Replay", settings.replayWaitTenths * 100L) {
                    if (written.isEmpty() && result == null && drill === d) { autoReplays++; listen() }
                }
            }
        })
    }

    /** Automatic replays of the current question so far. */
    private var autoReplays = 0

    /** Whether [v] fits at the cursor without crossing a bar line. */
    fun canWrite(v: RhythmValue): Boolean {
        val d = drill ?: return false
        if (result != null) return false
        if (playback.countInBeat != null) return false // counting in: nothing can be written yet
        val p = position
        val barEnd = (p / d.meter.measureLength + 1) * d.meter.measureLength
        if (p + v.ticks > barEnd) return false
        // Triplets come in threes filling a beat.
        val inTriplet = written.lastOrNull()?.value?.triplet == true && p % d.meter.beat != 0
        if (inTriplet) return v.triplet
        if (v.triplet) return p % d.meter.beat == 0
        return true
    }

    /** Another try at the same rhythm after a mistake (doesn't change the score). */
    var retrying by mutableStateOf(false)
        private set

    fun write(v: RhythmValue) {
        val d = drill ?: return
        if (!canWrite(v)) return
        if (written.isEmpty()) cancelAuto() // writing started: no more automatic replays
        written += RhythmEvent(position, v)
        if (position >= d.length) {
            val r = checkDictation(d, written)
            result = r
            playback.stop()
            if (!retrying) { notesRight += r.matched; notesTotal += r.total; if (r.allCorrect) perfect++ }
            if (r.allCorrect) advanceSoon(::nextQuestion)
        }
    }

    override fun onTap() = Unit

    override fun resume() {
        if (pausedPlayback) { pausedPlayback = false; listen() } else super.resume()
    }

}

/** Rhythm imitation: hear the rhythm, then tap it back after a count-in. */
class ImitationSession(scope: CoroutineScope, now: () -> Long, sink: () -> NoteSink, onFinished: (RhythmRecord) -> Unit) :
    RhythmSession(scope, now, sink, onFinished) {
    override val mode = RhythmMode.IMITATION
    enum class Phase { LISTEN, YOUR_TURN, RESULT }
    var phase by mutableStateOf(Phase.LISTEN)
        private set
    var matcher by mutableStateOf<TapMatcher?>(null)
        private set
    var result by mutableStateOf<ImitationResult?>(null)
        private set
    private var t0 = 0L
    /** Increments on every tap (drives the pad animation). */
    var tapCount by mutableIntStateOf(0)
        private set

    override fun start(newSettings: RhythmSettings, seed: Long?) {
        super.start(newSettings, seed)
        nextQuestion()
    }

    fun nextQuestion() {
        cancelAuto()
        if (question >= settings.questions) { finishSession(); return }
        question++
        drill = newDrill()
        listenThenTap()
    }

    /** Plays the rhythm, then counts in for the user's turn. */
    fun listenThenTap() {
        cancelAuto()
        val d = drill ?: return
        result = null; matcher = null
        phase = Phase.LISTEN
        playback.play(d, settings.bpm, rhythm = true, clicks = settings.metronome, onDone = { yourTurn() })
    }

    private fun yourTurn() {
        val d = drill ?: return
        val mpt = playback.msPerTick(d, settings.bpm)
        val m = TapMatcher(d, mpt)
        phase = Phase.YOUR_TURN
        playback.play(
            d, settings.bpm, rhythm = false, clicks = true, tail = d.meter.beat / 2,
            onStart = { t0 = it; matcher = m },
            onTick = { m.advance(now() - t0) },
            onDone = {
                m.finish()
                val r = m.result()
                result = r
                addTiming(r)
                phase = Phase.RESULT
                if (r.passed) advanceSoon(::nextQuestion)
            },
        )
    }

    override fun resume() {
        if (pausedPlayback) { pausedPlayback = false; listenThenTap() } else super.resume()
    }

    override fun onTap() {
        if (phase != Phase.YOUR_TURN) return
        playback.tapSound()
        tapCount++
        matcher?.tap(now() - t0)
    }
}

/** Rhythm play: sight-read a run of bars with the metronome; no repeats, the staff moves on bar by bar. */
class PlaySession(scope: CoroutineScope, now: () -> Long, sink: () -> NoteSink, onFinished: (RhythmRecord) -> Unit) :
    RhythmSession(scope, now, sink, onFinished) {
    override val mode = RhythmMode.PLAY
    var matcher by mutableStateOf<TapMatcher?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    /** Bar the playhead is in (0-based). */
    var bar by mutableIntStateOf(0)
        private set
    private var t0 = 0L
    var tapCount by mutableIntStateOf(0)
        private set

    /** Bars in the run = [RhythmSettings.questions]. */
    override fun start(newSettings: RhythmSettings, seed: Long?) {
        super.start(newSettings, seed)
        val d = newDrill(bars = settings.questions)
        drill = d
        bar = 0
        val m = TapMatcher(d, playback.msPerTick(d, settings.bpm))
        running = true
        playback.play(
            d, settings.bpm, rhythm = false, clicks = true, tail = d.meter.beat / 2,
            onStart = { t0 = it; matcher = m },
            onTick = { t ->
                m.advance(now() - t0)
                bar = (t / d.meter.measureLength).coerceIn(0, d.bars - 1)
                question = bar + 1
            },
            onDone = {
                m.finish()
                running = false
                val r = m.result()
                addTiming(r)
                // A bar counts as perfect when all its notes were hit and no stray taps happened in it.
                perfect = (0 until d.bars).count { b ->
                    r.marks.filter { it.event.start / d.meter.measureLength == b }.all { it.offsetMs != null }
                } - if (r.extraTaps > 0) minOf(r.extraTaps, d.bars) else 0
                perfect = perfect.coerceAtLeast(0)
                finishSession()
            },
        )
    }

    override fun onTap() {
        if (!running || matcher == null) return
        playback.tapSound()
        tapCount++
        matcher?.tap(now() - t0)
    }

    fun stop() {
        running = false
        abandon()
    }
}
