/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Reports whether the OS is allowed to put this app to sleep.
 *
 * Aggressive vendor battery management is one of the most commonly reported causes of a VPN
 * that silently dies in the background, and several manufacturers re-apply the restriction
 * even after a user has lifted it once. The app cannot stop that, but a user who can see the
 * state has something to act on instead of concluding the VPN is unreliable.
 *
 * Requesting the exemption directly is only acceptable because this build is distributed
 * outside Google Play — the direct-request intent is a policy violation for Play apps, and a
 * Play-bound fork would have to send the user to the settings screen instead.
 */
package com.wireguard.android.preference

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import androidx.preference.Preference
import com.wireguard.android.R

class BatteryOptimizationPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private val exempt: Boolean
        get() = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    override fun getTitle() = context.getString(R.string.battery_opt_title)

    override fun getSummary(): CharSequence = context.getString(
        if (exempt) R.string.battery_opt_exempt else R.string.battery_opt_restricted
    )

    override fun onAttached() {
        super.onAttached()
        notifyChanged()
    }

    fun refresh() = notifyChanged()

    override fun onClick() {
        if (exempt) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some builds ship without the dialog; fall back to the list screen.
        if (runCatching { context.startActivity(intent) }.isFailure) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
