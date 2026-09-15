/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.icu.text.ListFormatter
import android.icu.text.MeasureFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Build
import com.wireguard.android.Application
import com.wireguard.android.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

object QuantityFormatter {
    /**
     * Portway: decimal units (KB/MB/GB/TB), where upstream printed binary ones (KiB/MiB). The design
     * writes "12.4 of 30 GB", a plan quota is sold in decimal gigabytes, and "MiB" is jargon on a
     * consumer screen. At most one decimal, and none when it would be ".0", so a 30 GB plan reads
     * "30 GB" rather than "30.00 GiB". Digits follow the locale, like every other figure.
     */
    fun formatBytes(bytes: Long): String {
        val context = Application.get().applicationContext
        fun scaled(divisor: Double, unit: Int) =
            context.getString(unit, DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(bytes / divisor))
        return when {
            bytes < 1_000 -> context.getString(R.string.transfer_bytes, bytes)
            bytes < 1_000_000 -> scaled(1e3, R.string.transfer_kilobytes)
            bytes < 1_000_000_000 -> scaled(1e6, R.string.transfer_megabytes)
            bytes < 1_000_000_000_000L -> scaled(1e9, R.string.transfer_gigabytes)
            else -> scaled(1e12, R.string.transfer_terabytes)
        }
    }

    /**
     * Portway: H:MM:SS / MM:SS for the live session timer. formatEpochAgo is not a
     * substitute — it renders prose like "3 minutes ago", which cannot tick.
     */
    fun formatDuration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    /** Reuses formatBytes so units stay consistent and already-translated. */
    fun formatBytesPerSecond(bytesPerSecond: Double): String =
        Application.get().applicationContext.getString(
            R.string.transfer_rate, formatBytes(bytesPerSecond.toLong())
        )

    fun formatEpochAgo(epochMillis: Long): String {
        var span = (System.currentTimeMillis() - epochMillis) / 1000

        if (span <= 0L)
            return RelativeDateTimeFormatter.getInstance().format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        val measureFormat = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
        val parts = ArrayList<CharSequence>(4)
        if (span >= 24 * 60 * 60L) {
            val v = span / (24 * 60 * 60L)
            parts.add(measureFormat.format(Measure(v, MeasureUnit.DAY)))
            span -= v * (24 * 60 * 60L)
        }
        if (span >= 60 * 60L) {
            val v = span / (60 * 60L)
            parts.add(measureFormat.format(Measure(v, MeasureUnit.HOUR)))
            span -= v * (60 * 60L)
        }
        if (span >= 60L) {
            val v = span / 60L
            parts.add(measureFormat.format(Measure(v, MeasureUnit.MINUTE)))
            span -= v * 60L
        }
        if (span > 0L)
            parts.add(measureFormat.format(Measure(span, MeasureUnit.SECOND)))

        val joined = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            parts.joinToString()
        else
            ListFormatter.getInstance(Locale.getDefault(), ListFormatter.Type.UNITS, ListFormatter.Width.SHORT).format(parts)

        return Application.get().applicationContext.getString(R.string.latest_handshake_ago, joined)
    }
}