/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Names the place a config's traffic surfaces in, so the Nocturne screens can
 * say "Frankfurt, DE · 42 ms" instead of repeating an IP the user cannot read.
 *
 * Portway has no server list — a user owns configs someone handed them — so the city is a
 * *finding* about the peer, never a menu the user picked from. Two consequences run through
 * everything below: the app never invents a city, and it never claims one it did not resolve.
 *
 * WHERE THE ANSWER COMES FROM, in the order we would rather have it:
 *
 *   1. The management panel, when it names a city for the peer. No new trust relationship and
 *      no extra request — the app is already talking to it — and it works while disconnected.
 *   2. A geo-IP lookup made BY THIS DEVICE, THROUGH THE TUNNEL, once per connection.
 *
 * The second one deserves its reasoning written down, because the naive reading of it is
 * "the app tells a third party where its users connect", and the opposite is true here:
 *
 *   - The request leaves from the tunnel's exit, so the provider sees the EXIT IP — the address
 *     every site the user visits already sees — and never the user's own address. While
 *     disconnected the same request WOULD carry the real IP, so with no VPN network it never runs.
 *
 *     But "a VPN network exists" is NOT the same as "this request rides it", and this file used
 *     to assume it was. Binding a socket to the VPN network does not force it into the tunnel:
 *     on a SPLIT config (AllowedIPs that don't cover the provider — GoBackend then allows both
 *     address families outside the VPN) the kernel routes the connection out over the bare
 *     link, and the provider saw the user's real IP and city. Found 2026-09-13 on a config
 *     routing only 192.0.2.0/24 that had never handshaked, and still showed a city.
 *
 *     So the rule is now proven per destination, by the kernel, before a single packet: see
 *     [routesThroughTunnel]. A provider whose addresses would leave outside the tunnel is
 *     skipped, a split tunnel skips the lookup entirely before even resolving a hostname, and
 *     [TunnelOnlySslSocketFactory] refuses any connection whose source is not the tunnel before
 *     TLS begins — the enforcement at the moment of use, in case DNS hands the connection an
 *     address the check never saw. The config degrades to its bare IP, which is the rule anyway.
 *   - It answers the honest question. A panel-side table says where the operator believes a
 *     server is; a lookup from inside the tunnel says where this session's traffic actually
 *     surfaces right now, which is what the screen claims. Per-route and server addresses move
 *     often enough that a cached answer would be confidently wrong.
 *   - It works where the geo provider is blocked locally, because the request exits abroad.
 *
 * Answers are cached per endpoint host and persisted, so the Configs list can name the city of
 * a config that is not currently up — a fact learned last time it was, not a guess.
 */
package com.wireguard.android.util

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

object GeoResolver {
    /**
     * Where a peer's traffic surfaces. Every field is optional because every source is partial:
     * the trace endpoint returns a country and no city, and a panel may name a city and no
     * country. [label] renders whatever is actually known and nothing more.
     */
    data class Place(
        val ip: String?,
        val city: String?,
        val country: String?,
        val fromPanel: Boolean,
    ) {
        /** "Frankfurt, DE", "Frankfurt", "DE" — or null, which means "say nothing about place". */
        val label: String?
            get() = when {
                city != null && country != null -> "$city, $country"
                city != null -> city
                country != null -> country
                else -> null
            }
    }

    /** Endpoint host -> what we know about it. Read from the render path, so it stays in memory. */
    @Volatile
    private var places: Map<String, Place> = emptyMap()

    /**
     * Bumped whenever [places] changes, purely so screens showing a cached label can re-render.
     * A counter rather than the map itself: consumers all re-read through [cached] anyway.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    /**
     * The connection a lookup has already been made for, as "name#connectedSince". A new session
     * gets a new monotonic stamp, which is exactly the "once per connection" rule.
     */
    private var attemptedSession: String? = null
    private var attempts = 0
    private var lastAttemptAt = 0L
    private val gate = Mutex()

    fun cached(host: String?): Place? = host?.let { places[it.lowercase()] }

