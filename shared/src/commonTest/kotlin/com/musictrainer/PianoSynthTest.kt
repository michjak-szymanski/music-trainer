package com.musictrainer

import com.musictrainer.audio.PianoSamples
import com.musictrainer.audio.PianoSynth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PianoSynthTest {
    /** Frequency estimated from upward zero crossings. */
    private fun frequency(buf: ShortArray, rate: Int): Double {
        var crossings = 0
        var first = -1
        var last = -1
        for (i in 1 until buf.size) if (buf[i - 1] < 0 && buf[i] >= 0) {
            if (first < 0) first = i
            last = i
            crossings++
        }
        return (crossings - 1) * rate.toDouble() / (last - first)
    }

    @Test
    fun samplesAreRepitchedToTheExactKey() {
        val sampleRate = 32_000
        // A fake "recording" of A4 (440 Hz) at the sample rate the bundled files use.
        val a4 = ShortArray(sampleRate) { (sin(2 * PI * 440 * it / sampleRate) * 12_000).toInt().toShort() }
        val synth = PianoSynth()
        synth.setSamples(PianoSamples(sampleRate, mapOf(69 to a4)))
        for (midi in listOf(68, 69, 70)) {
            synth.noteOn(midi)
            val out = ShortArray(20_000)
            synth.render(out)
            val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val measured = frequency(out, PianoSynth.SAMPLE_RATE)
            assertTrue(abs(measured - expected) < 1.0, "midi $midi: expected $expected Hz, got $measured")
            repeat(5) { synth.render(ShortArray(20_000)) } // let the voice finish
        }
    }

    @Test
    fun releasedNotesFadeOutQuickly() {
        val rate = 32_000
        val long = ShortArray(rate * 5) { (sin(2 * PI * 440 * it / rate) * 12_000).toInt().toShort() } // 5 s, no decay
        val synth = PianoSynth()
        synth.setSamples(PianoSamples(rate, mapOf(69 to long)))
        synth.noteOn(69)
        val held = ShortArray(4410); synth.render(held)
        assertTrue(held.maxOf { abs(it.toInt()) } > 5000, "sounds while held")
        synth.noteOff(69)
        synth.render(ShortArray(PianoSynth.SAMPLE_RATE / 2)) // 0.5 s after release
        val after = ShortArray(4410); synth.render(after)
        assertTrue(after.maxOf { abs(it.toInt()) } < 50, "silent after release, got ${after.maxOf { abs(it.toInt()) }}")
        assertTrue(synth.isIdle)
    }

    @Test
    fun parsesWav() {
        val data = shortArrayOf(1, -2, 300)
        val bytes = ByteArray(44 + 6)
        fun put(i: Int, s: String) = s.encodeToByteArray().copyInto(bytes, i)
        fun le(i: Int, v: Int, n: Int) { for (k in 0 until n) bytes[i + k] = (v shr (8 * k)).toByte() }
        put(0, "RIFF"); le(4, 36 + 6, 4); put(8, "WAVE")
        put(12, "fmt "); le(16, 16, 4); le(20, 1, 2); le(22, 1, 2); le(24, 32000, 4); le(28, 64000, 4); le(32, 2, 2); le(34, 16, 2)
        put(36, "data"); le(40, 6, 4)
        data.forEachIndexed { i, v -> le(44 + 2 * i, v.toInt(), 2) }
        val (rate, parsed) = PianoSamples.parseWav(bytes)
        assertEquals(32000, rate)
        assertEquals(data.toList(), parsed.toList())
    }
}
