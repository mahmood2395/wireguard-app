/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. Once a hand-drawn soft-depth surface; now a flat card with a hairline edge.
 *
 * The name is kept because the editor layouts refer to it, and renaming is a mechanical diff
 * better done on its own. What it no longer does is the point:
 * it drew two blurred shadows with Paint.setShadowLayer() under a gradient face, and because
 * setShadowLayer paints outside the view bounds it had to pad itself by blur + offset — 21dp on
 * every side. Two weighted tiles in a row therefore held their faces 42dp apart and a full-width
 * card sat 45dp from the screen edge, which is why the screens read as scattered objects rather
 * than one surface. Nocturne spends elevation as an edge instead, so there is nothing to pad for.
 *
 * It reads no attributes: the old clay* ones were removed from the layouts and attrs.xml.
 */
package com.wireguard.android.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.wireguard.android.R

open class ClayCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val face = ContextCompat.getColor(context, R.color.clay_surface_hi)
    private val edge = ContextCompat.getColor(context, R.color.clay_stroke)

    init {
        // Built per instance rather than @drawable/surface_card, so nothing that tints or mutates one
        // card's background can reach every other card through a shared constant state.
        applyFace()
    }

    private fun applyFace() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * CORNER_RADIUS_DP
            setColor(face)
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), edge)
        }
    }

    companion object {
        /** Nocturne uses one radius for cards, tiles, buttons and inputs. */
        private const val CORNER_RADIUS_DP = 8f
    }
}
