package com.eddyslarez.kmpsiprtc.core

import kotlinx.coroutines.*
import java.net.NetworkInterface


actual fun createNetworkManager(): NetworkManager = DesktopNetworkManager()

/**
 * NetworkManager para Desktop (Windows / macOS / Linux).
 *
 * Conectividad: se evalua por interfaces de red locales (al menos una interfaz
 * activa, no loopback, con IP routable). NO se consulta DNS externo: el chequeo
 * funciona en LANs corporativas sin salida a internet (donde suele vivir el SIP)
 * y no genera trafico.
 *
 * Suspension del SO: ademas del monitoreo de red, se detecta sleep/hibernacion
 * por salto de reloj (si entre dos ticks del loop pasa mucho mas tiempo del
 * intervalo, el proceso estuvo congelado). Al despertar, el socket TCP esta
 * muerto aunque las interfaces reporten "up", asi que se simula un ciclo
 * lost->restored para forzar la reconexion y el re-REGISTER inmediatos.
 */
class DesktopNetworkManager : NetworkManager {

    private val TAG = "NetworkManager"
    private val CHECK_INTERVAL = 5000L

    // Si entre dos ticks pasa mas de este umbral, el SO estuvo suspendido
    private val SUSPEND_THRESHOLD = CHECK_INTERVAL + 25_000L

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
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                delay(CHECK_INTERVAL)
                try {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTick
                    lastTick = now

                    if (elapsed > SUSPEND_THRESHOLD) {
                        // El SO estuvo suspendido/hibernado: el socket esta muerto
                        // aunque la red local reporte disponible. Forzar ciclo
                        // lost -> restored para que toda la cadena de reconexion
                        // (SipReconnectionManager -> re-REGISTER) se dispare ya.
                        println("$TAG: System suspend detected (${elapsed}ms gap) - forcing reconnection cycle")
                        isNetworkAvailable = false
                        connectivityListener?.onNetworkLost()
                        // Pequeña espera a que el SO re-levante las interfaces tras el wake
                        delay(2000L)
                        val connectedAfterWake = checkNetworkConnectivity()
                        isNetworkAvailable = connectedAfterWake
                        if (connectedAfterWake) {
                            connectivityListener?.onNetworkRestored()
                        }
                        continue
                    }

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
            }
        }
    }

    /**
     * Conectividad real local: al menos una interfaz activa, no loopback,
     * con direccion IP no link-local. Funciona sin salida a internet.
     */
    private fun checkNetworkConnectivity(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.any { iface ->
                iface.isUp && !iface.isLoopback && iface.inetAddresses.toList().any { addr ->
                    !addr.isLoopbackAddress && !addr.isLinkLocalAddress
                }
            } ?: false
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
