package com.musictrainer.notation

import com.musictrainer.model.Clef
import kotlin.math.roundToInt

/**
 * Reads MuseScore files (.mscx, or zipped .mscz; formats 3.x–4.x) straight into a [Score]:
 * exact durations, tuplets, voices, spelling (tpc), accidentals as engraved, ties, clef/key/time changes.
 * The piano part (or the first part with two staves) becomes the grand staff.
 */
object MuseScoreParser {
    fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun parse(bytes: ByteArray, fallbackTitle: String): Score {
        val xml = if (isZip(bytes)) {
            val entries = unzipEntries(bytes)
            val rootPath = entries["META-INF/container.xml"]?.let { c ->
                // MuseScore 4.5 lists the style sheet first, so take the first .mscx entry.
                runCatching {
                    Xml.parse(c.decodeToString()).child("rootfiles")?.all("rootfile")
                        ?.mapNotNull { it.attrs["full-path"] }?.firstOrNull { it.endsWith(".mscx", ignoreCase = true) }
                }.getOrNull()
            }
            val main = rootPath?.let { entries[it] }
                ?: entries.entries.firstOrNull { it.key.endsWith(".mscx") && '/' !in it.key }?.value
                ?: entries.entries.firstOrNull { it.key.endsWith(".mscx") }?.value
                ?: throw MidiFormatException("No score found in the MuseScore file")
            main.decodeToString()
        } else bytes.decodeToString()
        return fromXml(Xml.parse(xml), fallbackTitle)
    }

    private val TYPE_TICKS = mapOf(
        "long" to 16 * TICKS_PER_QUARTER, "breve" to 8 * TICKS_PER_QUARTER, "whole" to 4 * TICKS_PER_QUARTER,
        "half" to 2 * TICKS_PER_QUARTER, "quarter" to TICKS_PER_QUARTER, "eighth" to TICKS_PER_QUARTER / 2,
        "16th" to TICKS_PER_QUARTER / 4, "32nd" to TICKS_PER_QUARTER / 8, "64th" to 3, "128th" to 1,
    )
    private val GRACE = setOf("acciaccatura", "appoggiatura", "grace4", "grace8", "grace16", "grace32", "grace8after", "grace16after", "grace32after")

    /** "3/4" text, or `<duration z="3" n="4"/>` attributes (MuseScore 2). */
    private fun fraction(node: XmlNode?): Int? =
        node?.let { fraction(it.text.ifBlank { null } ?: it.attrs["z"]?.let { z -> "$z/${it.attrs["n"] ?: "1"}" }) }

    private fun fraction(s: String?): Int? {
        val parts = s?.trim()?.split('/') ?: return null
        val n = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val d = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return n * 4 * TICKS_PER_QUARTER / d
    }

    private fun clefOf(type: String?): Clef? = when {
        type == null -> null
        type.startsWith("F") -> Clef.BASS
        else -> Clef.TREBLE // G clefs; C and percussion clefs are drawn as treble
    }

    /** Written pitch from MuseScore's tonal pitch class (line of fifths, 14 = C). */
    private fun written(midi: Int, tpc: Int?): WrittenPitch {
        if (tpc == null || tpc < -1 || tpc > 33) return spell(midi, KeySignature(0))
        val letter = "FCGDAEB"[(tpc + 1).mod(7)].let { "CDEFGAB".indexOf(it) }
        val alter = (tpc + 1).floorDiv(7) - 2
        val pcs = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return WrittenPitch(midi, letter, alter, (midi - alter - pcs[letter]).floorDiv(12) - 1)
    }

    private fun accidentalOf(subtype: String?): Int? = when (subtype) {
        null -> null
        "accidentalSharp" -> 1
        "accidentalFlat" -> -1
        "accidentalNatural" -> 0
        "accidentalDoubleSharp", "accidentalSharpSharp" -> 2
        "accidentalDoubleFlat" -> -2
        // MuseScore 2 names
        "sharp" -> 1
        "flat" -> -1
        "natural" -> 0
        "sharp2" -> 2
        "flat2" -> -2
        else -> null
    }

    private class RawNote(val pitch: WrittenPitch, val accidental: Int?, val tieTo: Boolean, val tieFrom: Boolean)
    private class Raw(
        val staff: Clef, val measure: Int, val start: Int, val duration: Int, val base: Int, val dots: Int,
        val notes: List<RawNote>, val voice: Int, val clef: Clef?, val tuplets: List<TupletInfo>,
        val visible: Boolean, val wholeMeasure: Boolean,
    )

    /**
     * The voices of a bar as (voice index, elements). MuseScore 3–4 wrap them in `<voice>`; MuseScore 2 puts
     * everything directly in the bar and marks other voices with `<track>` on chords and rests.
     */
    private fun voicesOf(measure: XmlNode): List<Pair<Int, List<XmlNode>>> {
        val voices = measure.all("voice")
        if (voices.isNotEmpty()) return voices.mapIndexed { i, v -> i to v.children }
        val groups = LinkedHashMap<Int, MutableList<XmlNode>>()
        for (el in measure.children) {
            val v = if (el.name == "Chord" || el.name == "Rest") el.int("track")?.mod(4) ?: 0 else 0
            groups.getOrPut(v) { ArrayList() } += el
        }
        return groups.toSortedMap().map { (v, els) -> v to els }
    }

