/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Upstream wireguard-android contains no network-change handling at all —
 * no ConnectivityManager, no NetworkCallback, nothing. That is the root cause of the single
 * most common complaint about the app: you walk out of Wi-Fi onto mobile data, the tunnel
 * still reads "connected", and nothing passes.
 *
 * Why a restart rather than a rebind: the Go layer exposes only wgTurnOn/wgTurnOff (plus
 * getters). There is no bind-update entry point to call, so re-creating the tunnel is the
 * only lever the app layer has — and it is a good one, because it rebuilds the UDP socket on
 * the new network and re-resolves the endpoint hostname at the same time.
 */
package com.wireguard.android.model

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.wireguard.android.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object NetworkMonitor {
    /**
     * Every non-VPN transport currently available.
     *
     * The signal we want is a network being LOST, not one appearing. A phone routinely has
     * Wi-Fi and cellular up at once — at registration the framework reports every matching
     * network, and later ones come and go — but a tunnel's socket only breaks when the
     * network it was riding goes away. Treating "a new network appeared" as a handover meant
     * restarting the tunnel at startup on any device with both radios on.
     */
    private val available = mutableSetOf<Long>()

    /** A network went away with nothing left to move to; act when something arrives. */
    private var awaitingReplacement = false
    private var started = false

    private val changes = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun start(scope: CoroutineScope) {
        if (started) return
        val cm = Application.get().getSystemService(ConnectivityManager::class.java) ?: run {
            Log.w(TAG, "No ConnectivityManager; roaming recovery disabled")
            return
        }
        started = true

        scope.launch {
            // collectLatest + delay is the debounce: a handover emits a burst of callbacks
            // (available, capabilities, link properties) and restarting once per callback
            // would tear the tunnel down repeatedly on a single network change.
            changes.collectLatest {
                delay(SETTLE_MS)
                HandshakeWatchdog.onNetworkChanged()
            }
        }

        // Deliberately NOT registerDefaultNetworkCallback: once our own tunnel is up, the VPN
        // *is* the default network, so that callback would report our own tunnel coming and
        // going and we would restart ourselves forever. NetworkRequest.Builder implies
        // NET_CAPABILITY_NOT_VPN, so this request only ever matches real transports.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                synchronized(available) {
                    available.add(handle)
                    if (!awaitingReplacement) {
                        // Another transport coming up alongside the current one does not
                        // disturb a live tunnel. Nothing to do.
                        Log.d(TAG, "Network $handle available (${available.size} up)")
                        return
                    }
                    awaitingReplacement = false
                }
                Log.i(TAG, "Connectivity restored on $handle after a drop")
                changes.tryEmit(Unit)
            }

            override fun onLost(network: Network) {
                val handle = network.networkHandle
                val othersRemain = synchronized(available) {
                    available.remove(handle)
                    if (available.isEmpty()) awaitingReplacement = true
                    available.isNotEmpty()
                }
                if (othersRemain) {
                    // The classic handover: Wi-Fi drops while cellular is already up. Whatever
                    // socket the tunnel held is gone even though the device still has internet.
                    Log.i(TAG, "Lost network $handle; another transport remains — treating as a handover")
                    changes.tryEmit(Unit)
                } else {
                    // No transport at all: a restart now could only fail. Wait for one.
                    Log.d(TAG, "Lost network $handle; no transport left, waiting")
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Throwable) {
            // Some OEM builds cap the number of callbacks a process may register.
            Log.w(TAG, "Could not register network callback; roaming recovery disabled", e)
            started = false
        }
    }

    private const val TAG = "Portway/NetworkMonitor"

    /** Long enough for a handover's callback burst to settle, short enough to feel immediate. */
    private const val SETTLE_MS = 1_500L
}
