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

import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.CircleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Which way the share toggle actually asks for.
 *
 * The row and the switch must not derive intent from the VIEW's post-click
 * state —
 *
 *     val target = !binding.toggle.isChecked
 *
 * A Switch is a CompoundButton, so tapping it flips `isChecked` BEFORE the
 * click listener runs. Tapping an OFF switch would make `isChecked` true and
 * ask for `target = false` — an UNSHARE, when the user asked to share. Tapping
 * the ROW does not self-toggle, so the same expression is correct there, which
 * makes the mistake easy to miss.
 *
 * Its symptom is sharing that silently fails: the app calls unshare, the server
 * answers 404 "Share not found" (no row to delete), the failure rolls the
 * optimistic flip the other way and leaves the switch looking ON, and reopening
 * the dialog rebuilds it from the unchanged model as OFF. No share is ever
 * created, so a personal place can never be shared — and an unshared place is
 * never reported to the server at all (BoundaryRepository: "the crossing never
 * leaves the phone"), which silently disables server-routed place alerts.
 *
 * Robolectric rather than a pure function because the defect only exists in the
 * interaction with the real CompoundButton self-toggle. A test against
 * extracted logic would pass while the product stayed broken.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's
// native lib, which isn't on the JVM library path and fails every test here with
// UnsatisfiedLinkError. Nothing under test needs the real app class.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShareToggleAdapterTest {

    private fun circle(id: String) = CircleEntity(
        id = id,
        name = "BatteryTest",
        encryptedKey = null,
        createdAt = 0L,
        updatedAt = 0L,
        isOwner = true,
    )

    /** Binds one row into a real RecyclerView and returns its itemView. */
    private fun bindRow(row: ShareToggleAdapter.Row, onToggle: (CircleEntity, Boolean) -> Unit): android.view.View {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val adapter = ShareToggleAdapter(onToggle = onToggle)
        val parent = FrameLayout(activity)
        val holder = adapter.onCreateViewHolder(parent, 0)
        // submitList goes through AsyncListDiffer; pump the loop so the list is
        // in place, then bind explicitly rather than racing the differ.
        adapter.submitList(listOf(row))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    private fun switchOf(itemView: android.view.View): android.widget.CompoundButton {
        val id = itemView.resources.getIdentifier("toggle", "id", itemView.context.packageName)
        return itemView.findViewById(id)
    }

    /**
     * The headline case: tapping an OFF switch must ask to SHARE.
     */
    @Test
    fun `tapping an off switch asks to share, not unshare`() {
        val captured = mutableListOf<Pair<String, Boolean>>()
        val row = ShareToggleAdapter.Row(circle = circle("c1"), isShared = false, isPending = false)
        val itemView = bindRow(row) { c, shouldShare -> captured += c.id to shouldShare }

        switchOf(itemView).performClick()

        assertEquals("exactly one toggle request", 1, captured.size)
        assertEquals(
            "tapping an OFF switch must request a SHARE — this asked to unshare",
            true,
            captured.single().second,
        )
    }

    /** And the reverse: tapping an ON switch must ask to UNSHARE. */
    @Test
    fun `tapping an on switch asks to unshare`() {
        val captured = mutableListOf<Pair<String, Boolean>>()
        val row = ShareToggleAdapter.Row(circle = circle("c1"), isShared = true, isPending = false)
        val itemView = bindRow(row) { c, shouldShare -> captured += c.id to shouldShare }

        switchOf(itemView).performClick()

        assertEquals(1, captured.size)
        assertEquals(false, captured.single().second)
    }

    /**
     * Clicking the ROW must agree with clicking the switch. Both go through
     * the same listener, but only the switch self-toggles, so a listener that
     * read the view's state would make them disagree by exactly one inversion.
     */
    @Test
    fun `row click and switch click agree`() {
        val fromRow = mutableListOf<Boolean>()
        val rowView = bindRow(ShareToggleAdapter.Row(circle("c1"), isShared = false, isPending = false)) { _, s -> fromRow += s }
        rowView.performClick()

        val fromSwitch = mutableListOf<Boolean>()
        val switchView = bindRow(ShareToggleAdapter.Row(circle("c1"), isShared = false, isPending = false)) { _, s -> fromSwitch += s }
        switchOf(switchView).performClick()

        assertEquals("row and switch must request the same thing", fromRow, fromSwitch)
        assertEquals(listOf(true), fromRow)
    }

    /**
     * The view must not get ahead of the model. The parent re-emits the row
     * (pending, then final), so the switch has to show `row.isShared` until
     * then — otherwise a failed share leaves the UI claiming success.
     */
    @Test
    fun `switch does not visually flip ahead of the model`() {
        val row = ShareToggleAdapter.Row(circle("c1"), isShared = false, isPending = false)
        val itemView = bindRow(row) { _, _ -> }
        val toggle = switchOf(itemView)

        toggle.performClick()

        assertTrue("switch must still render the model state until the parent re-emits", !toggle.isChecked)
    }

    /** A pending row must swallow taps so a double-tap can't double-fire. */
    @Test
    fun `pending row ignores taps`() {
        val captured = mutableListOf<Boolean>()
        val row = ShareToggleAdapter.Row(circle("c1"), isShared = false, isPending = true)
        val itemView = bindRow(row) { _, s -> captured += s }

        switchOf(itemView).performClick()
        itemView.performClick()

        assertEquals("a pending row must not fire", 0, captured.size)
    }
}
