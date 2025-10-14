package com.eddyslarez.kmpsiprtc.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.RequiresPermission
import com.eddyslarez.kmpsiprtc.platform.AndroidContext

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min


actual fun createNetworkManager(): NetworkManager =AndroidNetworkManager()

class AndroidNetworkManager() : NetworkManager {
    private val context: Context = AndroidContext.get()


    companion object {
        private const val TAG = "AndroidNetworkManager"
        private const val INTERNET_CHECK_TIMEOUT = 3000
        private const val DEBOUNCE_DELAY = 1500L
        private const val MAX_VALIDATION_RETRIES = 3
        private const val PRIMARY_NETWORK_VALIDATION_DELAY = 1000L
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = false
    private var lastNetworkChangeTime = 0L
    private var pendingValidationJob: Job? = null
    private var primaryNetwork: Network? = null
    private var primaryNetworkCapabilities: NetworkCapabilities? = null
    private var networkValidationRetries = 0
    private var connectivityListener: NetworkConnectivityListener? = null
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    override fun initialize() {
        try {
            connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            setupNetworkCallback()
            checkInitialNetworkState()
            println("$TAG: Initialized successfully")
        } catch (e: Exception) {
            println("$TAG: Initialization error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                println("$TAG: Network available: $network")
                networkScope.launch {
                    delay(PRIMARY_NETWORK_VALIDATION_DELAY)
                    evaluatePrimaryNetwork(network, "onAvailable")
                }
            }

            override fun onLost(network: Network) {
                println("$TAG: Network lost: $network")
                if (primaryNetwork == network) {
                    println("$TAG: PRIMARY network lost")
                    primaryNetwork = null
                    primaryNetworkCapabilities = null
                    handleNetworkUnavailable()
                } else {
                    println("$TAG: Secondary network lost (ignored)")
                    networkScope.launch {
                        delay(500)
                        evaluateCurrentPrimaryNetwork()
                    }
                }
            }

            override fun onUnavailable() {
                println("$TAG: No available network")
                if (primaryNetwork == null) handleNetworkUnavailable()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                println("$TAG: Capabilities changed: $capabilities")
                networkScope.launch {
                    evaluatePrimaryNetwork(network, "onCapabilitiesChanged")
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun evaluatePrimaryNetwork(network: Network, source: String) {
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        val shouldBePrimary =
            activeNetwork == network ||
                    (activeNetwork == null && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

        if (shouldBePrimary && network != primaryNetwork) {
            println("$TAG: Setting new primary network from $source")
            pendingValidationJob?.cancel()
            primaryNetwork = network
            primaryNetworkCapabilities = capabilities
            validatePrimaryNetworkWithStabilization()
        } else if (!shouldBePrimary && network == primaryNetwork) {
            println("$TAG: Current primary network lost, re-evaluating")
            evaluateCurrentPrimaryNetwork()
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun evaluateCurrentPrimaryNetwork() {
        val activeNetwork = connectivityManager?.activeNetwork
        if (activeNetwork != null && activeNetwork != primaryNetwork) {
            println("$TAG: Active network changed, updating primary")
            evaluatePrimaryNetwork(activeNetwork, "systemActiveChange")
        } else if (activeNetwork == null && primaryNetwork != null) {
            println("$TAG: No active network available")
            primaryNetwork = null
            primaryNetworkCapabilities = null
            handleNetworkUnavailable()
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun validatePrimaryNetworkWithStabilization() {
        delay(DEBOUNCE_DELAY)
        val currentPrimary = primaryNetwork ?: return handleNetworkUnavailable()

        val activeNetwork = connectivityManager?.activeNetwork
        if (activeNetwork != currentPrimary) return evaluateCurrentPrimaryNetwork()

        val capabilities = connectivityManager?.getNetworkCapabilities(currentPrimary)
        val hasInternet =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        val validated =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false

        when {
            hasInternet && validated -> {
                networkValidationRetries = 0
                primaryNetworkCapabilities = capabilities
                handleNetworkAvailable()
            }

            hasInternet && !validated -> {
                validateInternetAccessOnNetwork(currentPrimary) { ok ->
                    if (ok) handleNetworkAvailable() else handleValidationFailure()
                }
            }

            else -> handleValidationFailure()
        }
    }

    private fun validateInternetAccessOnNetwork(network: Network, callback: (Boolean) -> Unit) {
        networkScope.launch {
            try {
                val url = URL("https://clients3.google.com/generate_204")
                val connection = network.openConnection(url) as HttpURLConnection
                connection.connectTimeout = INTERNET_CHECK_TIMEOUT
                connection.readTimeout = INTERNET_CHECK_TIMEOUT
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connect()
                val ok = connection.responseCode == 204
                connection.disconnect()
                withContext(Dispatchers.Main) { callback(ok) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun handleValidationFailure() {
        networkValidationRetries++
        if (networkValidationRetries < MAX_VALIDATION_RETRIES) {
            val delayMs = min(2000L * (1 shl networkValidationRetries), 10000L)
            pendingValidationJob = networkScope.launch {
                delay(delayMs)
                validatePrimaryNetworkWithStabilization()
            }
        } else {
            networkValidationRetries = 0
            handleNetworkUnavailable()
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun checkInitialNetworkState() {
        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            val hasInternet =
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            val validated =
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false

            isNetworkAvailable = hasInternet && validated
            if (activeNetwork != null) {
                primaryNetwork = activeNetwork
                primaryNetworkCapabilities = capabilities
            } else {
                primaryNetwork = null
                primaryNetworkCapabilities = null
            }
        } catch (e: Exception) {
            println("$TAG: Initial network check error: ${e.message}")
        }
    }

    private fun handleNetworkAvailable() {
        val wasUnavailable = !isNetworkAvailable
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < DEBOUNCE_DELAY) return

        isNetworkAvailable = true
        lastNetworkChangeTime = now
        if (wasUnavailable) connectivityListener?.onNetworkRestored()
    }

    private fun handleNetworkUnavailable() {
        val wasAvailable = isNetworkAvailable
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < DEBOUNCE_DELAY) return

        isNetworkAvailable = false
        lastNetworkChangeTime = now
        pendingValidationJob?.cancel()
        networkValidationRetries = 0

        if (wasAvailable) connectivityListener?.onNetworkLost()?.start()
    }

    override fun isNetworkAvailable(): Boolean = isNetworkAvailable

    override fun setConnectivityListener(listener: NetworkConnectivityListener?) {
        connectivityListener = listener
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun forceNetworkCheck() {
        networkScope.launch { evaluateCurrentPrimaryNetwork() }
    }

    override fun getNetworkInfo(): Map<String, Any> = mapOf(
        "available" to isNetworkAvailable,
        "wifi" to (primaryNetworkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ?: false),
        "cellular" to (primaryNetworkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            ?: false)
    )

    override fun dispose() {
        try {
            pendingValidationJob?.cancel()
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkScope.cancel()
            connectivityListener = null
        } catch (e: Exception) {
            println("$TAG: Error disposing manager: ${e.message}")
        }
    }
}
