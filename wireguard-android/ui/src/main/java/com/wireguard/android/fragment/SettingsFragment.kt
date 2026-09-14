/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by Portway: promoted out of SettingsActivity so it can also be hosted as a
 * bottom-navigation destination inside MainActivity. The preference-removal logic is
 * upstream's, unchanged apart from the theme control now existing on every API level.
 */
package com.wireguard.android.fragment

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.wireguard.android.Application
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.activity.LogViewerActivity
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.preference.AccountPreference
import com.wireguard.android.preference.BatteryOptimizationPreference
import com.wireguard.android.preference.PreferencesPreferenceDataStore
import com.wireguard.android.preference.ProtectionStatusPreference
import com.wireguard.android.util.AdminKnobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    /**
     * Insets, but only when nothing above us is already doing it.
     *
     * androidx.preference never got edge-to-edge support, which is on everywhere from API 35, so
     * one of the two hosts has to inset this screen. Which one differs:
     *
     *  - [SettingsActivity] adds the fragment straight into `android.R.id.content` with nothing
     *    in between, so here the fragment is the only thing that can do it.
     *  - [MainActivity]'s root CoordinatorLayout already carries `fitsSystemWindows` — and, being
     *    a CoordinatorLayout, applies the insets to itself without CONSUMING them, so a child
     *    that also fits them applies the very same padding a second time.
     *
     * That second case is a real bug, not a theory: the fragment container already began at
     * y=296, and the preference list inside it began at y=592. It only showed itself after
     * something re-dispatched insets to an already-laid-out hierarchy — dismissing a preference
     * dialog, or the activity being recreated by a theme or language change — which is why the
     * screen looked right until the user opened a popup and then had a band of empty canvas
     * above the first row.
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view.fitsSystemWindows = activity !is MainActivity
        return view
    }

    override fun onResume() {
        super.onResume()
        // Both reflect system state the user may have just changed in another app, so they are
        // re-read on every return rather than only when the screen is built.
        (preferenceManager.findPreference("protection_status") as? ProtectionStatusPreference)?.refresh()
        (preferenceManager.findPreference("battery_optimization") as? BatteryOptimizationPreference)?.refresh()
        (preferenceManager.findPreference("account") as? AccountPreference)?.refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Portway: the rows carry their own spacing, so androidx's dividers just add noise.
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.bottom_nav_height))
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
        preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(lifecycleScope, Application.getPreferencesDataStore())
        addPreferencesFromResource(R.xml.preferences)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || QuickTileService.isAdded) {
            val quickTile = preferenceManager.findPreference<Preference>("quick_tile")
            quickTile?.parent?.removePreference(quickTile)
        }
        if (AdminKnobs.disableConfigExport) {
            val zipExporter = preferenceManager.findPreference<Preference>("zip_exporter")
            zipExporter?.parent?.removePreference(zipExporter)
        }
        val wgQuickOnlyPrefs = arrayOf(
            preferenceManager.findPreference("tools_installer"),
            preferenceManager.findPreference("restore_on_boot"),
            preferenceManager.findPreference<Preference>("multiple_tunnels")
        ).filterNotNull()
        wgQuickOnlyPrefs.forEach { it.isVisible = false }
        lifecycleScope.launch {
            if (Application.getBackend() is WgQuickBackend) {
                wgQuickOnlyPrefs.forEach { it.isVisible = true }
            } else {
                wgQuickOnlyPrefs.forEach { it.parent?.removePreference(it) }
            }
        }
        preferenceManager.findPreference<Preference>("log_viewer")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LogViewerActivity::class.java))
            true
        }
        val kernelModuleEnabler = preferenceManager.findPreference<Preference>("kernel_module_enabler")
        if (WgQuickBackend.hasKernelSupport()) {
            lifecycleScope.launch {
                if (Application.getBackend() !is WgQuickBackend) {
                    try {
                        withContext(Dispatchers.IO) { Application.getRootShell().start() }
                    } catch (_: Throwable) {
                        kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                    }
                }
            }
        } else {
            kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
        }
    }
}
