package com.eddyslarez.kmpsiprtc.core

import kotlinx.coroutines.Job



interface NetworkManager {
    fun initialize()
    fun isNetworkAvailable(): Boolean
    fun setConnectivityListener(listener: NetworkConnectivityListener?)
    fun forceNetworkCheck()
    fun getNetworkInfo(): Map<String, Any>
    fun dispose()
}

/**
 * Factory function que retorna la implementación nativa de cada plataforma.
 */
expect fun createNetworkManager(): NetworkManager

interface NetworkConnectivityListener {
    fun onNetworkLost(): Job
    fun onNetworkRestored()
    fun onReconnectionStarted()
    fun onReconnectionCompleted(successful: Boolean)
}