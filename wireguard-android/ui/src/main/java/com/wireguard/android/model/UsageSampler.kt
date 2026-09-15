/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Turns WireGuard's per-session byte counters into a daily total that
 * survives reconnects, so the detail screen can show fourteen days of usage.
 *
 * The counters restart at zero every time a tunnel comes up, which is why this differences them
 * rather than storing the number the backend reports. A tunnel that reconnects six times in an
 * evening would otherwise contribute its largest single session and lose the other five.
 *
 * Running here rather than in a screen's polling loop is the whole point: usage happens while
 * nobody is looking. Whenever a tunnel is up, Android is holding this process alive for the VPN
 * service, so this loop is alive too. The gaps it cannot cover are process death mid-session and
 * the last minute before a tunnel goes down; both undercount, neither invents traffic.
 */
package com.wireguard.android.model

import android.os.SystemClock
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.util.UsageHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object UsageSampler {
    /**
     * One statistics round-trip per tunnel per minute. Matches the watchdog's background
     * cadence, which is the rate we already decided was acceptable for a tunnel that is fine.
     */
    private const val SAMPLE_INTERVAL_MS = 60_000L

    /** Last cumulative rx+tx seen per tunnel, so we can record the difference. */
    private val lastCumulative = mutableMapOf<String, Long>()

    /**
     * elapsedRealtime at the start of the previous pass; 0 before the first. A tunnel that came up
     * after it started from zero counters inside a window we were watching (see [sampleLocked]).
     */
    private var lastPassAt = 0L

    /** The 60s loop and [flush] must not interleave, or two passes difference against each other. */
    private val mutex = Mutex()

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                runCatching { sample() }.onFailure { Log.w(TAG, "usage sample failed", it) }
            }
        }
    }

    /**
     * Records what the running tunnels have carried since the last pass, right now. Called before a
     * state change that can take a tunnel down: once it is down its counters are gone, and the
     * traffic since the last 60s pass used to vanish with them — up to a minute lost per session.
     * A teardown the system performs on its own (another VPN taking over) still cannot be caught.
     */
    suspend fun flush() {
        runCatching { mutex.withLock { sampleLocked() } }.onFailure { Log.w(TAG, "usage flush failed", it) }
    }

    private suspend fun sample() = mutex.withLock { sampleLocked() }

    private suspend fun sampleLocked() {
        val passStartedAt = SystemClock.elapsedRealtime()
        val manager = Application.getTunnelManager()
        // Non-suspending on purpose: getTunnels() awaits a deferred that is completed later in
        // startup, and awaiting it from a background loop is how this file could deadlock the
        // very restore it is meant to be independent of.
        if (!manager.hasTunnelUp()) {
            lastCumulative.clear()
            lastPassAt = passStartedAt
            return
        }
        val tunnels = manager.getTunnels()
        val up = tunnels.filter { it.state == Tunnel.State.UP }
        // A tunnel that went down has had its counters reset, so its baseline must go with it
        // or the next session's first sample would be recorded as a negative and dropped.
        lastCumulative.keys.retainAll(up.map { it.name }.toSet())
        up.forEach { tunnel ->
            val statistics = runCatching { tunnel.getStatisticsAsync() }.getOrNull() ?: return@forEach
            val cumulative = statistics.totalRx() + statistics.totalTx()
            val previous = lastCumulative[tunnel.name]
            lastCumulative[tunnel.name] = cumulative
            // No baseline yet: the first sample of this session. WireGuard's counters start at zero
            // when a tunnel comes up, so if it came up after our previous pass began — or in this
            // process before any pass — everything counted so far is this session's, and dropping
            // it lost up to a minute per connection. A tunnel adopted at process start carries no
            // stamp; its earlier traffic belongs to a session we did not watch, so it is not ours.
            // A smaller value than last time means the tunnel restarted between samples.
            val since = tunnel.connectedSinceElapsedRealtime
            val delta = when {
                previous == null -> if (since != null && since >= lastPassAt) cumulative else 0L
                cumulative >= previous -> cumulative - previous
                else -> cumulative
            }
            UsageHistory.record(delta)
        }
        lastPassAt = passStartedAt
    }

    private const val TAG = "WireGuard/UsageSampler"
}
