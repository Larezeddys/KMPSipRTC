package com.eddyslarez.kmpsiprtc.platform

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual class LogFilePersister actual constructor(baseDir: String) {
    private val lock = ReentrantLock()
    private val dir: File = File(baseDir).apply { if (!exists()) mkdirs() }
    private val currentFile: File get() = File(dir, LOG_FILE_CURRENT_NAME)

    actual fun write(level: String, tag: String, message: String) {
        lock.withLock {
            try {
                rotateIfNeeded()
                val line = "${Instant.now()} [$level/$tag] $message\n"
                currentFile.appendText(line)
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
        }
    }

    actual fun getLogFiles(): List<String> = lock.withLock {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("sip_log") && it.name.endsWith(".txt") }
            ?.sortedBy { it.lastModified() }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    actual fun clearLogs() {
        lock.withLock {
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("sip_log") }
                ?.forEach { it.delete() }
        }
    }

    private fun rotateIfNeeded() {
        if (!currentFile.exists()) return
        if (currentFile.length() < LOG_FILE_MAX_SIZE_BYTES) return
        val rotated = File(
            dir,
            "$LOG_FILE_ROTATED_PREFIX${System.currentTimeMillis()}$LOG_FILE_ROTATED_SUFFIX"
        )
        if (!currentFile.renameTo(rotated)) {
            try {
                rotated.writeBytes(currentFile.readBytes())
                currentFile.delete()
            } catch (_: IOException) {
                return
            }
        }
        pruneOldRotations()
    }

    private fun pruneOldRotations() {
        val rotated = dir.listFiles()
            ?.filter {
                it.isFile &&
                        it.name.startsWith(LOG_FILE_ROTATED_PREFIX) &&
                        it.name.endsWith(LOG_FILE_ROTATED_SUFFIX) &&
                        it.name != LOG_FILE_CURRENT_NAME
            }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (rotated.size > LOG_FILE_MAX_ROTATED) {
            rotated.drop(LOG_FILE_MAX_ROTATED).forEach { it.delete() }
        }
    }
}
