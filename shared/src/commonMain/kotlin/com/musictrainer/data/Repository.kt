package com.musictrainer.data

import com.musictrainer.library.PieceMeta
import com.musictrainer.model.SessionRecord
import com.musictrainer.player.PlayerRecord
import com.musictrainer.player.PlayerSettings
import com.musictrainer.player.RecordedEvent
import com.musictrainer.player.RecordingInfo
import com.musictrainer.model.Settings
import kotlinx.serialization.json.Json

/** Minimal text-file storage, implemented per platform. */
interface FileStore {
    fun read(name: String): String?
    fun write(name: String, content: String)
    fun delete(name: String) = Unit
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

    fun loadPlayerSettings(): PlayerSettings = load(PLAYER_SETTINGS) ?: PlayerSettings()
    fun savePlayerSettings(s: PlayerSettings) = store.write(PLAYER_SETTINGS, json.encodeToString(s))

    /** Newest first. */
    fun loadPlayerHistory(): List<PlayerRecord> = load(PLAYER_HISTORY) ?: emptyList()
    fun savePlayerHistory(h: List<PlayerRecord>) = store.write(PLAYER_HISTORY, json.encodeToString(h))

    /** Newest first. */
    fun loadRecordings(): List<RecordingInfo> = load(RECORDINGS) ?: emptyList()
    fun saveRecordings(r: List<RecordingInfo>) = store.write(RECORDINGS, json.encodeToString(r))
    fun loadRecordingEvents(info: RecordingInfo): List<RecordedEvent> = load(info.fileName) ?: emptyList()
    fun saveRecordingEvents(info: RecordingInfo, events: List<RecordedEvent>) = store.write(info.fileName, Json.encodeToString(events))
    fun deleteRecordingEvents(info: RecordingInfo) = store.delete(info.fileName)

    fun loadLibraryCache(): List<PieceMeta> = load(LIBRARY) ?: emptyList()
    fun saveLibraryCache(p: List<PieceMeta>) = store.write(LIBRARY, json.encodeToString(p))

    private inline fun <reified T> load(name: String): T? =
        runCatching { store.read(name)?.let { json.decodeFromString<T>(it) } }.getOrNull()

    private companion object {
        const val SETTINGS = "settings.json"
        const val HISTORY = "history.json"
        const val PLAYER_SETTINGS = "player-settings.json"
        const val PLAYER_HISTORY = "player-history.json"
        const val LIBRARY = "library.json"
        const val RECORDINGS = "recordings.json"
    }
}
