/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway. Fourteen days of "how much did this device move through the tunnel", one bucket per
 * local calendar day.
 *
 * Deliberately small and deliberately local. The panel already knows a user's total quota; what
 * it cannot tell them is the shape — the four quiet days, then the evening that ate a third of
 * the month. That shape is what the band on the detail screen draws.
 *
 * Buckets are keyed by the epoch-millis of local midnight rather than by an "epoch day" integer.
 * minSdk is 24 and this module has no core-library desugaring, so java.time is unavailable, and
 * dividing an instant by 86_400_000 quietly disagrees with the calendar across DST and for
 * negative UTC offsets. Calendar.add(DAY_OF_YEAR, -1) is exact on every one of those days.
 */
package com.wireguard.android.util

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wireguard.android.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar

object UsageHistory {
    /**
     * Retention. The Home screen's band shows a calendar month and the detail screen's shows a
     * fortnight, so the longer of the two is what has to be kept; anything older is pruned on
     * the next write.
     */
    const val DAYS = 30

    /** The two window lengths the design draws. */
    const val MONTH_DAYS = 30
    const val FORTNIGHT_DAYS = 14

    /**
     * One preference holding the whole history as `midnightMillis:bytes` pairs.
     *
     * A single key rather than fourteen: the whole set is read and written together, so this
     * keeps every update atomic and makes pruning a filter rather than fourteen removals.
     */
    private val USAGE_DAYS = stringPreferencesKey("usage_days")

    val history: Flow<Map<Long, Long>>
        get() = Application.getPreferencesDataStore().data.map { parse(it[USAGE_DAYS]) }

    /**
     * Add [bytes] to today's bucket.
     *
     * Callers pass a delta, never a cumulative total: WireGuard's counters restart from zero
     * every time a tunnel comes up, so a cumulative value would be counted again on each
     * reconnect. [com.wireguard.android.model.UsageSampler] owns that differencing.
     */
    suspend fun record(bytes: Long) {
        if (bytes <= 0L) return
        val today = midnightOfToday()
        Application.getPreferencesDataStore().edit { prefs ->
            val buckets = parse(prefs[USAGE_DAYS]).toMutableMap()
            buckets[today] = (buckets[today] ?: 0L) + bytes
            val oldest = midnightsBack(DAYS).last()
            buckets.keys.retainAll { it >= oldest }
            prefs[USAGE_DAYS] = buckets.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    /**
     * The last [count] days, oldest first, aligned to the band's bars. Days with no recorded
     * traffic are zero rather than absent — a missing bar and an idle day look identical to the
     * eye, and only one of them is true.
     */
    fun series(buckets: Map<Long, Long>, count: Int = DAYS): List<Long> =
        midnightsBack(count).reversed().map { buckets[it] ?: 0L }

    /** Total bytes over the last [count] days, for the "12.4 of 30 GB" readout. */
    fun total(buckets: Map<Long, Long>, count: Int = DAYS): Long =
        series(buckets, count).sum()

    /** Local midnights, today first, going back [count] days. */
    fun midnightsBack(count: Int): List<Long> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (0 until count).map {
            val millis = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            millis
        }
    }

    private fun midnightOfToday(): Long = midnightsBack(1).first()

    private fun parse(stored: String?): Map<Long, Long> {
        if (stored.isNullOrEmpty()) return emptyMap()
        return stored.split(',').mapNotNull { entry ->
            val separator = entry.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val day = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            val bytes = entry.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
            day to bytes
        }.toMap()
    }
}
