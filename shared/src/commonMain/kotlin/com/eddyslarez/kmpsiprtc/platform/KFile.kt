package com.eddyslarez.kmpsiprtc.platform


expect class KFile(path: String) {
    val path: String
    fun exists(): Boolean
    fun length(): Long
}