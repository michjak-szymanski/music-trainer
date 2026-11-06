package com.musictrainer

import com.musictrainer.library.FileEntry
import com.musictrainer.library.MusicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.File

class DesktopMusicFiles : MusicFiles {
    override suspend fun pickFolder(): String? = withContext(Dispatchers.IO) {
        NativeFolderPicker.pick("Choose a folder with MIDI or MuseScore files")
    }

    override suspend fun list(folder: String): List<FileEntry> = withContext(Dispatchers.IO) {
        File(folder).walkTopDown().maxDepth(8)
            .filter { it.isFile && it.extension.lowercase() in MusicFiles.SUPPORTED_EXTENSIONS }
            .map { FileEntry(it.absolutePath, it.name, it.lastModified(), it.length()) }
            .toList()
    }

    override suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(id).readBytes() }.getOrNull()
    }

    override fun label(folder: String): String = folder
}
