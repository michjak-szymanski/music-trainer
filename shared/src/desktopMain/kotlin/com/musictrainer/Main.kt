package com.musictrainer

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.musictrainer.data.FileStore
import com.musictrainer.ui.App
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class DesktopFileStore(private val dir: File) : FileStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    override fun write(name: String, content: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun main() = application {
    val midi = remember { DesktopMidiInput().also { it.start() } }
    val sound = remember { DesktopKeySound() }
    val platform = remember {
        Platform(
            name = "Desktop",
            midi = midi,
            store = DesktopFileStore(
                System.getenv("MUSIC_TRAINER_HOME")?.let(::File)
                    ?: File(System.getProperty("user.home"), ".music-trainer")
            ),
            now = System::currentTimeMillis,
            formatDate = { dateFormat.format(Instant.ofEpochMilli(it)) },
            sound = sound,
        )
    }
    Window(
        onCloseRequest = {
            midi.stop()
            sound.release()
            exitApplication()
        },
        title = "Music Trainer",
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        App(platform)
    }
}
