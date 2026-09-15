/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Nocturne. Upstream's config listing is still here in full, but behind a disclosure; what sits
 * above it is the live picture — session, throughput, handshake rhythm, fourteen days of usage
 * and the health rows. The peer-row updating below is upstream's, unchanged.
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.GeoResolver
import com.wireguard.android.util.Pinger
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.util.ThroughputMeter
import com.wireguard.android.util.UsageHistory
import com.wireguard.android.util.resolveAttribute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment that shows details about a specific tunnel.
 */
class TunnelDetailFragment : BaseFragment() {
    private var binding: TunnelDetailFragmentBinding? = null
    private val throughput = ThroughputMeter()

    /** Collapsed on open, every time. A disclosure that remembers is a disclosure you forget. */
    private var configOpen = false

    private var endpointHost: String? = null
    private var lastPingMs: Double? = null
    private var pingFailures = 0
    private var pingInFlight = false

    /** Whether this visit has already measured the round trip. See [probePing]. */
    private var pingDone = false

    /** Where this peer's traffic surfaces, once resolved; null means nothing resolved it yet. */
    private var place: String? = null
    private var geoInFlight = false
    private var tickCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelDetailFragmentBinding.inflate(inflater, container, false)
        binding?.executePendingBindings()
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // No action-bar Edit: the actions row below carries Edit beside Connect, as the prototype
        // has it, and a header pencil as well gave the screen two ways to do one thing.
        binding?.configDisclosure?.setOnClickListener { toggleConfig() }
        // The prototype's actions row. Connect/Disconnect goes through BaseFragment so the VPN
        // consent dialog is raised exactly where every other toggle raises it; Edit and Remove
        // reuse the action-bar items rather than growing a second implementation.
        binding?.detailToggle?.setOnClickListener {
            val tunnel = binding?.tunnel ?: return@setOnClickListener
            setTunnelState(it, tunnel.state != Tunnel.State.UP)
        }
        binding?.detailEdit?.setOnClickListener { (activity as? MainActivity)?.openEditor() }
        binding?.detailRemove?.setOnClickListener { confirmRemove() }
        renderUsageAxis()

