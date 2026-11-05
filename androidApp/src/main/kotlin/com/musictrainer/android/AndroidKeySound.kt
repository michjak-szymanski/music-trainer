package com.musictrainer.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.musictrainer.audio.KeySound
import com.musictrainer.audio.PianoSamples
import com.musictrainer.audio.PianoSynth

/** Plays piano samples ([PianoSynth]) through a low-latency AudioTrack. The audio thread stops itself when idle. */
class AndroidKeySound : KeySound {
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
                thread = Thread(::loop, "key-sound").apply { start() }
            }
        }
    }

    override fun release() {
        released = true
    }

    private fun loop() {
        val minBuffer = AudioTrack.getMinBufferSize(
            PianoSynth.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PianoSynth.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minBuffer, PianoSynth.CHUNK * 2 * 4))
                .build()
        } catch (e: Exception) {
            Log.w("MusicTrainer", "Key sound unavailable", e)
            return
        }
        val samples = ShortArray(PianoSynth.CHUNK)
        var idleChunks = 0
        try {
            track.play()
            while (!released && idleChunks < IDLE_CHUNKS_BEFORE_STOP) {
                synchronized(lock) {
                    synth.render(samples)
                    if (synth.isIdle) idleChunks++ else idleChunks = 0
                }
                track.write(samples, 0, samples.size)
            }
            track.stop()
        } finally {
            track.release()
        }
    }

    private companion object {
        /** ~2 seconds of silence. */
        const val IDLE_CHUNKS_BEFORE_STOP = 350
    }
}
