package com.musictrainer

import com.musictrainer.audio.KeySound
import com.musictrainer.audio.PianoSamples
import com.musictrainer.audio.PianoSynth
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Plays piano samples ([PianoSynth]) through Java Sound. The audio thread stops itself when idle. */
class DesktopKeySound : KeySound {
    private val synth = PianoSynth()
    private val lock = Any()
    @Volatile private var thread: Thread? = null
    @Volatile private var released = false

    override fun setSamples(samples: PianoSamples) {
        synchronized(lock) { synth.setSamples(samples) }
    }

    override fun play(midi: Int) {
        synchronized(lock) {
            synth.noteOn(midi)
            if (thread?.isAlive != true && !released) {
                thread = Thread(::loop, "key-sound").apply { isDaemon = true; start() }
            }
        }
    }

    override fun stop(midi: Int) {
        synchronized(lock) { synth.noteOff(midi) }
    }

    override fun stopAll() {
        synchronized(lock) { synth.allNotesOff() }
    }

    override fun release() {
        released = true
    }

    private fun loop() {
        val format = AudioFormat(PianoSynth.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val line: SourceDataLine = try {
            AudioSystem.getSourceDataLine(format).apply {
                open(format, PianoSynth.CHUNK * 2 * 8)
                start()
            }
        } catch (e: Exception) {
            System.err.println("Key sound unavailable: ${e.message}")
            return
        }
        val samples = ShortArray(PianoSynth.CHUNK)
        val bytes = ByteArray(PianoSynth.CHUNK * 2)
        var idleChunks = 0
        try {
            while (!released && idleChunks < IDLE_CHUNKS_BEFORE_STOP) {
                synchronized(lock) {
                    synth.render(samples)
                    if (synth.isIdle) idleChunks++ else idleChunks = 0
                }
                for (i in samples.indices) {
                    val v = samples[i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)
            }
            line.drain()
        } finally {
            line.close()
        }
    }

    private companion object {
        /** ~2 seconds of silence. */
        const val IDLE_CHUNKS_BEFORE_STOP = 350
    }
}
