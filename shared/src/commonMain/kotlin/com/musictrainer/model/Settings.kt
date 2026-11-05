package com.musictrainer.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class Settings(
    /** Lowest note that can be asked (MIDI number). Default C2. */
    val lowestMidi: Int = 36,
    /** Highest note that can be asked (MIDI number). Default C7 -> 61 keys. */
    val highestMidi: Int = 96,
    /** The clef switches between treble and bass after this many notes. */
    val notesPerClef: Int = 10,
    /** Number of notes in one session. */
    val sessionLength: Int = 50,
    /** Whether black keys (sharps / flats) are asked. */
    val includeAccidentals: Boolean = false,
    /** How long the correct answer is shown after a mistake. */
    val wrongPauseMs: Int = 2000,
    /** Play a piano sound when an on-screen key is tapped. */
    val virtualKeySound: Boolean = true,
    /** Range of the user's physical MIDI keyboard (set by size preset or detection). */
    val keyboardLowest: Int = 36,
    val keyboardHighest: Int = 96,
) {
    val rangeKeys: Int get() = highestMidi - lowestMidi + 1
    val keyboardKeys: Int get() = keyboardHighest - keyboardLowest + 1

    /** True when some notes of the practice range are not on the user's keyboard. */
    val rangeExceedsKeyboard: Boolean
        get() = lowestMidi < keyboardLowest || highestMidi > keyboardHighest

    /** Only the fields that affect a running practice session. */
    fun practiceKey(): Settings = copy(keyboardLowest = 0, keyboardHighest = 0, virtualKeySound = true)

    fun sanitized(): Settings {
        val lo = lowestMidi.coerceIn(MIDI_MIN, MIDI_MAX - MIN_RANGE)
        val hi = highestMidi.coerceIn(lo + MIN_RANGE, MIDI_MAX)
        return copy(
            lowestMidi = lo,
            highestMidi = hi,
            notesPerClef = notesPerClef.coerceIn(1, 500),
            sessionLength = sessionLength.coerceIn(5, 1000),
            wrongPauseMs = wrongPauseMs.coerceIn(500, 10_000),
            keyboardLowest = min(keyboardLowest, keyboardHighest).coerceIn(0, 127),
            keyboardHighest = max(keyboardLowest, keyboardHighest).coerceIn(0, 127),
        )
    }

    companion object {
        /** Minimum practice range: one octave. */
        const val MIN_RANGE = 12
    }
}

/** Notes that can be asked in [clef] with the given settings. */
fun notePool(clef: Clef, settings: Settings): List<Int> =
    (max(settings.lowestMidi, clef.minMidi)..min(settings.highestMidi, clef.maxMidi))
        .filter { settings.includeAccidentals || !isBlackKey(it) }

fun rangeLabel(lowest: Int, highest: Int): String = "${noteName(lowest)}–${noteName(highest)}"
