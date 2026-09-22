/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. One config, one device at a time.
 *
 * A config is one private key, which is ONE peer on the router. Connect it on two phones and
 * both claim the same interface address: the router sends each reply to whichever phone spoke
 * last, so both connections flap and the operator gets a support ticket that looks like a
 * server fault. This file stops the second device before that happens.
 *
 * Why not just ask the panel "is this peer online?" — it already answers that. Because one key
 * cannot say WHICH device is online: a user who disconnects and reconnects the same phone would
 * be told it is busy, by their own handshake, for several minutes. So each install carries a
 * random device id and CLAIMS the session; the panel only refuses when a different id holds a
 * session the router confirms is still handshaking. The panel owns that rule, and its timings
 * (it reads handshakes from a ~60s snapshot, so a dead session frees in up to ~240s) — nothing
 * here hard-codes a liveness window. Trust the 409.
 *
 * Two decisions the user made, which shape everything below:
 *  - FAIL-OPEN. No panel, a timeout, a 5xx, a 429 rate limit: connect anyway. A panel outage
 *    must never become a VPN outage, and in the networks this app serves the panel is exactly
 *    the thing most likely to be unreachable.
 *  - TAKEOVER, not a hard block. The warning offers "Use here instead", which re-claims with
 *    takeover=true; the first device learns it was superseded on its next heartbeat and
 *    disconnects itself. Without that, a phone left connected at home locks the account out.
 *
 * And one limit worth stating plainly: this only governs Portway. The .conf holds the private
 * key, so the official WireGuard app can import it and skip all of this. The panel's own
 * endpoint-alternation check is what catches those; this is the friendly front door.
 */
package com.wireguard.android.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

object SessionGuard {
    private const val TAG = "Portway/SessionGuard"

    /**
     * Who is asking for a state change, which decides whether the session check may say no.
     * An explicit argument rather than a guess from the call stack: every caller of
     * TunnelManager.setTunnelState states its intent, so a new entry point cannot silently
     * inherit the wrong policy.
     */
    enum class Gate {
        /** A person tapped something. May be refused, and gets the takeover dialog. */
        USER,

        /**
         * Boot restore and Android's always-on. Claims, so the panel knows the device, but is
         * never refused: blocking always-on makes Android retry in a loop, and in lockdown mode
         * it cuts the user's whole connection. A conflict here is a notification, nothing more.
         */
        ADVISORY,

        /**
         * The watchdog restarting a tunnel this device already holds, and the updater taking it
         * down for an install. No claim, and no release on the way down — releasing for a 700ms
         * restart would hand the session to anyone who asked in that window.
         */
        NONE,
    }

    /** Thrown from a USER connect the panel refused. The UI turns it into the takeover dialog. */
    class AccountInUseException(
        val tunnelName: String,
        val otherDevice: String?,
    ) : Exception("$tunnelName is connected on another device")

    private sealed class Claim {
        object Granted : Claim()
        data class Conflict(val otherDevice: String?) : Claim()
        object Unavailable : Claim()
    }

    private sealed class Beat {
        object Active : Beat()
        data class Superseded(val byDevice: String?) : Beat()
        object Unavailable : Beat()
    }

    /** Short on purpose: this sits between a tap and a connection, and fail-open means waiting buys nothing. */
    private const val CLAIM_TIMEOUT_MS = 3_000L
    private const val BACKGROUND_TIMEOUT_MS = 8_000L

    /** Matches the lease the panel was built around (600s) with a wide margin for Doze. */
    private const val HEARTBEAT_INTERVAL_MS = 60_000L

    private const val CHANNEL_ID = "session"
    private const val NOTIFICATION_ID_BASE = 7300
    private const val ACTION_TAKEOVER = "com.portway.vpn.action.SESSION_TAKEOVER"
    private const val EXTRA_TUNNEL = "tunnel"

    private val idMutex = Mutex()

    /** Created on first use and kept; see UserKnobs.deviceId for why it is random. */
    suspend fun deviceId(): String = idMutex.withLock {
        UserKnobs.deviceId.first() ?: UUID.randomUUID().toString().also { UserKnobs.setDeviceId(it) }
    }

    /**
     * "13 (33)": the Android version a user would quote, and the API level the code actually
     * branches on. Support questions arrive as "I'm on Android 13"; half this app's behaviour
     * (notification permission, always-on, alarms) turns on the number in brackets.
     */
    val osVersion: String by lazy { "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})" }

    /** "Google Pixel 7", not "Google Google Pixel 7" — some makers put the brand in the model too. */
    val deviceName: String by lazy {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val model = Build.MODEL
        if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }

