/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * The Connect home: one tunnel, its live state, session duration and throughput.
 */
package com.wireguard.android.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.TextView
import androidx.core.view.MenuProvider
import androidx.databinding.Observable
import androidx.databinding.ObservableList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.ConnectFragmentBinding
import com.wireguard.android.databinding.ObservableSortedKeyedArrayList
import com.wireguard.android.model.HandshakeWatchdog
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.widget.ConnectRingView
import androidx.core.content.ContextCompat
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.GeoResolver
import com.wireguard.android.util.ExpiryNotifier
import com.wireguard.android.util.AccountRepository
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.util.Pinger
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.util.UpdateChecker
import com.wireguard.android.util.UpdateInstaller
import com.wireguard.android.util.UsageHistory
import com.wireguard.android.util.UserKnobs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import com.wireguard.android.util.ThroughputMeter
import com.wireguard.android.util.resolveAttribute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.wireguard.android.util.SessionGuard

class ConnectFragment : BaseFragment(), MenuProvider {
    private var binding: ConnectFragmentBinding? = null

    /**
     * The tunnel Connect is showing. Deliberately NOT BaseActivity.selectedTunnel — assigning
     * that fires onSelectedTunnelChanged, which pushes the detail fragment onto the back stack.
     */
    private var connectTunnel: ObservableTunnel? = null

    /**
     * TunnelManager.getTunnels() is suspend (the list is a CompletableDeferred that resolves
     * once configs are loaded), so cache the resolved list for the synchronous render path.
     */
    private var tunnelList: ObservableSortedKeyedArrayList<String, ObservableTunnel>? = null

    /** UI-only. The backend has no "connecting" state; State is just DOWN/UP. */
    private enum class Phase { IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    private var phase = Phase.IDLE
    private var requestInFlight = false

    /** Set while the watchdog is restarting this tunnel, so the UI does not read as a drop. */
    private var watchdogRestarting = false
    private var requestStartedAt = 0L

    // Throughput is differenced from cumulative counters; these are the last sample.


    private val throughput = ThroughputMeter()

    /** Newest handshake across peers; null until one is observed. */
    private var latestHandshakeEpochMillis: Long? = null

    /**
     * Whether the app was replaced while a tunnel was running. On its own it says nothing —
     * the notice appears only when it coincides with a tunnel that is up and not handshaking,
     * because that pairing is the signature of Android 16's post-update stack bug. Showing it
     * for an update alone would be crying wolf at every single update.
     */
    private var updatedWhileConnected = false
    private var postUpdateDismissed = false

    /**
     * When the notice became eligible, on the monotonic clock.
     *
     * Deliberately NOT the tunnel's connect time: the stalled-tunnel watchdog restarts a
     * non-handshaking tunnel every half-minute or so, and each restart resets that timestamp,
     * so a "has been up this long without handshaking" test against it can never come true on
     * exactly the tunnels this notice is about. Anchoring to our own arming time is immune to
     * that, and measures the thing we actually care about — how long the user has been staring
     * at a connection that is not working.
     */
    private var postUpdateArmedAt = 0L

    /** A newer build the panel advertised, if any. Null until the first check answers. */
    private var pendingUpdate: UpdateChecker.Available? = null
    private var updateDismissed = false
    private var updateStatus: String? = null

    /** Latest account info result for the bound tunnel; null until the first fetch lands. */
    private var accountResult: AccountRepository.Result? = null

    /** Endpoint host of the bound tunnel (lazily resolved) and the latest probe result. */
    private var endpointHost: String? = null

    /**
     * Where this config's traffic surfaces, once something has resolved it. Null means exactly
     * that — nothing resolved yet — and every readout below degrades rather than guessing.
     */
    private var place: String? = null
    private var lastPingMs: Double? = null
    private var pingInFlight = false

    /**
     * Whether this visit has already measured the round trip.
     *
     * The probe used to run every third tick for as long as Home was open — a packet to the peer
     * every three seconds, all day, for a figure that is a property of the peer rather than a
     * live meter. It now runs once when the screen comes forward and once more whenever the
     * tunnel comes up, which are the two moments the number can actually have changed.
     */
    private var pingDone = false

    /** Single-flight for the geo lookup, so a slow one does not queue a coroutine per tick. */
    private var geoInFlight = false
    private var tickCount = 0

