package com.ravango.platform.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkStatus(val online: Boolean, val unmetered: Boolean)

/** Default-network connectivity via [ConnectivityManager.registerDefaultNetworkCallback]. */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    @Volatile private var registered = false

    fun start() {
        if (registered || connectivity == null) return
        registered = true
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _status.value = caps.toStatus()
                }

                override fun onLost(network: Network) {
                    _status.value = NetworkStatus(online = false, unmetered = false)
                }

                override fun onUnavailable() {
                    _status.value = NetworkStatus(online = false, unmetered = false)
                }
            })
        }.onFailure {
            registered = false
            RgLog.w("Network", "could not register network callback", it)
        }
    }

    private fun currentStatus(): NetworkStatus {
        val caps = connectivity?.let { cm -> cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }
        return caps?.toStatus() ?: NetworkStatus(online = false, unmetered = false)
    }

    private fun NetworkCapabilities.toStatus() = NetworkStatus(
        online = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        unmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
