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
import com.pingolin.track.data.remote.dto.SelfKeyUnreadableResponse
import com.pingolin.track.data.remote.dto.StoreSelfKeyRequest
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.PersonalKeyVerdict
import uniffi.pingolin_mobile.PublishablePersonalKey

/**
 * Personal history repairs itself. When neither self-key slot holds the key the
 * account's record names, the phone takes the published copy only through the SDK's
 * adoption (which runs its own checks and keeps the replaced key, retired); a copy it
 * can't use is reported once so the phone holding the recorded key puts it right; a
 * pseudonym mismatch alone is never reported; a slot heal never publishes over the
 * server's copy; and only the SDK's signed copy is ever published.
 */
class PersonalKeyAdoptionTest {

    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private lateinit var repository: LocationRepository

    private val nil = LocationRepository.SELF_CIRCLE_UUID
    private val self = LocationRepository.SELF_CIRCLE_ID
    private val served = CircleKeyBundleResponse(
        circleId = nil,
        encryptedCircleKey = EncryptedPayload(nonce = "n9", ciphertext = "c9"),
        ephemeralPublicKey = "eph9",
        keyVersion = 1,
        wrapSignature = "sig9",
        userPseudonym = "recorded",
    )

    /** SessionManager's record of the copy last reported as unusable (kept across restarts). */
    private var unusableCopy: String? = null

