package com.eddyslarez.kmpsiprtc.core


import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.Network.*
import platform.darwin.dispatch_queue_create

@OptIn(ExperimentalForeignApi::class)
actual fun createNetworkManager(): NetworkManager = IOSNetworkManager()

@OptIn(ExperimentalForeignApi::class)
class IOSNetworkManager() : NetworkManager {

    private val TAG = "NetworkManager"

    private var pathMonitor: nw_path_monitor_t? = null
    private var isNetworkAvailable = true // optimistic default: assume connected until NWPathMonitor fires
    private var connectivityListener: NetworkConnectivityListener? = null
    private val networkScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Tracks interface type combo to detect VPN on/off and WiFi↔Cellular switches
    private var lastInterfaceSignature: String = ""

    @OptIn(ExperimentalForeignApi::class)
    private val monitorQueue = dispatch_queue_create("NetworkMonitorQueue", null)

    override fun initialize() {
        if (pathMonitor != null) {
            println("$TAG: NetworkManager already initialized, skipping duplicate init")
            return
        }
        try {
            pathMonitor = nw_path_monitor_create()

            nw_path_monitor_set_update_handler(pathMonitor) { path ->
                val status = nw_path_get_status(path)
                val isConnected =
                    status == nw_path_status_satisfied || status == nw_path_status_satisfiable

                // Build interface signature: wifi/cellular/other(VPN tunnel) combo
                // nw_interface_type_other covers VPN tunnels, hotspot, etc.
                val usesWifi = nw_path_uses_interface_type(path, nw_interface_type_wifi)
                val usesCellular = nw_path_uses_interface_type(path, nw_interface_type_cellular)
                val usesOther = nw_path_uses_interface_type(path, nw_interface_type_other)
                val interfaceSignature = "w=$usesWifi,c=$usesCellular,o=$usesOther"

                val previouslyAvailable = isNetworkAvailable
                val previousSignature = lastInterfaceSignature
                // Detect interface change while connected (VPN on/off, WiFi→Cellular, etc.)
                val interfaceChanged = isConnected && previouslyAvailable &&
                    previousSignature.isNotEmpty() && interfaceSignature != previousSignature

                isNetworkAvailable = isConnected
                lastInterfaceSignature = if (isConnected) interfaceSignature else ""

                networkScope.launch {
                    when {
                        !isConnected && previouslyAvailable -> {
                            println("$TAG: Network lost")
                            connectivityListener?.onNetworkLost()
                        }
                        isConnected && !previouslyAvailable -> {
                            println("$TAG: Network restored: $interfaceSignature")
                            connectivityListener?.onNetworkRestored()
                        }
                        interfaceChanged -> {
                            // IP likely changed (VPN activated/deactivated, switched network)
                            // Re-register SIP with new source IP
                            println("$TAG: Network interface changed ($previousSignature→$interfaceSignature), triggering re-registration")
                            connectivityListener?.onNetworkRestored()
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
