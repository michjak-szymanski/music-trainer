package com.musictrainer.notation

/** Built-in pieces, generated as real MIDI files so they go through the same import path as user files. */
object DemoPieces {
    class Demo(val id: String, val title: String, val bytes: ByteArray)

    private const val TICKS_PER_16TH = 120 // at 480 ticks per quarter

    /**
     * Tiny note language: `NOTE:DUR` with NOTE like `C4`, `F#5`, `Bb3`, chords `C4+E4+G4`, rest `r`;
     * DUR in 16th notes. `|` separates bars (for readability only).
     */
    fun voice(text: String): List<NoteSpec> {
        val out = ArrayList<NoteSpec>()
        var t = 0
        for (token in text.split(' ', '|').filter { it.isNotBlank() }) {
            val (what, dur) = token.split(':').let { it[0] to it[1].toInt() }
            if (what != "r") for (name in what.split('+')) out += NoteSpec(midiOf(name), t * TICKS_PER_16TH, dur * TICKS_PER_16TH)
            t += dur
        }
        return out
    }

    fun midiOf(name: String): Int {
        val letter = "CDEFGAB".indexOf(name[0].uppercaseChar())
        require(letter >= 0) { "Bad note $name" }
        var i = 1
        var alter = 0
        while (i < name.length && (name[i] == '#' || name[i] == 'b')) { alter += if (name[i] == '#') 1 else -1; i++ }
        val octave = name.substring(i).toInt()
        return (octave + 1) * 12 + intArrayOf(0, 2, 4, 5, 7, 9, 11)[letter] + alter
    }

    private fun demo(id: String, title: String, rh: String, lh: String, sig: TimeSignature, fifths: Int, bpm: Int) =
        Demo(id, title, MidiWriter.write(listOf(voice(rh), voice(lh)), timeSignature = sig, keyFifths = fifths,
            trackNames = listOf("Right hand", "Left hand"), bpm = bpm))

    val all: List<Demo> by lazy {
        listOf(
            demo(
                "demo:ode", "Ode to Joy (Beethoven)",
                rh = "E4:4 E4:4 F4:4 G4:4 | G4:4 F4:4 E4:4 D4:4 | C4:4 C4:4 D4:4 E4:4 | E4:6 D4:2 D4:8 |" +
                    "E4:4 E4:4 F4:4 G4:4 | G4:4 F4:4 E4:4 D4:4 | C4:4 C4:4 D4:4 E4:4 | D4:6 C4:2 C4:8",
                lh = "C3+G3:16 | G2+G3:16 | C3+G3:8 E3:8 | G2+D3:16 | C3+G3:16 | G2+G3:16 | C3+E3:8 G2:8 | G2+B2:8 C3:8",
                sig = TimeSignature(4, 4), fifths = 0, bpm = 96,
            ),
            demo(
                "demo:rhythm", "Rhythm study in G",
                rh = "G4:4 A4:2 B4:2 C5:4 D5:4 | D5:1 C5:1 B4:1 A4:1 G4:2 A4:2 B4:8 | G4:3 A4:1 B4:2 C5:4 B4:2 A4:4 |" +
                    "G4:8 r:2 F5:2 E5:8 | D5:4 G4+B4+D5:4 r:4 | G4+B4+D5:16",
                lh = "G2:8 D3:8 | G2:2 D3:2 G3:2 D3:2 G2:2 D3:2 G3:2 D3:2 | C3:4 E3:4 G3:4 E3:4 | D3:12 r:4 |" +
                    "r:4 G2:4 B2:4 D3:4 | G2:16",
                sig = TimeSignature(4, 4), fifths = 1, bpm = 80,
            ),
            demo(
                "demo:sixeight", "Barcarolle study in 6/8",
                rh = "D5:6 A4:2 B4:2 C#5:2 | D5:2 E5:2 F#5:2 E5:4 D5:2 | A4:12 | G4:2 F#4:2 E4:2 D4:6",
                lh = "D3:6 A3:6 | D3:6 G3:6 | A2:6 E3:6 | D3:12",
                sig = TimeSignature(6, 8), fifths = 2, bpm = 72,
            ),
            demo(
                "demo:scale", "C major scale, hands together",
                rh = scale(60), lh = scale(48), sig = TimeSignature(4, 4), fifths = 0, bpm = 90,
            ),
        )
    }

    /** Two octaves up and down in eighths from [tonic], then rests to fill four bars. */
    private fun scale(tonic: Int): String {
        val steps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val up = (0..14).map { tonic + 12 * (it / 7) + steps[it % 7] }
        val notes = up + up.reversed().drop(1)
        val names = notes.joinToString(" ") { m -> "${"CDEFGAB"[steps.indexOf(m.mod(12))]}${m / 12 - 1}:2" }
        return "$names r:2 r:4"
    }
}