    @Before
    fun setUp() {
        every { sessionManager.getUserId() } returns "me"
        every { sessionManager.getUnusableSelfKeyCopy() } answers { unusableCopy }
        every { sessionManager.setUnusableSelfKeyCopy(any()) } answers { unusableCopy = firstArg() }
        every { cryptoManager.hasCircleKey(self) } returns true
        every { cryptoManager.hasCircleKeyInUse(self) } returns true
        every { cryptoManager.hasCircleKey(nil) } returns true
        every { cryptoManager.hasCircleKeyInUse(nil) } returns true
        every { cryptoManager.encryptDataV2(any(), "keycheck", "key_check") } returns EncryptedData("x", "y")
        every { cryptoManager.decryptDataV2(any(), any(), "key_check") } returns "keycheck"
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = served, error = null))
        coEvery { tracksApi.reportSelfKeyUnreadable(any()) } returns
            Response.success(ApiResponse(success = true, data = SelfKeyUnreadableResponse(true), error = null))
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

    private fun slots(selfSlot: String, nilSlot: String) {
        every { cryptoManager.deriveUserPseudonym(self, "me") } returns selfSlot
        every { cryptoManager.deriveUserPseudonym(nil, "me") } returns nilSlot
    }

    private fun sdkSays(verdict: PersonalKeyVerdict) {
        every { cryptoManager.adoptPersonalKey("me", "n9c9", "eph9", 1, "sig9", "recorded") } returns verdict
    }

    private suspend fun check() {
        repository.lastSelfKeySlotCheckMs = 0L
        repository.ensurePersonalKeyExists()
    }

    @Test
    fun `neither slot holds the recorded key - the sdk adopts the published copy and self follows`() = runTest {
        slots("mine", "mine")
        sdkSays(PersonalKeyVerdict.ADOPT)

        check()

        verify(exactly = 1) { cryptoManager.adoptPersonalKey("me", "n9c9", "eph9", 1, "sig9", "recorded") }
        verify { cryptoManager.copyPersonalKey(nil, self) }
        // Never taken any other way.
        verify(exactly = 0) { cryptoManager.storeCircleKey(any(), "n9c9", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.reportSelfKeyUnreadable(any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    @Test
    fun `a copy this phone cannot open is reported, once per copy`() = runTest {
        slots("mine", "mine")
        sdkSays(PersonalKeyVerdict.CANNOT_OPEN)

        check()
        check()

        coVerify(exactly = 1) { tracksApi.reportSelfKeyUnreadable(any()) }
        verify(exactly = 0) { cryptoManager.copyPersonalKey(any(), any()) }
    }

    @Test
    fun `reported once per copy across restarts, again for a different copy, and again after one opened`() = runTest {
        slots("mine", "mine")
        sdkSays(PersonalKeyVerdict.CANNOT_OPEN)
        check()
        // A fresh process: the record is SessionManager's, not memory's.
        setUp()
        slots("mine", "mine")
        sdkSays(PersonalKeyVerdict.CANNOT_OPEN)
        check()
        coVerify(exactly = 1) { tracksApi.reportSelfKeyUnreadable(any()) }

        val other = served.copy(encryptedCircleKey = EncryptedPayload(nonce = "n8", ciphertext = "c8"))
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = other, error = null))
        every { cryptoManager.adoptPersonalKey("me", "n8c8", "eph9", 1, "sig9", "recorded") } returns PersonalKeyVerdict.CANNOT_OPEN
        check()
        coVerify(exactly = 2) { tracksApi.reportSelfKeyUnreadable(any()) }

        every { cryptoManager.adoptPersonalKey("me", "n8c8", "eph9", 1, "sig9", "recorded") } returns PersonalKeyVerdict.ADOPT
        check()
        assertEquals("an adopted copy clears the record", null, unusableCopy)
    }

    private fun firstContact(fails: Exception) {
        every { cryptoManager.hasCircleKey(self) } returns false
        every { cryptoManager.hasCircleKeyInUse(self) } returns false
        every { cryptoManager.hasCircleKey(nil) } returns false
        every { cryptoManager.hasCircleKeyInUse(nil) } returns false
        every { cryptoManager.storeCircleKey(nil, "n9c9", "eph9", 1, "me", "me", "sig9", null) } throws fails
    }

    @Test
    fun `a phone holding no personal key that can't open the served copy reports it, once, and mints nothing`() = runTest {
        firstContact(MobileException.DecryptionFailed("does not open"))

        assertEquals(false, repository.ensurePersonalKeyExists())
        assertEquals(false, repository.ensurePersonalKeyExists())

        coVerify(exactly = 1) { tracksApi.reportSelfKeyUnreadable(any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
        verify(exactly = 0) { cryptoManager.prepareCircleCreation() }
    }

    @Test
    fun `a served copy that is not this account's own is refused at first contact, never reported`() = runTest {
        firstContact(MobileException.SignatureInvalid("not ours"))

        assertEquals(false, repository.ensurePersonalKeyExists())

        coVerify(exactly = 0) { tracksApi.reportSelfKeyUnreadable(any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    @Test
    fun `a copy that opens at first contact clears the record of one that didn't`() = runTest {
        unusableCopy = "me:earlier"
        every { cryptoManager.hasCircleKey(self) } returns false
        every { cryptoManager.hasCircleKeyInUse(self) } returns false
        every { cryptoManager.hasCircleKey(nil) } returns false
        every { cryptoManager.hasCircleKeyInUse(nil) } returns false
        every { cryptoManager.encryptDataV2(self, "test", "key_check") } returns EncryptedData("x", "y")
        every { cryptoManager.decryptDataV2(self, any(), "key_check") } returns "test"

        assertEquals(true, repository.ensurePersonalKeyExists())

        verify { cryptoManager.storeCircleKey(nil, "n9c9", "eph9", 1, "me", "me", "sig9", null) }
        assertEquals(null, unusableCopy)
        coVerify(exactly = 0) { tracksApi.reportSelfKeyUnreadable(any()) }
    }

    @Test
    fun `the report names the copy by its digest, and a stale one is reported again once re-read`() = runTest {
        val hexCopy = served.copy(
            encryptedCircleKey = EncryptedPayload(nonce = "aa".repeat(24), ciphertext = "bb".repeat(48)),
            ephemeralPublicKey = "cc".repeat(32),
        )
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = hexCopy, error = null))
        every { cryptoManager.adoptPersonalKey("me", "aa".repeat(24) + "bb".repeat(48), "cc".repeat(32), 1, "sig9", "recorded") } returns
            PersonalKeyVerdict.CANNOT_OPEN
        slots("mine", "mine")
        val sent = mutableListOf<com.pingolin.track.data.remote.dto.SelfKeyUnreadableRequest>()
        coEvery { tracksApi.reportSelfKeyUnreadable(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = SelfKeyUnreadableResponse(requested = false, copyChanged = true), error = null))

        check()
        check()

        // sha256 over the RAW bytes ciphertext ‖ nonce ‖ ephemeral key.
        val expected = java.security.MessageDigest.getInstance("SHA-256").run {
            update(ByteArray(48) { 0xbb.toByte() }); update(ByteArray(24) { 0xaa.toByte() }); update(ByteArray(32) { 0xcc.toByte() })
            digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected, sent.first().copySha256)
        assertEquals("a stale report is not recorded, so it goes again", 2, sent.size)
        assertEquals(null, unusableCopy)
    }

    private fun holderSeesOpens() {
        every { cryptoManager.checkPublishedPersonalCopy("me", "n9c9", "eph9", 1, "sig9") } returns
            uniffi.pingolin_mobile.PublishedCopyStatus.OPENS
        every { cryptoManager.personalKeyTakenOnTrust() } returns false
    }

    @Test
    fun `a record that names another key, while the copy is ours, is repaired by compare-and-set - never republished`() = runTest {
        slots("mine", "mine")
        sdkSays(PersonalKeyVerdict.PSEUDONYM_MISMATCH)
        holderSeesOpens()
        val sent = slot<com.pingolin.track.data.remote.dto.RecordSelfPseudonymRequest>()
        coEvery { tracksApi.recordSelfPseudonym(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.RecordSelfPseudonymResponse(true), error = null))

        check()

        assertEquals("mine", sent.captured.userPseudonym)
        assertEquals("the copy as served", "n9", sent.captured.encryptedCircleKey.nonce)
        assertEquals("c9", sent.captured.encryptedCircleKey.ciphertext)
        assertEquals("eph9", sent.captured.ephemeralPublicKey)
        assertEquals(1, sent.captured.keyVersion)
        coVerify(exactly = 0) { tracksApi.reportSelfKeyUnreadable(any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }

        // A copy this device can't open, on a mismatch: it holds no recorded key, so nothing.
        every { cryptoManager.checkPublishedPersonalCopy("me", "n9c9", "eph9", 1, "sig9") } returns
            uniffi.pingolin_mobile.PublishedCopyStatus.CANNOT_OPEN
        check()
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
        coVerify(exactly = 1) { tracksApi.recordSelfPseudonym(any()) }
    }

    @Test
    fun `nothing recorded - the record is pointed at the published copy when it is ours, and only then`() = runTest {
        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = served.copy(userPseudonym = null), error = null))
        slots("mine", "mine")
        holderSeesOpens()
        coEvery { tracksApi.recordSelfPseudonym(any()) } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.RecordSelfPseudonymResponse(true), error = null))

        check()
        coVerify(exactly = 1) { tracksApi.recordSelfPseudonym(match { it.userPseudonym == "mine" }) }

        every { cryptoManager.checkPublishedPersonalCopy("me", "n9c9", "eph9", 1, "sig9") } returns
            uniffi.pingolin_mobile.PublishedCopyStatus.OPENS_TO_DIFFERENT_KEY
        check()
        coVerify(exactly = 1) { tracksApi.recordSelfPseudonym(any()) }
    }

    @Test
    fun `the holder of the recorded key never rewrites the record`() = runTest {
        slots("recorded", "recorded")
        holderSeesOpens()

        check()

        coVerify(exactly = 0) { tracksApi.recordSelfPseudonym(any()) }
    }

    @Test
    fun `an unsigned copy is reported too`() = runTest {
        slots("mine", "mine")
        sdkSays(PersonalKeyVerdict.UNSIGNED)

        check()

        coVerify(exactly = 1) { tracksApi.reportSelfKeyUnreadable(any()) }
    }

    @Test
    fun `a pseudonym mismatch or a foreign signature is logged, never reported`() = runTest {
        slots("mine", "mine")
        for (verdict in listOf(PersonalKeyVerdict.PSEUDONYM_MISMATCH, PersonalKeyVerdict.SIGNATURE_INVALID, PersonalKeyVerdict.TOO_SOON, PersonalKeyVerdict.NO_SIGNING_KEY)) {
            sdkSays(verdict)
            check()
        }

        coVerify(exactly = 0) { tracksApi.reportSelfKeyUnreadable(any()) }
        verify(exactly = 0) { cryptoManager.copyPersonalKey(any(), any()) }
    }

    @Test
    fun `a slot heal never publishes over the server's copy`() = runTest {
        slots("recorded", "stale")

        check()

        verify { cryptoManager.copyPersonalKey(self, nil) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
        verify(exactly = 0) { cryptoManager.adoptPersonalKey(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `only the sdk's signed copy is published, with its pseudonym`() = runTest {
        every { cryptoManager.publishablePersonalKey(self, "me") } returns
            PublishablePersonalKey(EncryptedData("pn", "pc"), "peph", 1, "psig", "ppseudo")
        val sent = slot<StoreSelfKeyRequest>()
        coEvery { tracksApi.storeSelfKey(capture(sent)) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))

        assertTrue(repository.republishSelfKey().isSuccess)

        assertEquals("psig", sent.captured.wrapSignature)
        assertEquals("ppseudo", sent.captured.userPseudonym)
        assertEquals(true, sent.captured.replace)
        verify(exactly = 0) { cryptoManager.wrapCircleKeyForMemberFull(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a key taken on trust is never published`() = runTest {
        every { cryptoManager.publishablePersonalKey(self, "me") } throws MobileException.InvalidInput("taken on trust")

        assertTrue(repository.republishSelfKey().isFailure)

        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    private fun holderSees(status: uniffi.pingolin_mobile.PublishedCopyStatus, takenOnTrust: Boolean = false) {
        every { cryptoManager.checkPublishedPersonalCopy("me", "n9c9", "eph9", 1, "sig9") } returns status
        every { cryptoManager.personalKeyTakenOnTrust() } returns takenOnTrust
    }

    @Test
    fun `the holder leaves a published copy that opens alone`() = runTest {
        slots("recorded", "recorded")
        holderSees(uniffi.pingolin_mobile.PublishedCopyStatus.OPENS)

        check()

        coVerify(exactly = 0) { tracksApi.signOwnWrap(any(), any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    @Test
    fun `the holder republishes only a copy nobody can open, signed`() = runTest {
        slots("recorded", "recorded")
        holderSees(uniffi.pingolin_mobile.PublishedCopyStatus.CANNOT_OPEN)
        every { cryptoManager.publishablePersonalKey(self, "me") } returns
            PublishablePersonalKey(EncryptedData("pn", "pc"), "peph", 1, "psig", "ppseudo")
        val sent = slot<StoreSelfKeyRequest>()
        coEvery { tracksApi.storeSelfKey(capture(sent)) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))

        check()

        assertEquals("psig", sent.captured.wrapSignature)
        assertEquals("ppseudo", sent.captured.userPseudonym)
    }

    @Test
    fun `a copy of another genuine key is never published over`() = runTest {
        slots("recorded", "recorded")
        holderSees(uniffi.pingolin_mobile.PublishedCopyStatus.OPENS_TO_DIFFERENT_KEY)

        check()

        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
        coVerify(exactly = 0) { tracksApi.signOwnWrap(any(), any()) }
    }

    @Test
    fun `a key taken on trust is neither signed nor republished by its holder`() = runTest {
        slots("recorded", "recorded")
        for (status in listOf(uniffi.pingolin_mobile.PublishedCopyStatus.UNSIGNED, uniffi.pingolin_mobile.PublishedCopyStatus.CANNOT_OPEN)) {
            holderSees(status, takenOnTrust = true)
            check()
        }

        verify(exactly = 0) { cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.storeSelfKey(any()) }
    }

    @Test
    fun `a wake asking for the copy runs the check at once, and only then`() = runTest {
        slots("recorded", "recorded")
        holderSees(uniffi.pingolin_mobile.PublishedCopyStatus.OPENS)
        repository.ensurePersonalKeyExists() // the hourly check has just run
        verify(exactly = 1) { cryptoManager.checkPublishedPersonalCopy(any(), any(), any(), any(), any()) }

        repository.onSilentWake() // served copy without a request: nothing
        verify(exactly = 1) { cryptoManager.checkPublishedPersonalCopy(any(), any(), any(), any(), any()) }

        coEvery { tracksApi.getSelfKey() } returns Response.success(ApiResponse(success = true, data = served.copy(republishRequested = true), error = null))
        repository.onSilentWake()
        verify(exactly = 2) { cryptoManager.checkPublishedPersonalCopy(any(), any(), any(), any(), any()) }
    }
}
