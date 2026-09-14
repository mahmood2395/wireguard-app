/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.content.Context
import android.content.ContextWrapper
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.wireguard.android.Application
import kotlinx.coroutines.CoroutineScope

fun Context.resolveAttribute(@AttrRes attrRes: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attrRes, typedValue, true)
    return typedValue.data
}

val Any.applicationScope: CoroutineScope
    get() = Application.getCoroutineScope()

/**
 * Portway: upstream hard-cast a Preference's context to SettingsActivity, which made it
 * impossible to host the preference screen anywhere else — it threw on the first frame.
 * Preferences are now hosted inside MainActivity too, so resolve any FragmentActivity,
 * unwrapping the ContextThemeWrapper that androidx-preference inflates with.
 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

val Preference.activity: FragmentActivity
    get() = context.findFragmentActivity()
        ?: throw IllegalStateException("Preference is not hosted by a FragmentActivity")

/**
 * Falls back to the application scope rather than throwing: a preference whose work
 * outlives its host is better than a crash on a screen the user can reach from two places.
 */
val Preference.lifecycleScope: CoroutineScope
    get() = context.findFragmentActivity()?.lifecycleScope ?: Application.getCoroutineScope()
