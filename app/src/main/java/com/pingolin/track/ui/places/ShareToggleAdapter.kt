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
import com.pingolin.track.databinding.ItemCircleToggleBinding

/**
 * Adapter for the "Manage sharing" dialog. Each row shows a circle with
 * a Material Switch reflecting whether this place is currently shared
 * with that circle. A tap fires `onToggle` at once; the adapter itself
 * changes nothing — it only shows the rows it is given — so the caller
 * applies the change optimistically (marking the row pending) and
 * submits the rows again. If the share fails, the caller flips its
 * state back and submits again — this lets the dialog show a Toast with
 * the actual error and roll the UI back in one place.
 */
class ShareToggleAdapter(
    private val onToggle: (circle: CircleEntity, shouldShare: Boolean) -> Unit,
) : ListAdapter<ShareToggleAdapter.Row, ShareToggleAdapter.Holder>(Diff()) {

    data class Row(
        val circle: CircleEntity,
        /** Whether the place is currently shared with this circle. */
        val isShared: Boolean,
        /** When true, the toggle visually flips but is non-interactive
         *  while a network call is in flight. Avoids double-fires. */
        val isPending: Boolean = false,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCircleToggleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemCircleToggleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            binding.tvCircleName.text = row.circle.name
            binding.tvCircleMeta.text = if (row.circle.isOwner) binding.root.context.getString(com.pingolin.track.R.string.places_circle_owner) else ""
            binding.tvCircleMeta.visibility =
                if (row.circle.isOwner) android.view.View.VISIBLE else android.view.View.GONE

            // Detach listener before mutating state so the listener
            // doesn't fire from the programmatic state update.
            binding.toggle.setOnCheckedChangeListener(null)
            binding.toggle.isChecked = row.isShared
            binding.root.isEnabled = !row.isPending
            binding.toggle.alpha = if (row.isPending) 0.5f else 1.0f

            val onClick = android.view.View.OnClickListener {
                if (row.isPending) return@OnClickListener
                // Intent comes from the MODEL, never from the
                // view's state. A Switch is a CompoundButton,
                // so tapping IT has already flipped isChecked
                // by the time this runs, while tapping the row
                // has not: `!binding.toggle.isChecked` would be
                // right for the row and wrong for the switch,
                // and tapping an OFF switch would ask to
                // UNSHARE.
                val target = !row.isShared
                // Undo the switch's self-flip. The parent re-emits a Row with
                // isPending=true and then the final state, so the adapter stays
                // the single source of truth for what's shown — a view that runs
                // ahead is what made the failure look like success.
                // Safe: the checked-change listener was detached above.
                binding.toggle.isChecked = row.isShared
                onToggle(row.circle, target)
            }
            binding.root.setOnClickListener(onClick)
            binding.toggle.setOnClickListener(onClick)
        }
    }

    private class Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) =
            oldItem.circle.id == newItem.circle.id

        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }
}
