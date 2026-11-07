package com.musictrainer.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musictrainer.data.Repository
import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiData
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.MuseScoreParser
import com.musictrainer.notation.Score
import com.musictrainer.notation.ScoreBuilder
import com.musictrainer.notation.TICKS_PER_QUARTER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class PieceMeta(
    val id: String,
    val title: String,
    val fileName: String,
    val durationMs: Long,
    val notes: Int,
    val modified: Long,
    val builtIn: Boolean = false,
    /** "MIDI" or "MuseScore". */
    val format: String = FORMAT_MIDI,
    /** Reader version that produced this entry; older entries are re-read on the next scan. */
    val metaVersion: Int = 0,
)

/** Bump when title/duration/note extraction changes so cached library entries get refreshed. */
const val META_VERSION = 6

const val FORMAT_MIDI = "MIDI"
const val FORMAT_MUSESCORE = "MuseScore"

fun formatOf(fileName: String): String =
    if (fileName.substringAfterLast('.', "").lowercase() in MusicFiles.MUSESCORE_EXTENSIONS) FORMAT_MUSESCORE else FORMAT_MIDI

/** Builds the engraved score for a piece from its file bytes; files without a tempo get [defaultBpm]. */
fun buildScore(piece: PieceMeta, bytes: ByteArray, defaultBpm: Int = 120): Score {
    val sc = if (piece.format == FORMAT_MUSESCORE) MuseScoreParser.parse(bytes, piece.fileName.substringBeforeLast('.'))
    else ScoreBuilder.build(MidiParser.parse(bytes), piece.title)
    return if (sc.tempoFromFile || sc.bpm == defaultBpm) sc else Score(sc.title, sc.key, sc.measures, sc.steps, defaultBpm, tempoFromFile = false)
}

/** MuseScore's default titles, which say nothing about the piece. */
internal fun isPlaceholderTitle(t: String): Boolean = t.trim().lowercase() in setOf("untitled score", "untitled", "title", "")

/** Playing time of the file, following its tempo changes. */
fun MidiData.durationMs(): Long {
    val endTick = notes.maxOfOrNull { it.end } ?: 0L
    var ms = 0.0
    var lastTick = 0L
    var usPerQuarter = 500_000
    for ((tick, tempo) in tempos) {
        if (tick >= endTick) break
        ms += (tick - lastTick) * usPerQuarter / 1000.0 / ticksPerQuarter
        lastTick = tick
        usPerQuarter = tempo
    }
    ms += (endTick - lastTick) * usPerQuarter / 1000.0 / ticksPerQuarter
    return ms.toLong()
}

/** Words of instrument / sound names that MIDI tracks often carry instead of a title ("CFX Concert Grand"). */
private val INSTRUMENT_WORDS = listOf(
    "piano", "grand", "concert", "cfx", "steinway", "yamaha", "bösendorfer", "bosendorfer", "kawai", "roland", "korg",
    "rhodes", "wurli", "e.piano", "epiano", "keys", "keyboard", "organ", "harpsichord", "celesta", "clavi", "synth",
    "strings", "ensemble", "pad", "choir", "bass", "guitar", "violin", "cello", "flute", "drum", "percussion", "sound",
    "upright", "bright", "acoustic", "electric", "midi", "channel", "ch.", "right", "left", "rh", "lh", "hand",
)

/** Model codes like "C7", "D274", "SK2" in instrument names. */
private val MODEL_CODE = Regex("""[a-z]{0,3}\d+[a-z]?""")

/** True when a track name is an instrument or sound rather than the piece's title. */
internal fun looksLikeInstrument(name: String): Boolean {
    val words = name.lowercase().split(Regex("""[^\p{L}\p{N}.]+""")).filter { it.length > 1 } // single letters (model names like "Steinway D") say nothing
    if (words.isEmpty()) return true
    // Mostly instrument words (and numbers): "CFX Concert Grand", "Grand Piano 1", "Piano RH".
    val instrumentish = words.count { w -> w.all { it.isDigit() } || MODEL_CODE.matches(w) || INSTRUMENT_WORDS.any { w == it || w.startsWith(it) } }
    return instrumentish * 2 >= words.size + 1 || instrumentish == words.size
}

