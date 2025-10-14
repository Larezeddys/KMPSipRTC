package com.eddyslarez.kmpsiprtc.core

import kotlinx.coroutines.*
import java.net.InetAddress


actual fun createNetworkManager(): NetworkManager = DesktopNetworkManager()

class DesktopNetworkManager() : NetworkManager {

    private val TAG = "NetworkManager"
    private val CHECK_INTERVAL = 5000L


    private var isNetworkAvailable = false
    private var connectivityListener: NetworkConnectivityListener? = null
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    override fun initialize() {
        try {
            checkInitialNetworkState()
            startNetworkMonitoring()
            println("$TAG: NetworkManager initialized successfully on Desktop")
        } catch (e: Exception) {
            println("$TAG: Error initializing NetworkManager: ${e.message}")
        }
    }

    private fun startNetworkMonitoring() {
        monitorJob = networkScope.launch {
            while (isActive) {
                try {
                    val wasAvailable = isNetworkAvailable
                    val isConnected = checkNetworkConnectivity()

                    if (isConnected != wasAvailable) {
                        isNetworkAvailable = isConnected

                        if (isConnected) {
                            connectivityListener?.onNetworkRestored()
                        } else {
                            connectivityListener?.onNetworkLost()
                        }
                    }
                } catch (e: Exception) {
                    println("$TAG: Error monitoring network: ${e.message}")
                }

                delay(CHECK_INTERVAL)
            }
        }
    }

    private fun checkNetworkConnectivity(): Boolean {
        return try {
            val address = InetAddress.getByName("www.google.com")
            !address.equals("")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkInitialNetworkState() {
        isNetworkAvailable = checkNetworkConnectivity()
    }

    override fun isNetworkAvailable(): Boolean = isNetworkAvailable

    override fun setConnectivityListener(listener: NetworkConnectivityListener?) {
        this.connectivityListener = listener
    }

    override fun forceNetworkCheck() {
        networkScope.launch {
            val isConnected = checkNetworkConnectivity()
            if (isConnected != isNetworkAvailable) {
                isNetworkAvailable = isConnected
                if (isConnected) {
                    connectivityListener?.onNetworkRestored()
                } else {
                    connectivityListener?.onNetworkLost()
                }
            }
        }
    }

    override fun getNetworkInfo(): Map<String, Any> {
        return mapOf(
            "isAvailable" to isNetworkAvailable,
            "platform" to "Desktop"
        )
    }

    override fun dispose() {
        try {
            monitorJob?.cancel()
            networkScope.cancel()
            connectivityListener = null
            println("$TAG: NetworkManager disposed")
        } catch (e: Exception) {
            println("$TAG: Error disposing NetworkManager: ${e.message}")
        }
    }
}

