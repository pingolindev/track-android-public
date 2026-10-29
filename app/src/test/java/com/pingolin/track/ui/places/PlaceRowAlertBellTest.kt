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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import com.google.android.material.card.MaterialCardView
import com.pingolin.track.data.local.entity.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The alert bell on a Places row.
 *
 * Two properties this pins.
 *
 * **It must be there on a received share.** Everything else on such a row —
 * Edit, Share, Delete — is deliberately hidden, because the recipient doesn't
 * own the place's metadata. The bell is the exception: setting your OWN alert on
 * a place somebody shared with you is exactly what the share is for, and it is
 * the only way to reach the alert editor from this screen. If a future "hide the
 * owner-only controls" change sweeps the bell up with them, the recipient is left
 * with the map's place sheet as the sole entry point.
 *
 * **It must be VISIBLE, not merely present.** The two bell states are two
 * different drawables and only one of them carries a tint: `ic_alert` (alerts
 * off) has `android:tint="?attr/colorOnSurface"` baked into the vector, while
 * `ic_notifications` (alerts on) is a bare `fillColor="#FFFFFF"`. The card is
 * `@color/surface_container`, which is `#ffffff` in the light theme, so the
 * row's ImageButton has to set a tint of its own: without one, a place WITH an
 * alert draws a white bell on a white card. Present, clickable, invisible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PlaceRowAlertBellTest {

    private fun place(sharedWithMe: Boolean) = PlaceEntity(
        id = "place-1",
        circleId = if (sharedWithMe) "circle-1" else "self",
        name = "Home",
        address = "1500 McHenry Avenue",
        latitude = 37.6391,
        longitude = -120.9969,
        radius = 150,
        icon = "home",
        color = "#6D4AFF",
        createdAt = 0L,
        updatedAt = 0L,
        // wrapViaCircle set = this place reached the user through somebody else's
        // share, which is what drives the SHARED badge and the hidden controls.
        wrapViaCircle = if (sharedWithMe) "circle-1" else null,
        isOwner = !sharedWithMe,
    )

    private fun bindRow(place: PlaceEntity, alertsEnabled: Boolean): View {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).get()
        activity.setTheme(com.pingolin.track.R.style.Theme_Track)
        val adapter = PlacesAdapter(
            onPlaceClick = {},
            onEditClick = {},
            onAlertClick = {},
            onShareClick = {},
            onDeleteClick = {},
            hasAlertsEnabled = { alertsEnabled },
        )
        adapter.submitList(listOf(place))
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    private fun bell(itemView: View): ImageButton =
        itemView.findViewById(com.pingolin.track.R.id.btnAlert)

    // --- Presence ------------------------------------------------------------

    @Test
    fun `the bell is on a place somebody shared with me`() {
        val itemView = bindRow(place(sharedWithMe = true), alertsEnabled = false)

        assertEquals(
            "a share recipient must be able to reach the alert editor from Places",
            View.VISIBLE,
            bell(itemView).visibility,
        )
        // The owner-only controls stay hidden — the bell is the deliberate exception.
        assertEquals(View.GONE, itemView.findViewById<View>(com.pingolin.track.R.id.btnEdit).visibility)
        assertEquals(View.GONE, itemView.findViewById<View>(com.pingolin.track.R.id.btnShare).visibility)
        assertEquals(View.GONE, itemView.findViewById<View>(com.pingolin.track.R.id.btnDelete).visibility)
    }

    @Test
    fun `the bell is on my own place too`() {
        val itemView = bindRow(place(sharedWithMe = false), alertsEnabled = false)
        assertEquals(View.VISIBLE, bell(itemView).visibility)
    }

    // --- Visibility, as in "can actually be seen" ----------------------------

    @Test
    @Config(qualifiers = "notnight")
    fun `both bell states are visible against the card in the light theme`() {
        assertBellVisible(alertsEnabled = false)
        assertBellVisible(alertsEnabled = true)
    }

    @Test
    @Config(qualifiers = "night")
    fun `both bell states are visible against the card at night`() {
        assertBellVisible(alertsEnabled = false)
        assertBellVisible(alertsEnabled = true)
    }

    /**
     * Rasterise the bell over the card's own fill and measure the strongest
     * contrast it achieves. An icon needs 3:1 (WCAG non-text); a bell rendered in
     * the card colour scores 1.00:1 and is what this exists to catch.
     */
    private fun assertBellVisible(alertsEnabled: Boolean) {
        val itemView = bindRow(place(sharedWithMe = true), alertsEnabled)
        val card = itemView as MaterialCardView
        val background = card.cardBackgroundColor.defaultColor
        val button = bell(itemView)
        val drawable = requireNotNull(button.drawable) { "no bell drawable" }

        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        drawable.setBounds(0, 0, size, size)
        // The ImageView's own tint is applied by the view, not by the drawable, so
        // it has to be re-applied here or the measurement tests the wrong thing.
        button.imageTintList?.let { drawable.setTintList(it) }
        drawable.draw(canvas)

        var best = 1.0
        for (x in 0 until size) {
            for (y in 0 until size) {
                val px = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(px) == 0) continue
                best = max(best, contrastRatio(px, background))
            }
        }

        val state = if (alertsEnabled) "alerts ON (ic_notifications)" else "alerts OFF (ic_alert)"
        assertTrue(
            "$state bell peaks at ${"%.2f".format(best)}:1 against the card " +
                "(#${Integer.toHexString(background)}); an icon needs 3:1",
            best >= 3.0,
        )
    }

    /** WCAG 2.1 contrast ratio (1:1 … 21:1). */
    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(android.graphics.Color.red(color)) +
            0.7152 * channel(android.graphics.Color.green(color)) +
            0.0722 * channel(android.graphics.Color.blue(color))
    }
}
