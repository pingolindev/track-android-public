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
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.ui.map.MapViewModel
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.databinding.ItemCircleMemberBinding

class CircleMembersAdapter(
    private val currentUserId: String,
    private val onChangeRole: (CircleMemberEntity) -> Unit,
    private val onRemove: (CircleMemberEntity) -> Unit,
    /** Overflow entry opening MemberDetailFragment (safety number / verify
     *  toggle / key details). Lives in the menu rather than on the row tap:
     *  the row is the way to the member's position on the map, which is what
     *  people open a circle to look at. */
    private val onSecurityDetails: (CircleMemberEntity) -> Unit,
    /** Tap on any member row shows that member on the map. Null (the
     * default) does nothing, for callers that don't wire the navigation. */
    private val onMemberTap: ((CircleMemberEntity) -> Unit)? = null,
) : ListAdapter<CircleMemberEntity, CircleMembersAdapter.ViewHolder>(DIFF) {

    companion object {
        // Menu titles double as the click-dispatch keys below, so they have to
        // be the same strings in both places.
        private const val CHANGE_ROLE = "Change Role"
        private const val REMOVE = "Remove"

        private val DIFF = object : DiffUtil.ItemCallback<CircleMemberEntity>() {
            override fun areItemsTheSame(a: CircleMemberEntity, b: CircleMemberEntity) = a.userId == b.userId
            override fun areContentsTheSame(a: CircleMemberEntity, b: CircleMemberEntity) = a == b
        }
    }

    inner class ViewHolder(val binding: ItemCircleMemberBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * What this account may do here: change roles as the owner, remove as an owner or admin, each
     * where it takes effect; nothing until it is known.
     */
    private var rights = com.pingolin.track.domain.repository.CircleRights.Rights.NONE

    fun setRights(rights: com.pingolin.track.domain.repository.CircleRights.Rights) {
        if (rights == this.rights) return
        this.rights = rights
        @Suppress("NotifyDataSetChanged") // a handful of rows, and every row's menu may change
        notifyDataSetChanged()
    }

    /** Members who are in but cannot open the circle yet: "{name} · Joining…". */
    private var joining: Set<String> = emptySet()

    fun setJoining(userIds: Set<String>) {
        if (userIds == joining) return
        joining = userIds
        @Suppress("NotifyDataSetChanged") // a handful of rows, and any of them may change
        notifyDataSetChanged()
    }

    /** Members asked to update Pingolin: "{name} · Asked to update Pingolin". */
    private var askedToUpdate: Set<String> = emptySet()

    fun setAskedToUpdate(userIds: Set<String>) {
        if (userIds == askedToUpdate) return
        askedToUpdate = userIds
        @Suppress("NotifyDataSetChanged") // a handful of rows, and any of them may change
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCircleMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = getItem(position)
        val b = holder.binding
        val isMe = member.userId == currentUserId
        val isOwner = member.role == "owner"

        val shownName = member.displayName.ifBlank { "Unknown" }
        b.tvMemberName.text = when (member.userId) {
            in askedToUpdate -> b.root.context.getString(R.string.member_name_asked_to_update, shownName)
            in joining -> b.root.context.getString(R.string.member_name_joining, shownName)
            else -> shownName
        }
        b.tvMemberRole.text = member.role.replaceFirstChar { it.uppercase() }

        // The same bubble the map draws: MapViewModel.MEMBER_COLORS indexed by
        // the member's position in this roster. The adapter's list IS the
        // roster the map indexes, so `position` gives the same colour without
        // reaching into MapViewModel for it.
        //
        // The initials' ink is picked by luminance (A11yInk), as on iOS: white
        // initials fail WCAG AA on all eight bubbles (worst 1.44:1 on amber).
        val name = member.displayName.ifBlank { "Unknown" }
        b.tvMemberAvatar.text = name.split(" ")
            .mapNotNull { it.firstOrNull() }
            .take(2)
            .joinToString("")
            .uppercase()
        val color = MapViewModel.MEMBER_COLORS[position % MapViewModel.MEMBER_COLORS.size]
        b.tvMemberAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        b.tvMemberAvatar.setTextColor(com.pingolin.track.util.A11yInk.ink(color))

        // "You" badge
        b.chipYou.visibility = if (isMe) View.VISIBLE else View.GONE

        // Why their location isn't updating, under the role: "Location sharing off",
        // "Paused until 5:00 PM", "Paused". Nothing while sharing.
        val now = System.currentTimeMillis()
        val sharing = MemberSharing.rowLabel(b.root.context, MemberSharing.status(member, now), now)
        b.tvMemberSharing.text = sharing
        b.tvMemberSharing.visibility = if (sharing != null) View.VISIBLE else View.GONE
        // One spoken label for the row, the sharing line included.
        b.root.contentDescription = listOfNotNull(
            b.tvMemberName.text,
            b.chipYou.text.takeIf { isMe },
            b.tvMemberRole.text,
            sharing,
        ).joinToString(", ")

        // Role changes and removal, as this account may make them, and never on the owner or on
        // self.
        val mayChangeRole = rights.changeRoles && !isOwner && !isMe
        val mayRemove = rights.remove && !isOwner && !isMe
        val canManage = mayChangeRole || mayRemove
        // Security details are for everyone — verifying a peer's keys is not an
        // admin power — but not for self: the safety number is a pairwise value
        // and there is no pair when both sides are you.
        val canSeeSecurity = !isMe

        // The overflow only earns its place if it would open onto something.
        val hasMenu = canManage || canSeeSecurity
        b.btnMemberActions.visibility = if (hasMenu) View.VISIBLE else View.GONE

        if (hasMenu) {
            b.btnMemberActions.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                val securityDetails = view.context.getString(R.string.member_security_details)
                if (canSeeSecurity) popup.menu.add(securityDetails)
                if (mayChangeRole) popup.menu.add(CHANGE_ROLE)
                if (mayRemove) popup.menu.add(REMOVE)
                popup.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        securityDetails -> { onSecurityDetails(member); true }
                        CHANGE_ROLE -> { onChangeRole(member); true }
                        REMOVE -> { onRemove(member); true }
                        else -> false
                    }
                }
                popup.show()
            }
        } else {
            b.btnMemberActions.setOnClickListener(null)
        }

        // Row tap shows the member on the map. Self included — "where am I"
        // is a legitimate question and the self marker answers it.
        val tap = onMemberTap
        if (tap != null) {
            b.root.setOnClickListener { tap(member) }
        } else {
            b.root.setOnClickListener(null)
            b.root.isClickable = false
        }
    }
}
