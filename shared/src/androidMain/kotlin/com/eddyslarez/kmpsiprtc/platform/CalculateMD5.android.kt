package com.eddyslarez.kmpsiprtc.platform

import java.security.MessageDigest

actual fun calculateMD5(input: String): String {
    val md5 = MessageDigest.getInstance("MD5")
    val digest = md5.digest(input.toByteArray())
    return digest.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}