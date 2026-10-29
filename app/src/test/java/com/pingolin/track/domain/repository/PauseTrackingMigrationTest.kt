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

import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.data.remote.dto.PauseSharingRequest
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.domain.repository.PauseTrackingMigration.Outcome
import com.pingolin.track.util.SessionManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * An install that had the device-wide "Pause Tracking" switch on is moved to each circle's own
 * pause, and never resumes in between.
 */
class PauseTrackingMigrationTest {

    private val sessionManager = mockk<SessionManager>().also {
        every { it.isLoggedIn() } returns true
        every { it.getUserId() } returns "me"
        every { it.claimTrackingPauseFor("me") } returns true
        every { it.clearTrackingPaused() } just Runs
        every { it.markPauseTrackingMovedNote(any()) } just Runs
    }
    private val circleRepository = mockk<CircleRepository>().also {
        coEvery { it.persistPrivacyLocally(any()) } just Runs
    }
    private val tracksApi = mockk<TrackApi>()
    private val stored = mutableMapOf<String, PrivacySettingEntity>()
    private val privacySettingDao = mockk<PrivacySettingDao>().also { dao ->
        coEvery { dao.get(any()) } answers { stored[firstArg()] }
    }
    private val migration = PauseTrackingMigration(sessionManager, circleRepository, tracksApi, privacySettingDao)

    private fun switchWasOn(on: Boolean) = every { sessionManager.isTrackingPaused() } returns on

    private fun settings(circleId: String, pausedUntil: String? = null) = PrivacySettingsResponse(
        circleId = circleId, sharingMode = "paused", shareRealtime = true, shareHistory = true,
        shareBattery = true, pausedUntil = pausedUntil, primaryDeviceId = null,
    )

    /** The server takes the pause, answers the GET with it, and this phone keeps what it said. */
    private fun pauseLands(circleId: String, pausedUntil: Long? = null) {
        coEvery { tracksApi.pauseSharing(circleId, any()) } returns
            Response.success(ApiResponse<PrivacySettingsResponse>(success = true, data = null, error = null))
        coEvery { tracksApi.getPrivacySettings(circleId) } returns
            Response.success(ApiResponse(success = true, data = settings(circleId), error = null))
        stored[circleId] = PrivacySettingEntity(
            circleId = circleId, sharingMode = PrivacySettingEntity.MODE_PAUSED, pausedUntil = pausedUntil, updatedAt = 0L,
        )
    }

    /** The circle list as the server pages it: [pages] in order, each next one behind a cursor. */
    private fun circlesListed(vararg pages: List<String>) {
        pages.forEachIndexed { i, ids ->
            val next = if (i + 1 < pages.size) "page-${i + 1}" else null
            coEvery { tracksApi.getCirclesPaginated(if (i == 0) null else "page-$i", any()) } returns Response.success(
                ApiResponse(
                    success = true,
                    data = PaginatedResponse(ids.map { id -> mockk<CircleResponse>().also { every { it.id } returns id } }, hasMore = next != null, cursor = next),
                    error = null,
                )
            )
        }
    }

    private fun pauseAnswers(circleId: String, code: Int) {
        coEvery { tracksApi.pauseSharing(circleId, any()) } returns Response.error(code, "{}".toResponseBody(null))
    }

    @Test
    fun `nothing happens for an install that never had the switch on`() = runTest {
        switchWasOn(false)

        assertEquals(Outcome.NOTHING_TO_MOVE, migration.runIfNeeded())

        coVerify(exactly = 0) { tracksApi.getCirclesPaginated(any(), any()) }
        coVerify(exactly = 0) { tracksApi.pauseSharing(any(), any()) }
        verify(exactly = 0) { sessionManager.clearTrackingPaused() }
        verify(exactly = 0) { sessionManager.markPauseTrackingMovedNote(any()) }
    }

    @Test
    fun `every circle is paused with no end and kept so here, then the switch goes and the note is due`() = runTest {
        switchWasOn(true)
        circlesListed(listOf("c-1", "c-2"))
        pauseLands("c-1")
        pauseLands("c-2")

        assertEquals(Outcome.MOVED, migration.runIfNeeded())

        coVerify(exactly = 1) { tracksApi.pauseSharing("c-1", PauseSharingRequest(resumeAt = null)) }
        coVerify(exactly = 1) { tracksApi.pauseSharing("c-2", PauseSharingRequest(resumeAt = null)) }
        coVerify(exactly = 1) { circleRepository.persistPrivacyLocally(settings("c-1")) }
        coVerify(exactly = 1) { circleRepository.persistPrivacyLocally(settings("c-2")) }
        verify(exactly = 1) { sessionManager.clearTrackingPaused() }
        verify(exactly = 1) { sessionManager.markPauseTrackingMovedNote(hadCircles = true) }
    }

