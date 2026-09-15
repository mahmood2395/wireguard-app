/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Nocturne turned this from a naming prompt into an import confirmation: it now says what the
 * config does — where it connects, what it routes, who resolves DNS — before saving it.
 *
 * Still the same class, the same arguments and the same entry point. Every import path in the
 * app (deep link, QR, file, zip) reaches this through TunnelImporter.importTunnel, so replacing
 * it with a new destination would have meant changing all of them in one go.
 *
 * The primary action saves and does not connect, despite the design calling it "Save and
 * connect". Bringing a tunnel up needs the VpnService consent dialog, and the machinery that
 * asks for it lives in BaseFragment/BaseActivity, not in a DialogFragment; wiring a second,
 * partial copy of it here would be the kind of duplicate that goes wrong quietly. The ring on
 * the Connect screen does it properly, one tap later.
 */
package com.wireguard.android.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.util.GeoResolver
import com.wireguard.android.databinding.ConfigNamingDialogFragmentBinding
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class ConfigNamingDialogFragment : DialogFragment() {
    private var binding: ConfigNamingDialogFragmentBinding? = null
    private var config: Config? = null

    private fun createTunnelAndDismiss() {
        val binding = binding ?: return
        val activity = activity ?: return
        val name = binding.tunnelNameText.text.toString()
        activity.lifecycleScope.launch {
            try {
                Application.getTunnelManager().create(name, config)
                dismiss()
            } catch (e: Throwable) {
                binding.tunnelNameTextLayout.error = e.message
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configText = requireArguments().getString(KEY_CONFIG_TEXT)
        val configBytes = configText!!.toByteArray(StandardCharsets.UTF_8)
        config = try {
            Config.parse(ByteArrayInputStream(configBytes))
        } catch (e: Throwable) {
            when (e) {
                is BadConfigException, is IOException -> throw IllegalArgumentException("Invalid config passed to ${javaClass.simpleName}", e)
                else -> throw e
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        // No builder title and no builder buttons: the layout carries both, and a platform
        // title bar above this content would state the same thing twice in two type scales.
        val alertDialogBuilder = MaterialAlertDialogBuilder(activity)
        binding = ConfigNamingDialogFragmentBinding.inflate(activity.layoutInflater, null, false)
        binding?.apply {
            // Portway fork: a deep link may carry the tunnel name, so prefill it.
            val suggested = arguments?.getString(KEY_SUGGESTED_NAME)
            suggested?.let { tunnelNameText.setText(it) }
            describeConfig(this, suggested)
            confirmSave.setOnClickListener { createTunnelAndDismiss() }
            confirmCancel.setOnClickListener { dismiss() }
            executePendingBindings()
            alertDialogBuilder.setView(root)
        }
        val dialog = alertDialogBuilder.create()
        // Upstream raised the keyboard immediately, when the field was the only thing on the
        // dialog. It is not any more: the keyboard covers the summary and both buttons, so the
        // user would have to dismiss it to read what they are about to save.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        return dialog
    }

    /**
     * The three facts a non-technical person can act on. Each row hides itself when the config
     * does not state that field, rather than showing a blank or a guess.
     */
    private fun describeConfig(binding: ConfigNamingDialogFragmentBinding, suggested: String?) {
        val config = config ?: return
        binding.confirmTitle.text = getString(R.string.confirm_ready, suggested ?: getString(R.string.tunnel_name))

        val peers = config.peers
        val peerCount = resources.getQuantityString(R.plurals.confirm_peers, peers.size, peers.size)
        // "All traffic" means a default route is present; anything else is a split tunnel, and
        // the difference is the single most consequential thing about a config.
        val allowed = peers.flatMap { it.allowedIps }
        val routesEverything = allowed.any { it.mask == 0 }
        // The place, only when this peer's endpoint is already in the cache — which means a
        // previous connection to it resolved one. Nothing is looked up here: an import happens
        // with this config down, and a lookup off-tunnel is the one that carries the user's own
        // address. Where there is no cached place the sentence simply does not mention a city.
        val place = GeoResolver.cached(peers.firstOrNull()?.endpoint?.orElse(null)?.host)?.label
        binding.confirmSubtitle.text = if (place != null) getString(
            if (routesEverything) R.string.confirm_subtitle_all_geo else R.string.confirm_subtitle_split_geo,
            peerCount, place
        ) else getString(
            if (routesEverything) R.string.confirm_subtitle_all else R.string.confirm_subtitle_split,
            peerCount
        )
        binding.confirmRoutes.text =
            if (routesEverything) getString(R.string.confirm_routes_all)
            else getString(R.string.confirm_routes_some, allowed.size)

        val endpoint = peers.firstOrNull()?.endpoint?.orElse(null)?.toString()
        binding.confirmEndpoint.text = endpoint.orEmpty()
        binding.confirmEndpointRow.visibility = if (endpoint == null) View.GONE else View.VISIBLE

        val dns = config.`interface`.dnsServers.joinToString(", ") { it.hostAddress ?: it.toString() }
        binding.confirmDns.text = dns
        binding.confirmDnsRow.visibility = if (dns.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val KEY_CONFIG_TEXT = "config_text"
        private const val KEY_SUGGESTED_NAME = "suggested_name"

        fun newInstance(configText: String?, suggestedName: String? = null): ConfigNamingDialogFragment {
            val extras = Bundle()
            extras.putString(KEY_CONFIG_TEXT, configText)
            extras.putString(KEY_SUGGESTED_NAME, suggestedName)
            val fragment = ConfigNamingDialogFragment()
            fragment.arguments = extras
            return fragment
        }
    }
}
