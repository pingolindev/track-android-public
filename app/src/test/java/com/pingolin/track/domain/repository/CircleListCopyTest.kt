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

import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.AccessRequestResponse
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.ArchivedRecoveryEntryDto
import com.pingolin.track.data.remote.dto.ArchivedRecoveryResponse
import com.pingolin.track.data.remote.dto.PreviousIdentitiesResponse
import com.pingolin.track.data.remote.dto.PreviousIdentityDto
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.SealedPreviousIdentityDto
import com.pingolin.track.testutil.startOnIpv4Loopback
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.SealedPreviousIdentity

/**
 * Whether the server holds a copy of a circle's key for us, as GET /api/v1/circles really says
 * it. The copy rides as encrypted_circle_key, circle_key_nonce and ephemeral_public_key, and
 * member_key_version comes exactly when our row has one. Looked for under any other name
 * (`encrypted_key`, which the server never sends), a copy that didn't open would be taken for
 * no copy at all: an account reset by email would be told to wait for access, and a restore
 * made in the account portal would reach a running app only by push or a cold start.
 *
 * The replies are the server's JSON, decoded as the app decodes them (Retrofit and its Gson,
 * then CanonicalIdsTrackApi), into the real CircleRepository and the real PreviousIdentityLoader.
 * The SDK is the harness's mock, holding no key until a copy opens: getActiveVersion throws and
 * newestHeldVersion is null, as the SDK's do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleListCopyTest {

    /** The circle and the accounts, UUIDs with hex letters (the decoder takes no other ids). */
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"
    private val me = "be5e1f00-0000-4000-8000-0000000000be"
    private val ann = "a11ce000-0000-4000-8000-00000000000a"

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val api = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TrackApi::class.java)

    private val authApi = mockk<AuthApi>()

    /** What GET previous-identities answers, in turn (the ids of the identities a restore brought back); then none. */
    private val identityAnswers = ArrayDeque<List<String>>()
    private var identityGets = 0

    /** Identities from before a reset the SDK holds open: what it was last given. */
    private var restored = 0

    /** The account's email resets that left data behind, as auth's archived-recovery says. */
    private var archived = emptyList<ArchivedRecoveryEntryDto>()

    /** Whether our copy opens with the keys this account has now. */
    private var copyOpens: () -> Boolean = { true }

    /** Every time the SDK was asked to take our copy. */
    private var copyAttempts = 0

    /** The circle rows the device keeps. */
    private val rows = mutableMapOf<String, CircleEntity>()

    @After
    fun tearDown() {
        server.shutdown()
        clearAllMocks()
    }

    /** Our copy as the server keeps it: its version, and who signed it. */
    private data class Copy(val version: Int, val signer: String)

    // The lengths the server stores: a 24-byte nonce, a 32-byte key sealed with its tag, an X25519
    // key, an Ed25519 signature. Their bytes don't matter here: the SDK is mocked.
    private val nameNonce = "4e".repeat(24)
    private val nameCiphertext = "6e".repeat(24)
    private val copyNonce = "c0".repeat(24)
    private val copyCiphertext = "c1".repeat(48)
    private val ephemeral = "e0".repeat(32)
    private val signature = "51".repeat(64)

    /**
     * GET /api/v1/circles with the one circle, field for field and in the order the server
     * serializes it. An absent field is left out: with no [copy], member_key_version and the
     * copy's fields are not there at all.
     */
    private fun circleList(owner: String, isOwner: Boolean, keyVersion: Int, copy: Copy?): String {
        val ours = if (copy == null) "" else
            ""","member_key_version":${copy.version},"encrypted_circle_key":"$copyCiphertext","circle_key_nonce":"$copyNonce","ephemeral_public_key":"$ephemeral","sender_user_id":"${copy.signer}","wrap_signature":"$signature""""
        return """{"success":true,"data":{"items":[{"id":"$fam","owner_id":"$owner","is_owner":$isOwner,"encrypted_name":{"nonce":"$nameNonce","ciphertext":"$nameCiphertext"},"day_bucket":20366,"member_count":2,"key_version":$keyVersion,"name_key_version":$keyVersion$ours}],"has_more":false}}"""
    }

    /** GET /api/v1/circles/keys: the same copy, the only place this app takes one from (the account's own personal copy left out). */
    private fun circleKeys(copy: Copy?): String =
        if (copy == null) """{"success":true,"data":[]}"""
        else """{"success":true,"data":[{"circle_id":"$fam","encrypted_circle_key":{"nonce":"$copyNonce","ciphertext":"$copyCiphertext"},"ephemeral_public_key":"$ephemeral","key_version":${copy.version},"sender_user_id":"${copy.signer}","wrap_signature":"$signature"}]}"""

    /** [json] as the server sends it, through the app's own decoding: Retrofit, its Gson and [TrackApi]'s types. */
    private suspend fun <T> decoded(json: String, call: suspend TrackApi.() -> T): T {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json))
        return api.call()
    }

    /**
     * [self]'s phone, holding no key of the circle (a fresh store, as after an email reset), with
     * [role] in it, [owner] its owner; our row's copy is at [ours]. The circle is at v2, and so is its name.
     */
    private fun TestScope.harness(self: String, role: String, owner: String, ours: Int? = 2): TrustHarness = TrustHarness(
        self = self,
        selfRole = role,
        canonicalIds = true,
        circleId = fam,
        previousIdentityLoader = { sdk ->
            PreviousIdentityLoader(authApi, sdk).also {
                it.clock = { testScheduler.currentTime }
                it.scope = backgroundScope
            }
        },
    ).also { h ->
        h.repository.catchUpPausesMs = emptyList()
        h.heldKeyVersion = 0
        h.circleVersion = 2
        h.circle = h.circle.copy(keyVersion = 2, keyRotationNeeded = false, isOwner = owner == self)
        val other = if (owner == self) ann else owner
        h.serverLists(listOf(h.rosterMember(self, role = role, keyVersion = ours), h.rosterMember(other, role = if (other == owner) "owner" else "member")))
        // As the SDK: a circle it holds no key of has no version (KeyNotFound), not version 0.
        every { h.cryptoManager.getActiveVersion(fam) } answers {
            h.heldKeyVersion.takeIf { it > 0 } ?: throw MobileException.KeyNotFound("No key version for circle: $fam")
        }
        every { h.cryptoManager.newestHeldVersion(fam) } answers { h.heldKeyVersion.takeIf { it > 0 } }
        // Our copy, by whichever door it is taken (our own, on trust, or from a confirmed member).
        every { h.cryptoManager.storeCircleKey(fam, any(), any(), any(), any(), any(), anyNullable(), anyNullable()) } answers { take(h, arg(3)) }
        h.repository.firstKeys = object : FirstKeys {
            override fun storeFirstCircleKey(
                circleId: String, encryptedKey: String, senderPublicKey: String, keyVersion: Int,
                senderUserId: String, recipientUserId: String, wrapSignatureHex: String,
                confirmedSenderSigningPubkeyHex: String, sealedCircleName: EncryptedData?,
            ) = take(h, keyVersion)
        }
        every { h.cryptoManager.decryptDataV2(fam, any(), "name") } answers {
            if (h.heldKeyVersion >= 2) "Family" else throw MobileException.DecryptionFailed("no key for the name")
        }
        // Identities from before a reset, as the SDK holds them: the import replaces the set.
        every { h.cryptoManager.previousIdentityCount() } answers { restored.toUInt() }
        every { h.cryptoManager.previousSigningPublicKeys() } returns emptyList()
        every { h.cryptoManager.importPreviousIdentitiesV2(any()) } answers {
            restored = arg<List<SealedPreviousIdentity>>(0).size
            restored.toUInt()
        }
        coEvery { authApi.getPreviousIdentities() } answers {
            identityGets++
            val ids = identityAnswers.removeFirstOrNull().orEmpty()
            Response.success(ApiResponse(true, PreviousIdentitiesResponse(ids.map { PreviousIdentityDto(1, SealedPreviousIdentityDto("n-$it", "c-$it")) }), null))
        }
        coEvery { authApi.getArchivedRecovery() } answers { Response.success(ApiResponse(true, ArchivedRecoveryResponse(archived), null)) }
        coEvery { h.circleDao.getAll() } answers { rows.values.toList() }
        coEvery { h.circleDao.insertAll(any()) } answers {
            for (row in arg<List<CircleEntity>>(0)) {
                rows[row.id] = row
                if (row.id == fam) h.circle = row
            }
        }
        coEvery { h.tracksApi.requestAccess(fam) } returns Response.success(ApiResponse(true, AccessRequestResponse(true), null))
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(ApiResponse(true, RefreshRecordsResponse(), null))
    }

    /** The SDK takes our copy at [version], or fails to open it, as [copyOpens] says. */
    private fun take(h: TrustHarness, version: Int) {
        copyAttempts++
        if (!copyOpens()) throw MobileException.DecryptionFailed("made for keys this account no longer has")
        h.heldKeyVersion = version
    }

    /** The server lists the circle with [copy] (null: none for us) and serves it on the key route; the phone refreshes. */
    private suspend fun refresh(h: TrustHarness, owner: String, copy: Copy?, keyVersion: Int = 2) {
        val list = decoded(circleList(owner, isOwner = owner == h.self, keyVersion, copy)) { getCircles() }
        val keys = decoded(circleKeys(copy)) { getCircleKeys() }
        // What the app decoded says about our copy what the server said: nothing else is under test.
        assertEquals(copy?.version, list.body()!!.data!!.items.single().memberKeyVersion)
        coEvery { h.tracksApi.getCircles() } returns list
        coEvery { h.tracksApi.getCircleKeys() } returns keys
        assertTrue("the refresh went through", h.repository.refreshCircles().isSuccess)
    }

    private fun TrustHarness.locked() = repository.isCircleLocked(rows.getValue(fam))


    @Test
    fun `after an email reset the owner's own copy does not open - unopenable, and a restore made in the portal reaches the running app at the next foreground`() = runTest {
        // The owner reset by email and signed in again on a fresh store.
        val h = harness(self = me, role = "owner", owner = me)
        copyOpens = { restored > 0 }
        refresh(h, owner = me, copy = Copy(2, signer = me))

        assertEquals("the key import tried the copy, and only it: the list stores none", 1, copyAttempts)
        assertTrue(h.locked())
        assertEquals(LockedCircle.PLACEHOLDER_NAME, rows.getValue(fam).name)
        assertEquals("the start-up load", 1, identityGets)

        // The restore in the account portal; then the app comes to the foreground. No push, no restart.
        identityAnswers += listOf("before-reset")
        h.repository.onForegroundIdentities()

        assertEquals("the foreground looked again", 2, identityGets)
        assertEquals(1, restored)
        assertEquals("the copy was taken again, and opened", 2, copyAttempts)
        assertFalse("the circle opens in the running app", h.locked())
        assertEquals("Family", rows.getValue(fam).name)
    }

    @Test
    fun `a member's copy that does not open after an email reset is explained as the reset, with the restore`() = runTest {
        val h = harness(self = me, role = "member", owner = ann)
        copyOpens = { false }
        archived = listOf(ArchivedRecoveryEntryDto(keyVersion = 1))
        refresh(h, owner = ann, copy = Copy(2, signer = ann))

        assertEquals(1, copyAttempts)
        val cause = h.repository.lockedCircleCause(fam)
        assertEquals(LockedCircle.Cause.OWN_RESET, cause)
        assertTrue(LockedCircle.explanationFor(cause, isOwner = false).offersRestore)
    }

    @Test
    fun `a copy that does not open on an account that never reset is not blamed on a reset`() = runTest {
        val h = harness(self = me, role = "member", owner = ann)
        copyOpens = { false }
        archived = emptyList()
        refresh(h, owner = ann, copy = Copy(2, signer = ann))

        assertEquals(1, copyAttempts)
        assertEquals(LockedCircle.Cause.UNREADABLE, h.repository.lockedCircleCause(fam))
    }

    @Test
    fun `a circle with no copy for us waits for access, and the foreground does not look again`() = runTest {
        val h = harness(self = me, role = "member", owner = ann, ours = null)
        refresh(h, owner = ann, copy = null)

        assertEquals(0, copyAttempts)
        assertTrue(h.locked())
        assertEquals(LockedCircle.Cause.AWAITING_ACCESS, h.repository.lockedCircleCause(fam))
        h.repository.onForegroundIdentities()

        assertEquals("only the start-up load", 1, identityGets)
        coVerify(exactly = 0) { authApi.getArchivedRecovery() }
    }

    @Test
    fun `a copy that opens names the circle, and nothing is looked for`() = runTest {
        val h = harness(self = me, role = "member", owner = ann)
        copyOpens = { true }
        refresh(h, owner = ann, copy = Copy(2, signer = ann))
        h.repository.onForegroundIdentities()

        assertEquals(1, copyAttempts)
        assertFalse(h.locked())
        assertEquals("Family", rows.getValue(fam).name)
        assertEquals("only the start-up load", 1, identityGets)
    }

    /**
     * A copy a rotation left behind, on a phone that holds no key of the circle: it didn't open at the
     * import either. A member who never reset opens an older copy; this is the reset's shape.
     */
    @Test
    fun `a copy a rotation left behind that does not open is the reset too - explained with the restore, and looked for again`() = runTest {
        val h = harness(self = me, role = "member", owner = ann, ours = 1)
        copyOpens = { false }
        archived = listOf(ArchivedRecoveryEntryDto(keyVersion = 1))
        refresh(h, owner = ann, copy = Copy(1, signer = ann))

        assertEquals(1, copyAttempts)
        assertEquals(LockedCircle.Cause.OWN_RESET, h.repository.lockedCircleCause(fam))
        h.repository.onForegroundIdentities()

        assertEquals("the foreground looked again", 2, identityGets)
    }

    @Test
    fun `a left-behind copy that does not open on an account that never reset is not blamed on a reset, and is looked for again`() = runTest {
        val h = harness(self = me, role = "member", owner = ann, ours = 1)
        copyOpens = { false }
        archived = emptyList()
        refresh(h, owner = ann, copy = Copy(1, signer = ann))

        assertEquals(LockedCircle.Cause.UNREADABLE, h.repository.lockedCircleCause(fam))
        h.repository.onForegroundIdentities()

        assertEquals(2, identityGets)
    }

    /**
     * This member's email reset made the owner's phone re-key the circle, which left this member
     * pending at v1, a copy made for the keys it had before the reset. The restore brings those keys
     * back at the next foreground: the old copy opens (and with it what the circle shared before),
     * and the circle then waits for its current copy like any member a rotation left behind.
     */
    @Test
    fun `after the reset's own re-key left this member behind, the restore reaches the running app at the next foreground and the circle waits for its current copy`() = runTest {
        val h = harness(self = me, role = "member", owner = ann, ours = 1)
        copyOpens = { restored > 0 }
        refresh(h, owner = ann, copy = Copy(1, signer = ann))

        assertEquals(1, copyAttempts)
        assertTrue(h.locked())

        identityAnswers += listOf("before-reset")
        h.repository.onForegroundIdentities()

        assertEquals("the foreground looked again", 2, identityGets)
        assertEquals("the old copy opened", 1, h.heldKeyVersion)
        assertEquals(2, copyAttempts)
        assertTrue("still locked: v2 never reached this member", h.locked())
        assertEquals(LockedCircle.Cause.BEHIND, h.repository.lockedCircleCause(fam))

        // Nothing is left to restore: later foregrounds don't look again.
        advanceTimeBy(PreviousIdentityLoader.RECHECK_WINDOW_MS + 1)
        h.repository.onForegroundIdentities()
        assertEquals(2, identityGets)
    }

    /** The control: a member who never reset holds the older key; the circle moved on without it. */
    @Test
    fun `a member holding the older key a rotation left behind waits for the current one, and nothing is looked for`() = runTest {
        val h = harness(self = me, role = "member", owner = ann, ours = 1)
        h.heldKeyVersion = 1
        rows[fam] = h.circle.copy(keyVersion = 1, name = "Family")
        copyOpens = { true }
        refresh(h, owner = ann, copy = Copy(1, signer = ann))

        assertTrue(h.locked())
        assertEquals(LockedCircle.Cause.BEHIND, h.repository.lockedCircleCause(fam))
        h.repository.onForegroundIdentities()

        assertEquals("only the start-up load", 1, identityGets)
        coVerify(exactly = 0) { authApi.getArchivedRecovery() }
    }
}