    @Test
    fun `every page of the circle list is paused, not just the first`() = runTest {
        // No cap to lean on: paid accounts have unlimited circles.
        switchWasOn(true)
        circlesListed(listOf("c-1", "c-2"), listOf("c-3"))
        pauseLands("c-1")
        pauseLands("c-2")
        pauseLands("c-3")

        assertEquals(Outcome.MOVED, migration.runIfNeeded())

        coVerify(exactly = 1) { tracksApi.getCirclesPaginated(null, 100) }
        coVerify(exactly = 1) { tracksApi.getCirclesPaginated("page-1", 100) }
        coVerify(exactly = 1) { tracksApi.pauseSharing("c-3", PauseSharingRequest(resumeAt = null)) }
        verify(exactly = 1) { sessionManager.clearTrackingPaused() }
    }

    @Test
    fun `another account's switch moves nothing onto this one's circles`() = runTest {
        switchWasOn(true)
        every { sessionManager.claimTrackingPauseFor("me") } returns false

        assertEquals(Outcome.NOTHING_TO_MOVE, migration.runIfNeeded())

        coVerify(exactly = 0) { tracksApi.getCirclesPaginated(any(), any()) }
        coVerify(exactly = 0) { tracksApi.pauseSharing(any(), any()) }
    }

    @Test
    fun `a pause that fails keeps the switch, so nothing is sent until the next try`() = runTest {
        switchWasOn(true)
        circlesListed(listOf("c-1", "c-2", "c-3"))
        pauseLands("c-1")
        pauseAnswers("c-2", 500)
        coEvery { tracksApi.pauseSharing("c-3", any()) } throws java.io.IOException("offline")

        assertEquals(Outcome.NOT_YET, migration.runIfNeeded())

        coVerify(exactly = 1) { tracksApi.pauseSharing("c-1", any()) }
        verify(exactly = 0) { sessionManager.clearTrackingPaused() }
        verify(exactly = 0) { sessionManager.markPauseTrackingMovedNote(any()) }
    }

    @Test
    fun `offline, the circle list read throws - nothing paused, the switch kept, never a crash`() = runTest {
        switchWasOn(true)
        coEvery { tracksApi.getCirclesPaginated(any(), any()) } throws java.io.IOException("offline")

        assertEquals(Outcome.NOT_YET, migration.runIfNeeded())

        coVerify(exactly = 0) { tracksApi.pauseSharing(any(), any()) }
        verify(exactly = 0) { sessionManager.clearTrackingPaused() }
    }

    @Test
    fun `a page of the circle list that fails pauses nothing and keeps the switch`() = runTest {
        switchWasOn(true)
        circlesListed(listOf("c-1"), listOf("c-2"))
        coEvery { tracksApi.getCirclesPaginated("page-1", any()) } returns Response.error(500, "{}".toResponseBody(null))

        assertEquals(Outcome.NOT_YET, migration.runIfNeeded())

        coVerify(exactly = 0) { tracksApi.pauseSharing(any(), any()) }
        verify(exactly = 0) { sessionManager.clearTrackingPaused() }
    }

    @Test
    fun `a pause this phone doesn't hold yet, or one with an end, keeps the switch`() = runTest {
        switchWasOn(true)
        circlesListed(listOf("c-1", "c-2"))
        pauseLands("c-1", pausedUntil = 9_000L)
        pauseLands("c-2")
        stored.remove("c-2")

        assertEquals(Outcome.NOT_YET, migration.runIfNeeded())

        verify(exactly = 0) { sessionManager.clearTrackingPaused() }
    }

    @Test
    fun `a circle this account isn't in any more has nothing to pause`() = runTest {
        switchWasOn(true)
        circlesListed(listOf("c-1", "c-gone"))
        pauseLands("c-1")
        pauseAnswers("c-gone", 403)

        assertEquals(Outcome.MOVED, migration.runIfNeeded())

        verify(exactly = 1) { sessionManager.clearTrackingPaused() }
    }

    @Test
    fun `with no circles the switch goes, and the note says the person's own history records again`() = runTest {
        switchWasOn(true)
        circlesListed(emptyList())

        assertEquals(Outcome.MOVED, migration.runIfNeeded())

        verify(exactly = 1) { sessionManager.clearTrackingPaused() }
        verify(exactly = 1) { sessionManager.markPauseTrackingMovedNote(hadCircles = false) }
    }

    @Test
    fun `signed out, nothing is touched`() = runTest {
        switchWasOn(true)
        every { sessionManager.isLoggedIn() } returns false

        assertEquals(Outcome.NOT_YET, migration.runIfNeeded())

        coVerify(exactly = 0) { tracksApi.getCirclesPaginated(any(), any()) }
        verify(exactly = 0) { sessionManager.clearTrackingPaused() }
    }

    // ---- what's left of the switch, read from the source ----

    private fun src(path: String) = java.io.File("src/main/$path").readText()

    @Test
    fun `Settings has no Pause Tracking row`() {
        assertFalse(src("res/layout/fragment_settings.xml").contains("switchPauseTracking"))
        val settings = src("java/com/pingolin/track/ui/settings/SettingsFragment.kt")
        assertFalse(settings.contains("switchPauseTracking"))
        assertFalse(settings.contains("setupPauseTracking"))
    }

