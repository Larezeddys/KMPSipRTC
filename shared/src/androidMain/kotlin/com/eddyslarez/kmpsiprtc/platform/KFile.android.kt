package com.eddyslarez.kmpsiprtc.platform

import java.io.File


actual class KFile actual constructor(actual val path: String) {
    private val file = File(path)

    actual fun exists(): Boolean = file.exists()
    actual fun length(): Long = file.length()
}
