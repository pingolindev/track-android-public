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

package com.pingolin.track.ui.sos

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.pingolin.track.data.local.entity.SosAlertEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What an SOS row on the Safety screen actually renders.
 *
 * Three things are pinned here.
 *
 * **Who is offered which action.** Resolve and Cancel are the raiser's alone
 * (alerts are personal), Acknowledge and the private dismiss everyone else's, and
 * the row is the only place a user ever meets them, so the mapping is asserted
 * per viewer — raiser, anyone else — rather than left to be read off the `when`
 * block.
 *
 * **Contrast.** The row is a MaterialCardView filled with
 * `@color/error_container`. A hard-coded red such as `#E53935` on it is red on
 * PALE red in the light theme (2.9:1) and red on DARK red at night (2.5:1) — the
 * emergency's own status the least readable text on the card, and worst exactly
 * where people read it, at night. A literal colour cannot satisfy both themes,
 * because the container it sits on inverts between them; only the
 * `on_error_container` token flips with it. Asserted as a measured WCAG ratio
 * against the card's real background in BOTH configurations, so a future "just
 * pick a nicer red" is caught rather than eyeballed.
 *
 * **The address.** The row shows WHERE the SOS was raised. The server holds the
 * alert's location encrypted and cannot supply this, so it is reverse-geocoded on
 * device and arrives after the row first binds — hence a nullable field, and
 * hence the need to prove both that it appears when present and that the line
 * disappears rather than showing a stale value or an empty gap when it isn't.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's
