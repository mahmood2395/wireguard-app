/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Resolves a peer endpoint's hostname while going around the caches that
 * strand users when a server changes address.
 *
 * The problem this exists for: the endpoint IP is frozen into the userspace config when a
 * tunnel comes up, and wireguard-go never re-resolves it, so a moved server needs a restart to
 * be found again. But a restart only helps if the lookup returns the NEW address — and the
 * device resolver cache, and more importantly the carrier's resolver, routinely serve a stale
 * answer long past the record's TTL. Rebooting the phone works purely because it clears those
 * caches. That is the behaviour being replaced here.
 *
 * The hostname in the tunnel config is never rewritten. This changes only how it is looked up,
 * so a config keeps working unchanged if every source below is unavailable.
 */
package com.wireguard.android.util

import android.net.DnsResolver
import android.os.Build
import android.util.Log
import com.wireguard.config.InetEndpoint
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object EndpointResolver {
    /**
     * Where an answer came from, in the order we would rather believe it.
     *
     * Deliberately a preference order and NOT arrival order. Every source is queried at the
     * same time, but taking whichever replies first would actively make things worse: a
     * carrier resolver holding a stale record answers in milliseconds, while the source that
     * knows the truth takes a little longer. Fast and wrong must lose to slow and right.
     */
    private enum class Source { DOH, PANEL, SYSTEM_NO_CACHE, SYSTEM }

    /** Endpoint host -> address, published by the management panel. Never written to any config. */
    @Volatile
    private var panelHints: Map<String, String> = emptyMap()

    /**
     * Consecutive DoH failures. Once DoH looks blocked we stop *waiting* for it, because on a
     * network that blocks it every lookup would otherwise pay the full timeout before falling
     * back — turning a fix into a two-and-a-half second tax on every reconnect, in exactly the
     * countries this feature exists to serve. It keeps being probed in the background so it
     * comes back by itself if the block lifts or the user changes network.
     */
    @Volatile
    private var dohFailures = 0

    /** Last source that produced the answer, purely so a field report can say what worked. */
    @Volatile
    var lastSource: String? = null
        private set

    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "portway-resolve").apply { isDaemon = true }
    }

    /** Installs this resolver into the config layer. Called once at start-up. */
    fun install() {
        InetEndpoint.setResolver { host -> resolve(host) }
    }

    /**
     * Records what the panel says a host currently resolves to.
     *
     * This is a hint about an existing hostname, not a replacement for it — the panel is being
     * used as one more resolver, not as an authority over the config. It matters because in
     * networks where DoH is blocked the panel may be the only channel that still returns a
     * fresh answer, and the app is already talking to it.
     */
    fun setPanelHints(hints: Map<String, String>) {
        if (hints.isNotEmpty()) panelHints = panelHints + hints
    }

    @Throws(UnknownHostException::class)
    private fun resolve(host: String): Array<InetAddress> {
        val tasks = LinkedHashMap<Source, Future<Array<InetAddress>?>>()
        val dohProbeOnly = dohFailures >= DOH_SKIP_AFTER
        val dohTask = pool.submit(Callable { runCatching { doh(host) }.getOrNull() })
        // When DoH is presumed blocked the probe still runs, but nothing waits on it.
        if (!dohProbeOnly) tasks[Source.DOH] = dohTask
        tasks[Source.PANEL] = pool.submit(Callable { runCatching { fromPanelHint(host) }.getOrNull() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tasks[Source.SYSTEM_NO_CACHE] = pool.submit(Callable { runCatching { systemNoCache(host) }.getOrNull() })
        }
        tasks[Source.SYSTEM] = pool.submit(Callable { runCatching { InetAddress.getAllByName(host) }.getOrNull() })

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)
        for ((source, future) in tasks) {
            val remaining = deadline - System.nanoTime()
            val answer = try {
                if (remaining <= 0) future.get(0, TimeUnit.MILLISECONDS)
                else future.get(remaining, TimeUnit.NANOSECONDS)
            } catch (e: Throwable) {
                null
            }
            if (answer == null || answer.isEmpty()) Log.d(TAG, "source $source produced nothing for $host")
            if (answer != null && answer.isNotEmpty()) {
                tasks.values.forEach { it.cancel(true) }
                lastSource = source.name
                Log.i(TAG, "$host resolved via $source -> ${answer.first().hostAddress}")
                return answer
            }
        }
        // Last chance: the plain lookup may have landed after the budget expired. Better a
        // possibly-stale address than refusing to connect at all.
        val late = runCatching { tasks[Source.SYSTEM]?.get(LATE_GRACE_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        if (late != null && late.isNotEmpty()) {
            lastSource = "SYSTEM_LATE"
            return late
        }
        lastSource = null
        throw UnknownHostException(host)
    }

    /**
     * DNS over HTTPS, addressed by IP literal so it needs no bootstrap lookup of its own.
     *
     * Uses the JSON API rather than the binary wire format: same answer, far less code. Where
     * DoH is blocked this simply fails and costs nothing, because every source runs together.
     */
    @Throws(IOException::class)
    private fun doh(host: String): Array<InetAddress>? {
        // In parallel, not one after another: a single blocked endpoint must not spend the
        // whole budget and take the working one down with it.
        val probes = DOH_ENDPOINTS.map { base ->
            base to pool.submit(Callable {
                try {
                    dohQuery(base, host)
                } catch (e: Throwable) {
                    // Logged rather than swallowed: on a network that blocks DoH this is the
                    // only way to tell "blocked" from "broken", and that distinction is the
                    // whole question when diagnosing a field report.
                    Log.i(TAG, "DoH via $base unavailable: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            })
        }
        var answer: Array<InetAddress>? = null
        for ((_, future) in probes) {
            val r = runCatching { future.get(DOH_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) }.getOrNull()
            if (r != null && r.isNotEmpty()) { answer = r; break }
        }
        probes.forEach { it.second.cancel(true) }
        if (answer != null) {
            if (dohFailures > 0) Log.i(TAG, "DoH reachable again")
            dohFailures = 0
        } else {
            dohFailures++
            if (dohFailures == DOH_SKIP_AFTER)
                Log.i(TAG, "DoH looks blocked on this network; no longer waiting on it")
        }
        return answer
    }

    @Throws(IOException::class)
    private fun dohQuery(base: String, host: String): Array<InetAddress>? {
        val url = URL("$base?name=${host}&type=A")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/dns-json")
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val answers = JSONObject(body).optJSONArray("Answer") ?: return null
            val out = ArrayList<InetAddress>()
            for (i in 0 until answers.length()) {
                val entry = answers.getJSONObject(i)
                if (entry.optInt("type") != 1) continue   // A records only
                val data = entry.optString("data").takeIf { it.isNotBlank() } ?: continue
                runCatching { InetAddress.getByName(data) }.getOrNull()?.let { out.add(it) }
            }
            return out.takeIf { it.isNotEmpty() }?.toTypedArray()
        } finally {
            conn.disconnect()
        }
    }

    /** The address the panel last reported for this host, if any. */
    private fun fromPanelHint(host: String): Array<InetAddress>? {
        val ip = panelHints[host] ?: return null
        // Parsed as a literal, so this never recurses back into a lookup.
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return null
        return arrayOf(addr)
    }

    /** Android's resolver with the device cache explicitly skipped. Still the network's resolver. */
    private fun systemNoCache(host: String): Array<InetAddress>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val results = ArrayList<InetAddress>()
        val done = java.util.concurrent.CountDownLatch(1)
        DnsResolver.getInstance().query(
            null, host, DnsResolver.FLAG_NO_CACHE_LOOKUP, pool, null,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    results.addAll(answer); done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    done.countDown()
                }
            }
        )
        done.await(SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return results.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /** Upstream prefers v4 over v6 to dodge DNS64 and IPv6 NAT; keep that ordering. */
    fun preferIpv4(addresses: Array<InetAddress>): Array<InetAddress> =
        addresses.sortedBy { if (it is Inet4Address) 0 else 1 }.toTypedArray()

    private const val TAG = "Portway/EndpointResolver"

    /** Addressed by literal IP: resolving the resolver's own name would defeat the purpose. */
    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    /** After this many consecutive failures, DoH is probed but never waited on. */
    private const val DOH_SKIP_AFTER = 2

    private const val DOH_TIMEOUT_MS = 2_500
    private const val SYSTEM_TIMEOUT_MS = 3_000L
    private const val TOTAL_BUDGET_MS = 3_500L
    private const val LATE_GRACE_MS = 2_000L
}
