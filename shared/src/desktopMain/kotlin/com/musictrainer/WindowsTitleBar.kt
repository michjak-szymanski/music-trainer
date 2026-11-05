package com.musictrainer

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/** Colors the native Windows title bar to match the app theme (no-op on other OSes). */
internal object WindowsTitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20 // Windows 10 2004+
    private const val DWMWA_CAPTION_COLOR = 35           // Windows 11
    private const val DWMWA_TEXT_COLOR = 36              // Windows 11

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val dwm: Dwm? by lazy {
        if (!isWindows) null else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    fun apply(window: ComposeWindow, dark: Boolean, caption: Color, text: Color) {
        val api = dwm ?: return
        runCatching {
            val hwnd = Pointer(window.windowHandle)
            api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), 4)
            api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(caption.toColorRef()), 4)
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(text.toColorRef()), 4)
        }
    }

    /** Win32 COLORREF is 0x00BBGGRR. */
    private fun Color.toColorRef(): Int {
        val r = (red * 255).toInt()
        val g = (green * 255).toInt()
        val b = (blue * 255).toInt()
        return (b shl 16) or (g shl 8) or r
    }
}