    /** Loads the persisted cache. Called once at start-up, before any screen renders. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            // One-time: drop every lookup-sourced entry from before the tunnel-route check. Any
            // of them may be a user's real city cached against a server (see the class comment).
            // What the panel said about its own server involved no lookup, so it is kept.
            val legacy = runCatching { UserKnobs.takeLegacyGeoCache() }.getOrNull()
            if (legacy != null) {
                val kept = runCatching { decode(legacy) }.getOrNull().orEmpty().filterValues { it.fromPanel }
                Log.i(TAG, "purged pre-route-check geo cache; kept ${kept.size} panel entries")
                if (kept.isNotEmpty()) runCatching { UserKnobs.setGeoCache(encode(kept)) }
            }
            val json = runCatching { UserKnobs.geoCache.first() }.getOrNull() ?: return@launch
            val restored = runCatching { decode(json) }.getOrNull() ?: return@launch
            // Anything resolved since start-up is fresher than the file, so it wins.
            places = restored + places
            _revision.value = _revision.value + 1
        }
    }

    /**
     * Records what the panel says about a peer's endpoint.
     *
     * Preferred over a lookup and never overwritten by one: the operator naming its own server's
     * city involves no third party at all, and it is available while the tunnel is down.
     */
    fun setPanelPlace(host: String?, city: String?, country: String?) {
        val key = host?.lowercase() ?: return
        if (city == null && country == null) return
        val existing = places[key]
        if (existing?.fromPanel == true && existing.city == city && existing.country == country) return
        put(key, Place(ip = existing?.ip, city = city, country = country, fromPanel = true))
    }

    /**
     * Resolves this tunnel's place if it is up and we have not already asked for this session.
     *
     * Safe to call from every polling tick and from more than one screen: it is single-flight,
     * and the session token means a connection costs one lookup however many callers there are.
     */
    suspend fun ensureResolved(tunnel: ObservableTunnel) {
        if (tunnel.state != Tunnel.State.UP) return
        // Tunnels adopted at process start have no stamp (see ObservableTunnel); 0 is a stable
        // token for "this run's inherited session", which is still one lookup, not one per tick.
        val session = "${tunnel.name}#${tunnel.connectedSinceElapsedRealtime ?: 0L}"
        val host = runCatching {
            tunnel.getConfigAsync().peers.firstOrNull()?.endpoint?.orElse(null)?.host
        }.getOrNull()?.lowercase() ?: return

        gate.withLock {
            val known = places[host]
            // The panel already answered for this peer. Nothing to ask anyone else.
            if (known?.fromPanel == true) return
            val now = System.currentTimeMillis()
            if (session == attemptedSession) {
                // Same connection. Retry only while the answer is still missing, a few times,
                // spaced out — the first attempt can easily precede the first handshake, and a
                // tunnel that is up but not yet passing traffic simply has nothing to reach.
                if (known?.label != null) return
                if (attempts >= MAX_ATTEMPTS_PER_SESSION) return
                if (now - lastAttemptAt < RETRY_INTERVAL_MS) return
            } else {
                attemptedSession = session
                attempts = 0
            }
            attempts++
            lastAttemptAt = now

            val place = lookupThroughTunnel() ?: return
            // The IP the lookup reports is the exit, which is the address the peer's endpoint
            // hostname is standing in for. Keep the label only when there is one to keep.
            if (place.label == null && place.ip == null) return
            put(host, place)
        }
    }

    private fun put(host: String, place: Place) {
        // Bounded: the cache exists to name a handful of configs, not to accumulate history.
        val merged = (places + (host to place)).let {
            if (it.size <= MAX_ENTRIES) it else it.entries.drop(it.size - MAX_ENTRIES).associate { e -> e.toPair() }
        }
        places = merged
        _revision.value = _revision.value + 1
        Application.get().applicationScope.launch {
            runCatching { UserKnobs.setGeoCache(encode(merged)) }
        }
    }

