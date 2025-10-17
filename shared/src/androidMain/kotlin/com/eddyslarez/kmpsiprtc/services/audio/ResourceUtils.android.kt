package com.eddyslarez.kmpsiprtc.services.audio

import android.content.Context
import com.eddyslarez.kmpsiprtc.platform.AndroidContext
import com.eddyslarez.kmpsiprtc.platform.getAndroidContext

actual fun createResourceUtils(): ResourceUtils {
    val context: Context = AndroidContext.get()
    return AndroidResourceUtils(context)
}


class AndroidResourceUtils(private val context: Context) : ResourceUtils {
    override fun getDefaultIncomingRingtonePath(): String? {
        return "android.resource://${context.packageName}/raw/call"
    }

    override fun getDefaultOutgoingRingtonePath(): String? {
        return "android.resource://${context.packageName}/raw/ringback"
    }
}

