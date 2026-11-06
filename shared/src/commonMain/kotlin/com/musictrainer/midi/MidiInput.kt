package com.musictrainer.midi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class MidiDeviceStatus(val id: String, val name: String)

private val NO_OFFS = MutableSharedFlow<Int>()

/** A source of note-on events from all connected MIDI input devices. */
interface MidiInput {
    /** Input devices that are currently connected and being listened to. */
    val devices: StateFlow<List<MidiDeviceStatus>>

    /** Last problem opening or scanning devices, or null. */
    val error: StateFlow<String?>

    /** MIDI note numbers of note-on events (velocity > 0). */
    val noteOns: SharedFlow<Int>

    /** MIDI note numbers of released keys (note-off, or note-on with velocity 0). */
    val noteOffs: SharedFlow<Int> get() = NO_OFFS

    /** Starts listening and watching for devices being plugged in or out. */
    fun start()

    /** Forces an immediate rescan of devices. */
    fun rescan()

    fun stop()
}
