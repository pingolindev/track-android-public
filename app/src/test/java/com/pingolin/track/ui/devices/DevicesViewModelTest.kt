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

package com.pingolin.track.ui.devices

import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.RevocationStatusResponse
import com.pingolin.track.data.remote.dto.SessionInfo
import com.pingolin.track.data.remote.dto.SessionsListResponse
import com.pingolin.track.data.remote.dto.TrackingDevice
import com.pingolin.track.util.SessionManager
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Which devices the Manage Devices screen is willing to show.
 *
 * WHY THE JOIN MATTERS
 *   The screen is an intersection: the auth server's session list supplies
 *   the names, Track's /tracking-devices supplies "this one actually pushes
 *   location", and the two are joined on session_id. The server deliberately
 *   lists every push row that has a device id, active or not, so that "a
 *   phone that stops receiving notifications" still shows on the one screen
 *   where that question gets asked. But a push row with a NULL session_id
 *   joins to nothing, so a join on session_id alone silently re-applies a
 *   filter of the same shape — and such rows exist.
 *
 *   The symptom is indistinguishable from correctness. The list renders, the
 *   rows in it are right, and a device that is missing looks exactly like a
 *   device that was never registered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var authApi: AuthApi

    @MockK
    private lateinit var trackApi: TrackApi

    @MockK
    private lateinit var sessionManager: SessionManager

    /** Days since epoch, as `last_seen_day_bucket` is stored. */
    private val todayBucket = (System.currentTimeMillis() / 86_400_000L).toInt()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        every { sessionManager.getLastSuccessfulSendAt() } returns 0L
        coEvery { trackApi.getRevocationStatus() } returns
            Response.success(ApiResponse(true, RevocationStatusResponse(enforcing = true), null))
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun session(
        id: String,
        name: String?,
        isCurrent: Boolean = false,
        lastUsedAt: String = isoNow(),
    ) = SessionInfo(
        id = id,
        deviceName = name,
        ipAddress = null,
        lastUsedAt = lastUsedAt,
        createdAt = lastUsedAt,
        isCurrent = isCurrent,
    )

    private fun device(
        id: String,
        sessionId: String?,
        platform: String = "android",
        lastSeenDayBucket: Int? = todayBucket,
    ) = TrackingDevice(
        id = id,
        sessionId = sessionId,
        platform = platform,
        pushType = "fcm",
        lastSeenDayBucket = lastSeenDayBucket,
        createdDayBucket = todayBucket,
        isCurrent = false,
    )

    private fun isoNow(): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())

    private fun stub(sessions: List<SessionInfo>, devices: List<TrackingDevice>) {
        coEvery { authApi.listSessions() } returns Response.success(
            ApiResponse(
                true,
                SessionsListResponse(sessions, sessions.size, 50, 0),
                null,
            )
        )
        coEvery { trackApi.listTrackingDevices() } returns
            Response.success(ApiResponse(true, devices, null))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.items(): List<DeviceListItem> {
        val vm = DevicesViewModel(authApi, trackApi, sessionManager)
        advanceUntilIdle()
        return vm.state.value.items
    }

    @Test
    fun `a push device with no session is shown, not dropped`() = runTest(testDispatcher) {
        // The central case. This row is addressable — the server only
        // returns it because it has a device id — it simply has no auth
        // session behind it, and joining on session_id alone would make
        // it disappear.
        stub(
            sessions = listOf(session("s-1", "Pixel 8 on Android", isCurrent = true)),
            devices = listOf(device("d-1", sessionId = "s-1"), device("d-2", sessionId = null)),
        )

        val unlinked = items().filterIsInstance<DeviceListItem.Unlinked>()

        assertEquals("Expected the session-less device to be listed", 1, unlinked.size)
        assertEquals("d-2", unlinked.single().device.id)
    }

    @Test
    fun `a session-less device gets a readable name, never a blank or a uuid`() = runTest(testDispatcher) {
        // The friendly name lives on the auth session this row does not have, so
        // there is nothing user-supplied to fall back on. A raw device uuid, or an
        // empty label, would make the row worse than useless.
        stub(
            sessions = emptyList(),
            devices = listOf(
                device("d-android", sessionId = null, platform = "android"),
                device("d-ios", sessionId = null, platform = "ios"),
                device("d-web", sessionId = null, platform = "web"),
            ),
        )

        val byId = items().filterIsInstance<DeviceListItem.Unlinked>()
            .associate { it.device.id to it.name }

        assertEquals("Android device", byId["d-android"])
        assertEquals("iPhone or iPad", byId["d-ios"])
        assertEquals("Web browser", byId["d-web"])
        assertTrue(
            "No row may render its uuid",
            byId.none { (id, name) -> name.contains(id) },
        )
    }

    @Test
    fun `an unlinked row says why it has no sign-out`() = runTest(testDispatcher) {
        // The row deliberately carries no overflow menu: rename writes to a
        // session and sign-out revokes one, and there is no session here. A card
        // that just does nothing when tapped reads as a bug, so the subtitle says
        // what is actually true about it.
        stub(sessions = emptyList(), devices = listOf(device("d-1", sessionId = null)))

        val subtitle = items().filterIsInstance<DeviceListItem.Unlinked>().single().subtitle

        assertTrue("Subtitle was \"$subtitle\"", subtitle.contains("No linked sign-in"))
    }

    @Test
    fun `linked devices still resolve to their session row, not to a second unlinked one`() =
        runTest(testDispatcher) {
            // The join is still the join. A device WITH a session_id must render as
            // that session — with its user-visible name — and must not also appear
            // as an anonymous device.
            stub(
                sessions = listOf(session("s-1", "Pixel 8 on Android")),
                devices = listOf(device("d-1", sessionId = "s-1")),
            )

            val items = items()

            assertTrue(items.filterIsInstance<DeviceListItem.Unlinked>().isEmpty())
            assertEquals(
                "s-1",
                items.filterIsInstance<DeviceListItem.Other>().single().session.id,
            )
        }

    @Test
    fun `sessions with no push row are still filtered out`() = runTest(testDispatcher) {
        // The whole point of the intersection: web logins, Note sessions and admin
        // dashboards share the auth account and push no location, and this screen
        // is not the security audit that lists them. Widening it to unlinked push
        // rows must not widen it to unlinked SESSIONS.
        stub(
            sessions = listOf(session("s-1", "Firefox on Windows 10")),
            devices = emptyList(),
        )

        assertTrue(items().filterIsInstance<DeviceListItem.Other>().isEmpty())
    }

    @Test
    fun `an unlinked device sorts among the sessions by last activity, not into a bucket of its own`() =
        runTest(testDispatcher) {
            // A phone that reported an hour ago belongs beside the other devices
            // that reported an hour ago. Both buckets are keyed on the same clock:
            // last_seen_day_bucket for the device, last_used_at for the session.
            val longAgo = todayBucket - 30
            stub(
                sessions = listOf(session("s-old", "Old phone", lastUsedAt = "2026-01-01T00:00:00Z")),
                devices = listOf(
                    device("d-old", sessionId = "s-old", lastSeenDayBucket = longAgo),
                    device("d-fresh", sessionId = null, lastSeenDayBucket = todayBucket),
                ),
            )

            val items = items()
            val headers = items.filterIsInstance<DeviceListItem.SectionHeader>().map { it.title }
            assertTrue("Expected both sections, got $headers", headers.any { it == "Active" })
            assertTrue("Expected both sections, got $headers", headers.any { it.startsWith("Older") })

            // The fresh unlinked device is under "Active"; the stale session is under
            // "Older" and dimmed.
            val activeIdx = items.indexOfFirst {
                it is DeviceListItem.SectionHeader && it.title == "Active"
            }
            val olderIdx = items.indexOfFirst {
                it is DeviceListItem.SectionHeader && it.title.startsWith("Older")
            }
            val freshIdx = items.indexOfFirst {
                it is DeviceListItem.Unlinked && it.device.id == "d-fresh"
            }
            assertTrue("fresh=$freshIdx active=$activeIdx older=$olderIdx", freshIdx in (activeIdx + 1) until olderIdx)
        }

    @Test
    fun `an older unlinked row is dimmed like its neighbours`() = runTest(testDispatcher) {
        stub(
            sessions = emptyList(),
            devices = listOf(device("d-stale", sessionId = null, lastSeenDayBucket = todayBucket - 30)),
        )

        assertTrue(items().filterIsInstance<DeviceListItem.Unlinked>().single().dimmed)
    }

    @Test
    fun `sign out all never targets a row with no session to revoke`() = runTest(testDispatcher) {
        // revokeAllOthers maps items to session ids. An Unlinked row has none, so
        // it must not be among the targets — and the type system is what enforces
        // that, since the bulk revoke filters to DeviceListItem.Other.
        stub(
            sessions = emptyList(),
            devices = listOf(device("d-1", sessionId = null, lastSeenDayBucket = todayBucket - 30)),
        )

        val items = items()
        assertEquals(1, items.filterIsInstance<DeviceListItem.Unlinked>().size)
        assertTrue(items.filterIsInstance<DeviceListItem.Other>().isEmpty())
    }

    /**
     * ...and because it can never target them, it must not OFFER to.
     *
     * Where every row is a session-less push registration, "Sign out all"
     * beside "OLDER (2)" would do nothing visible: with no revocable row,
     * confirmBulkRevoke toasts and returns before building a dialog, and both
     * devices stay listed. An action with nothing to act on should not be on
     * screen.
     */
    @Test
    fun `the older section offers no bulk sign-out when every row is unlinked`() = runTest(testDispatcher) {
        stub(
            sessions = emptyList(),
            devices = listOf(
                device("d-1", sessionId = null, lastSeenDayBucket = todayBucket - 30),
                device("d-2", sessionId = null, lastSeenDayBucket = todayBucket - 40),
            ),
        )

        val header = items().filterIsInstance<DeviceListItem.SectionHeader>()
            .single { it.title.startsWith("Older") }
        // The count still describes the section, which really does hold two rows.
        assertEquals("Older (2)", header.title)
        assertEquals(null, header.actionLabel)
    }

    /**
     * The other half: one revocable row in the section is enough to earn the
     * button back, even sitting beside rows it cannot touch.
     */
    @Test
    fun `one revocable row is enough to offer the bulk sign-out`() = runTest(testDispatcher) {
        stub(
            // Far enough back to land in "Older", the same stamp the sorting test
            // above uses.
            sessions = listOf(session("s-old", "Old phone", lastUsedAt = "2026-01-01T00:00:00Z")),
            devices = listOf(
                device("d-old", sessionId = "s-old", lastSeenDayBucket = todayBucket - 30),
                device("d-unlinked", sessionId = null, lastSeenDayBucket = todayBucket - 40),
            ),
        )

        val header = items().filterIsInstance<DeviceListItem.SectionHeader>()
            .single { it.title.startsWith("Older") }
        assertEquals("Older (2)", header.title)
        assertEquals("Sign out all", header.actionLabel)
    }

    /** No "Retry" to press; the list loads again by itself. */
    @Test
    fun `a list that fails to load loads again by itself`() = runTest(testDispatcher) {
        var calls = 0
        coEvery { authApi.listSessions() } answers {
            calls++
            if (calls == 1) throw java.io.IOException("offline")
            Response.success(ApiResponse(true, SessionsListResponse(listOf(session("s-1", "Pixel 8")), 1, 50, 0), null))
        }
        coEvery { trackApi.listTrackingDevices() } returns Response.success(ApiResponse(true, listOf(device("d-1", "s-1")), null))

        val vm = DevicesViewModel(authApi, trackApi, sessionManager)
        vm.reloadPausesMs = listOf(1_000L)
        runCurrent()
        assertEquals("Can't load your devices right now. Trying again…", vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(null, vm.state.value.error)
        assertEquals(1, vm.state.value.items.filterIsInstance<DeviceListItem.Other>().size)
    }

    @Test
    fun `a list that keeps failing keeps trying, further apart each time`() = runTest(testDispatcher) {
        var calls = 0
        coEvery { authApi.listSessions() } answers { calls++; throw java.io.IOException("offline") }
        coEvery { trackApi.listTrackingDevices() } returns Response.success(ApiResponse(true, emptyList(), null))

        val vm = DevicesViewModel(authApi, trackApi, sessionManager)
        vm.reloadPausesMs = listOf(1_000L, 5_000L)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_001L); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(4_000L); runCurrent()
        assertEquals("the second pause is the longer one", 2, calls)
        advanceTimeBy(1_001L); runCurrent()
        assertEquals(3, calls)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `a tracking-devices outage still shows the unfiltered session list`() = runTest(testDispatcher) {
        // A 404 from an older backend, or a network blip, passes null and the
        // screen falls back to every session rather than going blank.
        coEvery { authApi.listSessions() } returns Response.success(
            ApiResponse(true, SessionsListResponse(listOf(session("s-1", "Pixel 8")), 1, 50, 0), null)
        )
        coEvery { trackApi.listTrackingDevices() } returns
            Response.error(404, "".toResponseBody(null))

        val items = items()

        assertEquals(1, items.filterIsInstance<DeviceListItem.Other>().size)
        assertTrue(items.filterIsInstance<DeviceListItem.Unlinked>().isEmpty())
    }
}
