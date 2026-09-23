/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. Why each config's last session ended, so a support question can be answered without
 * asking the user to describe what they saw.
 *
 * The app already watched tunnels go down; it just never recorded who did it. The backend reports
 * a teardown the same way whether the user tapped disconnect, the watchdog restarted a stalled
 * tunnel, another device took the session, or Android killed the process — and "it just
 * disconnects" is the least useful sentence in a support thread.
 *
 * So whoever causes a teardown says so FIRST, with [expect], and [record] — called when the
 * backend actually reports the tunnel down — keeps that attribution or falls back to SYSTEM.
 * Guessing afterwards cannot work: by the time the report arrives the cause is gone.
 *
 * KILLED is the one that cannot be observed as it happens, and it is the most valuable: a process
 * Android stopped in the background writes nothing. It is inferred at the next start instead, from
 * the persisted running-tunnels set, which a clean disconnect would have emptied. Anything still
 * listed there ended with the process (or with a reboot).
 *
 * UNKNOWN is sent explicitly rather than by omitting the field: a teardown the app could not
 * attribute is itself information, and the panel should not have to infer it from absence.
 */
package com.wireguard.android.util

import android.util.Log

object DisconnectReasons {
    /** The vocabulary the panel sees. Values are the wire format; keep them stable. */
    enum class Reason(val wire: String) {
        /** A person asked for it. */
        USER("user"),

        /** Torn down by the backend to make room for another config the user connected. */
        REPLACED("replaced"),

        /** The watchdog found it up but not handshaking and restarted it. */
        HANDSHAKE_TIMEOUT("handshake_timeout"),

        /** Another device took the session; this one stood down. */
        SUPERSEDED("superseded"),

        /** Taken down so an update could install. */
        UPDATE("update"),

        /** Android or another VPN ended it: permission revoked, another app took over, teardown. */
        SYSTEM("system"),

        /** The process ended while connected — killed in the background, or the device rebooted. */
        KILLED("killed"),

        /** Nothing has been recorded for this config yet. Sent as a value, never as an absence. */
        UNKNOWN("unknown"),
    }

    private const val TAG = "Portway/DisconnectReasons"

    /** Attributions declared but not yet confirmed by a backend report, by tunnel name. */
    private val expected = mutableMapOf<String, Reason>()

    /** Declare the cause of a teardown that is about to happen. */
    fun expect(tunnelName: String, reason: Reason) {
        expected[tunnelName] = reason
    }

    /** Drop a declaration that did not happen after all, so it cannot mislabel a later teardown. */
    fun forget(tunnelName: String) {
        expected.remove(tunnelName)
    }

    /**
     * The backend reported this tunnel down: whoever claimed it, or SYSTEM — nothing in the app
     * asked, so it was Android, another VPN app, or a revoked permission.
     *
     * Synchronous and separate from [persist] because two things need this answer at the same
     * moment: the store, which can wait, and the session release, which reports it to the panel.
     * Having each of them take the attribution for itself would mean whichever coroutine ran
     * first won and the other saw nothing.
     */
    fun take(tunnelName: String, fallback: Reason = Reason.SYSTEM): Reason =
        expected.remove(tunnelName) ?: fallback

    /** Store an attribution already taken, for the next claim or register to report. */
    suspend fun persist(tunnelName: String, reason: Reason) {
        UserKnobs.setLastDisconnect(tunnelName, reason.wire, System.currentTimeMillis())
        Log.i(TAG, "$tunnelName went down: ${reason.wire}")
    }

    /**
     * Called at start-up with the tunnels the persisted set still claims are running and that the
     * backend says are not. A clean disconnect removes a tunnel from that set, so anything left
     * ended with the process.
     */
    suspend fun recordKilled(tunnelNames: Collection<String>) {
        tunnelNames.forEach {
            UserKnobs.setLastDisconnect(it, Reason.KILLED.wire, System.currentTimeMillis())
            Log.i(TAG, "$it was still marked running at start-up: killed")
        }
    }

    /** The stored reason and when it happened, or UNKNOWN with no time. */
    suspend fun last(tunnelName: String): Pair<String, Long?> =
        UserKnobs.lastDisconnect(tunnelName) ?: (Reason.UNKNOWN.wire to null)
}
