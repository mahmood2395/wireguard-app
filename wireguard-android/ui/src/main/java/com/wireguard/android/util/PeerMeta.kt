/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. The one sentence under a config's name in the Configs list.
 *
 * It lives here, called from the row's binding expression, rather than being assembled in
 * onConfigureRow: geography and RTT both arrive long after a row is bound, and a row that was
 * written once stays wrong until something happens to rebind it. Every input the line depends on
 * is a parameter, which is what makes databinding re-evaluate it when any of them changes — the
 * arguments are the dependency list, so do not read anything off `tunnel` inside.
 */
package com.wireguard.android.util

import android.content.Context
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel

object PeerMeta {
    /**
     * The prototype's four cases, in the order it shows them:
     *
     *   Connected · Frankfurt, DE      — the one carrying traffic says so first
     *   No route to peer · 81.4.22.9   — a peer that will not answer is the headline, not the city
     *   Frankfurt, DE · 5.9.44.12      — a config we have been to before
     *   5.9.44.12                      — one we have not; never a city we did not resolve
     */
    @JvmStatic
    fun line(
        context: Context,
        state: Tunnel.State?,
        pingState: ObservableTunnel.PingState?,
        geoLabel: String?,
        endpointHost: String?,
    ): CharSequence {
        val connected = state == Tunnel.State.UP
        val place = geoLabel?.takeIf { it.isNotBlank() }
        val host = endpointHost?.takeIf { it.isNotBlank() }
        return when {
            connected && place != null ->
                join(context.getString(R.string.tunnel_status_active), place)
            connected -> context.getString(R.string.tunnel_status_active)
            pingState == ObservableTunnel.PingState.FAILED && host != null ->
                join(context.getString(R.string.peer_no_route), host)
            pingState == ObservableTunnel.PingState.FAILED ->
                context.getString(R.string.peer_no_route)
            place != null && host != null -> join(place, host)
            place != null -> place
            host != null -> host
            else -> context.getString(R.string.tunnel_status_inactive)
        }
    }

    private fun join(first: String, second: String) = "$first · $second"
}