    /**
     * The lookup itself.
     *
     * Returns null rather than throwing, and returns null WITHOUT asking anyone when there is no
     * VPN network to bind to. Every provider below is addressed over HTTPS and sees only the
     * exit address; none of them is told anything about the user or the config.
     */
    private suspend fun lookupThroughTunnel(): Place? = withContext(Dispatchers.IO) {
        val network = vpnNetwork() ?: run {
            // Not an error worth surfacing: it means the tunnel went down between the state
            // check and here, and the rule is that a lookup off-tunnel never happens at all.
            Log.d(TAG, "no VPN network bound; not resolving")
            return@withContext null
        }
        // The tunnel's own addresses: a socket that really rides the tunnel has one of these as
        // its source. Read from the VPN network itself, not the config, so it is what the kernel
        // actually assigned.
        val cm = Application.get().getSystemService(ConnectivityManager::class.java)
        val tunnelAddresses = cm?.getLinkProperties(network)?.linkAddresses?.map { it.address }?.toSet().orEmpty()
        if (tunnelAddresses.isEmpty()) {
            Log.d(TAG, "VPN network has no addresses; not resolving")
            return@withContext null
        }
        // First, and before any DNS: does this app's internet traffic go through the tunnel at
        // all? Two IP literals, so asking costs no lookup that could itself leave outside. A
        // split tunnel, or this app excluded from its own VPN, stops here.
        if (CANARIES.none { routeOf(network, tunnelAddresses, it) == Route.TUNNEL }) {
            Log.i(TAG, "this app's internet traffic is not routed through the tunnel " +
                "(split tunnel, or the app is excluded); not resolving")
            return@withContext null
        }
        for (provider in PROVIDERS) {
            // The connection may pick ANY address the name resolves to, so one that would route
            // outside the tunnel vetoes the provider. An address with no route at all does not:
            // an IPv4-only full tunnel blocks IPv6 outright, and a provider's AAAA records are
            // then simply unusable, not a leak — treating them as one would cost most configs
            // their city for nothing. At least one address must actually ride the tunnel.
            val addresses = runCatching { network.getAllByName(provider.host).toList() }.getOrNull()
            if (addresses.isNullOrEmpty()) continue
            val routes = addresses.map { routeOf(network, tunnelAddresses, it) }
            if (Route.OUTSIDE in routes) {
                Log.i(TAG, "${provider.name} would be reached outside the tunnel; skipping it")
                continue
            }
            if (Route.TUNNEL !in routes) continue
            val place = runCatching { provider.query(network, tunnelAddresses) }.getOrNull()
            if (place != null && (place.label != null || place.ip != null)) {
                Log.i(TAG, "place resolved via ${provider.name}: ${place.label ?: place.ip}")
                return@withContext place
            }
        }
        null
    }

    /**
     * Our own tunnel's network.
     *
     * Binding to it is what guarantees the request rides the tunnel even when the user has
     * excluded this app from the VPN in split-tunnelling, which would otherwise send exactly
     * this request out over the bare connection.
     */
    @Suppress("DEPRECATION") // allNetworks: the callback API cannot answer synchronously here.
    private fun vpnNetwork(): Network? {
        val cm = Application.get().getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private class Provider(
        val name: String,
        /** What gets resolved and route-checked before [query] runs. An IP literal resolves to itself. */
        val host: String,
        val query: (Network, Set<InetAddress>) -> Place?,
    )

    private val PROVIDERS = listOf(
        Provider("ipwho.is", "ipwho.is") { network, tunnel ->
            val json = JSONObject(fetch(network, tunnel, "https://ipwho.is/") ?: return@Provider null)
            if (!json.optBoolean("success", true)) return@Provider null
            Place(
                ip = json.optString("ip").takeIf { it.isNotBlank() },
                city = json.optString("city").takeIf { it.isNotBlank() },
                country = json.optString("country_code").takeIf { it.length == 2 },
                fromPanel = false,
            )
        },
        Provider("ipapi.co", "ipapi.co") { network, tunnel ->
            val json = JSONObject(fetch(network, tunnel, "https://ipapi.co/json/") ?: return@Provider null)
            // It answers 200 with {"error":true,"reason":"RateLimited"} rather than a status
            // code, and its free tier is metered per requesting IP — which here is the server's
            // exit, shared by everyone on it. Falling through to the next provider is the point.
            if (json.optBoolean("error", false)) return@Provider null
            Place(
                ip = json.optString("ip").takeIf { it.isNotBlank() },
                city = json.optString("city").takeIf { it.isNotBlank() },
                country = json.optString("country").takeIf { it.length == 2 },
                fromPanel = false,
            )
        },
        // Last, and worth having despite naming no city: it is addressed by IP literal, so it
        // still answers when DNS is the thing that is broken, and a country beats silence.
        Provider("cloudflare-trace", "1.1.1.1") { network, tunnel ->
            val body = fetch(network, tunnel, "https://1.1.1.1/cdn-cgi/trace") ?: return@Provider null
            val fields = body.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            Place(
                ip = fields["ip"]?.takeIf { it.isNotBlank() },
                city = null,
                country = fields["loc"]?.takeIf { it.length == 2 },
                fromPanel = false,
            )
        },
    )

    private fun fetch(network: Network, tunnelAddresses: Set<InetAddress>, url: String): String? {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        return try {
            // Every provider is HTTPS, so every connection passes through this factory after TCP
            // connects and before TLS: the one place that sees the socket's real source address.
            // A non-HTTPS URL gets no guard, so refuse to fetch one at all.
            if (conn !is HttpsURLConnection) return null
            conn.sslSocketFactory = TunnelOnlySslSocketFactory(
                HttpsURLConnection.getDefaultSSLSocketFactory(), tunnelAddresses
            )
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "geo lookup at $url failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Where a connection to one address would go. */
    internal enum class Route {
        /** Leaves through the tunnel: safe. */
        TUNNEL,

        /** Routable, but not through the tunnel. The leak. Vetoes the request. */
        OUTSIDE,

        /**
         * No route on this network at all — typically an address family the VPN blocks. A
         * connection cannot use it, so it can neither leak nor help. Never grants permission by
         * itself: an app excluded from its own VPN, which cannot bind to it, lands here for every
         * address and so never resolves.
         */
        UNREACHABLE,
    }

    /**
     * Would a connection to [destination], made on [network], leave through the tunnel?
     *
     * Asked of the kernel, not worked out from the config. Connecting a UDP socket sends nothing
     * but does run the route lookup and pick a source address; if that source is one of the
     * tunnel's own addresses the traffic rides the tunnel, and if it is the Wi-Fi or mobile
     * address it would not. That single test covers everything a config-reading check would have
     * to model and could get wrong: split AllowedIPs, "exclude private IPs", an address family
     * the VPN does or does not block, several tunnels, and this app being excluded from its own
     * VPN. If asking fails — a family the VPN blocks, a network the app may not bind to — there
     * is no route, so a real connection would fail the same way: [Route.UNREACHABLE].
     */
    internal fun routeOf(network: Network, tunnelAddresses: Set<InetAddress>, destination: InetAddress): Route =
        try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(destination, 443))
                if (socket.localAddress in tunnelAddresses) Route.TUNNEL else Route.OUTSIDE
            }
        } catch (e: Exception) {
            Route.UNREACHABLE
        }

