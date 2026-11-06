package com.musictrainer.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.model.Accidental
import com.musictrainer.model.Clef
import com.musictrainer.model.SessionRecord
import com.musictrainer.model.Settings
import com.musictrainer.model.SpelledNote
import com.musictrainer.model.notePool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Phase { WAITING, CORRECT, WRONG, FINISHED }

/** A note that has already been answered, kept so the staff can show it scrolling away. */
data class AnsweredNote(val note: SpelledNote, val clef: Clef, val correct: Boolean)

/**
 * One practice session: shows a note, waits for a key, evaluates it,
 * gives feedback, then moves on. All state is Compose state.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onSessionEnded: (SessionRecord) -> Unit,
) {
    var settings by mutableStateOf(Settings())
        private set
    var clef by mutableStateOf(Clef.TREBLE)
        private set
    var note by mutableStateOf<SpelledNote?>(null)
        private set
    /** The wrong key the user pressed, while [phase] is WRONG. */
    var playedNote by mutableStateOf<SpelledNote?>(null)
        private set
    var phase by mutableStateOf(Phase.WAITING)
        private set
    /** 0-based number of the current note. */
    var index by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var wrong by mutableIntStateOf(0)
        private set
    /** Increments with every answer so animations can restart. */
    var feedbackId by mutableIntStateOf(0)
        private set
    var lastRecord by mutableStateOf<SessionRecord?>(null)
        private set
    /** Previously answered notes, oldest first (bounded). */
    val trail = mutableStateListOf<AnsweredNote>()

    private var job: Job? = null
    private var noteShownAt = 0L
    private var totalResponseMs = 0L
    private val random = Random.Default

    /** Notes left (including the current one) before the clef changes. */
    val notesUntilClefChange: Int get() = settings.notesPerClef - index % settings.notesPerClef

    /** Increments with every new session (seeds "mix it up" notation styles). */
    var sessionNumber by mutableIntStateOf(0)
        private set

    fun start(newSettings: Settings) {
        sessionNumber++
        job?.cancel()
        settings = newSettings
        index = 0
        correct = 0
        wrong = 0
        totalResponseMs = 0
        lastRecord = null
        note = null
        trail.clear()
        showNote()
    }

    /** Applies settings that don't need a new session (e.g. the mistake pause). */
    fun applyLive(newSettings: Settings) {
        settings = newSettings
    }

    /** Starts over with a fresh score; an unfinished session with answers is still logged. */
    fun restart(newSettings: Settings = settings) {
        if (phase != Phase.FINISHED && correct + wrong > 0) record(completed = false)
        start(newSettings)
    }

    fun onNotePlayed(midi: Int) {
        if (phase != Phase.WAITING) return
        val target = note ?: return
        totalResponseMs += now() - noteShownAt
        feedbackId++
        if (midi == target.midi) {
            correct++
            phase = Phase.CORRECT
            advanceAfter(CORRECT_PAUSE_MS)
        } else {
            wrong++
            playedNote = SpelledNote.spell(midi, preferFlat = target.accidental == Accidental.FLAT)
            phase = Phase.WRONG
            advanceAfter(settings.mistakePauseMs.toLong())
        }
    }

    private fun advanceAfter(ms: Long) {
        job = scope.launch {
            delay(ms)
            note?.let { trail += AnsweredNote(it, clef, correct = phase == Phase.CORRECT) }
            if (trail.size > MAX_TRAIL) trail.removeAt(0)
            index++
            if (index >= settings.sessionLength) {
                phase = Phase.FINISHED
                record(completed = true)
            } else {
                showNote()
            }
        }
    }

    private fun showNote() {
        val wanted = if ((index / settings.notesPerClef) % 2 == 0) Clef.TREBLE else Clef.BASS
        var chosenClef = wanted
        var pool = notePool(wanted, settings)
        if (pool.isEmpty()) { // range lies entirely in the other clef
            chosenClef = wanted.other
            pool = notePool(chosenClef, settings)
        }
        if (pool.isEmpty()) pool = listOf(60)
        val previous = note?.midi
        val choices = if (pool.size > 1) pool.filter { it != previous } else pool
        val midi = choices.random(random)
        clef = chosenClef
        note = SpelledNote.spell(midi, preferFlat = random.nextBoolean())
        playedNote = null
        phase = Phase.WAITING
        noteShownAt = now()
    }

    private fun record(completed: Boolean) {
        val answered = correct + wrong
        val rec = SessionRecord(
            timestamp = now(),
            correct = correct,
            wrong = wrong,
            plannedNotes = settings.sessionLength,
            completed = completed,
            lowestMidi = settings.lowestMidi,
            highestMidi = settings.highestMidi,
            notesPerClef = settings.notesPerClef,
            includeAccidentals = settings.includeAccidentals,
            avgResponseMs = if (answered == 0) 0 else totalResponseMs / answered,
        )
        lastRecord = rec
        onSessionEnded(rec)
    }

    private companion object {
        const val CORRECT_PAUSE_MS = 700L
        const val MAX_TRAIL = 64
    }
}
