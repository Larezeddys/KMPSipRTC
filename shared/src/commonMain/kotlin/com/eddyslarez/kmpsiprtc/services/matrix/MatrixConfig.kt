package com.eddyslarez.kmpsiprtc.services.matrix

data class MatrixConfig(
    val homeserverUrl: String = "https://matrix.org",
    val deviceDisplayName: String = "KmpSipRtc Client",
    val enableEncryption: Boolean = true,
    val syncTimeout: Long = 30000L,
    val enableVoip: Boolean = true,
    val enableVideo: Boolean = true,
    val enableFileTransfer: Boolean = true,
    val maxFileUploadSize: Long = 100 * 1024 * 1024 // 100MB
)
