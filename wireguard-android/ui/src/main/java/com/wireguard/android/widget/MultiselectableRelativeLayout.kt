/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.wireguard.android.R

/**
 * Portway: still named MultiselectableRelativeLayout because TunnelListFragment casts the row
 * root to this type in onConfigureRow and viewForTunnel, but it is a plain FrameLayout now.
 *
 * Nocturne list rows are not cards — they are separated by a hairline and share the ground, so
 * the list reads as one column. The row therefore keeps the background set in XML rather than
 * inheriting a painted surface, and selection tints that face instead of drawing a border.
 */
class MultiselectableRelativeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : android.widget.FrameLayout(context, attrs, defStyleAttr) {
    private var multiselected = false

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        if (multiselected) {
            val drawableState = super.onCreateDrawableState(extraSpace + 1)
            View.mergeDrawableStates(drawableState, STATE_MULTISELECTED)
            return drawableState
        }
        return super.onCreateDrawableState(extraSpace)
    }

    fun setMultiSelected(on: Boolean) {
        if (!multiselected) {
            multiselected = true
            refreshDrawableState()
        }
        isActivated = on
        applySelectionFace(on)
    }

    fun setSingleSelected(on: Boolean) {
        if (multiselected) {
            multiselected = false
            refreshDrawableState()
        }
        isActivated = on
        applySelectionFace(on)
    }

    private fun applySelectionFace(selected: Boolean) {
        // Paint the face, do not add an edge: a second border on a row that already has a
        // hairline reads as a defect rather than as selection.
        backgroundTintList = if (selected)
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.row_selected))
        else null
    }

    companion object {
        private val STATE_MULTISELECTED = intArrayOf(R.attr.state_multiselected)
    }
}
