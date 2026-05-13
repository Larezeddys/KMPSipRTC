package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.AndroidContext.getApplication
import java.io.File

actual fun matrixStoragePath(): String {
    val baseDir = File(getApplication().filesDir, "matrix")
    if (!baseDir.exists()) baseDir.mkdirs()
    return baseDir.absolutePath
}
