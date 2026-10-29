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
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.DeliverAccessRequest
import com.pingolin.track.data.remote.dto.DeliverAccessResponse
import com.pingolin.track.data.remote.dto.MemberPublicKeyResponse
import com.pingolin.track.data.remote.dto.MemberResponse
import com.pingolin.track.data.remote.dto.MembershipStatementDto
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.data.remote.dto.RotateKeysRequest
import com.pingolin.track.data.remote.dto.RotateKeysResponse
import com.pingolin.track.data.remote.dto.StoreStatementRequest
import com.pingolin.track.data.remote.dto.StoreStatementResponse
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.FakeMemberPins
import com.pingolin.track.testutil.FakeMembershipStatements
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.util.KeyAccessApprovals
import com.pingolin.track.util.SecurePrefsKeyAccessApprovals
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KeyChainLinkData
import uniffi.pingolin_mobile.KeyRotationBundle
import uniffi.pingolin_mobile.MemberKeyBundle
import uniffi.pingolin_mobile.MemberKeyRotationEntry
import uniffi.pingolin_mobile.PinResult
import uniffi.pingolin_mobile.PinState

/**
 * The real CircleRepository against a mocked server and crypto manager, with this device's pins and signed
 * statements as plain fakes. One circle, [circleId] ("c-1" by default), at key version [circleVersion]; this
 * device holds [heldKeyVersion] of it.
 *
 * Keys: [sharing] and [signing] of a user id, 64 hex digits each, distinct per user.
 */
