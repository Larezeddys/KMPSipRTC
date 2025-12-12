package com.eddyslarez.kmpsiprtc.platform


import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.*

actual class KFile actual constructor(actual val path: String) {

    actual fun exists(): Boolean {
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun length(): Long {
        val fileManager = NSFileManager.defaultManager
        val attributes = fileManager.attributesOfItemAtPath(path, null)
        return attributes?.get(NSFileSize)?.toString()?.toLong() ?: 0L
    }
}
