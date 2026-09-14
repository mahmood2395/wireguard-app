/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Asks the panel whether a newer build exists.
 *
 * This is NOT upstream's updater, which was deleted from the fork and must not come back: it
 * throws from Application.onCreate whenever the package name does not start with
 * `com.wireguard.` — upstream's own deliberate tripwire against copy-paste forks — and it
 * fetches WireGuard-signed APKs that could never install over a differently-signed build.
 *
 * Why an in-app updater is worth having here at all: the APK is hand-distributed, so users
 * install it whenever they happen to, very plausibly while a tunnel is up — and Android 16 can
 * corrupt the device's network stack when a VPN app is replaced mid-session, leaving a tunnel
 * that looks connected and carries nothing until a reboot. Owning the moment of install is what
 * lets the app take the tunnel down first and avoid that entirely.
 */
package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    /**
     * A build the panel says exists.
     *
     * [mandatory] is decided here rather than by the UI so there is one definition of it: the
     * panel names the oldest version it is still willing to serve, and anything older than that
     * cannot be dismissed. That exists so a client with a broken contract or a security problem
     * can actually be retired, instead of quietly staying in the field forever.
     */
    data class Available(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val notes: String?,
        val mandatory: Boolean,
    )

    sealed class Result {
        data class Update(val available: Available) : Result()
        object UpToDate : Result()
        /** No panel configured, endpoint absent, malformed answer, or offline. Never surfaced. */
        object Unavailable : Result()
    }

    /** Advertised version we already handed to the installer; see UserKnobs.lastAttemptedUpdate. */
    @Volatile
    private var alreadyAttempted = 0

    @Volatile
    private var lastCheckedElapsed = 0L

    @Volatile
    private var cached: Result = Result.Unavailable

    /**
     * @param force skip the throttle, for an explicit "check now" from Settings.
     *
     * Throttled because this runs on app start: a user who opens the app twenty times a day
     * should not produce twenty requests, and the answer changes at most on release days.
     */
    suspend fun check(force: Boolean = false): Result {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastCheckedElapsed != 0L && now - lastCheckedElapsed < THROTTLE_MS) return cached

        val base = UserKnobs.panelUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) return Result.Unavailable
        alreadyAttempted = UserKnobs.lastAttemptedUpdate.first()

        val result = withContext(Dispatchers.IO) {
            try {
                val conn = (URL("$base/api/app/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode != 200) return@withContext Result.Unavailable
                            parse(base, JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Throwable) {
                // An absent endpoint is the normal state until the panel ships it, so this is
                // deliberately quiet — never a user-visible error.
                Log.d(TAG, "Update check unavailable: ${e.javaClass.simpleName}")
                Result.Unavailable
            }
        }
        lastCheckedElapsed = now
        cached = result
        // Logged at info on every path: an operator asking "why is nobody updating" needs to
        // see what the client actually concluded, not just its failures.
        Log.i(TAG, "Update check: " + when (result) {
            is Result.Update -> "offering ${result.available.versionName} (code ${result.available.versionCode}, mandatory=${result.available.mandatory})"
            is Result.UpToDate -> "up to date at ${BuildConfig.VERSION_CODE}"
            is Result.Unavailable -> "unavailable"
        })
        return result
    }

    private fun parse(base: String, json: JSONObject): Result {
        val versionCode = json.optInt("version_code", 0)
        // The update must come from the same origin as the panel we already trust, rather than
        // merely "some https URL". A manifest that could point anywhere would let whoever
        // controls the panel response redirect the install to a host of their choosing; and a
        // flat https requirement would also have broken LAN panels served over http, which this
        // app deliberately supports. Same scheme, same host, same port.
        val url = json.optString("url").takeIf { sameOrigin(base, it) } ?: return Result.Unavailable
        val sha256 = json.optString("sha256").lowercase().takeIf { it.matches(SHA256) } ?: return Result.Unavailable
        if (versionCode <= 0) return Result.Unavailable
        // Strictly greater: a panel that reports the running version, or an older one after a
        // rollback, must never offer a "downgrade" that Android would refuse to install anyway.
        if (versionCode <= BuildConfig.VERSION_CODE) return Result.UpToDate

        val minSupported = json.optInt("min_supported_version_code", 0)
        // If we already installed this exact advertised version and are still below it, the
        // published file does not contain what its manifest claims. Keep offering it — the
        // install may simply have been cancelled — but never as mandatory, because a forced
        // card for an update that cannot raise the version is one the user can never escape.
        val ineffective = versionCode == alreadyAttempted
        if (ineffective) Log.w(TAG, "Version $versionCode was already installed but this build is still " +
            "${BuildConfig.VERSION_CODE}; the published APK does not match its manifest")
        return Result.Update(
            Available(
                versionCode = versionCode,
                versionName = json.optString("version_name").ifBlank { versionCode.toString() },
                url = url,
                sha256 = sha256,
                notes = json.optString("notes").takeIf { it.isNotBlank() && it != "null" },
                mandatory = BuildConfig.VERSION_CODE < minSupported && !ineffective,
            )
        )
    }

    private fun sameOrigin(base: String, candidate: String): Boolean = runCatching {
        val a = URL(base)
        val b = URL(candidate)
        // Effective ports, not raw ones: URL.port is -1 when the port is implied, so a panel
        // stored as "https://host" and a download at "https://host:443/..." are the same origin
        // even though the raw values differ. Comparing raw would silently refuse every update.
        fun port(u: URL) = if (u.port != -1) u.port else u.defaultPort
        a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }.getOrDefault(false)

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    private const val TAG = "Portway/UpdateChecker"
    private const val TIMEOUT_MS = 8_000
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L
}
