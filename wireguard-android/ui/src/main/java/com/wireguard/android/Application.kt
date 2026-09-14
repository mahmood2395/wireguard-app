/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.configStore.FileConfigStore
import com.wireguard.android.model.HandshakeWatchdog
import com.wireguard.android.model.NetworkMonitor
import com.wireguard.android.model.UsageSampler
import com.wireguard.android.model.WatchdogAlarm
import com.wireguard.android.model.TunnelManager
import com.wireguard.android.util.EndpointResolver
import com.wireguard.android.util.GeoResolver
import com.wireguard.android.util.ExpiryNotifier
import com.wireguard.android.util.RootShell
import com.wireguard.android.util.ToolsInstaller
import com.wireguard.android.util.SessionGuard
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.Locale

class Application : android.app.Application() {
    private val futureBackend = CompletableDeferred<Backend>()

    /**
     * Portway. Outcome of backend initialisation: null while the attempt is still running,
     * then success or the failure that stopped it.
     *
     * Upstream only ever completed [futureBackend] on success. When init threw — an ABI the
     * APK does not carry, a partial install, an OEM that refuses writes to the code cache —
     * the exception was logged and swallowed, so every caller of getBackend() awaited a
     * deferred that would never resolve. No crash, no message: the app simply showed a
     * spinner and an empty tunnel list for the life of the process.
     *
     * [futureBackend] is deliberately still left uncompleted on failure rather than failed:
     * roughly fifteen call sites await it bare inside launch{}, so completing it
     * exceptionally would convert that hang into a crash. Code that must not hang uses
     * [awaitBackendResult] instead, and the UI reads [backendResult] to explain itself.
     */
    private val _backendResult = MutableStateFlow<Result<Backend>?>(null)
    private var backendAttemptInFlight = false
    private val coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private lateinit var rootShell: RootShell
    private lateinit var preferencesDataStore: DataStore<Preferences>
    private lateinit var toolsInstaller: ToolsInstaller
    private lateinit var tunnelManager: TunnelManager

