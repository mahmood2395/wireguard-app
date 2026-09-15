/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.GeoResolver
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wireguard.android.util.Pinger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Fragment containing a list of known WireGuard tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment(), MenuProvider {
    /** The action bar's Add button, once the menu is inflated. */
    private var addButton: View? = null

    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        // Portway: RTT to every tunnel's endpoint, ONCE each time the list becomes visible.
        //
        // It used to re-probe every ten seconds for as long as the screen was open, which is a
        // burst of ICMP — or a TCP connect per peer where ICMP is filtered — to every server the
        // user owns, forever, for a number that barely moves. A round-trip time is a property of
        // the peer, not a live meter: measuring it when the user arrives is what the reading is
        // for, and repeating it only spends radio and battery. repeatOnLifecycle(RESUMED) already
        // re-runs this every time the screen comes back, which is the "again when you look again"
        // half of that.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                run {
                    val tunnels = Application.getTunnelManager().getTunnels().toList()
                    tunnels.map { tunnel ->
                        async {
                            val host = runCatching {
                                tunnel.getConfigAsync().peers.firstOrNull()?.endpoint?.orElse(null)?.host
                            }.getOrNull() ?: return@async
                            // The row's meta line reads both off the tunnel. The place comes
                            // strictly from the cache — resolving one is something that happens
                            // while a tunnel is up, never on behalf of a list of configs that
                            // are down, which is the case that would leak a real address.
                            withContext(Dispatchers.Main.immediate) {
                                tunnel.onPeerLocated(host, GeoResolver.cached(host)?.label)
                            }
                            withContext(Dispatchers.Main.immediate) { tunnel.onPingStarted() }
                            val ms = Pinger.ping(host)
                            withContext(Dispatchers.Main.immediate) { tunnel.onPingResult(ms) }
                        }
                    }.awaitAll()
                }
            }
        }
        // A city resolved on the Home screen belongs on this list the moment it lands. With the
        // probe above now running once per visit, this is the only thing that keeps a row current
        // while the user is looking at it.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                GeoResolver.revision.collect {
                    Application.getTunnelManager().getTunnels().forEach { tunnel ->
                        tunnel.onPeerLocated(tunnel.endpointHost, GeoResolver.cached(tunnel.endpointHost)?.label)
                    }
                }
            }
        }
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getStringArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (key in checkedItems) actionModeListener.setItemChecked(key, true)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        binding?.executePendingBindings()
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    /** The add sheet. Reached from the action bar's Add button, which is a menu action view. */
    private fun onAddClicked() {
        if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null) return
        // The sheet answers on the FragmentManager it was shown from, so the listener and
        // show() must use the same one.
        childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                AddTunnelsSheet.REQUEST_CREATE ->
                    startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))

                AddTunnelsSheet.REQUEST_IMPORT -> tunnelFileImportResultLauncher.launch("*/*")

                AddTunnelsSheet.REQUEST_SCAN -> qrImportResultLauncher.launch(
                    ScanOptions()
                        .setOrientationLocked(false)
                        .setBeepEnabled(false)
                        .setPrompt(getString(R.string.qr_code_hint))
                )
            }
        }
        AddTunnelsSheet().showNow(childFragmentManager, "BOTTOM_SHEET")
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.tunnel_list, menu)
        addButton = menu.findItem(R.id.menu_add_config)?.actionView
        addButton?.setOnClickListener { onAddClicked() }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_add_config) return false
        onAddClicked()
        return true
    }

    /**
     * The detail screen is pushed onto the back stack over this list, which leaves this
     * fragment's view alive and its menu provider registered — so Add appeared in the detail
     * screen's action bar next to Edit. It is only offered at the root of this tab.
     */
    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.menu_add_config)?.isVisible =
            parentFragmentManager.backStackEntryCount == 0
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        // The view's scope, and a null check on the way back: getTunnels() waits for the configs
        // to load, and with the bottom navigation a user can leave this tab before that finishes
        // on a cold start. The fragment's own scope outlived the view, and binding!! then crashed.
        viewLifecycleOwner.lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            binding?.tunnels = tunnels
        }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                // Nocturne: the list is a chooser. Tapping a row switches to that config;
                // connecting and disconnecting live on Home, where the ring is. The chevron is
                // the way into detail, which used to be what the whole row did.
                binding.root.setOnClickListener { view ->
                    if (actionMode != null) {
                        actionModeListener.toggleItemChecked(item.name)
                    } else if (item.state != Tunnel.State.UP) {
                        // Routed through BaseFragment so the VPN consent round-trip is handled.
                        setTunnelState(view, true)
                    } else {
                        selectedTunnel = item
                    }
                }
                binding.tunnelDetailChevron.setOnClickListener {
                    if (actionMode == null) selectedTunnel = item
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(item.name)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(item.name))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.tunnelList)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    private inner class ActionModeListener : ActionMode.Callback {
        /**
         * Portway: keyed by tunnel name, not adapter position. Positions drift the moment the
         * list changes — with notifyDataSetChanged() on every change that was merely invisible,
         * but it means "delete" resolved `tunnels[position]` and could remove the wrong tunnel.
         */
        val checkedItems: MutableCollection<String> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<String> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = copyCheckedItems.mapNotNull { key -> tunnels[key] }
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        tunnels.forEach { setItemChecked(it.name, true) }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Portway: the bottom nav would sit under the contextual bar looking live.
            (activity as? MainActivity)?.setBottomNavVisible(false)
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(addButton, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            (activity as? MainActivity)?.setBottomNavVisible(true)
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(addButton, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(key: String, checked: Boolean) {
            if (checked) {
                checkedItems.add(key)
            } else {
                checkedItems.remove(key)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            // Position is only needed to repaint the row, and a wrong one here is cosmetic.
            lifecycleScope.launch {
                val index = Application.getTunnelManager().getTunnels().indexOfFirst { it.name == key }
                if (index >= 0) adapter?.notifyItemChanged(index)
            }
            updateTitle(actionMode)
        }

        fun toggleItemChecked(key: String) {
            setItemChecked(key, !checkedItems.contains(key))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {
                }

                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }

                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
