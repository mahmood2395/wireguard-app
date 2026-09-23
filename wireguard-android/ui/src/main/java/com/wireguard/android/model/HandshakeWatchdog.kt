/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. A WireGuard tunnel can sit in the UP state while never completing a
 * handshake — the endpoint moved, the network changed underneath it, a NAT binding died, or
 * the DNS name now resolves elsewhere. The kernel/userspace side keeps retrying the same
 * resolved endpoint and the user just sees "connected" with nothing working.
 *
 * This watchdog restarts such a tunnel. Restarting re-resolves the endpoint and forces a
 * fresh handshake, which is the cheapest fix that needs no cooperation from the peer.
 */
package com.wireguard.android.model

import android.os.SystemClock
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.widget.HandshakeDecayView
import com.wireguard.android.util.DisconnectReasons
import com.wireguard.android.util.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object HandshakeWatchdog {
    /** Name of the tunnel currently being restarted, for the UI to show. */
    private val _reconnecting = MutableStateFlow<String?>(null)
    val reconnecting: StateFlow<String?> = _reconnecting.asStateFlow()

    private data class Health(
        var attempts: Int = 0,
        var lastRestartAt: Long = 0,
        var restarts: Int = 0,
        var silentSince: Long = 0,
    )

    private val health = mutableMapOf<String, Health>()

    /**
     * How many times this tunnel has been restarted since it came up. Unlike [Health.attempts],
     * which resets the moment a handshake lands, this counts the whole session: a tunnel that
     * breaks and recovers ten times looks perfectly healthy in any single snapshot, and that is
     * exactly the fault a user reports as "it keeps dropping".
     *
     * Read from SessionGuard's heartbeat, which runs on the same Main scope as this watchdog,
     * so the map needs no synchronisation.
     */
    fun restartsOf(tunnelName: String): Int = health[tunnelName]?.restarts ?: 0

    /**
     * Seconds this tunnel has been judged not-handshaking, or null while it is healthy — or
     * while nothing has judged it, which is also the answer when auto-reconnect is off.
     *
     * The only clock here that survives a restart. Every other measure of "how long has this
     * been broken" is reset by the restarts themselves: a tunnel whose server answers nothing
     * is put back up every thirty seconds, so its uptime never grows, and judged on uptime it
     * looks permanently like a connection that has simply not finished yet. That is how a dead
     * tunnel reported itself as "connecting" indefinitely.
     */
    fun silentForSeconds(tunnelName: String): Long? =
        health[tunnelName]?.silentSince?.takeIf { it != 0L }
            ?.let { (SystemClock.elapsedRealtime() - it) / 1000L }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                // Backing off while nobody is looking is the single biggest battery saving
                // available here: this loop otherwise runs a statistics round-trip across the
                // JNI boundary every ten seconds, all day, for a tunnel that is usually fine.
                // A stalled tunnel is still caught — the stale-handshake threshold is three
                // minutes, a network change is reported immediately by NetworkMonitor, and
                // Doze is covered by WatchdogAlarm — so the only cost is noticing a stall up
                // to a minute later while the app is not on screen.
                delay(if (Application.isForeground()) CHECK_INTERVAL_MS else BACKGROUND_INTERVAL_MS)
                runPass()
            }
        }
    }

    /**
     * One health check over every tunnel that is up.
     *
     * Separate from the polling loop because that loop is a coroutine [delay], which the
     * scheduler suspends in Doze — exactly the long-idle stretch where tunnels go stale and
     * where a user is most likely to pick the phone up and find nothing works. [WatchdogAlarm]
     * drives this same pass from an alarm and on wake-up, where the loop cannot run.
     */
    suspend fun runPass() {
        try {
            // Judging happens either way; only the restart is the setting's to refuse. A user who
            // turns auto-reconnect off is asking not to have their tunnel bounced, not asking to
            // be told nothing — and the silence clock is what the Configs list and the panel read.
            val mayRestart = UserKnobs.autoReconnect.first()
            val tunnels = Application.getTunnelManager().getTunnels()
            // Snapshot: restarting mutates state and the list is observable.
            tunnels.filter { it.state == Tunnel.State.UP }.forEach { check(it, mayRestart) }
            // Forget tunnels that are no longer up, so a manual reconnect starts clean.
            // A tunnel mid-restart is momentarily DOWN; purging it here would reset its attempt
            // counter and put it back in the quick-retry burst on every pass instead of backing off.
            val upNames = tunnels.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet() +
                setOfNotNull(_reconnecting.value)
            health.keys.retainAll(upNames)
        } catch (e: Throwable) {
            Log.w(TAG, "watchdog pass failed", e)
        }
    }



    /**
     * The underlying transport moved. Called by [NetworkMonitor].
     *
     * A handover invalidates the UDP socket the tunnel is riding, so waiting for the ordinary
     * stale-handshake path would leave it dead for up to three minutes. We know the cause here,
     * so act at once — and clear the attempt counters, because the backoff exists to stop us
     * hammering a broken endpoint, not to punish a tunnel for having been on a network that went
     * away. Without this reset a tunnel that backed off while out of signal would wait out up to
     * fifteen minutes after signal returned.
     */
    suspend fun onNetworkChanged() {
        if (!UserKnobs.autoReconnect.first()) return
        health.values.forEach { it.attempts = 0 }
        if (_reconnecting.value != null) return   // a restart is already in flight
        val tunnels = runCatching { Application.getTunnelManager().getTunnels() }.getOrNull() ?: return
        tunnels.filter { it.state == Tunnel.State.UP }.forEach { tunnel ->
            Log.i(TAG, "Network changed; restarting ${tunnel.name}")
            health.getOrPut(tunnel.name) { Health() }.lastRestartAt = SystemClock.elapsedRealtime()
            restart(tunnel)
        }
    }

    private suspend fun check(tunnel: ObservableTunnel, mayRestart: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val entry = health.getOrPut(tunnel.name) { Health() }

        // Don't judge a tunnel that has only just come up, and leave room after a restart
        // for the new attempt to actually complete.
        val upFor = tunnel.connectedSinceElapsedRealtime?.let { now - it } ?: return
        if (upFor < INITIAL_GRACE_MS) return
        if (entry.lastRestartAt != 0L && now - entry.lastRestartAt < RESTART_GRACE_MS) return

        val stats = runCatching { tunnel.getStatisticsAsync() }.getOrNull() ?: return
        val latestHandshake = stats.peers().mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis }
            .filter { it > 0 }
            .maxOrNull() ?: 0L

        val healthy = if (latestHandshake > 0) {
            // A live tunnel rekeys about every two minutes, so a handshake older than the
            // stale threshold means traffic has stopped flowing, not that it is merely idle.
            System.currentTimeMillis() - latestHandshake < STALE_HANDSHAKE_MS
        } else {
            false
        }

        if (healthy) {
            entry.attempts = 0
            entry.silentSince = 0
            tunnel.onLinkSilenceChanged()
            return
        }
        // First judgement of this silence. Kept across the restarts that follow, because it is
        // the same silence — see silentForSeconds.
        if (entry.silentSince == 0L) entry.silentSince = now
        tunnel.onLinkSilenceChanged()
        if (!mayRestart) return
        // Upstream-style "give up after N" left a tunnel dead forever once the budget was
        // spent — the worst case being a server that moved while the old address was still
        // cached, so every attempt failed for a reason that later fixed itself. Back off
        // instead: keep trying, just rarely, so recovery needs no user action.
        if (entry.attempts >= MAX_ATTEMPTS) {
            val backoff = BACKOFF_BASE_MS shl (entry.attempts - MAX_ATTEMPTS).coerceAtMost(4)
            if (now - entry.lastRestartAt < backoff.coerceAtMost(BACKOFF_CAP_MS)) return
        }

        entry.attempts++
        entry.lastRestartAt = now
        Log.i(TAG, "No handshake for ${tunnel.name} (attempt ${entry.attempts}); restarting")
        refreshEndpoints(tunnel)
        restart(tunnel)
    }

    /**
     * Force the next bring-up to look the endpoint up again.
     *
     * Without this a restart reuses the address already held — InetEndpoint keeps a resolution
     * for a minute — so the first restart after a server moves could not possibly find it, and
     * that is the restart most likely to happen.
     */
    private suspend fun refreshEndpoints(tunnel: ObservableTunnel) {
        runCatching {
            val config = tunnel.getConfigAsync()
            // All of it on IO, not just the middle call. getResolved() is a blocking DNS lookup
            // whenever its cached answer is stale or missing, and this pass runs on the
            // application's Main scope: the "before" read resolves once the cache is two
            // minutes old, and the "after" read resolves AGAIN if the refresh failed — which is
            // the usual case here, because a tunnel that stopped handshaking takes the app's own
            // DNS down with it. Each of those used to freeze the main thread for seconds, on
            // every restart attempt, exactly when the tunnel was already in trouble.
            withContext(Dispatchers.IO) {
                config.peers.forEach { peer ->
                    peer.endpoint.orElse(null)?.let { endpoint ->
                        val before = endpoint.getResolved().orElse(null)?.host
                        endpoint.invalidateResolution()
                        val after = endpoint.getResolved().orElse(null)?.host
                        if (before != null && after != null && before != after)
                            Log.i(TAG, "Endpoint ${endpoint.host} moved: $before -> $after")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Could not refresh endpoint resolution", it) }
    }

    private suspend fun restart(tunnel: ObservableTunnel) {
        health.getOrPut(tunnel.name) { Health() }.restarts++
        _reconnecting.value = tunnel.name
        try {
            // Main, like every other caller of setStateAsync: the backend notifies databinding
            // synchronously from the calling thread and those callbacks touch views.
            withContext(Dispatchers.Main.immediate) {
                val manager = Application.getTunnelManager()
                DisconnectReasons.expect(tunnel.name, DisconnectReasons.Reason.HANDSHAKE_TIMEOUT)
                tunnel.setStateAsync(Tunnel.State.DOWN, SessionGuard.Gate.NONE)
                // Our own down is the only state change we expect to see across the pause.
                val expected = manager.stateChangeGeneration
                // The pause is a real suspension point, so a user tap can land inside it. Their
                // tap would be a no-op — the tunnel is already down, because we just put it
                // there — and then we would bring the tunnel back up underneath them, with no
                // sign that an explicit instruction had been overruled.
                delay(RESTART_PAUSE_MS)
                if (manager.stateChangeGeneration != expected) {
                    Log.i(TAG, "Abandoning restart of ${tunnel.name}: state changed during the pause")
                    return@withContext
                }
                tunnel.setStateAsync(Tunnel.State.UP, SessionGuard.Gate.NONE)
            }
        } catch (e: Throwable) {
            // Most likely the VPN permission was revoked while running; nothing to do but log.
            Log.w(TAG, "Restart of ${tunnel.name} failed", e)
        } finally {
            _reconnecting.value = null
        }
    }

    private const val TAG = "Portway/HandshakeWatchdog"
    private const val CHECK_INTERVAL_MS = 10_000L
    private const val BACKGROUND_INTERVAL_MS = 60_000L
    private const val INITIAL_GRACE_MS = 25_000L
    private const val RESTART_GRACE_MS = 30_000L
    private const val RESTART_PAUSE_MS = 700L
    /**
     * The same number the decay bar draws its full track against. The bar and this watchdog
     * answer one question — "up but not handshaking" — and the bar is the visual form of the
     * condition acted on here, so they read the same constant rather than two copies of 180s
     * that can drift apart. It is `const`, so the compiler inlines it and nothing loads a View
     * class on this headless path.
     */
    private const val STALE_HANDSHAKE_MS = HandshakeDecayView.HANDSHAKE_LIMIT_MS
    private const val MAX_ATTEMPTS = 5

    /** After the burst of quick attempts, keep retrying on a widening interval, never stopping. */
    private const val BACKOFF_BASE_MS = 60_000L
    private const val BACKOFF_CAP_MS = 15 * 60 * 1000L
}
