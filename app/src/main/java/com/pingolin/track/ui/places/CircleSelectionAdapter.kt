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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.databinding.ItemCircleSelectionBinding

/**
 * Adapter for multi-select circle selection in share place dialog.
 */
class CircleSelectionAdapter(
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<CircleEntity, CircleSelectionAdapter.CircleViewHolder>(CircleDiffCallback()) {

    private val selectedCircleIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CircleViewHolder {
        val binding = ItemCircleSelectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CircleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CircleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun getSelectedCircleIds(): Set<String> = selectedCircleIds.toSet()

    fun clearSelection() {
        selectedCircleIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedCircleIds)
    }

    inner class CircleViewHolder(
        private val binding: ItemCircleSelectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(circle: CircleEntity) {
            binding.tvCircleName.text = circle.name
            // Show owner status if available, otherwise just the circle name
            binding.tvMemberCount.text = if (circle.isOwner) binding.root.context.getString(com.pingolin.track.R.string.places_circle_owner) else ""
            binding.tvMemberCount.visibility = if (circle.isOwner) android.view.View.VISIBLE else android.view.View.GONE

            // Set checkbox state without triggering listener
            binding.checkbox.setOnCheckedChangeListener(null)
            binding.checkbox.isChecked = selectedCircleIds.contains(circle.id)

            // Handle clicks on the entire row
            binding.root.setOnClickListener {
                binding.checkbox.isChecked = !binding.checkbox.isChecked
            }

            // Handle checkbox changes
            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedCircleIds.add(circle.id)
                } else {
                    selectedCircleIds.remove(circle.id)
                }
                onSelectionChanged(selectedCircleIds)
            }
        }
    }

    class CircleDiffCallback : DiffUtil.ItemCallback<CircleEntity>() {
        override fun areItemsTheSame(oldItem: CircleEntity, newItem: CircleEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CircleEntity, newItem: CircleEntity): Boolean {
            return oldItem == newItem
        }
    }
}
