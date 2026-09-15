/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.fragment.app.FragmentManager
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.fragment.ConfigNamingDialogFragment
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object TunnelImporter {
    suspend fun importTunnel(contentResolver: ContentResolver, uri: Uri, messageCallback: (CharSequence) -> Unit) = withContext(Dispatchers.IO) {
        val context = Application.get().applicationContext
        val futureTunnels = ArrayList<Deferred<ObservableTunnel>>()
        val throwables = ArrayList<Throwable>()
        try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
            var name = ""
            contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    name = cursor.getString(0)
                }
            }
            if (name.isEmpty()) {
                name = Uri.decode(uri.lastPathSegment)
            }
            var idx = name.lastIndexOf('/')
            if (idx >= 0) {
                require(idx < name.length - 1) { context.getString(R.string.illegal_filename_error, name) }
                name = name.substring(idx + 1)
            }
            // Portway: decide by content, not by extension. Upstream refused anything that
            // was not literally *.conf or *.zip; users get configs as .txt from chat apps
            // and e-mail all the time. A zip is recognised by its magic bytes, anything
            // else must be UTF-8 text that the stock parser accepts. The extension (any
            // extension) is only stripped to form the suggested tunnel name.
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readUpTo(MAX_IMPORT_BYTES + 1) }
            require(bytes.size <= MAX_IMPORT_BYTES) { context.getString(R.string.import_too_large_error) }
            val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
            name = stripExtension(name)

            if (isZip) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var entry: ZipEntry?
                    while (true) {
                        entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        name = entry.name
                        idx = name.lastIndexOf('/')
                        if (idx >= 0) {
                            if (idx >= name.length - 1) {
                                continue
                            }
                            name = name.substring(name.lastIndexOf('/') + 1)
                        }
                        name = stripExtension(name)
                        // Every entry is a candidate; non-configs are skipped silently
                        // (a README in the archive is not an error), parse failures of
                        // things that do look like configs are reported.
                        val text = zip.readUpTo(MAX_IMPORT_BYTES).toString(StandardCharsets.UTF_8)
                        if (!looksLikeConfig(text)) continue
                        try {
                            Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
                        } catch (e: Throwable) {
                            throwables.add(e)
                            null
                        }?.let {
                            val nameCopy = uniqueName(sanitizeName(name))
                            futureTunnels.add(async(SupervisorJob()) { Application.getTunnelManager().create(nameCopy, it) })
                        }
                    }
                }
            } else {
                val text = bytes.toString(StandardCharsets.UTF_8)
                require(looksLikeConfig(text)) { context.getString(R.string.not_a_config_error) }
                val config = Config.parse(ByteArrayInputStream(bytes))
                val tunnelName = uniqueName(sanitizeName(name))
                futureTunnels.add(async(SupervisorJob()) { Application.getTunnelManager().create(tunnelName, config) })
            }

            if (futureTunnels.isEmpty()) {
                if (throwables.size == 1) {
                    throw throwables[0]
                } else {
                    require(throwables.isNotEmpty()) { context.getString(R.string.no_configs_error) }
                }
            }
            val tunnels = futureTunnels.mapNotNull {
                try {
                    it.await()
                } catch (e: Throwable) {
                    throwables.add(e)
                    null
                }
            }
            withContext(Dispatchers.Main.immediate) { onTunnelImportFinished(tunnels, throwables, messageCallback) }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main.immediate) { onTunnelImportFinished(emptyList(), listOf(e), messageCallback) }
        }
    }

    // suggestedName precedes the callback so existing trailing-lambda callers keep binding
    // their lambda to messageCallback.
    fun importTunnel(parentFragmentManager: FragmentManager, configText: String, suggestedName: String? = null, messageCallback: (CharSequence) -> Unit) {
        try {
            // Ensure the config text is parseable before proceeding…
            Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))

            // Config text is valid, now create the tunnel…
            ConfigNamingDialogFragment.newInstance(configText, suggestedName).show(parentFragmentManager, null)
        } catch (e: Throwable) {
            onTunnelImportFinished(emptyList(), listOf<Throwable>(e), messageCallback)
        }
    }

    private fun onTunnelImportFinished(tunnels: List<ObservableTunnel>, throwables: Collection<Throwable>, messageCallback: (CharSequence) -> Unit) {
        val context = Application.get().applicationContext
        var message = ""
        for (throwable in throwables) {
            val error = ErrorMessages[throwable]
            message = context.getString(R.string.import_error, error)
            Log.e(TAG, message, throwable)
        }
        if (tunnels.size == 1 && throwables.isEmpty())
            message = context.getString(R.string.import_success, tunnels[0].name)
        else if (tunnels.isEmpty() && throwables.size == 1)
        else if (throwables.isEmpty())
            message = context.resources.getQuantityString(
                R.plurals.import_total_success,
                tunnels.size, tunnels.size
            )
        else if (!throwables.isEmpty())
            message = context.resources.getQuantityString(
                R.plurals.import_partial_success,
                tunnels.size + throwables.size,
                tunnels.size, tunnels.size + throwables.size
            )

        messageCallback(message)
    }

    /** Drops one trailing extension of any kind: "office.conf", "office.txt", "office.conf.txt" -> "office", "office.conf". */
    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /**
     * Coerces a filename into a legal tunnel name.
     *
     * Tunnel names must match `[a-zA-Z0-9_=+.-]{1,15}` — a limit inherited from Linux network
     * interface naming, not from anything about WireGuard. Upstream passed the filename
     * through untouched, so a perfectly valid config saved as "My Office VPN.conf" or
     * "corporate-gateway-west.conf" failed the whole import with "Invalid name", while the
     * same config pasted or scanned imported fine because those paths offer a rename dialog.
     * A filename is a label, not part of the configuration; bending it to fit is better than
     * refusing the file.
     */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() && it.code < 128 || it in "_=+.-") it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(MAX_NAME_LENGTH)
            .trim('-')
        return cleaned.ifEmpty { "tunnel" }
    }

    /** Appends -2, -3 … when the sanitized name is taken, staying inside the length limit. */
    private suspend fun uniqueName(base: String): String {
        val existing = Application.getTunnelManager().getTunnels().map { it.name }.toSet()
        if (base !in existing) return base
        for (n in 2..999) {
            val suffix = "-$n"
            val candidate = base.take(MAX_NAME_LENGTH - suffix.length).trim('-') + suffix
            if (candidate !in existing) return candidate
        }
        return base
    }

    /** Cheap pre-check so binary junk gets a clear message instead of a parser stack trace. */
    private fun looksLikeConfig(text: String): Boolean =
        !text.contains('\uFFFD') && text.contains("[Interface]", ignoreCase = true)

    /** InputStream.readNBytes is API 33+; minSdk is 24. Does not close the stream (zip entries). */
    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Tunnel.NAME_PATTERN caps names at 15 characters. */
    private const val MAX_NAME_LENGTH = 15

    /** Well above any real config or archive of them; bounds memory for a mis-tapped video. */
    private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    private const val TAG = "WireGuard/TunnelImporter"
}