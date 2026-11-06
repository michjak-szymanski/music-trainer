package com.musictrainer.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** A key going down or up, [t] ms after the recording started. */
@Serializable
data class RecordedEvent(val t: Long, val midi: Int, val on: Boolean)

/** Index entry of a recording; its events are stored in [fileName]. */
@Serializable
data class RecordingInfo(
    val id: Long,
    val pieceId: String,
    val title: String,
    val durationMs: Long,
    val notes: Int,
    val hands: HandsMode,
    val bpm: Int,
    /** Results of the session this recording was made in (null for older recordings). */
    val session: PlayerRecord? = null,
) {
    val fileName: String get() = "recording-$id.json"
}

/** Collects what was heard during a session: the user's keys and the notes the app played. */
class Recorder(private val now: () -> Long) {
    private var startedAt = 0L
    private val events = ArrayList<RecordedEvent>()
    var active = false
        private set

    fun begin() {
        events.clear()
        startedAt = now()
        active = true
    }

    fun note(midi: Int, on: Boolean) {
        if (active) events += RecordedEvent(now() - startedAt, midi, on)
    }

    /** Stops recording; returns the events (with any still-held keys released at the end). */
    fun end(): List<RecordedEvent> {
        if (!active) return emptyList()
        active = false
        val end = now() - startedAt
        val down = HashSet<Int>()
        for (e in events) if (e.on) down += e.midi else down -= e.midi
        return events.toList() + down.map { RecordedEvent(end, it, on = false) }
    }
}

/** Plays a recording back through a [NoteSink], with progress. */
class RecordingPlayer(private val scope: CoroutineScope, private val now: () -> Long) {
    var playingId by mutableStateOf<Long?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    private var job: Job? = null
    private var sink: NoteSink? = null

    fun play(id: Long, events: List<RecordedEvent>, out: NoteSink) {
        stop()
        if (events.isEmpty()) return
        sink = out
        playingId = id
        val total = events.maxOf { it.t }.coerceAtLeast(1)
        job = scope.launch {
            val start = now()
            for (e in events.sortedBy { it.t }) {
                val wait = e.t - (now() - start)
                if (wait > 0) delay(wait)
                if (e.on) out.noteOn(e.midi, 80) else out.noteOff(e.midi)
                progress = (e.t.toFloat() / total).coerceIn(0f, 1f)
            }
            delay(300)
            out.allOff()
            playingId = null
            progress = 0f
        }
    }

    fun stop() {
        job?.cancel()
        sink?.allOff()
        playingId = null
        progress = 0f
    }
}