internal class TrustHarness(
    val self: String = "me",
    selfRole: String = "owner",
    /** Changes waiting to sync: none by default; a test that keeps some passes its own. */
    val pendingChanges: com.pingolin.track.domain.pending.PendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
    /**
     * The server's replies reach the repository as the app decodes them, account ids canonical
     * (CanonicalIdsTrackApi): its tests use UUIDs, as the decoder drops any other id.
     */
    canonicalIds: Boolean = false,
    /** The one circle's id. The decoder ([canonicalIds]) refuses a circle id that is no UUID, so its tests pass one. */
    val circleId: String = "c-1",
    /** Identities from before a reset, made with this harness's SDK: a stand-in unless a test passes the real loader. */
    previousIdentityLoader: (CryptoManager) -> PreviousIdentityLoader = { mockk(relaxed = true) },
) {
    val context: android.content.Context = mockk(relaxed = true)
    val tracksApi: TrackApi = mockk()
    val circleDao: CircleDao = mockk()
    val circleMemberDao: CircleMemberDao = mockk(relaxed = true)
    val sessionManager: SessionManager = mockk()
    val cryptoManager: CryptoManager = mockk(relaxed = true)
    /** The approvals' file: a test damages it ([DamagedPrefs.damaged]). */
    val approvalPrefs = DamagedPrefs()
    /** Invitations "me" agreed to that wait to be renewed. */
    val pendingAccepts = com.pingolin.track.util.SecurePrefsPendingInviteAccepts(mockk(), { self }, { InMemoryPrefs() })
    /** "Is this really {email}?" raised and not answered; survives wipeInMemory. */
    val renewalQuestions = com.pingolin.track.util.SecurePrefsPendingRenewalQuestions(mockk(), { self }, { InMemoryPrefs() })
    /** This user's removals without a key change; survives wipeInMemory. Its file: [ownRemovalsPrefs]. */
    val ownRemovalsPrefs = DamagedPrefs()
    val ownRemovals = com.pingolin.track.util.SecurePrefsOwnRemovals(mockk(), { self }, { ownRemovalsPrefs })
    /** Role changes this user made, until its own rotation records them; survives wipeInMemory. */
    val ownerRoleChangesPrefs = DamagedPrefs()
    val ownerRoleChanges = com.pingolin.track.util.SecurePrefsOwnerRoleChanges(mockk(), { self }, { ownerRoleChangesPrefs })
    /** The owner's hand-overs this device recorded, until their transfer lands; survives wipeInMemory. */
    val handOvers = com.pingolin.track.util.SecurePrefsHandOvers(mockk(), { self }, { InMemoryPrefs() })
    /** The repair budget; survives wipeInMemory. Its file: [repairBudgetPrefs]. */
    val repairBudgetPrefs = DamagedPrefs()
    val repairBudget = com.pingolin.track.util.SecurePrefsRepairBudget(mockk(), { self }, { repairBudgetPrefs })
    /** "It's them" answers; survives wipeInMemory. */
    val personConfirmedKeys = com.pingolin.track.util.SecurePrefsPersonConfirmedKeys(mockk(), { self }, { InMemoryPrefs() })
    /** When this account paused the circle, on the statements' clock; survives wipeInMemory. */
    val pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory({ self }, { statements.now * 1000 })
    /** How wide a bubble the circle got, and when, on the statements' clock; survives wipeInMemory. */
    val approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory({ self }, { statements.now * 1000 })
    val statements = FakeMembershipStatements(selfSigningKey = signing(self))
    /** On the device's one clock, which the statements' "now" stands for: records lapse by it. */
    val approvals = SecurePrefsKeyAccessApprovals(mockk(), { self }, { approvalPrefs }, { statements.now * 1000 })
    val pins = FakeMemberPins()

    var circleVersion = 3
    var heldKeyVersion = 3
    var role = selfRole
    var circle = CircleEntity(
        id = circleId, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0,
        isOwner = selfRole == "owner", keyRotationNeeded = true, keyVersion = 3,
    )

    /** This account's own SOS, resealed when a circle's key moves: a test verifies the calls. */
    val sosRepository: SosRepository = mockk(relaxed = true)

    /** What the stored member rows were last replaced with. */
    var storedRows: List<CircleMemberEntity> = emptyList()

    /** First copies taken through the SDK's confirmed-sender check: (sender, confirmed key, sealed name?). */
    val firstCopies = mutableListOf<Triple<String, String, uniffi.pingolin_mobile.EncryptedData?>>()

    /** Questions taken to the person as notifications, in order. */
    val questions = mutableListOf<String>()

    /** What the decoder logged of the ids it refused ([canonicalIds]). */
    val refusedIds = mutableListOf<String>()

    val repository: CircleRepository

    init {
        every { sessionManager.getUserId() } returns self
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.getEmail() } returns "$self@x.test"
        coEvery { circleDao.getById(circleId) } answers { circle.copy(keyVersion = circleVersion) }
        coEvery { circleDao.insert(any()) } just Runs
        coEvery { circleMemberDao.replaceForCircle(circleId, any()) } answers { storedRows = arg(1) }
        every { cryptoManager.getActiveVersion(circleId) } answers { heldKeyVersion }
        every { cryptoManager.newestHeldVersion(circleId) } answers { heldKeyVersion }
        every { cryptoManager.hasCircleKey(circleId) } answers { heldKeyVersion > 0 }
        every { cryptoManager.signingPublicKeyHex() } returns signing(self)
        every { cryptoManager.exportPublicKey() } returns java.util.Base64.getEncoder().encodeToString(hexBytes(sharing(self)))
        every { cryptoManager.hasActiveSessionKey(any()) } returns true
        every { cryptoManager.computeSafetyNumber(self, any(), any(), any()) } answers { "number-of-${arg<String>(1)}" }
        every { cryptoManager.retainMemberPins(any(), any()) } returns 0u
        // The SDK's pin store, as the fake: a first pin is recorded, a different key is a change.
        every { cryptoManager.pinMemberKeys(any(), any(), any(), any()) } answers {
            val user = arg<String>(1)
            val x = arg<String>(2)
            val s = arg<String>(3)
            val px = pins.sharing[user]
            val ps = pins.signing[user]
            when {
                px == null && ps == null -> { pins.pin(user, x, s); PinResult(PinState.NEW_PINNING, 0L) }
                px.equals(x, true) && ps.equals(s, true) -> PinResult(PinState.MATCHED, 0L)
                else -> PinResult(PinState.CHANGED_BOTH, 1L)
            }
        }
        every { cryptoManager.approveMemberKeyChange(any(), any(), any(), any()) } answers {
            pins.pin(arg(1), arg(2), arg(3))
        }
        every { cryptoManager.wrapCircleKeyForMemberFull(circleId, any(), self, any(), any()) } answers {
            MemberKeyBundle(EncryptedData("n-${arg<String>(3)}", "c-${arg<String>(1)}"), "e-${arg<String>(3)}", "sig-${arg<String>(3)}")
        }
        every { cryptoManager.createKeyChainLink(any()) } returns KeyChainLinkData(3, 4, "ek", "nk")
        every { cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) } answers {
            val ids = arg<List<String>>(2)
            KeyRotationBundle(
                newKeyVersion = circleVersion + 1,
                encryptedName = EncryptedData("nn", "cc"),
                memberKeys = ids.map {
                    MemberKeyRotationEntry(it, MemberKeyBundle(EncryptedData("n-$it", "c-$it"), "e-$it", "s-$it"))
                },
                keyChainLink = KeyChainLinkData(circleVersion, circleVersion + 1, "ek", "nk"),
            )
        }
        // A rotation with its record, as prepareKeyRotation's fake: the included
        // entries get the wraps, and the link goes one version down.
        every { cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) } answers {
            val included = arg<List<uniffi.pingolin_mobile.PlannedEntry>>(3)
                .filter { it.state == uniffi.pingolin_mobile.RecordMemberState.INCLUDED }.map { it.userId }
            uniffi.pingolin_mobile.RecordedRotationBundle(
                KeyRotationBundle(
                    newKeyVersion = circleVersion + 1,
                    encryptedName = EncryptedData("nn", "cc"),
                    memberKeys = included.map {
                        MemberKeyRotationEntry(it, MemberKeyBundle(EncryptedData("n-$it", "c-$it"), "e-$it", "s-$it"))
                    },
                    keyChainLink = KeyChainLinkData(circleVersion + 1, circleVersion, "ek", "nk"),
                ),
                uniffi.pingolin_mobile.SignedRefreshRecord("record", "record-sig"),
            )
        }
        val locationRepository = mockk<LocationRepository>(relaxed = true)
        val locationRepositoryLazy = mockk<dagger.Lazy<LocationRepository>>()
        every { locationRepositoryLazy.get() } returns locationRepository

        repository = CircleRepository(
            context = context,
            tracksApi = if (canonicalIds) com.pingolin.track.data.remote.api.CanonicalIdsTrackApi(tracksApi) { refusedIds += it } else tracksApi,
            circleDao = circleDao,
            circleSortOrderDao = mockk(),
            circleMemberDao = circleMemberDao,
            pendingInviteDao = mockk(relaxed = true),
            privacySettingDao = mockk(),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            locationRepository = locationRepositoryLazy,
            previousIdentityLoader = previousIdentityLoader(cryptoManager),
            uxEventBus = UxEventBus(),
            currentKeyGate = CurrentKeyGate(circleDao, cryptoManager, mockk(relaxed = true), sessionManager),
            keyStoreRecovery = mockk(relaxed = true),
            pendingChanges = pendingChanges,
            sosRepository = dagger.Lazy { sosRepository },
            pauseIntervals = pauseIntervals,
            approximateIntervals = approximateIntervals,
        )
        repository.keyAccessApprovals = approvals
        // This account's own sealed name is sealed only where a test asks for it
        // (OwnNameBackfillTest runs it on its own scope): elsewhere it would be background calls
        // nobody stubbed.
        repository.ownNameScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })
        repository.pendingInviteAccepts = pendingAccepts
        repository.pendingRenewalQuestions = renewalQuestions
        repository.ownRemovals = ownRemovals
        repository.ownerRoleChanges = ownerRoleChanges
        repository.handOvers = handOvers
        repository.repairBudget = repairBudget
        repository.personConfirmedKeys = personConfirmedKeys
        repository.membershipStatements = statements
        repository.memberPins = pins
        repository.nowSecs = { statements.now }
        repository.questionNotifier = object : com.pingolin.track.util.MembershipQuestionNotifier {
            override fun memberReset(circleId: String, userId: String, name: String?) {
                questions += "reset $circleId $userId $name"
            }
            override fun waitingToJoin(circleId: String, circleName: String, userId: String, name: String) {
                questions += "join $circleId $circleName $userId $name"
            }
            override fun answered(circleId: String, userId: String) {
                questions += "answered $circleId $userId"
            }
            override fun inviteeChanged(circleId: String, userId: String, email: String) {
                questions += "renew $circleId $userId $email"
            }
            override fun clear() {
                questions += "clear"
            }
        }
        repository.firstKeys = object : FirstKeys {
            override fun storeFirstCircleKey(
                circleId: String, encryptedKey: String, senderPublicKey: String, keyVersion: Int,
                senderUserId: String, recipientUserId: String, wrapSignatureHex: String,
                confirmedSenderSigningPubkeyHex: String, sealedCircleName: EncryptedData?,
            ) {
                firstCopies += Triple(senderUserId, confirmedSenderSigningPubkeyHex, sealedCircleName)
                heldKeyVersion = keyVersion
            }
        }
    }

    /**
     * A circle with refresh records whose chain counts every listed member, with this user's
     * list role, and whose eligibility check allows every member this device confirms: its
     * rotations are recorded, and wrap the same members as a rotation without records.
     */
    fun recordedCircle() {
        every { cryptoManager.circleHasRefreshRecords(circleId) } returns true
        every { cryptoManager.refreshHeadVersions() } answers { mapOf(circleId to circleVersion) }
        // Named outside the answer: inside it, `self` is MockK's own (the mock).
        val me = self
        every { cryptoManager.chainRole(circleId, any()) } answers {
            if (arg<String>(1) == me) RefreshRecords.role(role) else uniffi.pingolin_mobile.CircleRole.MEMBER
        }
        every { cryptoManager.refreshEligibility(circleId, any(), any()) } answers {
            arg<List<uniffi.pingolin_mobile.RecordMember>>(1).map {
                uniffi.pingolin_mobile.MemberEligibility(it.userId, it.confirmed, null, false, false, false, null, false)
            }
        }
    }

    /** What an earlier build's seed wrote: the circle's approvals, seeded from its
     * first complete member list.
     */
    fun earlierSeed(vararg userIds: String) {
        approvalPrefs.edit()
            .putStringSet("$self|c-1", userIds.toSet())
            .putBoolean("$self|c-1#seeded", true)
            .apply()
        for (id in userIds) if (id != self) pins.pin(id, sharing(id), signing(id))
    }

    fun invitation(
        subject: String,
        by: String,
        kind: Kind = Kind.INVITATION_TO_ACCOUNT,
        signerKey: String = signing(by),
        atVersion: Int = circleVersion,
        expiresAt: Long = statements.now + 7 * 86_400,
        email: String? = null,
        id: String? = null,
        keysOf: String = subject,
    ) = if (kind == Kind.INVITATION_TO_EMAIL) {
        statements.statement(kind, circleId, by, signerKey, email = email ?: "$subject@x.test", atVersion = atVersion, expiresAt = expiresAt, id = id ?: "inv-$subject")
    } else {
        statements.statement(kind, circleId, by, signerKey, subject, sharing(keysOf), signing(keysOf), atVersion = atVersion, expiresAt = expiresAt, id = id ?: "inv-$subject")
    }

    fun rosterMember(
        id: String,
        invitation: MembershipStatements.Signed? = null,
        vouches: List<MembershipStatements.Signed> = emptyList(),
        keyVersion: Int? = circleVersion,
        role: String = if (id == self) this.role else "member",
        keysOf: String = id,
        accessRequested: Boolean = false,
    ) = MemberResponse(
        userId = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        role = role,
        canRotateKeys = role == "owner" || role == "admin",
        sharingPublicKey = sharing(keysOf),
        signingPublicKey = signing(keysOf),
        keyVersion = keyVersion,
        invitation = invitation?.let { MembershipStatementDto(it.statementHex, it.signatureHex, null) },
        vouches = vouches.map { MembershipStatementDto(it.statementHex, it.signatureHex, null) }.takeIf { it.isNotEmpty() },
        accessRequested = accessRequested,
    )

    fun key(id: String, keyVersion: Int? = circleVersion, email: String? = null, keysOf: String = id) = MemberPublicKeyResponse(
        userId = id,
        sharingPublicKey = sharing(keysOf),
        signingPublicKey = signing(keysOf),
        keyVersion = keyVersion,
        email = email,
    )

    /** The server's member list (and member-public-keys, from the same rows). */
    fun serverLists(roster: List<MemberResponse>, keys: List<MemberPublicKeyResponse> = roster.map {
        MemberPublicKeyResponse(it.userId, it.sharingPublicKey!!, it.signingPublicKey, it.keyVersion)
    }) {
        coEvery { tracksApi.getMemberPublicKeys(circleId) } returns
            Response.success(ApiResponse(success = true, data = keys, error = null))
        coEvery { tracksApi.getCircleMembers(circleId, any(), any()) } returns Response.success(
            ApiResponse(success = true, data = PaginatedResponse(items = roster, hasMore = false), error = null)
        )
    }

    fun rotationAccepted(): io.mockk.CapturingSlot<RotateKeysRequest> {
        val sent = slot<RotateKeysRequest>()
        coEvery { tracksApi.rotateCircleKeys(circleId, capture(sent)) } answers {
            Response.success(ApiResponse(success = true, data = RotateKeysResponse(newKeyVersion = circleVersion + 1), error = null))
        }
        return sent
    }

    /** Deliveries the server takes, by recipient. */
    fun deliveriesAccepted(): MutableMap<String, DeliverAccessRequest> {
        val sent = mutableMapOf<String, DeliverAccessRequest>()
        coEvery { tracksApi.deliverAccess(circleId, any(), any()) } answers {
            sent[arg(1)] = arg(2)
            Response.success(ApiResponse(success = true, data = DeliverAccessResponse(true, arg<DeliverAccessRequest>(2).keyVersion), error = null))
        }
        return sent
    }

    fun statementsAccepted(): MutableMap<String, StoreStatementRequest> {
        val sent = mutableMapOf<String, StoreStatementRequest>()
        coEvery { tracksApi.storeMembershipStatement(circleId, any(), any()) } answers {
            sent[arg(1)] = arg(2)
            Response.success(ApiResponse(success = true, data = StoreStatementResponse(true), error = null))
        }
        return sent
    }

    fun origins(): Map<String, KeyAccessApprovals.Origin> = approvals.origins(circleId)

    companion object {
        fun sharing(id: String) = sha256Hex("sharing:$id")
        fun signing(id: String) = sha256Hex("signing:$id")

        private fun sha256Hex(text: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
