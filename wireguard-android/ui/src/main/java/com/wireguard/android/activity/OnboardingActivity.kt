/*
 * Copyright © 2026 Portway. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portway addition. Three screens shown once, on first run.
 *
 * The problem it solves is specific: a consumer is handed a .conf file and an app, and neither
 * tells them what WireGuard is, that there is no account, or why Android is about to show a
 * scary permission dialog. Upstream drops them straight into an empty tunnel list.
 *
 * Both CTAs that promise an action perform the real one. Step 2 opens the actual scanner and
 * imports what it reads; step 3 raises the actual VpnService consent dialog. A mock of either
 * would teach the user the wrong thing about the one screen they most need to trust.
 *
 * An Activity rather than a fragment inside MainActivity: MainActivity's back handling is
 * arithmetic on backStackEntryCount, and adding a root-level fragment that must not be counted
 * is exactly the kind of change that breaks it silently.
 */
package com.wireguard.android.activity

import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.TextView
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.R
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.util.UserKnobs
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {
    private var step = 0

    private lateinit var kicker: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var cta: MaterialButton
    private lateinit var content: View
    private lateinit var marks: List<View>

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents ?: return@registerForActivityResult
        lifecycleScope.launch {
            TunnelImporter.importTunnel(supportFragmentManager, qrCode) { message ->
                Snackbar.make(content, message, Snackbar.LENGTH_LONG).show()
            }
        }
        // Whether or not the config parsed, the user has done the scanning step; the snackbar
        // reports the outcome and the next screen is the one that explains the consent dialog.
        showStep(2)
    }

    /** The consent dialog's answer is not acted on here — connecting asks again anyway. */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.onboarding_activity)
        kicker = findViewById(R.id.onboard_kicker)
        title = findViewById(R.id.onboard_title)
        body = findViewById(R.id.onboard_body)
        cta = findViewById(R.id.onboard_cta)
        content = findViewById(R.id.step_content)
        marks = listOf(
            findViewById(R.id.step_mark_1),
            findViewById(R.id.step_mark_2),
            findViewById(R.id.step_mark_3),
        )

        bindLanguageSwitch()
        findViewById<View>(R.id.onboard_skip).setOnClickListener { finishOnboarding() }
        cta.setOnClickListener { onCtaClicked() }
        showStep(savedInstanceState?.getInt(KEY_STEP) ?: 0, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    private fun onCtaClicked() {
        when (step) {
            0 -> showStep(1)
            1 -> scanLauncher.launch(
                ScanOptions()
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt(getString(R.string.qr_code_hint))
            )
            // The real dialog. Null means consent already stands, and there is nothing to show.
            else -> VpnService.prepare(this)?.let { consentLauncher.launch(it) } ?: finishOnboarding()
        }
    }

    private fun showStep(next: Int, animate: Boolean = true) {
        step = next
        val (kickerRes, titleRes, bodyRes, ctaRes) = STEPS[step]
        kicker.setText(kickerRes)
        title.setText(titleRes)
        body.setText(bodyRes)
        cta.setText(ctaRes)
        marks.forEachIndexed { index, mark ->
            mark.setBackgroundColor(
                ContextCompat.getColor(this, if (index <= step) R.color.accent else R.color.rule)
            )
        }
        if (!animate) return
        // Rise and fade, matching the disclosure on the detail screen: content that arrives
        // from below reads as the next thing rather than as a different screen.
        content.alpha = 0f
        content.translationY = 16f * resources.displayMetrics.density
        content.animate().alpha(1f).translationY(0f).setDuration(450).start()
    }

    /**
     * The two-language switch.
     *
     * It is on this screen because the first run is where being unable to read the app costs the
     * most: everything after it asks the user to trust something — a permission dialog, a config
     * someone sent them — and none of that survives being illegible. Waiting until they can find
     * Settings assumes they can read Settings.
     *
     * Choosing recreates the activity, which is why [step] is in the saved state: a user who
     * switches language on step 2 must not be sent back to step 1.
     */
    private fun bindLanguageSwitch() {
        val en = findViewById<TextView>(R.id.lang_en)
        val fa = findViewById<TextView>(R.id.lang_fa)
        // Whatever is on screen right now, whether it was pinned here or inherited from the
        // phone. A system language that is neither marks English, because English is in fact
        // what most of this app's own strings are rendering in for that user.
        val persian = currentLanguage() == "fa"
        paintSegment(fa, persian)
        paintSegment(en, !persian)
        en.setOnClickListener { setLanguage("en") }
        fa.setOnClickListener { setLanguage("fa") }
    }

    private fun currentLanguage(): String {
        val applied = AppCompatDelegate.getApplicationLocales()
        val locale = if (!applied.isEmpty) applied[0] else resources.configuration.locales[0]
        return locale?.language ?: Locale.ENGLISH.language
    }

    private fun paintSegment(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.lang_segment_active else 0)
        view.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.accent_300 else R.color.clay_text_muted)
        )
    }

    private fun setLanguage(tag: String) {
        if (currentLanguage() == tag) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun finishOnboarding() {
        // Written before finishing, not after: if the process dies on the way out, the user has
        // still seen this and must not see it again. MainActivity is underneath — it launched
        // this and stayed alive — so finishing reveals it rather than starting a second copy.
        lifecycleScope.launch {
            UserKnobs.setOnboardingComplete(true)
            finish()
        }
    }

    private data class Step(val kicker: Int, val title: Int, val body: Int, val cta: Int)

    private companion object {
        const val KEY_STEP = "step"

        val STEPS = listOf(
            Step(R.string.onboard_kicker_1, R.string.onboard_title_1, R.string.onboard_body_1, R.string.onboard_cta_1),
            Step(R.string.onboard_kicker_2, R.string.onboard_title_2, R.string.onboard_body_2, R.string.onboard_cta_2),
            Step(R.string.onboard_kicker_3, R.string.onboard_title_3, R.string.onboard_body_3, R.string.onboard_cta_3),
        )
    }
}
