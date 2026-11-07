package com.ghosttyxr.app.terminal.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connectivity signal source.
 *
 * [DefaultCockpitLifecycle] observes [isOnline] and, on a `false -> true`
 * edge, nudges every engine's [TerminalSessionEngine.onNetworkAvailable] so
 * a pane stuck in exponential backoff retries *immediately* instead of
 * waiting out the (up to 60s) delay. The headset roaming Wi-Fi APs or
 * waking from sleep is exactly when you want the terminals back fast.
 *
 * An interface rather than a concrete class so tests inject a controllable
 * fake without a real [ConnectivityManager]. Production wiring uses
 * [ConnectivityNetworkMonitor].
 */
interface NetworkMonitor {
    /** `true` while a network with internet capability is available. */
    val isOnline: StateFlow<Boolean>

    /** Begin observing connectivity. Idempotent. */
    fun start()

    /** Stop observing and release the platform callback. Idempotent. */
    fun stop()
}

/**
 * [ConnectivityManager]-backed [NetworkMonitor]. Requires the
 * `ACCESS_NETWORK_STATE` permission (already declared in the manifest).
 */
class ConnectivityNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private val _isOnline = MutableStateFlow(queryOnline())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
        }

        override fun onLost(network: Network) {
            // Another transport may still be up — re-query rather than
            // assuming offline just because one network dropped.
            _isOnline.value = queryOnline()
        }
    }

    override fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
        // Seed with the current value in case we registered after a change.
        _isOnline.value = queryOnline()
    }

    override fun stop() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun queryOnline(): Boolean = runCatching {
        connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ?: false
    }.getOrDefault(false)

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
