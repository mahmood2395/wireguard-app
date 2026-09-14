/*
 * Copyright © 2026 MyVPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added by the MyVPN fork of the WireGuard Android project: parses the
 * <scheme>://import?c=<base64url .conf> deep link that upstream does not provide.
 */
package com.wireguard.android.util

import android.net.Uri
import android.util.Base64
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Parses config-import deep links of the form:
 *
 *     portway://import?c=<base64url of a WireGuard .conf>[&name=<tunnel name>]
 *
 * The payload is carried inline rather than fetched, so importing works offline and
 * needs no network permission at import time. The private key is therefore present in
 * the URL — the same exposure as the QR code the peer page already renders.
 */
object ConfigDeepLink {
    const val HOST = "import"
    const val PARAM_CONFIG = "c"
    const val PARAM_NAME = "name"

    /** Guards against a malicious/broken link forcing a huge allocation. A real config is < 1 KiB. */
    private const val MAX_PAYLOAD_BYTES = 64 * 1024

    sealed class Result {
        data class Success(val configText: String, val suggestedName: String?) : Result()
        data class Failure(val reason: Reason) : Result()
    }

    enum class Reason { NOT_AN_IMPORT_LINK, MISSING_PAYLOAD, PAYLOAD_TOO_LARGE, MALFORMED_BASE64, NOT_UTF8, NOT_A_CONFIG }

    fun parse(uri: Uri?): Result {
        if (uri == null || !HOST.equals(uri.host, ignoreCase = true))
            return Result.Failure(Reason.NOT_AN_IMPORT_LINK)

        val payload = try {
            uri.getQueryParameter(PARAM_CONFIG)
        } catch (_: UnsupportedOperationException) {
            // getQueryParameter throws on opaque URIs (portway:import?... with no "//")
            null
        }
        if (payload.isNullOrBlank()) return Result.Failure(Reason.MISSING_PAYLOAD)
        if (payload.length > MAX_PAYLOAD_BYTES) return Result.Failure(Reason.PAYLOAD_TOO_LARGE)

        // Accept both base64url (-_) and standard base64 (+/), padded or not, since a
        // web page may percent-encode a standard-alphabet string instead of using base64url.
        val normalized = payload.trim().replace('-', '+').replace('_', '/')
        val bytes = try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return Result.Failure(Reason.MALFORMED_BASE64)
        }
        if (bytes.size > MAX_PAYLOAD_BYTES) return Result.Failure(Reason.PAYLOAD_TOO_LARGE)

        // Strict UTF-8: a wrong payload must fail loudly rather than import mojibake as a key.
        val configText = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return Result.Failure(Reason.NOT_UTF8)
        }

        // Cheap sanity check before handing to the real parser, so a random blob gives a
        // clear "not a config" instead of a confusing parser error.
        if (!configText.contains("[Interface]", ignoreCase = true))
            return Result.Failure(Reason.NOT_A_CONFIG)

        val name = uri.getQueryParameter(PARAM_NAME)?.trim()?.takeIf { it.isNotEmpty() }
        return Result.Success(configText, name)
    }
}
