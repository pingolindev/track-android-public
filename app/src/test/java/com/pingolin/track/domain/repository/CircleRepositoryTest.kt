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
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.CircleSortOrderDao
import com.pingolin.track.data.local.dao.PendingInviteDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.AccessRequestResponse
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * Unit tests for CircleRepository focusing on:
 * - importCircleKeys: silently skip incomplete bundles
 * - importCircleKeys: nil-UUID maps to "self"
 * - importCircleKeys: count of successfully imported
 * - autoRotateIfNeeded: backoff on recent failure
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleRepositoryTest {

    @MockK
    private lateinit var context: android.content.Context

    @MockK
    private lateinit var tracksApi: TrackApi

    @MockK
    private lateinit var circleDao: CircleDao

    @MockK
    private lateinit var circleSortOrderDao: CircleSortOrderDao

    @MockK
    private lateinit var circleMemberDao: CircleMemberDao

    @MockK
    private lateinit var pendingInviteDao: PendingInviteDao

    @MockK
    private lateinit var privacySettingDao: PrivacySettingDao

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var cryptoManager: CryptoManager

    // Relaxed: importCircleKeys asks it to load first, and a strict mock would
    // throw straight into that method's own catch, turning every success-path
    // assertion into a silent failure.
    @MockK(relaxed = true)
    private lateinit var previousIdentityLoader: PreviousIdentityLoader

    @MockK
    private lateinit var locationRepositoryLazy: dagger.Lazy<LocationRepository>

    private lateinit var repository: CircleRepository

    /** Changes waiting to sync: nothing waits, and what the repository asks of it is seen. */
    private val pendingChanges = com.pingolin.track.testutil.quietPendingChanges()

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { sessionManager.getUserId() } returns "user-1"

        // importCircleKeys brackets the whole bulk import in
        // beginBulkKeyImport/endBulkKeyImport, which flushes the encrypted
        // key-persist writes once instead of per bundle. These are strict MockK
        // mocks, so an unstubbed call THROWS — and the throw lands in
        // importCircleKeys' own catch, which turns every success-path assertion
        // into `result.isSuccess == false`. Miss a stub here and five tests in
        // this class fail together for a reason none of them mention.
        every { cryptoManager.beginBulkKeyImport() } just Runs
        every { cryptoManager.endBulkKeyImport() } just Runs
        // Behind by its records: not here; RecordsBehindTest has that.
        every { cryptoManager.isBehind(any()) } returns false

        repository = repositoryWith(mockk(relaxed = true))
    }

    private fun repositoryWith(keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery) = CircleRepository(
        context = context,
        tracksApi = tracksApi,
        circleDao = circleDao,
        circleSortOrderDao = circleSortOrderDao,
        circleMemberDao = circleMemberDao,
        pendingInviteDao = pendingInviteDao,
        privacySettingDao = privacySettingDao,
        sessionManager = sessionManager,
        cryptoManager = cryptoManager,
        locationRepository = locationRepositoryLazy,
        previousIdentityLoader = previousIdentityLoader,
        uxEventBus = UxEventBus(),
        currentKeyGate = CurrentKeyGate(circleDao, cryptoManager, mockk(relaxed = true), sessionManager),
        keyStoreRecovery = keyStoreRecovery,
        pendingChanges = pendingChanges,
        sosRepository = dagger.Lazy<SosRepository> { mockk(relaxed = true) },
        pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
        approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
    )

    @Test
    fun `a refresh reads a store the platform couldn't read again, and one that doesn't decode ends the session there`() = runTest {
        val recovery = mockk<com.pingolin.track.util.KeyStoreRecovery>(relaxed = true)
        every { recovery.retryIfReadFailed() } returns com.pingolin.track.util.KeyStoreRecovery.Verdict.SIGN_OUT

        val result = repositoryWith(recovery).refreshCircles()

        assertTrue(result.isFailure)
        verify { recovery.signOut(sessionManager) }
        coVerify(exactly = 0) { tracksApi.getCircles() }
    }

    /**
     * Leaving a circle, or stopping sharing, which leaves it: the changes waiting to sync for it
     * go with its other local state, with no line (they chose to leave).
     */
    @Test
    fun `leaving a circle takes its changes waiting to sync with it`() = runTest {
        coEvery { circleDao.deleteById("c-1") } just Runs
        coEvery { circleMemberDao.deleteByCircle("c-1") } just Runs
        every { locationRepositoryLazy.get() } returns mockk(relaxed = true)
        every { cryptoManager.forgetCircle("c-1") } returns true

        repository.forgetCircleHere("c-1")

        coVerify(exactly = 1) { pendingChanges.forgetCircle("c-1") }
        // Its keys, its records and the circle's secret go too.
        verify(exactly = 1) { cryptoManager.forgetCircle("c-1") }
    }

    @Test
    fun `a deleted circle's keys go once the server took the delete, and only then`() = runTest {
        coEvery { circleDao.deleteById("c-1") } just Runs
        coEvery { circleMemberDao.deleteByCircle("c-1") } just Runs
        every { locationRepositoryLazy.get() } returns mockk(relaxed = true)
        every { cryptoManager.forgetCircle("c-1") } returns true
        coEvery { tracksApi.deleteCircle("c-1") } returns Response.error(503, "{}".toResponseBody(null))

        assertTrue(repository.deleteCircle("c-1").isFailure)
        verify(exactly = 0) { cryptoManager.forgetCircle(any()) }

        coEvery { tracksApi.deleteCircle("c-1") } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        assertTrue(repository.deleteCircle("c-1").isSuccess)
        verify(exactly = 1) { cryptoManager.forgetCircle("c-1") }
    }

    @Test
    fun `a circle only a 404 or 403 says is gone keeps its keys`() = runTest {
        // forgetCircle only on the server-confirmed own leave, stop or delete.
        coEvery { circleDao.deleteById("c-1") } just Runs
        coEvery { circleMemberDao.deleteByCircle("c-1") } just Runs
        every { locationRepositoryLazy.get() } returns mockk(relaxed = true)

        repository.deleteLocalCircle("c-1")

        verify(exactly = 0) { cryptoManager.forgetCircle(any()) }
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `importCircleKeys skips bundles with blank ephemeral public key`() = runTest {
        val bundle = CircleKeyBundleResponse(
            circleId = "c-1",
            encryptedCircleKey = EncryptedPayload(nonce = "n", ciphertext = "c"),
            ephemeralPublicKey = "",  // blank → skip
            keyVersion = 1
        )
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = listOf(bundle), error = null)
        )

        val result = repository.importCircleKeys()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        verify(exactly = 0) {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        }
    }

    @Test
    fun `importCircleKeys skips bundles with blank nonce`() = runTest {
        val bundle = CircleKeyBundleResponse(
            circleId = "c-1",
            encryptedCircleKey = EncryptedPayload(nonce = "", ciphertext = "c"),
            ephemeralPublicKey = "epk",
            keyVersion = 1
        )
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = listOf(bundle), error = null)
        )

        val result = repository.importCircleKeys()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `importCircleKeys imports valid bundle`() = runTest {
        val bundle = CircleKeyBundleResponse(
            circleId = "c-1",
            encryptedCircleKey = EncryptedPayload(nonce = "noncehex", ciphertext = "cipherhex"),
            ephemeralPublicKey = "epk-hex",
            keyVersion = 3
        )
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = listOf(bundle), error = null)
        )
        every { cryptoManager.storeCircleKey(eq("c-1"), eq("noncehexcipherhex"), eq("epk-hex"), eq(3), any(), any(), anyNullable(), anyNullable()) } just Runs

        val result = repository.importCircleKeys()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        verify {
            cryptoManager.storeCircleKey(eq("c-1"), eq("noncehexcipherhex"), eq("epk-hex"), eq(3), any(), any(), anyNullable(), anyNullable())
        }
    }

    /**
     * The self key is NOT imported here, deliberately.
     *
     * The self key lives in TWO slots ("self" and the nil UUID) that different
     * readers use: PlaceRepository unwraps personal place keys via "self", while
     * sendEnvelopeLocation encrypts via the nil UUID. Writing only "self" here,
     * from the server, on every sync, while the nil slot is topped up once, would
     * let the two hold DIFFERENT KEYS: personal places undecryptable while
     * locations keep flowing under the other key, each reader using a different
     * half of a split identity.
     *
     * LocationRepository.ensurePersonalKeyExists owns both slots and checks
     * them against the pseudonym the server has recorded.
     */
    @Test
    fun `importCircleKeys leaves the self key to its single owner`() = runTest {
        val bundle = CircleKeyBundleResponse(
            circleId = "00000000-0000-0000-0000-000000000000",
            encryptedCircleKey = EncryptedPayload(nonce = "n", ciphertext = "c"),
            ephemeralPublicKey = "epk",
            keyVersion = 1
        )
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = listOf(bundle), error = null)
        )

        val result = repository.importCircleKeys()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        // Neither slot: this path must not touch the self key at all.
        verify(exactly = 0) {
            cryptoManager.storeCircleKey(eq("self"), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        }
        verify(exactly = 0) {
            cryptoManager.storeCircleKey(
                eq("00000000-0000-0000-0000-000000000000"),
                any(), any(), any(), any(), any(), anyNullable(), anyNullable()
            )
        }
    }

    @Test
    fun `importCircleKeys continues despite one bundle failure`() = runTest {
        val good = CircleKeyBundleResponse(
            circleId = "c-good",
            encryptedCircleKey = EncryptedPayload(nonce = "n1", ciphertext = "c1"),
            ephemeralPublicKey = "epk1",
            keyVersion = 1
        )
        val bad = CircleKeyBundleResponse(
            circleId = "c-bad",
            encryptedCircleKey = EncryptedPayload(nonce = "n2", ciphertext = "c2"),
            ephemeralPublicKey = "epk2",
            keyVersion = 1
        )
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = listOf(good, bad), error = null)
        )
        every { cryptoManager.storeCircleKey(eq("c-good"), any(), any(), any(), any(), any(), anyNullable(), anyNullable()) } just Runs
        every { cryptoManager.storeCircleKey(eq("c-bad"), any(), any(), any(), any(), any(), anyNullable(), anyNullable()) } throws RuntimeException("stale")

        val result = repository.importCircleKeys()
        assertTrue(result.isSuccess)
        // The bad bundle threw → skipped; only good was counted.
        assertEquals(1, result.getOrNull())
    }

    @Test
    fun `importCircleKeys returns failure when api returns error`() = runTest {
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse<List<CircleKeyBundleResponse>>(success = false, data = null, error = "denied")
        )

        val result = repository.importCircleKeys()
        assertTrue(result.isFailure)
    }

    @Test
    fun `importCircleKeys returns failure on api exception`() = runTest {
        coEvery { tracksApi.getCircleKeys() } throws java.io.IOException("network")

        val result = repository.importCircleKeys()
        assertTrue(result.isFailure)
    }

    /**
     * A recovered pre-reset identity must be in the SDK BEFORE anything unwraps a
     * circle key. After an email password reset every wrap was made for the OLD
     * keypair, so unwrapping first means they all fail and the circle renders with
     * no name — the exact failure PreviousIdentityLoader exists to prevent, and one
     * that looks identical to "the key never arrived".
     *
     * refreshCircles gets this right by calling importCircleKeys() — which awaits
     * ensureLoaded() — before mapping the circle list. But decryptCircleName ALSO
     * calls storeCircleKey directly on the wrap that rides along with each circle,
     * and it is a plain `private fun` running inside a per-circle map on
     * Dispatchers.Default, so it cannot await the suspend loader itself. Call
     * ORDER is therefore the only thing holding this together, and nothing else
     * checks it.
     *
     * This pins it: move the mapping above importCircleKeys and this fails here
     * rather than silently on someone's phone.
     */
    @Test
    fun `refreshCircles loads previous identities before unwrapping any circle key`() = runTest {
        // No key bundles, so the ONLY storeCircleKey call in this test comes from
        // decryptCircleName's inline wrap — the call site under test.
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = emptyList(), error = null)
        )
        val dto = CircleResponse(
            id = "c-1",
            encryptedName = EncryptedPayload(nonce = "nn", ciphertext = "cc"),
            encryptedKey = EncryptedPayload(nonce = "kn", ciphertext = "kc"),
            ephemeralPublicKey = "epk",
            memberCount = 1,
            isOwner = true,
            dayBucket = 20000,
            keyRotationNeeded = false,
            keyVersion = 1
        )
        coEvery { tracksApi.getCircles() } returns Response.success(
            ApiResponse(
                success = true,
                data = PaginatedResponse(items = listOf(dto), hasMore = false),
                error = null
            )
        )
        every {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        } just Runs
        // Name resolution is not what this test is about; false keeps it off the
        // decrypt path and out of "Circle (key unavailable)"'s way.
        every { cryptoManager.hasCircleKey(any()) } returns false
        coEvery { circleDao.insertAll(any()) } just Runs
        // A circle this device has never held: the refresh imports keys for it,
        // which is the path whose ordering is pinned here.
        coEvery { circleDao.getAll() } returns emptyList()

        val result = repository.refreshCircles()
        assertTrue(result.isSuccess)

        coVerifyOrder {
            previousIdentityLoader.ensureLoaded()
            cryptoManager.storeCircleKey(
                eq("c-1"), any(), any(), any(), any(), any(), anyNullable(), anyNullable()
            )
        }
    }

    @Test
    fun `autoRotateIfNeeded returns early when circle not found`() = runTest {
        coEvery { circleDao.getById("c-1") } returns null

        // Should not throw or call rotation API
        repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 0) { tracksApi.getMemberPublicKeys(any()) }
    }

    @Test
    fun `autoRotateIfNeeded returns early when keyRotationNeeded is false`() = runTest {
        val circle = CircleEntity(
            id = "c-1", name = "Test", encryptedKey = null,
            createdAt = 0, updatedAt = 0, isOwner = true,
            keyRotationNeeded = false, keyVersion = 1
        )
        coEvery { circleDao.getById("c-1") } returns circle

        repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 0) { tracksApi.getMemberPublicKeys(any()) }
    }

    // =============================================================================
    // BACKGROUND REFRESH ECONOMY
    // =============================================================================

    private fun circleDto(id: String = "c-1", keyVersion: Int = 1) = CircleResponse(
        id = id,
        encryptedName = EncryptedPayload(nonce = "nn", ciphertext = "cc"),
        encryptedKey = EncryptedPayload(nonce = "kn", ciphertext = "kc"),
        ephemeralPublicKey = "epk",
        memberCount = 2,
        isOwner = false,
        dayBucket = 20000,
        keyRotationNeeded = false,
        keyVersion = keyVersion
    )

    /** The row this device holds for [circleDto] when nothing has changed. */
    private fun storedRow(id: String = "c-1", keyVersion: Int = 1, name: String = "Family") = CircleEntity(
        id = id,
        name = name,
        encryptedKey = "kn:kc",
        createdAt = 20000L * 86400 * 1000L,
        updatedAt = 1_000L,
        isOwner = false,
        keyRotationNeeded = false,
        keyVersion = keyVersion
    )

    private class MemoryLedger : PseudonymBackfillLedger {
        val digests = mutableMapOf<String, String>()
        override fun digestFor(circleId: String) = digests[circleId]
        override fun record(circleId: String, digest: String) { digests[circleId] = digest }
        override fun clear() = digests.clear()
    }

    /**
     * Steady state: the server lists the circle this device already holds, at the
     * key version it holds, and the held key opens the name. [heldVersion] is what
     * the SDK reports as active, and as the newest held (a legacy circle: the same).
     */
    private fun stubSteadyState(dto: CircleResponse, row: CircleEntity, heldVersion: Int = row.keyVersion) {
        coEvery { circleDao.getAll() } returns listOf(row)
        coEvery { tracksApi.getCircles() } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = listOf(dto), hasMore = false), error = null)
        )
        every { cryptoManager.hasCircleKey(any()) } returns true
        every { cryptoManager.getActiveVersion(any()) } returns heldVersion
        every { cryptoManager.newestHeldVersion(any()) } returns heldVersion
        every { cryptoManager.decryptDataV2(any(), any(), "name") } returns "Family"
        every { cryptoManager.deriveUserPseudonymsAllVersions(any(), any()) } returns listOf("p1")
        coEvery { circleDao.insertAll(any()) } just Runs
    }

    @Test
    fun `circles past the first page are kept too, but only a first page with no more makes a whole list`() = runTest {
        repository.backfillLedger = MemoryLedger().also { ledger ->
            for (id in listOf("c-1", "c-2")) ledger.record(id, CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        stubSteadyState(circleDto("c-1"), storedRow("c-1"))
        coEvery { circleDao.getAll() } returns listOf(storedRow("c-1"), storedRow("c-2"))
        coEvery { tracksApi.getCircles() } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = listOf(circleDto("c-1")), hasMore = true, cursor = "page-2"), error = null)
        )
        coEvery { tracksApi.getCirclesPaginated("page-2", 100) } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = listOf(circleDto("c-2"), circleDto("c-1")), hasMore = false), error = null)
        )

        val listed = repository.refreshCircles().getOrThrow()

        assertEquals("every page, each circle once", listOf("c-1", "c-2"), listed.map { it.id })
        // The server orders its first page by the member's own sort position and a cursor page
        // by day bucket: pages after the first can skip a circle, so they never say "whole".
        assertEquals(null, repository.refreshCirclesWhole())
    }

    @Test
    fun `a circle missing from a list with more pages is never 'no longer a member'`() = runTest {
        repository.backfillLedger = MemoryLedger().also { ledger ->
            ledger.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        stubSteadyState(circleDto("c-1"), storedRow("c-1"))
        coEvery { tracksApi.getCircles() } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = listOf(circleDto("c-1")), hasMore = true, cursor = "page-2"), error = null)
        )
        // The next page can't be had just now.
        coEvery { tracksApi.getCirclesPaginated("page-2", 100) } returns Response.error(503, "{}".toResponseBody(null))

        assertEquals(true, repository.isStillAMember("c-1"))
        assertEquals("couldn't tell", null, repository.isStillAMember("c-9"))

        coEvery { tracksApi.getCircles() } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = listOf(circleDto("c-1")), hasMore = false), error = null)
        )
        assertEquals("a whole list says so", false, repository.isStillAMember("c-9"))
    }

    @Test
    fun `an unchanged refresh imports no keys, stores no wrap, writes no row and prefetches nothing`() = runTest {
        val ledger = MemoryLedger().also {
            it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        repository.backfillLedger = ledger
        stubSteadyState(circleDto(), storedRow())

        val result = repository.refreshCircles()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { tracksApi.getCircleKeys() }
        verify(exactly = 0) {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        }
        coVerify(exactly = 0) { circleDao.insertAll(any()) }
        coVerify(exactly = 0) { tracksApi.getActiveSessionKeys(any()) }
        coVerify(exactly = 0) { tracksApi.backfillPseudonyms(any(), any()) }
    }

    @Test
    fun `a moved key version imports keys and prefetches that circle's session keys`() = runTest {
        repository.backfillLedger = MemoryLedger()
        stubSteadyState(circleDto(keyVersion = 2), storedRow(keyVersion = 1), heldVersion = 1)
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = emptyList(), error = null)
        )
        every {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        } just Runs
        coEvery { tracksApi.getActiveSessionKeys("c-1") } returns Response.success(
            ApiResponse(success = true, data = emptyList(), error = null)
        )
        coEvery { tracksApi.backfillPseudonyms(any(), any()) } returns Response.success(
            ApiResponse(success = true, data = null, error = null)
        )

        repository.refreshCircles()

        coVerify(exactly = 1) { tracksApi.getCircleKeys() }
        coVerify(exactly = 1) { tracksApi.getActiveSessionKeys("c-1") }
        // The wrap on the circle is v2 and the device holds v1: it must be stored.
        verify(exactly = 1) {
            cryptoManager.storeCircleKey(eq("c-1"), any(), any(), eq(2), any(), any(), anyNullable(), anyNullable())
        }
        // The row changed (keyVersion), so it is written with a fresh stamp.
        coVerify(exactly = 1) { circleDao.insertAll(match { it.single().keyVersion == 2 && it.single().updatedAt != 1_000L }) }
    }

    @Test
    fun `a device a rotation left behind catches up by itself once its own wrap arrives`() = runTest {
        // The circle stays at v2 and only our member_key_version moves, when somebody
        // finally wraps the current key for us — which no other import reason
        // notices. The refresh imports it, and the circle is no longer behind:
        // sharing resumes with no restart.
        repository.backfillLedger = MemoryLedger().also {
            it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        stubSteadyState(circleDto(keyVersion = 2).copy(memberKeyVersion = 2), storedRow(keyVersion = 2))
        coEvery { circleDao.getById("c-1") } returns storedRow(keyVersion = 2)
        var held = 1
        every { cryptoManager.getActiveVersion("c-1") } answers { held }
        every { cryptoManager.newestHeldVersion("c-1") } answers { held }
        // Behind before the refresh: nothing may be sealed for c-1.
        assertFalse(repository.holdsCurrentKey("c-1"))
        assertEquals(setOf("c-1"), repository.behindCircles.value)
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(
                success = true,
                data = listOf(
                    CircleKeyBundleResponse(
                        circleId = "c-1",
                        encryptedCircleKey = EncryptedPayload(nonce = "n2", ciphertext = "c2"),
                        ephemeralPublicKey = "e2",
                        keyVersion = 2,
                    )
                ),
                error = null,
            )
        )
        every {
            cryptoManager.storeCircleKey(eq("c-1"), any(), any(), eq(2), any(), any(), anyNullable(), anyNullable())
        } answers { held = 2 }

        repository.refreshCircles()

        coVerify(exactly = 1) { tracksApi.getCircleKeys() }
        assertEquals(emptySet<String>(), repository.behindCircles.value)
        assertTrue(repository.holdsCurrentKey("c-1"))
    }

    @Test
    fun `a device still behind with no newer wrap for it does not re-import on every refresh`() = runTest {
        repository.backfillLedger = MemoryLedger().also {
            it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        stubSteadyState(circleDto(keyVersion = 2).copy(memberKeyVersion = 1), storedRow(keyVersion = 2), heldVersion = 1)
        coEvery { tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))

        repository.refreshCircles()

        coVerify(exactly = 0) { tracksApi.getCircleKeys() }
        // Its copy of the current version is missing: the members are asked for one.
        coVerify(exactly = 1) { tracksApi.requestAccess("c-1") }
    }

    @Test
    fun `a version held but not accepted yet (its record waits) is not imported or stored again on every refresh`() = runTest {
        // The SDK holds version 3 but seals under 2 until 3's record is accepted.
        // "Is my copy current?" asks what is HELD, never what is sealed under.
        repository.backfillLedger = MemoryLedger().also {
            it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        stubSteadyState(circleDto(keyVersion = 3).copy(memberKeyVersion = 3), storedRow(keyVersion = 3), heldVersion = 3)
        every { cryptoManager.getActiveVersion(any()) } returns 2
        coEvery { tracksApi.requestAccess(any()) } returns
            Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))

        repository.refreshCircles()
        repository.refreshCircles()

        coVerify(exactly = 0) { tracksApi.getCircleKeys() }
        verify(exactly = 0) {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        }
        // Behind ("Waiting to sync"), and no other copy is asked for: the one here waits on its
        // record, and the records import asks when a copy would help.
        assertEquals(setOf("c-1"), repository.behindCircles.value)
        coVerify(exactly = 0) { tracksApi.requestAccess(any()) }
    }

    @Test
    fun `the pseudonym backfill is sent once for a set and again only when the set changes`() = runTest {
        val ledger = MemoryLedger()
        repository.backfillLedger = ledger
        stubSteadyState(circleDto(), storedRow())
        coEvery { tracksApi.backfillPseudonyms(any(), any()) } returns Response.success(
            ApiResponse(success = true, data = null, error = null)
        )

        repository.refreshCircles()
        repository.refreshCircles()
        coVerify(exactly = 1) { tracksApi.backfillPseudonyms("c-1", any()) }

        // A rotation adds a pseudonym: the set moved, so it is sent again.
        every { cryptoManager.deriveUserPseudonymsAllVersions(any(), any()) } returns listOf("p1", "p2")
        repository.refreshCircles()
        coVerify(exactly = 2) { tracksApi.backfillPseudonyms("c-1", any()) }
    }

    @Test
    fun `a refused backfill is not recorded, so the next refresh retries it`() = runTest {
        val ledger = MemoryLedger()
        repository.backfillLedger = ledger
        stubSteadyState(circleDto(), storedRow())
        coEvery { tracksApi.backfillPseudonyms(any(), any()) } returns Response.error(
            503, okhttp3.ResponseBody.create(null, "")
        )

        repository.refreshCircles()
        repository.refreshCircles()

        coVerify(exactly = 2) { tracksApi.backfillPseudonyms("c-1", any()) }
        assertNull(ledger.digestFor("c-1"))
    }

    @Test
    fun `a forced refresh does the full work even when nothing changed`() = runTest {
        val ledger = MemoryLedger().also {
            it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        repository.backfillLedger = ledger
        stubSteadyState(circleDto(), storedRow())
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = emptyList(), error = null)
        )
        coEvery { tracksApi.getActiveSessionKeys("c-1") } returns Response.success(
            ApiResponse(success = true, data = emptyList(), error = null)
        )
        coEvery { tracksApi.backfillPseudonyms(any(), any()) } returns Response.success(
            ApiResponse(success = true, data = null, error = null)
        )

        repository.refreshCircles(force = true)

        coVerify(exactly = 1) { tracksApi.getCircleKeys() }
        coVerify(exactly = 1) { tracksApi.getActiveSessionKeys("c-1") }
        coVerify(exactly = 1) { tracksApi.backfillPseudonyms("c-1", any()) }
    }

    @Test
    fun `a held key that does not open the name falls back to storing the wrap`() = runTest {
        repository.backfillLedger = MemoryLedger().also {
            it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
        }
        stubSteadyState(circleDto(), storedRow())
        every {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        } just Runs
        // The first decrypt (held key) fails; after the store from the wrap it opens.
        every { cryptoManager.decryptDataV2(any(), any(), "name") } throws RuntimeException("bad key") andThen "Family"

        val result = repository.refreshCircles()

        assertEquals("Family", result.getOrNull()?.single()?.name)
        verify(exactly = 1) {
            cryptoManager.storeCircleKey(eq("c-1"), any(), any(), eq(1), any(), any(), anyNullable(), anyNullable())
        }
    }

    // --- pure policies ---

    @Test
    fun `keyImportReason imports at once for a new circle or a moved version, and rate-limits the rest`() {
        val stored = mapOf("c-1" to 1)
        val allHeld: (String) -> Boolean = { true }
        val fresh = CircleRepository.CONDITIONAL_KEY_IMPORT_COOLDOWN_MS
        fun reason(
            listed: List<Pair<String, Int>> = listOf("c-1" to 1),
            hasKey: (String) -> Boolean = allHeld,
            requested: Boolean = false,
            since: Long = fresh,
            force: Boolean = false,
        ) = CircleRepository.keyImportReason(force, listed, stored, hasKey, requested, since)

        assertEquals(CircleRepository.KeyImportReason.FORCED, reason(force = true))
        assertEquals(CircleRepository.KeyImportReason.NEW_CIRCLE, reason(listed = listOf("c-1" to 1, "c-2" to 1), since = 0))
        assertEquals(CircleRepository.KeyImportReason.KEY_VERSION_ADVANCED, reason(listed = listOf("c-1" to 2), since = 0))
        assertNull("nothing changed, every key held", reason())
        assertNull("missing key inside the cooldown", reason(hasKey = { false }, since = 1_000))
        assertEquals(CircleRepository.KeyImportReason.MISSING_KEY, reason(hasKey = { false }))
        assertNull("a request inside the cooldown waits", reason(requested = true, since = 1_000))
        assertEquals(CircleRepository.KeyImportReason.REQUESTED, reason(requested = true))
        assertNull(
            "the self key is LocationRepository's, not a reason to import",
            CircleRepository.keyImportReason(
                false, listOf("00000000-0000-0000-0000-000000000000" to 1),
                mapOf("00000000-0000-0000-0000-000000000000" to 1), { false }, false, fresh
            )
        )
    }

    @Test
    fun `rowsThatChanged leaves unchanged rows alone and re-stamps the rest`() {
        val known = mapOf("c-1" to storedRow(), "c-2" to storedRow(id = "c-2"))
        val same = storedRow()
        val renamed = storedRow(id = "c-2", name = "Work")
        val added = storedRow(id = "c-3").copy(updatedAt = 5L)

        val written = CircleRepository.rowsThatChanged(listOf(same, renamed, added), known, nowMs = 9_999L)

        assertEquals(listOf("c-2", "c-3"), written.map { it.id })
        assertTrue(written.all { it.updatedAt == 9_999L })
    }

    @Test
    fun `sessionKeyPrefetchTargets are new circles and moved versions unless forced`() {
        val known = mapOf("c-1" to storedRow(), "c-2" to storedRow(id = "c-2"))
        val circles = listOf(storedRow(), storedRow(id = "c-2", keyVersion = 3), storedRow(id = "c-3"))

        assertEquals(
            listOf("c-2", "c-3"),
            CircleRepository.sessionKeyPrefetchTargets(circles, known, force = false).map { it.id }
        )
        assertEquals(3, CircleRepository.sessionKeyPrefetchTargets(circles, known, force = true).size)
    }

    @Test
    fun `pseudonymSetDigest ignores order and is bound to the user`() {
        val a = CircleRepository.pseudonymSetDigest("user-1", listOf("p1", "p2"))
        assertEquals(a, CircleRepository.pseudonymSetDigest("user-1", listOf("p2", "p1")))
        assertNotEquals(a, CircleRepository.pseudonymSetDigest("user-2", listOf("p1", "p2")))
        assertNotEquals(a, CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
    }

    // =============================================================================
    // OWN PRIVACY POSTURE FROM THE CIRCLE LIST
    // =============================================================================

    private fun posture(circleId: String = "c-1", mode: String = "approximate", paused: String? = null) =
        PrivacySettingsResponse(
            id = "ps-1", circleId = circleId, sharingMode = mode, approximateRadiusMiles = 5,
            shareRealtime = true, shareHistory = true, shareBattery = false,
            pausedUntil = paused, primaryDeviceId = null
        )

    private fun steadyLedger() = MemoryLedger().also {
        it.record("c-1", CircleRepository.pseudonymSetDigest("user-1", listOf("p1")))
    }

    @Test
    fun `a circle that carries my_privacy is stored from it and never fetched`() = runTest {
        repository.backfillLedger = steadyLedger()
        stubSteadyState(circleDto().copy(myPrivacy = posture(paused = "2026-09-23T08:00:00Z")), storedRow())
        coEvery { privacySettingDao.insert(any()) } just Runs

        repository.refreshCircles()

        coVerify(exactly = 1) {
            privacySettingDao.insert(match {
                it.circleId == "c-1" && it.sharingMode == "approximate" &&
                    it.approximateRadiusMiles == 5 && !it.shareBattery &&
                    it.pausedUntil == java.time.Instant.parse("2026-09-23T08:00:00Z").toEpochMilli()
            })
        }
        // The per-circle fallback is launched on IO, so give any launch a real-time
        // window to happen before asserting it did not: a bare exactly = 0 right
        // after the refresh would pass even if the fetch had been launched.
        Thread.sleep(500)
        coVerify(exactly = 0) { tracksApi.getPrivacySettings(any()) }
    }

    @Test
    fun `without my_privacy the circle still falls back to the per-circle GET`() = runTest {
        repository.backfillLedger = steadyLedger()
        stubSteadyState(circleDto(), storedRow())
        coEvery { tracksApi.getPrivacySettings("c-1") } returns Response.success(
            ApiResponse(success = true, data = posture(mode = "exact"), error = null)
        )
        coEvery { privacySettingDao.insert(any()) } just Runs

        repository.refreshCircles()

        // The fallback is a fire-and-forget sync on IO, hence the timeout.
        coVerify(timeout = 5_000, exactly = 1) { tracksApi.getPrivacySettings("c-1") }
        coVerify(timeout = 5_000) { privacySettingDao.insert(match { it.circleId == "c-1" && it.sharingMode == "exact" }) }
    }

    @Test
    fun `only the circles the list does not cover are fetched, keyed on the list's id`() {
        val plan = CircleRepository.privacyMirrorPlan(
            listOf(
                circleDto("c-1").copy(myPrivacy = posture(circleId = "something-else")),
                circleDto("c-2"),
            )
        )
        assertEquals(listOf("c-1"), plan.embedded.map { it.circleId })
        assertEquals(listOf("c-2"), plan.fetch)
    }

    @Test
    fun `my pause's end is kept in the form the privacy route writes it`() = runTest {
        // As the server writes it, with to_rfc3339(): "+00:00", fractions when there are some.
        coEvery { privacySettingDao.insert(any()) } just Runs

        repository.persistPrivacyLocally(posture(mode = "paused", paused = "2026-10-14T17:00:00.123456+00:00"))
        repository.persistPrivacyLocally(posture(circleId = "c-2", mode = "paused", paused = "2026-10-14T17:00:00+00:00"))

        coVerify { privacySettingDao.insert(match { it.circleId == "c-1" && it.pausedUntil == 1_791_997_200_123L }) }
        coVerify { privacySettingDao.insert(match { it.circleId == "c-2" && it.pausedUntil == 1_791_997_200_000L }) }
    }

    @Test
    fun `the mode held before a pause is kept with the row the send path reads`() = runTest {
        coEvery { privacySettingDao.insert(any()) } just Runs

        repository.persistPrivacyLocally(posture(mode = "paused").copy(modeBeforePause = "approximate"))

        coVerify {
            privacySettingDao.insert(match {
                it.sharingMode == "paused" && it.modeBeforePause == "approximate" && it.approximateRadiusMiles == 5
            })
        }
    }

    /** This JVM's Instant.parse reads "+00:00" too (JDK 12 and later), so the test above can't see it. */
    @Test
    fun `my pause's end is not read with Instant parse, which reads only Z before Android 14`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        val start = src.indexOf("private fun parseIsoToEpochMillis(")
        assertTrue("parseIsoToEpochMillis not found — update this test with the new name", start >= 0)
        val body = src.substring(start).substringBefore("\n\n")
        assertTrue(body, body.contains("OffsetDateTime.parse("))
        assertFalse(body, body.contains("Instant.parse("))
    }

    // =============================================================================
    // WHY A CIRCLE IS LOCKED
    // =============================================================================

    /**
     * Refresh a circle this device has never held, whose wrap [store] reacts to as
     * given, and with no key held afterwards: the shapes a locked circle comes from.
     */
    private suspend fun refreshLocked(dto: CircleResponse, store: MockKStubScope<Unit, Unit>.() -> Unit) {
        coEvery { circleDao.getAll() } returns emptyList()
        coEvery { tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(success = true, data = emptyList(), error = null)
        )
        coEvery { tracksApi.getCircles() } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = listOf(dto), hasMore = false), error = null)
        )
        every { cryptoManager.hasCircleKey(any()) } returns false
        every {
            cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), anyNullable(), anyNullable())
        }.store()
        coEvery { circleDao.insertAll(any()) } just Runs

        assertTrue(repository.refreshCircles().isSuccess)
    }

    private fun storedName(): String {
        val rows = mutableListOf<List<CircleEntity>>()
        coVerify { circleDao.insertAll(capture(rows)) }
        return rows.last().single().name
    }

    /**
     * The circle with a copy for us, as the server lists it: member_key_version comes with every
     * copy, and it is what says there is one.
     */
    private fun withOurCopy() = circleDto().copy(memberKeyVersion = 1)

    @Test
    fun `a circle with no key for us is locked and waits for access`() = runTest {
        refreshLocked(circleDto().copy(encryptedKey = null, ephemeralPublicKey = null)) { just(Runs) }

        assertEquals(LockedCircle.PLACEHOLDER_NAME, storedName())
        assertEquals(LockedCircle.Cause.AWAITING_ACCESS, repository.lockedCircleCause("c-1"))
        // Nothing about a reset is asked when it cannot be the cause.
        coVerify(exactly = 0) { previousIdentityLoader.preResetDataExists() }
    }

    @Test
    fun `a refused wrap is explained as refused`() = runTest {
        refreshLocked(circleDto()) { throws(uniffi.pingolin_mobile.MobileException.SignatureInvalid("bad")) }

        assertEquals(LockedCircle.PLACEHOLDER_NAME, storedName())
        assertEquals(LockedCircle.Cause.KEY_REFUSED, repository.lockedCircleCause("c-1"))
    }

    @Test
    fun `a wrap that will not open after an email reset points to the restore`() = runTest {
        refreshLocked(withOurCopy()) { throws(uniffi.pingolin_mobile.MobileException.DecryptionFailed("stale")) }
        coEvery { previousIdentityLoader.preResetDataExists() } returns true

        assertEquals(LockedCircle.Cause.OWN_RESET, repository.lockedCircleCause("c-1"))
    }

    @Test
    fun `a wrap that will not open without any reset is not blamed on one`() = runTest {
        refreshLocked(withOurCopy()) { throws(uniffi.pingolin_mobile.MobileException.DecryptionFailed("stale")) }
        coEvery { previousIdentityLoader.preResetDataExists() } returns false

        assertEquals(LockedCircle.Cause.UNREADABLE, repository.lockedCircleCause("c-1"))
    }

    /**
     * The foreground asks for identities from before a reset again only while a circle here
     * is locked since one (its copy won't open with this account's keys).
     */
    @Test
    fun `the foreground looks again for identities while a circle is locked since a reset`() = runTest {
        refreshLocked(circleDto().copy(encryptedKey = null, ephemeralPublicKey = null)) { just(Runs) }
        repository.onForegroundIdentities()
        coVerify(exactly = 1) { previousIdentityLoader.onForeground(false) }

        refreshLocked(withOurCopy()) { throws(uniffi.pingolin_mobile.MobileException.DecryptionFailed("stale")) }
        repository.onForegroundIdentities()
        coVerify(exactly = 1) { previousIdentityLoader.onForeground(true) }
    }

    @Test
    fun `a circle really named Locked circle is not treated as locked`() = runTest {
        // The name test alone would stop rotation for it: rotation refuses to
        // re-encrypt a placeholder as the circle's name.
        repository.backfillLedger = MemoryLedger()
        stubSteadyState(circleDto(), storedRow(name = "Old name"))
        every { cryptoManager.decryptDataV2(any(), any(), "name") } returns "Locked circle"

        repository.refreshCircles()

        assertFalse(repository.isCircleLocked(storedRow(name = "Locked circle")))
    }

    @Test
    fun `a stored placeholder counts as locked until this process looks`() {
        assertTrue(repository.isCircleLocked(storedRow(name = "Circle (key unavailable)")))
        assertTrue(repository.isCircleLocked(storedRow(name = LockedCircle.PLACEHOLDER_NAME)))
        assertFalse(repository.isCircleLocked(storedRow(name = "Family")))
    }

    @Test
    fun `a circle whose key moved on without this phone is locked, waiting for the new key`() = runTest {
        // Locked here even though the older key still opens its name, because nothing
        // can be shared with it from this device until the new key comes.
        repository.backfillLedger = MemoryLedger()
        stubSteadyState(circleDto(keyVersion = 2).copy(memberKeyVersion = 1), storedRow(keyVersion = 2), heldVersion = 1)

        repository.refreshCircles()

        assertTrue(repository.isCircleLocked(storedRow(keyVersion = 2)))
        assertEquals(LockedCircle.Cause.BEHIND, repository.lockedCircleCause("c-1"))
    }

    @Test
    fun `a hand-over waiting for its transfer is sent again by the circle refresh`() = runTest {
        // Retried durably, keyed to (circle, the hand-over's version, target).
        repository.backfillLedger = MemoryLedger()
        repository.handOvers = com.pingolin.track.util.SecurePrefsHandOvers(mockk(), { "user-1" }, { com.pingolin.track.testutil.InMemoryPrefs() })
        repository.handOvers.remember("c-1", com.pingolin.track.util.HandOvers.HandOver(2, "alice"))
        stubSteadyState(circleDto(), storedRow())
        coEvery { tracksApi.transferOwnership("c-1", any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        coEvery { circleDao.getById("c-1") } returns storedRow()
        coEvery { circleDao.insert(any()) } just Runs

        repository.refreshCircles()

        coVerify(exactly = 1) { tracksApi.transferOwnership("c-1", match { it.newOwnerId == "alice" }) }
        assertNull(repository.handOvers.of("c-1"))
    }

}
