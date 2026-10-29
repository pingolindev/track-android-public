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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.local.entity.PendingInviteEntity
import com.pingolin.track.databinding.ItemPendingInviteBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying pending invites in a RecyclerView.
 */
class PendingInvitesAdapter(
    private val onRevokeClick: (PendingInviteEntity) -> Unit
) : ListAdapter<PendingInviteEntity, PendingInvitesAdapter.InviteViewHolder>(InviteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteViewHolder {
        val binding = ItemPendingInviteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InviteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: InviteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class InviteViewHolder(
        private val binding: ItemPendingInviteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun bind(invite: PendingInviteEntity) {
            // Show invite type or email as primary identifier
            val label = if (!invite.email.isNullOrBlank()) {
                invite.email
            } else {
                invite.inviteType?.replaceFirstChar { it.uppercase() } ?: "Invite"
            }
            binding.tvInviteCode.text = label

            // Show invite type as subtitle when email is the primary label
            if (!invite.email.isNullOrBlank() && invite.inviteType != null) {
                binding.tvEmail.text = invite.inviteType.replaceFirstChar { it.uppercase() } + " invite"
                binding.tvEmail.visibility = View.VISIBLE
            } else {
                binding.tvEmail.visibility = View.GONE
            }

            // Show expiration
            val expiresDate = Date(invite.expiresDayBucket.toLong() * 86400 * 1000L)
            binding.tvExpires.text = "Expires ${dateFormat.format(expiresDate)}"

            // Show expired badge
            if (invite.isExpired) {
                binding.chipExpired.visibility = View.VISIBLE
                binding.btnRevoke.isEnabled = false
                binding.btnRevoke.alpha = 0.5f
            } else {
                binding.chipExpired.visibility = View.GONE
                binding.btnRevoke.isEnabled = true
                binding.btnRevoke.alpha = 1.0f
            }

            binding.btnRevoke.setOnClickListener { onRevokeClick(invite) }
        }
    }

    class InviteDiffCallback : DiffUtil.ItemCallback<PendingInviteEntity>() {
        override fun areItemsTheSame(oldItem: PendingInviteEntity, newItem: PendingInviteEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PendingInviteEntity, newItem: PendingInviteEntity): Boolean {
            return oldItem == newItem
        }
    }
}
