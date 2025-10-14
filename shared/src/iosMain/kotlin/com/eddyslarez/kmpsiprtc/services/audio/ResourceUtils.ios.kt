package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager


actual fun createResourceUtils(): ResourceUtils = IosResourceUtils()

@OptIn(ExperimentalForeignApi::class)
class IosResourceUtils() : ResourceUtils {
    override fun getDefaultIncomingRingtonePath(): String? {
        return getResourcePath("call.mp3")
    }

    override fun getDefaultOutgoingRingtonePath(): String? {
        return getResourcePath("ringback.mp3")
    }

    private fun getResourcePath(filename: String): String? {
        val bundlePath = NSBundle.mainBundle.bundlePath
        val fileManager = NSFileManager.defaultManager

        // Intentar en compose-resources
        val resourcePath = "$bundlePath/compose-resources"
        val files = fileManager.contentsOfDirectoryAtPath(resourcePath, null)

        if (files?.contains(filename) == true) {
            val filePath = "$resourcePath/$filename"
            println("Audio file found: $filePath")
            return filePath
        }

        // Intentar en el bundle principal
        val mainBundleFile = "$bundlePath/$filename"
        if (fileManager.fileExistsAtPath(mainBundleFile)) {
            println("Audio file found in main bundle: $mainBundleFile")
            return mainBundleFile
        }

        println("Audio file not found: $filename")
        return null
    }
}
