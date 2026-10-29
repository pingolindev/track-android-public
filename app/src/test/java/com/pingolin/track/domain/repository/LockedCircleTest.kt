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

package com.pingolin.track.domain.repository

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.domain.repository.LockedCircle.Cause
import com.pingolin.track.domain.repository.LockedCircle.WrapOutcome
import com.pingolin.track.ui.circles.CirclesAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One placeholder for a circle this device cannot open, and an explanation chosen by
 * why.
 *
 * One text for every cause — "Waiting for the circle owner" — would tell an owner who
 * had reset their password to wait for themselves, and never point them at the
 * recovery key that could bring the circle back. Robolectric so the assertions read
 * the words people actually see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LockedCircleTest {

    private lateinit var res: Resources

    @Before
    fun setup() {
        res = ApplicationProvider.getApplicationContext<android.app.Application>().resources
    }

    private fun body(cause: Cause, isOwner: Boolean) =
        res.getString(LockedCircle.explanationFor(cause, isOwner).body, "Family")

    private fun title(cause: Cause, isOwner: Boolean) =
        res.getString(LockedCircle.explanationFor(cause, isOwner).title, "Family")

    @Test
    fun `a joiner whose invitation ran out is told whom it asked, word for word`() {
        val ranOut = LockedCircle.explanationFor(Cause.AWAITING_ACCESS, isOwner = false, ranOut = LockedCircle.RanOut("Ada"))
        assertEquals(
            "Your invitation ran out before anyone's phone could hand the circle over. Ada has been asked to let you in.",
            res.getString(ranOut.body, ranOut.bodyName),
        )
        assertEquals(title(Cause.AWAITING_ACCESS, isOwner = false), res.getString(ranOut.title, "Family"))
        assertFalse(ranOut.offersRestore)
        // Nothing asked, or an owner: the plain explanation.
        assertEquals(body(Cause.AWAITING_ACCESS, isOwner = false), res.getString(LockedCircle.explanationFor(Cause.AWAITING_ACCESS, false, null).body, "Family"))
        assertEquals(body(Cause.AWAITING_ACCESS, isOwner = true), res.getString(LockedCircle.explanationFor(Cause.AWAITING_ACCESS, true, LockedCircle.RanOut("Ada")).body, "Family"))
    }

    @Test
    fun `with no name here for whom it asked, the line names nobody - never a stand-in such as "A member"`() {
        val ranOut = LockedCircle.explanationFor(Cause.AWAITING_ACCESS, isOwner = false, ranOut = LockedCircle.RanOut(null))
        assertNull(ranOut.bodyName)
        // The screen passes the circle's name when the explanation names nobody (CirclesFragment).
        assertEquals(
            "Your invitation ran out before anyone's phone could hand the circle over. An owner or admin of the circle has been asked to let you in.",
            res.getString(ranOut.body, ranOut.bodyName ?: "Family"),
        )
        assertFalse(ranOut.offersRestore)
    }

    @Test
    fun `each outcome maps to its cause`() {
        assertEquals(Cause.AWAITING_ACCESS, LockedCircle.causeOf(WrapOutcome.NO_WRAP, preResetDataExists = null))
        assertEquals(Cause.KEY_REFUSED, LockedCircle.causeOf(WrapOutcome.REFUSED, preResetDataExists = true))
        assertEquals(Cause.OWN_RESET, LockedCircle.causeOf(WrapOutcome.UNOPENABLE, preResetDataExists = true))
        assertEquals(Cause.UNREADABLE, LockedCircle.causeOf(WrapOutcome.UNOPENABLE, preResetDataExists = false))
        // Unknown reset state never becomes "your reset": that would send somebody
        // hunting for a recovery key that cannot help.
        assertEquals(Cause.UNREADABLE, LockedCircle.causeOf(WrapOutcome.UNOPENABLE, preResetDataExists = null))
        // Not looked at yet this process: the reset answer decides.
        assertEquals(Cause.OWN_RESET, LockedCircle.causeOf(null, preResetDataExists = true))
        assertEquals(Cause.UNREADABLE, LockedCircle.causeOf(null, preResetDataExists = null))
    }

    @Test
    fun `no explanation talks about keys or sends anybody to an owner or an admin`() {
        // No user-facing text talks about keys; another member's app, online, is what opens
        // a circle. "recovery key" is a thing users hold.
        val banned = Regex("""\b(keys?|wrap|pin|safety number|re-?key)\b""", RegexOption.IGNORE_CASE)
        for (cause in Cause.values()) for (isOwner in listOf(false, true)) {
            for (text in listOf(title(cause, isOwner), body(cause, isOwner))) {
                val words = text.replace("recovery key", "", ignoreCase = true)
                assertFalse("$cause (owner=$isOwner): $text", banned.containsMatchIn(words))
                assertFalse("$cause (owner=$isOwner): $text", text.contains("owner or an admin"))
            }
        }
    }

    @Test
    fun `after your own reset the explanation says the circle confirms it and points at the restore`() {
        val explanation = LockedCircle.explanationFor(Cause.OWN_RESET, isOwner = true)
        assertTrue(explanation.offersRestore)
        assertEquals("Waiting for your circle", title(Cause.OWN_RESET, isOwner = true))
        val text = body(Cause.OWN_RESET, isOwner = true)
        // What happens — the members' phones already have the question.
        assertTrue(text, text.contains("another member of Family has been asked to confirm it's you"))
        assertTrue(text, text.contains("It opens here as soon as one of them does."))
        assertTrue(text, text.contains("Data from before your password reset"))
        assertTrue(text, text.contains("recovery key"))
        // A member after their own reset gets the same pointer.
        assertTrue(LockedCircle.explanationFor(Cause.OWN_RESET, isOwner = false).offersRestore)
    }

    @Test
    fun `a member who just joined is told it opens by itself, soon`() {
        assertEquals("Almost ready", title(Cause.AWAITING_ACCESS, isOwner = false))
        val text = body(Cause.AWAITING_ACCESS, isOwner = false)
        assertEquals(
            "You've joined Family. It opens here as soon as another member's app is online, usually within a minute.",
            text,
        )
        assertFalse(LockedCircle.explanationFor(Cause.AWAITING_ACCESS, isOwner = false).offersRestore)
        // An owner never "joined" their own circle.
        assertEquals("Waiting to sync", title(Cause.AWAITING_ACCESS, isOwner = true))
    }

    @Test
    fun `a copy this phone refused, cannot open or is behind on waits to sync, word for word`() {
        for (cause in listOf(Cause.KEY_REFUSED, Cause.UNREADABLE, Cause.BEHIND)) for (isOwner in listOf(false, true)) {
            assertEquals("Waiting to sync", title(cause, isOwner))
            assertEquals(
                "Awaiting sync for circle settings from another member. Locations will be queued until synced.",
                body(cause, isOwner),
            )
            assertFalse(LockedCircle.explanationFor(cause, isOwner).offersRestore)
        }
    }

    @Test
    fun `every shape of placeholder reads as the one locked-circle name`() {
        for (stored in listOf(
            "Locked circle",
            "Circle (key unavailable)",
            "[Encrypted] 3f9a",
            "[Key unavailable]",
            "0123456789abcdef0123456789abcdef",
        )) {
            assertTrue(stored, LockedCircle.isPlaceholder(stored))
            assertEquals(stored, LockedCircle.PLACEHOLDER_NAME, CirclesAdapter.sanitizeCircleName(stored))
        }
        assertFalse(LockedCircle.isPlaceholder("Family"))
        assertEquals("Family", CirclesAdapter.sanitizeCircleName("Family"))
    }
}
