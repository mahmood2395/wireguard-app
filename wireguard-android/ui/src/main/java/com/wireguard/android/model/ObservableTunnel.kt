/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.util.Log
import androidx.databinding.BaseObservable
import android.os.SystemClock
import androidx.databinding.Bindable
import com.wireguard.android.BR
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.Keyed
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import com.wireguard.android.util.SessionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates the volatile and nonvolatile state of a WireGuard tunnel.
 */
class ObservableTunnel internal constructor(
    private val manager: TunnelManager,
    private var name: String,
    config: Config?,
    state: Tunnel.State
) : BaseObservable(), Keyed<String>, Tunnel {
    override val key
        get() = name

    @Bindable
    override fun getName() = name

    suspend fun setNameAsync(name: String): String = withContext(Dispatchers.Main.immediate) {
        if (name != this@ObservableTunnel.name)
            manager.setTunnelName(this@ObservableTunnel, name)
        else
            this@ObservableTunnel.name
    }

    fun onNameChanged(name: String): String {
        this.name = name
        notifyPropertyChanged(BR.name)
        return name
    }


    @get:Bindable
    var state = state
        private set

    override fun onStateChange(newState: Tunnel.State) {
        // Through the manager, not straight to onStateChanged: the manager is what persists
        // the running-tunnels set, and a teardown the backend performed on its own must not
        // leave that set claiming this tunnel is still up.
        manager.onBackendStateChange(this, newState)
    }

    /**
     * Portway: monotonic timestamp of when this tunnel came up, or null when it is down.
     * There is no such timestamp anywhere in the backend — the latest handshake is a rekey
     * (roughly every two minutes), not the session start. Deliberately NOT @Bindable: the
     * Connect screen ticks it itself once a second rather than invalidating a binding.
     *
     * Known gap: tunnels already running when the process starts are constructed without
     * passing through here, so they report "connected" with no duration.
     */
    var connectedSinceElapsedRealtime: Long? = null
        private set

    /** Portway: last ICMP RTT to this tunnel's endpoint, or null when unknown/lost. */
    @get:Bindable
    var pingMillis: Double? = null
        private set

    enum class PingState { IDLE, PROBING, OK, FAILED }

    @get:Bindable
    var pingState: PingState = PingState.IDLE
        private set

    /** Must be invoked on the main thread — databinding notification is not marshalled. */
    fun onPingStarted() {
        pingState = PingState.PROBING
        notifyPropertyChanged(BR.pingState)
    }

    /**
     * Portway: this peer's endpoint host, and the place its traffic surfaces in ("Frankfurt, DE").
     *
     * Both live here rather than in the fragment because the Configs list needs them per row, and
     * databinding is what keeps a row honest when either arrives after the row was bound. Neither
     * is ever derived from the other: [geoLabel] is null until something actually resolved it —
     * see [com.wireguard.android.util.GeoResolver], which only asks while this tunnel is up.
     */
    @get:Bindable
    var endpointHost: String? = null
        private set

    @get:Bindable
    var geoLabel: String? = null
        private set

    /** Must be invoked on the main thread — databinding notification is not marshalled. */
    fun onPeerLocated(host: String?, geoLabel: String?) {
        if (host == this.endpointHost && geoLabel == this.geoLabel) return
        this.endpointHost = host
        this.geoLabel = geoLabel
        notifyPropertyChanged(BR.endpointHost)
        notifyPropertyChanged(BR.geoLabel)
    }

    /** Must be invoked on the main thread — databinding notification is not marshalled. */
    fun onPingResult(ms: Double?) {
        pingMillis = ms
        pingState = if (ms != null) PingState.OK else PingState.FAILED
        notifyPropertyChanged(BR.pingMillis)
        notifyPropertyChanged(BR.pingState)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.UP) onStatisticsChanged(null)
        // This is the only place `state` is ever assigned, so every path that can bring a
        // tunnel up — the UI, the quick tile, always-on, boot restore, the intent receiver —
        // is covered by stamping it here.
        connectedSinceElapsedRealtime = when {
            state == Tunnel.State.UP && this.state != Tunnel.State.UP -> SystemClock.elapsedRealtime()
            state != Tunnel.State.UP -> null
            else -> connectedSinceElapsedRealtime
        }
        this.state = state
        notifyPropertyChanged(BR.state)
        return state
    }

    suspend fun setStateAsync(
        state: Tunnel.State,
        gate: SessionGuard.Gate = SessionGuard.Gate.USER,
        takeover: Boolean = false,
    ): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        if (state != this@ObservableTunnel.state)
            manager.setTunnelState(this@ObservableTunnel, state, gate, takeover)
        else
            this@ObservableTunnel.state
    }


    /**
     * WARNING: same side effect as [statistics] — a null value launches a background load.
     * Read it once or bind it; use [getConfigAsync] when the intent is actually to fetch.
     */
    @get:Bindable
    var config = config
        get() {
            if (field == null)
            // Opportunistically fetch this if we don't have a cached one, and rely on data bindings to update it eventually
                applicationScope.launch {
                    try {
                        manager.getTunnelConfig(this@ObservableTunnel)
                    } catch (e: Throwable) {
                        Log.e(TAG, Log.getStackTraceString(e))
                    }
                }
            return field
        }
        private set

    suspend fun getConfigAsync(): Config = withContext(Dispatchers.Main.immediate) {
        config ?: manager.getTunnelConfig(this@ObservableTunnel)
    }

    suspend fun setConfigAsync(config: Config): Config = withContext(Dispatchers.Main.immediate) {
        this@ObservableTunnel.config.let {
            if (config != it)
                manager.setTunnelConfig(this@ObservableTunnel, config)
            else
                it
        }
    }

    fun onConfigChanged(config: Config?): Config? {
        this.config = config
        notifyPropertyChanged(BR.config)
        return config
    }


    /**
     * WARNING: reading this property has a side effect — a stale or absent value launches a
     * background refetch. That is deliberate for databinding, which re-reads on notification
     * and eventually settles, but it makes the getter unsafe anywhere it is read repeatedly.
     * Bind it from a layout or read it once; a RecyclerView adapter, a polling loop or a
     * `@{tunnel.statistics}` expression evaluated per row will spam the backend instead.
     *
     * Use [getStatisticsAsync] for anything that fetches on purpose. Every current caller
     * already does; this note exists so that stays true.
     */
    @get:Bindable
    var statistics: Statistics? = null
        get() {
            if (field == null || field?.isStale != false)
            // Opportunistically fetch this if we don't have a cached one, and rely on data bindings to update it eventually
                applicationScope.launch {
                    try {
                        manager.getTunnelStatistics(this@ObservableTunnel)
                    } catch (e: Throwable) {
                        Log.e(TAG, Log.getStackTraceString(e))
                    }
                }
            return field
        }
        private set

    suspend fun getStatisticsAsync(): Statistics = withContext(Dispatchers.Main.immediate) {
        statistics.let {
            if (it == null || it.isStale)
                manager.getTunnelStatistics(this@ObservableTunnel)
            else
                it
        }
    }

    fun onStatisticsChanged(statistics: Statistics?): Statistics? {
        this.statistics = statistics
        notifyPropertyChanged(BR.statistics)
        return statistics
    }


    suspend fun deleteAsync() = manager.delete(this)


    companion object {
        private const val TAG = "WireGuard/ObservableTunnel"
    }
}
