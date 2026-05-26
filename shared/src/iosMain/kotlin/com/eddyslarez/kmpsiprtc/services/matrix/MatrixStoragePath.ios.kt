package com.eddyslarez.kmpsiprtc.services.matrix

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun matrixStoragePath(): String {
    val docDirs = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true
    )
    val docPath = (docDirs.firstOrNull() as? String) ?: "/tmp"
    val matrixPath = "$docPath/matrix"
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(matrixPath)) {
        fm.createDirectoryAtPath(matrixPath, true, null, null)
    }
    return matrixPath
}
