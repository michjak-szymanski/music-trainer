package com.musictrainer

import com.musictrainer.data.FileStore
import com.musictrainer.data.Repository
import com.musictrainer.library.PieceMeta
import com.musictrainer.notation.MidiParser
import com.musictrainer.player.HandsMode
import com.musictrainer.player.PlayStyle
import com.musictrainer.player.PlayerRecord
import com.musictrainer.player.RecordedEvent
import com.musictrainer.player.RecordingInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerTest {
    private class MemoryStore : FileStore {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, content: String) { files[name] = content }
    }

    private val store = MemoryStore().also { s ->
        val repo = Repository(s)
        repo.saveLibraryCache(listOf(PieceMeta("C:/music/fur_elise.mid", "Für Elise", "fur_elise.mid", 32_000, 104, 1)))
        repo.savePlayerHistory(listOf(PlayerRecord(1_700_000_000_000, "C:/music/fur_elise.mid", "Für Elise", 90, 4, 6, 100, true, 100, 120, PlayStyle.WAIT, HandsMode.BOTH, 60_000, rhythmErrors = 3)))
        val rec = RecordingInfo(1_700_000_100_000, "C:/music/fur_elise.mid", "Für Elise", 1000, 2, HandsMode.RIGHT, 100)
        repo.saveRecordings(listOf(rec))
        repo.saveRecordingEvents(rec, listOf(RecordedEvent(0, 76, true), RecordedEvent(400, 76, false), RecordedEvent(500, 75, true), RecordedEvent(1000, 75, false)))
    }
    private val port = ServerSocket(0).use { it.localPort }
    private val server = McpServer(store, "test").also { it.apply(true, port) }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() = server.stop()

    private fun post(body: String, origin: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(server.url(port))).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body))
        if (origin != null) b.header("Origin", origin)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun call(tool: String, args: String = "{}"): String {
        val r = post("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""")
        assertEquals(200, r.statusCode())
        val result = Json.parseToJsonElement(r.body()).jsonObject["result"]!!.jsonObject
        return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test
    fun handshakeAndTools() {
        val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals(200, init.statusCode())
        val res = Json.parseToJsonElement(init.body()).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", res["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("musigium", res["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())

        val tools = Json.parseToJsonElement(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").body())
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("list_pieces", "get_session_history", "list_recordings", "get_recording"), tools)
    }

    @Test
    fun toolsReturnTheAppData() {
        val pieces = Json.parseToJsonElement(call("list_pieces", """{"query":"elise"}""")).jsonArray
        assertEquals(1, pieces.size)
        assertEquals("Für Elise", pieces[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("1", pieces[0].jsonObject["sessions"]!!.jsonPrimitive.content)

        val history = Json.parseToJsonElement(call("get_session_history", """{"mode":"player"}""")).jsonObject
        val s = history["notePlayer"]!!.jsonArray[0].jsonObject
        assertEquals("3", s["rhythmErrors"]!!.jsonPrimitive.content)
        assertTrue("noteFinder" !in history)

        val recs = Json.parseToJsonElement(call("list_recordings")).jsonArray
        val id = recs[0].jsonObject["id"]!!.jsonPrimitive.content
        val events = Json.parseToJsonElement(call("get_recording", """{"id":$id}""")).jsonObject["events"]!!.jsonArray
        assertEquals(4, events.size)

        val midi = Json.parseToJsonElement(call("get_recording", """{"id":$id,"format":"midi"}""")).jsonObject
        val parsed = MidiParser.parse(Base64.getDecoder().decode(midi["midiBase64"]!!.jsonPrimitive.content))
        assertEquals(listOf(76, 75), parsed.notes.map { it.pitch })
    }

    @Test
    fun lanModeListensOnNetworkAddresses() {
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
        assertTrue(server.lanUrls(port).isEmpty(), "loopback-only by default")
        server.apply(true, port, lan = true)
        assertTrue(server.status.value.contains("local network"), server.status.value)
        assertEquals(200, post(init).statusCode(), "still reachable from this computer")
        val lan = server.lanUrls(port)
        println("LAN urls: $lan")
        lan.firstOrNull()?.let { url ->
            val r = http.send(
                HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(init)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, r.statusCode(), "reachable via $url")
        }
        server.apply(true, port, lan = false)
        assertTrue(server.lanUrls(port).isEmpty())
    }

    @Test
    fun rejectsWebPagesAndOtherMethods() {
        assertEquals(403, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "https://evil.example").statusCode())
        assertEquals(200, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "http://localhost:3000").statusCode())
        val get = http.send(HttpRequest.newBuilder(URI(server.url(port))).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(405, get.statusCode())
        val unknown = Json.parseToJsonElement(post("""{"jsonrpc":"2.0","id":3,"method":"nope"}""").body()).jsonObject
        assertTrue((unknown["error"] as JsonObject)["code"]!!.jsonPrimitive.content == "-32601")
    }
}
