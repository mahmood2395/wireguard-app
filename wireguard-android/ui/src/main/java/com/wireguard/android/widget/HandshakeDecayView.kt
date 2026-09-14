/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. The handshake decay bar — Nocturne's replacement for "Latest handshake 12 seconds
 * ago", a figure that re-rendered every second and could therefore never be read.
 *
 * Two constants define the whole graphic. HANDSHAKE_LIMIT is WireGuard's 180s cutoff, at which
 * the peer counts as gone: it is the full width of the track. REKEY_DUE is the nominal 118s
 * rekey interval: it is the hairline, at 65.6% of the track. So the age still ticks, but it now
 * ticks against a limit — one glance says how much room is left, and a bar past the hairline is
 * the news.
 *
 * It replaced the earlier heartbeat lane, which needed a ring buffer of past handshakes to draw
 * anything. This needs only the age of the latest one, which the polling loop already reads, so
 * HandshakeLog went with it.
 *
 * HandshakeWatchdog reads HANDSHAKE_LIMIT_MS for its own stale threshold. The bar and the
 * watchdog answer the same question — "up but not handshaking" — and pointing them at one
 * number is the only way they cannot disagree. The constant is `const`, so Kotlin inlines it
 * at the call site and the headless watchdog never loads a View class to read it.
 */
package com.wireguard.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.ContextCompat
import com.wireguard.android.R

class HandshakeDecayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val track: HandshakeDecayTrack
    private val ageView: TextView
    private val sentence: TextView

    private val inkFresh = ContextCompat.getColor(context, R.color.accent_300)
    private val inkLate = ContextCompat.getColor(context, R.color.handshake_late)
    private val inkSilent = ContextCompat.getColor(context, R.color.ping_fail)

    private var fillAnimator: ValueAnimator? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.handshake_decay_view, this, true)
        track = findViewById(R.id.decay_track)
        ageView = findViewById(R.id.decay_age)
        sentence = findViewById(R.id.decay_sentence)
        // The graphic is decorative; the meaning lives on the container as words.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // A percent guideline is absolute, not layout-direction aware, so in RTL it would put
        // "rekey due" at 65.6% from the LEFT while the track fills from the right.
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            findViewById<Guideline>(R.id.decay_rekey_guide).setGuidelinePercent(1f - REKEY_FRACTION)
        }
        setAge(null)
    }

    /**
     * @param ageSeconds seconds since the latest handshake, or null when the tunnel is not up
     *                   (or is up but has never handshaked) — the silent state.
     */
    fun setAge(ageSeconds: Long?) {
        val connected = ageSeconds != null
        val age = ageSeconds ?: HANDSHAKE_LIMIT
        val late = connected && age > LATE_AFTER

        val ink = when {
            !connected -> inkSilent
            late -> inkLate
            else -> inkFresh
        }
        track.inkColor = ink
        ageView.setTextColor(ink)

        ageView.text =
            if (!connected) context.getString(R.string.handshake_age_none)
            else formatAge(age)

        sentence.setText(
            when {
                !connected -> R.string.handshake_state_silent
                late -> R.string.handshake_state_late
                else -> R.string.handshake_state_fresh
            }
        )

        contentDescription = when {
            !connected -> context.getString(R.string.handshake_a11y_silent)
            late -> context.getString(R.string.handshake_a11y_late, spokenAge(age))
            else -> context.getString(R.string.handshake_a11y_fresh, spokenAge(age))
        }

        animateFill((age.toFloat() / HANDSHAKE_LIMIT).coerceIn(0f, 1f))
    }

    /** "12s ago" / "1m 58s ago", the prototype's own formatting. */
    private fun formatAge(age: Long): String =
        if (age >= 60) context.getString(R.string.handshake_age_minutes, age / 60, age % 60)
        else context.getString(R.string.handshake_age_seconds, age)

    /** Spoken form: "12 seconds" reads; "12s" does not. */
    private fun spokenAge(age: Long): String =
        if (age >= 60) context.getString(R.string.handshake_spoken_minutes, age / 60, age % 60)
        else context.getString(R.string.handshake_spoken_seconds, age)

    private fun animateFill(target: Float) {
        if (track.fraction == target) return
        fillAnimator?.cancel()
        // A new handshake resets the age to near zero. Sliding the bar backwards over 0.9s
        // reads as the bar being wrong; snapping it reads as the rekey landing, which is what
        // happened. Only growth is animated.
        if (target < track.fraction) {
            track.fraction = target
            return
        }
        fillAnimator = ValueAnimator.ofFloat(track.fraction, target).apply {
            duration = FILL_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { track.fraction = it.animatedValue as Float }
            start()
        }
    }

    /** Called from the host fragment's onStop; an animator left running is a battery bug. */
    fun stopAnimations() {
        fillAnimator?.cancel()
        fillAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    companion object {
        /** WireGuard's cutoff: past this the peer counts as gone. The full width of the track. */
        const val HANDSHAKE_LIMIT = 180L
        const val HANDSHAKE_LIMIT_MS = HANDSHAKE_LIMIT * 1000L

        /** Nominal rekey interval — the hairline. */
        const val REKEY_DUE = 118L

        /** Where the hairline sits, 0.656 of the track. */
        const val REKEY_FRACTION = REKEY_DUE.toFloat() / HANDSHAKE_LIMIT.toFloat()

        /** Past this the rekey is overdue, though still inside the 3m window. */
        private const val LATE_AFTER = 150L

        private const val FILL_DURATION_MS = 900L
    }
}