        // STARTED, not RESUMED: the band is not a live number, and re-reading it once when the
        // screen comes back is enough.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsageHistory.history.collectLatest { buckets ->
                    binding?.usageBand?.setSeries(UsageHistory.series(buckets, UsageHistory.FORTNIGHT_DAYS))
                }
            }
        }
        // Portway: repeatOnLifecycle cancels at pause and restarts at resume. The previous
        // onResume/flag/onStop pattern could leave two loops running if onResume fired twice.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                throughput.reset()
                while (isActive) {
                    tick()
                    delay(1000)
                }
            }
        }
    }

    override fun onDestroyView() {
        // The decay bar's fill animator outlives the view otherwise.
        binding?.detailHandshakeDecay?.stopAnimations()
        binding = null
        super.onDestroyView()
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        // Everything derived from the old tunnel is wrong for the new one, and a stale endpoint
        // would have this screen pinging a host it is no longer showing.
        endpointHost = null
        place = null
        pingDone = false
        lastPingMs = null
        pingFailures = 0
        throughput.reset()
        if (newTunnel == null) {
            binding.config = null
        } else {
            lifecycleScope.launch {
                try {
                    binding.config = newTunnel.getConfigAsync()
                } catch (_: Throwable) {
                    binding.config = null
                }
            }
        }
        lifecycleScope.launch { tick() }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        binding ?: return
        binding!!.fragment = this
        onSelectedTunnelChanged(null, selectedTunnel)
        super.onViewStateRestored(savedInstanceState)
    }

    /**
     * Expanding animates a 16dp rise; collapsing does not. Motion on the way in earns its keep
     * by showing where the content came from; motion on the way out just delays the screen.
     */
    private fun toggleConfig() {
        val binding = binding ?: return
        configOpen = !configOpen
        binding.configDisclosureHint.setText(if (configOpen) R.string.detail_hide else R.string.detail_show)
        if (!configOpen) {
            binding.configBody.visibility = View.GONE
            return
        }
        binding.configBody.apply {
            alpha = 0f
            translationY = 16f * resources.displayMetrics.density
            visibility = View.VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(300).start()
        }
    }

    /**
     * Removal asks first. It is the one irreversible action on this screen and the design puts
     * it as a plain line of text, which is easy to hit by accident on the way past.
     */
    private fun confirmRemove() {
        val tunnel = binding?.tunnel ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_remove_confirm, tunnel.name))
            .setMessage(R.string.detail_remove_detail)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    runCatching { tunnel.deleteAsync() }
                    // The screen is about the config that no longer exists.
                    activity?.onBackPressedDispatcher?.onBackPressed()
                }
            }
            .show()
    }

    private fun renderUsageAxis() {
        val binding = binding ?: return
        val format = SimpleDateFormat("d MMM", Locale.getDefault())
        val midnights = UsageHistory.midnightsBack(UsageHistory.FORTNIGHT_DAYS)
        binding.usageAxisStart.text = format.format(Date(midnights.last()))
        binding.usageAxisEnd.text = format.format(Date(midnights.first()))
    }

    private suspend fun tick() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        tickCount++
        val up = tunnel.state == Tunnel.State.UP
        if (!up) {
            lastPingMs = null
            pingDone = false
            pingFailures = 0
            throughput.reset()
            render(binding, tunnel, null)
            return
        }
        probePing(tunnel)
        resolvePlace(tunnel)
        // getStatisticsAsync, never the @Bindable getter: that getter launches a refetch as a
        // side effect, which would double the IPC rate.
        val statistics = runCatching { tunnel.getStatisticsAsync() }.getOrNull()
        if (statistics != null) {
            throughput.sample(statistics.totalRx(), statistics.totalTx(), SystemClock.elapsedRealtime())
        }
        render(binding, tunnel, statistics)
    }

    /**
     * One probe per visit, single-flight: a stalled probe must not stack behind itself.
     *
     * This used to fire every third tick for as long as the screen was open. A round-trip time is
     * a property of the peer, not a live meter — the lane above it is what shows liveness — so it
     * is measured when the user opens the screen and left alone after that.
     */
    private suspend fun probePing(tunnel: ObservableTunnel) {
        if (pingDone || pingInFlight) return
        if (endpointHost == null) {
            endpointHost = runCatching {
                tunnel.getConfigAsync().peers.firstOrNull()?.endpoint?.orElse(null)?.host
            }.getOrNull()
        }
        val host = endpointHost ?: return
        pingDone = true
        pingInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = Pinger.ping(host)
                lastPingMs = result
                pingFailures = if (result == null) pingFailures + 1 else 0
            } finally {
                pingInFlight = false
            }
        }
    }

    /**
     * The place, resolved once per connection and only ever from here — GeoResolver refuses to
     * ask anything unless it can bind the request to the tunnel, so opening this screen on a
     * config that is down never sends a packet on that config's behalf.
     */
    private fun resolvePlace(tunnel: ObservableTunnel) {
        if (geoInFlight) return
        geoInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                GeoResolver.ensureResolved(tunnel)
            } finally {
                geoInFlight = false
            }
        }
    }

    private fun latestHandshake(statistics: Statistics): Long? = statistics.peers()
        .mapNotNull { statistics.peer(it)?.latestHandshakeEpochMillis }
        .filter { it > 0 }
        .maxOrNull()

    private fun render(binding: TunnelDetailFragmentBinding, tunnel: ObservableTunnel, statistics: Statistics?) {
        val context = context ?: return
        val up = tunnel.state == Tunnel.State.UP
        binding.statsStatus.setText(if (up) R.string.connect_connected else R.string.connect_disconnected)
        // Read fresh every render: the lookup lands asynchronously, and the cache is where
        // both this screen and Home get the same answer from.
        place = GeoResolver.cached(endpointHost)?.label
        val here = place
        binding.detailSentence.text = when {
            up && here != null -> getString(R.string.detail_sentence_connected_geo, tunnel.name, here)
            up -> getString(R.string.detail_sentence_connected, tunnel.name)
            else -> getString(R.string.detail_sentence_down)
        }
        // Never a guess: an unresolved peer reads as an em dash, exactly like an unmeasured ping.
        binding.detailLocationValue.text = here ?: getString(R.string.ping_none)

        val since = tunnel.connectedSinceElapsedRealtime
        // INVISIBLE rather than GONE: the block below it must not jump when a session starts.
        binding.detailTimer.visibility = if (up && since != null) View.VISIBLE else View.INVISIBLE
        if (up && since != null)
            binding.detailTimer.text = QuantityFormatter.formatDuration((SystemClock.elapsedRealtime() - since) / 1000)

        binding.statsRxRate.text = QuantityFormatter.formatBytesPerSecond(throughput.rxPerSecond)
        binding.statsTxRate.text = QuantityFormatter.formatBytesPerSecond(throughput.txPerSecond)
        binding.statsRxTotal.text = getString(R.string.detail_session_total, QuantityFormatter.formatBytes(statistics?.totalRx() ?: 0))
        binding.statsTxTotal.text = getString(R.string.detail_session_total, QuantityFormatter.formatBytes(statistics?.totalTx() ?: 0))

        // The three peer-derived rows the disclosure states. Not databound: peers is a list, and
        // an expression on peers[0] throws at inflate time when a config has none.
        val peer = binding.config?.peers?.firstOrNull()
        binding.allowedIpsText.text = peer?.allowedIps?.joinToString(", ").orEmpty()
        val keepalive = peer?.persistentKeepalive?.orElse(null)
        binding.keepaliveText.text =
            if (keepalive == null) getString(R.string.detail_keepalive_off)
            else getString(R.string.detail_keepalive_seconds, keepalive)

        binding.detailToggle.setText(if (up) R.string.detail_disconnect else R.string.detail_connect)
        // Connect is the accent action; disconnecting is not something to invite, so it drops to
        // the quiet hairline — the prototype swaps both the border and the label colour.
        binding.detailToggle.setTextColor(
            ContextCompat.getColor(context, if (up) R.color.clay_text_muted else R.color.accent_300)
        )
        binding.detailToggle.strokeColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, if (up) R.color.clay_stroke else R.color.accent)
        )

        binding.detailEndpointValue.text = endpointHost?.let { host ->
            val port = runCatching { binding.config?.peers?.firstOrNull()?.endpoint?.orElse(null)?.port }.getOrNull()
            if (port != null) "$host:$port" else host
        } ?: getString(R.string.ping_none)

        val ping = lastPingMs
        // Three-way readout: a live value; nothing yet while probes are still trying; a declared
        // Timeout once several in a row have gone unanswered.
        val timedOut = ping == null && pingFailures >= PING_TIMEOUT_AFTER
        binding.detailPingValue.text = when {
            timedOut -> getString(R.string.ping_timeout)
            ping == null -> getString(R.string.ping_none)
            else -> getString(R.string.ping_ms, ping.toInt())
        }
        val pingColor = when {
            timedOut -> ContextCompat.getColor(context, R.color.ping_fail)
            ping == null -> ContextCompat.getColor(context, R.color.clay_text_muted)
            ping < 80 -> ContextCompat.getColor(context, R.color.ping_ok)
            ping < 200 -> context.resolveAttribute(R.attr.statusConnectingColor)
            else -> context.resolveAttribute(androidx.appcompat.R.attr.colorError)
        }
        binding.detailPingValue.setTextColor(pingColor)
        // mutate(): the oval drawable's constant state is shared with every other dot in the
        // app — an unmutated tint recolours them all.
        binding.detailPingDot.background.mutate().setTint(pingColor)
        binding.detailPingDot.alpha = if (ping == null && !timedOut) 0.25f else 1f

        renderHandshake(binding, statistics, up)
    }

    /**
     * The decay bar, fed the age of the latest handshake and nothing else. Null is the silent
     * state, which covers both "down" and "up but never handshaked" — the same picture, and the
     * second is the case the watchdog is about to act on.
     */
    private fun renderHandshake(binding: TunnelDetailFragmentBinding, statistics: Statistics?, up: Boolean) {
        val latest = statistics?.let { latestHandshake(it) }
        binding.detailHandshakeDecay.setState(
            connected = up,
            ageSeconds = latest?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) },
            connectedForSeconds = binding.tunnel?.connectedSinceElapsedRealtime
                ?.let { (SystemClock.elapsedRealtime() - it) / 1000L },
        )
    }

    private companion object {
        /**
         * One: probing is a single burst per visit (ICMP, then TCP 443, then TCP 80), so its failure
         * IS the answer. At three the counter was reset before it could ever get there, and a
         * failed probe read "—", exactly like one that had not run yet.
         */
        const val PING_TIMEOUT_AFTER = 1
    }
}
