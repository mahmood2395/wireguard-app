/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.BaseActivity
import com.wireguard.android.activity.BaseActivity.OnSelectedTunnelChangedListener
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.ConnectFragmentBinding
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.util.SessionGuard

/**
 * Base class for fragments that need to know the currently-selected tunnel. Only does anything when
 * attached to a `BaseActivity`.
 */
abstract class BaseFragment : Fragment(), OnSelectedTunnelChangedListener {
    private var pendingTunnel: ObservableTunnel? = null
    private var pendingTunnelUp: Boolean? = null
    private val permissionActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val tunnel = pendingTunnel
        val checked = pendingTunnelUp
        if (tunnel != null && checked != null)
            setTunnelStateWithPermissionsResult(tunnel, checked)
        pendingTunnel = null
        pendingTunnelUp = null
    }

    protected var selectedTunnel: ObservableTunnel?
        get() = (activity as? BaseActivity)?.selectedTunnel
        protected set(tunnel) {
            (activity as? BaseActivity)?.selectedTunnel = tunnel
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (activity as? BaseActivity)?.addOnSelectedTunnelChangedListener(this)
    }

    override fun onDetach() {
        (activity as? BaseActivity)?.removeOnSelectedTunnelChangedListener(this)
        super.onDetach()
    }

    fun setTunnelState(view: View, checked: Boolean) {
        val tunnel = when (val binding = DataBindingUtil.findBinding<ViewDataBinding>(view)) {
            is TunnelDetailFragmentBinding -> binding.tunnel
            is TunnelListItemBinding -> binding.item
            // Portway: without this branch the Connect screen's control is inert — the
            // `when` falls through to `else` and returns silently, with no crash or log.
            is ConnectFragmentBinding -> binding.tunnel
            else -> return
        } ?: return
        val activity = activity ?: return
        activity.lifecycleScope.launch {
            if (Application.getBackend() is GoBackend) {
                try {
                    val intent = GoBackend.VpnService.prepare(activity)
                    if (intent != null) {
                        pendingTunnel = tunnel
                        pendingTunnelUp = checked
                        permissionActivityResultLauncher.launch(intent)
                        return@launch
                    }
                } catch (e: Throwable) {
                    val message = activity.getString(R.string.error_prepare, ErrorMessages[e])
                    Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                        // The FAB is gone in the Nocturne list; an absent anchor is fine and
                        // lets the Snackbar sit above the nav bar via layout_insetEdge.
                        .setAnchorView(view.findViewById<View?>(R.id.create_fab))
                        .show()
                    Log.e(TAG, message, e)
                }
            }
            setTunnelStateWithPermissionsResult(tunnel, checked)
        }
    }

    /**
     * Portway: called on both the success and failure paths once a state change has
     * finished, including after the permission round trip. Default no-op, so every
     * existing screen keeps its current behaviour; Connect overrides it to leave its
     * synthesized CONNECTING phase.
     */
    protected open fun onTunnelStateChangeFinished(tunnel: ObservableTunnel, requestedUp: Boolean, error: Throwable?) {}

    /**
     * Portway: a state change is about to start that the screen did not initiate itself — the
     * "Use here instead" retry from the account-in-use dialog. Connect overrides it to re-enter
     * its CONNECTING phase, which its own tap handler normally sets.
     */
    protected open fun onTunnelStateChangeStarting(tunnel: ObservableTunnel, requestedUp: Boolean) {}

    private fun setTunnelStateWithPermissionsResult(tunnel: ObservableTunnel, checked: Boolean, takeover: Boolean = false) {
        val activity = activity ?: return
        activity.lifecycleScope.launch {
            try {
                tunnel.setStateAsync(Tunnel.State.of(checked), takeover = takeover)
                onTunnelStateChangeFinished(tunnel, checked, null)
            } catch (e: SessionGuard.AccountInUseException) {
                // Not an error: the panel said this config is live on another device. Finish the
                // request quietly, then ask — a Snackbar reading "could not connect" would send
                // the user to support, which is the exact cost this check exists to remove.
                onTunnelStateChangeFinished(tunnel, checked, e)
                showAccountInUse(tunnel, e)
            } catch (e: Throwable) {
                onTunnelStateChangeFinished(tunnel, checked, e)
                val error = ErrorMessages[e]
                val messageResId = if (checked) R.string.error_up else R.string.error_down
                val message = activity.getString(messageResId, error)
                val view = view
                if (view != null)
                    Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                        // The FAB is gone in the Nocturne list; an absent anchor is fine and
                        // lets the Snackbar sit above the nav bar via layout_insetEdge.
                        .setAnchorView(view.findViewById<View?>(R.id.create_fab))
                        .show()
                else
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message, e)
            }
        }
    }

    private fun showAccountInUse(tunnel: ObservableTunnel, e: SessionGuard.AccountInUseException) {
        val context = context ?: return
        val device = e.otherDevice ?: getString(R.string.session_other_device_unknown)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.session_dialog_title)
            .setMessage(getString(R.string.session_dialog_message, tunnel.name, device))
            .setPositiveButton(R.string.session_use_here) { _, _ ->
                onTunnelStateChangeStarting(tunnel, true)
                setTunnelStateWithPermissionsResult(tunnel, true, takeover = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "WireGuard/BaseFragment"
    }
}
