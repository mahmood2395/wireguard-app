/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. Once a hand-drawn soft-depth surface; now a flat card with a hairline edge.
 *
 * The name is kept because a dozen layouts and MultiselectableRelativeLayout refer to it, and
 * renaming is a wide mechanical diff better done on its own. What it no longer does is the point:
 * it drew two blurred shadows with Paint.setShadowLayer() under a gradient face, and because
 * setShadowLayer paints outside the view bounds it had to pad itself by blur + offset — 21dp on
 * every side. Two weighted tiles in a row therefore held their faces 42dp apart and a full-width
 * card sat 45dp from the screen edge, which is why the screens read as scattered objects rather
 * than one surface. Nocturne spends elevation as an edge instead, so there is nothing to pad for.
 *
 * Layouts keep their clayCornerRadius / clayShadowBlur / clayShadowOffset / claySunken attributes
 * for now — they are parsed and ignored rather than removed, so this phase does not have to touch
 * every layout at once.
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

    /** Selection paints the face rather than drawing a second border. Null restores the default. */
    var faceOverride: Int? = null
        set(value) {
            field = value
            applyFace()
        }

    private val face = ContextCompat.getColor(context, R.color.clay_surface_hi)
    private val edge = ContextCompat.getColor(context, R.color.clay_stroke)

    init {
        // A drawable built here rather than @drawable/surface_card, because faceOverride has to
        // recolour the fill per instance and a shared constant state would tint every card.
        applyFace()
    }

    private fun applyFace() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * CORNER_RADIUS_DP
            setColor(faceOverride ?: face)
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), edge)
        }
    }

    companion object {
        /** Nocturne uses one radius for cards, tiles, buttons and inputs. */
        private const val CORNER_RADIUS_DP = 8f
    }
}
