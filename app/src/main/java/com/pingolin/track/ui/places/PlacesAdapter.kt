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

package com.pingolin.track.ui.places

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.google.android.material.color.MaterialColors
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.databinding.ItemPlaceBinding
import com.pingolin.track.util.PlaceIconUtils
import java.util.Collections

class PlacesAdapter(
    private val onPlaceClick: (PlaceEntity) -> Unit,
    private val onEditClick: (PlaceEntity) -> Unit,
    private val onAlertClick: (PlaceEntity) -> Unit,
    private val onShareClick: (PlaceEntity) -> Unit,
    private val onDeleteClick: (PlaceEntity) -> Unit,
    private val onOrderChanged: (List<PlaceEntity>) -> Unit = {},
    private val hasAlertsEnabled: (String) -> Boolean = { false },
    /** Every alert on this place is dormant because its target opted out. */
    private val allAlertsSuppressed: (String) -> Boolean = { false }
) : RecyclerView.Adapter<PlacesAdapter.PlaceViewHolder>() {

    private var itemTouchHelper: ItemTouchHelper? = null
    private val items = mutableListOf<PlaceEntity>()
    private var isDragging = false
    private var isSettling = false
    private val handler = Handler(Looper.getMainLooper())
    private var settlingRunnable: Runnable? = null

    fun setItemTouchHelper(helper: ItemTouchHelper) {
        itemTouchHelper = helper
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun submitList(list: List<PlaceEntity>?) {
        // Don't update if we're currently dragging or settling after a drag
        if (isDragging || isSettling) {
            return
        }

        val newList = list ?: emptyList()

        // Check if it's the same items in the same order (ignoring sortOrder changes)
        val isSameContent = newList.size == items.size &&
            newList.zip(items).all { (new, old) ->
                new.id == old.id &&
                new.name == old.name &&
                new.address == old.address
            }

        if (isSameContent) {
            // Just update internal list without notifying (for sortOrder-only changes)
            items.clear()
            items.addAll(newList)
            return
        }

        // Use DiffUtil for efficient updates
        val diffCallback = PlaceDiffCallback(items.toList(), newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        items.clear()
        items.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }

    fun onDragStart() {
        isDragging = true
        isSettling = false
        handler.removeCallbacksAndMessages(null)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || toPosition < 0 ||
            fromPosition >= items.size || toPosition >= items.size) {
            return
        }
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onDragComplete() {
        isDragging = false
        isSettling = true
        // Notify about the new order
        onOrderChanged(items.toList())
        // Allow list updates again after a short delay to let the database transaction complete
        // Track the runnable to prevent memory leaks
        settlingRunnable?.let { handler.removeCallbacks(it) }
        settlingRunnable = Runnable { isSettling = false }
        handler.postDelayed(settlingRunnable!!, 300)
    }

    /**
     * Clean up handler callbacks when adapter is detached to prevent memory leaks.
     */
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        handler.removeCallbacksAndMessages(null)
        settlingRunnable = null
    }

    inner class PlaceViewHolder(
        private val binding: ItemPlaceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: PlaceEntity) {
            binding.tvPlaceName.text = place.name

            // Show address if available, otherwise show coordinates
            binding.tvAddress.text = place.address
                ?: com.pingolin.track.util.coordinatesText(place.latitude, place.longitude)

            // Show emoji for place icon
            binding.tvIcon.text = PlaceIconUtils.getEmoji(place.icon)


            // "SHARED" badge — visible only when this place was shared
            // by another circle member (wrap_via_circle is set on the
            // server response and persisted on the entity). Without
            // this the user can't tell their own places apart from
            // received shares at a glance.
            val isReceivedShare = !place.wrapViaCircle.isNullOrBlank()
            if (isReceivedShare) {
                binding.tvSharedBadge.visibility = View.VISIBLE
                val badgeBgColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiaryContainer)
                binding.tvSharedBadge.background.setTint(badgeBgColor)
                val badgeTextColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnTertiaryContainer)
                binding.tvSharedBadge.setTextColor(badgeTextColor)
            } else {
                binding.tvSharedBadge.visibility = View.GONE
            }
            // Hide Edit + Delete + Share on places another circle
            // member shared with this user — they don't own the
            // place's metadata, so editing or deleting the row
            // would either fail server-side or stomp the sharer's
            // data.
            binding.btnEdit.visibility = if (isReceivedShare) View.GONE else View.VISIBLE
            binding.btnShare.visibility = if (isReceivedShare) View.GONE else View.VISIBLE
            binding.btnDelete.visibility = if (isReceivedShare) View.GONE else View.VISIBLE

            // Alert bell: the single, consistent entry point to the unified
            // place-alert editor (create/edit, boundary-aware, explicit Save/
            // Remove) for BOTH own places and received shares. It opens a dialog
            // rather than toggling anything, so it is safe on every row.
            // Deliberately NOT hidden alongside Edit/Share/Delete above: setting
            // your own alert on a place somebody shared with you is what the
            // share is for, and this is the only route to the editor from here.
            binding.btnAlert.visibility = View.VISIBLE
            val alertsOn = hasAlertsEnabled(place.id)
            // THREE states, not two. A place whose every alert is suppressed by the
            // target's `allow_alerts_on_me` opt-out keeps the filled bell (the alerts
            // exist and are configured) but takes the muted tint (they cannot fire).
            // Rendering it as "alerts on" would promise notifications that will never
            // arrive; rendering it as "no alerts" would hide configuration the user
            // still owns and can still edit.
            val suppressed = alertsOn && allAlertsSuppressed(place.id)
            binding.btnAlert.setImageResource(
                if (alertsOn) R.drawable.ic_notifications else R.drawable.ic_alert
            )
            binding.btnAlert.contentDescription = binding.root.context.getString(
                if (suppressed) R.string.place_alert_suppressed_by_target
                else R.string.place_alert_button
            )
            // Tint explicitly, in both states. The two drawables disagree about
            // colour: ic_alert carries android:tint="?attr/colorOnSurface", while
            // ic_notifications is a bare fillColor="#FFFFFF". With no tint on the
            // button, a place WITH an alert would draw a WHITE bell on the card —
            // and @color/surface_container is #ffffff in the light theme, so it
            // would score 1.00:1 and be invisible: present and clickable, but
            // reading as "this place has no alert affordance".
            binding.btnAlert.imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.root,
                    // colorPrimary lives in appcompat, not material —
                    // material does not redeclare it.
                    // Suppressed shares the muted attr with "no alerts"
                    // rather than introducing a new colour: that attr
                    // is already proven against this card in both
                    // themes, and a wrong attr in this spot can render
                    // at 1.00:1 contrast.
                    if (alertsOn && !suppressed) {
                        androidx.appcompat.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    },
                )
            )

            binding.root.setOnClickListener { onPlaceClick(place) }
            binding.btnEdit.setOnClickListener { onEditClick(place) }
            binding.btnAlert.setOnClickListener { onAlertClick(place) }
            binding.btnShare.setOnClickListener { onShareClick(place) }
            binding.btnDelete.setOnClickListener { onDeleteClick(place) }

            // Long press on the whole item starts drag
            binding.root.setOnLongClickListener {
                itemTouchHelper?.startDrag(this)
                true
            }
        }
    }

    class PlaceDiffCallback(
        private val oldList: List<PlaceEntity>,
        private val newList: List<PlaceEntity>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            // Compare all fields except sortOrder
            return old.id == new.id &&
                    old.circleId == new.circleId &&
                    old.name == new.name &&
                    old.address == new.address &&
                    old.latitude == new.latitude &&
                    old.longitude == new.longitude &&
                    old.radius == new.radius &&
                    old.icon == new.icon &&
                    old.color == new.color &&
                    old.placeType == new.placeType
        }
    }
}
