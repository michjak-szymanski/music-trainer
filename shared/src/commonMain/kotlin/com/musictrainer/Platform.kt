package com.musictrainer

import com.musictrainer.audio.KeySound
import com.musictrainer.audio.NoSound
import com.musictrainer.data.FileStore
import com.musictrainer.midi.MidiInput

/** Everything platform specific the shared app needs, supplied by each entry point. */
class Platform(
    val name: String,
    val midi: MidiInput,
    val store: FileStore,
    val now: () -> Long,
    val formatDate: (Long) -> String,
    /** Piano sound for the on-screen keyboard. */
    val sound: KeySound = NoSound,
)
