package com.eddyslarez.kmpsiprtc.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData

@OptIn(ExperimentalForeignApi::class)
actual class LogFilePersister actual constructor(private val baseDir: String) {
    private val lock = NSLock()
    private val fm = NSFileManager.defaultManager
    private val isoFormatter = NSISO8601DateFormatter().apply {
        formatOptions = NSISO8601DateFormatWithInternetDateTime or
                NSISO8601DateFormatWithFractionalSeconds
    }

    init {
        if (!fm.fileExistsAtPath(baseDir)) {
            fm.createDirectoryAtPath(
                baseDir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
    }

    private val currentPath: String
        get() = (baseDir as NSString).stringByAppendingPathComponent(LOG_FILE_CURRENT_NAME)

    actual fun write(level: String, tag: String, message: String) {
        lock.lock()
        try {
            rotateIfNeeded()
            val timestamp = isoFormatter.stringFromDate(NSDate())
            val line = "$timestamp [$level/$tag] $message\n"
            val data = (line as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
            if (!fm.fileExistsAtPath(currentPath)) {
                fm.createFileAtPath(currentPath, contents = data, attributes = null)
            } else {
                val handle = NSFileHandle.fileHandleForWritingAtPath(currentPath) ?: return
                handle.seekToEndOfFile()
                handle.writeData(data)
                handle.closeFile()
            }
        } catch (_: Throwable) {
        } finally {
            lock.unlock()
        }
    }

    actual fun getLogFiles(): List<String> {
        lock.lock()
        try {
            val items = fm.contentsOfDirectoryAtPath(baseDir, error = null) ?: return emptyList()
            return items
                .filterIsInstance<String>()
                .filter { it.startsWith("sip_log") && it.endsWith(".txt") }
                .sorted()
                .map { (baseDir as NSString).stringByAppendingPathComponent(it) }
        } finally {
            lock.unlock()
        }
    }

    actual fun clearLogs() {
        lock.lock()
        try {
            val items = fm.contentsOfDirectoryAtPath(baseDir, error = null) ?: return
            items.filterIsInstance<String>()
                .filter { it.startsWith("sip_log") }
                .forEach {
                    val p = (baseDir as NSString).stringByAppendingPathComponent(it)
                    fm.removeItemAtPath(p, error = null)
                }
        } finally {
            lock.unlock()
        }
    }

    private fun rotateIfNeeded() {
        if (!fm.fileExistsAtPath(currentPath)) return
        val attrs = fm.attributesOfItemAtPath(currentPath, error = null) ?: return
        val sizeNum = attrs[NSFileSize] as? NSNumber ?: return
        if (sizeNum.longLongValue < LOG_FILE_MAX_SIZE_BYTES) return
        val epochMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
        val rotatedName = "$LOG_FILE_ROTATED_PREFIX${epochMs}$LOG_FILE_ROTATED_SUFFIX"
        val rotatedPath = (baseDir as NSString).stringByAppendingPathComponent(rotatedName)
        fm.moveItemAtPath(currentPath, toPath = rotatedPath, error = null)
        pruneOldRotations()
    }

    private fun pruneOldRotations() {
        val items = fm.contentsOfDirectoryAtPath(baseDir, error = null) ?: return
        val files = items.filterIsInstance<String>()
            .filter {
                it.startsWith(LOG_FILE_ROTATED_PREFIX) &&
                        it.endsWith(LOG_FILE_ROTATED_SUFFIX) &&
                        it != LOG_FILE_CURRENT_NAME
            }
            .sortedDescending()
        if (files.size > LOG_FILE_MAX_ROTATED) {
            files.drop(LOG_FILE_MAX_ROTATED).forEach {
                val p = (baseDir as NSString).stringByAppendingPathComponent(it)
                fm.removeItemAtPath(p, error = null)
            }
        }
    }
}
