package com.musictrainer

import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage

/**
 * MIDI output through javax.sound.midi (hardware ports and system synths). The device list is refreshed
 * every few seconds, so keyboards plugged in later appear; a device that was unplugged and plugged back
 * in is reopened instead of writing to a dead connection; problems are reported in [error].
 */
class DesktopMidiOutput : MidiOutput {
    private val _devices = MutableStateFlow<List<MidiDeviceStatus>>(emptyList())
    override val devices: StateFlow<List<MidiDeviceStatus>> = _devices
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error
    private val infos = HashMap<String, MidiDevice.Info>()
    private val open = HashMap<String, Pair<MidiDevice, Receiver>>()
    private val scanner = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "midi-out-scanner").apply { isDaemon = true } }

    init {
        refresh()
        scanner.scheduleWithFixedDelay({ refresh() }, 3, 3, TimeUnit.SECONDS)
    }

    @Synchronized
    override fun refresh() {
        val found = HashMap<String, MidiDevice.Info>()
        for (info in runCatching { MidiSystem.getMidiDeviceInfo() }.getOrDefault(emptyArray())) {
            val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            if (device is Sequencer || device.maxReceivers == 0) continue
            found["${info.name}|${info.description}|${info.vendor}"] = info
        }
        // Drop connections to devices that disappeared (unplugged); they'll be reopened when back.
        for (gone in open.keys - found.keys) closeDevice(gone)
        infos.clear(); infos.putAll(found)
        val list = infos.map { (id, info) -> MidiDeviceStatus(id, if (info.name == "Gervill") "Java software synthesizer" else info.name) }
            .sortedBy { it.name }
        if (list != _devices.value) _devices.value = list
    }

    @Synchronized
    private fun receiver(id: String): Receiver? {
        open[id]?.let { return it.second }
        val info = infos[id] ?: run { _error.value = "MIDI output isn't connected"; return null }
        return try {
            val device = MidiSystem.getMidiDevice(info)
            if (!device.isOpen) device.open()
            val r = device.receiver
            open[id] = device to r
            _error.value = null
            r
        } catch (e: Exception) {
            _error.value = "Couldn't open MIDI output \"${info.name}\" (is another app using it?): ${e.message}"
            null
        }
    }

    @Synchronized
    private fun closeDevice(id: String) {
        open.remove(id)?.let { (d, r) -> runCatching { r.close() }; runCatching { d.close() } }
    }

    private fun send(id: String, command: Int, data1: Int, data2: Int) {
        val msg = ShortMessage(command, 0, data1, data2)
        val first = receiver(id) ?: return
        try {
            first.send(msg, -1)
        } catch (e: Exception) {
            // Stale connection (device was replugged): reopen once.
            closeDevice(id)
            runCatching { receiver(id)?.send(msg, -1) }.onFailure { _error.value = "MIDI output failed: ${it.message}" }
        }
    }

    override fun noteOn(deviceId: String, midi: Int, velocity: Int) = send(deviceId, ShortMessage.NOTE_ON, midi, velocity)
    override fun noteOff(deviceId: String, midi: Int) = send(deviceId, ShortMessage.NOTE_OFF, midi, 0)
    override fun allNotesOff(deviceId: String) = send(deviceId, ShortMessage.CONTROL_CHANGE, 123, 0)

    @Synchronized
    override fun close() {
        scanner.shutdownNow()
        for (id in open.keys.toList()) closeDevice(id)
    }
}
