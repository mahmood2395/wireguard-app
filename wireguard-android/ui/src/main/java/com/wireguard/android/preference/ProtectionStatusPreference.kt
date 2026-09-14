/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Points at Android's always-on VPN and its "block connections without VPN"
 * lockdown — the two protections this app does not own and cannot switch on.
 *
 * Why this is informational and does NOT report on/off: there is no API that answers "is
 * always-on configured for this app". `VpnService.isAlwaysOn()` reports whether the CURRENT
 * SESSION was started by the always-on mechanism, which is false for any tunnel the user
 * started by hand — verified on a device with always-on and lockdown both genuinely enabled,
 * where it still read false. The backend's wrapper additionally does a zero-timeout get on the
 * VpnService future, so it throws outright whenever no tunnel is up. Rendering either of those
 * as "Off" would tell the user they are unprotected at exactly the moment they are checking
 * whether they are, so this states what the settings do and takes them there in one tap.
 *
 * The discoverability gap is the real problem anyway: both settings work correctly, and
 * neither is mentioned anywhere in the app. Always-on is also the only way a non-rooted device
 * gets its tunnel back after a reboot, which nothing else tells the user.
 */
package com.wireguard.android.preference

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.AttributeSet
import androidx.preference.Preference
import com.wireguard.android.R

class ProtectionStatusPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun getTitle() = context.getString(R.string.protection_title)

    override fun getSummary(): CharSequence = context.getString(R.string.protection_unknown)

    /** Kept so the settings screen can call it uniformly; there is no state to re-read. */
    fun refresh() = notifyChanged()

    override fun onClick() {
        // ACTION_VPN_SETTINGS is where both switches live; there is no API to set them.
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
