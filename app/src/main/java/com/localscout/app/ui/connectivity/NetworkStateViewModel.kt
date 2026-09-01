package com.localscout.app.ui.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface NetworkState {
    data object Online : NetworkState
    data object Offline : NetworkState
}

@HiltViewModel
class NetworkStateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Online)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService<ConnectivityManager>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = NetworkState.Online
        }
        override fun onLost(network: Network) {
            _networkState.value = NetworkState.Offline
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _networkState.value = if (online) NetworkState.Online else NetworkState.Offline
        }
    }

    init {
        registerCallback()
        recheck()
    }

    private fun registerCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE permission is in manifest; this should never happen.
            e.printStackTrace()
        }
    }

    fun recheck() {
        viewModelScope.launch {
            val cm = connectivityManager ?: run {
                _networkState.value = NetworkState.Offline
                return@launch
            }
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val online = caps?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } ?: false
            _networkState.value = if (online) NetworkState.Online else NetworkState.Offline
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }
}
