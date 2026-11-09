package com.eddyslarez.kmpsiprtc.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun platformDefaultSipDispatcher(): CoroutineDispatcher = Dispatchers.Main

internal actual fun platformSharesNetworkManager(): Boolean = false
