package com.musictrainer.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** SMuFL music fonts bundled with the app. */
@Serializable
enum class NotationFont(val label: String) {
    BRAVURA("Bravura (engraved)"), LELAND("Leland (MuseScore)"), PETALUMA("Petaluma (handwritten)"),
}

/** Colours of the sheet. */
@Serializable
enum class SheetColors(val label: String) { THEME("Follow the app theme"), PAPER("Paper"), CONTRAST("High contrast") }

/** How the music is drawn; separate per mode. */
@Serializable
data class NotationStyle(
    val font: NotationFont = NotationFont.BRAVURA,
    val colors: SheetColors = SheetColors.THEME,
    /** A random font and colours for every session / run. */
    val mixItUp: Boolean = false,
)

/** App colour theme. */
@Serializable
enum class AppTheme(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The app's modes, picked in the top-left corner. */
@Serializable
enum class AppMode(val label: String, val description: String) {
    FEEDER("Note finder", "Random notes, one at a time"),
    PLAYER("Note player", "Play pieces from MIDI files"),
    RHYTHM_DICTATION("Rhythm dictation", "Hear a rhythm, write down the note values"),
    RHYTHM_IMITATION("Rhythm imitation", "Hear a rhythm, tap it back"),
    RHYTHM_PLAY("Rhythm play", "Sight-read rhythms bar after bar"),
    CHORDS("Chord finder", "Play the chord for a symbol"),
    CHORD_EAR("Chord identification", "Hear a chord, name its type"),
    PROGRESSION_EAR("Progression identification", "Hear a chord progression, name it"),
    PERFECT_PITCH("Perfect pitch", "Hear a note, find it on the staff"),
    RELATIVE_PITCH("Relative pitch", "Hear two notes, name the interval"),
    ;
    val rhythmMode: com.musictrainer.rhythm.RhythmMode? get() = when (this) {
        RHYTHM_DICTATION -> com.musictrainer.rhythm.RhythmMode.DICTATION
        RHYTHM_IMITATION -> com.musictrainer.rhythm.RhythmMode.IMITATION
        RHYTHM_PLAY -> com.musictrainer.rhythm.RhythmMode.PLAY
        else -> null
    }
}

@Serializable
data class Settings(
    val appMode: AppMode = AppMode.FEEDER,
    /** Colour theme for the whole app (both modes). */
    val theme: AppTheme = AppTheme.SYSTEM,
    /** Rhythm, chord and ear modes: play through a connected MIDI device even when "App piano" is chosen. */
    val trainerMidiOut: Boolean = true,
    /** Notation look in the Note finder. */
    val notationStyle: NotationStyle = NotationStyle(),
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
    /** Opacity of the stripe behind the current note, in percent (0 = no stripe). */
    val stripeOpacity: Int = 7,
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
    /** Built-in MCP server for Claude and other MCP clients (desktop). */
    val mcpEnabled: Boolean = false,
    val mcpPort: Int = 7432,
    /** Also reachable from other devices on the local network (private addresses only). */
    val mcpLan: Boolean = false,
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
            stripeOpacity = stripeOpacity.coerceIn(0, MAX_STRIPE_OPACITY),
            mcpPort = mcpPort.coerceIn(1024, 65535),
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
        const val MAX_STRIPE_OPACITY = 50
    }
}

/** Notes that can be asked in [clef] with the given settings. */
fun notePool(clef: Clef, settings: Settings): List<Int> =
    (max(settings.lowestMidi, clef.minMidi)..min(settings.highestMidi, clef.maxMidi))
        .filter { settings.includeAccidentals || !isBlackKey(it) }

fun rangeLabel(lowest: Int, highest: Int): String = "${noteName(lowest)}–${noteName(highest)}"
