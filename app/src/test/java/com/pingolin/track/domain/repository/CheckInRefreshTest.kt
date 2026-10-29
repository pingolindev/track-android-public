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

import com.pingolin.track.data.local.dao.CheckInDao
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CheckInResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * A check-in's sealed time is required: one that doesn't open is tampering or a key not held,
 * never "no time", and the server's day bucket would date the row at its will. The row is
 * dropped, as one whose user id doesn't open is.
 */
class CheckInRefreshTest {

    private companion object {
        const val USER = "a11ce000-0000-4000-8000-00000000a11c"
        const val SENT_AT = 1_700_000_000_000L
    }

    private fun checkIn(id: String, sealedTime: String) = CheckInResponse(
        id = id,
        circleId = "c-1",
        placeId = null,
        encryptedLocation = null,
        encryptedMessage = null,
        checkInType = "general",
        encryptedUserId = EncryptedPayload("n", USER),
        encryptedDeviceId = EncryptedPayload("n", "dev-a"),
        dayBucket = 20_000,
        encryptedTimestamp = EncryptedPayload("n", sealedTime),
    )

    @Test
    fun `a check-in whose sealed time doesn't open is not listed, and the others still are`() = runTest {
        val api = mockk<TrackApi>()
        val dao = mockk<CheckInDao>(relaxed = true)
        val crypto = mockk<CryptoManager>()
        // A field opens to its own ciphertext here, except "doesn't open".
        every { crypto.decryptDataV2(any(), any(), any()) } answers {
            arg<EncryptedData>(1).ciphertext.also { if (it == "doesn't open") throw IllegalStateException("no key") }
        }
        coEvery { api.getCircleCheckIns("c-1", any()) } returns Response.success(ApiResponse(
            success = true,
            data = listOf(checkIn("ci-1", SENT_AT.toString()), checkIn("ci-2", "doesn't open")),
            error = null,
        ))
        val repository = CheckInRepository(
            tracksApi = api,
            checkInDao = dao,
            sessionManager = mockk(relaxed = true),
            cryptoManager = crypto,
            privacySettingDao = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            pendingChanges = mockk(relaxed = true),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )

        val listed = repository.refreshCheckIns("c-1").getOrThrow()

        assertEquals(listOf("ci-1"), listed.map { it.id })
        assertEquals("its own sealed time", SENT_AT, listed.single().timestamp)
        coVerify(exactly = 1) { dao.insert(any<CheckInEntity>()) }
    }
}