    /**
     * The three settings that answer most support questions, added to every report.
     *
     * "My VPN keeps dropping" is nearly always one of these: Android is not set to keep the VPN up,
     * or it is free to kill the app in the background. And if notifications are off, the user never
     * sees the takeover or superseded notice, which makes the one-device check look broken rather
     * than working — so it is the permission RIGHT NOW that matters, not whether it was ever
     * granted; people revoke it later, and that is exactly the case worth seeing.
     *
     * always_on and lockdown can only be read from a running VpnService (and only on Android 10+),
     * so they ride the heartbeat and are simply absent elsewhere. An absent key means "not known
     * here", never false.
     */
    /** How the previous session of this config ended. "unknown" is a value, not an absence. */
    private fun JSONObject.putLastDisconnect(last: Pair<String, Long?>) {
        put("last_disconnect_reason", last.first)
        last.second?.let { put("last_disconnect_at", it) }
    }

    private fun JSONObject.putEnvironment() {
        val context = Application.get()
        put("notifications", ExpiryNotifier.canNotify(context))
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()?.let { put("battery_unrestricted", it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // .value, never an await: this runs on the path to a connect, and a backend that never
        // initialised must not hold the claim open.
        val backend = Application.backendResult.value?.getOrNull() ?: return
        runCatching { backend.isAlwaysOn() }.getOrNull()?.let { put("always_on", it) }
        runCatching { backend.isLockdownEnabled() }.getOrNull()?.let { put("lockdown", it) }
    }

    // ---- the gate, called from TunnelManager.setTunnelState ------------------------------------

    /**
     * Runs before a tunnel goes UP, outside the backend mutex — it is network I/O, and holding
     * the lock through a slow panel would freeze every other toggle behind it.
     *
     * Throws [AccountInUseException] only for a USER connect the panel explicitly refused.
     * Everything else returns, which means "go ahead".
     */
    suspend fun beforeUp(tunnel: ObservableTunnel, gate: Gate, takeover: Boolean) {
        if (gate == Gate.NONE) return
        // Not this panel's config: it cannot answer for it, and asking would hand it this
        // device's id for a peer it disowned. Connecting is never blocked by that.
        if (AccountRepository.disowned(tunnel)) return
        when (val claim = claim(tunnel, takeover)) {
            is Claim.Conflict ->
                if (gate == Gate.USER) throw AccountInUseException(tunnel.name, claim.otherDevice)
                else notifyConflict(tunnel.name, claim.otherDevice)
            // Fail-open: an unreachable, rate-limited or broken panel never blocks a connection.
            Claim.Unavailable -> Log.i(TAG, "claim unavailable for ${tunnel.name}; connecting anyway")
            Claim.Granted -> Unit
        }
    }

    /** After a tunnel came DOWN. Fire-and-forget: a lost release just waits out the panel's lease. */
    fun afterDown(tunnel: ObservableTunnel, gate: Gate) {
        if (gate == Gate.NONE) return
        applicationScope.launch {
            if (AccountRepository.disowned(tunnel)) return@launch
            runCatching { release(tunnel) }.onFailure { Log.d(TAG, "release failed", it) }
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    fun start(scope: CoroutineScope) {
        createChannel(Application.get())
        scope.launch {
            // Existing installs report every config they already hold, once per process start.
            // Idempotent on the panel, and after startup so it never competes with restore.
            delay(15_000L)
            runCatching {
                Application.getTunnelManager().getTunnels().forEach {
                    if (AccountRepository.isOurs(it)) register(it)
                }
            }.onFailure { Log.d(TAG, "startup register failed", it) }
        }
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { beatAll() }.onFailure { Log.w(TAG, "heartbeat pass failed", it) }
            }
        }
    }

    /**
     * Called when a config is imported or created.
     *
     * A fresh config is asked about first, with its public key alone: registering announces this
     * device to the panel, and a config from another provider should never cause that. The mark is
     * forgotten first, so re-importing a config is also the way a user retries a panel that has
     * since been given the peer.
     */
    fun onImported(tunnel: ObservableTunnel) {
        applicationScope.launch {
            runCatching {
                AccountRepository.forgetForeign(tunnel)
                if (AccountRepository.isOurs(tunnel)) register(tunnel)
            }.onFailure { Log.d(TAG, "register failed", it) }
        }
    }

    private suspend fun beatAll() {
        val manager = Application.getTunnelManager()
        // Non-suspending check first, same reason as UsageSampler: awaiting getTunnels() from a
        // background loop during startup can deadlock the restore it depends on.
        if (!manager.hasTunnelUp()) return
        manager.getTunnels().filter { it.state == Tunnel.State.UP }.forEach { tunnel ->
            if (AccountRepository.disowned(tunnel)) return@forEach
            when (val beat = heartbeat(tunnel)) {
                is Beat.Superseded -> onSuperseded(tunnel, beat.byDevice)
                Beat.Active, Beat.Unavailable -> Unit
            }
        }
    }

    private suspend fun onSuperseded(tunnel: ObservableTunnel, byDevice: String?) {
        // Never fight always-on. Android restarts the service, this would disconnect it again,
        // and in lockdown mode every bounce cuts the user's internet. Say so and stop.
        if (isAlwaysOn()) {
            Log.i(TAG, "${tunnel.name} superseded by $byDevice under always-on; notifying only")
            notifyConflict(tunnel.name, byDevice)
            return
        }
        Log.i(TAG, "${tunnel.name} superseded by $byDevice; disconnecting")
        runCatching {
            DisconnectReasons.expect(tunnel.name, DisconnectReasons.Reason.SUPERSEDED)
            withContext(Dispatchers.Main.immediate) { tunnel.setStateAsync(Tunnel.State.DOWN, Gate.NONE) }
        }.onFailure { Log.w(TAG, "could not disconnect superseded tunnel", it) }
        notifySuperseded(tunnel.name, byDevice)
    }

    private suspend fun isAlwaysOn(): Boolean = runCatching {
        (Application.getBackend() as? GoBackend)?.isAlwaysOn == true
    }.getOrDefault(false)

    // ---- the four panel calls ------------------------------------------------------------------

    private suspend fun register(tunnel: ObservableTunnel) {
        // Read before the body is built: the builder lambda is not a suspending one.
        val lastDisconnect = DisconnectReasons.last(tunnel.name)
        post(tunnel, "/api/peer/device/register", BACKGROUND_TIMEOUT_MS) {
            put("device_name", deviceName)
            put("app_version", BuildConfig.VERSION_CODE)
            put("os_version", osVersion)
            putEnvironment()
            putLastDisconnect(lastDisconnect)
        }
    }

    private suspend fun claim(tunnel: ObservableTunnel, takeover: Boolean): Claim {
        val lastDisconnect = DisconnectReasons.last(tunnel.name)
        val response = post(tunnel, "/api/peer/session/claim", CLAIM_TIMEOUT_MS) {
            put("device_name", deviceName)
            put("app_version", BuildConfig.VERSION_CODE)
            put("os_version", osVersion)
            put("takeover", takeover)
            putEnvironment()
            putLastDisconnect(lastDisconnect)
        } ?: return Claim.Unavailable
        return when (response.code) {
            200 -> if (response.json?.optBoolean("granted", true) == false) Claim.Unavailable else Claim.Granted
            409 -> Claim.Conflict(response.json?.optString("other_device_name")?.takeIf { it.isNotBlank() })
            // Unknown peer: not ours to police. The panel's router-side check still sees it.
            // Remember it, so nothing else here asks about this config again.
            404 -> {
                AccountRepository.markForeign(tunnel)
                Claim.Granted
            }
            // 429 and everything else: fail-open, by agreement with the panel.
            else -> Claim.Unavailable
        }
    }

    private suspend fun heartbeat(tunnel: ObservableTunnel): Beat {
        // The name and version ride along so a phone that stays connected for weeks keeps its row
        // current: otherwise they only refresh when the app is restarted.
        val response = post(tunnel, "/api/peer/session/heartbeat", BACKGROUND_TIMEOUT_MS) {
            put("device_name", deviceName)
            put("app_version", BuildConfig.VERSION_CODE)
            putEnvironment()
        }
            ?: return Beat.Unavailable
        if (response.code != 200) return Beat.Unavailable
        val json = response.json ?: return Beat.Unavailable
        return if (json.optBoolean("active", true)) Beat.Active
        else Beat.Superseded(json.optString("superseded_by_device_name").takeIf { it.isNotBlank() })
    }

    private suspend fun release(tunnel: ObservableTunnel) {
        post(tunnel, "/api/peer/session/release", BACKGROUND_TIMEOUT_MS) {}
    }

    private class Response(val code: Int, val json: JSONObject?)

    /**
     * One POST. Returns null for anything that did not produce an HTTP status — no panel URL,
     * no config, a timeout, a dead network — which every caller reads as "allow".
     */
    private suspend fun post(
        tunnel: ObservableTunnel,
        path: String,
        timeoutMs: Long,
        fields: JSONObject.() -> Unit,
    ): Response? {
        val base = UserKnobs.panelUrl.first()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val config = runCatching { tunnel.getConfigAsync() }.getOrNull() ?: return null
        val body = JSONObject().apply {
            // Same identity as /api/peer/info: the key, plus the address the panel uses to find
            // legacy peers whose key it never stored.
            put("pubkey", config.`interface`.keyPair.publicKey.toBase64())
            config.`interface`.addresses.firstOrNull()?.let { put("address", it.toString()) }
            put("device_id", deviceId())
            fields()
        }.toString().toByteArray(Charsets.UTF_8)

        // The timeout must be a real ceiling. HttpURLConnection blocks, and a blocking call
        // ignores coroutine cancellation — withTimeoutOrNull wrapped directly around it would
        // wait out connectTimeout AND readTimeout back to back, up to twice the budget, with
        // the user watching "Connecting". So the request runs detached and we stop waiting for
        // it; an abandoned call finishes (and disconnects) on its own in the background.
        val call = applicationScope.async(Dispatchers.IO) {
                runCatching {
                    val conn = URL(base + path).openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "POST"
                        conn.connectTimeout = timeoutMs.toInt()
                        conn.readTimeout = timeoutMs.toInt()
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("Accept", "application/json")
                        sign(conn, body)
                        conn.outputStream.use { it.write(body) }
                        val code = conn.responseCode
                        val text = (if (code < 400) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }
                        Response(code, text?.let { runCatching { JSONObject(it) }.getOrNull() })
                    } finally {
                        conn.disconnect()
                    }
                }.onFailure { Log.d(TAG, "$path failed: ${it.javaClass.simpleName}") }.getOrNull()
        }
        return withTimeoutOrNull(timeoutMs) { call.await() }
            ?: null.also { Log.d(TAG, "$path gave up after ${timeoutMs}ms") }
    }

    /**
     * Request-signing hook, deliberately empty in v1. The panel trusts pubkey+address alone,
     * the same as /api/peer/info, which means anyone who knows a user's PUBLIC key could send a
     * takeover and kick them off; the panel rate-limits takeovers per peer to blunt that.
     *
     * The proper fix proves possession of the private key without sending it: with the panel's
     * static X25519 public key, shared = X25519(peer_private, panel_public), then an
     * `X-Portway-Signature: HMAC-SHA256(shared, timestamp + body)` header plus the timestamp.
     * The panel derives the same secret from X25519(panel_private, peer_public). WireGuard keys
     * are X25519 already, so no new key material. Add it here when the panel publishes its key.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun sign(conn: HttpURLConnection, body: ByteArray) = Unit

    // ---- notifications -------------------------------------------------------------------------

    /**
     * A conflict nobody is looking at: the quick tile, a remote-control app, boot restore,
     * always-on. Carries a "Use here instead" action, because the person who pulled down the
     * tile is exactly the one who would otherwise have no way to take over.
     */
    fun notifyConflict(tunnelName: String, otherDevice: String?) {
        val context = Application.get()
        if (!canNotify(context)) return
        val device = otherDevice ?: context.getString(R.string.session_other_device_unknown)
        val takeover = PendingIntent.getBroadcast(
            context, tunnelName.hashCode(),
            Intent(context, TakeoverReceiver::class.java)
                .setAction(ACTION_TAKEOVER)
                .putExtra(EXTRA_TUNNEL, tunnelName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.session_conflict_text, tunnelName, device)
        post(
            context, tunnelName,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_portway_mark)
                .setContentTitle(context.getString(R.string.session_conflict_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context))
                .addAction(0, context.getString(R.string.session_use_here), takeover)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun notifySuperseded(tunnelName: String, byDevice: String?) {
        val context = Application.get()
        if (!canNotify(context)) return
        val device = byDevice ?: context.getString(R.string.session_other_device_unknown)
        val text = context.getString(R.string.session_superseded_text, tunnelName, device)
        post(
            context, tunnelName,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_portway_mark)
                .setContentTitle(context.getString(R.string.session_superseded_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun post(context: Context, tunnelName: String, notification: android.app.Notification) {
        // Checked here, beside notify(), and not only in the callers: this is the check that
        // actually guards the call, and lint can only see a permission test in the same method.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            // One slot per config, so a second warning about the same config replaces the first.
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + (tunnelName.hashCode() and 0xff), notification)
        }.onFailure { Log.w(TAG, "could not post session notice", it) }
    }

    private fun cancel(context: Context, tunnelName: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_BASE + (tunnelName.hashCode() and 0xff))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.session_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.session_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * "Use here instead" from a notification. VPN consent was already granted — a headless
     * connect could not have reached the conflict otherwise — so this can connect directly.
     */
    class TakeoverReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_TAKEOVER) return
            val name = intent.getStringExtra(EXTRA_TUNNEL) ?: return
            val pending = goAsync()
            applicationScope.launch {
                try {
                    cancel(context, name)
                    val tunnel = Application.getTunnelManager().getTunnels()[name] ?: return@launch
                    withContext(Dispatchers.Main.immediate) {
                        tunnel.setStateAsync(Tunnel.State.UP, Gate.USER, takeover = true)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "takeover from notification failed", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
