package com.musictrainer.library

/** A music file in one of the library folders. [id] is a path (desktop) or document URI (Android). */
data class FileEntry(val id: String, val name: String, val modified: Long, val size: Long)

/** Access to the user's music folders, implemented per platform. */
interface MusicFiles {
    /** Lets the user choose a folder; returns its id, or null if cancelled. */
    suspend fun pickFolder(): String?

    /** All supported music files (MIDI, MuseScore) in [folder] and its subfolders. */
    suspend fun list(folder: String): List<FileEntry>

    suspend fun read(id: String): ByteArray?

    /** Human-readable folder name. */
    fun label(folder: String): String = folder

    companion object {
        val MIDI_EXTENSIONS = setOf("mid", "midi", "kar")
        val MUSESCORE_EXTENSIONS = setOf("mscz", "mscx")
        val SUPPORTED_EXTENSIONS = MIDI_EXTENSIONS + MUSESCORE_EXTENSIONS
    }
}

object NoMusicFiles : MusicFiles {
    override suspend fun pickFolder(): String? = null
    override suspend fun list(folder: String) = emptyList<FileEntry>()
    override suspend fun read(id: String): ByteArray? = null
}
