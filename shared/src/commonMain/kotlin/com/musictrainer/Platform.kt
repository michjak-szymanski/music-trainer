package com.musictrainer

import com.musictrainer.audio.KeySound
import com.musictrainer.audio.NoSound
import com.musictrainer.data.FileStore
import com.musictrainer.library.MusicFiles
import com.musictrainer.library.NoMusicFiles
import com.musictrainer.midi.MidiOutput
import com.musictrainer.midi.NoMidiOutput
import com.musictrainer.mcp.McpControl
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
    /** The user's music folders (Note player library). */
    val files: MusicFiles = NoMusicFiles,
    /** MIDI output devices for playback. */
    val midiOut: MidiOutput = NoMidiOutput,
    /** Built-in MCP server, or null where not available (Android). */
    val mcp: McpControl? = null,
    /** Told whether the app is showing its dark theme (desktop: colours the window title bar). */
    val onDarkTheme: ((Boolean) -> Unit)? = null,
    /** Opens a library file in the program the system uses for it (e.g. MuseScore), or null where unavailable. */
    val openFile: ((String) -> Unit)? = null,
    /** Desktop touch keyboard (null where the system shows one by itself, e.g. Android). */
    val touchKeyboard: com.musictrainer.ui.TouchKeyboard? = null,
)
