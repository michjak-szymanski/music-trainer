package com.musictrainer.notation

/** A note from a MIDI file, in ticks. */
data class MidiNote(val pitch: Int, val start: Long, val end: Long, val track: Int, val channel: Int)

data class TimeSignature(val numerator: Int, val denominator: Int) {
    override fun toString() = "$numerator/$denominator"
}

/** Everything the score builder needs from a Standard MIDI File. */
class MidiData(
    val ticksPerQuarter: Int,
    val notes: List<MidiNote>,
    /** (tick, time signature), sorted. */
    val timeSignatures: List<Pair<Long, TimeSignature>>,
    /** (tick, sharps (+) / flats (−)), sorted. */
    val keySignatures: List<Pair<Long, Int>>,
    val trackNames: Map<Int, String>,
    /** (tick, microseconds per quarter note), sorted. */
    val tempos: List<Pair<Long, Int>> = emptyList(),
) {
    /** Tempo at the start of the piece in quarter notes per minute (120 if the file has none). */
    val initialBpm: Int
        get() = tempos.firstOrNull()?.second?.takeIf { it > 0 }?.let { (60_000_000.0 / it).toInt().coerceIn(20, 400) } ?: 120
}

class MidiFormatException(message: String) : Exception(message)

/** Minimal, tolerant Standard MIDI File (format 0/1) reader. */
object MidiParser {
    fun parse(bytes: ByteArray): MidiData {
        var pos = 0
        fun need(n: Int) {
            if (pos + n > bytes.size) throw MidiFormatException("Unexpected end of file")
        }
        fun u8(): Int { need(1); return bytes[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int = (u16() shl 16) or u16()
        fun tag(): String { need(4); return bytes.decodeToString(pos, pos + 4).also { pos += 4 } }

        if (bytes.size < 14 || tag() != "MThd") throw MidiFormatException("Not a MIDI file")
        val headerLen = u32()
        u16() // format
        val trackCount = u16()
        val division = u16()
        pos += headerLen - 6
        if (division and 0x8000 != 0) throw MidiFormatException("SMPTE-timed MIDI files are not supported")
        val ppq = division.coerceAtLeast(1)

        val notes = ArrayList<MidiNote>()
        val timeSigs = ArrayList<Pair<Long, TimeSignature>>()
        val keySigs = ArrayList<Pair<Long, Int>>()
        val names = HashMap<Int, String>()
        val tempos = ArrayList<Pair<Long, Int>>()

        var track = 0
        while (pos + 8 <= bytes.size && track < trackCount + 16) {
            val id = tag()
            val len = u32()
            val end = minOf(bytes.size, pos + len)
            if (id != "MTrk") { pos = end; continue }

            var tick = 0L
            var running = 0
            val open = HashMap<Int, ArrayDeque<Long>>() // channel*128+pitch -> start ticks
            fun varLen(): Long {
                var v = 0L
                repeat(4) {
                    val b = u8()
                    v = (v shl 7) or (b and 0x7F).toLong()
                    if (b and 0x80 == 0) return v
                }
                return v
            }
            fun noteOff(ch: Int, pitch: Int) {
                val starts = open[ch * 128 + pitch] ?: return
                val s = starts.removeFirstOrNull() ?: return
                notes += MidiNote(pitch, s, tick, track, ch)
            }

            while (pos < end) {
                tick += varLen()
                if (pos >= end) break
                var status = bytes[pos].toInt() and 0xFF
                if (status >= 0x80) pos++ else if (running != 0) status = running else { pos++; continue }
                when {
                    status == 0xFF -> {
                        val type = u8()
                        val l = varLen().toInt()
                        val dataStart = pos
                        when (type) {
                            0x58 -> if (l >= 2) timeSigs += tick to TimeSignature(
                                bytes[dataStart].toInt() and 0xFF,
                                1 shl (bytes[dataStart + 1].toInt() and 0xFF).coerceAtMost(6),
                            )
                            0x59 -> if (l >= 1) keySigs += tick to bytes[dataStart].toInt().coerceIn(-7, 7)
                            0x51 -> if (l >= 3) tempos += tick to (
                                ((bytes[dataStart].toInt() and 0xFF) shl 16) or
                                    ((bytes[dataStart + 1].toInt() and 0xFF) shl 8) or (bytes[dataStart + 2].toInt() and 0xFF)
                                )
                            0x03 -> names.putIfAbsent(track, bytes.decodeToString(dataStart, minOf(end, dataStart + l)).filter { it >= ' ' && it != '�' }.trim())
                        }
                        pos = dataStart + l
                    }
                    status == 0xF0 || status == 0xF7 -> pos += varLen().toInt()
                    else -> {
                        if (status < 0xF0) running = status
                        val ch = status and 0x0F
                        when (status and 0xF0) {
                            0x80 -> { val p = u8(); u8(); noteOff(ch, p) }
                            0x90 -> {
                                val p = u8(); val v = u8()
                                if (v == 0) noteOff(ch, p) else open.getOrPut(ch * 128 + p) { ArrayDeque() }.addLast(tick)
                            }
                            0xA0, 0xB0, 0xE0 -> { u8(); u8() }
                            0xC0, 0xD0 -> u8()
                            else -> pos++ // unknown system message: skip a byte
                        }
                    }
                }
            }
            // Close notes left hanging at the end of the track.
            for ((key, starts) in open) for (s in starts) notes += MidiNote(key % 128, s, maxOf(tick, s + ppq / 4), track, key / 128)
            pos = end
            track++
        }

        val cleaned = notes
            .filter { it.channel != 9 } // drop the drum channel
            .map { if (it.end <= it.start) it.copy(end = it.start + ppq / 8) else it }
            .sortedWith(compareBy({ it.start }, { it.pitch }))
        if (cleaned.isEmpty()) throw MidiFormatException("The file contains no notes")
        return MidiData(ppq, cleaned, timeSigs.sortedBy { it.first }, keySigs.sortedBy { it.first }, names, tempos.sortedBy { it.first })
    }
}

/** A note to write: times in ticks. */
data class NoteSpec(val pitch: Int, val start: Int, val duration: Int)

/** Writes a format-1 Standard MIDI File (used for the built-in demo pieces and tests). */
object MidiWriter {
    fun write(
        tracks: List<List<NoteSpec>>,
        ticksPerQuarter: Int = 480,
        timeSignature: TimeSignature = TimeSignature(4, 4),
        keyFifths: Int = 0,
        trackNames: List<String> = emptyList(),
        bpm: Int = 100,
    ): ByteArray {
        val out = ByteBuilder()
        out.ascii("MThd"); out.u32(6); out.u16(1); out.u16(tracks.size + 1); out.u16(ticksPerQuarter)

        // Conductor track: tempo, time and key signature.
        val meta = ByteBuilder()
        meta.varLen(0); meta.bytes(0xFF, 0x51, 0x03); meta.u24(60_000_000 / bpm)
        val dd = generateSequence(1) { it * 2 }.indexOf(timeSignature.denominator).coerceAtLeast(0)
        meta.varLen(0); meta.bytes(0xFF, 0x58, 0x04, timeSignature.numerator, dd, 24, 8)
        meta.varLen(0); meta.bytes(0xFF, 0x59, 0x02, keyFifths and 0xFF, 0)
        meta.varLen(0); meta.bytes(0xFF, 0x2F, 0x00)
        out.chunk(meta)

        tracks.forEachIndexed { i, notes ->
            val t = ByteBuilder()
            trackNames.getOrNull(i)?.let { name ->
                val b = name.encodeToByteArray()
                t.varLen(0); t.bytes(0xFF, 0x03); t.varLen(b.size.toLong()); b.forEach { t.bytes(it.toInt() and 0xFF) }
            }
            // (tick, isOn, pitch) with note-offs before note-ons at the same tick.
            val events = notes.flatMap { listOf(Triple(it.start, true, it.pitch), Triple(it.start + it.duration, false, it.pitch)) }
                .sortedWith(compareBy({ it.first }, { it.second }))
            var last = 0
            for ((tick, on, pitch) in events) {
                t.varLen((tick - last).toLong()); last = tick
                t.bytes(if (on) 0x90 else 0x80, pitch, if (on) 80 else 0)
            }
            t.varLen(0); t.bytes(0xFF, 0x2F, 0x00)
            out.chunk(t)
        }
        return out.toByteArray()
    }

    private class ByteBuilder {
        private val buf = ArrayList<Byte>()
        fun bytes(vararg v: Int) = v.forEach { buf += it.toByte() }
        fun ascii(s: String) = s.forEach { buf += it.code.toByte() }
        fun u16(v: Int) = bytes(v shr 8 and 0xFF, v and 0xFF)
        fun u24(v: Int) = bytes(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
        fun u32(v: Int) = bytes(v ushr 24 and 0xFF, v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
        fun varLen(value: Long) {
            var v = value
            val stack = ArrayList<Int>()
            stack += (v and 0x7F).toInt(); v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            for (i in stack.indices.reversed()) buf += stack[i].toByte()
        }
        fun chunk(body: ByteBuilder) { ascii("MTrk"); u32(body.buf.size); buf.addAll(body.buf) }
        fun toByteArray() = buf.toByteArray()
    }
}
