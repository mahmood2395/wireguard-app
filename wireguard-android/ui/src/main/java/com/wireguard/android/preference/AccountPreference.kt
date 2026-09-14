/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. The account card at the top of Settings: who this device is on the panel,
 * how much it has moved, and how long it has left.
 *
 * A Preference rather than a header view above the RecyclerView, because androidx-preference
 * owns that list and a header bolted on outside it scrolls independently of the rows — the card
 * would sit still while the settings slid under it.
 *
 * It hides itself whenever the panel cannot answer. Deliberately: an account card that says
 * "unknown" is worse than no card, because the user cannot tell "we could not reach the panel"
 * from "your plan has a problem".
 *
 * The rail appears only when the panel states a quota (`quota_bytes` / `plan_bytes` /
 * `limit_bytes`); /api/peer/info has always returned a running total, never a ceiling. A bar
 * with an invented denominator would be the one thing on this screen a user might act on, and
 * it would be made up — so with no quota there is a used figure and no bar.
 */
package com.wireguard.android.preference

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.util.AccountRepository
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.util.lifecycleScope
import kotlinx.coroutines.launch

class AccountPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var info: AccountRepository.AccountInfo? = null
    private var fetched = false

    init {
        layoutResource = R.layout.preference_account
        isSelectable = false
        // Hidden until the panel answers, so the screen never shows an empty card mid-fetch.
        isVisible = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        if (!fetched) {
            fetched = true
            refresh()
        }
        val account = info ?: return
        holder.itemView.findViewById<TextView>(R.id.account_pref_name).text =
            account.name ?: context.getString(R.string.app_name)
        val plan = holder.itemView.findViewById<TextView>(R.id.account_pref_plan)
        // The badge says the worst true thing: a suspended account is a suspended account
        // whatever its plan is called.
        plan.text = when {
            account.disabled -> context.getString(R.string.account_suspended)
            (account.daysLeft ?: 1) <= 0 -> context.getString(R.string.account_expired)
            else -> account.plan.orEmpty()
        }
        plan.visibility = if (plan.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        val usage = holder.itemView.findViewById<TextView>(R.id.account_pref_usage)
        usage.text = account.totalBytes?.let {
            context.getString(R.string.account_usage_value, QuantityFormatter.formatBytes(it))
        }.orEmpty()

        val rail = holder.itemView.findViewById<ProgressBar>(R.id.account_pref_rail)
        val used = account.totalBytes
        val quota = account.quotaBytes
        if (used != null && quota != null && quota > 0) {
            rail.visibility = View.VISIBLE
            rail.progress = ((used.toDouble() / quota) * 1000).toInt().coerceIn(0, 1000)
            // With a quota known, the plan line states it — "30 GB plan" — rather than repeating
            // whatever name the panel gave the tier.
            if (!account.disabled && (account.daysLeft ?: 1) > 0) {
                plan.text = context.getString(R.string.account_plan_size, QuantityFormatter.formatBytes(quota))
                plan.visibility = View.VISIBLE
            }
        } else {
            rail.visibility = View.GONE
        }

        val days = holder.itemView.findViewById<TextView>(R.id.account_pref_days)
        days.text = account.daysLeft?.let { context.getString(R.string.account_days_value, it) }.orEmpty()
    }

    /** Re-read on every return to the screen; a plan can be renewed while the app is open. */
    fun refresh() {
        lifecycleScope.launch {
            val manager = Application.getTunnelManager()
            val tunnels = runCatching { manager.getTunnels() }.getOrNull() ?: return@launch
            // Whichever config the user actually uses. The panel answers per public key, so
            // there is no single account to ask about — only the account behind a config.
            val tunnel = manager.lastUsedTunnel ?: tunnels.firstOrNull() ?: return@launch
            val result = runCatching { AccountRepository.fetch(tunnel) }.getOrNull()
            if (result is AccountRepository.Result.Ok) {
                info = result.info
                isVisible = true
                notifyChanged()
            } else {
                isVisible = false
            }
        }
    }
}
