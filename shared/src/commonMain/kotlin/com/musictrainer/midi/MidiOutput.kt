package com.musictrainer.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** MIDI output devices (external synths, digital pianos) the app can play through. */
interface MidiOutput {
    val devices: StateFlow<List<MidiDeviceStatus>>
    /** Last problem opening or sending to an output, or null. */
    val error: StateFlow<String?> get() = NO_ERROR
    fun refresh()
    fun noteOn(deviceId: String, midi: Int, velocity: Int)
    fun noteOff(deviceId: String, midi: Int)
    fun allNotesOff(deviceId: String)
    /** A percussion hit on MIDI channel 10 (General MIDI drums), e.g. a wood block for the metronome. */
    fun drum(deviceId: String, note: Int, velocity: Int) = Unit
    fun close()
}

private val NO_ERROR = MutableStateFlow<String?>(null)

object NoMidiOutput : MidiOutput {
    override val devices: StateFlow<List<MidiDeviceStatus>> = MutableStateFlow(emptyList())
    override fun refresh() = Unit
    override fun noteOn(deviceId: String, midi: Int, velocity: Int) = Unit
    override fun noteOff(deviceId: String, midi: Int) = Unit
    override fun allNotesOff(deviceId: String) = Unit
    override fun close() = Unit
}
