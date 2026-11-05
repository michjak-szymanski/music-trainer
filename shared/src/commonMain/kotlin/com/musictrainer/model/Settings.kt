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
    /**
     * How long the correct answer is shown after a mistake. (Stored under a new name so that the
     * old 2 s default saved by earlier versions doesn't carry over.)
     */
    val mistakePauseMs: Int = 1000,
    /** Play a piano sound when an on-screen key is tapped. */
    val virtualKeySound: Boolean = true,
    /** Height of the on-screen keyboard in dp, set by dragging its handle; 0 = automatic. */
    val pianoHeightDp: Int = 0,
    /** Show the on-screen keyboard at all. */
    val showVirtualKeyboard: Boolean = true,
    /** On-screen keyboard size (a [KeyboardSize] key count); 0 = follow the practice range. */
    val virtualKeys: Int = 0,
    /** Show note names on the on-screen keyboard keys. */
    val showKeyLabels: Boolean = false,
    /** Show octave numbers on the on-screen keys (on C keys only when note names are off). */
    val showOctaveNumbers: Boolean = false,
    /** Range of the user's physical MIDI keyboard (set by size preset or detection). */
    val keyboardLowest: Int = 36,
    val keyboardHighest: Int = 96,
) {
    val rangeKeys: Int get() = highestMidi - lowestMidi + 1
    val keyboardKeys: Int get() = keyboardHighest - keyboardLowest + 1

    /** True when some notes of the practice range are not on the user's keyboard. */
    val rangeExceedsKeyboard: Boolean
        get() = lowestMidi < keyboardLowest || highestMidi > keyboardHighest

    /** Only the fields whose change should start a new practice session. */
    fun practiceKey(): Settings = Settings(
        lowestMidi = lowestMidi,
        highestMidi = highestMidi,
        notesPerClef = notesPerClef,
        sessionLength = sessionLength,
        includeAccidentals = includeAccidentals,
    )

    /** Range of the on-screen keyboard. */
    val virtualRange: IntRange
        get() = KeyboardSize.entries.firstOrNull { it.keys == virtualKeys }?.let { it.lowest..it.highest }
            ?: lowestMidi..highestMidi

    fun sanitized(): Settings {
        val lo = lowestMidi.coerceIn(MIDI_MIN, MIDI_MAX - MIN_RANGE)
        val hi = highestMidi.coerceIn(lo + MIN_RANGE, MIDI_MAX)
        return copy(
            lowestMidi = lo,
            highestMidi = hi,
            notesPerClef = notesPerClef.coerceIn(1, 500),
            sessionLength = sessionLength.coerceIn(5, 1000),
            mistakePauseMs = mistakePauseMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS),
            virtualKeys = if (KeyboardSize.entries.any { it.keys == virtualKeys }) virtualKeys else 0,
            keyboardLowest = min(keyboardLowest, keyboardHighest).coerceIn(0, 127),
            keyboardHighest = max(keyboardLowest, keyboardHighest).coerceIn(0, 127),
        )
    }

    companion object {
        /** Minimum practice range: one octave. */
        const val MIN_RANGE = 12
        const val MIN_PAUSE_MS = 300
        const val MAX_PAUSE_MS = 5000
    }
}

/** Notes that can be asked in [clef] with the given settings. */
fun notePool(clef: Clef, settings: Settings): List<Int> =
    (max(settings.lowestMidi, clef.minMidi)..min(settings.highestMidi, clef.maxMidi))
        .filter { settings.includeAccidentals || !isBlackKey(it) }

fun rangeLabel(lowest: Int, highest: Int): String = "${noteName(lowest)}–${noteName(highest)}"
