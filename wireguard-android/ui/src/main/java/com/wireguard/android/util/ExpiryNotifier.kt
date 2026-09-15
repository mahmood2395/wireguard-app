/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Warns a user whose plan is about to run out.
 *
 * The account card already shows days remaining, but only to someone who opens the app — and
 * the person who most needs the warning is precisely the one who has not opened it in a week.
 * So this also checks on a timer of its own.
 *
 * It deliberately does NOT reuse the watchdog's alarm, which is only armed while a tunnel is
 * up. An account close to expiry is often one whose tunnel is already down, which is the exact
 * population that alarm would miss.
 *
 * This is the app's only notification. Android posts its own persistent notice for any active
 * VpnService, and duplicating that was never worth a permission prompt; telling someone their
 * access ends on Thursday is.
 */
package com.wireguard.android.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object ExpiryNotifier {
    /**
     * Warn at three days and at every whole day after.
     *
     * Inclusive, so the three-day mark itself produces a notice — that is the one with enough
     * time left to actually act on.
     */
    const val WARN_AT_DAYS = 3

    private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        createChannel(Application.get())
        scope.launch { schedule() }
    }

    /**
     * Post a warning if this account is close to running out.
     *
     * De-duplicated on the number of days rather than on a timestamp: a user is told once at
     * three days, once at two, once at one and once on the day it lapses, and never twice for
     * the same number however often the app refetches. A renewal pushes the count back above
     * the threshold, which clears the record so the next expiry warns again.
     */
    suspend fun consider(tunnelName: String, info: AccountRepository.AccountInfo) {
        val days = info.daysLeft
        val context = Application.get()

        // The stage this config is at, or null when there is nothing to warn about. A suspended
        // account is a stage of its own and is warned about even when the panel sends no day
        // count — the old early return on a missing count silently skipped exactly that case.
        val stage = when {
            info.disabled -> "suspended"
            days != null && days <= WARN_AT_DAYS -> days.toString()
            else -> null
        }
        // Per config: a single global marker was cleared by whichever config was checked next, so
        // an expiring config was warned again on every check that also looked at a healthy one.
        val warned = UserKnobs.expiryNoticeFor(tunnelName)
        if (stage == null) {
            // Nothing to say. Clear this config's marker so a future expiry is announced afresh.
            if (warned != null) UserKnobs.setExpiryNotice(tunnelName, null)
            return
        }
        if (stage == warned) return
        val marker = "$tunnelName:$stage"
        if (!canNotify(context)) {
            // Permission is requested from the Connect screen; without it the account card is
            // still the fallback, so this is not worth surfacing as an error.
            Log.d(TAG, "Would warn ($marker) but notifications are not permitted")
            return
        }

        val (title, text) = message(context, tunnelName, days, info)
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_portway_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            // Not ongoing, and dismissable: it is information, not something to fight with.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // One slot per config, so two expiring configs no longer overwrite each other's notice.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + (tunnelName.hashCode() and 0x3ff), notification) }
            .onSuccess { Log.i(TAG, "Warned: $marker") }
            .onFailure { Log.w(TAG, "Could not post expiry notice", it) }
        UserKnobs.setExpiryNotice(tunnelName, stage)
    }

    private fun message(
        context: Context,
        tunnelName: String,
        days: Int?,
        info: AccountRepository.AccountInfo,
    ): Pair<String, String> = when {
        info.disabled -> context.getString(R.string.expiry_suspended_title) to
            context.getString(R.string.expiry_suspended_text, tunnelName)
        days == null || days <= 0 -> context.getString(R.string.expiry_today_title) to
            context.getString(R.string.expiry_today_text, tunnelName)
        else -> context.resources.getQuantityString(R.plurals.expiry_soon_title, days, days) to
            context.getString(R.string.expiry_soon_text, tunnelName)
    }

    fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        else
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.expiry_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.expiry_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Re-arm the periodic check. Twice a day rather than once, so a device that is asleep across
     * the moment the count ticks over still hears about it the same day.
     */
    fun schedule() {
        val context = Application.get()
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, CheckReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            else
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }.onFailure { Log.w(TAG, "Could not schedule expiry check", it) }
    }

    class CheckReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CHECK) return
            applicationScope.launch {
                try {
                    // Every tunnel, because the account that is expiring is not necessarily the
                    // one last used — and a user with a lapsed plan may have switched away from it.
                    val tunnels = Application.getTunnelManager().getTunnels()
                    for (tunnel in tunnels) {
                        val result = AccountRepository.fetch(tunnel)
                        if (result is AccountRepository.Result.Ok) consider(tunnel.name, result.info)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Expiry check failed", e)
                } finally {
                    schedule()   // one-shot alarms: re-arm every time
                }
            }
        }
    }

    private const val TAG = "Portway/ExpiryNotifier"
    private const val CHANNEL_ID = "account_expiry"
    private const val NOTIFICATION_ID = 4711
    private const val ACTION_CHECK = "com.wireguard.android.action.EXPIRY_CHECK"
    private const val INTERVAL_MS = 12 * 60 * 60 * 1000L
}
