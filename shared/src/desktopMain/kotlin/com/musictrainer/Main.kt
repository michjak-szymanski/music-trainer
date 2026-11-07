package com.musictrainer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.musictrainer.resources.Res
import com.musictrainer.resources.app_icon
import com.musictrainer.ui.appColorScheme
import org.jetbrains.compose.resources.painterResource
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

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

/**
 * Where settings and history live: MUSIGIUM_HOME (or the older MUSIC_TRAINER_HOME), else ~/.musigium.
 * The first start after the rename copies the old ~/.music-trainer folder (which stays as a backup).
 */
private fun dataDir(): File {
    (System.getenv("MUSIGIUM_HOME") ?: System.getenv("MUSIC_TRAINER_HOME"))?.let { return File(it) }
    val home = File(System.getProperty("user.home"))
    val dir = File(home, ".musigium")
    val old = File(home, ".music-trainer")
    if (!dir.exists() && old.isDirectory) runCatching { old.copyRecursively(dir) }
    return dir
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun main() = application {
    val midi = remember { DesktopMidiInput().also { it.start() } }
    val sound = remember { DesktopKeySound() }
    val midiOut = remember { DesktopMidiOutput() }
    val store = remember { DesktopFileStore(dataDir()) }
    val mcp = remember { McpServer(store, System.getProperty("jpackage.app-version") ?: "dev") }
    // The theme chosen in Settings (null until the app reports it): drives the title bar colour.
    val appDark = remember { mutableStateOf<Boolean?>(null) }
    val platform = remember {
        Platform(
            name = "Desktop",
            midi = midi,
            store = store,
            now = System::currentTimeMillis,
            formatDate = { dateFormat.format(Instant.ofEpochMilli(it)) },
            sound = sound,
            files = DesktopMusicFiles(),
            midiOut = midiOut,
            mcp = mcp,
            onDarkTheme = { appDark.value = it },
            touchKeyboard = WindowsTouchKeyboard,
            openFile = { path -> Thread { runCatching { java.awt.Desktop.getDesktop().open(File(path)) } }.apply { isDaemon = true }.start() },
        )
    }
    Window(
        onCloseRequest = {
            midi.stop()
            sound.release()
            midiOut.close()
            mcp.stop()
            exitApplication()
        },
        title = "Musigium",
        // Window/taskbar icon; without it Windows shows the default Java icon.
        icon = painterResource(Res.drawable.app_icon),
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        val dark = appDark.value ?: isSystemInDarkTheme()
        LaunchedEffect(dark) {
            val colors = appColorScheme(dark)
            // Noticeably darker than the tab row below it, so the draggable title bar stands out.
            val caption = lerp(colors.surface, Color.Black, if (dark) 0.6f else 0.12f)
            WindowsTitleBar.apply(window, dark, caption = caption, text = colors.onSurface)
        }
        App(platform)
    }
}
