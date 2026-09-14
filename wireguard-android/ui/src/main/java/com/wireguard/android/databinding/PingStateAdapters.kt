/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import android.animation.ObjectAnimator
import android.view.View
import androidx.databinding.BindingAdapter
import com.wireguard.android.R
import com.wireguard.android.model.ObservableTunnel

/**
 * Probe-state dot: flashes red while a probe is in flight, settles green on a reply,
 * steady red when the endpoint stayed silent, dim while nothing has been measured yet.
 *
 * `background.mutate()` is load-bearing: the oval drawable's constant state is shared by
 * every dot inflated from it (all rows AND the Connect card's dots), so an unmutated
 * setTint recolours all of them at once.
 */
@BindingAdapter("pingState")
fun View.setPingState(state: ObservableTunnel.PingState?) {
    val running = getTag(R.id.ping_flash_animator) as? ObjectAnimator
    if (state == ObservableTunnel.PingState.PROBING) {
        background.mutate().setTint(context.getColor(R.color.ping_fail))
        if (running == null) {
            val flash = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.2f).apply {
                duration = 400
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
            setTag(R.id.ping_flash_animator, flash)
        }
        return
    }
    running?.cancel()
    setTag(R.id.ping_flash_animator, null)
    when (state) {
        ObservableTunnel.PingState.OK -> {
            alpha = 1f
            background.mutate().setTint(context.getColor(R.color.ping_ok))
        }
        ObservableTunnel.PingState.FAILED -> {
            alpha = 1f
            background.mutate().setTint(context.getColor(R.color.ping_fail))
        }
        else -> {
            alpha = 0.3f
            background.mutate().setTint(context.getColor(R.color.clay_text_muted))
        }
    }
}
