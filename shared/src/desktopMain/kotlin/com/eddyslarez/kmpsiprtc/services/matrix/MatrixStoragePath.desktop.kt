package com.eddyslarez.kmpsiprtc.services.matrix

import java.io.File

actual fun matrixStoragePath(): String {
    val home = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: "."
    val baseDir = File(home, ".mcn-softphone/matrix")
    if (!baseDir.exists()) baseDir.mkdirs()
    return baseDir.absolutePath
}
