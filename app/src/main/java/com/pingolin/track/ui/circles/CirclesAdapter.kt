/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Track.
 *
 * Pingolin Track is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Track is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.track.ui.circles

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.domain.repository.LockedCircle
import com.pingolin.track.databinding.ItemCircleBinding
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Circles list with drag-to-reorder, mirroring PlacesAdapter. A plain adapter
 * (not ListAdapter) so optimistic moveItem() during a drag isn't fought by an
 * async differ; submitList() is suppressed while dragging/settling so the
 * persisted re-emit can't snap the row back. A trailing "loading" row is shown
 * during pagination.
 */
class CirclesAdapter(
    private val onCircleClick: (CircleEntity) -> Unit,
    private val onLeaveClick: (CircleEntity) -> Unit,
    private val onDeleteClick: (CircleEntity) -> Unit,
    private val onInviteClick: (CircleEntity) -> Unit = {},
    private val onOrderChanged: (List<CircleEntity>) -> Unit = {},
    /** Whether a row is a circle this device cannot open; see CircleRepository.isCircleLocked. */
    private val isLocked: (CircleEntity) -> Boolean = { isKeyUnavailable(it.name) },
    /** A locked row's info button: explain why it is locked. */
    private val onLockedInfoClick: (CircleEntity) -> Unit = {},
    /** The row's waiting-to-sync line: open that circle's list of changes. */
    private val onPendingClick: (CircleEntity) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CIRCLE = 0
        private const val VIEW_TYPE_LOADING = 1

        /**
         * The name a row shows: any placeholder, including the raw hex or blob
         * an older app version stored for a failed decrypt, reads as the one
         * [LockedCircle.PLACEHOLDER_NAME].
         */
        fun sanitizeCircleName(name: String): String {
            return when {
                name.isBlank() -> "Circle (unnamed)"
                LockedCircle.isPlaceholder(name) -> LockedCircle.PLACEHOLDER_NAME
                else -> name
            }
        }

        /**
         * True when the stored name is a placeholder rather than a real name, i.e.
         * this device holds no usable key for the circle. The fallback for
         * [isLocked]; the Circles screen passes CircleRepository's answer, which also
         * knows a circle somebody really named "Locked circle".
         */
        fun isKeyUnavailable(name: String): Boolean = LockedCircle.isPlaceholder(name)
    }

    private val items = mutableListOf<CircleEntity>()
    private var itemTouchHelper: ItemTouchHelper? = null
    private var isDragging = false
    private var isSettling = false
    private val handler = Handler(Looper.getMainLooper())
    private var settlingRunnable: Runnable? = null
    private var isLoadingMore = false

    /** Circle id -> re-key warning (string resource); see CirclesViewModel.rekeyWarnings. */
    private var rekeyWarnings: Map<String, Int> = emptyMap()

    fun setRekeyWarnings(warnings: Map<String, Int>) {
        if (warnings == rekeyWarnings) return
        rekeyWarnings = warnings
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    /** Circle id -> its line about changes waiting to sync; see CirclesViewModel. */
    private var pendingLines: Map<String, String> = emptyMap()

    fun setPendingLines(lines: Map<String, String>) {
        if (lines == pendingLines) return
        pendingLines = lines
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    /** The circles whose card offers Invite: an owner's or admin's, where it takes effect; see
     * CirclesViewModel.
     */
    private var inviteOffered: Set<String> = emptySet()

    fun setInviteOffered(circleIds: Set<String>) {
        if (circleIds == inviteOffered) return
        inviteOffered = circleIds
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun setItemTouchHelper(helper: ItemTouchHelper) {
        itemTouchHelper = helper
    }

    fun submitList(list: List<CircleEntity>?) {
        // Don't apply DB updates mid-drag or in the brief settle window after
        // one — the optimistic move is the source of truth until persistence
        // completes, otherwise the row snaps back to its old position.
        if (isDragging || isSettling) return

        val newList = list ?: emptyList()
        val sameOrder = newList.size == items.size &&
            newList.indices.all { newList[it].id == items[it].id }

        if (sameOrder) {
            // Same circles in the same order — e.g. the re-emit right after a
            // reorder (already matches the optimistic move), or a no-op refresh.
            // Rebind only if a displayed field actually changed.
            val contentChanged = newList.indices.any { newList[it] != items[it] }
            items.clear()
            items.addAll(newList)
            if (contentChanged && items.isNotEmpty()) {
                notifyItemRangeChanged(0, items.size)
            }
        } else {
            // Structural change (added/removed/renamed/reordered by the server).
            // Small list — a full refresh is imperceptible and avoids any
            // footer position math.
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }

    fun setLoadingMore(loading: Boolean) {
        if (isLoadingMore != loading) {
            isLoadingMore = loading
            if (loading) {
                notifyItemInserted(itemCount)
            } else {
                notifyItemRemoved(itemCount)
            }
        }
    }

    override fun getItemCount(): Int = items.size + if (isLoadingMore) 1 else 0

    override fun getItemViewType(position: Int): Int {
        return if (isLoadingMore && position == itemCount - 1) VIEW_TYPE_LOADING else VIEW_TYPE_CIRCLE
    }

    fun onDragStart() {
        isDragging = true
        isSettling = false
        handler.removeCallbacksAndMessages(null)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        // Guard the loading footer (which lives at items.size) and bounds.
        if (fromPosition < 0 || toPosition < 0 ||
            fromPosition >= items.size || toPosition >= items.size
        ) {
            return
        }
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onDragComplete() {
        isDragging = false
        isSettling = true
        onOrderChanged(items.toList())
        // Allow DB updates again once the persist + re-emit have settled.
        settlingRunnable?.let { handler.removeCallbacks(it) }
        settlingRunnable = Runnable { isSettling = false }
        handler.postDelayed(settlingRunnable!!, 300)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        handler.removeCallbacksAndMessages(null)
        settlingRunnable = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LOADING -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_loading, parent, false)
                LoadingViewHolder(view)
            }
            else -> {
                val binding = ItemCircleBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                CircleViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CircleViewHolder -> holder.bind(items[position])
            is LoadingViewHolder -> { /* Nothing to bind */ }
        }
    }

    inner class CircleViewHolder(
        private val binding: ItemCircleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun bind(circle: CircleEntity) {
            val locked = isLocked(circle)
            binding.tvCircleName.text =
                if (locked) LockedCircle.PLACEHOLDER_NAME else sanitizeCircleName(circle.name)
            binding.tvCreatedAt.text = "Created ${dateFormat.format(Date(circle.createdAt))}"

            // A placeholder name is the only signal the user gets that this
            // device cannot open the circle, and on its own it explains nothing.
            // The button explains why — which depends on what happened to the key,
            // so the Circles screen works it out rather than this row.
            binding.btnKeyUnavailableInfo.visibility =
                if (locked) View.VISIBLE else View.GONE
            binding.btnKeyUnavailableInfo.setOnClickListener { onLockedInfoClick(circle) }

            val warning = rekeyWarnings[circle.id]
            binding.tvRekeyWarning.visibility = if (warning != null) View.VISIBLE else View.GONE
            if (warning != null) binding.tvRekeyWarning.setText(warning)

            val pending = pendingLines[circle.id]
            binding.tvPendingChanges.visibility = if (pending != null) View.VISIBLE else View.GONE
            binding.tvPendingChanges.text = pending
            binding.tvPendingChanges.setOnClickListener { onPendingClick(circle) }

            if (circle.isOwner) {
                binding.chipOwner.visibility = View.VISIBLE
                binding.btnLeave.visibility = View.GONE
                binding.btnDelete.visibility = View.VISIBLE
            } else {
                binding.chipOwner.visibility = View.GONE
                binding.btnLeave.visibility = View.VISIBLE
                binding.btnDelete.visibility = View.GONE
            }
            // An owner's and an admin's, where the invitation takes effect.
            binding.btnInvite.visibility = if (circle.id in inviteOffered) View.VISIBLE else View.GONE

            // Key rotation is fully automatic — no badge needed, no user action possible.

            binding.root.setOnClickListener { onCircleClick(circle) }
            binding.btnLeave.setOnClickListener { onLeaveClick(circle) }
            binding.btnDelete.setOnClickListener { onDeleteClick(circle) }
            binding.btnInvite.setOnClickListener { onInviteClick(circle) }

            // Long press starts a reorder drag (matches Places).
            binding.root.setOnLongClickListener {
                itemTouchHelper?.startDrag(this)
                true
            }
        }
    }

    class LoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
