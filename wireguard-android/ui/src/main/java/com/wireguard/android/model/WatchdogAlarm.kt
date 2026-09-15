/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. [HandshakeWatchdog]'s polling loop is a coroutine delay, and the
 * scheduler suspends those in Doze — so the watchdog was blind during exactly the long idle
 * stretches where tunnels go stale, and the user's experience was picking up the phone to
 * find a tunnel that had been dead for hours.
 *
 * Two wake sources, because they answer different halves of the problem:
 *
 *  - **Leaving idle / screen on.** The one that actually matters. Deep in Doze the OS
 *    suspends app network access anyway, so a repair attempt down there would usually fail;
 *    what the user needs is for the tunnel to be healthy by the time they look at it. Both
 *    signals fire before that.
 *  - **A while-idle alarm** as a backstop, for a device left idle so long it never trips the
 *    above. Deliberately inexact: setAndAllowWhileIdle needs no special permission (unlike
 *    the exact variant on API 31+), and the OS coalescing it into a maintenance window is
 *    the correct behaviour rather than a limitation.
 *
 * The alarm is only scheduled while a tunnel is actually up.
 */
package com.wireguard.android.model

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.wireguard.android.Application
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object WatchdogAlarm {
    private var started = false

    /**
     * Screen-on fires every time the user glances at the phone, which on a normal day is
     * dozens of times. A pass costs a statistics round-trip per live tunnel, so rate-limit it:
     * anything within the window has just been checked and cannot have gone stale since.
     */
    private var lastWakePassAt = 0L

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        val context = Application.get()

        // ACTION_SCREEN_ON cannot be declared in a manifest filter — it is registered-only.
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        val wakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = context.getSystemService(PowerManager::class.java)
                val leftIdle = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm?.isDeviceIdleMode != true
                if (!leftIdle) return   // entering idle, not leaving it
                val now = SystemClock.elapsedRealtime()
                if (now - lastWakePassAt < WAKE_THROTTLE_MS) return
                lastWakePassAt = now
                Log.i(TAG, "Device awake (${intent.action}); running a watchdog pass")
                applicationScope.launch { HandshakeWatchdog.runPass() }
            }
        }
        // API 33+ requires an explicit export flag. These are system broadcasts, so the
        // receiver must not be exported to other apps.
        ContextCompat.registerReceiver(context, wakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch { reschedule() }   // after the manager exists
    }

    /**
     * Arms the next while-idle check, or cancels it when nothing is up.
     *
     * Non-suspending on purpose: it is called from setTunnelState, which runs during the
     * initial tunnel load, and anything that awaits the tunnel-list deferred from there
     * deadlocks the load.
     */
    fun reschedule() {
        val context = Application.get()
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        if (!Application.getTunnelManager().hasTunnelUp()) {
            am.cancel(pending)
            return
        }
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not schedule watchdog alarm", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CHECK) return
            Log.i(TAG, "While-idle alarm; running a watchdog pass")
            applicationScope.launch {
                HandshakeWatchdog.runPass()
                reschedule()   // one-shot alarms: re-arm each time
            }
        }
    }

    private const val TAG = "Portway/WatchdogAlarm"
    private const val ACTION_CHECK = "com.wireguard.android.action.WATCHDOG_CHECK"

    /** Shortest gap between two wake-driven passes. */
    private const val WAKE_THROTTLE_MS = 30_000L

    /** Below the Doze maintenance-window floor is pointless; the OS coalesces anyway. */
    private const val INTERVAL_MS = 15 * 60 * 1000L
}
