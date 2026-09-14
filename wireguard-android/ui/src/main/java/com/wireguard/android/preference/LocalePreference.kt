/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Picks the app's language independently of the phone's.
 *
 * Why it exists at all, given Android 13 has a per-app language screen: most of this app's
 * Persian-reading users are not on a Persian phone. A shared or second-hand device, a phone
 * bought abroad, a work profile, a launcher someone else set up — the system language is not
 * reliably the language the person reads, and before Android 13 there is no per-app setting to
 * fall back to. On API 24-32 this preference IS the only way; on 33+ it is the same setting the
 * system screen writes, so the two stay in step rather than disagreeing.
 *
 * [AppCompatDelegate.setApplicationLocales] is the single source of truth, so this preference is
 * deliberately NOT persisted into the app's own DataStore: two stores for one setting is how a
 * setting ends up disagreeing with itself after the system screen changes it. On API 33+ the
 * framework holds it; below that AppCompat does, which is what the AppLocalesMetadataHolderService
 * entry in the manifest turns on — without it the choice is lost at the next launch.
 *
 * The list is built at runtime from [R.array.supported_locales] and each language is named IN
 * ITSELF ("فارسی", not "Persian"), because someone looking for their language cannot necessarily
 * read the name of it in the language they are trying to leave.
 */
package com.wireguard.android.preference

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import com.wireguard.android.R
import java.text.Collator
import java.util.Locale

class LocalePreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {

    private val languages: List<Pair<String, String>>

    init {
        isPersistent = false
        title = context.getString(R.string.language_title)
        val system = context.getString(R.string.language_follow_system)

        val tags = context.resources.getStringArray(R.array.supported_locales)
        val current = Locale.getDefault()
        // Sorted in the reader's own collation, not by tag: "Ελληνικά" and "فارسی" have no
        // meaningful ASCII order, and a list nobody can scan is a list nobody can use.
        val collator = Collator.getInstance(current)
        languages = tags
            .map { tag -> tag to displayName(tag) }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }

        // "" is the value that means "no app override" — the same empty locale list the system
        // screen writes when the user picks the system default.
        entryValues = (listOf("") + languages.map { it.first }).toTypedArray()
        entries = (listOf(system) + languages.map { it.second }).toTypedArray()

        setOnPreferenceChangeListener { _, newValue ->
            val tag = newValue as? String ?: return@setOnPreferenceChangeListener false
            // Applying the change recreates every activity, so summary and value are updated
            // first — after this call the preference this listener belongs to is already gone.
            value = tag
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
            // Returning false: isPersistent is off, so there is nothing to write, and letting
            // ListPreference set the value again after an activity recreate is pointless.
            false
        }
    }

    /**
     * The framework's initial-value pass would overwrite [value] with the persisted default —
     * null, since nothing is persisted here — leaving the row claiming "Follow system" while a
     * language was plainly pinned, and no option ticked in the dialog. AppCompatDelegate is the
     * store; there is no initial value to set.
     */
    override fun onSetInitialValue(defaultValue: Any?) = Unit

    override fun onAttached() {
        super.onAttached()
        syncFromDelegate()
    }

    /** Reads the applied locale back, so the row and the dialog agree with the running app. */
    private fun syncFromDelegate() {
        value = AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() }
            ?.let { applied -> languages.firstOrNull { matches(applied, it.first) }?.first }
            ?: ""
    }

    /** The current selection, or "Follow system" — the row states the answer, not the question. */
    override fun getSummary(): CharSequence = entry ?: context.getString(R.string.language_follow_system)

    /**
     * The stored tag may carry a region the entry list does not ("fa-IR" against our "fa", or a
     * script subtag Android added), so a plain string comparison would leave the row reading
     * "Follow system" while the app was plainly not following it.
     */
    private fun matches(applied: String, tag: String): Boolean {
        val a = Locale.forLanguageTag(applied.substringBefore(','))
        val b = Locale.forLanguageTag(tag)
        if (!a.language.equals(b.language, ignoreCase = true)) return false
        // A region in the entry has to be honoured (pt-BR is not pt-PT); one only in the applied
        // tag does not disqualify the language-only entry.
        return b.country.isEmpty() || b.country.equals(a.country, ignoreCase = true)
    }

    private fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(locale)
        // Several languages return their endonym lowercased; a list of options reads better
        // capitalised, and the locale's own rules are the only correct way to do that.
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
