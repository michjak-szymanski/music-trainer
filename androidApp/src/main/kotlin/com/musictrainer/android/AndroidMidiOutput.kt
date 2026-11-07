package com.musictrainer.android

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Sends notes to MIDI devices that accept input (USB synths, digital pianos). */
class AndroidMidiOutput(context: Context) : MidiOutput {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val handler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<MidiDeviceStatus>>(emptyList())
    override val devices: StateFlow<List<MidiDeviceStatus>> = _devices
    private val infos = HashMap<String, MidiDeviceInfo>()
    private val ports = HashMap<String, MidiInputPort>()
    private val opening = HashSet<String>()

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            ports.remove(device.id.toString())?.let { runCatching { it.close() } }
            refresh()
        }
    }

    init {
        manager?.registerDeviceCallback(callback, handler)
        refresh()
    }

    @Suppress("DEPRECATION")
    override fun refresh() {
        val m = manager ?: return
        val all = if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) else m.devices.toList()
        infos.clear()
        for (info in all) if (info.inputPortCount > 0) infos[info.id.toString()] = info
        _devices.value = infos.map { (id, info) ->
            MidiDeviceStatus(id, info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device")
        }
    }

    /** Messages sent while the device is still opening; flushed once the port is ready. */
    private val pendingMessages = HashMap<String, MutableList<ByteArray>>()

    override fun drum(deviceId: String, note: Int, velocity: Int) {
        send(deviceId, 0x99, note, velocity) // note on, channel 10
        send(deviceId, 0x89, note, 0)
    }

    private fun send(id: String, vararg bytes: Int) {
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        val port = ports[id]
        if (port == null) {
            pendingMessages.getOrPut(id) { ArrayList() } += data
            open(id)
            return
        }
        runCatching { port.send(data, 0, data.size) }
    }

    private fun open(id: String) {
        val info = infos[id] ?: return
        if (!opening.add(id)) return
        manager?.openDevice(info, { device ->
            opening.remove(id)
            val portNumber = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.portNumber ?: 0
            device?.openInputPort(portNumber)?.let { port ->
                ports[id] = port
                pendingMessages.remove(id)?.forEach { runCatching { port.send(it, 0, it.size) } }
            }
        }, handler)
    }

    override fun noteOn(deviceId: String, midi: Int, velocity: Int) = send(deviceId, 0x90, midi, velocity)
    override fun noteOff(deviceId: String, midi: Int) = send(deviceId, 0x80, midi, 0)
    override fun allNotesOff(deviceId: String) = send(deviceId, 0xB0, 123, 0)

    override fun close() {
        manager?.unregisterDeviceCallback(callback)
        ports.values.forEach { runCatching { it.close() } }
        ports.clear()
    }
}
