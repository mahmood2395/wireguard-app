/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Portway note on the getters below.
 *
 * Upstream answered every synchronous read with `runBlocking { dataStore.data.first() }`.
 * AndroidX calls those getters from the main thread while it inflates the preference screen —
 * once per visible row — so opening Settings meant a burst of blocking file reads on the UI
 * thread, exactly what the StrictMode policy in Application.onCreate exists to catch, and a
 * jank/ANR risk on slow storage.
 *
 * DataStore has no synchronous read and PreferenceDataStore's contract is synchronous, so the
 * gap is bridged by keeping a snapshot warm: a collector mirrors the latest Preferences into
 * memory and the getters read that. The mirror is process-wide because exactly one settings
 * DataStore backs it, so a later visit to Settings costs nothing; only the first read in a
 * cold process can still block, and once rather than once per row.
 */
class PreferencesPreferenceDataStore(private val coroutineScope: CoroutineScope, private val dataStore: DataStore<Preferences>) : PreferenceDataStore() {
    init {
        coroutineScope.launch { dataStore.data.collect { cached = it } }
    }

    /** Latest known values, seeding itself with a single blocking read if it must. */
    private fun snapshot(): Preferences =
        cached ?: runBlocking { dataStore.data.first() }.also { cached = it }

    /**
     * Reflect a write immediately, so a getter called straight after a put cannot read through
     * to the pre-write value while the asynchronous edit is still in flight.
     */
    private fun <T> optimistic(key: Preferences.Key<T>, value: T?) {
        val base = cached ?: return
        cached = base.toMutablePreferences().apply {
            if (value == null) remove(key) else set(key, value)
        }
    }
    override fun putString(key: String?, value: String?) {
        if (key == null) return
        val pk = stringPreferencesKey(key)
        optimistic(pk, value)
        coroutineScope.launch {
            dataStore.edit {
                if (value == null) it.remove(pk)
                else it[pk] = value
            }
        }
    }

    override fun putStringSet(key: String?, values: Set<String?>?) {
        if (key == null) return
        val pk = stringSetPreferencesKey(key)
        val filteredValues = values?.filterNotNull()?.toSet()
        optimistic(pk, filteredValues?.takeIf { it.isNotEmpty() })
        coroutineScope.launch {
            dataStore.edit {
                if (filteredValues == null || filteredValues.isEmpty()) it.remove(pk)
                else it[pk] = filteredValues
            }
        }
    }

    override fun putInt(key: String?, value: Int) {
        if (key == null) return
        val pk = intPreferencesKey(key)
        optimistic(pk, value)
        coroutineScope.launch {
            dataStore.edit {
                it[pk] = value
            }
        }
    }

    override fun putLong(key: String?, value: Long) {
        if (key == null) return
        val pk = longPreferencesKey(key)
        optimistic(pk, value)
        coroutineScope.launch {
            dataStore.edit {
                it[pk] = value
            }
        }
    }

    override fun putFloat(key: String?, value: Float) {
        if (key == null) return
        val pk = floatPreferencesKey(key)
        optimistic(pk, value)
        coroutineScope.launch {
            dataStore.edit {
                it[pk] = value
            }
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        if (key == null) return
        val pk = booleanPreferencesKey(key)
        optimistic(pk, value)
        coroutineScope.launch {
            dataStore.edit {
                it[pk] = value
            }
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        val pk = stringPreferencesKey(key)
        return snapshot()[pk] ?: defValue
    }

    override fun getStringSet(key: String?, defValues: Set<String?>?): Set<String?>? {
        if (key == null) return defValues
        val pk = stringSetPreferencesKey(key)
        return snapshot()[pk] ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null) return defValue
        val pk = intPreferencesKey(key)
        return snapshot()[pk] ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null) return defValue
        val pk = longPreferencesKey(key)
        return snapshot()[pk] ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null) return defValue
        val pk = floatPreferencesKey(key)
        return snapshot()[pk] ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null) return defValue
        val pk = booleanPreferencesKey(key)
        return snapshot()[pk] ?: defValue
    }

    companion object {
        /** One settings store backs every instance, so the mirror is shared between them. */
        @Volatile
        private var cached: Preferences? = null
    }
}
