/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application.Companion.get
import com.wireguard.android.Application.Companion.awaitBackendResult
import com.wireguard.android.Application.Companion.getBackend
import com.wireguard.android.Application.Companion.getTunnelManager
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.configStore.ConfigStore
import com.wireguard.android.databinding.ObservableSortedKeyedArrayList
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.AccountRepository
import com.wireguard.android.util.DisconnectReasons
import com.wireguard.android.util.SessionGuard
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    private val tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false

    /**
     * Serialises every path that drives the backend.
     *
     * Five entry points can toggle a tunnel — the connect screen, the quick-settings tile, the
     * toggle shortcut, the TV activity and the remote-control broadcast — and the connect
     * screen guards only against itself. GoBackend mutates currentTunnel / currentTunnelHandle
     * with no lock of its own, so two toggles landing together could both observe DOWN, both
     * call establish(), and leak a native handle while the tracked state pointed at a file
     * descriptor that was no longer alive.
     *
     * Suspending rather than blocking, so holding it across a ~1s establish() parks the
     * coroutine instead of the thread. NOT reentrant: nothing guarded here may call another
     * guarded function.
     */
    private val backendMutex = Mutex()

    /**
     * Tunnels whose teardown right now must not release their session. Added on Main, read from
     * the backend's IO thread inside the same call, so concurrent.
     */
    private val releaseSuppressed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The tunnel currently being brought up, if any. See onBackendStateChange. */
    @Volatile
    private var bringingUp: String? = null

    /**
     * Portway. Bumped on every requested tunnel state change, from any entry point. The
     * handshake watchdog snapshots it around its own down/up restart so it can tell whether
     * someone — the user, the quick tile, always-on — changed their mind mid-restart.
     */
    var stateChangeGeneration = 0L
        private set

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel> = tunnels.await()

    /**
     * Whether anything is up, WITHOUT awaiting [tunnels].
     *
     * Deliberately not `getTunnels().any { … }`: restoreState() runs inside onTunnelsLoaded
     * *before* the deferred is completed, and it calls setTunnelState, which reschedules the
     * watchdog alarm. Awaiting the deferred from there deadlocks the whole load — the tunnel
     * list never resolves and the app shows "No tunnels yet" forever with a tunnel on disk.
     * The backing map is already populated by then, so read it directly.
     */
    fun hasTunnelUp(): Boolean = tunnelMap.any { it.state == Tunnel.State.UP }

    suspend fun create(name: String, config: Config?): ObservableTunnel = withContext(Dispatchers.Main.immediate) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        addToList(name, withContext(Dispatchers.IO) { configStore.create(name, config!!) }, Tunnel.State.DOWN)
            .also { SessionGuard.onImported(it) }
    }

    suspend fun delete(tunnel: ObservableTunnel) = withContext(Dispatchers.Main.immediate) {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        try {
            if (originalState == Tunnel.State.UP)
                backendMutex.withLock { withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) } }
            try {
                withContext(Dispatchers.IO) { configStore.delete(tunnel.name) }
            } catch (e: Throwable) {
                if (originalState == Tunnel.State.UP)
                    withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }
                throw e
            }
        } catch (e: Throwable) {
            // Failure, put the tunnel back.
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel
            throw e
        }
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel? = null
        private set(value) {
            if (value == field) return
            field = value
            notifyPropertyChanged(BR.lastUsedTunnel)
            applicationScope.launch { UserKnobs.setLastUsedTunnel(value?.name) }
        }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        tunnel.onConfigChanged(withContext(Dispatchers.IO) { configStore.load(tunnel.name) })!!
    }

    fun onCreate() {
        applicationScope.launch {
            // Portway: upstream awaited the config store and the backend in one expression and
            // let any failure fall into a bare log. Because `tunnels` is only completed inside
            // onTunnelsLoaded, a backend that never arrived meant every caller of getTunnels()
            // — the connect screen, the list, the quick tile — parked forever on an empty view.
            // The config store needs no backend, so enumeration still happens; a missing backend
            // only costs us the knowledge of which tunnels were already up.
            val present = try {
                withContext(Dispatchers.IO) { configStore.enumerate() }
            } catch (e: Throwable) {
                Log.e(TAG, "Could not enumerate stored tunnels", e)
                emptyList<String>()
            }
            val running = awaitBackendResult().fold(
                onSuccess = { backend -> runCatching { withContext(Dispatchers.IO) { backend.runningTunnelNames } }.getOrDefault(emptySet()) },
                onFailure = { emptySet() }
            )
            onTunnelsLoaded(present, running)
        }
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        applicationScope.launch {
            val lastUsedName = UserKnobs.lastUsedTunnel.first()
            if (lastUsedName != null)
                lastUsedTunnel = tunnelMap[lastUsedName]
            haveLoaded = true
            restoreState(true)
            tunnels.complete(tunnelMap)
        }
    }

    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
                for (tunnel in tunnelMap)
                    tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    suspend fun restoreState(force: Boolean) {
        if (!haveLoaded || (!force && !UserKnobs.restoreOnBoot.first()))
            return
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        // A clean disconnect empties this set, so whatever is still in it and not actually up
        // ended with the process — killed in the background, or a reboot. The one cause that
        // cannot be observed as it happens, and the one users complain about most.
        DisconnectReasons.recordKilled(
            tunnelMap.filter { previouslyRunning.contains(it.name) && it.state != Tunnel.State.UP }.map { it.name }
        )
        withContext(Dispatchers.IO) {
            try {
                tunnelMap.filter { previouslyRunning.contains(it.name) }.map { async(Dispatchers.IO + SupervisorJob()) { setTunnelState(it, Tunnel.State.UP, SessionGuard.Gate.ADVISORY) } }
                    .awaitAll()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    /**
     * A state change that originated in the backend rather than in a request we made — the
     * user revoking VPN permission, another VPN taking over, or the system killing the
     * service.
     *
     * Upstream routed those straight to the tunnel object, bypassing the manager. Since
     * saveState() was then only reached from setTunnelState, the persisted running-tunnels set kept
     * listing a tunnel the system had already torn down, and the next process start silently
     * tried to reconnect it — a tunnel coming back for no visible reason, or failing to and
     * saying nothing.
     */
    fun onBackendStateChange(tunnel: ObservableTunnel, newState: Tunnel.State) {
        // Portway: the one-device session is released here, where the backend reports EVERY
        // teardown — not in setTunnelState, which only sees the tunnel it was asked about.
        // Connecting config B makes the backend take config A down on its own, and A's session
        // used to stay claimed until the panel's lease ran out, so another device trying A was
        // told it was "already connected" here for minutes. Read before onStateChanged, so
        // tunnel.state is still the state being left.
        if (newState == Tunnel.State.DOWN && tunnel.state == Tunnel.State.UP) {
            val replacing = bringingUp?.takeIf { it != tunnel.name } != null
            // Taken here, once and synchronously, so the store and the panel report the same
            // cause; see DisconnectReasons.take.
            val reason = DisconnectReasons.take(
                tunnel.name,
                if (replacing) DisconnectReasons.Reason.REPLACED else DisconnectReasons.Reason.SYSTEM,
            )
            applicationScope.launch { DisconnectReasons.persist(tunnel.name, reason) }
            if (tunnel.name !in releaseSuppressed) SessionGuard.afterDown(tunnel, SessionGuard.Gate.USER, reason)
        }
        tunnel.onStateChanged(newState)
        applicationScope.launch { saveState() }
        WatchdogAlarm.reschedule()
    }

    suspend fun saveState() {
        UserKnobs.setRunningTunnels(tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())
    }

    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config = withContext(Dispatchers.Main.immediate) {
        // Reconfiguring a live tunnel tears it down and brings it back inside the backend, so
        // it contends with setTunnelState for exactly the same state.
        backendMutex.withLock {
            tunnel.onConfigChanged(withContext(Dispatchers.IO) {
                getBackend().setState(tunnel, tunnel.state, config)
                configStore.save(tunnel.name, config)
            })!!
        }.also {
            // The key may have changed, and editing is the other moment a user would expect the
            // panel to be asked again about a config it once disowned.
            AccountRepository.forgetForeign(tunnel)
        }
    }

    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = withContext(Dispatchers.Main.immediate) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        var throwable: Throwable? = null
        var newName: String? = null
        try {
            if (originalState == Tunnel.State.UP)
                backendMutex.withLock { withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) } }
            withContext(Dispatchers.IO) { configStore.rename(tunnel.name, name) }
            newName = tunnel.onNameChanged(name)
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }
        } catch (e: Throwable) {
            throwable = e
            // On failure, we don't know what state the tunnel might be in. Fix that.
            getTunnelState(tunnel)
        }
        // Add the tunnel back to the manager, under whatever name it thinks it has.
        tunnelMap.add(tunnel)
        if (wasLastUsed)
            lastUsedTunnel = tunnel
        if (throwable != null)
            throw throwable
        newName!!
    }

    /**
     * @param gate who is asking, which decides whether the one-device-at-a-time check may refuse.
     *             USER by default so a new entry point is checked unless it opts out; see
     *             SessionGuard.Gate for what each value is for.
     * @param takeover claim the session even if another device holds it ("Use here instead").
     */
    suspend fun setTunnelState(
        tunnel: ObservableTunnel,
        state: Tunnel.State,
        gate: SessionGuard.Gate = SessionGuard.Gate.USER,
        takeover: Boolean = false,
    ): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        val wasUp = tunnel.state == Tunnel.State.UP
        val goingUp = !wasUp && (state == Tunnel.State.UP || state == Tunnel.State.TOGGLE)
        // Before the lock: it is a network round trip, and may throw AccountInUseException.
        if (goingUp) SessionGuard.beforeUp(tunnel, gate, takeover)
        // Gate.NONE state changes (a watchdog restart, the updater, a superseded disconnect) must
        // not release the session when the backend reports the teardown; see onBackendStateChange.
        // Whoever asks for a teardown says why before it happens; see DisconnectReasons. Gate.NONE
        // callers (watchdog, updater, a superseded session) declare their own and are left alone.
        if (wasUp && !goingUp && gate != SessionGuard.Gate.NONE) {
            DisconnectReasons.expect(
                tunnel.name,
                if (gate == SessionGuard.Gate.USER) DisconnectReasons.Reason.USER
                else DisconnectReasons.Reason.SYSTEM,
            )
        }
        val suppress = gate == SessionGuard.Gate.NONE
        // Any change may take a running tunnel down — this one, or another the backend stops to
        // make room — and its byte counters go with it. Count what they hold first. Outside the
        // lock: it is a statistics round trip per running tunnel.
        if (hasTunnelUp()) UsageSampler.flush()
        // While this is in flight, a teardown of ANOTHER tunnel is the backend making room for it.
        if (goingUp) bringingUp = tunnel.name
        if (suppress) releaseSuppressed.add(tunnel.name)
        try {
        backendMutex.withLock {
        stateChangeGeneration++
        var newState = tunnel.state
        var throwable: Throwable? = null
        try {
            // Portway: fail loudly rather than park. getBackend() only resolves on success, so
            // a device whose backend never initialised would hang here with the UI stuck mid
            // "connecting" instead of telling the user the engine could not start.
            val backend = awaitBackendResult().getOrThrow()
            newState = withContext(Dispatchers.IO) { backend.setState(tunnel, state, tunnel.getConfigAsync()) }
            if (newState == Tunnel.State.UP)
                lastUsedTunnel = tunnel
        } catch (e: Throwable) {
            throwable = e
        }
        tunnel.onStateChanged(newState)
        saveState()
        // Portway: the while-idle watchdog alarm only exists while something is up.
        WatchdogAlarm.reschedule()
        if (throwable != null)
            throw throwable
        newState
        }
        } finally {
            if (suppress) releaseSuppressed.remove(tunnel.name)
            if (goingUp) bringingUp = null
            // A teardown that never happened must not label the next one.
            if (wasUp && !goingUp) DisconnectReasons.forget(tunnel.name)
        }
    }

    class IntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                val manager = getTunnelManager()
                if (intent == null) return@launch
                val action = intent.action ?: return@launch
                if ("com.wireguard.android.action.REFRESH_TUNNEL_STATES" == action) {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (!UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state = when (action) {
                    "com.wireguard.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "com.wireguard.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                    else -> return@launch
                }
                val tunnelName = intent.getStringExtra("tunnel") ?: return@launch
                val tunnels = manager.getTunnels()
                val tunnel = tunnels[tunnelName] ?: return@launch
                try {
                    manager.setTunnelState(tunnel, state)
                } catch (e: SessionGuard.AccountInUseException) {
                    SessionGuard.notifyConflict(e.tunnelName, e.otherDevice)
                } catch (e: Throwable) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main.immediate) {
        tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) })!!
    }

    companion object {
        private const val TAG = "WireGuard/TunnelManager"
    }
}
