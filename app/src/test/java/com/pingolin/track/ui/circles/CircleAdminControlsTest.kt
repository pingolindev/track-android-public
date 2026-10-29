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

import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.domain.repository.CircleRights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import java.io.File

/**
 * On the circle screens, an admin gets Invite on the circle's card, Manage invitations and Remove;
 * Change Role, Transfer Ownership and Delete stay the owner's; a member gets none of them. This
 * follows the server: it refuses an admin's role change ("Only the owner can change member roles")
 * and allows an admin's invitations. The real adapters, bound to real rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CircleAdminControlsTest {

    private val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get().also { it.setTheme(R.style.Theme_Track) }

    private val owner = CircleRights.of("owner", CircleRights.Records.NONE, null)
    private val admin = CircleRights.of("admin", CircleRights.Records.NONE, null)
    private val member = CircleRights.of("member", CircleRights.Records.NONE, null)

    private fun row(userId: String, role: String) =
        CircleMemberEntity(circleId = "c-1", userId = userId, displayName = userId, role = role, joinedAt = 0L)

    /** The member row's menu for [target], as [rights] allow it; empty when the row has no menu. */
    private fun menuFor(rights: CircleRights.Rights, target: CircleMemberEntity): List<String> {
        val adapter = CircleMembersAdapter(
            currentUserId = "me", onChangeRole = {}, onRemove = {}, onSecurityDetails = {},
        )
        adapter.setRights(rights)
        adapter.submitList(listOf(target))
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        adapter.onBindViewHolder(holder, 0)
        val button = holder.itemView.findViewById<View>(R.id.btnMemberActions)
        if (button.visibility != View.VISIBLE) return emptyList()
        button.performClick()
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        return (0 until menu.size()).map { menu.getItem(it).title.toString() }
    }

    @Test
    fun `an owner may change a member's role and remove them, an admin only remove, a member neither`() {
        val security = activity.getString(R.string.member_security_details)
        assertEquals(listOf(security, "Change Role", "Remove"), menuFor(owner, row("dana", "member")))
        assertEquals(listOf(security, "Remove"), menuFor(admin, row("dana", "member")))
        assertEquals("an admin removes another admin, as the server allows", listOf(security, "Remove"), menuFor(admin, row("erin", "admin")))
        assertEquals(listOf(security), menuFor(member, row("dana", "member")))
        // Nobody manages the owner, or themselves.
        assertEquals(listOf(security), menuFor(owner.copy(changeRoles = true), row("olga", "owner")))
        assertEquals(emptyList<String>(), menuFor(owner, row("me", "owner")))
    }

    @Test
    fun `while the records wait, or before the rights are known, the rows offer only security details`() {
        val security = activity.getString(R.string.member_security_details)
        assertEquals(listOf(security), menuFor(CircleRights.of("owner", CircleRights.Records.WAITING, null), row("dana", "member")))
        assertEquals(listOf(security), menuFor(CircleRights.Rights.NONE, row("dana", "member")))
    }

    private fun circle(id: String, isOwner: Boolean) =
        CircleEntity(id = id, name = "Circle $id", encryptedKey = null, createdAt = 0L, updatedAt = 0L, isOwner = isOwner)

    @Test
    fun `the card offers Invite to an owner and an admin where it takes effect, and Leave to all but the owner`() {
        val adapter = CirclesAdapter(onCircleClick = {}, onLeaveClick = {}, onDeleteClick = {})
        val circles = listOf(circle("own", isOwner = true), circle("adm", isOwner = false), circle("mem", isOwner = false), circle("wait", isOwner = true))
        adapter.submitList(circles)
        adapter.setInviteOffered(setOf("own", "adm"))
        fun card(position: Int): View {
            val holder = adapter.onCreateViewHolder(FrameLayout(activity), adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            return holder.itemView
        }
        fun shown(view: View, id: Int) = view.findViewById<View>(id).visibility == View.VISIBLE

        val own = card(0)
        assertTrue(shown(own, R.id.btnInvite)); assertTrue(shown(own, R.id.btnDelete)); assertFalse(shown(own, R.id.btnLeave))
        val adm = card(1)
        assertTrue("an admin invites", shown(adm, R.id.btnInvite)); assertTrue(shown(adm, R.id.btnLeave)); assertFalse("only the owner deletes", shown(adm, R.id.btnDelete))
        val mem = card(2)
        assertFalse(shown(mem, R.id.btnInvite)); assertTrue(shown(mem, R.id.btnLeave)); assertFalse(shown(mem, R.id.btnDelete))
        assertFalse("an owner's circle whose records wait: no Invite yet", shown(card(3), R.id.btnInvite))
    }

    @Test
    fun `the circle's settings follow the rights, and Transfer stays the owner's`() {
        val screen = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(screen.contains("val rights = viewModel.rightsIn(circle, viewModel.selectedCircleMembers.value)\n            membersAdapter.setRights(rights)"))
        assertTrue(screen.contains("dialogBinding.btnManageInvites.visibility = if (rights.manageInvites) View.VISIBLE else View.GONE"))
        assertTrue("looked again as the list and the records change", screen.contains("combine(viewModel.selectedCircleMembers, viewModel.recordsNoted) { _, _ -> }.collectLatest { showRights() }"))
        assertTrue(screen.contains("viewModel.inviteOffered.collectLatest { circlesAdapter.setInviteOffered(it) }"))
        val transfer = screen.substringBefore("dialogBinding.btnTransferOwnership.visibility = View.VISIBLE")
        assertTrue("Transfer under the owner's branch", transfer.trimEnd().endsWith("if (circle.isOwner) {\n            // Transfer Ownership - only for owners"))
        assertFalse(screen.contains("currentUserIsOwnerOrAdmin"))
    }
}
