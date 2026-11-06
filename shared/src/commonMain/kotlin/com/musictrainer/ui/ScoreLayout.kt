package com.musictrainer.ui

import com.musictrainer.model.Clef
import com.musictrainer.notation.KeySignature
import com.musictrainer.notation.Score
import com.musictrainer.notation.ScoreElement
import com.musictrainer.notation.ScoreMeasure
import com.musictrainer.notation.T16
import com.musictrainer.notation.TICKS_PER_QUARTER
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// SMuFL code points (Bravura).
internal object Smufl {
    const val G_CLEF = ""
    const val F_CLEF = ""
    const val HEAD_WHOLE = ""
    const val HEAD_HALF = ""
    const val HEAD_BLACK = ""
    const val SHARP = ""
    const val FLAT = ""
    const val NATURAL = ""
    const val DOUBLE_SHARP = ""
    const val DOUBLE_FLAT = ""
    const val DOT = ""
    const val REST_WHOLE = ""
    const val REST_HALF = ""
    const val REST_QUARTER = ""
    const val REST_8 = ""
    const val REST_16 = ""
    const val REST_32 = ""
    const val REST_64 = ""
    val FLAGS_UP = listOf("", "", "", "") // 8th, 16th, 32nd, 64th
    val FLAGS_DOWN = listOf("", "", "", "")
    fun timeSigDigit(d: Int) = (0xE080 + d).toChar().toString()
    fun tupletDigit(d: Int) = (0xE880 + d).toChar().toString()
}

/** A music-font glyph with its SMuFL origin at (x, y). [step]/[midi] identify noteheads for colouring. */
class GlyphPrim(val code: String, val x: Float, val y: Float, val scale: Float = 1f, val step: Int = -1, val midi: Int = -1)
class LinePrim(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float)
/** Beam between two stem ends; [thickness] is signed (positive grows downwards, towards up-stem noteheads). */
class BeamPrim(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float)
class TiePrim(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val below: Boolean)
class BracePrim(val x: Float, val top: Float, val bottom: Float)

class SystemPrims(val top: Float, val bottom: Float, val startX: Float, val endX: Float) {
    val glyphs = ArrayList<GlyphPrim>()
    val lines = ArrayList<LinePrim>()
    val beams = ArrayList<BeamPrim>()
    val ties = ArrayList<TiePrim>()
    val braces = ArrayList<BracePrim>()
}

/** Where a play step is on the page. */
class StepPos(val system: Int, val x: Float)

class ScoreLayout(val systems: List<SystemPrims>, val height: Float, val stepPos: List<StepPos>, val space: Float)

private val SHARP_POS = intArrayOf(8, 5, 9, 6, 3, 7, 4) // treble staff positions of F C G D A E B
private val FLAT_POS = intArrayOf(4, 7, 3, 6, 2, 5, 1) // treble staff positions of B E A D G C F
private const val WHOLE = 4 * TICKS_PER_QUARTER

/** Number of beams/flags for a written value: 8th = 1, 16th = 2, 32nd = 3, 64th = 4. */
private fun beamLevels(base: Int): Int = when {
    base > 2 * T16 -> 0
    base > T16 -> 1
    base > T16 / 2 -> 2
    base > T16 / 4 -> 3
    else -> 4
}

