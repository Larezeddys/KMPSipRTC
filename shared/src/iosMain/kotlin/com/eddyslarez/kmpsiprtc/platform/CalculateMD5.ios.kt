package com.eddyslarez.kmpsiprtc.platform


import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import kotlin.collections.joinToString


@OptIn(ExperimentalForeignApi::class)
actual fun calculateMD5(input: String): String {
    val data = input.encodeToByteArray()
    val digest = UByteArray(CC_MD5_DIGEST_LENGTH)

    data.toUByteArray().usePinned { pinned ->
        digest.usePinned { digestPinned ->
            CC_MD5(pinned.addressOf(0), data.size.toUInt(), digestPinned.addressOf(0))
        }
    }

    return digest.joinToString("") { byte ->
        val hex = byte.toInt().toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}