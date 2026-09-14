/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. The Nocturne hero: one stroked circle whose dash carries the whole connection state,
 * over a radial glow.
 *
 * Replaces the clay puck, which was a bitmap pipeline — a Python generator, four density buckets
 * and a software-layer view drawing two blurred shadows. This draws one arc and one gradient, so
 * it scales to any size, needs no assets, and costs nothing to re-theme.
 *
 * State is the dash, not the colour:
 *   idle          invisible (dash 0 / circumference)
 *   connecting    a 22% arc spinning at 1.1s, glow at 0.6
 *   connected     closed circle, no spin, glow breathing 0.35..0.8 over 3.4s
 *
 * Two constraints from DESIGN.md hold here. There is no MotionLayout: a nested one swallows
 * touch events over its bounds, which once made this control keyboard-only with no crash to
 * explain it. And every infinite animator is cancelled in onStop, or it is a battery bug.
 */
package com.wireguard.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.wireguard.android.R

class ConnectRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Phase { IDLE, CONNECTING, CONNECTED }

    private companion object {
        /** The prototype's `inset:20px` on the glow disc, in dp. */
        const val GLOW_INSET_DP = 20f
    }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.rule)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val ground = ContextCompat.getColor(context, R.color.clay_canvas_top)

    /** Degrees of the circle that are drawn. Animated, not set directly. */
    private var sweepDegrees = 0f
    private var spinDegrees = 0f
    private var glowAlpha = 0f
    private var glowRadius = 0f

    private var sweepAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null

    var phase: Phase = Phase.IDLE
        set(value) {
            if (field == value) return
            field = value
            applyPhase()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = arcPaint.strokeWidth
        bounds.set(inset, inset, w - inset, h - inset)
        // The glow sits behind the stroke and falls off well before the edge, so the ring reads
        // as lit rather than as a filled disc — Nocturne never spends the accent as a large fill.
        //
        // Geometry and colour are the prototype's, literally: the disc is the ring square inset
        // GLOW_INSET_DP, and the gradient reaches transparent at 68% of it. The centre is
        // mix(accent, ground, 0.66) — the ratio the prototype's own ramp() publishes as
        // --pw-a-glow, which is why re-seeding the accent moves the glow with it and the ring
        // can never end up a teal stroke over a violet glow. (ACCENT_TEAL_PROMPT.md quotes the
        // stale CSS fallback of 22% alpha; ramp() is what the design actually renders.)
        glowRadius = (minOf(w, h) / 2f - GLOW_INSET_DP * density).coerceAtLeast(1f)
        glowPaint.shader = RadialGradient(
            w / 2f, h / 2f, glowRadius,
            intArrayOf(ColorUtils.blendARGB(accent, ground, 0.66f), ColorUtils.setAlphaComponent(ground, 0)),
            floatArrayOf(0f, 0.68f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (glowAlpha > 0f) {
            glowPaint.alpha = (glowAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(width / 2f, height / 2f, glowRadius, glowPaint)
        }
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        if (sweepDegrees > 0f) {
            // -90 puts the start at twelve o'clock; spin is a plain rotation on top of it.
            canvas.drawArc(bounds, -90f + spinDegrees, sweepDegrees, false, arcPaint)
        }
    }

    private fun applyPhase() {
        val targetSweep = when (phase) {
            Phase.IDLE -> 0f
            Phase.CONNECTING -> 360f * 0.22f
            Phase.CONNECTED -> 360f
        }
        animateSweep(targetSweep)

        if (phase == Phase.CONNECTING) startSpin() else stopSpin()

        when (phase) {
            Phase.IDLE -> animateGlow(0f, breathe = false)
            Phase.CONNECTING -> animateGlow(0.6f, breathe = false)
            Phase.CONNECTED -> animateGlow(0.8f, breathe = true)
        }
    }

    private fun animateSweep(target: Float) {
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(sweepDegrees, target).apply {
            duration = 400
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { sweepDegrees = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startSpin() {
        if (spinAnimator?.isRunning == true) return
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spinDegrees = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
        spinDegrees = 0f
    }

    private fun animateGlow(target: Float, breathe: Boolean) {
        glowAnimator?.cancel()
        glowAnimator = if (breathe) {
            ValueAnimator.ofFloat(0.35f, 0.8f).apply {
                duration = 3400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            ValueAnimator.ofFloat(glowAlpha, target).apply {
                duration = 500
                addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    /** Called from the fragment's onStop. Infinite animators left running are a battery bug. */
    fun stopAnimations() {
        stopSpin()
        glowAnimator?.cancel()
        glowAnimator = null
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        stopAnimations()
        super.onDetachedFromWindow()
    }
}
