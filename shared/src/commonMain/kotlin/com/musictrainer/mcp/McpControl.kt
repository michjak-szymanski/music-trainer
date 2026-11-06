package com.musictrainer.mcp

import kotlinx.coroutines.flow.StateFlow

/** The app's built-in MCP server (desktop only), switched on/off from Settings. */
interface McpControl {
    /** "Off", "Running at …" or an error. */
    val status: StateFlow<String>
    /** [lan]: also accept connections from other devices on the local network. */
    fun apply(enabled: Boolean, port: Int, lan: Boolean = false)
    fun url(port: Int): String
    /** Addresses other devices on the local network can use (empty when not exposed on the LAN). */
    fun lanUrls(port: Int): List<String> = emptyList()
}
