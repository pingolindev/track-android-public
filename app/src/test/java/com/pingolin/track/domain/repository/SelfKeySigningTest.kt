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
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.OwnWrapSignatureRequest
import com.pingolin.track.data.remote.dto.OwnWrapSignatureResponse
import com.pingolin.track.data.remote.dto.StoreSelfKeyRequest
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleCreationData
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.MemberKeyBundle
import uniffi.pingolin_mobile.MobileException

/**
 * The personal ("self") key is signed as our own and published before it is
 * installed.
 *
 * An unsigned "own" wrap would let a server install a personal key of its choosing on
 * a new device, and a key installed after a refused upload would diverge for good:
 * every later launch would stop at "key already held" and personal locations would be
 * sealed under a key no other device could obtain.
 */
class SelfKeySigningTest {

    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private lateinit var repository: LocationRepository

    private val nil = LocationRepository.SELF_CIRCLE_UUID
    private val creation = CircleCreationData(
        encryptedCircleKey = EncryptedData(nonce = "n0", ciphertext = "c0"),
        ephemeralPublicKey = "eph0",
        tempCircleId = "temp_1",
    )

    @Before
    fun setUp() {
        every { sessionManager.getUserId() } returns "me"
        every { cryptoManager.prepareCircleCreation() } returns creation
        every { cryptoManager.signCircleCreationWrap(creation, nil, "me") } returns "creation-sig"
        every { cryptoManager.publishablePersonalKey("temp_1", "me") } returns
            uniffi.pingolin_mobile.PublishablePersonalKey(EncryptedData("n0", "c0"), "eph0", 1, "publish-sig", "pseudo-0")
        repository = LocationRepository(
            locationProvider = mockk(relaxed = true),
            locationDao = mockk(relaxed = true),
            pendingLocationDao = mockk(relaxed = true),
            circleDao = mockk(relaxed = true),
            privacySettingDao = mockk(relaxed = true),
            tracksApi = tracksApi,
            authApi = mockk(relaxed = true),
            sessionManager = sessionManager,
            tokenRefresher = mockk(relaxed = true),
            cryptoManager = cryptoManager,
            uxEventBus = com.pingolin.track.util.UxEventBus(),
            queueMonitor = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            boundaryRepository = mockk(relaxed = true),
            placeAlertEvaluator = mockk(relaxed = true),
            currentKeyGate = CurrentKeyGate(mockk(relaxed = true), cryptoManager, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = com.pingolin.track.util.ApproximateMembersStore.inMemory(),
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    private fun noPersonalKeyAnywhere() {
        every { cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID) } returns false
        every { cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID) } returns false
        every { cryptoManager.hasCircleKey(nil) } returns false
        every { cryptoManager.hasCircleKeyInUse(nil) } returns false
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = null, error = null))
    }

    @Test
    fun `a new personal key is published signed, then installed`() = runTest {
        noPersonalKeyAnywhere()
        val sent = slot<StoreSelfKeyRequest>()
        coEvery { tracksApi.storeSelfKey(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = Unit, error = null))

        assertTrue(repository.ensurePersonalKeyExists())

        // The SDK's publishable copy: signed, with the pseudonym under
        // exactly this key for the server to record with the bytes.
        assertEquals("publish-sig", sent.captured.wrapSignature)
        assertEquals("pseudo-0", sent.captured.userPseudonym)
        assertEquals("n0", sent.captured.encryptedSelfKey.nonce)
        assertNull("a mint must stay refusable", sent.captured.replace)
        verify { cryptoManager.finalizeCircleCreation("temp_1", LocationRepository.SELF_CIRCLE_ID) }
        verify { cryptoManager.copyPersonalKey(LocationRepository.SELF_CIRCLE_ID, nil) }
    }

    /**
     * The published copy opens only through an identity from before a reset (the SDK stored
     * it retired). With no personal key in use here, a new one is published over it and
     * installed; nobody is asked.
     */
    @Test
    fun `a published copy only a pre-reset identity opens gets a new personal key over it`() = runTest {
        every { cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID) } returns false
        every { cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID) } returns false
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = CircleKeyBundleResponse(
            circleId = nil, encryptedCircleKey = EncryptedPayload("n-old", "c-old"), ephemeralPublicKey = "e-old", keyVersion = 1,
            wrapSignature = "old-sig",
        ), error = null))
        every { cryptoManager.personalCopySupersededByReset("n-oldc-old", "e-old") } returns true
        val sent = slot<StoreSelfKeyRequest>()
        coEvery { tracksApi.storeSelfKey(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = Unit, error = null))

        assertTrue(repository.ensurePersonalKeyExists())

        assertEquals("the one mint that replaces", true, sent.captured.replace)
        assertEquals("publish-sig", sent.captured.wrapSignature)
        verify { cryptoManager.finalizeCircleCreation("temp_1", LocationRepository.SELF_CIRCLE_ID) }
        verify { cryptoManager.copyPersonalKey(LocationRepository.SELF_CIRCLE_ID, nil) }
    }

    /**
     * A phone can hold the pre-reset key, retired, in the personal slot (stored there by an
     * earlier build): held (hasCircleKey) but not in use. The new key is still published over
     * the copy.
     */
    @Test
    fun `a personal slot holding only the retired pre-reset key still gets the new key`() = runTest {
        every { cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID) } returns true
        every { cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID) } returns false
        every { cryptoManager.getActiveVersion(LocationRepository.SELF_CIRCLE_ID) } throws MobileException.KeyNotFound("no key in use")
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = CircleKeyBundleResponse(
            circleId = nil, encryptedCircleKey = EncryptedPayload("n-old", "c-old"), ephemeralPublicKey = "e-old", keyVersion = 1,
            wrapSignature = "old-sig",
        ), error = null))
        every { cryptoManager.personalCopySupersededByReset("n-oldc-old", "e-old") } returns true
        val sent = slot<StoreSelfKeyRequest>()
        coEvery { tracksApi.storeSelfKey(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = Unit, error = null))

        assertTrue(repository.ensurePersonalKeyExists())

        assertEquals(true, sent.captured.replace)
        verify { cryptoManager.finalizeCircleCreation("temp_1", LocationRepository.SELF_CIRCLE_ID) }
    }

    @Test
    fun `a published copy that isn't superseded by a reset is never minted over`() = runTest {
        every { cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID) } returns false
        every { cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID) } returns false
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = CircleKeyBundleResponse(
            circleId = nil, encryptedCircleKey = EncryptedPayload("n-1", "c-1"), ephemeralPublicKey = "e-1", keyVersion = 1,
            wrapSignature = "sig",
        ), error = null))
        every { cryptoManager.personalCopySupersededByReset(any(), any()) } returns false

        repository.ensurePersonalKeyExists()

        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
        verify(exactly = 0) { cryptoManager.prepareCircleCreation() }
    }

    @Test
    fun `a personal key the server refused is never installed`() = runTest {
        noPersonalKeyAnywhere()
        coEvery { tracksApi.storeSelfKey(any()) } returns
            Response.error(409, """{"success":false,"error_code":"key_self_key_in_use"}""".toResponseBody(null))

        assertFalse(repository.ensurePersonalKeyExists())

        verify(exactly = 0) { cryptoManager.finalizeCircleCreation(any(), any()) }
        verify(exactly = 0) { cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.copyPersonalKey(any(), any()) }
    }

    @Test
    fun `a server copy is imported with its signature on the slot the signature binds`() = runTest {
        noPersonalKeyAnywhere()
        coEvery { tracksApi.getSelfKey() } returns Response.success(
            ApiResponse(
                success = true,
                data = CircleKeyBundleResponse(
                    circleId = nil,
                    encryptedCircleKey = EncryptedPayload(nonce = "n1", ciphertext = "c1"),
                    ephemeralPublicKey = "eph1",
                    keyVersion = 1,
                    senderUserId = "me",
                    wrapSignature = "server-sig",
                ),
                error = null,
            )
        )
        every { cryptoManager.encryptDataV2(any(), any(), any()) } returns EncryptedData("x", "y")
        every { cryptoManager.decryptDataV2(any(), any(), any()) } returns "test"

        repository.ensurePersonalKeyExists()

        // The signature binds the nil UUID, so it is verified there (under OUR key);
        // the legacy "self" slot gets the same key by the sdk's local copy.
        verify { cryptoManager.storeCircleKey(nil, "n1c1", "eph1", 1, "me", "me", "server-sig", null) }
        verify { cryptoManager.copyPersonalKey(nil, LocationRepository.SELF_CIRCLE_ID) }
    }

    @Test
    fun `the personal key is always verified as ours, whoever the server says signed it`() = runTest {
        noPersonalKeyAnywhere()
        coEvery { tracksApi.getSelfKey() } returns Response.success(
            ApiResponse(
                success = true,
                data = CircleKeyBundleResponse(
                    circleId = nil,
                    encryptedCircleKey = EncryptedPayload(nonce = "n3", ciphertext = "c3"),
                    ephemeralPublicKey = "eph3",
                    keyVersion = 1,
                    senderUserId = "mallory",
                    wrapSignature = "mallory-sig",
                ),
                error = null,
            )
        )

        repository.ensurePersonalKeyExists()

        // Labelled as a stranger's, an unpinned signer on first contact would pass;
        // labelled ours, the signature must verify under OUR key and cannot.
        verify { cryptoManager.storeCircleKey(nil, "n3c3", "eph3", 1, "me", "me", "mallory-sig", null) }
        verify(exactly = 0) { cryptoManager.storeCircleKey(any(), any(), any(), any(), "mallory", any(), any(), any()) }
    }

    private fun holdsPersonalKey(serverCopy: CircleKeyBundleResponse) {
        every { cryptoManager.hasCircleKey(any()) } returns true
        every { cryptoManager.hasCircleKeyInUse(any()) } returns true
        every { cryptoManager.encryptDataV2(any(), "keycheck", "key_check") } returns EncryptedData("x", "y")
        every { cryptoManager.decryptDataV2(any(), any(), "key_check") } returns "keycheck"
        coEvery { tracksApi.getSelfKey() } returns
            Response.success(ApiResponse(success = true, data = serverCopy, error = null))
    }

    private val unsignedServerCopy = CircleKeyBundleResponse(
        circleId = nil,
        encryptedCircleKey = EncryptedPayload(nonce = "n2", ciphertext = "c2"),
        ephemeralPublicKey = "eph2",
        keyVersion = 1,
        senderUserId = "me",
        wrapSignature = null,
    )

    @Test
    fun `an unsigned server copy of the key we hold is signed on exactly the bytes we read`() = runTest {
        holdsPersonalKey(unsignedServerCopy)
        // The holder's check: the copy opens to our key and carries no signature.
        every { cryptoManager.checkPublishedPersonalCopy("me", "n2c2", "eph2", 1, null) } returns
            uniffi.pingolin_mobile.PublishedCopyStatus.UNSIGNED
        every { cryptoManager.personalKeyTakenOnTrust() } returns false
        every { cryptoManager.signOwnCircleKeyWrap(nil, "me", 1, "n2c2", "eph2") } returns "our-sig"
        val sent = slot<OwnWrapSignatureRequest>()
        coEvery { tracksApi.signOwnWrap(nil, capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = OwnWrapSignatureResponse(signed = true), error = null))

        repository.ensurePersonalKeyExists()

        assertEquals("our-sig", sent.captured.wrapSignature)
        assertEquals(1, sent.captured.keyVersion)
        assertEquals("n2", sent.captured.encryptedCircleKey.nonce)
        assertEquals("c2", sent.captured.encryptedCircleKey.ciphertext)
        assertEquals("eph2", sent.captured.ephemeralPublicKey)
        // Never re-published: a POST of the bytes we read would replace a personal key
        // another device published since.
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    @Test
    fun `a server copy that is not our key is never signed`() = runTest {
        holdsPersonalKey(unsignedServerCopy)
        every { cryptoManager.checkPublishedPersonalCopy("me", "n2c2", "eph2", 1, null) } returns
            uniffi.pingolin_mobile.PublishedCopyStatus.OPENS_TO_DIFFERENT_KEY
        every { cryptoManager.personalKeyTakenOnTrust() } returns false
        every { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } throws
            MobileException.SignatureInvalid("does not open to the held key")

        repository.ensurePersonalKeyExists()

        coVerify(exactly = 0) { tracksApi.signOwnWrap(any(), any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    @Test
    fun `the nil-UUID slot is filled from the key held, never from the server's copy`() = runTest {
        every { cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID) } returns true
        every { cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID) } returns true
        every { cryptoManager.hasCircleKey(nil) } returns false
        every { cryptoManager.hasCircleKeyInUse(nil) } returns false
        every { cryptoManager.encryptDataV2(any(), "keycheck", "key_check") } returns EncryptedData("x", "y")
        every { cryptoManager.decryptDataV2(any(), any(), "key_check") } returns "keycheck"
        coEvery { tracksApi.getSelfKey() } returns Response.success(
            ApiResponse(success = true, data = unsignedServerCopy, error = null)
        )

        repository.ensurePersonalKeyExists()

        // The SDK's local slot copy of the key held, never the server's copy.
        verify { cryptoManager.copyPersonalKey(LocationRepository.SELF_CIRCLE_ID, nil) }
        verify(exactly = 0) { cryptoManager.storeCircleKey(nil, "n2c2", any(), any(), any(), any(), any(), any()) }
    }
}