// native lib, which isn't on the JVM library path. Nothing here needs the real
// app class — but the row DOES need a Material theme, since MaterialCardView
// resolves its own attributes from it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SosAlertRowTest {

    private fun alert(
        status: String = "active",
        latitude: Double? = 37.6391,
        longitude: Double? = -120.9969,
    ) = SosAlertEntity(
        id = "alert-1",
        circleId = "circle-1",
        userId = "user-1",
        status = status,
        latitude = latitude,
        longitude = longitude,
        acknowledgedBy = null,
        createdAt = 1_700_000_000_000L,
        resolvedAt = null,
    )

    /**
     * A holder created by the real adapter, ready to be bound.
     *
     * [isMine] is the adapter's own ownership predicate — in production
     * `SosViewModel.isMyAlert`, comparing the alert's decrypted user id to the
     * signed-in one. It is a constructor lambda rather than a row field, so it
     * has to be chosen here.
     */
    private fun newHolder(
        isMine: Boolean = false,
        responders: Map<String, String> = emptyMap(),
    ): SosAlertsAdapter.AlertViewHolder {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).get()
        activity.setTheme(com.pingolin.track.R.style.Theme_Track)
        val adapter = SosAlertsAdapter(
            onAcknowledgeClick = {},
            onResolveClick = {},
            onCancelClick = {},
            onAlertClick = {},
            onDismissClick = {},
            onUndismissClick = {},
            isMyAlert = { isMine },
        )
        adapter.responders = responders
        return adapter.onCreateViewHolder(FrameLayout(activity), 0)
    }

    /**
     * Bind one row and return its itemView.
     *
     * Straight to `bind`, deliberately not through `submitList`: ListAdapter's
     * AsyncListDiffer only takes the synchronous fast path when the list was
     * empty. Any later submit diffs on a background thread that idling the
     * main looper does not drain, so a second submit silently leaves the
     * earlier row in place — a rebind test written that way passes against a
     * binder that never clears anything.
     */
    private fun bindRow(
        row: SosAlertRow,
        isMine: Boolean = false,
        responders: Map<String, String> = emptyMap(),
    ): View {
        val holder = newHolder(isMine, responders)
        holder.bind(row)
        return holder.itemView
    }

    private fun addressView(itemView: View): TextView =
        itemView.findViewById(com.pingolin.track.R.id.tvAddress)

    private fun statusView(itemView: View): TextView =
        itemView.findViewById(com.pingolin.track.R.id.tvStatus)

    // --- Address ------------------------------------------------------------

    // --- Who asked -----------------------------------------------------------

    @Test
    fun `someone else's card says who asked when this phone can tell`() {
        val itemView = bindRow(SosAlertRow(alert(), address = null, raiserName = "Ana"), isMine = false)

        assertEquals("Ana asked for attention", itemView.findViewById<TextView>(com.pingolin.track.R.id.tvAlertTitle).text.toString())
    }

    @Test
    fun `and "A circle member" when it can't, and "You" on your own`() {
        val unknown = bindRow(SosAlertRow(alert(), address = null), isMine = false)
        assertEquals("A circle member asked for attention", unknown.findViewById<TextView>(com.pingolin.track.R.id.tvAlertTitle).text.toString())

        val mine = bindRow(SosAlertRow(alert(), address = null, raiserName = "Ana"), isMine = true)
        assertEquals("You asked for attention", mine.findViewById<TextView>(com.pingolin.track.R.id.tvAlertTitle).text.toString())
    }

    @Test
    fun `the row shows the reverse-geocoded address`() {
        val itemView = bindRow(SosAlertRow(alert(), address = "1500 McHenry Avenue, Modesto"))

        val tv = addressView(itemView)
        assertEquals("address line must be visible once resolved", View.VISIBLE, tv.visibility)
        assertEquals("1500 McHenry Avenue, Modesto", tv.text.toString())
    }

    @Test
    fun `the address line is hidden while unresolved`() {
        val itemView = bindRow(SosAlertRow(alert(), address = null))

        assertEquals(
            "no address yet must leave no gap and no placeholder",
            View.GONE,
            addressView(itemView).visibility,
        )
    }

    @Test
    fun `a recycled row drops the previous alert's address`() {
        // ViewHolders are reused. A row bound with an address and then rebound
        // for a different alert without one must not keep showing the first
        // alert's street — that would put a responder at the wrong address.
        val holder = newHolder()

        holder.bind(SosAlertRow(alert(), "1500 McHenry Avenue"))
        assertEquals(View.VISIBLE, addressView(holder.itemView).visibility)

        holder.bind(SosAlertRow(alert().copy(id = "alert-2"), null))

        assertEquals(
            "a reused row must not keep the previous alert's address",
            View.GONE,
            addressView(holder.itemView).visibility,
        )
    }

    // --- Who is offered which action ----------------------------------------
    //
    // Alerts are personal, on both the sent and the received side, so
    // nobody can affect the other's: they are more like notifications.
    // Only the raiser ends their request, by Resolve or by calling it a
    // false alarm. Everyone else, the circle's owner and admins included,
    // may acknowledge it or hide it for themselves, and nothing they can
    // tap here ends it for anyone. The row carries no role: there is no
    // control a role could turn on.

    private fun button(itemView: View, id: Int): View = itemView.findViewById(id)

    private fun acknowledgeVisibility(itemView: View) =
        button(itemView, com.pingolin.track.R.id.btnAcknowledge).visibility

    private fun resolveVisibility(itemView: View) =
        button(itemView, com.pingolin.track.R.id.btnResolve).visibility

    private fun cancelVisibility(itemView: View) =
        button(itemView, com.pingolin.track.R.id.btnCancel).visibility

    private fun dismissVisibility(itemView: View) =
        button(itemView, com.pingolin.track.R.id.btnDismiss).visibility

    @Test
    fun `the raiser is offered Resolve and Cancel`() {
        val itemView = bindRow(SosAlertRow(alert(), address = null), isMine = true)

        assertEquals("the raiser closes their own alert", View.VISIBLE, resolveVisibility(itemView))
        assertEquals("only the raiser may call it a false alarm", View.VISIBLE, cancelVisibility(itemView))
        assertEquals(
            "you do not acknowledge your own alert",
            View.GONE,
            acknowledgeVisibility(itemView),
        )
    }

    @Test
    fun `anyone else, the circle's owner or an admin included, is offered nothing that ends the alert`() {
        // The row has no input that could tell owners and admins apart,
        // and nothing to offer them if it did.
        val itemView = bindRow(SosAlertRow(alert(), address = null), isMine = false)

        assertEquals("nobody closes someone else's request", View.GONE, resolveVisibility(itemView))
        assertEquals("nor calls it a false alarm", View.GONE, cancelVisibility(itemView))
        assertEquals("they may still say they're responding", View.VISIBLE, acknowledgeVisibility(itemView))
        assertEquals("and hide it for themselves", View.VISIBLE, dismissVisibility(itemView))
    }

    @Test
    fun `someone else's alert I already acknowledged still offers nothing that ends it`() {
        // Acknowledge goes once this viewer has acknowledged (the alert stays
        // `active` after, so status alone can't say), and nothing takes its place.
        val itemView = bindRow(
            SosAlertRow(alert().copy(acknowledgedByMe = true), address = null),
            isMine = false,
        )

        assertEquals(View.GONE, acknowledgeVisibility(itemView))
        assertEquals(View.GONE, resolveVisibility(itemView))
        assertEquals(View.GONE, cancelVisibility(itemView))
        assertEquals(View.VISIBLE, dismissVisibility(itemView))
    }

    @Test
    fun `a recycled row drops my own alert's Resolve and Cancel`() {
        // ViewHolders are reused: a row bound for MY request, then rebound for
        // someone else's, must not carry my controls onto theirs.
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).get()
        activity.setTheme(com.pingolin.track.R.style.Theme_Track)
        val holder = SosAlertsAdapter(
            onAcknowledgeClick = {},
            onResolveClick = {},
            onCancelClick = {},
            onAlertClick = {},
            onDismissClick = {},
            onUndismissClick = {},
            isMyAlert = { it.id == "alert-1" },
        ).onCreateViewHolder(FrameLayout(activity), 0)

        holder.bind(SosAlertRow(alert(), address = null))
        assertEquals(View.VISIBLE, resolveVisibility(holder.itemView))
        assertEquals(View.VISIBLE, cancelVisibility(holder.itemView))

        holder.bind(SosAlertRow(alert().copy(id = "alert-2", userId = "user-2"), address = null))
        assertEquals(View.GONE, resolveVisibility(holder.itemView))
        assertEquals(View.GONE, cancelVisibility(holder.itemView))
        assertEquals(View.VISIBLE, acknowledgeVisibility(holder.itemView))
    }

    @Test
    fun `an already-closed alert offers nothing, mine or not`() {
        // The server only resolves an alert that is `active` or `acknowledged`:
        // a resolved alert would answer 400 "already resolved".
        for (mine in listOf(true, false)) {
            val itemView = bindRow(SosAlertRow(alert(status = "resolved"), address = null), isMine = mine)

            assertEquals(View.GONE, resolveVisibility(itemView))
            assertEquals(View.GONE, cancelVisibility(itemView))
            assertEquals(View.GONE, acknowledgeVisibility(itemView))
        }
    }

    // --- Status line -------------------------------------------------------

    private fun acknowledgedView(itemView: View): TextView =
        itemView.findViewById(com.pingolin.track.R.id.tvAcknowledged)

    @Test
    fun `my own request someone answered reads Acknowledged above who is responding, never waiting`() {
        // Never "Waiting for someone to respond" right above "Ana is responding": for
        // the raiser, the status follows everyone's acknowledgements, not only the
        // viewer's own. As iOS's card.
        val named = bindRow(
            SosAlertRow(alert().copy(acknowledgmentCount = 1), address = null),
            isMine = true,
            responders = mapOf("alert-1" to "Ana is responding"),
        )
        assertEquals("Acknowledged", statusView(named).text.toString())
        assertEquals("Ana is responding", acknowledgedView(named).text.toString())

        // Nobody this device can name, or no list: the count, and still never "waiting".
        val counted = bindRow(SosAlertRow(alert().copy(acknowledgmentCount = 2), address = null), isMine = true)
        assertEquals("Acknowledged", statusView(counted).text.toString())
        assertEquals("2 member(s) acknowledged", acknowledgedView(counted).text.toString())

        // Before anyone answers it is waiting; once closed, closed.
        val waiting = bindRow(SosAlertRow(alert(), address = null), isMine = true)
        assertEquals("Waiting for someone to respond", statusView(waiting).text.toString())
        val closed = bindRow(SosAlertRow(alert(status = "resolved").copy(acknowledgmentCount = 1), address = null), isMine = true)
        assertEquals("Resolved", statusView(closed).text.toString())
    }

    @Test
    fun `someone else's request reads by my own answer`() {
        // Others answering doesn't take it off MY plate: still waiting for me until I acknowledge.
        val othersAnswered = bindRow(SosAlertRow(alert().copy(acknowledgmentCount = 2), address = null), isMine = false)
        assertEquals("Waiting for someone to respond", statusView(othersAnswered).text.toString())

        val iAnswered = bindRow(
            SosAlertRow(alert().copy(acknowledgmentCount = 1, acknowledgedByMe = true), address = null),
            isMine = false,
        )
        assertEquals("Acknowledged", statusView(iAnswered).text.toString())
    }

    // --- Contrast -----------------------------------------------------------

    @Test
    @Config(qualifiers = "notnight")
    fun `status text clears WCAG AA against the card in the light theme`() {
        assertRowContrast()
    }

    @Test
    @Config(qualifiers = "night")
    fun `status text clears WCAG AA against the card at night`() {
        assertRowContrast()
    }

    /**
     * Every text line on the row against the card's own fill. The status line
     * is the one most at risk, but the whole card shares one background, so
     * they are all held to the same bar — that is what stops the next literal
     * colour from landing on a different line instead.
     */
    private fun assertRowContrast() {
        val itemView = bindRow(SosAlertRow(alert(), address = "1500 McHenry Avenue"))
        val card = itemView as MaterialCardView
        val background = card.cardBackgroundColor.defaultColor

        val lines = listOf(
            "tvAlertTitle" to com.pingolin.track.R.id.tvAlertTitle,
            "tvStatus" to com.pingolin.track.R.id.tvStatus,
            "tvAddress" to com.pingolin.track.R.id.tvAddress,
            "tvTime" to com.pingolin.track.R.id.tvTime,
        )
        for ((name, id) in lines) {
            val tv = itemView.findViewById<TextView>(id)
            // android:alpha on the view composites the text over the card, so
            // the effective colour — not the declared one — is what a reader
            // actually sees.
            val effective = blend(tv.currentTextColor, background, tv.alpha)
            val ratio = contrastRatio(effective, background)
            assertTrue(
                "$name is ${"%.2f".format(ratio)}:1 on the card background " +
                    "(#${Integer.toHexString(background)}); WCAG AA needs 4.5:1",
                ratio >= 4.5,
            )
        }

        // And the status line specifically must not be a literal red: it
        // has to track the container, which means differing between themes.
        assertTrue(
            "status colour must come from a theme-aware token",
            statusView(itemView).currentTextColor != android.graphics.Color.parseColor("#E53935"),
        )

        // The actions live on the same card. Left to their defaults they take
        // their colours from the app's PRIMARY palette (brand purple), which has
        // no relationship to the error container underneath them, so an
        // outlined button can land at 3.6:1 at night.
        val buttons = listOf(
            "btnAcknowledge" to com.pingolin.track.R.id.btnAcknowledge,
            "btnResolve" to com.pingolin.track.R.id.btnResolve,
            "btnCancel" to com.pingolin.track.R.id.btnCancel,
            "btnUndismiss" to com.pingolin.track.R.id.btnUndismiss,
        )
        for ((name, id) in buttons) {
            val button = itemView.findViewById<android.widget.Button>(id)
            // A filled button sits on its own tint; an outlined one shows the
            // card through. Measure the label against whatever is actually
            // behind it.
            val behind = (button as? com.google.android.material.button.MaterialButton)
                ?.backgroundTintList?.defaultColor
                ?.takeIf { android.graphics.Color.alpha(it) == 255 }
                ?: background
            val ratio = contrastRatio(button.currentTextColor, behind)
            assertTrue(
                "$name label is ${"%.2f".format(ratio)}:1 on #${Integer.toHexString(behind)}; " +
                    "WCAG AA needs 4.5:1",
                ratio >= 4.5,
            )
        }
    }

    /** Composite `fg` over `bg` at `alpha`, as the view hierarchy would. */
    private fun blend(fg: Int, bg: Int, alpha: Float): Int {
        fun mix(f: Int, b: Int) = (f * alpha + b * (1 - alpha)).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(
            mix(android.graphics.Color.red(fg), android.graphics.Color.red(bg)),
            mix(android.graphics.Color.green(fg), android.graphics.Color.green(bg)),
            mix(android.graphics.Color.blue(fg), android.graphics.Color.blue(bg)),
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
