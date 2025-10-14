package com.eddyslarez.kmpsiprtc.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.Network.*
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
actual fun createNetworkManager(): NetworkManager = IOSNetworkManager()

@OptIn(ExperimentalForeignApi::class)
class IOSNetworkManager() : NetworkManager {

    private val TAG = "NetworkManager"

    private var pathMonitor: nw_path_monitor_t? = null
    private var isNetworkAvailable = false
    private var connectivityListener: NetworkConnectivityListener? = null
    private val networkScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @OptIn(ExperimentalForeignApi::class)
    private val monitorQueue = dispatch_queue_create("NetworkMonitorQueue", null)

    override fun initialize() {
        try {
            pathMonitor = nw_path_monitor_create()

            nw_path_monitor_set_update_handler(pathMonitor) { path ->
                val status = nw_path_get_status(path)
                val isConnected =
                    status == nw_path_status_satisfied || status == nw_path_status_satisfiable

                if (isConnected != isNetworkAvailable) {
                    isNetworkAvailable = isConnected

                    networkScope.launch {
                        if (isConnected) {
                            connectivityListener?.onNetworkRestored()
                        } else {
                            connectivityListener?.onNetworkLost()
                        }
                    }
                }
            }

            nw_path_monitor_set_queue(pathMonitor, monitorQueue)
            nw_path_monitor_start(pathMonitor)

            println("$TAG: NetworkManager initialized successfully on iOS")
        } catch (e: Exception) {
            println("$TAG: Error initializing NetworkManager: ${e.message}")
        }
    }

    override fun isNetworkAvailable(): Boolean = isNetworkAvailable

    override fun setConnectivityListener(listener: NetworkConnectivityListener?) {
        this.connectivityListener = listener
    }

    override fun forceNetworkCheck() {
        // iOS NWPathMonitor actualizará automáticamente
        println("$TAG: Force network check requested (auto-handled by NWPathMonitor)")
    }

    override fun getNetworkInfo(): Map<String, Any> {
        return mapOf(
            "isAvailable" to isNetworkAvailable,
            "platform" to "iOS"
        )
    }

    override fun dispose() {
        try {
            pathMonitor?.let {
                nw_path_monitor_cancel(it)
            }
            pathMonitor = null
            networkScope.cancel()
            connectivityListener = null
            println("$TAG: NetworkManager disposed")
        } catch (e: Exception) {
            println("$TAG: Error disposing NetworkManager: ${e.message}")
        }
    }
}
