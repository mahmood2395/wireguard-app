/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.BuildConfig
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.wireguard.android.R
import com.wireguard.android.fragment.ConnectFragment
import com.wireguard.android.fragment.SettingsFragment
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.android.fragment.TunnelEditorFragment
import com.wireguard.android.fragment.TunnelListFragment
import com.wireguard.android.model.ObservableTunnel
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.util.ConfigDeepLink
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.util.UserKnobs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * CRUD interface for WireGuard tunnels. This activity serves as the main entry point to the
 * WireGuard application, and contains several fragments for listing, viewing details of, and
 * editing the configuration and interface state of WireGuard tunnels.
 */
class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null
    private var bottomNav: BottomNavigationView? = null
    private var currentDestination = R.id.dest_connect

    /**
     * Portway: the destination fragment is the *root* of the container and never goes on
     * the back stack, so `backStackEntryCount` keeps meaning exactly what it meant before
     * the navigation existed — which is what handleBackPressed and onBackStackChanged
     * are arithmetic on.
     */
    private val rootContainerId
        get() = if (isTwoPaneLayout) R.id.list_fragment else R.id.list_detail_container

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        // If the two-pane layout does not have an editor open, going back should exit the app.
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }

        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()

        // Deselect the current tunnel on navigating back from the detail pane to the one-pane list.
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    /**
     * Portway: the three-step introduction, shown once.
     *
     * Launched from here rather than replacing this activity, so onboarding finishing simply
     * reveals the app instead of rebuilding it. The knob lives in DataStore and can only be
     * read from a coroutine, which is why this happens after the first frame — a brief glimpse
     * of the app behind the introduction is a better trade than blocking startup on a disk read.
     *
     * A tunnel arriving by deep link skips it: someone who followed an import link is mid-task,
     * and an introduction dropped on top of that would look like the import failed.
     */
    private fun maybeShowOnboarding(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || intent?.data != null) return
        lifecycleScope.launch {
            if (!UserKnobs.onboardingComplete.first())
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
        }
    }

    override fun onBackStackChanged() {
        // Menus are per-destination now (Add on the Configs root, Edit inside a detail), so the
        // bar has to be rebuilt whenever the stack moves or both sets show at once.
        invalidateOptionsMenu()
        updateTitle()
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = backStackEntries >= 1
        if (actionBar == null) return
        // Do not show the home menu when the two-pane layout is at the detail view (see above).
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }

        currentDestination = savedInstanceState?.getInt(KEY_DESTINATION) ?: R.id.dest_connect
        bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)?.apply {
            // Nocturne's bar sits on the ground rather than floating, so it must clear the
            // gesture area itself. NavigationBarView consumes the system-bar inset as internal
            // padding and `paddingBottomSystemWindowInsets="false"` did not stop it, so inset
            // handling is taken over here and applied as bottom padding deliberately.
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets)
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
                v.setPadding(0, 0, 0, bottom)
                insets
            }
            // The content inset is the bar's REAL height, not a guessed dimen. It varies with
            // the gesture inset, font scale and label height; a fixed value was 30dp short here
            // and clipped the last card behind the bar.
            addOnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) applyNavHeight(bottom - top)
            }
            // isChecked, not selectedItemId: the latter fires the listener and would
            // re-commit the root fragment over the restored one.
            menu.findItem(currentDestination)?.isChecked = true
            setOnItemSelectedListener { item ->
                navigateTo(item.itemId)
                true
            }
        }
        applyPaneVisibility()
        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(rootContainerId, fragmentFor(currentDestination)) }
        }
        updateTitle()

        onBackStackChanged()
        handleImportIntent(intent)
        maybeShowOnboarding(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DESTINATION, currentDestination)
    }

    private fun fragmentFor(destinationId: Int) = when (destinationId) {
        R.id.dest_tunnels -> TunnelListFragment()
        R.id.dest_settings -> SettingsFragment()
        else -> ConnectFragment()
    }

    private fun titleFor(destinationId: Int) = when (destinationId) {
        R.id.dest_tunnels -> R.string.nav_configs
        R.id.dest_settings -> R.string.settings
        // Home is the wordmark, lower case, per the prototype's header.
        else -> R.string.wordmark
    }

    /**
     * The action bar shows the destination at a tab's root, and the tunnel's own name once
     * you are inside its detail or editor — otherwise every screen reads "Tunnels".
     */
    private fun updateTitle() {
        val name = selectedTunnel?.name
        if (supportFragmentManager.backStackEntryCount > 0 && !name.isNullOrEmpty()) {
            actionBar?.title = name
        } else {
            actionBar?.setTitle(titleFor(currentDestination))
        }
    }

    /**
     * Open the editor for the selected tunnel.
     *
     * Public because the detail screen now offers Edit as a button in its actions row, per the
     * Nocturne prototype, as well as in the action bar — and both must open the same editor on
     * the same container, which only this activity knows (it differs in the two-pane layout).
     */
    fun openEditor() {
        supportFragmentManager.commit {
            replace(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelEditorFragment())
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }
    }

    /**
     * Switch destination from a fragment. Nocturne's Home has two routes into the other tabs —
     * the days-left chip goes to Settings, the location card goes to Configs — and both must
     * move the nav bar's own selection, not just swap the fragment underneath it.
     */
    fun selectDestination(destinationId: Int) {
        bottomNav?.selectedItemId = destinationId
    }

    private fun navigateTo(destinationId: Int) {
        if (destinationId == currentDestination) {
            // Re-selecting the active tab returns to its root rather than doing nothing —
            // otherwise a detail or editor pane stays put and the tap feels broken.
            if (!isTwoPaneLayout) selectedTunnel = null
            return
        }
        // Clearing the selection pops every detail/editor entry via onSelectedTunnelChanged,
        // before the replace below — so there is no double pop and no orphaned detail pane.
        // Skipped on tablets, where the detail pane legitimately survives a left-pane switch.
        if (!isTwoPaneLayout) selectedTunnel = null
        supportFragmentManager.commit {
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            replace(rootContainerId, fragmentFor(destinationId))
        }
        currentDestination = destinationId
        applyPaneVisibility()
        updateTitle()
    }

    /**
     * On a tablet, give the detail pane to Tunnels only.
     *
     * The two-pane split exists for the tunnel list and its detail. Connect and Settings are
     * whole screens, but they are committed into the same 40%-wide master pane, so on a tablet
     * they rendered squeezed into the left third with the remaining two thirds permanently
     * blank. Collapsing the detail pane for those destinations lets the fragment take the full
     * width; the weights restore it on the way back to Tunnels.
     *
     * No-op on phones, where there is no detail pane to collapse.
     */
    private fun applyPaneVisibility() {
        if (!isTwoPaneLayout) return
        val wantsDetail = currentDestination == R.id.dest_tunnels
        findViewById<View?>(R.id.detail_container)?.visibility =
            if (wantsDetail) View.VISIBLE else View.GONE
    }

    /**
     * Push content and the hairline rule clear of the bar, using its measured height.
     *
     * Deliberately not `layout_dodgeInsetEdges`: that translates the whole container upward and
     * pushed the first row behind the action bar.
     *
     * `main_activity_container` is emphatically NOT in this list. It is the root Coordinator,
     * and the nav bar is one of its children — giving it the margin lifted the entire activity,
     * bar included, by the bar's own height, which is why the tabs sat 126dp up the screen with
     * a band of empty canvas underneath them.
     */
    private fun applyNavHeight(height: Int) {
        if (height <= 0) return
        listOf(R.id.list_detail_container, R.id.bottom_nav_rule).forEach { id ->
            findViewById<View?>(id)?.let { v ->
                (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.bottomMargin != height) {
                        lp.bottomMargin = height
                        v.layoutParams = lp
                    }
                }
            }
        }
    }

    /** Hidden during multi-select so the ActionMode owns the screen. */
    fun setBottomNavVisible(visible: Boolean) {
        bottomNav?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * Portway fork: handles the config-import deep link that upstream does not provide.
     * Reuses the QR-code import path, so the config is parse-validated and the user still
     * confirms a tunnel name before anything is written.
     */
    private fun handleImportIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!BuildConfig.IMPORT_SCHEME.equals(uri.scheme, ignoreCase = true)) return

        // Consume the payload so a rotation or process restart cannot re-import it.
        intent.data = null
        setIntent(intent)

        when (val result = ConfigDeepLink.parse(uri)) {
            is ConfigDeepLink.Result.Success ->
                TunnelImporter.importTunnel(supportFragmentManager, result.configText, result.suggestedName) { showImportMessage(it) }

            is ConfigDeepLink.Result.Failure -> {
                // The reason names the payload problem; the config itself is never logged.
                Log.w(TAG, "Rejected import deep link: ${result.reason}")
                showImportMessage(getString(R.string.deep_link_import_error))
            }
        }
    }

    private fun showImportMessage(message: CharSequence) {
        if (message.isEmpty()) return
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // The back arrow in the action bar should act the same as the back button.
                onBackPressedDispatcher.onBackPressed()
                true
            }

            // This menu item is handled by the editor fragment.
            R.id.menu_action_save -> false
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return false
        }

        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            // Clear everything off the back stack (all editors and detail fragments).
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        updateTitle()
        if (backStackEntries == 2) {
            // Pop the editor off the back stack to reveal the detail fragment. Use the immediate
            // method to avoid the editor picking up the new tunnel while it is still visible.
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            // Create and show a new detail fragment.
            fragmentManager.commit {
                add(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }

    companion object {
        private const val TAG = "Portway/MainActivity"
        private const val KEY_DESTINATION = "destination"
    }
}
