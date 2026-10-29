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

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.CircleMemberEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * Why a member's location isn't updating, as the circle sees it: the server's rule mirrored
 * on the phone, and the same English sentences as the web app and the iPhone app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemberSharingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val defaultZone = TimeZone.getDefault()
    private val defaultLocale = Locale.getDefault()

    /** 2026-10-14 (a Wednesday) 09:00 UTC. */
    private val now = 1_791_968_400_000L
    private val hour = 3_600_000L

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
    }

    @After
    fun restore() {
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    /** Platform formats may use a narrow no-break space before "PM"; the words are what is compared. */
    private fun plain(s: String?) = s?.replace(' ', ' ')?.replace(' ', ' ')

    private fun member(id: String, sharing: String?, until: Long? = null, circle: String = "c-1") = CircleMemberEntity(
        circleId = circle, userId = id, displayName = id, role = "member", joinedAt = 0L,
        locationSharing = sharing, sharingPausedUntil = until,
    )

    // ---- the rule ----

    @Test
    fun `off, paused and sharing as the server reports them`() {
        assertEquals(SharingStatus.Off, MemberSharing.status("off", null, now))
        assertEquals(SharingStatus.Paused(now + hour), MemberSharing.status("paused", now + hour, now))
        assertEquals("a pause with no end is a pause", SharingStatus.Paused(null), MemberSharing.status("paused", null, now))
        assertEquals("a pause whose end has passed is sharing, as the server reads it", SharingStatus.On, MemberSharing.status("paused", now - 1, now))
        assertEquals(SharingStatus.On, MemberSharing.status("paused", now, now))
        assertEquals("an older server says nothing: sharing", SharingStatus.On, MemberSharing.status(null, null, now))
        assertEquals("an unknown word hides nobody", SharingStatus.On, MemberSharing.status("approximate", null, now))
    }

    @Test
    fun `this account's own state - real-time off wins over a pause`() {
        assertEquals(SharingStatus.Off, MemberSharing.own(shareRealtime = false, sharingMode = "paused", pausedUntilMs = now + hour, nowMs = now))
        assertEquals(SharingStatus.Paused(now + hour), MemberSharing.own(true, "paused", now + hour, now))
        assertEquals(SharingStatus.Paused(null), MemberSharing.own(true, "paused", null, now))
        assertEquals(SharingStatus.On, MemberSharing.own(true, "paused", now - hour, now))
        assertEquals(SharingStatus.On, MemberSharing.own(true, "exact", null, now))
        assertEquals("Gson can leave the mode null", SharingStatus.On, MemberSharing.own(true, null, null, now))
    }

    @Test
    fun `what a members-list entry is kept as`() {
        assertEquals("off" to null, MemberSharing.fromWire("off", null))
        assertEquals("off" to null, MemberSharing.fromWire("off", "2026-10-14T17:00:00Z"))
        assertEquals("paused" to 1_791_997_200_000L, MemberSharing.fromWire("paused", "2026-10-14T17:00:00Z"))
        assertEquals("paused" to null, MemberSharing.fromWire("paused", null))
        assertEquals("an unreadable end is a pause with no end", "paused" to null, MemberSharing.fromWire("paused", "tomorrow"))
        assertEquals(null to null, MemberSharing.fromWire("on", null))
        assertEquals(null to null, MemberSharing.fromWire(null, null))
    }

    @Test
    fun `a pause's end in each form the server writes it`() {
        // RFC 3339, as the server writes sharing_paused_until and the privacy row's
        // paused_until.
        assertEquals(1_791_997_200_000L, MemberSharing.epochMs("2026-10-14T17:00:00+00:00"))
        assertEquals(1_791_997_200_123L, MemberSharing.epochMs("2026-10-14T17:00:00.123456+00:00"))
        assertEquals(1_791_997_200_123L, MemberSharing.epochMs("2026-10-14T17:00:00.123+00:00"))
        assertEquals(1_791_997_200_000L, MemberSharing.epochMs("2026-10-14T17:00:00Z"))
        assertEquals("the offset is applied, not dropped", 1_791_997_200_000L, MemberSharing.epochMs("2026-10-14T19:00:00+02:00"))
        assertEquals("paused" to 1_791_997_200_123L, MemberSharing.fromWire("paused", "2026-10-14T17:00:00.123456+00:00"))
        assertNull("a time with no offset can't be placed", MemberSharing.epochMs("2026-10-14T17:00:00"))
    }

    /**
     * The cases above pass on this JVM whatever the parser: its `Instant.parse` reads "+00:00"
     * (JDK 12 and later). Android's before 14 reads only "Z", so there an `Instant.parse` loses
     * every end and the label says "Paused" with no time. Hence a look at the source.
     */
    @Test
    fun `a pause's end is not read with Instant parse, which reads only Z before Android 14`() {
        val src = java.io.File("src/main/java/com/pingolin/track/ui/circles/MemberSharing.kt").readText()
        val start = src.indexOf("fun epochMs(")
        assertTrue("epochMs not found — update this test with the new name", start >= 0)
        val body = src.substring(start).substringBefore("\n\n")
        assertTrue(body, body.contains("OffsetDateTime.parse("))
        assertFalse(body, body.contains("Instant.parse("))
    }

    @Test
    fun `the map leaves out off and paused members, by circle, never this phone's own`() {
        val members = listOf(
            member("me", "off"),
            member("ann", "off"),
            member("bob", "paused", now + hour),
            member("cat", "paused", now - hour),
            member("dan", null),
            member("eve", "paused", null, circle = "c-2"),
        )
        assertEquals(
            setOf("c-1" to "ann", "c-1" to "bob", "c-2" to "eve"),
            MemberSharing.hiddenOnMap(members, selfId = "me", nowMs = now),
        )
        assertTrue(MemberSharing.hidesMarker(SharingStatus.Off))
        assertTrue(MemberSharing.hidesMarker(SharingStatus.Paused(null)))
        assertFalse(MemberSharing.hidesMarker(SharingStatus.On))
    }

    // ---- the words ----

    @Test
    fun `a pause's end - the time today, the weekday and time another day`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("5:00 PM", plain(MemberSharing.until(context, now + 8 * hour, now, utc)))
        assertEquals("Thu 5:00 PM", plain(MemberSharing.until(context, now + 32 * hour, now, utc)))
        assertTrue(MemberSharing.sameDay(now, now + 8 * hour, utc))
        assertFalse(MemberSharing.sameDay(now, now + 32 * hour, utc))
    }

    @Test
    fun `a pause's end follows the phone's 24-hour setting`() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertEquals("17:00", plain(MemberSharing.until(context, now + 8 * hour, now, TimeZone.getTimeZone("UTC"))))
    }

    @Test
    fun `the line under a member's name`() {
        assertNull(MemberSharing.rowLabel(context, SharingStatus.On, now))
        assertEquals("Location sharing off", MemberSharing.rowLabel(context, SharingStatus.Off, now))
        assertEquals("Paused until 5:00 PM", plain(MemberSharing.rowLabel(context, SharingStatus.Paused(now + 8 * hour), now)))
        assertEquals("Paused", MemberSharing.rowLabel(context, SharingStatus.Paused(null), now))
    }

    @Test
    fun `the sentence on a member's card`() {
        assertNull(MemberSharing.detailSentence(context, "Ann", SharingStatus.On, now))
        assertEquals("Ann turned off location sharing in this circle.", MemberSharing.detailSentence(context, "Ann", SharingStatus.Off, now))
        assertEquals(
            "Ann paused location sharing until Thu 5:00 PM.",
            plain(MemberSharing.detailSentence(context, "Ann", SharingStatus.Paused(now + 32 * hour), now)),
        )
        assertEquals("Ann paused location sharing.", MemberSharing.detailSentence(context, "Ann", SharingStatus.Paused(null), now))
    }

    @Test
    fun `what this account is told about its own sharing`() {
        assertNull(MemberSharing.ownSentence(context, SharingStatus.On, now))
        assertEquals(
            "Location sharing is off in this circle. Members see your history up to then and may still see the last location you shared, with “Location sharing off” next to your name.",
            MemberSharing.ownSentence(context, SharingStatus.Off, now),
        )
        assertEquals(
            "Paused until 5:00 PM. Members see this next to your name.",
            plain(MemberSharing.ownSentence(context, SharingStatus.Paused(now + 8 * hour), now)),
        )
        assertEquals("Paused. Members see this next to your name.", MemberSharing.ownSentence(context, SharingStatus.Paused(null), now))
    }

    @Test
    fun `with location history off, this account's own sentence says so - history alone, or both with real time`() {
        assertEquals(
            "Location history is off in this circle. Members see only where you are now. Track still keeps your history, and they'll see all of it if you turn it back on.",
            MemberSharing.ownSentence(context, SharingStatus.On, shareHistory = false, nowMs = now),
        )
        assertEquals(
            "Location sharing and location history are off in this circle. Members see “Location sharing off” next to your name, and none of your locations.",
            MemberSharing.ownSentence(context, SharingStatus.Off, shareHistory = false, nowMs = now),
        )
        assertEquals("a pause is said as a pause", "Paused. Members see this next to your name.", MemberSharing.ownSentence(context, SharingStatus.Paused(null), false, now))
        // History on: the sentence is the same as without it.
        assertNull(MemberSharing.ownSentence(context, SharingStatus.On, true, now))
        assertEquals(MemberSharing.ownSentence(context, SharingStatus.Off, now), MemberSharing.ownSentence(context, SharingStatus.Off, true, now))
        // The privacy dialog says it with the circle's history switch.
        val screen = java.io.File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(screen.contains("val sentence = MemberSharing.ownSentence(requireContext(), status, settings.shareHistory, now)"))
    }

    @Test
    fun `the privacy dialog says a pause once`() {
        // Real-time on: the sentence at the top says the pause, so nothing beside Resume.
        assertEquals(
            "Paused until 5:00 PM. Members see this next to your name.",
            plain(MemberSharing.ownSentence(context, MemberSharing.own(true, "paused", now + 8 * hour, now), now)),
        )
        assertNull(MemberSharing.pauseLine(context, shareRealtime = true, pausedUntilMs = now + 8 * hour, nowMs = now))
        assertNull(MemberSharing.pauseLine(context, true, null, now))
        // Real-time off as well: the top says that, so the line says the pause.
        assertEquals(
            "Location sharing is off in this circle. Members see your history up to then and may still see the last location you shared, with “Location sharing off” next to your name.",
            MemberSharing.ownSentence(context, MemberSharing.own(false, "paused", now + 8 * hour, now), now),
        )
        assertEquals("Paused until 5:00 PM", plain(MemberSharing.pauseLine(context, false, now + 8 * hour, now)))
        assertEquals("Paused", MemberSharing.pauseLine(context, false, null, now))
        assertNull("an ended pause is not said", MemberSharing.pauseLine(context, false, now - hour, now))
    }

    @Test
    fun `the privacy dialog takes the line beside Resume from pauseLine, not hard-coded words`() {
        val src = java.io.File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(src.contains("MemberSharing.pauseLine("))
        assertFalse(src.contains("\"Sharing paused"))
    }

    @Test
    fun `while paused the approximate switch shows what Resume goes back to, and can't be changed`() {
        assertTrue(MemberSharing.bubbleOn("approximate", null))
        assertFalse(MemberSharing.bubbleOn("exact", null))
        assertTrue("paused from approximate", MemberSharing.bubbleOn("paused", "approximate"))
        assertFalse("paused from exact", MemberSharing.bubbleOn("paused", "exact"))
        assertFalse("paused, from a server that doesn't say", MemberSharing.bubbleOn("paused", null))
        assertTrue(MemberSharing.bubbleEditable("approximate"))
        assertTrue(MemberSharing.bubbleEditable("exact"))
        assertFalse("a change would end the pause", MemberSharing.bubbleEditable("paused"))
    }

    @Test
    fun `the privacy dialog's approximate switch and radius follow those rules`() {
        val src = java.io.File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(src.contains("val isBubble = MemberSharing.bubbleOn(settings.sharingMode, settings.modeBeforePause)"))
        assertTrue(src.contains("dialogBinding.switchBubbleMode.isEnabled = bubbleEditable"))
        assertTrue(src.contains("dialogBinding.sliderBubbleRadius.isEnabled = bubbleEditable"))
    }
}
