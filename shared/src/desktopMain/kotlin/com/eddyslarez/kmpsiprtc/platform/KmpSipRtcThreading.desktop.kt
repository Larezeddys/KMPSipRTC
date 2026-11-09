package com.eddyslarez.kmpsiprtc.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun platformDefaultSipDispatcher(): CoroutineDispatcher = Dispatchers.Default

internal actual fun platformSharesNetworkManager(): Boolean = true