    @Volatile
    private var startedActivities = 0

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
        if (BuildConfig.MIN_SDK_VERSION > Build.VERSION.SDK_INT) {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            System.exit(0)
        }
    }

    private suspend fun determineBackend(): Backend {
        var backend: Backend? = null
        if (UserKnobs.enableKernelModule.first() && WgQuickBackend.hasKernelSupport()) {
            try {
                rootShell.start()
                val wgQuickBackend = WgQuickBackend(applicationContext, rootShell, toolsInstaller)
                wgQuickBackend.setMultipleTunnels(UserKnobs.multipleTunnels.first())
                backend = wgQuickBackend
                UserKnobs.multipleTunnels.onEach {
                    wgQuickBackend.setMultipleTunnels(it)
                }.launchIn(coroutineScope)
            } catch (ignored: Exception) {
            }
        }
        if (backend == null) {
            // Portway: the tunnel module has no BuildConfig of its own (its package name is a
            // build property), so the DNS fallback is handed down from here.
            GoBackend.setFallbackDns(BuildConfig.FALLBACK_DNS)
            backend = GoBackend(applicationContext)
            GoBackend.setAlwaysOnCallback { get().applicationScope.launch { get().tunnelManager.restoreState(true) } }
        }
        return backend
    }

    override fun onCreate() {
        Log.i(TAG, USER_AGENT)
        super.onCreate()
        // Portway: a cheap foreground signal, so background work can slow itself down. Counting
        // started activities needs no extra dependency (lifecycle-process is not on the
        // classpath) and is exact enough for deciding a poll interval.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { startedActivities++ }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (startedActivities > 0) startedActivities--
            }
            override fun onActivityCreated(activity: android.app.Activity, b: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, b: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // Portway: upstream applied DynamicColors here, which replaces every Material 3
        // role with the wallpaper palette on API 31+ — the brand palette never reached
        // the screen on modern devices. A branded VPN client wants its own colours.
        rootShell = RootShell(applicationContext)
        toolsInstaller = ToolsInstaller(applicationContext, rootShell)
        preferencesDataStore = PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile("settings") }
        // Portway: one path for every API level (upstream ignored the user's choice on Q+).
        // Measured at 8.6-16.5ms of a ~900ms cold start (see BUILD.md): small enough that
        // mirroring the value into SharedPreferences to avoid it would risk a theme flash for
        // no meaningful gain. Deliberately left blocking so the first frame is already correct.
        runBlocking {
            UserKnobs.migrateThemeMode()
            AppCompatDelegate.setDefaultNightMode(UserKnobs.themeMode.first().toNightMode())
        }
        UserKnobs.themeMode.onEach {
            val newMode = it.toNightMode()
            // Guarded: setDefaultNightMode recreates every activity.
            if (AppCompatDelegate.getDefaultNightMode() != newMode) {
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
        }.launchIn(coroutineScope)
        // Portway: endpoint lookups go around the device and carrier DNS caches, so a server
        // that changes address is found on the next reconnect instead of after a reboot.
        EndpointResolver.install()
        tunnelManager = TunnelManager(FileConfigStore(applicationContext))
        tunnelManager.onCreate()
        // Portway: watches for tunnels that are up but never handshake, and restarts them.
        HandshakeWatchdog.start(coroutineScope)
        // Portway: and reacts to the transport moving, which the watchdog alone would only
        // notice minutes later via a stale handshake.
        NetworkMonitor.start(coroutineScope)
        // Portway: and keeps checking while the device is idle, where the watchdog's own
        // coroutine loop is suspended by Doze.
        WatchdogAlarm.start(coroutineScope)
        // Portway: warns before a plan runs out, on its own schedule.
        ExpiryNotifier.start(coroutineScope)
        // Portway: accumulates per-day usage, which only this process can measure — the panel
        // knows the quota, not the shape of the fortnight.
        UsageSampler.start(coroutineScope)
        // Portway: one config, one device at a time — heartbeats live sessions to the panel and
        // reports which configs this install holds. See SessionGuard.
        SessionGuard.start(coroutineScope)
        // Portway: restores the cities resolved on earlier connections, so the Configs list can
        // name a peer's place before that config is the one carrying traffic.
        GeoResolver.start(coroutineScope)
        startBackendAttempt()
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(VmPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
    }

    /** Runs backend init, recording the outcome. Safe to call again after a failure. */
    private fun startBackendAttempt() {
        if (backendAttemptInFlight || _backendResult.value?.isSuccess == true) return
        backendAttemptInFlight = true
        _backendResult.value = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val newBackend = determineBackend()
                backend = newBackend
                _backendResult.value = Result.success(newBackend)
                futureBackend.complete(newBackend)
            } catch (e: Throwable) {
                Log.e(TAG, "Backend initialisation failed", e)
                _backendResult.value = Result.failure(e)
            } finally {
                backendAttemptInFlight = false
            }
        }
    }

    override fun onTerminate() {
        coroutineScope.cancel()
        super.onTerminate()
    }

    private fun UserKnobs.ThemeMode.toNightMode() = when (this) {
        UserKnobs.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        UserKnobs.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        UserKnobs.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    companion object {
        val USER_AGENT = String.format(Locale.ENGLISH, "WireGuard/%s (Android %d; %s; %s; %s %s; %s; %s)", BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown ABI", Build.BOARD, Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT, BuildConfig.APPLICATION_ID)
        private const val TAG = "WireGuard/Application"
        private lateinit var weakSelf: WeakReference<Application>

        fun get(): Application {
            return weakSelf.get()!!
        }

        /**
         * Unchanged upstream semantics: resolves only on success, so it still parks forever
         * if the backend never comes up. Prefer [awaitBackendResult] anywhere a hang would
         * be worse than an error.
         */
        suspend fun getBackend() = get().futureBackend.await()

        /** Portway. Waits for backend init to finish and reports how it went. Never hangs past the attempt. */
        suspend fun awaitBackendResult(): Result<Backend> = get()._backendResult.filterNotNull().first()

        /** Portway. Latest known backend outcome without waiting; null while an attempt is in flight. */
        val backendResult: StateFlow<Result<Backend>?> get() = get()._backendResult.asStateFlow()

        /** Portway. Re-attempt init after a failure — the failure may have been transient. */
        fun retryBackend() = get().startBackendAttempt()

        fun getRootShell() = get().rootShell

        fun getPreferencesDataStore() = get().preferencesDataStore

        fun getToolsInstaller() = get().toolsInstaller

        fun getTunnelManager() = get().tunnelManager

        fun getCoroutineScope() = get().coroutineScope

        /** True while any activity is started, i.e. the user can see the app. */
        fun isForeground() = get().startedActivities > 0
    }

    init {
        weakSelf = WeakReference(this)
    }
}
