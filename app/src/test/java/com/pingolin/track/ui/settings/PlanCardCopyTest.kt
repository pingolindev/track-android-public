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

import android.content.res.Resources
import com.pingolin.track.data.remote.dto.QuotaDto
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Plan card's grace-countdown sentence.
 *
 * WHY THE WORDING IS WORTH A TEST
 *   The server returns `history_expires_in_days` ONLY for a user who is
 *   already on the free tier, so their premium has ended and their visible
 *   history has already dropped. What is still counting down is deletion of
 *   the older data — the one part re-subscribing can still undo. A sentence
 *   like "Premium ends in N days — history drops to M days" would describe as
 *   pending something that has already happened, and a rendering test would
 *   pass anyway; only asserting on the sentence catches it.
 *
 *   Resources are resolved for real (Robolectric) rather than stubbed, so the
 *   plural selection and the two-placeholder substitution are exercised as they
 *   ship. A mocked `getString` would happily return whatever it was told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlanCardCopyTest {

    private lateinit var res: Resources

    @Before
    fun setup() {
        res = ApplicationProvider.getApplicationContext<android.app.Application>().resources
    }

    @Test
    fun `the countdown names the deletion, not the already-ended subscription`() {
        val msg = PlanCardCopy.historyExpiring(res, daysLeft = 5, keptDays = 2, circleScoped = true)
        assertEquals(
            "Location history older than 2 days is deleted in 5 days. " +
                "Resubscribe, or have a premium member join your circle, to keep it.",
            msg,
        )
        assertFalse(
            "the corrected copy must not claim premium is still about to end",
            msg.contains("Premium ends"),
        )
    }

    /**
     * THE SECOND SUBJECT.
     *
     * The server returns whichever expires first of a circle's history and the
     * user's own, so the same countdown describes either one, and
     * `history_expires_circle_id` is null when it is the user's own.
     *
     * The remedy is not shared between them. Personal history is culled on the
     * user's own premium status, so nobody joining their circle protects it, and
     * telling them otherwise is a wrong instruction delivered at the exact
     * moment they would act on it.
     */
    @Test
    fun `personal history is not fixed by somebody joining your circle`() {
        val msg = PlanCardCopy.historyExpiring(res, daysLeft = 5, keptDays = 2, circleScoped = false)
        assertEquals(
            "Location history older than 2 days is deleted in 5 days. Resubscribe to keep it.",
            msg,
        )
        assertFalse(
            "personal history gains nothing from a premium member joining a circle",
            msg.contains("circle"),
        )
    }

    /**
     * The two sentences differ ONLY in the remedy. Whichever subject is counting
     * down, the deadline itself is the same fact and must read identically —
     * a divergence there would be a second, silent copy to keep in step.
     */
    @Test
    fun `both subjects state the same deadline`() {
        val circle = PlanCardCopy.historyExpiring(res, daysLeft = 3, keptDays = 7, circleScoped = true)
        val personal = PlanCardCopy.historyExpiring(res, daysLeft = 3, keptDays = 7, circleScoped = false)
        val deadline = "Location history older than 7 days is deleted in 3 days."
        assertTrue(circle.startsWith(deadline))
        assertTrue(personal.startsWith(deadline))
    }

    /**
     * The kept figure comes from the server's `history_days`, never a
     * hardcoded free-tier constant, which would go stale as the plans change.
     */
    @Test
    fun `kept days comes from the server rather than a hardcoded free tier`() {
        assertTrue(
            PlanCardCopy.historyExpiring(res, daysLeft = 3, keptDays = 7)
                .startsWith("Location history older than 7 days"),
        )
        assertTrue(
            PlanCardCopy.historyExpiring(res, daysLeft = 3, keptDays = 365)
                .startsWith("Location history older than 365 days"),
        )
    }

    /**
     * The server clamps the countdown at 0, so the last day of the grace
     * window genuinely arrives as 0 and "in 0 days" reads as a broken
     * template rather than a deadline.
     */
    @Test
    fun `zero reads as today and one as tomorrow`() {
        assertTrue(
            PlanCardCopy.historyExpiring(res, daysLeft = 0, keptDays = 2)
                .contains("is deleted today."),
        )
        assertTrue(
            PlanCardCopy.historyExpiring(res, daysLeft = 1, keptDays = 2)
                .contains("is deleted tomorrow."),
        )
    }

    /** Only reachable from a malformed server answer, but it must still not
     * render "in -2 days".
     */
    @Test
    fun `a negative countdown does not leak a minus sign into the sentence`() {
        val msg = PlanCardCopy.historyExpiring(res, daysLeft = -2, keptDays = 2)
        assertTrue(msg.contains("is deleted today."))
        assertFalse(msg.contains("-"))
    }

    /** Plurals, so a one-day retention does not render as "1 days". */
    @Test
    fun `singular kept days is not pluralised`() {
        assertTrue(
            PlanCardCopy.historyExpiring(res, daysLeft = 4, keptDays = 1)
                .startsWith("Location history older than 1 day is"),
        )
    }

    // ---------------------------------------------------------------------
    // The quota-driven overload: whether to say anything, and about
    // what.
    //
    // Two decisions, kept out of SettingsFragment so they can be
    // asserted on: whether to say anything, and whether the countdown is
    // the user's own history or a circle's — without the second, every
    // countdown would get the circle wording.
    // ---------------------------------------------------------------------

    private fun quota(
        expiresInDays: Long?,
        circleId: String? = null,
        historyDays: Long = 2,
    ) = QuotaDto(
        historyDays = historyDays,
        historyExpiresInDays = expiresInDays,
        historyExpiresCircleId = circleId,
    )

    @Test
    fun `a countdown against a circle keeps the circle remedy`() {
        assertEquals(
            "Location history older than 2 days is deleted in 5 days. " +
                "Resubscribe, or have a premium member join your circle, to keep it.",
            PlanCardCopy.historyExpiring(res, quota(5, circleId = "c-1")),
        )
    }

    @Test
    fun `a countdown with no circle id is the users own history`() {
        // The central case. history_expires_circle_id is null when
        // the countdown is for the user's own history; a call site
        // that never read the field would render the circle advice.
        val msg = PlanCardCopy.historyExpiring(res, quota(5, circleId = null))
        assertEquals(
            "Location history older than 2 days is deleted in 5 days. Resubscribe to keep it.",
            msg,
        )
        assertFalse(msg!!.contains("circle"))
    }

    @Test
    fun `no countdown means no note at all`() {
        // Null covers both "premium is active" and "there was never premium to
        // lose". Neither should nag, and the fragment hides the view on null.
        assertNull(PlanCardCopy.historyExpiring(res, quota(expiresInDays = null)))
    }

    @Test
    fun `the last day of the window still renders`() {
        // 0 is a real value, not an absent one: the server clamps the
        // countdown at 0, so the day the data goes arrives as zero. A truthy
        // check here would hide the warning on the single most urgent day.
        val msg = PlanCardCopy.historyExpiring(res, quota(0, circleId = "c-1"))
        assertNotNull(msg)
        assertTrue(msg!!.contains("is deleted today."))
    }

    @Test
    fun `kept days still comes from the server through the quota overload`() {
        assertTrue(
            PlanCardCopy.historyExpiring(res, quota(3, circleId = "c-1", historyDays = 365))!!
                .startsWith("Location history older than 365 days"),
        )
    }
}
