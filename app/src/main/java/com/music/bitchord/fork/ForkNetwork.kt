package com.music.bitchord.fork

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The network as the fork's offline features see it (fork-only).
 *
 * [canDownload]: any unmetered network (Wi-Fi, Ethernet) always, a metered one
 * (mobile data; a hotspot counts as metered, as Android reports it) only with
 * "Download over mobile data". "Available offline" downloads and
 * pre-loading wait in [awaitDownload] until it allows them.
 *
 * [offline] is offline mode: the switch, or no network at all. Navidrome then
 * shows only what is kept on the phone (`NavidromeRepository.getSongs`).
 */
object ForkNetwork {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The default network's capabilities, or null with none. */
    private val network = MutableStateFlow<NetworkCapabilities?>(null)

    private val _offline = MutableStateFlow(false)
    private val _canDownload = MutableStateFlow(false)

    /** Offline mode: switched on, or no network. */
    val offline: StateFlow<Boolean> = _offline

    /** Whether "Available offline" downloads and pre-loading may run on the network there is now. */
    val canDownload: StateFlow<Boolean> = _canDownload

    fun init(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        network.value = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                network.value = caps
            }

            override fun onLost(n: Network) {
                network.value = null
            }
        })
        scope.launch {
            combine(network, ForkSettings.offlineMode) { caps, mode -> mode || !caps.isOnline() }
                .collect { offline ->
                    _offline.value = offline
                    // The player-side screens (Library rows, Explore) hide the server's parts.
                    com.music.bitchord.ui.screens.ForkLibraryPlaylists.offlineMode.value = offline
                }
        }
        scope.launch {
            combine(network, ForkSettings.downloadOnMobile) { caps, mobile ->
                when {
                    !caps.isOnline() -> false
                    caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> true
                    else -> mobile
                }
            }.collect { _canDownload.value = it }
        }
    }

    /** Suspends until downloads are allowed on the current network. */
    suspend fun awaitDownload() {
        canDownload.first { it }
    }

    private fun NetworkCapabilities?.isOnline(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
