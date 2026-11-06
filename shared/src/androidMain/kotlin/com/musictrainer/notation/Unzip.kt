package com.musictrainer.notation

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

actual fun unzipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) out[entry.name] = zip.readBytes()
        }
    }
    return out
}
