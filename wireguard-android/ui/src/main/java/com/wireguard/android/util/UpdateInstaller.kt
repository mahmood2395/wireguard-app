/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Downloads a build the panel advertised, checks it, and hands it to Android.
 *
 * The security boundary is NOT this code: Android refuses any update whose signature does not
 * match the installed app, so a substituted APK cannot replace Portway however it was obtained.
 * The SHA-256 check here is for a different failure — a truncated or corrupted download, which
 * is common on the flaky networks these users are on and would otherwise surface as a baffling
 * "app not installed" from the system installer.
 *
 * The tunnel is taken down before the install is offered. That is the entire reason this class
 * exists rather than a download link: Android 16 can corrupt the device's network stack when a
 * VPN app is replaced while its tunnel is live, leaving a VPN that looks connected and carries
 * nothing until the phone is rebooted. A hand-installed APK cannot avoid that; this can.
 */
package com.wireguard.android.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateInstaller {
    sealed class Outcome {
        /** Android's installer UI has been shown; the process may be replaced at any moment. */
        object HandedOff : Outcome()
        /** The user must allow this app to install packages before anything can happen. */
        object NeedsPermission : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    @Volatile
    var inProgress = false
        private set

    /**
     * The tunnels this installer took down, so they can be brought back if the install does not
     * happen. In memory only: the process survives a pending or cancelled install, and a
     * successful one replaces the process anyway.
     */
    @Volatile
    private var takenDown: List<String> = emptyList()

    suspend fun downloadAndInstall(context: Context, update: UpdateChecker.Available): Outcome {
        if (inProgress) return Outcome.Failed("already running")
        if (!canInstall(context)) return Outcome.NeedsPermission
        inProgress = true
        try {
            // Download and verify FIRST, with the VPN still up. This used to take the tunnels
            // down before downloading and never bring them back, so a download that failed —
            // most likely where the panel is only reachable through the tunnel — or a checksum
            // mismatch left the user disconnected by an update that never happened.
            val apk = withContext(Dispatchers.IO) { download(context, update) }
                ?: return Outcome.Failed("download failed")

            val actual = withContext(Dispatchers.IO) { sha256(apk) }
            if (!actual.equals(update.sha256, ignoreCase = true)) {
                apk.delete()
                Log.w(TAG, "Checksum mismatch: expected ${update.sha256}, got $actual")
                return Outcome.Failed("checksum mismatch")
            }

            // Down only now, immediately before Android gets the package: installing over a live
            // VPN is the case that wedges the network stack on Android 16. From here on, anything
            // that stops the install from happening must bring the tunnels back.
            takeTunnelsDown()

            // Recorded before the hand-off, because the process may be replaced the moment
            // the user confirms and there is no reliable "after".
            UserKnobs.setLastAttemptedUpdate(update.versionCode)
            try {
                withContext(Dispatchers.IO) { commit(context, apk) }
            } catch (e: Throwable) {
                restoreTunnels()
                throw e
            }
            return Outcome.HandedOff
        } catch (e: Throwable) {
            Log.e(TAG, "Update failed", e)
            return Outcome.Failed(e.javaClass.simpleName)
        } finally {
            inProgress = false
            }
    }

    /**
     * Bring every running tunnel down and wait for it, so the replace happens with no live VPN.
     *
     * Failures are swallowed on purpose: if a tunnel cannot be brought down, refusing to update
     * would strand the user on a version we may be trying to fix. The Android 16 bug is a risk,
     * not a certainty, while being unable to update at all is a certainty.
     */
    private suspend fun takeTunnelsDown() {
        runCatching {
            val tunnels = Application.getTunnelManager().getTunnels()
            val up = tunnels.filter { it.state == Tunnel.State.UP }
            takenDown = up.map { it.name }
            up.forEach { tunnel ->
                Log.i(TAG, "Taking ${tunnel.name} down before installing")
                tunnel.setStateAsync(Tunnel.State.DOWN, SessionGuard.Gate.NONE)
            }
        }.onFailure { Log.w(TAG, "Could not take tunnels down before update", it) }
    }

    /**
     * Brings back what [takeTunnelsDown] stopped, when the install did not happen: the hand-off
     * threw, Android rejected the package, or the user cancelled its install dialog.
     *
     * Gate.NONE on the way up, matching the way down: this device never gave up its session, so
     * there is nothing to claim, and a claim could only fail on a device that is about to have
     * its connection back.
     */
    fun restoreTunnels() {
        val names = takenDown
        takenDown = emptyList()
        if (names.isEmpty()) return
        applicationScope.launch {
            val tunnels = runCatching { Application.getTunnelManager().getTunnels() }.getOrNull() ?: return@launch
            names.mapNotNull { tunnels[it] }.forEach { tunnel ->
                runCatching { tunnel.setStateAsync(Tunnel.State.UP, SessionGuard.Gate.NONE) }
                    .onSuccess { Log.i(TAG, "Restored ${tunnel.name} after the update did not install") }
                    .onFailure { Log.w(TAG, "Could not restore ${tunnel.name}", it) }
            }
        }
    }

    private fun download(context: Context, update: UpdateChecker.Available): File? {
        val target = File(context.cacheDir, "update-${update.versionCode}.apk")
        if (target.exists()) target.delete()
        val conn = openFollowingSameOrigin(update.url) ?: return null
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "Download returned HTTP ${conn.responseCode}")
                return null
            }
            val total = conn.contentLength.toLong()
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Opens [url], following redirects only while they stay on its origin.
     *
     * UpdateChecker accepts a download URL only when it shares the panel's origin. Letting
     * HttpURLConnection follow redirects on its own undid that: any same-scheme redirect, to any
     * host, was followed silently. The checksum and the signing key still stop a tampered APK, but
     * the origin rule is supposed to mean what it says, so redirects are walked by hand and one
     * that leaves the origin ends the download. A same-host redirect — a panel serving the file
     * from a storage path — still works.
     */
    private fun openFollowingSameOrigin(url: String): HttpURLConnection? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            val next = location?.let { runCatching { URL(URL(current), it).toString() }.getOrNull() }
            if (next == null || !UpdateChecker.sameOrigin(url, next)) {
                Log.w(TAG, "Download redirected outside the panel's origin; refusing")
                return null
            }
            current = next
        }
        Log.w(TAG, "Download redirected too many times; refusing")
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Streams the APK into a PackageInstaller session.
     *
     * PackageInstaller rather than an ACTION_VIEW on a FileProvider URI: it needs no exported
     * provider for the APK, and it reports back, so a refusal or failure is something the app
     * can actually say out loud instead of the user staring at a screen that did nothing.
     */
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("portway", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, StatusReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    /**
     * API 26+ gates installing per app; below that it is a single global setting we cannot query
     * meaningfully, so assume it is allowed and let the system prompt if it is not.
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

    /** Sends the user straight to the toggle rather than leaving them to hunt through Settings. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Receives the session result. The important branch is PENDING_USER_ACTION: the system will
     * not show its confirmation dialog by itself, the installing app has to launch it.
     */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
                else -> {
                    // Rejected, failed, or the user cancelled Android's install dialog
                    // (STATUS_FAILURE_ABORTED). Either way the old version keeps running, so the
                    // tunnels this installer took down must come back.
                    Log.w(TAG, "Install finished with status $status: " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                    restoreTunnels()
                }
            }
            // The APK is no longer needed once the session holds its own copy — which it does
            // from the moment of commit, so this is safe even while the user is still deciding.
            context.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
        }
    }

    private const val TAG = "Portway/UpdateInstaller"
    private const val MAX_REDIRECTS = 5
    private const val ACTION_STATUS = "com.wireguard.android.action.INSTALL_STATUS"
}