/** "Minuet_BWV_Anhang__in_G_Major.mid" → "Minuet BWV Anhang in G Major". */
private fun titleFromFileName(fileName: String): String =
    fileName.substringBeforeLast('.').replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

private val GENERIC_NAMES = setOf("", "untitled", "piano", "acoustic grand piano", "grand piano", "right hand", "left hand", "melody")

fun pieceMeta(id: String, fileName: String, modified: Long, bytes: ByteArray, builtIn: Boolean = false, title: String? = null): PieceMeta {
    if (formatOf(fileName) == FORMAT_MUSESCORE) {
        val score = MuseScoreParser.parse(bytes, fileName.substringBeforeLast('.'))
        // MuseScore's placeholder title says nothing: use the file name.
        val scoreTitle = score.title.takeUnless(::isPlaceholderTitle) ?: titleFromFileName(fileName)
        val ticks = score.measures.lastOrNull()?.let { it.start + it.length } ?: 0
        val notes = score.measures.sumOf { m -> m.staves.values.sumOf { list -> list.sumOf { e -> e.notes.count { !it.tieFromPrev } } } }
        val ms = ticks.toLong() * 60_000 / (TICKS_PER_QUARTER.toLong() * score.bpm)
        return PieceMeta(id, title ?: scoreTitle, fileName, ms, notes, modified, builtIn, FORMAT_MUSESCORE, META_VERSION)
    }
    val midi = MidiParser.parse(bytes)
    val trackTitle = midi.trackNames.toSortedMap().values.firstOrNull { name ->
        val n = name.lowercase()
        n !in GENERIC_NAMES && !n.startsWith("track") && !n.startsWith("staff") && !n.startsWith("musicxml") &&
            !Regex("""(part|p|instrument|voice)\s*\d*""").matches(n) && !looksLikeInstrument(n)
    }
    val fallback = titleFromFileName(fileName)
    return PieceMeta(id, title ?: trackTitle ?: fallback, fileName, midi.durationMs(), midi.notes.size, modified, builtIn, FORMAT_MIDI, META_VERSION)
}

/** Built-in demo pieces plus MIDI files found in the user's folders (metadata cached between runs). */
class Library(private val files: MusicFiles, private val repo: Repository, private val scope: CoroutineScope) {
    val builtIns: List<PieceMeta> = DemoPieces.all.map { pieceMeta(it.id, "Built-in", 0, it.bytes, builtIn = true, title = it.title) }
    val pieces = mutableStateListOf<PieceMeta>().apply { addAll(repo.loadLibraryCache()) }
    var scanning by mutableStateOf(false)
        private set
    /** Files that couldn't be read in the last scan: file name to reason. */
    val unreadable = mutableStateListOf<Pair<String, String>>()

    fun scan(folders: List<String>) {
        if (scanning) return
        scanning = true
        scope.launch {
            val cache = pieces.associateBy { it.id }
            val found = ArrayList<PieceMeta>()
            val bad = ArrayList<Pair<String, String>>()
            for (folder in folders) for (f in runCatching { files.list(folder) }.getOrDefault(emptyList())) {
                val cached = cache[f.id]
                if (cached != null && cached.modified == f.modified && cached.metaVersion == META_VERSION) { found += cached; continue }
                val bytes = files.read(f.id)
                when {
                    bytes == null -> bad += f.name to "can't open the file"
                    bytes.isEmpty() -> bad += f.name to "the file is empty (0 bytes)"
                    else -> runCatching { pieceMeta(f.id, f.name, f.modified, bytes) }
                        .onSuccess { found += it }
                        .onFailure { bad += f.name to (it.message ?: "unknown error") }
                }
            }
            found.sortBy { it.title.lowercase() }
            pieces.clear(); pieces.addAll(found.distinctBy { it.id })
            unreadable.clear(); unreadable.addAll(bad)
            repo.saveLibraryCache(pieces.toList())
            scanning = false
        }
    }

    suspend fun bytes(piece: PieceMeta): ByteArray? =
        if (piece.builtIn) DemoPieces.all.firstOrNull { it.id == piece.id }?.bytes else files.read(piece.id)

    fun find(id: String): PieceMeta? = builtIns.firstOrNull { it.id == id } ?: pieces.firstOrNull { it.id == id }
}
