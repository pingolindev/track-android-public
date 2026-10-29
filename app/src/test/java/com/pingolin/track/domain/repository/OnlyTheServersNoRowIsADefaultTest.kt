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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.TrackDatabase
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
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
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * A 404 from GET /privacy/circles/{id} is read as the server's default posture, exact and sharing,
 * only when it is the server's own "no row yet" (the error "Privacy settings not found for this
 * circle", in its envelope). Any other 404, such as a proxy answering while the server restarts,
 * would otherwise seed EXACT, both as the send gate's prime and as the background read, which would
 * then write it over a bubble already stored. Since a forgotten circle takes its row with it, every
 * rejoin's first send reaches this read.
 *
 * Any other 404 is no answer about the posture: the send gate holds back, and the background read
 * stores nothing. The privacy screen's own read is CirclesViewModelTest's.
 *
 * Real Room behind the real [CircleRepository]; the server is a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class OnlyTheServersNoRowIsADefaultTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"

        /** What else answers a 404: an ingress for a pod mid-restart, nothing at all, another route's not-found. */
        val NOT_THE_SERVERS = listOf("404 page not found", "", """{"success":false,"error":"Not found"}""")
    }

    private lateinit var db: TrackDatabase
    private lateinit var circles: CircleRepository

    private val tracksApi: TrackApi = mockk(relaxed = true)
    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    @Before
    fun setup() {
        every { sessionManager.getUserId() } returns ME
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        circles = CircleRepository(
            previousIdentityLoader = mockk(relaxed = true),
            context = mockk(relaxed = true),
            tracksApi = tracksApi,
            circleDao = db.circleDao(),
            circleSortOrderDao = db.circleSortOrderDao(),
            circleMemberDao = db.circleMemberDao(),
            pendingInviteDao = db.pendingInviteDao(),
            privacySettingDao = db.privacySettingDao(),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            locationRepository = dagger.Lazy<LocationRepository> { mockk(relaxed = true) },
            uxEventBus = UxEventBus(),
            currentKeyGate = CurrentKeyGate(db.circleDao(), cryptoManager, mockk(relaxed = true), sessionManager),
            keyStoreRecovery = mockk(relaxed = true),
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
            sosRepository = dagger.Lazy<SosRepository> { mockk(relaxed = true) },
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun heldHere() {
        db.circleDao().insert(CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0))
    }

    private fun answer(body: String): Response<ApiResponse<PrivacySettingsResponse>> = Response.error(404, body.toResponseBody(null))

    /** The server's own "no row yet", word for word as the server sends it in its error envelope. */
    private fun noRowYet() = answer("""{"success":false,"error":"Privacy settings not found for this circle"}""")

    @Test
    fun `the server's own no-row answer is its default, exact and sharing, for the send gate and the background read`() = runTest {
        heldHere()
        coEvery { tracksApi.getPrivacySettings(CIRCLE) } answers { noRowYet() }

        assertTrue(circles.ensurePrivacyPrimed(CIRCLE))
        assertEquals("exact", db.privacySettingDao().get(CIRCLE)?.sharingMode)
        assertEquals(true, db.privacySettingDao().get(CIRCLE)?.shareRealtime)

        db.privacySettingDao().delete(CIRCLE)
        circles.refreshPrivacy(CIRCLE)
        assertEquals("exact", db.privacySettingDao().get(CIRCLE)?.sharingMode)
    }

    @Test
    fun `any other 404 is no answer, so the send gate holds back`() = runTest {
        heldHere()
        for (body in NOT_THE_SERVERS) {
            coEvery { tracksApi.getPrivacySettings(CIRCLE) } answers { answer(body) }

            assertFalse("'$body'", circles.ensurePrivacyPrimed(CIRCLE))
            assertNull("'$body': no posture made up", db.privacySettingDao().get(CIRCLE))
        }
    }

    @Test
    fun `any other 404 stores nothing in the background read, and never writes over a bubble stored`() = runTest {
        heldHere()
        for (body in NOT_THE_SERVERS) {
            coEvery { tracksApi.getPrivacySettings(CIRCLE) } answers { answer(body) }

            circles.refreshPrivacy(CIRCLE)
            assertNull("'$body': nothing stored", db.privacySettingDao().get(CIRCLE))

            db.privacySettingDao().insert(
                PrivacySettingEntity(circleId = CIRCLE, sharingMode = "approximate", approximateRadiusMiles = 3, updatedAt = 0)
            )
            circles.refreshPrivacy(CIRCLE)
            assertEquals("'$body': the bubble stays", "approximate", db.privacySettingDao().get(CIRCLE)?.sharingMode)
            db.privacySettingDao().delete(CIRCLE)
        }
    }
}
