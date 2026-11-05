package com.musictrainer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Plays a piano note for an on-screen key press. */
interface KeySound {
    fun setSamples(samples: PianoSamples)
    fun play(midi: Int)
    fun release()
}

object NoSound : KeySound {
    override fun setSamples(samples: PianoSamples) = Unit
    override fun play(midi: Int) = Unit
    override fun release() = Unit
}

/** Recorded piano notes (mono 16-bit) keyed by MIDI number, all at [sampleRate]. */
class PianoSamples(val sampleRate: Int, val byMidi: Map<Int, ShortArray>) {
    companion object {
        /** MIDI numbers of the bundled Salamander Grand Piano samples (every minor third, A0..C8). */
        val SAMPLE_NOTES: List<Int> = (21..108 step 3).toList()

        /** Parses a PCM 16-bit mono WAV file. */
        fun parseWav(bytes: ByteArray): Pair<Int, ShortArray> {
            fun u16(i: Int) = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
            fun tag(i: Int) = bytes.decodeToString(i, i + 4)
            require(tag(0) == "RIFF" && tag(8) == "WAVE") { "Not a WAV file" }
            var pos = 12
            var rate = 0
            while (pos + 8 <= bytes.size) {
                val id = tag(pos)
                val size = u32(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        require(u16(body) == 1 && u16(body + 2) == 1 && u16(body + 14) == 16) { "Need PCM 16-bit mono" }
                        rate = u32(body + 4)
                    }
                    "data" -> {
                        val n = minOf(size, bytes.size - body) / 2
                        return rate to ShortArray(n) { u16(body + 2 * it).toShort() }
                    }
                }
                pos = body + size + (size and 1)
            }
            error("WAV has no data chunk")
        }
    }
}

/**
 * Small polyphonic sample player. Each key plays the nearest recorded note,
 * resampled to the exact pitch. Falls back to a simple additive tone until
 * samples are loaded. Not thread-safe: platform outputs guard [noteOn] and [render] with one lock.
 */
class PianoSynth(private val outputRate: Int = SAMPLE_RATE) {
    private abstract class Voice {
        var done = false
        abstract fun next(): Double
    }

    private class SampleVoice(private val data: ShortArray, private val step: Double) : Voice() {
        private var pos = 0.0
        override fun next(): Double {
            val i = pos.toInt()
            if (i + 2 >= data.size) {
                done = true
                return 0.0
            }
            val t = pos - i
            pos += step
            // 4-point cubic Hermite interpolation: much cleaner than linear when repitching.
            val y0 = (if (i > 0) data[i - 1] else data[i]).toDouble()
            val y1 = data[i].toDouble()
            val y2 = data[i + 1].toDouble()
            val y3 = data[i + 2].toDouble()
            val c1 = 0.5 * (y2 - y0)
            val c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3
            val c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2)
            return (((c3 * t + c2) * t + c1) * t + y1) / Short.MAX_VALUE
        }
    }

    private class ToneVoice(midi: Int, rate: Int) : Voice() {
        private val step = 2 * PI * 440.0 * 2.0.pow((midi - 69) / 12.0) / rate
        private val tau = (1.6 - (midi - 21) / 87.0 * 1.1) * rate
        private val length = (tau * 4).toInt()
        private val attack = rate * 0.004
        private var n = 0
        override fun next(): Double {
            if (n >= length) {
                done = true
                return 0.0
            }
            val t = n++
            val env = exp(-t / tau) * (if (t < attack) t / attack else 1.0)
            val x = step * t
            return 0.6 * env * (sin(x) + 0.4 * sin(2 * x) * exp(-t / (tau * 0.5)) + 0.2 * sin(3 * x) * exp(-t / (tau * 0.3)))
        }
    }

    private var samples: PianoSamples? = null
    private val voices = ArrayList<Voice>()

    val isIdle: Boolean get() = voices.isEmpty()

    fun setSamples(s: PianoSamples) {
        samples = s
    }

    fun noteOn(midi: Int) {
        if (voices.size >= MAX_VOICES) voices.removeAt(0)
        val s = samples
        val root = s?.byMidi?.keys?.minByOrNull { abs(it - midi) }
        voices += if (s != null && root != null) {
            val step = 2.0.pow((midi - root) / 12.0) * s.sampleRate / outputRate
            SampleVoice(s.byMidi.getValue(root), step)
        } else {
            ToneVoice(midi, outputRate)
        }
    }

    /** Fills [out] with 16-bit mono samples. */
    fun render(out: ShortArray, frames: Int = out.size) {
        for (i in 0 until frames) {
            var sum = 0.0
            for (v in voices) sum += v.next()
            // Samples are normalized to peak at -3 dBFS, so a single note never reaches the limiter.
            val a = abs(sum)
            val limited = if (a <= KNEE) {
                sum
            } else { // soft knee for loud chords: approaches 1.0 but never clips
                val over = (a - KNEE) / (1 - KNEE)
                (KNEE + (1 - KNEE) * over / (1 + over)) * (if (sum < 0) -1 else 1)
            }
            out[i] = (limited.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        voices.removeAll { it.done }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val MAX_VOICES = 16
        private const val KNEE = 0.9
        /** Frames rendered per write: ~6 ms. */
        const val CHUNK = 256
    }
}
