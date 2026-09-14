/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. The drawn half of the handshake decay bar: a track, a fill, and a hairline where
 * the rekey is due. Nothing else — the labels around it are real TextViews so they stay
 * selectable and scale with the font setting.
 *
 * The hairline is deliberately drawn OUTSIDE the track's rounded clip, spanning the band's
 * full height. Inside it, it would read as a boundary between two fill segments rather than
 * as a mark on a scale, which is the one thing it has to say.
 *
 * This is the only element on these screens that moves every second, and what it moves is a
 * width. No layout pass, no text re-measure, nothing for a screen reader to re-announce.
 */
package com.wireguard.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.wireguard.android.R

class HandshakeDecayTrack @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val trackHeight = 6f * density
    private val trackRadius = 3f * density
    private val trackInset = 3f * density
    private val hairlineWidth = 1f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.decay_track)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.decay_mark)
    }
    private val rect = RectF()

    /** 0f..1f of the track that is filled. Animated by the parent, never set per-frame here. */
    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** The state ink: accent-300 fresh, amber overdue, the disconnected ink when silent. */
    var inkColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            fillPaint.color = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val top = trackInset
        val bottom = top + trackHeight

        rect.set(0f, top, width.toFloat(), bottom)
        canvas.drawRoundRect(rect, trackRadius, trackRadius, trackPaint)

        if (fraction > 0f) {
            val length = width * fraction
            // Anchored at the reading start: the bar grows away from "0s", which sits at the
            // right-hand edge in Persian. Time axes that do not mirror read backwards.
            if (rtl) rect.set(width - length, top, width.toFloat(), bottom)
            else rect.set(0f, top, length, bottom)
            canvas.drawRoundRect(rect, trackRadius, trackRadius, fillPaint)
        }

        val markCentre =
            if (rtl) width * (1f - HandshakeDecayView.REKEY_FRACTION)
            else width * HandshakeDecayView.REKEY_FRACTION
        rect.set(markCentre - hairlineWidth / 2f, 0f, markCentre + hairlineWidth / 2f, height.toFloat())
        canvas.drawRect(rect, markPaint)
    }
}