    @Test
    fun `nothing can turn the old switch on, and only the move clears it`() {
        val sources = java.io.File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.map { it.name to it.readText() }.toList()
        assertTrue(sources.size > 100)
        assertTrue(sources.none { it.second.contains("setTrackingPaused(") })
        assertTrue(sources.none { it.second.contains("putBoolean(KEY_TRACKING_PAUSED") })
        assertEquals(
            setOf("SessionManager.kt", "PauseTrackingMigration.kt"),
            sources.filter { it.second.contains("clearTrackingPaused(") }.map { it.first }.toSet(),
        )
    }

    @Test
    fun `the app moves it whenever it comes back, then says so once`() {
        val activity = src("java/com/pingolin/track/ui/main/MainActivity.kt")
        val onResume = activity.substring(activity.indexOf("override fun onResume()"))
        assertTrue(onResume.contains("pauseTrackingMigration.runIfNeeded()"))
        assertTrue(onResume.contains("sessionManager.takePauseTrackingMovedNote()"))
    }

    @Test
    fun `the ongoing notification's button opens the circles, where each pause lasts, not a Stop that doesn't`() {
        val service = src("java/com/pingolin/track/service/LocationTrackingService.kt")
        val build = service.substring(service.indexOf("private fun buildNotification("))
            .substringBefore("\n    private fun ")
        assertFalse(build, build.contains("ACTION_STOP"))
        assertFalse(build, build.contains("\"Stop\""))
        assertTrue(build, build.contains("putExtra(\"navigate_to\", \"circles\")"))
        assertTrue(build, build.contains("getString(R.string.notification_manage_sharing)"))
    }

    @Test
    fun `until it is moved the old switch holds wherever tracking starts or anything goes out`() {
        // The service can start (boot, an update, a push) before the app opens, so
        // every gate holds until the move clears the switch.
        val gates = mapOf(
            "main/java/com/pingolin/track/service/LocationTrackingService.kt" to 2, // start, sticky restart
            "main/java/com/pingolin/track/receiver/WatchdogAlarmReceiver.kt" to 1,
            "main/java/com/pingolin/track/worker/LocationHeartbeatWorker.kt" to 1,
            "main/java/com/pingolin/track/service/TrackUnifiedPushReceiver.kt" to 1, // wake_location
            "gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt" to 1, // wake_location
            "main/java/com/pingolin/track/worker/LocationSyncWorker.kt" to 1, // the backlog flush
            "main/java/com/pingolin/track/domain/repository/BoundaryRepository.kt" to 1, // crossing reports
            "main/java/com/pingolin/track/ui/map/MapFragment.kt" to 1, // the map's own send
            "main/java/com/pingolin/track/util/AlertHandler.kt" to 1, // member_joined
        )
        for ((path, atLeast) in gates) {
            val reads = java.io.File("src/$path").readText().split("isTrackingPaused()").size - 1
            assertTrue("$path reads the switch $reads time(s)", reads >= atLeast)
        }
        // And the one answer the watchdog and the health check share.
        assertTrue(src("java/com/pingolin/track/util/SessionManager.kt").contains("isLoggedIn() && isTrackingEnabled() && !isTrackingPaused()"))
    }

    @Test
    fun `every gate reads the switch through isTrackingPaused, which knows whose it is`() {
        // Parked while another account is signed in: the check of whose switch it is
        // lives in one place, so no gate can read the raw switch and hold the wrong
        // account.
        val apps = java.io.File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/test/" !in it.path && "/androidTest/" !in it.path }
            .map { it.name to it.readText() }.toList()
        assertTrue(apps.size > 100)
        assertEquals(
            setOf("SessionManager.kt"),
            apps.filter { "KEY_TRACKING_PAUSED" in it.second || "\"tracking_paused\"" in it.second }.map { it.first }.toSet(),
        )
        val sessionManager = src("java/com/pingolin/track/util/SessionManager.kt")
        val read = sessionManager.substring(sessionManager.indexOf("fun isTrackingPaused(")).substringBefore("\n\n")
        assertTrue(read, "KEY_TRACKING_PAUSED_ACCOUNT" in read && "getUserId()" in read)
    }

    @Test
    fun `the map and member_joined send nothing while the old switch still holds`() {
        val map = src("java/com/pingolin/track/ui/map/MapFragment.kt")
        val resume = map.substring(map.indexOf("private fun maybeRefreshOwnLocationOnResume("))
        assertTrue(resume.indexOf("if (sessionManager.isTrackingPaused()) return") in 0 until resume.indexOf("sendLocationNow("))
        // member_joined: AlertHandlerTest drives it both ways.
    }

    @Test
    fun `the note says the circles are paused, and that the person's own history records again`() {
        val strings = src("res/values/strings.xml")
        fun text(name: String) = Regex("<string name=\"$name\">([^<]*)</string>").find(strings)!!.groupValues[1]
        val moved = text("pause_tracking_moved")
        assertTrue(moved, moved.contains("Your circles are paused"))
        for (note in listOf(moved, text("pause_tracking_removed_no_circles"))) {
            assertTrue(note, note.contains("your own location history again; only you can see it."))
        }
    }
}
