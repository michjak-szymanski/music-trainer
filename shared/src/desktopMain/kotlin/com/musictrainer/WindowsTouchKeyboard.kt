package com.musictrainer

import com.musictrainer.ui.TouchKeyboard
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * The Windows touch keyboard. Compose Desktop text fields don't announce themselves to Windows' text
 * input service, so the keyboard never opens by itself; this opens it through ITipInvocation (the same
 * call the taskbar keyboard button makes), starting TabTip.exe first when it isn't running.
 */
internal object WindowsTouchKeyboard : TouchKeyboard {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetSystemMetrics(index: Int): Int
        fun GetDesktopWindow(): Pointer?
    }

    @Suppress("FunctionName")
    private interface Ole32 : Library {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
    }

    private val user32 by lazy { Native.load("user32", User32::class.java) }
    private val ole32 by lazy { Native.load("ole32", Ole32::class.java) }

    private const val SM_MAXIMUMTOUCHES = 95
    private const val SM_CONVERTIBLESLATEMODE = 0x2003
    private const val CLSID_UIHOST_NO_LAUNCH = "4CE576FA-83DC-4F88-951C-9D0782B4E376"
    private const val IID_ITIP_INVOCATION = "37C994E7-432B-4834-A2F7-DCE1F13B834B"
    private const val CLSCTX_LOCAL_SERVER = 4

    override val available: Boolean by lazy { isWindows && runCatching { user32.GetSystemMetrics(SM_MAXIMUMTOUCHES) > 0 }.getOrDefault(false) }

    /** Tablet posture (keyboard folded back or detached): 0 means slate mode. */
    override val autoShow: Boolean get() = available && runCatching { user32.GetSystemMetrics(SM_CONVERTIBLESLATEMODE) == 0 }.getOrDefault(false)

    override fun toggle() {
        if (!isWindows) return
        // COM needs its own initialised thread; this never blocks the UI.
        Thread {
            ole32.CoInitializeEx(null, 2)
            try {
                if (!invoke()) {
                    // Not running yet: starting TabTip shows the keyboard.
                    val tabTip = File(System.getenv("CommonProgramFiles") ?: "C:\\Program Files\\Common Files", "microsoft shared\\ink\\TabTip.exe")
                    if (tabTip.exists()) runCatching { ProcessBuilder("cmd", "/c", "start", "", tabTip.absolutePath).start() }
                }
            } finally {
                ole32.CoUninitialize()
            }
        }.apply { isDaemon = true; name = "touch-keyboard" }.start()
    }

    private fun invoke(): Boolean {
        val out = PointerByReference()
        if (ole32.CoCreateInstance(guid(CLSID_UIHOST_NO_LAUNCH), null, CLSCTX_LOCAL_SERVER, guid(IID_ITIP_INVOCATION), out) != 0) return false
        val tip = out.value ?: return false
        return try {
            call(tip, 3, user32.GetDesktopWindow()) == 0 // ITipInvocation::Toggle(HWND)
        } finally {
            call(tip, 2) // Release
        }
    }

    private fun guid(text: String): Memory {
        val hex = text.replace("-", "")
        val m = Memory(16)
        fun le(offset: Int, value: Long, bytes: Int) { for (i in 0 until bytes) m.setByte((offset + i).toLong(), (value shr (8 * i)).toByte()) }
        le(0, hex.substring(0, 8).toLong(16), 4)
        le(4, hex.substring(8, 12).toLong(16), 2)
        le(6, hex.substring(12, 16).toLong(16), 2)
        for (i in 0 until 8) m.setByte((8 + i).toLong(), hex.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        return m
    }

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf(obj, *args))
    }
}
