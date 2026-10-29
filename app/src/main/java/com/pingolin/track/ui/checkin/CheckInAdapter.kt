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

package com.pingolin.track.ui.checkin

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.databinding.ItemCheckInBinding

class CheckInAdapter(
    private val getUserDisplayName: (String) -> String,
    private val getPlaceName: (String) -> String?
) : ListAdapter<CheckInEntity, CheckInAdapter.CheckInViewHolder>(CheckInDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckInViewHolder {
        val binding = ItemCheckInBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CheckInViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CheckInViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CheckInViewHolder(
        private val binding: ItemCheckInBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(checkIn: CheckInEntity) {
            // Type icon and label
            val (icon, label) = CheckInChoices.label(checkIn.type)
            binding.tvTypeIcon.text = icon
            binding.tvType.text = label

            // User name
            binding.tvUserName.text = getUserDisplayName(checkIn.userId)

            // Message
            if (!checkIn.message.isNullOrBlank()) {
                binding.tvMessage.text = checkIn.message
                binding.tvMessage.visibility = View.VISIBLE
            } else {
                binding.tvMessage.visibility = View.GONE
            }

            // Location/Place
            val placeName = checkIn.placeId?.let { getPlaceName(it) }
            if (placeName != null) {
                binding.tvLocation.text = placeName
                binding.ivLocation.visibility = View.VISIBLE
                binding.tvLocation.visibility = View.VISIBLE
            } else if (checkIn.latitude != null && checkIn.longitude != null) {
                binding.tvLocation.text = "Location shared"
                binding.ivLocation.visibility = View.VISIBLE
                binding.tvLocation.visibility = View.VISIBLE
            } else {
                binding.ivLocation.visibility = View.GONE
                binding.tvLocation.visibility = View.GONE
            }

            // Timestamp
            binding.tvTimestamp.text = DateUtils.getRelativeTimeSpanString(
                checkIn.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        }
    }

    class CheckInDiffCallback : DiffUtil.ItemCallback<CheckInEntity>() {
        override fun areItemsTheSame(oldItem: CheckInEntity, newItem: CheckInEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CheckInEntity, newItem: CheckInEntity): Boolean {
            return oldItem == newItem
        }
    }
}
