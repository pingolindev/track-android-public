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

package com.pingolin.track.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.domain.repository.CurrentKeyGate
import com.pingolin.track.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.pingolin_mobile.CryptoManager

/**
 * While this user's SOS is active, the circle keeps seeing them even from a device that
 * is behind on its key (as on iPhone): their location goes out under the older key. The
 * exemption ends the moment the SOS is resolved or cancelled. Real in-memory Room, because
 * "active" is SosRepository's own `status = 'active'` and the behaviour under test is the SQL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class SosSharingExemptionTest {

    private lateinit var db: TrackDatabase
    private lateinit var gate: CurrentKeyGate

    private val cryptoManager = mockk<CryptoManager>().also {
        // This device holds v3; the circle is at v4 (inserted below): behind.
        every { it.getActiveVersion("circle-1") } returns 3
    }
    private val sessionManager = mockk<SessionManager>().also { every { it.getUserId() } returns "me" }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrackDatabase::class.java
        ).allowMainThreadQueries().build()
        gate = CurrentKeyGate(db.circleDao(), cryptoManager, db.sosAlertDao(), sessionManager)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun circleAtV4() = db.circleDao().insert(
        CircleEntity(id = "circle-1", name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0, keyVersion = 4)
    )

    private fun sos(id: String = "sos-1", userId: String = "me", circleId: String = "circle-1", status: String = "active") =
        SosAlertEntity(
            id = id,
            circleId = circleId,
            userId = userId,
            status = status,
            latitude = 51.5,
            longitude = -0.12,
            acknowledgedBy = null,
            createdAt = 1_000L,
            resolvedAt = null,
        )

    @Test
    fun `this user's active SOS keeps their location going, and resolving it ends that`() = runTest {
        circleAtV4()
        assertFalse("behind, no SOS: nothing goes out", gate.mayStreamLocationTo("circle-1"))

        db.sosAlertDao().insert(sos())
        assertTrue("SOS active: the circle keeps seeing them", gate.mayStreamLocationTo("circle-1"))
        // Only locations: every other write is still refused.
        assertFalse(gate.holdsCurrentKey("circle-1"))

        db.sosAlertDao().setStatus("sos-1", "resolved")
        assertFalse("resolved: gated again", gate.mayStreamLocationTo("circle-1"))
    }

    @Test
    fun `cancelling the SOS as a false alarm ends it too`() = runTest {
        circleAtV4()
        db.sosAlertDao().insert(sos())
        assertTrue(gate.mayStreamLocationTo("circle-1"))

        // What SosRepository.cancelSos stores from the server's answer.
        db.sosAlertDao().insert(sos(status = "false_alarm"))

        assertFalse(gate.mayStreamLocationTo("circle-1"))
    }

    @Test
    fun `only this user's SOS in this circle counts`() = runTest {
        circleAtV4()
        db.sosAlertDao().insert(sos(id = "someone-else", userId = "alice"))
        db.sosAlertDao().insert(sos(id = "other-circle", circleId = "circle-2"))

        assertEquals(0, db.sosAlertDao().countActiveRaisedBy("me", "circle-1"))
        assertFalse(gate.mayStreamLocationTo("circle-1"))
    }
}
