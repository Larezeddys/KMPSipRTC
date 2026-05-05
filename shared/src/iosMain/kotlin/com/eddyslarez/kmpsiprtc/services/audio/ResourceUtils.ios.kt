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

        // 1. Buscar en compose-resources (flat)
        val resourcePath = "$bundlePath/compose-resources"
        val flatFile = "$resourcePath/$filename"
        if (fileManager.fileExistsAtPath(flatFile)) {
            println("Audio file found: $flatFile")
            return flatFile
        }

        // 2. Buscar recursivamente en compose-resources (subdirectorios de modulos)
        // Compose Resources iOS usa: compose-resources/<package>.generated.resources/files/<filename>
        if (fileManager.fileExistsAtPath(resourcePath)) {
            val found = findFileRecursively(resourcePath, filename, fileManager)
            if (found != null) {
                println("Audio file found in compose-resources subdirectory: $found")
                return found
            }
        }

        // 3. Buscar en el bundle principal
        val mainBundleFile = "$bundlePath/$filename"
        if (fileManager.fileExistsAtPath(mainBundleFile)) {
            println("Audio file found in main bundle: $mainBundleFile")
            return mainBundleFile
        }

        // 4. Buscar en Frameworks (recursos de iosMain/resources/ del framework)
        val frameworksPath = "$bundlePath/Frameworks"
        if (fileManager.fileExistsAtPath(frameworksPath)) {
            val found = findFileRecursively(frameworksPath, filename, fileManager)
            if (found != null) {
                println("Audio file found in framework: $found")
                return found
            }
        }

        println("Audio file not found: $filename")
        return null
    }

    private fun findFileRecursively(basePath: String, filename: String, fileManager: NSFileManager): String? {
        val enumerator = fileManager.enumeratorAtPath(basePath) ?: return null
        while (true) {
            val relativePath = enumerator.nextObject() as? String ?: break
            if (relativePath.endsWith("/$filename") || relativePath == filename) {
                val fullPath = "$basePath/$relativePath"
                if (fileManager.fileExistsAtPath(fullPath)) {
                    return fullPath
                }
            }
        }
        return null
    }
}
