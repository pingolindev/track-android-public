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

package com.pingolin.track.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.BlockedPersonResponse
import com.pingolin.track.databinding.ItemBlockedPersonBinding

/** The rows of "Blocked people": an address, and Unblock. */
class BlockedPeopleAdapter(
    private val onUnblock: (BlockedPersonResponse) -> Unit,
) : ListAdapter<BlockedPersonResponse, BlockedPeopleAdapter.PersonViewHolder>(PersonDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder =
        PersonViewHolder(ItemBlockedPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PersonViewHolder(
        private val binding: ItemBlockedPersonBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(person: BlockedPersonResponse) {
            binding.tvBlockedEmail.text = person.blockedEmail
            // "Unblock" alone, read out row after row, does not say whom.
            binding.btnUnblock.contentDescription =
                binding.root.context.getString(R.string.blocked_people_unblock_cd, person.blockedEmail)
            binding.btnUnblock.setOnClickListener { onUnblock(person) }
        }
    }

    private object PersonDiff : DiffUtil.ItemCallback<BlockedPersonResponse>() {
        override fun areItemsTheSame(oldItem: BlockedPersonResponse, newItem: BlockedPersonResponse): Boolean =
            oldItem.blockedEmail == newItem.blockedEmail

        override fun areContentsTheSame(oldItem: BlockedPersonResponse, newItem: BlockedPersonResponse): Boolean =
            oldItem == newItem
    }
}
