package com.musictrainer

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Folder chooser that looks native: the Windows Explorer dialog (IFileOpenDialog with FOS_PICKFOLDERS,
 * follows the system theme), zenity/kdialog on Linux, and a system-styled Swing chooser as fallback.
 * Blocks the calling thread until the user is done.
 */
internal object NativeFolderPicker {
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    fun pick(title: String): String? {
        if (os.startsWith("windows")) runCatching { return windowsPick(title) }
        if (os.contains("linux")) linuxPick(title)?.let { return it.ifBlank { null } }
        return swingPick(title)
    }

    // ---------------- Windows: IFileOpenDialog through raw COM calls ----------------

    @Suppress("FunctionName")
    private interface Ole32 : Library {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
        fun CoTaskMemFree(p: Pointer?)
    }

    private val ole32: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }

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

    /** Calls method [index] of the COM interface [obj]. */
    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = obj.getPointer(0)
        val fn = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf(obj, *args))
    }

    private const val CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7"
    private const val IID_IFILE_OPEN_DIALOG = "D57C7288-D4AD-4768-BE02-9D969532D960"
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    // IFileDialog vtable slots (after IUnknown 0–2 and IModalWindow::Show 3).
    private const val SHOW = 3
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    private const val RELEASE = 2
    private const val ITEM_GET_DISPLAY_NAME = 5

    private fun windowsPick(title: String): String? {
        var result: String? = null
        var failure: Throwable? = null
        // COM dialogs need a single-threaded apartment: run on a fresh thread.
        val t = Thread {
            try {
                ole32.CoInitializeEx(null, 0x2 or 0x4) // APARTMENTTHREADED | DISABLE_OLE1DDE
                try {
                    val out = PointerByReference()
                    check(ole32.CoCreateInstance(guid(CLSID_FILE_OPEN_DIALOG), null, 1, guid(IID_IFILE_OPEN_DIALOG), out) == 0) { "CoCreateInstance failed" }
                    val dialog = out.value
                    try {
                        val opts = IntByReference()
                        call(dialog, GET_OPTIONS, opts)
                        call(dialog, SET_OPTIONS, opts.value or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM)
                        call(dialog, SET_TITLE, WString(title))
                        val owner = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>().firstOrNull { it.isVisible }
                            ?.let { Pointer(it.windowHandle) }
                        if (call(dialog, SHOW, owner) == 0) { // non-zero = cancelled
                            val item = PointerByReference()
                            if (call(dialog, GET_RESULT, item) == 0) {
                                val name = PointerByReference()
                                if (call(item.value, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name) == 0) {
                                    result = name.value.getWideString(0)
                                    ole32.CoTaskMemFree(name.value)
                                }
                                call(item.value, RELEASE)
                            }
                        }
                    } finally {
                        call(dialog, RELEASE)
                    }
                } finally {
                    ole32.CoUninitialize()
                }
            } catch (e: Throwable) {
                failure = e
            }
        }
        t.start()
        t.join()
        failure?.let { throw it }
        return result
    }

    // ---------------- Linux: desktop's own dialog when available ----------------

    /** Path, "" if the user cancelled, or null if no native picker is installed. */
    private fun linuxPick(title: String): String? {
        val commands = listOf(
            listOf("zenity", "--file-selection", "--directory", "--title=$title"),
            listOf("kdialog", "--getexistingdirectory", System.getProperty("user.home"), "--title", title),
        )
        for (cmd in commands) {
            val process = runCatching { ProcessBuilder(cmd).redirectErrorStream(false).start() }.getOrNull() ?: continue
            val out = process.inputStream.bufferedReader().readText().trim()
            return if (process.waitFor() == 0) out else ""
        }
        return null
    }

    // ---------------- Fallback ----------------

    private fun swingPick(title: String): String? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
}
