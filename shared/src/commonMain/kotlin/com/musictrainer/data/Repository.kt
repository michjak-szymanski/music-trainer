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

    // Rhythm modes (one settings and history file per mode) and the chord finder.
    fun loadRhythmSettings(mode: com.musictrainer.rhythm.RhythmMode): com.musictrainer.rhythm.RhythmSettings =
        load("rhythm-${mode.name.lowercase()}-settings.json") ?: com.musictrainer.rhythm.RhythmSettings()
    fun saveRhythmSettings(mode: com.musictrainer.rhythm.RhythmMode, s: com.musictrainer.rhythm.RhythmSettings) =
        store.write("rhythm-${mode.name.lowercase()}-settings.json", json.encodeToString(s))
    fun loadRhythmHistory(): List<com.musictrainer.rhythm.RhythmRecord> = load(RHYTHM_HISTORY) ?: emptyList()
    fun saveRhythmHistory(h: List<com.musictrainer.rhythm.RhythmRecord>) = store.write(RHYTHM_HISTORY, json.encodeToString(h))
    fun loadChordSettings(): com.musictrainer.chords.ChordSettings = load(CHORD_SETTINGS) ?: com.musictrainer.chords.ChordSettings()
    fun saveChordSettings(s: com.musictrainer.chords.ChordSettings) = store.write(CHORD_SETTINGS, json.encodeToString(s))
    fun loadChordHistory(): List<com.musictrainer.chords.ChordRecord> = load(CHORD_HISTORY) ?: emptyList()
    fun saveChordHistory(h: List<com.musictrainer.chords.ChordRecord>) = store.write(CHORD_HISTORY, json.encodeToString(h))

    fun loadEarSettings(): com.musictrainer.chords.EarSettings = load(EAR_SETTINGS) ?: com.musictrainer.chords.EarSettings()
    fun saveEarSettings(s: com.musictrainer.chords.EarSettings) = store.write(EAR_SETTINGS, json.encodeToString(s))
    fun loadEarHistory(): List<com.musictrainer.chords.EarRecord> = load(EAR_HISTORY) ?: emptyList()
    fun saveEarHistory(h: List<com.musictrainer.chords.EarRecord>) = store.write(EAR_HISTORY, json.encodeToString(h))
    fun loadEarStats(): com.musictrainer.chords.EarStats = load(EAR_STATS) ?: com.musictrainer.chords.EarStats()
    fun saveEarStats(s: com.musictrainer.chords.EarStats) = store.write(EAR_STATS, json.encodeToString(s))

    fun loadProgSettings(): com.musictrainer.chords.ProgressionSettings = load(PROG_SETTINGS) ?: com.musictrainer.chords.ProgressionSettings()
    fun saveProgSettings(s: com.musictrainer.chords.ProgressionSettings) = store.write(PROG_SETTINGS, json.encodeToString(s))
    fun loadProgHistory(): List<com.musictrainer.chords.EarRecord> = load(PROG_HISTORY) ?: emptyList()
    fun saveProgHistory(h: List<com.musictrainer.chords.EarRecord>) = store.write(PROG_HISTORY, json.encodeToString(h))
    fun loadProgStats(): com.musictrainer.chords.EarStats = load(PROG_STATS) ?: com.musictrainer.chords.EarStats()
    fun saveProgStats(s: com.musictrainer.chords.EarStats) = store.write(PROG_STATS, json.encodeToString(s))

    fun loadPitchSettings(): com.musictrainer.chords.PitchSettings = load(PITCH_SETTINGS) ?: com.musictrainer.chords.PitchSettings()
    fun savePitchSettings(s: com.musictrainer.chords.PitchSettings) = store.write(PITCH_SETTINGS, json.encodeToString(s))
    fun loadPitchHistory(): List<com.musictrainer.chords.EarRecord> = load(PITCH_HISTORY) ?: emptyList()
    fun savePitchHistory(h: List<com.musictrainer.chords.EarRecord>) = store.write(PITCH_HISTORY, json.encodeToString(h))
    fun loadPitchStats(): com.musictrainer.chords.EarStats = load(PITCH_STATS) ?: com.musictrainer.chords.EarStats()
    fun savePitchStats(s: com.musictrainer.chords.EarStats) = store.write(PITCH_STATS, json.encodeToString(s))

    fun loadIntervalSettings(): com.musictrainer.chords.IntervalSettings = load(INTERVAL_SETTINGS) ?: com.musictrainer.chords.IntervalSettings()
    fun saveIntervalSettings(s: com.musictrainer.chords.IntervalSettings) = store.write(INTERVAL_SETTINGS, json.encodeToString(s))
    fun loadIntervalHistory(): List<com.musictrainer.chords.EarRecord> = load(INTERVAL_HISTORY) ?: emptyList()
    fun saveIntervalHistory(h: List<com.musictrainer.chords.EarRecord>) = store.write(INTERVAL_HISTORY, json.encodeToString(h))
    fun loadIntervalStats(): com.musictrainer.chords.EarStats = load(INTERVAL_STATS) ?: com.musictrainer.chords.EarStats()
    fun saveIntervalStats(s: com.musictrainer.chords.EarStats) = store.write(INTERVAL_STATS, json.encodeToString(s))

    fun loadMarks(): Map<String, List<com.musictrainer.player.PieceMark>> = load(MARKS) ?: emptyMap()
    fun saveMarks(m: Map<String, List<com.musictrainer.player.PieceMark>>) = store.write(MARKS, json.encodeToString(m))
    fun loadLoops(): Map<String, List<com.musictrainer.player.PieceLoop>> = load(LOOPS) ?: emptyMap()
    fun saveLoops(l: Map<String, List<com.musictrainer.player.PieceLoop>>) = store.write(LOOPS, json.encodeToString(l))

    private inline fun <reified T> load(name: String): T? =
        runCatching { store.read(name)?.let { json.decodeFromString<T>(it) } }.getOrNull()

    private companion object {
        const val RHYTHM_HISTORY = "rhythm-history.json"
        const val LOOPS = "loops.json"
        const val MARKS = "marks.json"
        const val EAR_SETTINGS = "ear-settings.json"
        const val INTERVAL_SETTINGS = "interval-settings.json"
        const val INTERVAL_HISTORY = "interval-history.json"
        const val INTERVAL_STATS = "interval-stats.json"
        const val PITCH_SETTINGS = "pitch-settings.json"
        const val PITCH_HISTORY = "pitch-history.json"
        const val PITCH_STATS = "pitch-stats.json"
        const val PROG_SETTINGS = "progression-settings.json"
        const val PROG_HISTORY = "progression-history.json"
        const val PROG_STATS = "progression-stats.json"
        const val EAR_HISTORY = "ear-history.json"
        const val EAR_STATS = "ear-stats.json"
        const val CHORD_SETTINGS = "chord-settings.json"
        const val CHORD_HISTORY = "chord-history.json"
        const val SETTINGS = "settings.json"
        const val HISTORY = "history.json"
        const val PLAYER_SETTINGS = "player-settings.json"
        const val PLAYER_HISTORY = "player-history.json"
        const val LIBRARY = "library.json"
        const val RECORDINGS = "recordings.json"
    }
}
