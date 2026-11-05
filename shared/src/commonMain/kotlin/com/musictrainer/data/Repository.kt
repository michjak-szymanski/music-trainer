package com.musictrainer.data

import com.musictrainer.model.SessionRecord
import com.musictrainer.model.Settings
import kotlinx.serialization.json.Json

/** Minimal text-file storage, implemented per platform. */
interface FileStore {
    fun read(name: String): String?
    fun write(name: String, content: String)
}

class Repository(private val store: FileStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun loadSettings(): Settings =
        runCatching { store.read(SETTINGS)?.let { json.decodeFromString<Settings>(it) } }
            .getOrNull()?.sanitized() ?: Settings()

    fun saveSettings(settings: Settings) = store.write(SETTINGS, json.encodeToString(settings))

    /** Newest first. */
    fun loadHistory(): List<SessionRecord> =
        runCatching { store.read(HISTORY)?.let { json.decodeFromString<List<SessionRecord>>(it) } }
            .getOrNull() ?: emptyList()

    fun saveHistory(history: List<SessionRecord>) = store.write(HISTORY, json.encodeToString(history))

    private companion object {
        const val SETTINGS = "settings.json"
        const val HISTORY = "history.json"
    }
}
