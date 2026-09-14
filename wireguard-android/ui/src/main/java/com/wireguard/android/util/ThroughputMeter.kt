/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Derives a live transfer rate from WireGuard's cumulative byte counters, which are all the
 * backend exposes. Shared by the Connect screen and the tunnel detail dashboard.
 */
package com.wireguard.android.util

class ThroughputMeter {
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSampleAt = 0L

    var rxPerSecond = 0.0
        private set
    var txPerSecond = 0.0
        private set

    /** Call when the tunnel goes down, or after any gap that makes the last sample useless. */
    fun reset() {
        lastRx = 0
        lastTx = 0
        lastSampleAt = 0
        rxPerSecond = 0.0
        txPerSecond = 0.0
    }

    /**
     * @param nowElapsedRealtime monotonic clock; wall time would jump with timezone changes.
     */
    fun sample(rx: Long, tx: Long, nowElapsedRealtime: Long) {
        val dt = (nowElapsedRealtime - lastSampleAt) / 1000.0
        if (lastSampleAt != 0L && dt in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) {
            // coerceAtLeast: counters restart at zero when a tunnel goes down or is reconfigured.
            val rxSample = (rx - lastRx).coerceAtLeast(0) / dt
            val txSample = (tx - lastTx).coerceAtLeast(0) / dt
            rxPerSecond = SMOOTHING * rxPerSecond + (1 - SMOOTHING) * rxSample
            txPerSecond = SMOOTHING * txPerSecond + (1 - SMOOTHING) * txSample
        }
        // Outside the window — typically returning from the background — reseed silently
        // rather than reporting a huge burst that never happened.
        lastRx = rx
        lastTx = tx
        lastSampleAt = nowElapsedRealtime
    }

    private companion object {
        const val MIN_INTERVAL_SECONDS = 0.25
        const val MAX_INTERVAL_SECONDS = 5.0
        const val SMOOTHING = 0.6
    }
}
