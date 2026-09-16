/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Fetches per-peer account info from the management panel's /api/peer/info endpoint.
 *
 * Identity is the tunnel's own public key — derived from the private key, so it exists for
 * every config however it was imported — plus the interface address as a cross-check. The
 * panel's base URL is the single global setting (Settings → Panel URL); while it is unset
 * the feature is simply off.
 */
package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AccountRepository {
    data class AccountInfo(
        val name: String?,
        val plan: String?,
        val expiry: String?,
        val daysLeft: Int?,
        val disabled: Boolean,
        val online: Boolean,
        val totalBytes: Long?,
        /**
         * The plan's allowance, when the panel states one.
         *
         * Optional on purpose. Nocturne draws a usage rail and a "12.4 of 30 GB" readout, and
         * both need a denominator; without one the app shows the used figure alone rather than
         * inventing a ceiling to divide by.
         */
        val quotaBytes: Long?,
    )

    sealed class Result {
        data class Ok(val info: AccountInfo) : Result()
        object NotLinked : Result()      // no Panel URL configured
        object Unavailable : Result()    // network / panel error; keep whatever is cached
        object Unknown : Result()        // panel answered: this peer does not exist (404)
    }

    private val cache = mutableMapOf<String, AccountInfo>()

    /**
     * How long a "not my peer" answer stands before the app asks again. Long, because the answer
     * rarely changes and asking is the thing being avoided; not forever, because an operator can
     * add a peer to the panel after its owner already installed the app.
     */
    private const val FOREIGN_RECHECK_MS = 7L * 24 * 60 * 60 * 1000

    /** When the panel last disowned [pubkey], or null if it never has (or the answer has aged out). */
    private suspend fun disownedAt(pubkey: String): Long? =
        UserKnobs.foreignPeers.first()
            .firstOrNull { it.substringBeforeLast(':') == pubkey }
            ?.substringAfterLast(':')?.toLongOrNull()
            ?.takeIf { System.currentTimeMillis() - it < FOREIGN_RECHECK_MS }

    /**
     * Is this config one the panel knows? Answers false without a request once the panel has said
     * no. Used before anything that would send this device's identity.
     */
    suspend fun isOurs(tunnel: ObservableTunnel): Boolean = when (fetch(tunnel)) {
        is Result.Ok -> true
        Result.Unknown, Result.NotLinked -> false
        Result.Unavailable -> false
    }

    /** Forget the "not mine" answer for this peer, so the next lookup really asks. */
    suspend fun forgetForeign(pubkey: String) {
        if (UserKnobs.foreignPeers.first().any { it.substringBeforeLast(':') == pubkey })
            UserKnobs.setForeignPeer(pubkey, null)
    }

    fun cached(pubkey: String): AccountInfo? = cache[pubkey]

    /** The last answer for [tunnel]'s peer, without asking the panel again. */
    suspend fun cached(tunnel: ObservableTunnel): AccountInfo? =
        runCatching { tunnel.getConfigAsync().`interface`.keyPair.publicKey.toBase64() }.getOrNull()?.let { cache[it] }

    suspend fun fetch(tunnel: ObservableTunnel): Result {
        val config = runCatching { tunnel.getConfigAsync() }.getOrNull() ?: return Result.NotLinked
        val pubkey = config.`interface`.keyPair.publicKey.toBase64()
        // The address lets the panel find LEGACY peers whose public key it never stored:
        // it matches by address, verifies the full key against the router, then backfills.
        val address = config.`interface`.addresses.firstOrNull()?.toString()

        val base = UserKnobs.panelUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) return Result.NotLinked
        // Already disowned: say so without asking. Every caller — the Connect screen's minute
        // refresh, the twice-daily expiry check, the settings card — then goes quiet for a config
        // that is not this panel's, instead of sending its public key over and over.
        if (disownedAt(pubkey) != null) return Result.Unknown
        val url = "$base/api/peer/info?pubkey=${URLEncoder.encode(pubkey, "UTF-8")}" +
            (address?.let { "&address=${URLEncoder.encode(it, "UTF-8")}" } ?: "")

        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                try {
                    when (conn.responseCode) {
                        200 -> {
                            val json = JSONObject(conn.inputStream.bufferedReader().readText())
                            val info = AccountInfo(
                                name = json.optString("name").takeIf { it.isNotEmpty() },
                                plan = json.optString("plan").takeIf { it.isNotEmpty() },
                                expiry = json.optString("expiry").takeIf { it.isNotEmpty() },
                                daysLeft = if (json.isNull("days_left")) null else json.optInt("days_left"),
                                disabled = json.optBoolean("disabled", false),
                                online = json.optBoolean("online", false),
                                totalBytes = if (json.isNull("total_bytes")) null else json.optLong("total_bytes"),
                                quotaBytes = listOf("quota_bytes", "plan_bytes", "limit_bytes")
                                    .firstOrNull { json.has(it) && !json.isNull(it) }
                                    ?.let { json.optLong(it) }
                                    ?.takeIf { it > 0 },
                            )
                            cache[pubkey] = info
                            // The panel claims this peer after all — an operator can add one at
                            // any time — so drop a stale "not mine" mark. Only when there is one:
                            // this runs on every refresh, and a write per minute would be waste.
                            forgetForeign(pubkey)
                            // Remote move: the panel's answer names its authoritative URL.
                            // Persisting it means the operator can migrate domains and any
                            // app that phones home once follows automatically.
                            json.optString("panel_url").trimEnd('/').takeIf {
                                it.startsWith("http") && it != base
                            }?.let { UserKnobs.setPanelUrl(it) }
                            // Resolver hint only. The endpoint hostname in the user's config is
                            // never touched — this just gives EndpointResolver one more answer
                            // for that hostname, which is the one that still works on networks
                            // where DoH is blocked and the carrier resolver is serving a stale
                            // record. Both halves required, or it tells us nothing.
                            val hintHost = json.optString("endpoint_host").takeIf { it.isNotBlank() }
                            val hintIp = json.optString("endpoint_ip").takeIf { it.isNotBlank() }
                            if (hintIp != null) {
                                // Only the address is really required. The panel answered about
                                // THIS tunnel, so its router's address is this tunnel's endpoint
                                // address — which means the hostname to attach it to is the one
                                // already in this config. Requiring the panel to also name the
                                // host would make the whole feature depend on an optional column
                                // (cf_hostname) being filled in per router; where it is blank the
                                // hint would silently do nothing, in exactly the networks that
                                // need it most.
                                //
                                // When the panel DOES name a host, it must agree with the config.
                                // A mismatch means this answer is about a different server, and
                                // pointing the tunnel at it would be worse than doing nothing.
                                val configHost = config.peers.firstOrNull()
                                    ?.endpoint?.orElse(null)?.host
                                val target = when {
                                    hintHost == null -> configHost
                                    configHost == null -> hintHost
                                    hintHost.equals(configHost, ignoreCase = true) -> configHost
                                    else -> null
                                }
                                if (target != null) EndpointResolver.setPanelHints(mapOf(target to hintIp))
                            }
                            // Geography, when the panel states it. Preferred over the device
                            // lookup in GeoResolver for the obvious reason — the operator
                            // naming its own server involves no third party and answers while
                            // the tunnel is down — and optional for the same reason the quota
                            // is: the panel does not have to send it, and where it does not,
                            // nothing here invents a city.
                            val geoHost = json.optString("endpoint_host").takeIf { it.isNotBlank() }
                                ?: config.peers.firstOrNull()?.endpoint?.orElse(null)?.host
                            GeoResolver.setPanelPlace(
                                host = geoHost,
                                city = json.optString("city").takeIf { it.isNotBlank() },
                                country = listOf("country_code", "country")
                                    .firstNotNullOfOrNull { k -> json.optString(k).takeIf { it.length == 2 } },
                            )
                            Result.Ok(info)
                        }
                        // The panel does not know this peer. Remember it, with the time, so the
                        // next caller does not ask again until the recheck window is up.
                        404 -> {
                            UserKnobs.setForeignPeer(pubkey, System.currentTimeMillis())
                            Result.Unknown
                        }
                        else -> Result.Unavailable
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "account fetch failed: ${e.message}")
                Result.Unavailable
            }
        }
    }

    private const val TAG = "Portway/Account"
}
