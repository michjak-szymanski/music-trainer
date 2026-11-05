package com.musictrainer.audio

import com.musictrainer.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Loads the bundled Salamander Grand Piano samples, or null if they can't be read. */
suspend fun loadPianoSamples(): PianoSamples? = withContext(Dispatchers.IO) {
    runCatching {
        var rate = 0
        val map = PianoSamples.SAMPLE_NOTES.associateWith { midi ->
            val (r, data) = PianoSamples.parseWav(Res.readBytes("files/piano/m$midi.wav"))
            rate = r
            data
        }
        PianoSamples(rate, map)
    }.onFailure { println("Piano samples unavailable: ${it.message}") }.getOrNull()
}
