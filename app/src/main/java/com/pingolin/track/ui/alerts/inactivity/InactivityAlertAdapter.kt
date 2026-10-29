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

package com.pingolin.track.ui.alerts.inactivity

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.databinding.ItemInactivityAlertBinding

class InactivityAlertAdapter(
    private val onAlertClick: (InactivityAlertEntity) -> Unit,
    private val onToggleClick: (InactivityAlertEntity, Boolean) -> Unit,
    private val onDeleteClick: (InactivityAlertEntity) -> Unit
) : ListAdapter<InactivityAlertEntity, InactivityAlertAdapter.InactivityAlertViewHolder>(InactivityAlertDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InactivityAlertViewHolder {
        val binding = ItemInactivityAlertBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InactivityAlertViewHolder(binding)
    }

    override fun onBindViewHolder(holder: InactivityAlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class InactivityAlertViewHolder(
        private val binding: ItemInactivityAlertBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(alert: InactivityAlertEntity) {
            // Icon
            binding.tvIcon.text = if (alert.isSelfAlert) "🔔" else "⏰"

            // Title
            val title = if (alert.isSelfAlert) "Self Inactivity Alert" else "Inactivity Alert"
            binding.tvTitle.text = title

            // Description
            val timeDesc = if (alert.timeoutHours == 1) "1 hour" else "${alert.timeoutHours} hours"
            val description = if (alert.isSelfAlert) {
                "Alert me if I'm inactive for $timeDesc"
            } else {
                "Alert if inactive for $timeDesc"
            }
            binding.tvDescription.text = description

            // Status
            binding.switchActive.isChecked = alert.isActive
            binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != alert.isActive) {
                    onToggleClick(alert, isChecked)
                }
            }

            binding.root.setOnClickListener { onAlertClick(alert) }
            binding.btnDelete.setOnClickListener { onDeleteClick(alert) }
        }
    }

    class InactivityAlertDiffCallback : DiffUtil.ItemCallback<InactivityAlertEntity>() {
        override fun areItemsTheSame(oldItem: InactivityAlertEntity, newItem: InactivityAlertEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: InactivityAlertEntity, newItem: InactivityAlertEntity): Boolean {
            return oldItem == newItem
        }
    }
}
