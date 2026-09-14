/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. Fourteen bars, one per local day, today at the reading end and drawn in the accent.
 * The bars mirror under an RTL layout, along with the two axis labels beneath them, because a
 * run of days is a time axis and it has to run the way the reader does.
 *
 * Scaled against the largest day in the window rather than against a quota, so the band answers
 * "which days were heavy" — the question a shape can answer — instead of "how much is left",
 * which the account card already states in words.
 *
 * A day with no recorded traffic still gets a hairline stub. An absent bar and an idle day look
 * identical, and only one of them is true.
 */
package com.wireguard.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.wireguard.android.R

class UsageBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val path = Path()
    private val radii = FloatArray(8)

    private val pastColour = ContextCompat.getColor(context, R.color.accent_800)
    private val todayColour = ContextCompat.getColor(context, R.color.accent)

    /** Oldest first; the last entry is today. */
    private var series: List<Long> = emptyList()

    /**
     * Bar gap and corner, in dp. Two bands use this view and the design gives them different
     * geometry: the month band is 30 bars at 3dp/1dp, the fortnight band 14 at 6dp/2dp.
     */
    var gapDp: Float = GAP_DP
        set(value) { field = value; invalidate() }
    var radiusDp: Float = RADIUS_DP
        set(value) { field = value; invalidate() }

    fun setSeries(values: List<Long>) {
        if (values == series) return
        series = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (series.isEmpty()) return
        val gap = gapDp * density
        val radius = radiusDp * density
        val stub = STUB_DP * density
        val slot = (width - gap * (series.size - 1)) / series.size
        if (slot <= 0f) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // Only the top corners are rounded: the bars sit on a baseline, and rounding the foot
        // would lift them off it.
        radii[0] = radius; radii[1] = radius; radii[2] = radius; radii[3] = radius

        val peak = series.max().coerceAtLeast(1L)
        series.forEachIndexed { index, bytes ->
            val fraction = bytes.toFloat() / peak
            val barHeight = (height * fraction).coerceAtLeast(stub)
            val column = if (rtl) series.size - 1 - index else index
            val left = column * (slot + gap)
            bar.set(left, height - barHeight, left + slot, height.toFloat())
            paint.color = if (index == series.lastIndex) todayColour else pastColour
            path.reset()
            path.addRoundRect(bar, radii, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val GAP_DP = 6f
        const val RADIUS_DP = 2f

        /** Enough to read as a bar of nothing rather than as a missing bar. */
        const val STUB_DP = 2f
    }
}
