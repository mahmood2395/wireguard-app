/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object UserKnobs {
    private val ENABLE_KERNEL_MODULE = booleanPreferencesKey("enable_kernel_module")
    val enableKernelModule: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ENABLE_KERNEL_MODULE] ?: false
        }

    suspend fun setEnableKernelModule(enable: Boolean?) {
        Application.getPreferencesDataStore().edit {
            if (enable == null)
                it.remove(ENABLE_KERNEL_MODULE)
            else
                it[ENABLE_KERNEL_MODULE] = enable
        }
    }

    /**
     * Portway: whether the three-step introduction has been shown.
     *
     * Written explicitly when onboarding ends, never inferred from "does the user have a
     * tunnel" — someone who imports a config from a link before ever opening the app would
     * otherwise never see it, and someone who deletes their last config would see it again.
     */
    private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    val onboardingComplete: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ONBOARDING_COMPLETE] ?: false
        }

    suspend fun setOnboardingComplete(complete: Boolean) {
        Application.getPreferencesDataStore().edit { it[ONBOARDING_COMPLETE] = complete }
    }

    private val MULTIPLE_TUNNELS = booleanPreferencesKey("multiple_tunnels")
    val multipleTunnels: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[MULTIPLE_TUNNELS] ?: false
        }

    /**
     * Portway: the theme is a tri-state applied on every API level. Upstream had a
     * boolean that was silently ignored on API 29+ (hard-coded follow-system, and the
     * preference was removed from the settings screen), so most users had no control
     * at all. Aurora Dark is the designed-for theme, hence DARK is the default.
     */
    enum class ThemeMode { DARK, LIGHT, SYSTEM }

    private val DARK_THEME = booleanPreferencesKey("dark_theme")
    private val THEME_MODE = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode>
        get() = Application.getPreferencesDataStore().data.map { prefs ->
            prefs[THEME_MODE]?.let { stored ->
                ThemeMode.entries.firstOrNull { it.name == stored }
            } ?: legacyThemeMode(prefs[DARK_THEME])
        }

    /**
     * One-time read of the old key. `true` genuinely meant dark; `false` only meant light
     * below API 29 — at and above it the value was ignored and the user got follow-system,
     * so SYSTEM is the honest reading of a stored `false`.
     */
    private fun legacyThemeMode(legacy: Boolean?): ThemeMode = when (legacy) {
        true -> ThemeMode.DARK
        false -> ThemeMode.SYSTEM
        null -> ThemeMode.DARK
    }

    /**
     * Writes the effective theme once, on first run, so the stored value and the applied value
     * always agree. Without this the key stays absent, the preference screen persists whatever
     * it resolves on first bind, and the app silently ends up on a different mode than the
     * documented default.
     */
    suspend fun migrateThemeMode() {
        Application.getPreferencesDataStore().edit {
            if (it[THEME_MODE] == null) it[THEME_MODE] = legacyThemeMode(it[DARK_THEME]).name
        }
    }

    /**
     * Portway: base URL of the management panel. The baked-in BuildConfig default means the
     * feature works out of the box; a stored value (typed in Settings, or pushed remotely by
     * the panel via the info response's panel_url field) overrides it.
     */
    private val PANEL_URL = stringPreferencesKey("panel_url")
    val panelUrl: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            // A blank value means "unset", same as setPanelUrl treats it. The Settings field
            // writes through PreferenceDataStore, which stores an emptied field as "" rather
            // than removing the key — without this, clearing the field to get back to the
            // built-in panel instead disabled the panel outright: no account info, no updater,
            // no endpoint hint, no one-device session check, and nothing to say why.
            it[PANEL_URL]?.takeIf { url -> url.isNotBlank() }
                ?: BuildConfig.PANEL_URL.takeIf { url -> url.isNotBlank() }
        }

    suspend fun setPanelUrl(url: String?) {
        Application.getPreferencesDataStore().edit {
            if (url.isNullOrBlank()) it.remove(PANEL_URL) else it[PANEL_URL] = url.trim()
        }
    }

    /** Portway: restart a tunnel that is up but not handshaking. See HandshakeWatchdog. */
    private val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    val autoReconnect: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[AUTO_RECONNECT] ?: true
        }

    /**
     * Portway: set when the app was replaced while a tunnel was running.
     *
     * Android 16 can corrupt the network stack when a VPN app updates with its VPN active —
     * the tunnel comes back looking connected but carries no traffic, and only a reboot or
     * reinstall clears it. Reported to Google around September 2025 and still unfixed. It hits
     * us harder than a Play Store app because users install our APK by hand, at whatever moment
     * they like, very plausibly while connected. We cannot prevent it; recording it lets the
     * app explain the symptom instead of leaving the user to conclude the VPN is broken.
     */
    private val UPDATED_WHILE_CONNECTED = booleanPreferencesKey("updated_while_connected")
    val updatedWhileConnected: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[UPDATED_WHILE_CONNECTED] ?: false
        }

    suspend fun setUpdatedWhileConnected(value: Boolean) {
        Application.getPreferencesDataStore().edit {
            if (value) it[UPDATED_WHILE_CONNECTED] = true else it.remove(UPDATED_WHILE_CONNECTED)
        }
    }

    /**
     * Portway: the advertised version code we last handed to the package installer.
     *
     * Exists to break a loop that is otherwise invisible from the client: if a published APK
     * does not actually contain the version its manifest claims, installing it changes nothing,
     * the manifest still advertises the higher number, and the card returns forever. Seen in
     * production when a panel's version field was overridden by hand to 520 while the file was
     * a 519 build.
     */
    private val LAST_ATTEMPTED_UPDATE = intPreferencesKey("last_attempted_update")
    val lastAttemptedUpdate: Flow<Int>
        get() = Application.getPreferencesDataStore().data.map { it[LAST_ATTEMPTED_UPDATE] ?: 0 }

    suspend fun setLastAttemptedUpdate(versionCode: Int) {
        Application.getPreferencesDataStore().edit { it[LAST_ATTEMPTED_UPDATE] = versionCode }
    }

    /**
     * Which expiry stage each config was last warned about, as "tunnelName:stage" entries (tunnel
     * names cannot contain ':'). The stage is the day count, or "suspended".
     *
     * Keyed on the day count rather than a timestamp, so the user hears once at three days, once at
     * two, once at one and once on the day itself — never twice for the same number, however often
     * the account is refetched. Cleared when the count rises back above the threshold, so a renewal
     * re-arms the whole sequence. Per config since 2026-09-15: a single global marker was cleared
     * by whichever config was checked next, so an expiring config was warned again on every
     * twice-daily check that also looked at a healthy one.
     */
    private val EXPIRY_NOTICES = stringSetPreferencesKey("expiry_notices")
    private val LEGACY_LAST_EXPIRY_NOTICE = stringPreferencesKey("last_expiry_notice")

    suspend fun expiryNoticeFor(tunnelName: String): String? =
        Application.getPreferencesDataStore().data.first()[EXPIRY_NOTICES]
            ?.firstOrNull { it.substringBefore(':') == tunnelName }
            ?.substringAfter(':')

    suspend fun setExpiryNotice(tunnelName: String, stage: String?) {
        Application.getPreferencesDataStore().edit {
            val others = (it[EXPIRY_NOTICES] ?: emptySet()).filterNot { m -> m.substringBefore(':') == tunnelName }.toSet()
            it[EXPIRY_NOTICES] = if (stage == null) others else others + "$tunnelName:$stage"
            it.remove(LEGACY_LAST_EXPIRY_NOTICE)
        }
    }

    private val ALLOW_REMOTE_CONTROL_INTENTS = booleanPreferencesKey("allow_remote_control_intents")
    val allowRemoteControlIntents: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ALLOW_REMOTE_CONTROL_INTENTS] ?: false
        }

    private val RESTORE_ON_BOOT = booleanPreferencesKey("restore_on_boot")
    val restoreOnBoot: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[RESTORE_ON_BOOT] ?: false
        }

    private val LAST_USED_TUNNEL = stringPreferencesKey("last_used_tunnel")
    val lastUsedTunnel: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            it[LAST_USED_TUNNEL]
        }

    suspend fun setLastUsedTunnel(lastUsedTunnel: String?) {
        Application.getPreferencesDataStore().edit {
            if (lastUsedTunnel == null)
                it.remove(LAST_USED_TUNNEL)
            else
                it[LAST_USED_TUNNEL] = lastUsedTunnel
        }
    }

    private val RUNNING_TUNNELS = stringSetPreferencesKey("enabled_configs")
    val runningTunnels: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map {
            it[RUNNING_TUNNELS] ?: emptySet()
        }

    suspend fun setRunningTunnels(runningTunnels: Set<String>) {
        Application.getPreferencesDataStore().edit {
            if (runningTunnels.isEmpty())
                it.remove(RUNNING_TUNNELS)
            else
                it[RUNNING_TUNNELS] = runningTunnels
        }
    }

    /**
     * Resolved geography, as JSON keyed by endpoint host.
     *
     * Persisted because the Configs list names a city for every config, and geography is only
     * ever resolved while that config's tunnel is up (see [com.wireguard.android.util.GeoResolver]).
     * Without a cache the list could only ever name the one peer currently carrying traffic.
     */
    /**
     * v2 since 2026-09-13. Before then a lookup on a split-tunnel config could leave outside the
     * tunnel and cache the USER'S OWN city against a server's endpoint — see GeoResolver. Those
     * entries cannot be told apart from honest ones, so the old key is read once, only its
     * panel-sourced entries survive, and the key is removed rather than left on disk.
     */
    private val GEO_CACHE = stringPreferencesKey("geo_cache_v2")
    private val LEGACY_GEO_CACHE = stringPreferencesKey("geo_cache")
    val geoCache: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            it[GEO_CACHE]
        }

    /** Returns the pre-v2 cache, if any, and deletes it in the same edit. */
    suspend fun takeLegacyGeoCache(): String? {
        var legacy: String? = null
        Application.getPreferencesDataStore().edit {
            legacy = it[LEGACY_GEO_CACHE]
            it.remove(LEGACY_GEO_CACHE)
        }
        return legacy
    }

    suspend fun setGeoCache(json: String?) {
        Application.getPreferencesDataStore().edit {
            if (json == null)
                it.remove(GEO_CACHE)
            else
                it[GEO_CACHE] = json
        }
    }

    /**
     * Portway: how each config's last session ended, as "name:reason:whenMillis" (tunnel names
     * cannot contain ':'). Written when a tunnel goes down, read when reporting to the panel.
     */
    private val LAST_DISCONNECTS = stringSetPreferencesKey("last_disconnects")

    suspend fun lastDisconnect(tunnelName: String): Pair<String, Long>? =
        Application.getPreferencesDataStore().data.first()[LAST_DISCONNECTS]
            ?.firstOrNull { it.substringBefore(':') == tunnelName }
            ?.let { entry ->
                val rest = entry.substringAfter(':')
                val at = rest.substringAfterLast(':').toLongOrNull() ?: return@let null
                rest.substringBeforeLast(':') to at
            }

    suspend fun setLastDisconnect(tunnelName: String, reason: String, whenMillis: Long) {
        Application.getPreferencesDataStore().edit {
            val others = (it[LAST_DISCONNECTS] ?: emptySet()).filterNot { e -> e.substringBefore(':') == tunnelName }.toSet()
            it[LAST_DISCONNECTS] = others + "$tunnelName:$reason:$whenMillis"
        }
    }

    /**
     * Portway: peers this panel says are not its own, as "pubkey:whenCheckedMillis" (a base64 key
     * contains no ':'). The app is open to anyone, so a config from another provider is a normal
     * thing to hold — and the panel has nothing to say about it. Remembering the answer is what
     * stops the app reporting a stranger's config, and this device's identity, to a panel that
     * disowned it. Rechecked occasionally, because the operator may add a peer later.
     */
    private val FOREIGN_PEERS = stringSetPreferencesKey("foreign_peers")

    val foreignPeers: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map { it[FOREIGN_PEERS] ?: emptySet() }

    suspend fun setForeignPeer(pubkey: String, whenMillis: Long?) {
        Application.getPreferencesDataStore().edit {
            val others = (it[FOREIGN_PEERS] ?: emptySet()).filterNot { e -> e.substringBeforeLast(':') == pubkey }.toSet()
            it[FOREIGN_PEERS] = if (whenMillis == null) others else others + "$pubkey:$whenMillis"
        }
    }

    /**
     * Portway: this install's identity for the one-device-at-a-time session check. A random
     * UUID, deliberately NOT derived from hardware — a reinstall is a new device as far as the
     * panel is concerned, which is fine, and nothing here can be used to track the phone.
     * Created lazily by SessionGuard; null until then.
     */
    private val DEVICE_ID = stringPreferencesKey("session_device_id")
    val deviceId: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map { it[DEVICE_ID] }

    suspend fun setDeviceId(id: String) {
        Application.getPreferencesDataStore().edit { it[DEVICE_ID] = id }
    }
}
