/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. What "connected" actually means for one tunnel, in the terms support needs.
 *
 * A WireGuard tunnel is UP the moment the interface exists. That says the app asked for a
 * connection — not that the server answered. Every way a connection can fail after that point
 * (the endpoint moved, the ISP blocks the port, the key was removed from the router, the
 * handshake never completes) leaves the tunnel sitting in UP with no handshake, which is
 * precisely the state a user describes as "it says connected but nothing works".
 *
 * The app has always drawn that difference on screen — the decay bar's waiting/late/silent
 * states — but everything it REPORTED said only "up", so the panel showed a device as
 * connected while it was reaching nobody. That made the panel actively misleading in the one
 * situation where an operator most needs it, because it looked like the server was fine.
 *
 * So this is the same judgement the decay bar and the watchdog already make, in one place, in
 * a form that can be sent: a [Link] state, the numbers behind it, and the two facts that
 * usually explain it — how many times the watchdog has already restarted this tunnel, and
 * which transport it is riding.
 *
 * The thresholds are HandshakeDecayView's, not new ones. A tunnel is judged by WireGuard's own
 * 180s cutoff whether it is being drawn, restarted or reported.
 */
package com.wireguard.android.util

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.HandshakeWatchdog
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.widget.HandshakeDecayView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TunnelHealth {
    /**
     * The vocabulary the panel sees. Only [HANDSHAKING] means traffic is passing; everything
     * else is a tunnel that a user would call connected and an operator should not.
     */
    enum class Link(val wire: String) {
        /** Up, no handshake yet, and not up long enough for that to be wrong. */
        CONNECTING("connecting"),

        /** Handshaked inside WireGuard's 180s window. The only healthy value. */
        HANDSHAKING("handshaking"),

        /** Handshaked once, but not within the window: it was working and stopped. */
        STALE("stale"),

        /** Up past the window having never handshaked once: it never reached the server. */
        NO_HANDSHAKE("no_handshake"),

        /** Not up. Reported on the way down, never by a heartbeat. */
        DOWN("down"),
    }

    /**
     * @param handshakeAgeSeconds null when the tunnel has never handshaked — which is the whole
     *        point of the distinction between CONNECTING/NO_HANDSHAKE and STALE.
     * @param rxBytes bytes received since the tunnel came up. Zero with a non-zero tx is the
     *        signature of a server that is not answering at all.
     * @param restarts how many times the watchdog has restarted this tunnel in this session. A
     *        high number with a healthy link is a tunnel that keeps breaking and recovering,
     *        which no single snapshot would ever show.
     */
    data class Snapshot(
        val link: Link,
        val handshakeAgeSeconds: Long?,
        val connectedForSeconds: Long?,
        val rxBytes: Long,
        val txBytes: Long,
        val restarts: Int,
        val transport: String?,
        val silentForSeconds: Long?,
    )

    /** Reads statistics, so not free: called once per heartbeat, never in a render loop. */
    suspend fun of(tunnel: ObservableTunnel): Snapshot {
        if (tunnel.state != Tunnel.State.UP) {
            return Snapshot(
                Link.DOWN, null, null, 0L, 0L,
                HandshakeWatchdog.restartsOf(tunnel.name), transport(), null,
            )
        }
        val upFor = tunnel.connectedSinceElapsedRealtime
            ?.let { (SystemClock.elapsedRealtime() - it) / 1000L }
        val stats = runCatching { tunnel.getStatisticsAsync() }.getOrNull()
        val latest = stats?.peers()
            ?.mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis }
            ?.filter { it > 0 }
            ?.maxOrNull()
        val age = latest?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) }

        val restarts = HandshakeWatchdog.restartsOf(tunnel.name)
        // Measured across the watchdog's restarts, which the up-time is not: see
        // HandshakeWatchdog.silentForSeconds. Null while the tunnel is healthy, and also
        // whenever nothing has judged it — including with auto-reconnect switched off, which is
        // exactly the case the up-time below does answer, because then nothing resets it.
        val silentFor = HandshakeWatchdog.silentForSeconds(tunnel.name)
        val link = when {
            age != null && age < HandshakeDecayView.HANDSHAKE_LIMIT -> Link.HANDSHAKING
            age != null -> Link.STALE
            silentFor != null && silentFor >= HandshakeDecayView.HANDSHAKE_LIMIT -> Link.NO_HANDSHAKE
            // Never handshaked at all. A tunnel adopted at process start carries no up-stamp,
            // and guessing CONNECTING there would hide a dead tunnel for as long as the app
            // stayed open — so an unknown up-time is judged as the older, louder state.
            upFor == null || upFor >= HandshakeDecayView.HANDSHAKE_LIMIT -> Link.NO_HANDSHAKE
            else -> Link.CONNECTING
        }
        return Snapshot(
            link = link,
            handshakeAgeSeconds = age,
            connectedForSeconds = upFor,
            rxBytes = stats?.totalRx() ?: 0L,
            txBytes = stats?.totalTx() ?: 0L,
            restarts = restarts,
            transport = transport(),
            // Cleared by the watchdog's next pass, which may be up to a minute away, so a tunnel
            // that has just recovered would otherwise report a silence that is over. The state
            // above is the authority; this number only ever qualifies a state that is not healthy.
            silentForSeconds = silentFor?.takeIf { link != Link.HANDSHAKING },
        )
    }

    /**
     * What the tunnel is riding: "works on Wi-Fi, not on mobile data" is an ISP-level block,
     * and it is otherwise a whole support conversation to establish.
     *
     * Once a tunnel is up the active network IS our own VPN, so asking for it plainly would
     * answer "vpn" every time. The transport underneath is what matters.
     */
    private suspend fun transport(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val cm = Application.get().getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching null
            val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (active != null && !active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@runCatching label(active)
            }
            // NetworkCapabilities.getUnderlyingNetworks() would name it directly, but it is not
            // in the public SDK, so ask what else is up. A phone with both radios on can answer
            // "wifi" while riding cellular; that is a rare wrong answer to a question that is
            // only ever a hint, and the alternative is no answer at all.
            @Suppress("DEPRECATION")
            cm.allNetworks.firstNotNullOfOrNull { network ->
                cm.getNetworkCapabilities(network)?.takeIf {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }?.let { label(it) } ?: "none"
        }.getOrNull()
    }

    private fun label(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }
}
