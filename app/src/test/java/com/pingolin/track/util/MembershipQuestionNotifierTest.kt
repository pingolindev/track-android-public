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

package com.pingolin.track.util

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two questions only a person can answer reach them as a notification: plain
 * wording, at most once a day per question, never while the Circles screen shows it,
 * gone once answered, and as private as Settings → Notification detail asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MembershipQuestionNotifierTest {

    private lateinit var context: Context
    private val session = mockk<SessionManager>()
    private var now = 1_000_000_000L
    private lateinit var notifier: AndroidMembershipQuestionNotifier

    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun shown() = shadowOf(manager).allNotifications.map {
        it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        every { session.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        notifier = AndroidMembershipQuestionNotifier(context, session) { now }
        MembershipQuestionNotifier.circlesScreenShowing = false
    }

    @After
    fun tearDown() {
        MembershipQuestionNotifier.circlesScreenShowing = false
    }

    @Test
    fun `the two questions, in plain words, on their own channel`() {
        notifier.memberReset("c-1", "bob", "Bob")
        notifier.waitingToJoin("c-1", "Family", "sam", "Sam")

        assertEquals(
            setOf("Bob reset their password. Check it's really them.", "Sam is waiting to join Family."),
            shown().toSet(),
        )
        assertEquals(
            setOf(AndroidMembershipQuestionNotifier.CHANNEL_ID),
            shadowOf(manager).allNotifications.map { it.channelId }.toSet(),
        )
    }

    @Test
    fun `at most once a day per question, and not while the Circles screen shows it`() {
        notifier.memberReset("c-1", "bob", "Bob")
        notifier.answered("c-1", "bob")
        // Raised again within the day: not posted again.
        now += AndroidMembershipQuestionNotifier.ONCE_A_DAY_MS - 1
        notifier.memberReset("c-1", "bob", "Bob")
        assertTrue(shown().isEmpty())

        // On the Circles screen the question is on screen already.
        now += 2
        MembershipQuestionNotifier.circlesScreenShowing = true
        notifier.memberReset("c-1", "bob", "Bob")
        assertTrue(shown().isEmpty())

        // A day later, off that screen: once more.
        MembershipQuestionNotifier.circlesScreenShowing = false
        notifier.memberReset("c-1", "bob", "Bob")
        assertEquals(listOf("Bob reset their password. Check it's really them."), shown())
    }

    @Test
    fun `an answered question takes its notification with it, and no other`() {
        notifier.memberReset("c-1", "bob", "Bob")
        notifier.waitingToJoin("c-1", "Family", "sam", "Sam")

        notifier.answered("c-1", "bob")

        assertEquals(listOf("Sam is waiting to join Family."), shown())
    }

    @Test
    fun `as private as the notification detail setting asks`() {
        every { session.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_NAMES
        notifier.waitingToJoin("c-1", "Family", "sam", "Sam")
        assertEquals(listOf("Sam is waiting to join a circle."), shown())

        every { session.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_GENERIC
        notifier.memberReset("c-1", "bob", "Bob")
        assertTrue(shown().contains("A circle needs your answer."))
    }

    @Test
    fun `a question an earlier build posted under another spelling of its circle or member counts, the later post standing`() {
        val circle = "c1a2b3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        val bob = "b0b00b1c-d2e3-4f40-9a1b-c2d3e4f5a6b7"
        context.getSharedPreferences("circle_questions_posted", Context.MODE_PRIVATE).edit()
            .putLong("reset|${circle.uppercase()}|${bob.replace("-", "")}", now - 1_000)
            .putLong("reset|$circle|$bob", now - AndroidMembershipQuestionNotifier.ONCE_A_DAY_MS)
            .commit()

        notifier.memberReset("{$circle}", bob.uppercase(), "Bob")

        assertTrue(shown().isEmpty())
        assertEquals(
            mapOf("reset|$circle|$bob" to now - 1_000),
            context.getSharedPreferences("circle_questions_posted", Context.MODE_PRIVATE).all,
        )
    }

    @Test
    fun `a member this phone has no name for is "A member"`() {
        notifier.memberReset("c-1", "bob", null)
        assertEquals(listOf("A member reset their password. Check it's really them."), shown())
    }
}
