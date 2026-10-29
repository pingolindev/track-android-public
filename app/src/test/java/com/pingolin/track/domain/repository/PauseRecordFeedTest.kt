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

import com.google.gson.Gson
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import io.mockk.clearAllMocks
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every privacy row this phone stores, from any path, feeds the record a late point is held
 * against: paused, real-time sharing off (the server's ingest rule), and the server's
 * `not_sharing_intervals`, decoded from the server's own JSON.
 */
class PauseRecordFeedTest {

    @After
    fun tearDown() = clearAllMocks()

    private fun row(mode: String = "exact", realtime: Boolean = true, pausedUntil: String? = null) = PrivacySettingsResponse(
        circleId = "c-1", sharingMode = mode, shareRealtime = realtime, shareHistory = true, shareBattery = true,
        pausedUntil = pausedUntil, primaryDeviceId = null,
    )

    @Test
    fun `a pause and real-time sharing off are recorded from the rows stored here, and their end when they stop`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000

        h.repository.persistPrivacyLocally(row())
        h.statements.now += 600
        h.repository.persistPrivacyLocally(row(mode = "paused"))
        assertTrue("paused, with no end", h.pauseIntervals.covers("c-1", now + 30 * 60_000L))
        assertFalse("before it: the last time it was seen shared with", h.pauseIntervals.covers("c-1", now - 2 * 60_000L))

        h.statements.now += 3_600
        h.repository.persistPrivacyLocally(row())
        val ended = h.statements.now * 1000
        assertTrue(h.pauseIntervals.covers("c-1", ended - 60_000L))
        assertFalse(h.pauseIntervals.covers("c-1", ended + 2 * 60_000L))

        h.statements.now += 3_600
        h.repository.persistPrivacyLocally(row(realtime = false))
        assertTrue("real-time sharing off is not sharing too", h.pauseIntervals.covers("c-1", h.statements.now * 1000))
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toString().replace("Z", "+00:00")

    @Test
    fun `the server's spans of not sharing, as its JSON writes them, are recorded - a pause this phone never saw`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        val start = now - 5 * 3_600_000L
        val end = now - 3 * 3_600_000L
        // Paused since a minute ago, with no end: the span still open (a row that says sharing ends it).
        val json = """{"id":"5f0c7d1e-0000-4000-8000-000000000001","circle_id":"c-1","sharing_mode":"paused",""" +
            """"approximate_radius_miles":null,"approximate_until":null,"paused_until":null,"mode_before_pause":null,""" +
            """"share_realtime":true,"share_history":true,"share_battery":true,"primary_device_id":null,""" +
            """"primary_device_pseudonym":null,"show_all_devices":false,"allow_alerts_on_me":true,""" +
            """"created_day_bucket":20374,"updated_day_bucket":20375,""" +
            """"not_sharing_intervals":[{"start":"${iso(start)}","end":"${iso(end)}"},{"start":"${iso(now - 60_000L)}","end":null}]}"""
        val decoded = Gson().fromJson(json, PrivacySettingsResponse::class.java)
        assertEquals(2, decoded.notSharingIntervals?.size)

        h.repository.persistPrivacyLocally(decoded)

        assertTrue(h.pauseIntervals.covers("c-1", start + 3_600_000L))
        assertFalse(h.pauseIntervals.covers("c-1", end + 2 * 60_000L))
        assertTrue("one with no end: from its start on", h.pauseIntervals.covers("c-1", now + 3_600_000L))
    }

    @Test
    fun `a span whose start doesn't read, or that isn't there, covers from the beginning - never 'no pause'`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        val end = now - 3_600_000L
        fun row(circle: String, spans: String) = Gson().fromJson(
            """{"circle_id":"$circle","sharing_mode":"exact","share_realtime":true,"share_history":true,"share_battery":true,""" +
                """"paused_until":null,"primary_device_id":null,"not_sharing_intervals":$spans}""",
            PrivacySettingsResponse::class.java,
        )

        h.repository.persistPrivacyLocally(row("c-1", """[{"start":"yesterday","end":"${iso(end)}"}]"""))
        assertTrue(h.pauseIntervals.covers("c-1", now - 5 * 24 * 3_600_000L))
        assertFalse("its end still reads", h.pauseIntervals.covers("c-1", end + 2 * 60_000L))

        h.repository.persistPrivacyLocally(row("c-2", "[null]"))
        assertTrue(h.pauseIntervals.covers("c-2", now - 5 * 24 * 3_600_000L))
        assertTrue(h.pauseIntervals.covers("c-2", now))

        h.repository.persistPrivacyLocally(row("c-3", """[{"end":null}]"""))
        assertTrue("no start at all", h.pauseIntervals.covers("c-3", now))
    }

    @Test
    fun `the row kept for a circle the server has no row for ends a pause still open, as of then`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        h.repository.persistPrivacyLocally(Gson().fromJson(
            """{"circle_id":"c-1","sharing_mode":"paused","share_realtime":true,"share_history":true,"share_battery":true,""" +
                """"paused_until":null,"primary_device_id":null,"not_sharing_intervals":[{"start":"${iso(now - 3_600_000L)}","end":null}]}""",
            PrivacySettingsResponse::class.java,
        ))
        h.statements.now += 600
        // CircleRepository's default for a 404: exact, sharing, no spans.
        h.repository.persistPrivacyLocally(PrivacySettingsResponse(
            circleId = "c-1", shareRealtime = true, shareHistory = true, shareBattery = true, pausedUntil = null, primaryDeviceId = null,
        ))
        val then = h.statements.now * 1000

        assertTrue(h.pauseIntervals.covers("c-1", then - 60_000L))
        assertFalse(h.pauseIntervals.covers("c-1", then + 2 * 60_000L))
    }

    @Test
    fun `another account's sign-in takes this account's pauses, and an erasure all - its own sign-in keeps them`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        h.repository.persistPrivacyLocally(row(mode = "paused"))
        assertTrue(h.pauseIntervals.covers("c-1", now))

        h.repository.keepOnlyAccount(h.self) { true }
        assertTrue("its own sign-in", h.pauseIntervals.covers("c-1", now))
        h.repository.keepOnlyAccount("07e40000-0000-4000-8000-0000000000e7") { true }
        assertFalse("another account's sign-in", h.pauseIntervals.covers("c-1", now))

        h.repository.persistPrivacyLocally(row(mode = "paused"))
        h.repository.wipeAccountEntries()
        assertFalse("an erasure", h.pauseIntervals.covers("c-1", now))
    }

    @Test
    fun `a resume, or real-time sharing on again, made on this phone is marked as made here, before its row is kept`() {
        val vm = java.io.File("src/main/java/com/pingolin/track/ui/circles/CirclesViewModel.kt").readText()
        val resume = vm.substring(vm.indexOf("fun resumeSharing("))
        assertTrue(resume.indexOf("circleRepository.resumedHere(circleId)") in 0 until resume.indexOf("circleRepository.persistPrivacyLocally(data)"))
        val update = vm.substring(vm.indexOf("fun updatePrivacySettings("))
        // The row is read again on the write's own trip (readPrivacy keeps it).
        val marked = update.indexOf("if (!current.shareRealtime && shareRealtime == true) circleRepository.resumedHere(circleId)")
        assertTrue(marked in 0 until update.indexOf("readPrivacy(circleId)"))
    }

    @Test
    fun `an end that doesn't read is no end, and an older server's rows still feed what this phone sees`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        val json = """{"circle_id":"c-1","sharing_mode":"exact","share_realtime":true,"share_history":true,"share_battery":true,""" +
            """"paused_until":null,"primary_device_id":null,"not_sharing_intervals":[{"start":"2026-01-01T00:00:00+00:00","end":"not a time"}]}"""

        h.repository.persistPrivacyLocally(Gson().fromJson(json, PrivacySettingsResponse::class.java))

        assertTrue(h.pauseIntervals.covers("c-1", now))
        // No field at all: an older server.
        val older = Gson().fromJson(
            """{"circle_id":"c-2","sharing_mode":"paused","share_realtime":true,"share_history":true,"share_battery":true,"paused_until":null,"primary_device_id":null}""",
            PrivacySettingsResponse::class.java,
        )
        h.repository.persistPrivacyLocally(older)
        assertTrue(h.pauseIntervals.covers("c-2", now))
    }
}