    private fun fromXml(root: XmlNode, fallbackTitle: String): Score {
        val score = root.child("Score") ?: root
        val title = score.all("metaTag").firstOrNull { it.attrs["name"] == "workTitle" }?.text?.trim()?.ifBlank { null }
            ?: findTitleText(score) ?: fallbackTitle

        // Which staves form the grand staff.
        val parts = score.all("Part")
        val part = parts.firstOrNull { p ->
            val name = (p.child("Instrument")?.text("longName") ?: p.text("trackName") ?: "").lowercase()
            p.all("Staff").size >= 2 && ("piano" in name || "keyboard" in name || "organ" in name)
        } ?: parts.firstOrNull { it.all("Staff").size >= 2 } ?: parts.firstOrNull()
        val musicStaves = score.all("Staff").filter { it.child("Measure") != null }
        val ids = part?.all("Staff")?.mapNotNull { it.attrs["id"] }.orEmpty().ifEmpty { musicStaves.mapNotNull { it.attrs["id"] } }
        val upper = musicStaves.firstOrNull { it.attrs["id"] == ids.getOrNull(0) } ?: musicStaves.firstOrNull()
            ?: throw MidiFormatException("The MuseScore file has no music")
        val lower = musicStaves.firstOrNull { it.attrs["id"] == ids.getOrNull(1) && it !== upper }
        val defaultClef = part?.all("Staff")?.associate { (it.attrs["id"] ?: "") to clefOf(it.text("defaultClef")) }.orEmpty()

        // Bar starts and lengths come from the upper staff.
        val measureNodes = upper.all("Measure")
        val starts = IntArray(measureNodes.size)
        val lengths = IntArray(measureNodes.size)
        val meters = arrayOfNulls<Meter>(measureNodes.size)
        val keys = arrayOfNulls<KeySignature>(measureNodes.size)
        var meter = Meter(TimeSignature(4, 4))
        var key: KeySignature? = null
        var bpm: Int? = null
        var t = 0
        measureNodes.forEachIndexed { i, m ->
            for ((_, els) in voicesOf(m)) {
                // The piece's tempo is a marking at its very start (bar 1, before the first note or rest), as in
                // MuseScore; later markings (e.g. a hidden slow-down near the end) are not the starting tempo.
                var atStart = i == 0
                for (el in els) when (el.name) {
                    "TimeSig" -> { val n = el.int("sigN"); val d = el.int("sigD"); if (n != null && d != null && n > 0 && d > 0) meter = Meter(TimeSignature(n, d.coerceAtMost(16))) }
                    "KeySig" -> (el.int("concertKey") ?: el.int("accidental") ?: el.int("key"))?.let { key = KeySignature(it.coerceIn(-7, 7)) }
                    "Tempo" -> if (bpm == null && atStart) el.text("tempo")?.toDoubleOrNull()?.let { bpm = (it * 60).roundToInt().coerceIn(20, 400) }
                    "Chord", "Rest", "location" -> atStart = false
                }
            }
            meters[i] = meter
            keys[i] = key
            starts[i] = t
            lengths[i] = fraction(m.attrs["len"]) ?: meter.measureLength
            t += lengths[i]
        }

        val raws = ArrayList<Raw>()
        for ((staffNode, staff) in listOfNotNull(upper to Clef.TREBLE, lower?.let { it to Clef.BASS })) {
            var clef = defaultClef[staffNode.attrs["id"]] ?: staff
            var firstElement = true
            staffNode.all("Measure").forEachIndexed { mi, m ->
                if (mi >= measureNodes.size) return@forEachIndexed
                // MuseScore 2 defines tuplets once per bar (<Tuplet id="1">) and chords refer to them by number.
                val tupletDefs = m.all("Tuplet").filter { it.attrs["id"] != null }
                    .associate { it.attrs.getValue("id") to Pair(it.int("actualNotes") ?: 3, it.int("normalNotes") ?: 2) }
                voicesOf(m).forEach { (vi, elements) ->
                    var cursor = 0
                    var pendingClef: Clef? = null
                    val tuplets = ArrayList<Triple<Int, Int, Int>>() // actual, normal, id
                    var tupletCounter = 0
                    for (el in elements) when (el.name) {
                        "location" -> cursor += fraction(el.text("fractions")) ?: 0
                        "Clef" -> clefOf(el.text("concertClefType") ?: el.text("subtype"))?.let { c -> if (c != clef) pendingClef = c; clef = c }
                        "Tuplet" -> if (el.attrs["id"] == null) tuplets += Triple(el.int("actualNotes") ?: 3, el.int("normalNotes") ?: 2, mi * 1000 + vi * 100 + tupletCounter++)
                        "endTuplet" -> if (tuplets.isNotEmpty()) tuplets.removeAt(tuplets.lastIndex)
                        "Chord", "Rest" -> {
                            if (el.children.any { it.name in GRACE }) continue
                            val type = el.text("durationType") ?: "quarter"
                            val wholeMeasure = type == "measure"
                            val dots = el.int("dots") ?: 0
                            val base = if (wholeMeasure) lengths[mi] else TYPE_TICKS[type] ?: TICKS_PER_QUARTER
                            var dur = if (wholeMeasure) (fraction(el.child("duration")) ?: lengths[mi]) else base * ((1 shl (dots + 1)) - 1) / (1 shl dots)
                            // MuseScore 2: <Tuplet>1</Tuplet> inside the chord refers to a definition in the bar.
                            val legacyTuplet = el.text("Tuplet")?.let { ref -> tupletDefs[ref]?.let { (a, n) -> Triple(a, n, mi * 1000 + vi * 100 + 50 + (ref.toIntOrNull() ?: 0)) } }
                            val active = if (legacyTuplet != null) tuplets + legacyTuplet else tuplets
                            for ((a, n, _) in active) dur = dur * n / a
                            val tupletInfos = active.mapIndexed { depth, (a, n, id) -> TupletInfo(id, a, n, depth) }
                            val notes = if (el.name == "Rest") emptyList() else el.all("Note").mapNotNull { nNode ->
                                val midi = nNode.int("pitch") ?: return@mapNotNull null
                                val ties = nNode.all("Spanner").filter { it.attrs["type"] == "Tie" }
                                RawNote(
                                    written(midi, nNode.int("tpc")),
                                    accidentalOf(nNode.child("Accidental")?.text("subtype")),
                                    tieTo = ties.any { it.child("next") != null } || nNode.child("Tie") != null,
                                    tieFrom = ties.any { it.child("prev") != null } || nNode.child("endSpanner") != null,
                                )
                            }
                            val visible = el.text("visible") != "0"
                            val clefHere = if (firstElement && vi == 0) clef else pendingClef
                            raws += Raw(staff, mi, cursor, dur.coerceAtLeast(1), base, dots, notes, vi, clefHere, tupletInfos, visible, wholeMeasure)
                            if (vi == 0) firstElement = false
                            pendingClef = null
                            cursor += dur.coerceAtLeast(1)
                        }
                    }
                }
            }
        }

        // Play steps: every moment a (non-tied) note starts.
        val onsets = raws.filter { r -> r.notes.any { !it.tieFrom } }
        val times = onsets.map { starts[it.measure] + it.start }.distinct().sorted()
        val stepOf = times.withIndex().associate { (i, v) -> v to i }
        val steps = times.map { time ->
            val at = onsets.filter { starts[it.measure] + it.start == time }
            PlayStep(
                time, at.first().measure,
                Clef.entries.associateWith { st -> at.filter { it.staff == st }.flatMap { r -> r.notes.filter { !it.tieFrom }.map { it.pitch.midi } }.toSet() },
                at.flatMap { r -> r.notes.map { it.pitch.midi to r.duration } }.toMap(),
            )
        }

        var prevSig: TimeSignature? = null
        var prevKey: KeySignature? = null
        val firstKey = keys.firstOrNull { it != null } ?: KeySignature(0)
        val measures = measureNodes.indices.map { mi ->
            val m = meters[mi]!!
            val k = keys[mi] ?: firstKey
            val staves = Clef.entries.associateWith { st ->
                val list = raws.filter { it.staff == st && it.measure == mi }.sortedWith(compareBy({ it.start }, { it.voice }))
                    .map { r ->
                        ScoreElement(
                            start = r.start, duration = r.duration, dots = r.dots,
                            notes = r.notes.map { ScoreNote(it.pitch, it.accidental, it.tieTo, it.tieFrom) },
                            wholeMeasureRest = r.wholeMeasure && r.notes.isEmpty(),
                            stepIndex = if (r.notes.any { !it.tieFrom }) stepOf[starts[mi] + r.start] ?: -1 else -1,
                            writtenBase = r.base, voice = r.voice, clef = r.clef, tuplet = r.tuplets.lastOrNull(), visible = r.visible,
                            tuplets = r.tuplets,
                        )
                    }
                    .ifEmpty { listOf(ScoreElement(0, lengths[mi], 0, emptyList(), wholeMeasureRest = true)) }
                assignBeams(list, m)
                list
            }
            ScoreMeasure(
                mi, starts[mi], m,
                showTimeSignature = prevSig != m.signature, staves = staves, length = lengths[mi],
                key = k, showKeySignature = prevKey != null && prevKey != k,
            ).also { prevSig = m.signature; prevKey = k }
        }
        return Score(title, firstKey, measures, steps, bpm ?: 120, tempoFromFile = bpm != null)
    }

    /** Title text from the first vertical frame (VBox) when there is no workTitle. */
    private fun findTitleText(score: XmlNode): String? {
        for (staff in score.all("Staff")) for (box in staff.all("VBox")) for (text in box.all("Text")) {
            if (text.text("style")?.lowercase() == "title") {
                val raw = text.child("text")?.let { flatten(it) }?.trim()
                if (!raw.isNullOrBlank()) return raw
            }
        }
        return null
    }

    private fun flatten(n: XmlNode): String = n.text + n.children.joinToString("") { flatten(it) }
}
