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
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import com.pingolin.track.R
import com.pingolin.track.databinding.ItemReceivedInviteBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying received invites in a RecyclerView.
 * Each item shows the invite details with Accept and Decline buttons.
 */
class ReceivedInvitesAdapter(
    private val onAcceptClick: (ReceivedInviteResponse) -> Unit,
    private val onDeclineClick: (ReceivedInviteResponse) -> Unit
) : ListAdapter<ReceivedInviteResponse, ReceivedInvitesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReceivedInviteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Invitations this phone will join by itself once they are renewed. */
    private var waitingToJoin: Set<String> = emptySet()

    fun setWaitingToJoin(inviteIds: Set<String>) {
        if (inviteIds == waitingToJoin) return
        waitingToJoin = inviteIds
        @Suppress("NotifyDataSetChanged") // a handful of rows
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemReceivedInviteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun bind(invite: ReceivedInviteResponse) {
            val inviterName = invite.inviterDisplayName
            val circleName = invite.circleName

            // Show invite with circle name and inviter name when available
            binding.tvCircleId.text = when {
                !inviterName.isNullOrBlank() && !circleName.isNullOrBlank() ->
                    "$inviterName invited you to join \"$circleName\""
                !circleName.isNullOrBlank() ->
                    "Invitation to join \"$circleName\""
                !inviterName.isNullOrBlank() ->
                    "$inviterName invited you to join a circle"
                else -> "Circle invitation"
            }

            // Show invite source context
            binding.tvInvitedBy.text = when {
                !inviterName.isNullOrBlank() -> "Via ${invite.inviteType} invite from $inviterName"
                else -> "Via ${invite.inviteType} invite"
            }

            // Show expiration date — or, while it waits to be renewed, what is
            // happening: the app accepts it by itself once it has been.
            val expiresDate = Date(invite.expiresDayBucket.toLong() * 86400 * 1000L)
            val context = binding.root.context
            val joiningByItself = invite.id in waitingToJoin
            binding.tvExpires.text = when {
                // The person already said yes: this phone joins it by itself once it is renewed.
                joiningByItself && !inviterName.isNullOrBlank() -> context.getString(R.string.invite_joining_when_confirmed, inviterName)
                joiningByItself -> context.getString(R.string.invite_joining_when_confirmed_unknown)
                !invite.renewalRequested -> "Expires ${dateFormat.format(expiresDate)}"
                !inviterName.isNullOrBlank() -> context.getString(R.string.invite_renewing, inviterName)
                else -> context.getString(R.string.invite_renewing_unknown)
            }
            // Nothing to press while it joins by itself.
            binding.btnAccept.visibility = if (joiningByItself) android.view.View.GONE else android.view.View.VISIBLE

            binding.btnAccept.setOnClickListener { onAcceptClick(invite) }
            binding.btnDecline.setOnClickListener { onDeclineClick(invite) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ReceivedInviteResponse>() {
        override fun areItemsTheSame(
            oldItem: ReceivedInviteResponse,
            newItem: ReceivedInviteResponse
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: ReceivedInviteResponse,
            newItem: ReceivedInviteResponse
        ): Boolean {
            return oldItem == newItem
        }
    }
}
