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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-deletion billing notice is the LAST thing the account can ever tell
 * anyone — once the delete returns there is no session, no screen and no email
 * to follow up with. So what it leaves out is money the person goes on paying
 * without knowing.
 *
 * Subscriptions are unique per (user, product), so somebody can hold a Track
 * subscription through the App Store and a Note one billed by Paddle; if the
 * Paddle cancellation fails, the server reports BOTH counts. A notice that
 * names only Apple would leave them cancelling with Apple, thinking they were
 * finished, and still billed by the provider nobody named.
 */
class BillingNoticeTest {

    private val appleMarker = "App Store subscription is still running"
    private val supportMarker = "help@support.pingolin.com"

    private fun notice(appStore: Int, failed: Int): String =
        AccountDeletionOutcome(
            appStoreSubscriptionsRemaining = appStore,
            cancellationsFailed = failed,
            needsAttention = appStore > 0 || failed > 0,
        ).deletionNotice()

    @Test
    fun `app store only names apple and not support`() {
        val text = notice(appStore = 1, failed = 0)
        assertTrue(text, text.contains(appleMarker))
        assertFalse("must not send them to support when nothing failed", text.contains(supportMarker))
    }

    @Test
    fun `failed cancellation only names support and not apple`() {
        val text = notice(appStore = 0, failed = 1)
        assertTrue(text, text.contains(supportMarker))
        assertFalse("must not blame Apple when no Apple sub is running", text.contains(appleMarker))
    }

    /** The case this whole file exists for. */
    @Test
    fun `both counts are reported, not just the app store one`() {
        val text = notice(appStore = 1, failed = 1)
        assertTrue("must still name the Apple subscription: $text", text.contains(appleMarker))
        assertTrue("must ALSO tell them to write in: $text", text.contains(supportMarker))
        assertTrue(
            "must mark the second one as a DIFFERENT subscription, or it reads as a " +
                "restatement of the Apple one: $text",
            text.contains("Separately"),
        )
    }

    @Test
    fun `more than one app store subscription is not described in the singular`() {
        val text = notice(appStore = 2, failed = 0)
        assertTrue(text, text.contains("2 App Store subscriptions are still running"))
        assertTrue("plural must ask them to cancel them, not it: $text", text.contains("cancel them yourself"))
    }

    /**
     * Unreachable while the server's `billing_needs_attention` is
     * `(remaining > 0 || failed > 0)`, but if that flag ever widens, the dialog
     * must not come up blank.
     */
    @Test
    fun `neither count still says something actionable`() {
        val text = notice(appStore = 0, failed = 0)
        assertTrue(text, text.contains(supportMarker))
        assertTrue("must not end mid-sentence: $text", text.trim().endsWith("."))
    }

    /** The erasure half. A queued erasure is the one thing the user actually
     * asked for, so the notice must say it. */
    @Test
    fun `a queued erasure is reported`() {
        val text = AccountDeletionOutcome(dataErasure = "queued").deletionNotice()
        assertTrue("must say the erasure has not finished: $text", text.contains("retried until it goes"))
        assertTrue("must give the address to check: $text", text.contains("privacy@mail.pingolin.com"))
    }

    @Test
    fun `a failed erasure is reported as possibly not done`() {
        val text = AccountDeletionOutcome(dataErasure = "failed").deletionNotice()
        assertTrue("must not imply it completed: $text", text.contains("may not have"))
        assertTrue(text, text.contains("privacy@mail.pingolin.com"))
    }

    @Test
    fun `a completed erasure adds nothing`() {
        val text = AccountDeletionOutcome(appStoreSubscriptionsRemaining = 1, dataErasure = "completed").deletionNotice()
        assertFalse("nothing outstanding must not mention privacy@: $text", text.contains("privacy@mail.pingolin.com"))
    }

    /** Billing clean, erasure queued: a billing-only gate would drop this
     * case entirely. */
    @Test
    fun `erasure alone still produces a notice`() {
        assertNotNull(erasureNotice("queued"))
        assertNull(erasureNotice("completed"))
        assertNull(erasureNotice("not_applicable"))
    }
}
