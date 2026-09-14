/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * DEBUG BUILDS ONLY. Proves GeoResolver's leak guards do what they claim, on any device or
 * emulator, without a working tunnel — which matters because the emulator can never complete a
 * handshake, so a real tunnelled lookup is not observable there.
 *
 *   adb shell am broadcast -n com.portway.vpn.debug/com.wireguard.android.util.GeoGuardSelfTest
 *   adb logcat -s Portway/GeoSelfTest
 *
 * Runs against the device's ordinary (non-VPN) network, standing that network's own addresses
 * in for "the tunnel":
 *   A  the route probe says TUNNEL for a source it should accept
 *   B  the route probe says OUTSIDE for a source it should refuse
 *   C  a real HTTPS request succeeds through the guard, which proves HttpsURLConnection wraps
 *      its socket with the layered createSocket the guard relies on — if it did not, every
 *      lookup would fail closed and this case would say so
 *   D  the same request is refused by the guard, before TLS, when the source is wrong
 * And, if a VPN is up, reports whether this app's traffic actually rides it.
 */
package com.wireguard.android.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

class GeoGuardSelfTest : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        thread(name = "geo-self-test") {
            try {
                run(context)
            } catch (e: Throwable) {
                Log.e(TAG, "self-test crashed", e)
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun run(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun caps(n: Network) = cm.getNetworkCapabilities(n)
        val plain = cm.allNetworks.firstOrNull {
            val c = caps(it) ?: return@firstOrNull false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } ?: run { Log.e(TAG, "no non-VPN internet network to test on"); return }
        val own = cm.getLinkProperties(plain)?.linkAddresses?.map { it.address }?.toSet().orEmpty()
        val wrong = setOf(InetAddress.getByName("192.0.2.250"))
        val cloudflare = InetAddress.getByName("1.1.1.1")
        Log.i(TAG, "testing on $plain, own addresses $own")

        report("A route probe accepts the real source", GeoResolver.routeOf(plain, own, cloudflare) == GeoResolver.Route.TUNNEL)
        report("B route probe flags a wrong source as OUTSIDE", GeoResolver.routeOf(plain, wrong, cloudflare) == GeoResolver.Route.OUTSIDE)

        val okBody = get(plain, own)
        report("C HTTPS succeeds through the guard (layered socket path is used)", okBody.first?.contains("ip=") == true, okBody.second)

        val refused = get(plain, wrong)
        report(
            "D guard refuses a wrong source before TLS",
            refused.first == null && refused.second == GeoResolver.TunnelOnlySslSocketFactory.OUTSIDE_TUNNEL,
            refused.second,
        )

        val vpn = cm.allNetworks.firstOrNull {
            val c = caps(it) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        if (vpn == null) {
            Log.i(TAG, "E no VPN up — skipped")
        } else {
            val tun = cm.getLinkProperties(vpn)?.linkAddresses?.map { it.address }?.toSet().orEmpty()
            val v4 = GeoResolver.routeOf(vpn, tun, cloudflare)
            val v6 = GeoResolver.routeOf(vpn, tun, InetAddress.getByName("2606:4700:4700::1111"))
            val allowed = v4 == GeoResolver.Route.TUNNEL || v6 == GeoResolver.Route.TUNNEL
            Log.i(TAG, "E VPN up (tun $tun): 1.1.1.1 → $v4, 2606:4700:4700::1111 → $v6 " +
                "→ geo lookup would ${if (allowed) "be allowed" else "be SKIPPED"}")
        }
    }

    /** body-or-null to error-message-or-null */
    private fun get(network: Network, allowed: Set<InetAddress>): Pair<String?, String?> {
        val conn = network.openConnection(URL("https://1.1.1.1/cdn-cgi/trace")) as HttpsURLConnection
        return try {
            conn.sslSocketFactory = GeoResolver.TunnelOnlySslSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), allowed)
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null to "HTTP ${conn.responseCode}"
            else conn.inputStream.bufferedReader().use { it.readText() } to null
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun report(name: String, pass: Boolean, detail: String? = null) =
        Log.i(TAG, "${if (pass) "PASS" else "FAIL"}  $name${detail?.let { "  ($it)" } ?: ""}")

    private companion object {
        const val TAG = "Portway/GeoSelfTest"
    }
}