/** Lays out a grand-staff [score] for a page [width] px wide with staff-space [space] px. */
fun layoutScore(score: Score, width: Float, space: Float, withTitle: Boolean = true): ScoreLayout {
    val half = space / 2
    val stemW = maxOf(space * 0.13f, 1.5f) // never thinner than 1.5 px, so stems inside the staff stay visible
    val x0 = space * 3.2f // room for the brace
    val x1 = width - space * 1.2f
    val staffGap = space * 7f
    val systemGap = space * 7.5f
    val titleH = if (withTitle) space * 5f else space * 1.5f
    val systemHeight = space * 8f + staffGap + systemGap
    val timeSigW = space * 2.8f
    val clefChangeW = space * 2.4f

    fun headW(e: ScoreElement) = if (e.base >= WHOLE) space * 1.69f else space * 1.18f
    fun keyWidth(k: KeySignature) = if (k.fifths != 0) abs(k.fifths) * space * 1.0f + space * 0.8f else 0f
    fun accColumns(e: ScoreElement): Int {
        val cols = ArrayList<MutableList<Int>>()
        for (n in e.notes.sortedByDescending { it.pitch.diatonic }) {
            if (n.accidental == null) continue
            val p = n.pitch.diatonic
            val col = cols.firstOrNull { c -> c.all { abs(it - p) >= 6 } } ?: ArrayList<Int>().also { cols += it }
            col += p
        }
        return cols.size
    }
    fun hasSeconds(e: ScoreElement) = e.notes.map { it.pitch.diatonic }.sorted().zipWithNext().any { (a, b) -> b - a == 1 }

    // ---- pre-pass: running clef and key, horizontal natural widths ----
    class MInfo(
        val m: ScoreMeasure,
        val times: IntArray,
        val slot: FloatArray,
        val pre: FloatArray,
        val key: KeySignature,
        /** Effective clef per staff at the start of the bar (after any clef on its first element). */
        val startClef: Map<Clef, Clef>,
        /** (staff, element index) that need a clef-change sign. */
        val clefChanges: Set<Pair<Clef, Int>>,
        val elementClef: Map<Clef, List<Clef>>,
        /** Staves whose clef changes right at the start of this bar. */
        val startChange: Set<Clef>,
    )
    val running = HashMap<Clef, Clef>().apply { Clef.entries.forEach { put(it, it) } }
    var runningKey = score.key
    val infos = score.measures.map { m ->
        runningKey = m.key ?: runningKey
        val all = m.staves.values.flatten().filter { it.visible }
        val times = all.map { it.start }.distinct().sorted().toIntArray()
        val onlyWholeRests = all.all { it.wholeMeasureRest }
        val changes = HashSet<Pair<Clef, Int>>()
        val changeTimes = HashSet<Int>()
        val elementClef = HashMap<Clef, List<Clef>>()
        val startClef = HashMap<Clef, Clef>()
        val startChange = HashSet<Clef>()
        for (staff in Clef.entries) {
            val list = m.staves.getValue(staff)
            val clefs = ArrayList<Clef>()
            list.forEachIndexed { i, e ->
                val c = e.clef ?: running.getValue(staff)
                if (c != running.getValue(staff)) {
                    if (e.start > 0) { changes += staff to i; changeTimes += e.start } else startChange += staff
                    running[staff] = c
                }
                clefs += running.getValue(staff)
            }
            startClef[staff] = list.firstOrNull { it.start == 0 }?.let { it.clef } ?: clefs.firstOrNull() ?: running.getValue(staff)
            elementClef[staff] = clefs
        }
        val slot = FloatArray(times.size) { i ->
            val gap = (times.getOrNull(i + 1) ?: m.length) - times[i]
            val dotted = all.any { it.start == times[i] && it.dots > 0 }
            if (onlyWholeRests) space * 9f else space * (1.5f + 1.9f * sqrt(gap / TICKS_PER_QUARTER.toFloat())) + if (dotted) space * 0.6f else 0f
        }
        val pre = FloatArray(times.size) { i ->
            val at = all.filter { it.start == times[i] }
            val cols = at.maxOfOrNull { accColumns(it) } ?: 0
            (if (cols > 0) cols * space * 1.1f + space * 0.3f else 0f) +
                (if (at.any { hasSeconds(it) }) space * 0.6f else 0f) +
                (if (times[i] in changeTimes) clefChangeW else 0f)
        }
        MInfo(m, times, slot, pre, runningKey, startClef, changes, elementClef, startChange)
    }
    fun lead(i: Int, firstInSystem: Boolean) = space * 1.1f +
        (if (infos[i].startChange.isNotEmpty() && !firstInSystem) clefChangeW else 0f) +
        (if (infos[i].m.showTimeSignature && !firstInSystem) timeSigW else 0f) +
        (if (infos[i].m.showKeySignature && !firstInSystem) keyWidth(infos[i].key) else 0f)
    fun natural(i: Int, first: Boolean) = lead(i, first) + infos[i].pre.sum() + infos[i].slot.sum() + space * 0.8f
    fun header(i: Int) = space * 3.6f + keyWidth(infos[i].key) + (if (infos[i].m.showTimeSignature) timeSigW else 0f)

    // ---- break into systems ----
    val systemRanges = ArrayList<IntRange>()
    run {
        var i = 0
        while (i < infos.size) {
            val avail = x1 - x0 - header(i)
            var used = 0f
            var j = i
            while (j < infos.size) {
                val w = natural(j, j == i)
                if (j > i && used + w > avail) break
                used += w
                j++
            }
            systemRanges += i until j
            i = j
        }
    }

    val systems = ArrayList<SystemPrims>()
    val stepPos = arrayOfNulls<StepPos>(score.steps.size)

    // Noteheads by (staff, voice, element sequence number, midi), for ties once everything is laid out.
    class Head(val system: Int, val x: Float, val right: Float, val y: Float, val stemUp: Boolean)
    data class HeadKey(val staff: Clef, val voice: Int, val seq: Int, val midi: Int)
    val heads = HashMap<HeadKey, Head>()
    val tieStarts = ArrayList<Pair<HeadKey, Head>>()
    val sequence = HashMap<Pair<Clef, Int>, Int>()

    systemRanges.forEachIndexed { si, range ->
        val top = titleH + si * systemHeight + space * 2f
        val trebleBottom = top + space * 4
        val bassTop = trebleBottom + staffGap
        val bassBottom = bassTop + space * 4
        val staffBottom = mapOf(Clef.TREBLE to trebleBottom, Clef.BASS to bassBottom)
        /** y of staff position [pos] on [staff] when read in [clef]. */
        fun y(staff: Clef, pos: Int) = staffBottom.getValue(staff) - pos * half
        val sys = SystemPrims(top - space * 3, bassBottom + space * 4, x0, x1)
        systems += sys

        for (staff in Clef.entries) for (l in 0..4) sys.lines += LinePrim(x0, y(staff, l * 2), x1, y(staff, l * 2), space * 0.1f)
        sys.braces += BracePrim(x0 - space * 0.4f, top, bassBottom)
        sys.lines += LinePrim(x0, top, x0, bassBottom, space * 0.14f)

        fun clefGlyph(staff: Clef, clef: Clef, x: Float, scale: Float) {
            sys.glyphs += if (clef == Clef.TREBLE) GlyphPrim(Smufl.G_CLEF, x, y(staff, 2), scale)
            else GlyphPrim(Smufl.F_CLEF, x, y(staff, 6), scale)
        }
        fun keySignature(k: KeySignature, x: Float, clefs: Map<Clef, Clef>) {
            for (i in 0 until abs(k.fifths)) {
                val pos = if (k.fifths > 0) SHARP_POS[i] else FLAT_POS[i]
                for (staff in Clef.entries) {
                    val p = if (clefs.getValue(staff) == Clef.TREBLE) pos else pos - 2
                    sys.glyphs += GlyphPrim(if (k.fifths > 0) Smufl.SHARP else Smufl.FLAT, x + i * space * 1.0f, y(staff, p))
                }
            }
        }
        fun timeSignature(m: ScoreMeasure, atX: Float) {
            val center = atX + space * 1.2f
            for (staff in Clef.entries) for ((num, pos) in listOf(m.meter.signature.numerator to 6, m.meter.signature.denominator to 2)) {
                val digits = num.toString()
                var dx = center - digits.length * space * 0.9f
                for (ch in digits) { sys.glyphs += GlyphPrim(Smufl.timeSigDigit(ch - '0'), dx, y(staff, pos)); dx += space * 1.8f }
            }
        }

        val firstInfo = infos[range.first]
        for (staff in Clef.entries) clefGlyph(staff, firstInfo.startClef.getValue(staff), x0 + space * 0.6f, 1f)
        var x = x0 + space * 3.6f
        keySignature(firstInfo.key, x, firstInfo.startClef)
        x += keyWidth(firstInfo.key)
        if (firstInfo.m.showTimeSignature) { timeSignature(firstInfo.m, x); x += timeSigW }

        // Justification: stretch the rhythmic slots to fill the line (not a short last line).
        val avail = x1 - x
        val naturalSum = range.sumOf { natural(it, it == range.first).toDouble() }.toFloat()
        val slotSum = range.sumOf { infos[it].slot.sum().toDouble() }.toFloat()
        val isLast = range.last == infos.lastIndex
        val stretch = if (isLast && naturalSum < avail * 0.75f) 1f else ((avail - (naturalSum - slotSum)) / slotSum).coerceAtLeast(0.6f)

        for (mi in range) {
            val info = infos[mi]
            val m = info.m
            val measureStartX = x
            var lx = x + space * 0.2f
            if (info.startChange.isNotEmpty() && mi != range.first) {
                for (staff in info.startChange) clefGlyph(staff, info.startClef.getValue(staff), lx, 0.75f)
                lx += clefChangeW
            }
            if (m.showKeySignature && mi != range.first) {
                keySignature(info.key, lx, info.startClef); lx += keyWidth(info.key)
            }
            if (m.showTimeSignature && mi != range.first) timeSignature(m, lx)
            x += lead(mi, mi == range.first)
            val timeX = HashMap<Int, Float>()
            for (i in info.times.indices) {
                x += info.pre[i]
                timeX[info.times[i]] = x
                x += info.slot[i] * stretch
            }
            x += space * 0.8f
            val barX = if (mi == range.last) x1 else x
            x = barX

            for (staff in Clef.entries) {
                val elements = m.staves.getValue(staff)
                val clefs = info.elementClef.getValue(staff)
                val voices = elements.filter { it.visible && !it.wholeMeasureRest }.map { it.voice }.distinct()
                val multiVoice = voices.size > 1

                class Geo(val e: ScoreElement, val index: Int, val ex: Float, val seq: Int) {
                    val clef = clefs[index]
                    fun pos(n: com.musictrainer.notation.ScoreNote) = n.pitch.staffPosition(clef)
                    val pos = e.notes.map { pos(it) }.sorted()
                    var up = true
                    var stemX = 0f
                    var tip = 0f
                    val hw = headW(e)
                    fun setDir(stemUp: Boolean) {
                        up = stemUp
                        stemX = if (up) ex + hw - stemW / 2 else ex + stemW / 2
                        val len = space * (3.5f + max(0, beamLevels(e.base) - 2) * 0.75f)
                        val far = if (up) y(staff, pos.last()) - len else y(staff, pos.first()) + len
                        tip = if (up) min(far, y(staff, 4)) else max(far, y(staff, 4))
                    }
                }
                val geos = elements.mapIndexed { i, e ->
                    val key = staff to e.voice
                    val seq = sequence.getOrPut(key) { 0 }; sequence[key] = seq + 1
                    Geo(e, i, timeX[e.start] ?: (measureStartX + space), seq)
                }
                fun dirFor(ps: List<Int>): Boolean = (4 - ps.first()) > (ps.last() - 4)
                for (g in geos) if (!g.e.isRest) g.setDir(if (multiVoice) g.e.voice % 2 == 0 else dirFor(g.pos))

                // Clef changes inside the bar.
                for (g in geos) if ((staff to g.index) in info.clefChanges) clefGlyph(staff, g.clef, g.ex - clefChangeW + space * 0.1f, 0.75f)

                // Beams: per voice and group; minimum stem lengths; secondary beams and beamlets.
                for ((_, group) in geos.filter { it.e.beamGroup >= 0 && it.e.visible }.groupBy { it.e.voice to it.e.beamGroup }) {
                    if (group.size < 2) continue
                    val up = if (multiVoice) group.first().e.voice % 2 == 0 else dirFor(group.flatMap { it.pos }.sorted())
                    group.forEach { it.setDir(up) }
                    val first = group.first()
                    val last = group.last()
                    val dx = (last.stemX - first.stemX).coerceAtLeast(1f)
                    val rise = (last.tip - first.tip).coerceIn(-space, space)
                    fun beamY(xx: Float, shift: Float) = first.tip + rise * (xx - first.stemX) / dx + shift
                    val maxLevels = group.maxOf { beamLevels(it.e.base) }
                    val minStem = space * (2.5f + (maxLevels - 1) * 0.75f)
                    var shift = 0f
                    for (g in group) {
                        val nearest = if (up) y(staff, g.pos.last()) else y(staff, g.pos.first())
                        val by = beamY(g.stemX, 0f)
                        shift = if (up) min(shift, (nearest - minStem) - by) else max(shift, (nearest + minStem) - by)
                    }
                    group.forEach { it.tip = beamY(it.stemX, shift) }
                    val thick = space * 0.5f * (if (up) 1 else -1)
                    sys.beams += BeamPrim(first.stemX - stemW / 2, first.tip, last.stemX + stemW / 2, last.tip, thick)
                    for (level in 2..maxLevels) {
                        val off = space * 0.75f * (level - 1) * (if (up) 1 else -1)
                        var i = 0
                        while (i < group.size) {
                            if (beamLevels(group[i].e.base) < level) { i++; continue }
                            var j = i
                            while (j + 1 < group.size && beamLevels(group[j + 1].e.base) >= level) j++
                            if (j > i) {
                                sys.beams += BeamPrim(group[i].stemX - stemW / 2, group[i].tip + off, group[j].stemX + stemW / 2, group[j].tip + off, thick)
                            } else {
                                val g = group[i]
                                val left = i == group.lastIndex || (i > 0 && group[i - 1].e.dots > 0)
                                val len = space * 1.1f * (if (left) -1 else 1)
                                sys.beams += BeamPrim(g.stemX, g.tip + off, g.stemX + len, g.tip + off + rise / dx * len, thick)
                            }
                            i = j + 1
                        }
                    }
                }

                for (g in geos) {
                    val e = g.e
                    if (!e.visible) continue
                    if (e.isRest) {
                        val code = when {
                            e.wholeMeasureRest || e.base >= WHOLE -> Smufl.REST_WHOLE
                            e.base >= 2 * TICKS_PER_QUARTER -> Smufl.REST_HALF
                            e.base >= TICKS_PER_QUARTER -> Smufl.REST_QUARTER
                            e.base >= 2 * T16 -> Smufl.REST_8
                            e.base >= T16 -> Smufl.REST_16
                            e.base >= T16 / 2 -> Smufl.REST_32
                            else -> Smufl.REST_64
                        }
                        val rx = if (e.wholeMeasureRest) (measureStartX + barX) / 2 - space * 0.56f else g.ex
                        val voiceShift = if (!multiVoice) 0 else if (e.voice % 2 == 0) 4 else -4
                        val ry = y(staff, (if (code == Smufl.REST_WHOLE) 6 else 4) + voiceShift)
                        sys.glyphs += GlyphPrim(code, rx, ry)
                        for (d in 0 until e.dots) sys.glyphs += GlyphPrim(Smufl.DOT, rx + space * (1.5f + d * 0.5f), y(staff, 5 + voiceShift))
                        continue
                    }
                    // Noteheads, with seconds put on the other side of the stem.
                    val ordered = e.notes.sortedBy { it.pitch.diatonic }.let { if (g.up) it else it.reversed() }
                    var prevPos = Int.MIN_VALUE
                    var prevShifted = false
                    var right = g.ex + g.hw
                    var left = g.ex
                    val headXs = HashMap<Int, Float>()
                    for (n in ordered) {
                        val p = g.pos(n)
                        val shifted = !prevShifted && prevPos != Int.MIN_VALUE && abs(p - prevPos) == 1
                        val hx = if (!shifted) g.ex else if (g.up) g.ex + g.hw - stemW else g.ex - g.hw + stemW
                        headXs[n.pitch.midi] = hx
                        right = max(right, hx + g.hw); left = min(left, hx)
                        prevPos = p; prevShifted = shifted
                        val code = when { e.base >= WHOLE -> Smufl.HEAD_WHOLE; e.base >= 2 * TICKS_PER_QUARTER -> Smufl.HEAD_HALF; else -> Smufl.HEAD_BLACK }
                        sys.glyphs += GlyphPrim(code, hx, y(staff, p), step = e.stepIndex, midi = n.pitch.midi)
                        val lx1 = hx - space * 0.35f
                        val lx2 = hx + g.hw + space * 0.35f
                        if (p <= -2) for (lp in -2 downTo p step 2) sys.lines += LinePrim(lx1, y(staff, lp), lx2, y(staff, lp), space * 0.16f)
                        if (p >= 10) for (lp in 10..p step 2) sys.lines += LinePrim(lx1, y(staff, lp), lx2, y(staff, lp), space * 0.16f)
                    }
                    // Accidentals in columns from the top note down.
                    val cols = ArrayList<MutableList<Int>>()
                    for (n in e.notes.sortedByDescending { it.pitch.diatonic }) {
                        val acc = n.accidental ?: continue
                        val p = g.pos(n)
                        var ci = cols.indexOfFirst { c -> c.all { abs(it - p) >= 6 } }
                        if (ci < 0) { cols += ArrayList<Int>(); ci = cols.lastIndex }
                        cols[ci] += p
                        val code = when (acc) { 2 -> Smufl.DOUBLE_SHARP; 1 -> Smufl.SHARP; -1 -> Smufl.FLAT; -2 -> Smufl.DOUBLE_FLAT; else -> Smufl.NATURAL }
                        sys.glyphs += GlyphPrim(code, left - space * 0.25f - (ci + 1) * space * 1.1f, y(staff, p))
                    }
                    // Augmentation dots (in the space above for notes on a line; voice 2 below).
                    if (e.dots > 0) {
                        val dotPos = e.notes.map {
                            val p = g.pos(it)
                            if (p % 2 != 0) p else if (multiVoice && e.voice % 2 == 1) p - 1 else p + 1
                        }.distinct()
                        for (p in dotPos) for (d in 0 until e.dots) sys.glyphs += GlyphPrim(Smufl.DOT, right + space * (0.35f + d * 0.5f), y(staff, p))
                    }
                    // Stem and flag.
                    if (e.base < WHOLE) {
                        val baseY = if (g.up) y(staff, g.pos.first()) - space * 0.17f else y(staff, g.pos.last()) + space * 0.17f
                        sys.lines += LinePrim(g.stemX, baseY, g.stemX, g.tip, stemW)
                        val levels = beamLevels(e.base)
                        if (e.beamGroup < 0 && levels > 0) {
                            sys.glyphs += GlyphPrim((if (g.up) Smufl.FLAGS_UP else Smufl.FLAGS_DOWN)[levels - 1], g.stemX - stemW / 2, g.tip)
                        }
                    }
                    if (e.stepIndex >= 0 && stepPos[e.stepIndex] == null) stepPos[e.stepIndex] = StepPos(si, g.ex + g.hw / 2)
                    for (n in e.notes) {
                        val hx = headXs.getValue(n.pitch.midi)
                        val head = Head(si, hx, hx + g.hw, y(staff, g.pos(n)), g.up)
                        val key = HeadKey(staff, e.voice, g.seq, n.pitch.midi)
                        heads[key] = head
                        if (n.tieToNext) tieStarts += key to head
                    }
                }

                // Tuplet numbers (with a bracket unless the whole tuplet is one beam group).
                val tupletIds = geos.filter { it.e.visible }.flatMap { g -> g.e.tuplets.map { g.e.voice to it } }.distinct()
                val maxDepth = tupletIds.maxOfOrNull { it.second.depth } ?: 0
                for ((voice, t) in tupletIds) {
                    val group = geos.filter { it.e.visible && it.e.voice == voice && it.e.tuplets.any { x -> x.id == t.id } }
                    if (group.isEmpty()) continue
                    val nestShift = space * 1.6f * (maxDepth - t.depth) // outer tuplets sit further out
                    val notes = group.filter { !it.e.isRest }
                    val above = if (notes.isEmpty()) true else notes.count { it.up } * 2 >= notes.size
                    val beamed = group.all { it.e.beamGroup >= 0 } && group.map { it.e.beamGroup }.distinct().size == 1
                    val xs = group.first().ex
                    val xe = group.last().ex + group.last().hw
                    val extreme = group.map { g ->
                        val ends = if (g.e.isRest) listOf(y(staff, 8), y(staff, 0)) else listOf(g.tip, y(staff, g.pos.first()), y(staff, g.pos.last()))
                        if (above) ends.min() else ends.max()
                    }
                    val yLine = if (above) min(extreme.min(), y(staff, 10)) - space * 1.2f - nestShift else max(extreme.max(), y(staff, -2)) + space * 1.2f + nestShift
                    val mid = (xs + xe) / 2
                    val digits = t.actual.toString()
                    val dw = space * 1.0f * digits.length
                    var dx = mid - dw / 2
                    for (ch in digits) { sys.glyphs += GlyphPrim(Smufl.tupletDigit(ch - '0'), dx, yLine + space * 0.6f, 0.8f); dx += space }
                    if (!beamed) {
                        val hook = space * 0.6f * (if (above) 1 else -1)
                        sys.lines += LinePrim(xs, yLine, mid - dw / 2 - space * 0.3f, yLine, space * 0.12f)
                        sys.lines += LinePrim(mid + dw / 2 + space * 0.3f, yLine, xe, yLine, space * 0.12f)
                        sys.lines += LinePrim(xs, yLine, xs, yLine + hook, space * 0.12f)
                        sys.lines += LinePrim(xe, yLine, xe, yLine + hook, space * 0.12f)
                    }
                }
            }
            if (mi == infos.lastIndex) {
                sys.lines += LinePrim(barX - space * 0.9f, top, barX - space * 0.9f, bassBottom, space * 0.16f)
                sys.lines += LinePrim(barX - space * 0.25f, top, barX - space * 0.25f, bassBottom, space * 0.5f)
            } else {
                sys.lines += LinePrim(barX, top, barX, bassBottom, space * 0.16f)
            }
        }
    }

    for ((key, from) in tieStarts) {
        // The tie ends at the next element of the same voice that has the same pitch.
        val to = heads[key.copy(seq = key.seq + 1)] ?: continue
        val below = from.stemUp
        val dy = space * 0.55f * (if (below) 1 else -1)
        if (to.system == from.system) {
            systems[from.system].ties += TiePrim(from.right + space * 0.1f, from.y + dy, to.x - space * 0.1f, to.y + dy, below)
        } else {
            val a = systems[from.system]
            a.ties += TiePrim(from.right + space * 0.1f, from.y + dy, a.endX - space * 0.3f, from.y + dy, below)
            val b = systems[to.system]
            b.ties += TiePrim(to.x - space * 2.5f, to.y + dy, to.x - space * 0.1f, to.y + dy, below)
        }
    }

    val height = (systems.lastOrNull()?.bottom ?: 0f) + space * 2
    val positions = stepPos.mapIndexed { i, p -> p ?: stepPos.take(i).lastOrNull { it != null } ?: StepPos(0, x0) }
    return ScoreLayout(systems, height, positions, space)
}