    /**
     * Refuses any TLS connection whose underlying socket did not leave from a tunnel address.
     *
     * The route check above runs before the request, on the addresses DNS returned then; the
     * connection resolves again when it opens. This is the check at the moment of use: it sees
     * the connected socket's real source, and throws before TLS starts, so no HTTP request —
     * and with it no lookup result tied to the user's real address — can happen outside.
     *
     * Only the layered createSocket(Socket, …) is implemented, because it is the one
     * HttpsURLConnection uses to wrap the socket it already connected. The other forms would open
     * a socket this class cannot vouch for; they refuse rather than guess.
     */
    internal class TunnelOnlySslSocketFactory(
        private val delegate: SSLSocketFactory,
        private val tunnelAddresses: Set<InetAddress>,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            val source = s.localAddress
            if (source !in tunnelAddresses) {
                runCatching { s.close() }
                throw IOException(OUTSIDE_TUNNEL)
            }
            return delegate.createSocket(s, host, port, autoClose)
        }

        override fun createSocket(host: String?, port: Int): Socket = throw IOException(UNLAYERED)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw IOException(UNLAYERED)
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw IOException(UNLAYERED)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw IOException(UNLAYERED)

        internal companion object {
            const val OUTSIDE_TUNNEL = "refused: connection left outside the tunnel"
            const val UNLAYERED = "refused: unlayered socket"
        }
    }

    /** Public addresses by IP literal — asking about them needs no DNS. Cloudflare's resolvers, one per family. */
    private val CANARIES: List<InetAddress> by lazy {
        listOf("1.1.1.1", "2606:4700:4700::1111").mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    private fun encode(map: Map<String, Place>): String {
        val root = JSONObject()
        for ((host, place) in map) {
            root.put(
                host,
                JSONObject().apply {
                    place.ip?.let { put("ip", it) }
                    place.city?.let { put("city", it) }
                    place.country?.let { put("cc", it) }
                    if (place.fromPanel) put("panel", true)
                }
            )
        }
        return root.toString()
    }

    private fun decode(json: String): Map<String, Place> {
        val root = JSONObject(json)
        val out = LinkedHashMap<String, Place>()
        for (host in root.keys()) {
            val entry = root.optJSONObject(host) ?: continue
            out[host] = Place(
                ip = entry.optString("ip").takeIf { it.isNotBlank() },
                city = entry.optString("city").takeIf { it.isNotBlank() },
                country = entry.optString("cc").takeIf { it.isNotBlank() },
                fromPanel = entry.optBoolean("panel", false),
            )
        }
        return out
    }

    private const val TAG = "Portway/Geo"
    private const val TIMEOUT_MS = 6_000
    private const val MAX_ATTEMPTS_PER_SESSION = 3
    private const val RETRY_INTERVAL_MS = 20_000L
    private const val MAX_ENTRIES = 32
}
