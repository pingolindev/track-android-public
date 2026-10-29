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

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.OwnWrapSignatureRequest
import com.pingolin.track.data.remote.dto.OwnWrapSignatureResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleCreationData
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * Our own wraps get signed.
 *
 * A device that accepts "ours" unsigned lets the server install a key of its
 * choosing, and creation wraps and legacy self-labelled slots can sit on the server
 * unsigned. The creator's wrap is signed as soon as the circle id exists, and on sync
 * an unsigned copy of a key this device already holds is signed and uploaded.
 */
class OwnWrapSigningTest {

    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val circleDao = mockk<CircleDao>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private lateinit var repository: CircleRepository

    private val signed = slot<OwnWrapSignatureRequest>()

    @Before
    fun setUp() {
        every { sessionManager.getUserId() } returns "me"
        coEvery { tracksApi.signOwnWrap(any(), capture(signed)) } returns
            Response.success(ApiResponse(success = true, data = OwnWrapSignatureResponse(signed = true), error = null))
        repository = CircleRepository(
            context = mockk(relaxed = true),
            tracksApi = tracksApi,
            circleDao = circleDao,
            circleSortOrderDao = mockk(relaxed = true),
            circleMemberDao = mockk(relaxed = true),
            pendingInviteDao = mockk(relaxed = true),
            privacySettingDao = mockk(relaxed = true),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            // Typed: a relaxed mock of the generic Lazy hands back an untyped object,
            // and a background call that uses it after the test would throw on it —
            // surfacing as the NEXT test's failure.
            locationRepository = dagger.Lazy<LocationRepository> { mockk(relaxed = true) },
            previousIdentityLoader = mockk(relaxed = true),
            uxEventBus = UxEventBus(),
            currentKeyGate = CurrentKeyGate(circleDao, cryptoManager, mockk(relaxed = true), mockk(relaxed = true)),
            keyStoreRecovery = mockk(relaxed = true),
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
            sosRepository = dagger.Lazy<SosRepository> { mockk(relaxed = true) },
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    private fun bundle(circleId: String, sender: String?, signature: String?) = CircleKeyBundleResponse(
        circleId = circleId,
        encryptedCircleKey = EncryptedPayload(nonce = "n-$circleId", ciphertext = "c-$circleId"),
        ephemeralPublicKey = "e-$circleId",
        keyVersion = 2,
        senderUserId = sender,
        wrapSignature = signature,
    )

    private fun serverHasKeys(vararg bundles: CircleKeyBundleResponse) {
        coEvery { tracksApi.getCircleKeys() } returns
            Response.success(ApiResponse(success = true, data = bundles.toList(), error = null))
    }

    @Test
    fun `an unsigned copy of our own wrap is signed and uploaded unchanged`() = runTest {
        serverHasKeys(bundle("c-own", sender = "me", signature = null))
        every { cryptoManager.signOwnCircleKeyWrap("c-own", "me", 2, "n-c-ownc-c-own", "e-c-own") } returns "own-sig"

        repository.importCircleKeys()

        coVerify { tracksApi.signOwnWrap("c-own", any()) }
        assertEquals(2, signed.captured.keyVersion)
        assertEquals("n-c-own", signed.captured.encryptedCircleKey.nonce)
        assertEquals("c-c-own", signed.captured.encryptedCircleKey.ciphertext)
        assertEquals("e-c-own", signed.captured.ephemeralPublicKey)
        assertEquals("own-sig", signed.captured.wrapSignature)
    }

    @Test
    fun `a wrap with no signer on record is imported as ours, and signed`() = runTest {
        serverHasKeys(bundle("c-legacy", sender = null, signature = null))
        every { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } returns "legacy-sig"

        repository.importCircleKeys()

        verify { cryptoManager.storeCircleKey("c-legacy", any(), any(), 2, "me", "me", null, null) }
        coVerify { tracksApi.signOwnWrap("c-legacy", any()) }
    }

    @Test
    fun `signed wraps and other members' wraps are left alone`() = runTest {
        serverHasKeys(
            bundle("c-signed", sender = "me", signature = "a".repeat(128)),
            bundle("c-bob", sender = "bob", signature = null),
        )

        repository.importCircleKeys()

        verify(exactly = 0) { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.signOwnWrap(any(), any()) }
    }

    @Test
    fun `a wrap that does not open to the key we hold is never uploaded as ours`() = runTest {
        serverHasKeys(bundle("c-own", sender = "me", signature = null))
        every { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } throws
            uniffi.pingolin_mobile.MobileException.SignatureInvalid("not the held key")

        repository.importCircleKeys()

        coVerify(exactly = 0) { tracksApi.signOwnWrap(any(), any()) }
    }

    /** A key taken on trust at first contact is never signed as ours (the SDK refuses it too). */
    @Test
    fun `an own wrap of a key taken on trust is left unsigned, quietly`() = runTest {
        serverHasKeys(bundle("c-own", sender = "me", signature = null))
        every { cryptoManager.circleKeyTakenOnTrust("c-own", 2) } returns true
        every { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } returns "own-sig"

        repository.importCircleKeys()

        verify(exactly = 0) { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.signOwnWrap(any(), any()) }
    }

    @Test
    fun `each own wrap is signed once per process`() = runTest {
        serverHasKeys(bundle("c-own", sender = "me", signature = null))
        every { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } returns "own-sig"

        repository.importCircleKeys()
        repository.importCircleKeys()

        coVerify(exactly = 1) { tracksApi.signOwnWrap("c-own", any()) }
    }

    @Test
    fun `a new circle's creator wrap is signed with the real circle id`() = runTest {
        // Made under the id the server reserved, so its "temp" id is the real one.
        val creation = CircleCreationData(
            encryptedCircleKey = EncryptedData(nonce = "cn", ciphertext = "cc"),
            ephemeralPublicKey = "ce",
            tempCircleId = "c-new",
        )
        coEvery { tracksApi.reserveCircleId() } returns Response.success(
            ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.ReservedCircleIdResponse("c-new"), error = null)
        )
        every { cryptoManager.prepareRecordedCircleCreation("me", "c-new") } returns uniffi.pingolin_mobile.RecordedCircleCreation(
            "c-new", creation, uniffi.pingolin_mobile.SignedRefreshRecord("genesis", "genesis-sig"),
        )
        every { cryptoManager.encryptDataV2(any(), any(), any()) } returns EncryptedData("nn", "nc")
        every { cryptoManager.signCircleCreationWrap(creation, "c-new", "me") } returns "creator-sig"
        coEvery { circleDao.insert(any()) } just Runs
        val created = io.mockk.slot<com.pingolin.track.data.remote.dto.CreateCircleRequest>()
        coEvery { tracksApi.createCircle(capture(created)) } returns Response.success(
            ApiResponse(
                success = true,
                data = CircleResponse(
                    id = "c-new",
                    encryptedName = EncryptedPayload("nn", "nc"),
                    encryptedKey = null,
                    ephemeralPublicKey = null,
                    memberCount = 1,
                    isOwner = true,
                    dayBucket = 20000,
                    keyRotationNeeded = false,
                    keyVersion = 1,
                ),
                error = null,
            )
        )

        repository.createCircle("Family", "v2")

        verify(exactly = 0) { cryptoManager.finalizeCircleCreation(any(), any()) }
        verify { cryptoManager.signCircleCreationWrap(creation, "c-new", "me") }
        coVerify { tracksApi.signOwnWrap("c-new", any()) }
        assertEquals(1, signed.captured.keyVersion)
        assertEquals("cn", signed.captured.encryptedCircleKey.nonce)
        assertEquals("creator-sig", signed.captured.wrapSignature)
        // Creating is agreeing; the agreement goes in the
        // creation.
        assertEquals("v2", created.captured.locationConsent?.clientNoticeVersion)
        assertEquals("android", created.captured.locationConsent?.surface)
    }
}
