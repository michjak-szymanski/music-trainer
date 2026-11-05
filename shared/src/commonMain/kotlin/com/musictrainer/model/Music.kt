package com.musictrainer.model

enum class Accidental(val symbol: String) { NONE(""), SHARP("♯"), FLAT("♭") }

/**
 * A clef, described by the diatonic index of the note on its bottom staff line
 * and the MIDI range that is reasonable to show in it.
 */
enum class Clef(val label: String, val bottomLineDiatonic: Int, val minMidi: Int, val maxMidi: Int) {
    TREBLE("Treble", 4 * 7 + 2, 57, 108), // bottom line E4; notes from A3 upward
    BASS("Bass", 2 * 7 + 4, 21, 64);      // bottom line G2; notes up to E4

    val other: Clef get() = if (this == TREBLE) BASS else TREBLE
}

private val LETTERS = charArrayOf('C', 'D', 'E', 'F', 'G', 'A', 'B')

/** Pitch class -> letter index for natural notes, or -1 for black keys. */
private val NATURAL_LETTER = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)

fun isBlackKey(midi: Int): Boolean = NATURAL_LETTER[midi.mod(12)] < 0

fun octaveOf(midi: Int): Int = midi.floorDiv(12) - 1

/** A MIDI pitch together with how it is written on the staff. */
data class SpelledNote(val midi: Int, val letter: Int, val accidental: Accidental) {
    val octave: Int
        get() = octaveOf(
            midi + when (accidental) {
                Accidental.SHARP -> -1
                Accidental.FLAT -> 1
                Accidental.NONE -> 0
            }
        )

    val diatonic: Int get() = octave * 7 + letter

    val name: String get() = "${LETTERS[letter]}${accidental.symbol}$octave"

    /** 0 = bottom line, 8 = top line, odd numbers are spaces. */
    fun staffPosition(clef: Clef): Int = diatonic - clef.bottomLineDiatonic

    companion object {
        fun spell(midi: Int, preferFlat: Boolean = false): SpelledNote {
            val pc = midi.mod(12)
            val natural = NATURAL_LETTER[pc]
            return when {
                natural >= 0 -> SpelledNote(midi, natural, Accidental.NONE)
                preferFlat -> SpelledNote(midi, NATURAL_LETTER[pc + 1], Accidental.FLAT)
                else -> SpelledNote(midi, NATURAL_LETTER[pc - 1], Accidental.SHARP)
            }
        }
    }
}

/** Short display name, e.g. "C4" or "C♯4". */
fun noteName(midi: Int): String = SpelledNote.spell(midi).name

/** Name showing both spellings for black keys, e.g. "C♯4 / D♭4". */
fun fullNoteName(midi: Int): String =
    if (isBlackKey(midi)) "${SpelledNote.spell(midi).name} / ${SpelledNote.spell(midi, true).name}"
    else noteName(midi)

const val MIDI_MIN = 21  // A0, lowest key of an 88-key piano
const val MIDI_MAX = 108 // C8, highest key of an 88-key piano

/** Common keyboard sizes with their usual (un-transposed) range. */
enum class KeyboardSize(val keys: Int, val lowest: Int, val highest: Int) {
    K25(25, 48, 72),
    K37(37, 48, 84),
    K49(49, 36, 84),
    K61(61, 36, 96),
    K76(76, 28, 103),
    K88(88, 21, 108),
}
