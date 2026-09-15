/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition: ICMP round-trip time via the system ping binary. Android permits
 * unprivileged ICMP echo (ping_group_range covers app UIDs), so this works without root.
 * When a tunnel with AllowedIPs 0.0.0.0/0 is up, the probe rides the tunnel like any other
 * app traffic — which is the honest number: the latency the user actually experiences.
 */
package com.wireguard.android.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Pinger {
    private const val TAG = "Portway/Ping"
    private val TIME_PATTERN = Regex("""\btime=([0-9.]+)\s*ms""")

    /**
     * The average from ping's own summary line.
     *
     * Anchored on "rtt"/"round-trip" rather than on a bare "= n/n/": the loose version matched
     * inside an unrelated line of a failed probe's output and reported 6.5e14 ms, which the
     * Configs list happily rendered as "649894614403724 ms".
     */
    private val SUMMARY_PATTERN = Regex("""(?:rtt|round-trip)[^=\n]*= [0-9.]+/([0-9.]+)/""")

    /** Anything past this is not a round-trip time, whatever produced it. */
    private const val MAX_PLAUSIBLE_MS = 60_000.0

    /**
     * ICMP first, TCP connect-time as fallback. The fallback matters twice over: plenty of
     * networks filter ICMP outright, and the emulator's NAT drops app-originated echo while
     * happily passing TCP. A SYN answered by either an accept or an RST costs exactly one
     * round trip, so time-to-connect and time-to-refusal are both honest RTTs; only a silent
     * drop (timeout) means "unreachable".
     */
    suspend fun ping(host: String, timeoutSeconds: Int = 2): Double? {
        icmp(host, timeoutSeconds)?.let { return it }
        // Resolved once, up front, for both TCP attempts. It used to happen inside the timed
        // connect — InetSocketAddress(host, port) resolves in its constructor — so the "latency"
        // for a hostname endpoint included a DNS lookup, and a slow lookup was bounded by nothing.
        val address = resolve(host, timeoutSeconds * 1000L) ?: return null
        return tcpRtt(address, 443, timeoutSeconds) ?: tcpRtt(address, 80, timeoutSeconds)
    }

    /**
     * The lookup, with a real ceiling. getByName blocks and ignores coroutine cancellation, so it
     * runs detached and the caller simply stops waiting; an abandoned lookup finishes on its own.
     */
    private suspend fun resolve(host: String, timeoutMillis: Long): InetAddress? {
        val lookup = applicationScope.async(Dispatchers.IO) { runCatching { InetAddress.getByName(host) }.getOrNull() }
        return withTimeoutOrNull(timeoutMillis) { lookup.await() }
            .also { if (it == null) android.util.Log.d(TAG, "resolve $host timed out or failed") }
    }

    private suspend fun tcpRtt(address: InetAddress, port: Int, timeoutSeconds: Int): Double? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                val target = InetSocketAddress(address, port)
                val start = System.nanoTime()
                try {
                    socket.connect(target, timeoutSeconds * 1000)
                } catch (e: ConnectException) {
                    // Refused means an RST made the round trip — an honest RTT. Anything else
                    // (network unreachable, ...) fails locally in ~0ms and must not be counted.
                    if (e.message?.contains("refused", ignoreCase = true) != true) throw e
                }
                (System.nanoTime() - start) / 1_000_000.0
            }
        }.getOrNull().also { android.util.Log.d(TAG, "tcp ${address.hostAddress}:$port -> $it") }
    }

    /**
     * Waits for a process, then kills it if it outstays the deadline.
     *
     * Hand-rolled because `Process.waitFor(timeout, unit)` and `destroyForcibly()` are both
     * API 26 and this module's minSdk is 24 — on Android 7 they throw NoSuchMethodError, which
     * would have taken out every ping on those devices. `exitValue()` throws while the process
     * is still running, which is the API-24-safe way to poll for completion, and `destroy()`
     * has existed since API 1.
     */
    private fun reap(proc: Process, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            try {
                proc.exitValue()
                return
            } catch (_: IllegalThreadStateException) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        proc.destroy()
    }

    /**
     * The watchdog is load-bearing, not belt-and-braces.
     *
     * `readText()` blocks until the child closes stdout, which a wedged `ping` never does — and
     * the [reap] call below it can only run once that read has returned, so it could never rescue
     * the case it was written for. Observed in the field as a probe that simply never came back:
     * the row's dot sat at "probing" forever, the coroutine was pinned, and the process stayed
     * alive. `destroy()` closes the child's streams, which is what unblocks the read, so killing
     * the process from a second coroutine is the only thing that can end it.
     */
    private suspend fun icmp(host: String, timeoutSeconds: Int): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder(
                "/system/bin/ping", "-c", "3", "-i", "0.3", "-W", timeoutSeconds.toString(), host
            ).redirectErrorStream(true).start()
            val watchdog = launch {
                delay((timeoutSeconds + 4L) * 1000L)
                android.util.Log.w(TAG, "ping $host outstayed its deadline; killing it")
                runCatching { proc.destroy() }
            }
            val output = try {
                proc.inputStream.bufferedReader().readText()
            } finally {
                watchdog.cancel()
            }
            // The process must be reaped even on timeout, or zombies accumulate at one per probe.
            reap(proc, (timeoutSeconds + 4L) * 1000L)
            val ms = (SUMMARY_PATTERN.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: TIME_PATTERN.find(output)?.groupValues?.get(1)?.toDoubleOrNull())
                ?.takeIf { it >= 0.0 && it <= MAX_PLAUSIBLE_MS }
            android.util.Log.d(TAG, "burst $host -> $ms")
            ms
        }.onFailure { android.util.Log.w(TAG, "ping $host failed", it) }.getOrNull()
    }
}
