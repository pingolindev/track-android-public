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

package com.pingolin.track.ui.destinations

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.pingolin.track.R
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.databinding.ItemDestinationBinding
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.ui.circles.MemberSharing
import com.pingolin.track.ui.circles.SharingStatus

/**
 * A destination plus its resolved saved-place name (within ~100m of the
 * destination coordinates, computed in the ViewModel). The row shows
 * [placeName] as the title and the destination's stored address line
 * ([DestinationEntity.destName]) beneath it.
 */
data class DestinationListItem(
    val destination: DestinationEntity,
    val placeName: String?,
    /** Creator's display name, or null for your own rows / an unresolvable member. */
    val creatorName: String? = null,
    /** Created by the signed-in user — gates Arrive / Cancel / Delete, which the
     *  backend allows only to the creator. */
    val isMine: Boolean = true,
    /** Hidden from this device's map. Drives the Hide/Show-again label. */
    val isHidden: Boolean = false,
    /** Whether the person going there shares their location in its circle now. */
    val creatorSharing: SharingStatus = SharingStatus.On,
)

/**
 * While the person going to an active destination is paused, or has real-time sharing off, in its
 * circle, their row says so in place of an ETA nothing moves on. The destination stays active
 * meanwhile: the server neither ends it nor claims an arrival, either of which would tell the
 * circle something. Never for your own, or one that has ended. Null: the row shows its usual line.
 */
internal fun DestinationListItem.notSharingStatus(): SharingStatus? =
    creatorSharing.takeIf {
        it != SharingStatus.On && !isMine && destination.status == DestinationRepository.STATUS_ACTIVE
    }

class DestinationAdapter(
    private val onDestinationClick: (DestinationEntity) -> Unit,
    private val onArriveClick: (DestinationEntity) -> Unit,
    private val onCancelClick: (DestinationEntity) -> Unit,
    private val onDeleteClick: (DestinationEntity) -> Unit,
    /** Hide or show again on this device. Offered on EVERY row, including other
     *  members', which is the point: those are the ones that cannot be
     *  cancelled, so hiding is the only way to clear them off the map. */
    private val onToggleHidden: (DestinationListItem) -> Unit,
    /** Formats an ETA day-bucket into the user's preferred calendar-date string. */
    private val formatEtaDate: (Int) -> String
) : ListAdapter<DestinationListItem, DestinationAdapter.DestinationViewHolder>(DestinationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestinationViewHolder {
        val binding = ItemDestinationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DestinationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DestinationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DestinationViewHolder(
        private val binding: ItemDestinationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DestinationListItem) {
            val destination = item.destination
            // A blank name is no name: it would title the row with nothing.
            val placeName = item.placeName?.takeIf { it.isNotBlank() }
            // The stored name doubles as the address line: the create flow seeds
            // destName with the place's address (or the searched address line).
            val addressLine = destination.destName?.takeIf { it.isNotBlank() }

            // Title: prefer the saved-place name ("Home"); fall back to the stored
            // address line, then coordinates.
            binding.tvDestName.text = placeName
                ?: addressLine
                ?: com.pingolin.track.util.coordinatesText(destination.destLatitude, destination.destLongitude)

            // Secondary line: the address, shown only when it adds information
            // beyond the title (i.e. we titled the row with a place name and the
            // address line is a distinct, non-blank string). Otherwise the title
            // already IS the address, so hide it to avoid a duplicate line.
            val subtitle = addressLine?.takeIf { placeName != null && it != placeName }
            if (subtitle != null) {
                binding.tvDestAddress.text = subtitle
                binding.tvDestAddress.visibility = View.VISIBLE
            } else {
                binding.tvDestAddress.visibility = View.GONE
            }

            // Show status
            val statusText = when (destination.status) {
                DestinationRepository.STATUS_ACTIVE -> binding.root.context.getString(R.string.destinations_status_en_route)
                DestinationRepository.STATUS_ARRIVED -> binding.root.context.getString(R.string.destinations_status_arrived)
                DestinationRepository.STATUS_CANCELLED -> binding.root.context.getString(R.string.destinations_status_cancelled)
                DestinationRepository.STATUS_EXPIRED -> binding.root.context.getString(R.string.destinations_status_expired)
                else -> destination.status
            }
            binding.tvStatus.text = statusText

            // Status icon/color
            val isActive = destination.status == DestinationRepository.STATUS_ACTIVE
            binding.tvStatus.alpha = if (isActive) 1.0f else 0.6f

            // While the person going there isn't sharing, why, in place of an ETA.
            val notSharing = item.notSharingStatus()?.let {
                MemberSharing.rowLabel(binding.root.context, it, System.currentTimeMillis())
            }
            // Show ETA if available, formatted as a real calendar date rather
            // than the raw epoch-day bucket (which would read as "ETA: Day
            // 20603").
            val etaBucket = destination.etaDayBucket
            if (notSharing != null) {
                binding.tvEta.visibility = View.VISIBLE
                binding.tvEta.text = notSharing
            } else if (etaBucket != null) {
                binding.tvEta.visibility = View.VISIBLE
                binding.tvEta.text = binding.root.context.getString(R.string.destinations_eta, formatEtaDate(etaBucket))
            } else {
                binding.tvEta.visibility = View.GONE
            }

            // Show/hide action buttons based on status. Active rows show
            // Arrive/Cancel; finished rows (cancelled/arrived/expired) show
            // Delete so the user can clear the row from the list.
            // Arrive/Cancel/Delete are creator-only — the backend refuses them
            // for anyone else — so a shared row shows none of them. It still
            // shows Hide, which is the only action that IS available on it.
            if (isActive && item.isMine) {
                binding.btnArrive.visibility = View.VISIBLE
                binding.btnCancel.visibility = View.VISIBLE
                binding.btnDelete.visibility = View.GONE
            } else if (!isActive && item.isMine) {
                binding.btnArrive.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                binding.btnDelete.visibility = View.VISIBLE
            } else {
                binding.btnArrive.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                binding.btnDelete.visibility = View.GONE
            }

            // Attribution: the list carries every member's destinations, so whose
            // it is has to be on the row or the extra entries are a mystery.
            binding.tvCreator.visibility = if (item.isMine) View.GONE else View.VISIBLE
            binding.tvCreator.text = item.creatorName
                ?.let { binding.root.context.getString(R.string.destination_shared_by, it) }
                ?: binding.root.context.getString(R.string.destination_shared_by_member)

            binding.btnHide.text = binding.root.context.getString(
                if (item.isHidden) R.string.destination_show_again else R.string.destination_hide
            )

            binding.root.setOnClickListener { onDestinationClick(destination) }
            binding.btnArrive.setOnClickListener { onArriveClick(destination) }
            binding.btnCancel.setOnClickListener { onCancelClick(destination) }
            binding.btnDelete.setOnClickListener { onDeleteClick(destination) }
            binding.btnHide.setOnClickListener { onToggleHidden(item) }
        }
    }

    class DestinationDiffCallback : DiffUtil.ItemCallback<DestinationListItem>() {
        override fun areItemsTheSame(oldItem: DestinationListItem, newItem: DestinationListItem): Boolean {
            return oldItem.destination.id == newItem.destination.id
        }

        override fun areContentsTheSame(oldItem: DestinationListItem, newItem: DestinationListItem): Boolean {
            return oldItem == newItem
        }
    }
}
