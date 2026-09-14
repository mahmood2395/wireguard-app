/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Notices that the app was updated while a tunnel was running.
 *
 * Android 16 can wedge the device's network stack when a VPN app is replaced with its VPN
 * active: the tunnel returns looking connected but passes nothing, and only a reboot clears
 * it. It is a platform bug, open at Google since around September 2025, and nothing in this
 * app can prevent it. What the app CAN do is stop the user having to guess — a VPN that
 * silently carries no traffic after an update is indistinguishable, from the outside, from a
 * broken server or an expired account, which is exactly the support ticket we do not want.
 *
 * This matters more here than for a Play Store app: our APK is installed by hand, whenever
 * the user happens to open it, very plausibly mid-session.
 */
package com.wireguard.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        applicationScope.launch {
            // The persisted running set is the only record that survives the process being
            // killed by the install, so it is what tells us whether the risky case happened.
            val wasRunning = UserKnobs.runningTunnels.first()
            if (wasRunning.isEmpty()) {
                Log.i(TAG, "Updated with no tunnel running; nothing to warn about")
                return@launch
            }
            Log.i(TAG, "Updated while ${wasRunning.size} tunnel(s) were running; arming the notice")
            UserKnobs.setUpdatedWhileConnected(true)
        }
    }

    companion object {
        private const val TAG = "Portway/PackageReplaced"
    }
}
