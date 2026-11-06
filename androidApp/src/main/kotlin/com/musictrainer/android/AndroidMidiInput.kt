package com.musictrainer.android

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.musictrainer.midi.MidiDeviceStatus
import com.musictrainer.midi.MidiInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** MIDI input via android.media.midi (USB keyboards over OTG, and other MIDI apps). */
class AndroidMidiInput(context: Context) : MidiInput {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val handler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<MidiDeviceStatus>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    private val _noteOns = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    override val devices: StateFlow<List<MidiDeviceStatus>> = _devices
    override val error: StateFlow<String?> = _error
    override val noteOns: SharedFlow<Int> = _noteOns
    private val _noteOffs = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    override val noteOffs: SharedFlow<Int> = _noteOffs

    private class Opened(val device: MidiDevice, val ports: List<MidiOutputPort>, val name: String)

    // Accessed only on the main thread (callbacks use [handler]).
    private val opened = LinkedHashMap<Int, Opened>()
    private val pending = HashSet<Int>()

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = close(device.id)
    }

    override fun start() {
        if (manager == null) {
            _error.value = "This device does not support MIDI. Use the on-screen keyboard."
            return
        }
        manager.registerDeviceCallback(callback, handler)
        rescan()
    }

    override fun rescan() {
        handler.post { currentDevices().forEach(::open) }
    }

    override fun stop() {
        manager?.unregisterDeviceCallback(callback)
        opened.keys.toList().forEach(::close)
    }

    @Suppress("DEPRECATION")
    private fun currentDevices(): Collection<MidiDeviceInfo> {
        val m = manager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) {
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
        } else {
            m.devices.toList()
        }
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (info.outputPortCount == 0 || info.id in opened || info.id in pending) return
        pending += info.id
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device"
        m.openDevice(info, { device ->
            pending -= info.id
            if (device == null) {
                _error.value = "Could not open MIDI device \"$name\""
                return@openDevice
            }
            val ports = info.ports
                .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                .mapNotNull { device.openOutputPort(it.portNumber) }
            if (ports.isEmpty()) {
                device.close()
                _error.value = "MIDI device \"$name\" has no usable output port"
                return@openDevice
            }
            ports.forEach { it.connect(NoteParser()) }
            opened[info.id] = Opened(device, ports, name)
            _error.value = null
            publish()
        }, handler)
    }

    private fun close(id: Int) {
        opened.remove(id)?.let { o ->
            o.ports.forEach { runCatching { it.close() } }
            runCatching { o.device.close() }
        }
        publish()
    }

    private fun publish() {
        _devices.value = opened.map { (id, o) -> MidiDeviceStatus(id.toString(), o.name) }
    }

    /** Minimal MIDI byte-stream parser: emits note-on events, handles running status. */
    private inner class NoteParser : MidiReceiver() {
        private var status = 0
        private var data1 = -1

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> Unit // real-time bytes may appear anywhere
                    b >= 0x80 -> {
                        status = if (b >= 0xF0) 0 else b
                        data1 = -1
                    }
                    status == 0 -> Unit
                    (status and 0xF0) == 0xC0 || (status and 0xF0) == 0xD0 -> Unit // one data byte
                    data1 < 0 -> data1 = b
                    else -> {
                        val cmd = status and 0xF0
                        if (cmd == 0x90 && b > 0) _noteOns.tryEmit(data1)
                        else if (cmd == 0x80 || cmd == 0x90) _noteOffs.tryEmit(data1)
                        data1 = -1
                    }
                }
            }
        }
    }
}
