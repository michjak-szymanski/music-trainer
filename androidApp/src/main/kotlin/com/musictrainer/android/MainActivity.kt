package com.musictrainer.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.musictrainer.Platform
import com.musictrainer.data.FileStore
import com.musictrainer.ui.App
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class AndroidFileStore(private val dir: File) : FileStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    override fun write(name: String, content: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(dir, name))
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var midi: AndroidMidiInput
    private val sound = AndroidKeySound()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        midi = AndroidMidiInput(this)
        midi.start()
        val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val platform = Platform(
            name = "Android",
            midi = midi,
            store = AndroidFileStore(filesDir),
            now = System::currentTimeMillis,
            formatDate = { dateFormat.format(Instant.ofEpochMilli(it)) },
            sound = sound,
        )
        setContent { App(platform) }
    }

    override fun onDestroy() {
        midi.stop()
        sound.release()
        super.onDestroy()
    }
}
