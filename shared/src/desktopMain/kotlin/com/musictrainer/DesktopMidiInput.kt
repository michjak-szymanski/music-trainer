package com.musictrainer

import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.midi.Transmitter

/**
 * MIDI input via javax.sound.midi (Windows: WinMM, Linux: ALSA).
 * Rescans every 2 seconds so keyboards can be plugged in or out while running.
 */
class DesktopMidiInput : MidiInput {
    private val _devices = MutableStateFlow<List<MidiDeviceStatus>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    private val _noteOns = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    override val devices: StateFlow<List<MidiDeviceStatus>> = _devices
    override val error: StateFlow<String?> = _error
    override val noteOns: SharedFlow<Int> = _noteOns

    private class Opened(val device: MidiDevice, val transmitter: Transmitter)

    /** Only touched from the scanner thread. */
    private val opened = LinkedHashMap<String, Opened>()
    private val failed = HashSet<String>()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "midi-scanner").apply { isDaemon = true }
    }

    override fun start() {
        executor.scheduleWithFixedDelay(::scan, 0, 2, TimeUnit.SECONDS)
    }

    override fun rescan() {
        executor.execute {
            failed.clear()
            scan()
        }
    }

    override fun stop() {
        executor.shutdownNow()
        opened.values.forEach(::close)
        opened.clear()
    }

    private fun scan() {
        try {
            val present = LinkedHashMap<String, MidiDevice.Info>()
            for (info in MidiSystem.getMidiDeviceInfo()) {
                val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
                if (device is Sequencer || device is Synthesizer) continue
                if (device.maxTransmitters == 0) continue // output-only port
                present["${info.name}|${info.description}|${info.vendor}"] = info
            }

            (opened.keys - present.keys).forEach { key -> opened.remove(key)?.let(::close) }
            failed.retainAll(present.keys)

            for ((key, info) in present) {
                if (key in opened || key in failed) continue
                try {
                    val device = MidiSystem.getMidiDevice(info)
                    if (!device.isOpen) device.open()
                    val transmitter = device.transmitter
                    transmitter.receiver = NoteReceiver()
                    opened[key] = Opened(device, transmitter)
                    _error.value = null
                } catch (e: Exception) {
                    failed += key
                    _error.value = "Could not open MIDI device \"${info.name}\" (is another app using it?): ${e.message}"
                }
            }
            _devices.value = opened.map { (key, o) -> MidiDeviceStatus(key, o.device.deviceInfo.name) }
        } catch (e: Exception) {
            _error.value = "MIDI scan failed: ${e.message}"
        }
    }

    private fun close(o: Opened) {
        runCatching { o.transmitter.close() }
        runCatching { o.device.close() }
    }

    private inner class NoteReceiver : Receiver {
        override fun send(message: MidiMessage, timeStamp: Long) {
            if (message is ShortMessage && message.command == ShortMessage.NOTE_ON && message.data2 > 0) {
                _noteOns.tryEmit(message.data1)
            }
        }

        override fun close() = Unit
    }
}