    /**
     * Observable callbacks fire on whichever thread changed the property. Generated
     * databinding marshals to the UI thread for you; a hand-written callback does not — and
     * these touch views. The watchdog changes state from a background worker, so everything
     * here must be posted. (Symptom without this: CalledFromWrongThreadException, but only
     * ever from a non-UI-initiated state change.)
     */
    private fun onMain(block: () -> Unit) {
        val root = binding?.root ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else root.post(block)
    }

    private val tunnelStateCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (propertyId == BR.state) onMain { reconcilePhase() }
        }
    }

    private val managerCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (propertyId == BR.lastUsedTunnel) onMain { resolveTunnel() }
        }
    }

    private val listCallback = object : ObservableList.OnListChangedCallback<ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: ObservableList<ObservableTunnel>?) = onMain { resolveTunnel() }
        override fun onItemRangeChanged(s: ObservableList<ObservableTunnel>?, p: Int, c: Int) = onMain { resolveTunnel() }
        override fun onItemRangeInserted(s: ObservableList<ObservableTunnel>?, p: Int, c: Int) = onMain { resolveTunnel() }
        override fun onItemRangeMoved(s: ObservableList<ObservableTunnel>?, f: Int, t: Int, c: Int) = onMain { resolveTunnel() }
        override fun onItemRangeRemoved(s: ObservableList<ObservableTunnel>?, p: Int, c: Int) = onMain { resolveTunnel() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ConnectFragmentBinding.inflate(inflater, container, false)
        binding!!.fragment = this
        return binding!!.root
    }

    /** The days-left pill in the action bar; null until the menu is inflated. */
    private var daysChip: TextView? = null

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.connect, menu)
        daysChip = menu.findItem(R.id.menu_days_chip)?.actionView as? TextView
        daysChip?.setOnClickListener {
            (activity as? MainActivity)?.selectDestination(R.id.dest_settings)
        }
        binding?.let { renderAccount(it) }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_days_chip) return false
        (activity as? MainActivity)?.selectDestination(R.id.dest_settings)
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The design gives the 30-bar month band 3dp gaps and 1dp corners (the 14-bar band's
        // 6dp/2dp are the view's defaults). Never set, it drew noticeably thinner bars.
        binding?.monthBand?.apply {
            gapDp = MONTH_BAND_GAP_DP
            radiusDp = MONTH_BAND_RADIUS_DP
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        // repeatOnLifecycle rather than a flag + while-loop: it cancels at ON_PAUSE and
        // restarts at ON_RESUME, so two loops can never overlap and nothing polls in the
        // background. (Upstream's onResume/onStop flag pattern can double up.)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                seedThroughputBaseline()
                // Arriving on the screen is one of the two moments worth measuring.
                pingDone = false
                while (isActive) {
                    tick()
                    delay(1000)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    connectTunnel?.let { tunnel ->
                        val result = AccountRepository.fetch(tunnel)
                        if (result is AccountRepository.Result.Ok) onAccountLoaded(tunnel.name, result.info)
                        withContext(Dispatchers.Main.immediate) {
                            // A fetch can take seconds. If the user switched configs meanwhile,
                            // this answer is about the old one: dropping it is right, because
                            // bindTunnel already fetched for the new config, and applying it
                            // showed the wrong account's days and quota for up to a minute.
                            if (tunnel === connectTunnel) {
                                accountResult = result
                                render()
                            }
                        }
                    }
                    delay(60_000)
                }
            }
        }
        // Portway: a backend that never started used to be completely invisible here — the
        // screen sat in its idle state forever with no hint that connecting could not work.
        // Launched here, once per view, and not from onStart: repeatOnLifecycle already restarts
        // it at every STARTED, so launching it from onStart stacked one more collector on top of
        // the previous ones each time the app came back from the background.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Application.backendResult.collect { result ->
                    val error = result?.exceptionOrNull()
                    val binding = binding ?: return@collect
                    binding.backendErrorCard.visibility = if (error == null) View.GONE else View.VISIBLE
                    if (error != null) {
                        binding.backendErrorDetail.text = ErrorMessages[error]
                        binding.backendErrorRetry.setOnClickListener { Application.retryBackend() }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                HandshakeWatchdog.reconnecting.collect { name ->
                    watchdogRestarting = name != null && name == connectTunnel?.name
                    render()
                }
            }
        }
        // The month band is not a live figure — re-reading it when the screen comes back is
        // enough, so it collects at STARTED rather than joining the one-second tick.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsageHistory.history.collectLatest { buckets ->
                    binding?.let { renderMonth(it, buckets) }
                }
            }
        }
        // Nocturne's two routes out of Home. Both move the nav bar's selection rather than
        // swapping the fragment underneath it, or the bar would point at the wrong tab.
        binding?.locationCard?.setOnClickListener {
            (activity as? MainActivity)?.selectDestination(R.id.dest_tunnels)
        }
    }

    override fun onStart() {
        super.onStart()
        viewLifecycleOwner.lifecycleScope.launch {
            // Throttled inside the checker, so opening the app repeatedly costs one request a day.
            (UpdateChecker.check() as? UpdateChecker.Result.Update)?.let {
                pendingUpdate = it.available
                render()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            updatedWhileConnected = UserKnobs.updatedWhileConnected.first()
            if (updatedWhileConnected && postUpdateArmedAt == 0L)
                postUpdateArmedAt = SystemClock.elapsedRealtime()
        }
        Application.getTunnelManager().addOnPropertyChangedCallback(managerCallback)
        viewLifecycleOwner.lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            tunnelList = tunnels
            tunnels.addOnListChangedCallback(listCallback)
            resolveTunnel()
        }
    }

    override fun onStop() {
        // Infinite animators left running behind a backgrounded app are a battery bug.
        binding?.connectButton?.stopAnimations()
        binding?.handshakeDecay?.stopAnimations()
        Application.getTunnelManager().removeOnPropertyChangedCallback(managerCallback)
        tunnelList?.removeOnListChangedCallback(listCallback)
        bindTunnel(null)
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.connectButton?.stopAnimations()
        binding?.handshakeDecay?.stopAnimations()
        binding = null
        super.onDestroyView()
    }

    /**
     * Hand the phase to the ring and let it own its own animation.
     *
     * The old cluster drove a rotating ImageView, an inflated pulse animator and a scale on the
     * puck from here. ConnectRingView animates its own dash, spin and glow instead, so this is
     * one assignment and there is nothing left to keep in sync.
     */
    private fun applyRingPhase() {
        val ring = binding?.connectButton ?: return
        ring.phase = when {
            watchdogRestarting -> ConnectRingView.Phase.CONNECTING
            phase == Phase.CONNECTING || phase == Phase.DISCONNECTING -> ConnectRingView.Phase.CONNECTING
            phase == Phase.CONNECTED -> ConnectRingView.Phase.CONNECTED
            else -> ConnectRingView.Phase.IDLE
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        // Connect resolves its own tunnel; selection is the detail-navigation pointer.
    }

    /**
     * Which tunnel to show: whatever is up, else the last one used, else the first known.
     */
    private fun resolveTunnel() {
        val manager = Application.getTunnelManager()
        val tunnels = tunnelList ?: return
        val up = tunnels.filter { it.state == Tunnel.State.UP }
        val chosen = when {
            up.size == 1 -> up.first()
            up.size > 1 -> manager.lastUsedTunnel?.takeIf { it.state == Tunnel.State.UP } ?: up.first()
            else -> manager.lastUsedTunnel ?: tunnels.firstOrNull()
        }
        if (chosen !== connectTunnel) {
            bindTunnel(chosen)
        } else if (chosen != null && accountResult !is AccountRepository.Result.Ok) {
            // Same tunnel, but a marker may just have been imported — retry the account fetch.
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AccountRepository.fetch(chosen)
                if (result is AccountRepository.Result.Ok) onAccountLoaded(chosen.name, result.info)
                withContext(Dispatchers.Main.immediate) {
                    if (chosen === connectTunnel) {
                        accountResult = result
                        render()
                    }
                }
            }
        }
        reconcilePhase()
        render()
    }

    private fun bindTunnel(tunnel: ObservableTunnel?) {
        connectTunnel?.removeOnPropertyChangedCallback(tunnelStateCallback)
        connectTunnel = tunnel
        endpointHost = null
        place = null
        lastPingMs = null
        pingDone = false
        accountResult = null
        // The location card names the peer whether or not the tunnel is up, so the endpoint is
        // read from the config here rather than only along the connected polling path — where
        // it used to be resolved, which left the card's second line blank while disconnected.
        tunnel?.let { t ->
            viewLifecycleOwner.lifecycleScope.launch {
                val host = runCatching {
                    t.getConfigAsync().peers.firstOrNull()?.endpoint?.orElse(null)?.host
                }.getOrNull()
                if (t === connectTunnel) {
                    endpointHost = host
                    // Whatever the last connection to this peer found. Shown straight away so
                    // the caption does not flicker from an address to a city on every connect.
                    place = GeoResolver.cached(host)?.label
                    render()
                }
            }
        }
        // Show cached info instantly while the fresh fetch runs. The comment always said so, but
        // nothing read the cache: onStop unbinds and nulls the result, so the days chip and quota
        // rail vanished on every return to Home until the panel answered again.
        tunnel?.let { t ->
            viewLifecycleOwner.lifecycleScope.launch {
                AccountRepository.cached(t)?.let { info ->
                    if (t === connectTunnel && accountResult == null) {
                        accountResult = AccountRepository.Result.Ok(info)
                        render()
                    }
                }
                val result = AccountRepository.fetch(t)
                if (result is AccountRepository.Result.Ok) onAccountLoaded(t.name, result.info)
                withContext(Dispatchers.Main.immediate) {
                    if (t === connectTunnel) {
                        accountResult = result
                        render()
                    }
                }
            }
        }
        binding?.tunnel = tunnel
        tunnel?.addOnPropertyChangedCallback(tunnelStateCallback)
        seedThroughputBaseline()
    }

    /** tunnel.state is the truth except while our own request is still in flight. */
    private fun reconcilePhase() {
        if (requestInFlight) return
        phase = when (connectTunnel?.state) {
            Tunnel.State.UP -> Phase.CONNECTED
            else -> Phase.IDLE
        }
        render()
    }

    fun onConnectClicked(view: View) {
        val tunnel = connectTunnel ?: return
        val goingUp = tunnel.state != Tunnel.State.UP
        requestInFlight = true
        requestStartedAt = SystemClock.elapsedRealtime()
        phase = if (goingUp) Phase.CONNECTING else Phase.DISCONNECTING
        render()
        setTunnelState(view, goingUp)
    }

    // Import launchers for the empty-state "add tunnel" flow — same handling as
    // TunnelListFragment's FAB. Must be property initializers: registerForActivityResult
    // throws if called after the fragment reaches STARTED.
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val result = QrCodeFromFileScanner(contentResolver, QRCodeReader()).scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showImportSnackbar(it) }
                } catch (e: Exception) {
                    val message = Application.get().resources.getString(R.string.import_error, ErrorMessages[e])
                    Log.e(TAG, message, e)
                    showImportSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showImportSnackbar(it) }
            }
        }
    }

    /**
     * Asked for once, the first time an account actually loads — not at first launch.
     *
     * A permission prompt before the app has anything to notify about is noise, and a denial
     * there is sticky. By the time an account card appears we know this is a subscriber with a
     * plan that will one day run out, which is the only thing we ever notify about.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var askedForNotifications = false

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showImportSnackbar(it) } }
        }
    }

    private fun showImportSnackbar(message: CharSequence) {
        val view = binding?.root ?: return
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
    }

    fun onAddTunnelClicked(@Suppress("UNUSED_PARAMETER") view: View) {
        // The sheet answers on the FragmentManager it was shown from, so the listener and
        // show() must use the same one — showing on parentFragmentManager with no listener
        // there is exactly the bug that made this button a silent no-op.
        if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null) return
        childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                AddTunnelsSheet.REQUEST_CREATE ->
                    startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))

                AddTunnelsSheet.REQUEST_IMPORT -> tunnelFileImportResultLauncher.launch("*/*")

                AddTunnelsSheet.REQUEST_SCAN -> qrImportResultLauncher.launch(
                    ScanOptions()
                        .setOrientationLocked(false)
                        .setBeepEnabled(false)
                        .setPrompt(getString(R.string.qr_code_hint))
                )
            }
        }
        AddTunnelsSheet().showNow(childFragmentManager, "BOTTOM_SHEET")
    }

    override fun onTunnelStateChangeStarting(tunnel: ObservableTunnel, requestedUp: Boolean) {
        requestInFlight = true
        requestStartedAt = SystemClock.elapsedRealtime()
        phase = if (requestedUp) Phase.CONNECTING else Phase.DISCONNECTING
        render()
    }

    override fun onTunnelStateChangeFinished(tunnel: ObservableTunnel, requestedUp: Boolean, error: Throwable?) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Hold CONNECTING briefly: the backend can return fast enough that the
            // transition would read as a flicker rather than a state change.
            val elapsed = SystemClock.elapsedRealtime() - requestStartedAt
            if (elapsed < MIN_PHASE_DWELL_MS) delay(MIN_PHASE_DWELL_MS - elapsed)
            requestInFlight = false
            // An account in use elsewhere is a question for the user, not a failure: skip the red
            // ERROR dwell and settle straight back, so the dialog opens over a calm "Off".
            if (error != null && error !is SessionGuard.AccountInUseException) {
                phase = Phase.ERROR
                binding?.statusLabel?.text = ErrorMessages[error]
                render()
                delay(ERROR_DWELL_MS)
            }
            reconcilePhase()
        }
    }

    private fun seedThroughputBaseline() {
        throughput.reset()
        latestHandshakeEpochMillis = null
    }

    private suspend fun tick() {
        tickCount++
        val tunnel = connectTunnel
        if (tunnel == null || tunnel.state != Tunnel.State.UP) {
            lastPingMs = null
            // Coming back up is the other moment worth measuring, so arm the probe on the way down.
            pingDone = false
            render()
            return
        }
        // One probe per visit, and one per connection. Single-flight as well — a stalled probe
        // must not stack behind itself.
        if (!pingDone && !pingInFlight) {
            if (endpointHost == null) {
                endpointHost = runCatching {
                    tunnel.getConfigAsync().peers.firstOrNull()?.endpoint?.orElse(null)?.host
                }.getOrNull()
            }
            // Marked done only once there is a host to probe — the config can still be loading
            // on the first tick, and claiming the probe was made would leave the caption on an
            // em dash for the rest of the visit.
            endpointHost?.let { host ->
                pingDone = true
                pingInFlight = true
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val result = Pinger.ping(host)
                        lastPingMs = result
                    } finally {
                        pingInFlight = false
                    }
                    render()
                }
            }
        }
        // Geography, once per connection. ensureResolved is single-flight and remembers the
        // session, so calling it every tick costs one lookup per connect — and it refuses to
        // ask anyone at all unless it can bind the request to the tunnel.
        if (!geoInFlight) {
            geoInFlight = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    GeoResolver.ensureResolved(tunnel)
                } finally {
                    geoInFlight = false
                }
                val resolved = GeoResolver.cached(endpointHost)?.label
                if (resolved != place && tunnel === connectTunnel) {
                    place = resolved
                    render()
                }
            }
        }
        // getStatisticsAsync, never the @Bindable getter: that getter launches a refetch
        // as a side effect, which would double the IPC rate.
        val stats = runCatching { tunnel.getStatisticsAsync() }.getOrNull()
        if (stats != null) {
            throughput.sample(stats.totalRx(), stats.totalTx(), SystemClock.elapsedRealtime())
            latestHandshakeEpochMillis = stats.peers()
                .mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis }
                .filter { it > 0 }
                .maxOrNull()
        }
        render()
    }

    /**
     * The self-update prompt.
     *
     * A mandatory update cannot be dismissed — that is the whole point of the panel naming a
     * minimum supported version — so the Later button disappears rather than being disabled,
     * which would only invite tapping it.
     */
    /**
     * An account was fetched successfully: warn if it is close to running out, and take the one
     * opportunity to ask for the permission that makes warning possible.
     */
    private suspend fun onAccountLoaded(tunnelName: String, info: AccountRepository.AccountInfo) {
        val context = context ?: return
        if (!askedForNotifications && !ExpiryNotifier.canNotify(context) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            askedForNotifications = true
            withContext(Dispatchers.Main.immediate) {
                runCatching { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        ExpiryNotifier.consider(tunnelName, info)
    }

    /**
     * The decay bar's one input: how old the latest handshake is, or null for the silent state.
     *
     * Null covers both "not connected" and "connected but never handshaked" — from the bar's
     * point of view those are the same picture, a full track in the disconnected ink, and the
     * second is exactly the case the watchdog is about to act on.
     */
    private fun renderHandshake(binding: ConnectFragmentBinding) {
        val tunnel = connectTunnel
        val latest = latestHandshakeEpochMillis
        binding.handshakeDecay.setState(
            connected = tunnel?.state == Tunnel.State.UP,
            ageSeconds = latest?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) },
            connectedForSeconds = tunnel?.connectedSinceElapsedRealtime
                ?.let { (SystemClock.elapsedRealtime() - it) / 1000L },
        )
    }

    private fun renderUpdate(binding: ConnectFragmentBinding) {
        val update = pendingUpdate
        val show = update != null && (update.mandatory || !updateDismissed)
        binding.updateCard.visibility = if (show) View.VISIBLE else View.GONE
        if (update == null || !show) return

        binding.updateTitle.text = getString(R.string.update_available, update.versionName)
        binding.updateDetail.text = updateStatus
            ?: when {
                update.mandatory -> getString(R.string.update_required)
                !update.notes.isNullOrBlank() -> update.notes
                else -> getString(R.string.update_disconnect_note)
            }
        binding.updateLater.visibility = if (update.mandatory) View.GONE else View.VISIBLE
        binding.updateLater.setOnClickListener {
            updateDismissed = true
            render()
        }
        binding.updateAction.isEnabled = !UpdateInstaller.inProgress
        binding.updateAction.setOnClickListener {
            val context = context ?: return@setOnClickListener
            if (!UpdateInstaller.canInstall(context)) {
                // Nothing can proceed until this is granted, so send them straight to it
                // rather than failing with a message they cannot act on.
                updateStatus = getString(R.string.update_permission_needed)
                render()
                UpdateInstaller.requestInstallPermission(context)
                return@setOnClickListener
            }
            updateStatus = getString(R.string.update_downloading)
            render()
            viewLifecycleOwner.lifecycleScope.launch {
                when (val outcome = UpdateInstaller.downloadAndInstall(context, update)) {
                    is UpdateInstaller.Outcome.Failed -> updateStatus = getString(R.string.update_failed, outcome.reason)
                    is UpdateInstaller.Outcome.NeedsPermission -> updateStatus = getString(R.string.update_permission_needed)
                    is UpdateInstaller.Outcome.HandedOff -> updateStatus = null
                }
                render()
            }
        }
    }

    /**
     * The account, as Nocturne places it on Home: a days-left pill in the header, and the plan
     * rail under the month band. The full card lives in Settings.
     *
     * Both halves hide when the panel has not answered. A pill reading "— days left" or a rail
     * at an invented fraction is worse than no pill and no rail: the user cannot tell "we could
     * not reach the panel" from "your plan is in trouble".
     */
    private fun renderAccount(binding: ConnectFragmentBinding) {
        val info = (accountResult as? AccountRepository.Result.Ok)?.info
        val days = info?.daysLeft
        daysChip?.visibility = if (days != null) View.VISIBLE else View.GONE
        if (days != null) daysChip?.text = getString(R.string.days_left_chip, days)

        // The rail needs a denominator, and only the panel can supply one.
        val used = info?.totalBytes
        val quota = info?.quotaBytes
        if (used == null || quota == null || quota <= 0) {
            binding.quotaRail.visibility = View.GONE
            binding.quotaLine.visibility = View.GONE
            return
        }
        binding.quotaRail.visibility = View.VISIBLE
        binding.quotaRail.progress = ((used.toDouble() / quota) * 1000).toInt().coerceIn(0, 1000)
        val remaining = (quota - used).coerceAtLeast(0)
        val expiry = info.expiry
        binding.quotaLine.visibility = View.VISIBLE
        binding.quotaLine.text =
            if (expiry.isNullOrBlank()) getString(R.string.month_used, QuantityFormatter.formatBytes(used))
            else getString(R.string.quota_renews, expiry, QuantityFormatter.formatBytes(remaining))
    }

    /**
     * The month band. The bars are what this device measured; the "of 30 GB" half of the
     * readout only appears when the panel states a quota.
     */
    private fun renderMonth(binding: ConnectFragmentBinding, buckets: Map<Long, Long>) {
        binding.monthBand.setSeries(UsageHistory.series(buckets, UsageHistory.MONTH_DAYS))
        val used = UsageHistory.total(buckets, UsageHistory.MONTH_DAYS)
        val quota = (accountResult as? AccountRepository.Result.Ok)?.info?.quotaBytes
        binding.monthTotal.text =
            if (quota != null) getString(R.string.month_of, QuantityFormatter.formatBytes(used), QuantityFormatter.formatBytes(quota))
            else getString(R.string.month_used, QuantityFormatter.formatBytes(used))
        val oldest = UsageHistory.midnightsBack(UsageHistory.MONTH_DAYS).last()
        binding.monthAxisStart.text = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(oldest))
    }

    private fun render() {
        val binding = binding ?: return
        val context = context ?: return
        val tunnel = connectTunnel
        val hasTunnel = tunnel != null

        // Nocturne's kicker states the security fact rather than the transport state: what a
        // user wants from the top of the screen is whether they are covered, not whether a
        // socket is up. The layout uppercases it, so these stay sentence case as strings.
        binding.statusLabel.text = when {
            !hasTunnel -> getString(R.string.connect_no_tunnels)
            watchdogRestarting -> getString(R.string.connect_reconnecting)
            phase == Phase.CONNECTING -> getString(R.string.hero_connecting)
            phase == Phase.DISCONNECTING -> getString(R.string.hero_disconnecting)
            phase == Phase.ERROR -> binding.statusLabel.text
            phase == Phase.CONNECTED -> getString(R.string.hero_protected)
            else -> getString(R.string.hero_not_protected)
        }
        // The prototype paints the kicker accent while covered or working towards it, and muted
        // otherwise — the kicker is the accent's job on this screen, not the type's.
        binding.statusLabel.setTextColor(
            when {
                watchdogRestarting -> context.resolveAttribute(R.attr.statusConnectingColor)
                else -> null
            } ?: when (phase) {
                Phase.CONNECTING -> ContextCompat.getColor(context, R.color.accent)
                Phase.CONNECTED -> ContextCompat.getColor(context, R.color.accent)
                Phase.ERROR -> context.resolveAttribute(androidx.appcompat.R.attr.colorError)
                else -> ContextCompat.getColor(context, R.color.clay_text_muted)
            }
        )

        val connected = phase == Phase.CONNECTED
        applyRingPhase()

        binding.connectButton.isEnabled = hasTunnel && !requestInFlight
        // The ring stays drawn with no config: its idle state is an empty track, and removing
        // it would leave the kicker and the figure floating in the middle of the screen with
        // nothing around them.
        binding.emptyAction.visibility = if (hasTunnel) View.GONE else View.VISIBLE

        // The app's primary control is a custom card with an icon inside, so it carries no
        // text for a screen reader to read — TalkBack announced an unlabelled button. The
        // label has to be set here rather than in XML because it depends on what the tap will
        // actually do, which is the only useful thing to announce.
        val name = connectTunnel?.name.orEmpty()
        binding.connectButton.contentDescription = when (phase) {
            Phase.CONNECTING -> getString(R.string.a11y_connecting)
            Phase.DISCONNECTING -> getString(R.string.a11y_disconnecting)
            Phase.CONNECTED -> getString(R.string.a11y_disconnect, name)
            else -> getString(R.string.a11y_connect, name)
        }

        renderUpdate(binding)

        // Up long enough to have handshaked, and hasn't. Paired with an update that landed
        // mid-session, that is the Android 16 symptom rather than an ordinary bad connection.
        val armedFor = if (postUpdateArmedAt == 0L) 0L else SystemClock.elapsedRealtime() - postUpdateArmedAt
        val stalled = connectTunnel?.state == Tunnel.State.UP &&
            armedFor > POST_UPDATE_GRACE_MS &&
            latestHandshakeEpochMillis == null
        binding.postUpdateCard.visibility =
            if (updatedWhileConnected && stalled && !postUpdateDismissed) View.VISIBLE else View.GONE
        binding.postUpdateDismiss.setOnClickListener {
            postUpdateDismissed = true
            binding.postUpdateCard.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch { UserKnobs.setUpdatedWhileConnected(false) }
        }
        // A handshake means the stack is fine after all, so the flag has done its job.
        if (updatedWhileConnected && latestHandshakeEpochMillis != null) {
            updatedWhileConnected = false
            viewLifecycleOwner.lifecycleScope.launch { UserKnobs.setUpdatedWhileConnected(false) }
        }
        binding.tunnelName.text = tunnel?.name ?: getString(R.string.connect_no_tunnels_hint)

        val since = tunnel?.connectedSinceElapsedRealtime
        // The ring's figure is the session when there is one, an ellipsis while a request is in
        // flight, and "Off" otherwise — the prototype never leaves the centre of the ring empty.
        binding.sessionTimer.text = when {
            connected && since != null -> QuantityFormatter.formatDuration((SystemClock.elapsedRealtime() - since) / 1000)
            phase == Phase.CONNECTING || phase == Phase.DISCONNECTING -> getString(R.string.hero_busy_figure)
            connected -> getString(R.string.hero_connected_figure)
            else -> getString(R.string.hero_off_figure)
        }

        // A latency when the probe answered, otherwise nothing. Home never says "Timeout": many
        // working servers answer neither ICMP nor TCP 443/80, and a permanent alarm under a working
        // connection is worse than the plain host name the caption falls back to. A failed probe
        // is reported where it belongs, in the detail screen's HEALTH table.
        val pingText = lastPingMs?.let { getString(R.string.ping_ms, it.toInt()) }
        // Nocturne puts the place and the latency in the ring's caption: "Frankfurt, DE · 42 ms".
        // Each half is optional and neither is invented — with no city it is the latency alone,
        // and with neither it is the peer's host, never a repeat of the kicker directly above.
        val here = place
        binding.ringCaption.text = when {
            !hasTunnel -> getString(R.string.connect_no_tunnels_hint)
            connected && here != null && pingText != null -> getString(R.string.location_dot, here, pingText)
            connected && here != null -> here
            connected -> pingText ?: endpointHost.orEmpty()
            // While the tunnel is coming up there is no latency to state, but a place learned on
            // an earlier connection is still the place this one is headed.
            (phase == Phase.CONNECTING || phase == Phase.DISCONNECTING) && here != null -> here
            phase == Phase.CONNECTING || phase == Phase.DISCONNECTING -> getString(R.string.ring_caption_connecting)
            else -> getString(R.string.ring_caption_idle)
        }

        binding.heroSub.text = when {
            !hasTunnel -> getString(R.string.hero_sub_no_tunnels)
            phase == Phase.CONNECTING -> getString(R.string.hero_sub_connecting)
            phase == Phase.DISCONNECTING -> getString(R.string.hero_sub_disconnecting)
            connected && here != null -> getString(R.string.hero_sub_connected_geo, here)
            connected -> getString(R.string.hero_sub_connected)
            else -> getString(R.string.hero_sub_idle)
        }

        // Nothing below the rule means anything without a config, so the whole lower half of
        // the page goes rather than standing there empty.
        binding.heroRule.visibility = if (hasTunnel) View.VISIBLE else View.GONE
        binding.locationCard.visibility = if (hasTunnel) View.VISIBLE else View.GONE
        binding.monthBlock.visibility = if (hasTunnel) View.VISIBLE else View.GONE
        binding.statsRow.visibility = if (connected) View.VISIBLE else View.GONE
        // Not gated on `connected`: the bar has a designed silent state — a full track in the
        // disconnected ink reading "last handshake over three minutes ago" — and hiding it
        // there would throw away the one state the user most needs to see.
        binding.handshakeDecay.visibility = if (hasTunnel) View.VISIBLE else View.GONE

        // "Frankfurt, DE · from 5.9.44.12" once the place is known; the bare peer until then.
        binding.locationLine.text = endpointHost?.let { host ->
            if (here != null) getString(R.string.location_geo_from, here, host)
            else getString(R.string.location_from, host)
        }.orEmpty()

        renderAccount(binding)
        renderHandshake(binding)
        binding.rxRate.text = QuantityFormatter.formatBytesPerSecond(throughput.rxPerSecond)
        binding.txRate.text = QuantityFormatter.formatBytesPerSecond(throughput.txPerSecond)
    }

    companion object {
        private const val TAG = "WireGuard/ConnectFragment"
        private const val MONTH_BAND_GAP_DP = 3f
        private const val MONTH_BAND_RADIUS_DP = 1f

        /** Long enough that an ordinary slow first handshake is not mistaken for the bug. */
        private const val POST_UPDATE_GRACE_MS = 45_000L
        private const val MIN_PHASE_DWELL_MS = 450L
        private const val ERROR_DWELL_MS = 2000L
    }
}
