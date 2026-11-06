package com.musictrainer

import com.musictrainer.data.FileStore
import com.musictrainer.data.Repository
import com.musictrainer.mcp.McpControl
import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiWriter
import com.musictrainer.notation.NoteSpec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Read-only MCP server (Streamable HTTP transport, JSON responses) on 127.0.0.1 (optionally on the LAN), exposing the library,
 * session history and recordings to MCP clients such as Claude. Reads the app's data files on every call.
 */
class McpServer(private val store: FileStore, private val version: String) : McpControl {
    private val _status = MutableStateFlow("Off")
    override val status: StateFlow<String> = _status
    private var server: HttpServer? = null
    private var port = 0
    private var lan = false
    private val json = Json { prettyPrint = false }

    @Synchronized
    override fun apply(enabled: Boolean, port: Int, lan: Boolean) {
        if (!enabled) { stop(); _status.value = "Off"; return }
        if (server != null && this.port == port && this.lan == lan) return
        stop()
        try {
            val address = if (lan) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port)
            server = HttpServer.create(address, 0).apply {
                createContext("/mcp") { handle(it) }
                executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mcp-server").apply { isDaemon = true } }
                start()
            }
            this.port = port
            this.lan = lan
            _status.value = "Running at ${url(port)}" + if (lan) " and on the local network" else ""
        } catch (e: Exception) {
            server = null
            _status.value = "Couldn't start on port $port: ${e.message}"
        }
    }

    override fun url(port: Int) = "http://127.0.0.1:$port/mcp"

    override fun lanUrls(port: Int): List<String> {
        if (!lan || server == null) return emptyList()
        return runCatching {
            java.net.NetworkInterface.networkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses().toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { "http://${it.hostAddress}:$port/mcp" }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** Only this computer and private (home/office network) addresses may connect, never the internet. */
    private fun allowedPeer(a: InetAddress?): Boolean =
        a != null && (a.isLoopbackAddress || (lan && (a.isSiteLocalAddress || a.isLinkLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC))))

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    // ---------------------------------------------------------------- HTTP

    private fun handle(ex: HttpExchange) {
        try {
            if (!allowedPeer(ex.remoteAddress?.address)) { respond(ex, 403, """{"error":"forbidden address"}"""); return }
            // Browsers send Origin; only accept local pages so websites can't read the data (DNS rebinding).
            val origin = ex.requestHeaders.getFirst("Origin")
            if (origin != null && runCatching { URI(origin).host }.getOrNull() !in setOf("localhost", "127.0.0.1", "[::1]", "::1")) {
                respond(ex, 403, """{"error":"forbidden origin"}"""); return
            }
            if (ex.requestMethod != "POST") {
                ex.responseHeaders.add("Allow", "POST")
                respond(ex, 405, ""); return
            }
            val body = ex.requestBody.readBytes().decodeToString()
            val request = runCatching { json.parseToJsonElement(body) }.getOrElse {
                respond(ex, 400, error(JsonNull, -32700, "Parse error").toString()); return
            }
            val responses = when (request) {
                is JsonArray -> request.mapNotNull { rpc(it) }.let { if (it.isEmpty()) null else JsonArray(it) }
                else -> rpc(request)
            }
            if (responses == null) respond(ex, 202, "") else respond(ex, 200, responses.toString())
        } catch (e: Exception) {
            runCatching { respond(ex, 500, error(JsonNull, -32603, e.message ?: "error").toString()) }
        }
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        if (bytes.isNotEmpty()) ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    // ---------------------------------------------------------------- JSON-RPC

    /** Handles one JSON-RPC message; null for notifications. */
    private fun rpc(msg: JsonElement): JsonElement? {
        val o = msg as? JsonObject ?: return error(JsonNull, -32600, "Invalid request")
        val id = o["id"] ?: return null // notification (e.g. notifications/initialized)
        val method = o["method"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32600, "Invalid request")
        val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-06-18")
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "music-trainer"); put("version", version) }
                put("instructions", "Read-only access to the Music Trainer app: its piece library, practice session history (Note player and Note feeder) and recorded performances.")
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", toolList()) })
            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.contentOrNull
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val out = runCatching { callTool(name, args) }
                result(id, buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", out.getOrElse { "Error: ${it.message}" }.toString()) })
                    }
                    if (out.isFailure) put("isError", true)
                })
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun result(id: JsonElement, r: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", r) }
    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) }
    }

    // ---------------------------------------------------------------- tools

    private fun tool(name: String, description: String, props: Map<String, Pair<String, String>>, required: List<String> = emptyList()) = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") { for ((k, v) in props) putJsonObject(k) { put("type", v.first); put("description", v.second) } }
            putJsonArray("required") { required.forEach { add(it) } }
        }
        putJsonObject("annotations") { put("readOnlyHint", true) }
    }

    private fun toolList() = buildJsonArray {
        add(tool("list_pieces", "List the pieces in the Note player library (user's MIDI/MuseScore files and built-in demos) with duration, note count and practice stats.",
            mapOf("query" to ("string" to "Optional text to filter titles and file names"))))
        add(tool("get_session_history", "Practice sessions, newest first. Note player sessions have hits, wrong/missed notes, rhythm and note-length errors, accuracy, score, stars, tempo and hands; Note feeder sessions have correct/wrong, accuracy, score and settings.",
            mapOf(
                "mode" to ("string" to "\"player\", \"feeder\" or \"all\" (default)"),
                "piece" to ("string" to "Optional piece id or title text (Note player only)"),
                "limit" to ("integer" to "Maximum sessions per mode (default 50)"),
            )))
        add(tool("list_recordings", "Recorded performances (what was heard while practising), newest first.",
            mapOf("piece" to ("string" to "Optional piece id or title text"))))
        add(tool("get_recording", "One recording: its key events (ms, MIDI note, on/off), or the whole recording as a base64 Standard MIDI File.",
            mapOf(
                "id" to ("integer" to "Recording id from list_recordings"),
                "format" to ("string" to "\"events\" (default) or \"midi\""),
            ), required = listOf("id")))
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun callTool(name: String?, args: JsonObject): JsonElement {
        val repo = Repository(store)
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val players = repo.loadPlayerHistory()
        return when (name) {
            "list_pieces" -> {
                val q = str("query")?.lowercase()
                val builtIns = DemoPieces.all.map { Triple(it.id, it.title, "Built-in") }
                buildJsonArray {
                    for (p in repo.loadLibraryCache()) {
                        if (q != null && q !in p.title.lowercase() && q !in p.fileName.lowercase()) continue
                        val s = players.filter { it.pieceId == p.id }
                        add(buildJsonObject {
                            put("id", p.id); put("title", p.title); put("file", p.fileName); put("format", p.format)
                            put("durationSeconds", p.durationMs / 1000); put("notes", p.notes)
                            put("sessions", s.size); s.maxOfOrNull { it.score }?.let { put("bestScore", it) }
                            s.maxOfOrNull { it.accuracyPercent }?.let { put("bestAccuracyPercent", it) }
                        })
                    }
                    for ((id, title, _) in builtIns) {
                        if (q != null && q !in title.lowercase()) continue
                        add(buildJsonObject { put("id", id); put("title", title); put("format", "Built-in"); put("sessions", players.count { it.pieceId == id }) })
                    }
                }
            }
            "get_session_history" -> {
                val mode = str("mode") ?: "all"
                val piece = str("piece")?.lowercase()
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
                buildJsonObject {
                    if (mode != "feeder") putJsonArray("notePlayer") {
                        players.filter { piece == null || it.pieceId.lowercase() == piece || piece in it.title.lowercase() }.take(limit).forEach { r ->
                            add(buildJsonObject {
                                put("date", iso(r.timestamp)); put("pieceId", r.pieceId); put("title", r.title)
                                put("hands", r.hands.label); put("style", r.style.label); put("bpm", r.bpm); put("fileBpm", r.fileBpm)
                                put("hits", r.hits); put("wrong", r.wrong); put("missed", r.missed)
                                put("rhythmErrors", r.rhythmErrors); put("lengthErrors", r.lengthErrors)
                                put("accuracyPercent", r.accuracyPercent); put("score", r.score); put("stars", r.stars)
                                put("completed", r.completed); put("durationSeconds", r.durationMs / 1000)
                            })
                        }
                    }
                    if (mode != "player") putJsonArray("noteFeeder") {
                        repo.loadHistory().take(limit).forEach { r ->
                            add(buildJsonObject {
                                put("date", iso(r.timestamp)); put("correct", r.correct); put("wrong", r.wrong)
                                put("accuracyPercent", r.accuracyPercent); put("score", r.score); put("stars", r.stars)
                                put("completed", r.completed); put("plannedNotes", r.plannedNotes)
                                put("lowestMidi", r.lowestMidi); put("highestMidi", r.highestMidi)
                                put("notesPerClef", r.notesPerClef); put("accidentals", r.includeAccidentals)
                                put("avgResponseMs", r.avgResponseMs)
                            })
                        }
                    }
                }
            }
            "list_recordings" -> {
                val piece = str("piece")?.lowercase()
                buildJsonArray {
                    repo.loadRecordings().filter { piece == null || it.pieceId.lowercase() == piece || piece in it.title.lowercase() }.forEach { r ->
                        add(buildJsonObject {
                            put("id", r.id); put("date", iso(r.id)); put("pieceId", r.pieceId); put("title", r.title)
                            put("durationSeconds", r.durationMs / 1000); put("notes", r.notes); put("hands", r.hands.label); put("bpm", r.bpm)
                            r.session?.let { s ->
                                putJsonObject("session") {
                                    put("score", s.score); put("stars", s.stars); put("accuracyPercent", s.accuracyPercent)
                                    put("hits", s.hits); put("wrong", s.wrong); put("missed", s.missed)
                                    put("rhythmErrors", s.rhythmErrors); put("lengthErrors", s.lengthErrors); put("completed", s.completed)
                                }
                            }
                        })
                    }
                }
            }
            "get_recording" -> {
                val id = args["id"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("id is required")
                val info = repo.loadRecordings().firstOrNull { it.id == id } ?: throw IllegalArgumentException("No recording with id $id")
                val events = repo.loadRecordingEvents(info)
                if (str("format") == "midi") {
                    // 120 bpm at 480 ticks per quarter: 1 ms = 0.96 ticks.
                    val open = HashMap<Int, Long>()
                    val notes = ArrayList<NoteSpec>()
                    for (e in events.sortedBy { it.t }) {
                        if (e.on) open[e.midi] = e.t
                        else open.remove(e.midi)?.let { s -> notes += NoteSpec(e.midi, (s * 0.96).toInt(), ((e.t - s) * 0.96).toInt().coerceAtLeast(1)) }
                    }
                    val midi = MidiWriter.write(listOf(notes), bpm = 120, trackNames = listOf(info.title))
                    buildJsonObject { put("title", info.title); put("date", iso(info.id)); put("midiBase64", Base64.getEncoder().encodeToString(midi)) }
                } else buildJsonObject {
                    put("title", info.title); put("date", iso(info.id)); put("durationSeconds", info.durationMs / 1000)
                    putJsonArray("events") { events.forEach { e -> add(buildJsonArray { add(e.t); add(e.midi); add(if (e.on) "on" else "off") }) } }
                }
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }
}
