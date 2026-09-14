/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableList
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.BR
import java.lang.ref.WeakReference

/**
 * A generic `RecyclerView.Adapter` backed by a `ObservableKeyedArrayList`.
 */
class ObservableKeyedRecyclerViewAdapter<K, E : Keyed<out K>> internal constructor(
        context: Context, private val layoutId: Int,
        list: ObservableKeyedArrayList<K, E>?
) : RecyclerView.Adapter<ObservableKeyedRecyclerViewAdapter.ViewHolder>() {
    private val callback = OnListChangedCallback(this)

    init {
        // Stable ids let the default item animator match rows across a change, so inserts,
        // removals and moves animate instead of the whole list blinking. getItemId already
        // derives from the key.
        setHasStableIds(true)
    }
    private val layoutInflater: LayoutInflater = LayoutInflater.from(context)
    private var list: ObservableKeyedArrayList<K, E>? = null
    private var rowConfigurationHandler: RowConfigurationHandler<ViewDataBinding, Any>? = null

    private fun getItem(position: Int): E? = if (list == null || position < 0 || position >= list!!.size) null else list?.get(position)

    override fun getItemCount() = list?.size ?: 0

    override fun getItemId(position: Int) = (getKey(position)?.hashCode() ?: -1).toLong()

    private fun getKey(position: Int): K? = getItem(position)?.key

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.setVariable(BR.collection, list)
        holder.binding.setVariable(BR.key, getKey(position))
        holder.binding.setVariable(BR.item, getItem(position))
        holder.binding.executePendingBindings()
        if (rowConfigurationHandler != null) {
            val item = getItem(position)
            if (item != null) {
                rowConfigurationHandler?.onConfigureRow(holder.binding, item, position)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(DataBindingUtil.inflate(layoutInflater, layoutId, parent, false))

    fun setList(newList: ObservableKeyedArrayList<K, E>?) {
        list?.removeOnListChangedCallback(callback)
        list = newList
        list?.addOnListChangedCallback(callback)
        notifyDataSetChanged()
    }

    fun setRowConfigurationHandler(rowConfigurationHandler: RowConfigurationHandler<*, *>?) {
        @Suppress("UNCHECKED_CAST")
        this.rowConfigurationHandler = rowConfigurationHandler as? RowConfigurationHandler<ViewDataBinding, Any>
    }

    interface RowConfigurationHandler<B : ViewDataBinding, T> {
        fun onConfigureRow(binding: B, item: T, position: Int)
    }

    private class OnListChangedCallback<E : Keyed<*>> constructor(adapter: ObservableKeyedRecyclerViewAdapter<*, E>) : ObservableList.OnListChangedCallback<ObservableList<E>>() {
        private val weakAdapter: WeakReference<ObservableKeyedRecyclerViewAdapter<*, E>> = WeakReference(adapter)

        override fun onChanged(sender: ObservableList<E>) {
            val adapter = weakAdapter.get()
            if (adapter != null)
                adapter.notifyDataSetChanged()
            else
                sender.removeOnListChangedCallback(this)
        }

        /*
         * Portway: upstream funnelled every one of these into notifyDataSetChanged(), which
         * throws away the range information the observable list already provides and kills
         * all item animations. The backing ObservableSortedKeyedArrayList reports precisely
         * what changed, so honour it.
         */

        override fun onItemRangeChanged(sender: ObservableList<E>, positionStart: Int,
                                        itemCount: Int) {
            withAdapter(sender) { notifyItemRangeChanged(positionStart, itemCount) }
        }

        override fun onItemRangeInserted(sender: ObservableList<E>, positionStart: Int,
                                         itemCount: Int) {
            withAdapter(sender) { notifyItemRangeInserted(positionStart, itemCount) }
        }

        override fun onItemRangeMoved(sender: ObservableList<E>, fromPosition: Int,
                                      toPosition: Int, itemCount: Int) {
            withAdapter(sender) {
                // RecyclerView has no range-move; a moved run is emitted one row at a time.
                repeat(itemCount) { offset -> notifyItemMoved(fromPosition + offset, toPosition + offset) }
            }
        }

        override fun onItemRangeRemoved(sender: ObservableList<E>, positionStart: Int,
                                        itemCount: Int) {
            withAdapter(sender) { notifyItemRangeRemoved(positionStart, itemCount) }
        }

        private inline fun withAdapter(sender: ObservableList<E>, block: ObservableKeyedRecyclerViewAdapter<*, E>.() -> Unit) {
            val adapter = weakAdapter.get()
            if (adapter != null) adapter.block() else sender.removeOnListChangedCallback(this)
        }

    }

    class ViewHolder(val binding: ViewDataBinding) : RecyclerView.ViewHolder(binding.root)

    init {
        setList(list)
    }
}
