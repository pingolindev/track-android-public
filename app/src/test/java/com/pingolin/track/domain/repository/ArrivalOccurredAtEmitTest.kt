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

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.ReportArrivalRequest
import com.pingolin.track.data.remote.dto.ReportArrivalResponse
import com.pingolin.track.service.EncryptedOccurredAt
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * That an arrival carries WHEN it happened, sealed to the circle.
 *
 * WHAT IT CARRIES
 *   `ReportArrivalRequest` carries `encrypted_occurred_at`, as every producer
 *   does: the server accepts it, iOS sends it, Android's own boundary path
 *   sends it (BoundaryRepository.reportCrossing), and the consumer reads it
 *   (EncryptedOccurredAt, wired into NotificationTickleFetcher).
 *
 * WHY IT IS NOT A COSMETIC FEW SECONDS
 *   Without it, the only time a subscriber sees is the notification's creation
 *   time on the server — the moment the server was TOLD. And the auto arrival
 *   path retries: LocationTrackingService un-flags a destination whose report
 *   failed so a later tick re-sends it. So an arrival detected while the phone
 *   was offline would be announced to the circle with the RECONNECT time, which
 *   is exactly the case a sealed occurred-at exists for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalOccurredAtEmitTest {

    private lateinit var tracksApi: TrackApi
    private lateinit var cryptoManager: CryptoManager
    private lateinit var repo: DestinationRepository

    private val sent = slot<ReportArrivalRequest>()

    private companion object {
        const val CIRCLE = "circle-family"
        const val DEST = "dest-1"
        const val ME = "a1b2c3d4-0000-4000-8000-00000000000e"
    }

    @Before
    fun setup() {
        tracksApi = mockk(relaxed = true)
        cryptoManager = mockk()

        // Seal each field to a ciphertext that names its own AAD tag, so a test can
        // tell WHICH field a payload came from. The tag is load-bearing: it is
        // AAD-bound in the sdk, so a mismatch fails to decrypt outright rather than
        // decrypting to something wrong, and it is silent because this path is
        // best-effort.
        every { cryptoManager.encryptDataV2(any(), any(), any()) } answers {
            EncryptedData(nonce = "nonce:${thirdArg<String>()}", ciphertext = "ct:${secondArg<String>()}")
        }
        every { cryptoManager.computeEventTypeHash(any(), any()) } returns "h".repeat(64)

        coEvery {
            tracksApi.reportDestinationArrival(any(), capture(sent))
        } returns Response.success(
            ApiResponse(true, ReportArrivalResponse(DEST, "event-1", 1), null)
        )

        repo = DestinationRepository(
            tracksApi = tracksApi,
            // This account's own destination (the sealed id and the server's creator both
            // name it), which is all an arrival is ever reported for.
            destinationDao = mockk<com.pingolin.track.data.local.dao.DestinationDao>(relaxed = true).also {
                coEvery { it.getById(DEST) } returns com.pingolin.track.data.local.entity.DestinationEntity(
                    id = DEST, userId = ME, circleId = CIRCLE, destName = null, destLatitude = 0.0, destLongitude = 0.0,
                    etaDayBucket = null, startedDayBucket = 0, createdDayBucket = 0, updatedDayBucket = 0, creatorId = ME,
                )
            },
            circleMemberDao = mockk(relaxed = true),
            placeDao = mockk(relaxed = true),
            sessionManager = mockk<SessionManager>(relaxed = true).also {
                every { it.getUserId() } returns ME
            },
            cryptoManager = cryptoManager,
            privacySettingDao = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
        )
    }

    @Test
    fun `an arrival report carries the sealed occurred-at`() = runTest {
        // The field this file pins: it must be on the request.
        repo.reportArrival(DEST, CIRCLE, occurredAtMs = 1_785_398_745_123L)

        assertNotNull(
            "encrypted_occurred_at missing — the subscriber falls back to the server's " +
                "own created_at, which on a retried report is the reconnect time",
            sent.captured.encryptedOccurredAt,
        )
    }

    @Test
    fun `the time is sealed under the occurred_at field tag every other client uses`() = runTest {
        // AAD-bound across three clients and two directions: BoundaryRepository and
        // iOS seal with it, EncryptedOccurredAt.OCCURRED_AT_FIELD_TAG opens with it.
        // A mismatch does not decrypt to a wrong value, it fails — silently, because
        // the read side is best-effort and keeps the server stamp on failure.
        repo.reportArrival(DEST, CIRCLE, occurredAtMs = 1_785_398_745_123L)

        assertEquals(
            "nonce:${EncryptedOccurredAt.OCCURRED_AT_FIELD_TAG}",
            sent.captured.encryptedOccurredAt!!.nonce,
        )
    }

    @Test
    fun `the plaintext is epoch millis as a bare decimal string`() = runTest {
        // What the consumer parses: `plain.trim().toLongOrNull()` in
        // EncryptedOccurredAt, then Instant.ofEpochMilli. An ISO-8601 string here
        // would parse to null, and the stamp would silently revert to the server's.
        val ms = 1_785_398_745_123L
        repo.reportArrival(DEST, CIRCLE, occurredAtMs = ms)

        val plaintext = sent.captured.encryptedOccurredAt!!.ciphertext.removePrefix("ct:")
        assertTrue("was \"$plaintext\"", plaintext.matches(Regex("-?\\d+")))
        assertEquals(ms, plaintext.toLong())
    }

    @Test
    fun `a caller that names no time is stamped now`() = runTest {
        // The manual "I've arrived" button, where now IS the arrival.
        val before = System.currentTimeMillis()
        repo.reportArrival(DEST, CIRCLE)
        val after = System.currentTimeMillis()

        val stamped = sent.captured.encryptedOccurredAt!!.ciphertext.removePrefix("ct:").toLong()
        assertTrue("$before <= $stamped <= $after", stamped in before..after)
    }

    @Test
    fun `a failure to seal the time does not cost the user the arrival`() = runTest {
        // Best-effort, matching reportCrossing: a missing circle key, a rotated one,
        // anything. A slightly-late stamp beats an arrival the circle never hears
        // about — and this path also flips the destination's status, so dropping it
        // would strand the row.
        every { cryptoManager.encryptDataV2(CIRCLE, any(), "occurred_at") } throws
            RuntimeException("no circle key")

        val result = repo.reportArrival(DEST, CIRCLE, occurredAtMs = 1L)

        assertTrue("the arrival itself must still be reported", result.isSuccess)
        assertNull(sent.captured.encryptedOccurredAt)
        // And the rest of the envelope is untouched — the other three fields are
        // required, so a throw that took them out would be a 400.
        assertNotNull(sent.captured.encryptedUserId)
        assertNotNull(sent.captured.encryptedEventType)
        assertEquals(64, sent.captured.eventTypeHash.length)
    }
}
