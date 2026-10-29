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

import android.util.Log
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleSortOrderDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.PendingInviteDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleSortOrderEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.PendingInviteEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.local.entity.sendsBubble
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiErrors
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.CreateCircleRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.InviteRequest
import com.pingolin.track.data.remote.dto.KeyChainLinkResponse
import com.pingolin.track.data.remote.dto.MemberKeyUpdate
import com.pingolin.track.data.remote.dto.MemberPublicKeyResponse
import com.pingolin.track.data.remote.dto.OwnershipClaimExistsException
import com.pingolin.track.data.remote.dto.OwnershipClaimResponse
import com.pingolin.track.data.remote.dto.InviteResponse
import com.pingolin.track.data.remote.dto.PendingInviteResponse
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.data.remote.dto.PublishSessionKeyRequest
import com.pingolin.track.data.remote.dto.PutHeadRequest
import com.pingolin.track.data.remote.dto.RefreshRecordPayload
import com.pingolin.track.data.remote.dto.RefreshRecordsRequest
import com.pingolin.track.data.remote.dto.RotateKeysRequest
import com.pingolin.track.data.remote.dto.RotateKeysResponse
import com.pingolin.track.data.remote.dto.ServedCircleHead
import com.pingolin.track.data.remote.dto.TransferOwnershipRequest
import com.pingolin.track.data.remote.dto.UpdateMemberRoleRequest
import com.pingolin.track.util.MemberVerificationStore
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import com.pingolin.track.util.retryWhenUnopenable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.PinState
import retrofit2.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surfaced when [CryptoManager.pinMemberKeys] returns a [PinState] other
 * than `NewPinning` or `Matched`. The UI consumes the [CircleRepository]
 * `memberKeyAlerts` flow and shows a [MemberKeyChangedDialogFragment] to
 * either approve the new keys or block them. [previousPinnedAt] is the
 * `last_verified_at` of the prior pin record — used to display "last
 * verified N days ago" in the alert dialog. New sharing/signing pubkeys
 * are carried verbatim so the approval call can pass them straight back to
 * `approveMemberKeyChange`.
 */
data class MemberKeyAlert(
    val circleId: String,
    val userId: String,
    val displayName: String,
    val state: PinState,
    val previousPinnedAt: Long,
    val newSharingHex: String,
    val newSigningHex: String,
    /**
     * True for the signing-strip downgrade case: the server served this member
     * with only a sharing key (no signing key) but we had already pinned them
     * WITH one, so a plain Approve is impossible — the SDK's approve/pin calls
     * require a 32-byte signing key and would throw, re-spawning the alert and
     * wedging the member. Never shown as a question: there is nothing to answer
     * until they update, so the server tells THEM and the member list says they
     * have been asked. The SDK has no sharing-only pin update, so this stays
     * blocked (rotation excludes the member) until they migrate.
     */
    val signingUnavailable: Boolean = false,
    /** Their number (the security code), shown with the question. */
    val number: String? = null,
    /** An email invitee: their keys are this device's record, not an SDK pin. */
    val receiveOnly: Boolean = false,
)

/**
 * The invitation no longer names this account as it is now, and the circle has been asked
 * to renew it; the app accepts by itself once it does. Its message says so.
 */
class InvitationBeingRenewedException(message: String) : Exception(message)

/**
 * "Is this really {email}?" about an ACCOUNT invitation this account made that no longer names
 * its account as it is now — they reset their password after being invited. "It's them"
 * renews it for exactly [sharingKeyHex] and [signingKeyHex], the keys read when asking; "No"
 * withdraws it. Asked once per statement id.
 */
data class InviteRenewalQuestion(
    val circleId: String,
    val circleName: String,
    val inviteId: String,
    val statementId: String,
    val email: String,
    val userId: String,
    val sharingKeyHex: String,
    val signingKeyHex: String,
    /** Their number (the security code) with the new details; null when it could not be computed. */
    val number: String?,
    /** The invitation as it was signed: renewed as it is, but for these keys. */
    val original: MembershipStatements.Signed,
    /** Asked again because the details changed by the answer; not asked a third time. */
    val reasked: Boolean = false,
)

/**
 * Owner/admin devices only: a member invited by someone this device cannot confirm. "Let them
 * in" pins and signs an admission for exactly the keys behind the number shown, and hands them
 * the circle; "Not now" leaves them out and asks again after a restart. Nobody is asked about a
 * member invited from an app older than signed invitations.
 */
data class KeyAccessRequest(
    val circleId: String,
    val circleName: String,
    val userId: String,
    val displayName: String,
    /** Owner/admin only (member-public-keys). */
    val email: String?,
    val reason: Reason,
    /** Their number (the security code), for [Reason.UNCONFIRMED_INVITER]. */
    val number: String? = null,
    /** The keys [number] was worked out from: the only ones "Let them in" pins and signs. */
    val sharingKeyHex: String? = null,
    val signingKeyHex: String? = null,
    /** [Reason.LATE_JOINER]: which late-joiner case. */
    val lateCase: LateJoiners.Case? = null,
    /** [Reason.LATE_JOINER]: who invited them, named only when that isn't an owner or admin in the chain. */
    val inviterName: String? = null,
    /** [Reason.LATE_JOINER]: the invitation the answer covers. */
    val invitation: LateJoiners.Invitation? = null,
) {
    enum class Reason {
        /** Invited by someone this device cannot confirm (an inviter who left, or unknown). */
        UNCONFIRMED_INVITER,
        /** Confirmed here, but the circle's records deny them the key: the late joiner. */
        LATE_JOINER,
        /**
         * Never a question: the waiting "Let them in" an owner's or admin's "It's them" keeps for a member the chain
         * doesn't count.
         */
        KEY_CHANGE,
    }
}

/**
 * What the member list shows about a circle beyond its rows: who is still joining —
 * confirmed, without the circle's current version yet — and, on owner/admin devices, how
 * many people Pingolin kept out.
 */
data class CircleMemberStates(
    val joining: Set<String> = emptySet(),
    val keptOut: Int = 0,
    /**
     * Members whose app is too old for this circle (a sharing key and no signing key),
     * whom the server has asked to update Pingolin. The list says so; nobody here is
     * asked anything.
     */
    val askedToUpdate: Set<String> = emptySet(),
)

/**
 * Thrown by [CircleRepository.inviteToCircle] when the invitee's looked-up
 * sharing key no longer matches the key we previously TOFU-pinned for that
 * (circleId, userId). Signals a possible server-side key substitution, so the
 * invite is refused (fail closed) instead of wrapping the live circle key to a
 * changed recipient key. Caught at the invite boundary and surfaced as a
 * [Result.failure] the UI shows — never silently degraded to lazy delivery.
 */
private class RecipientKeyChangedException : Exception(
    "This account changed since it was last in this circle — for example after a password reset — " +
        "so Pingolin didn't send the invitation, to be safe."
)

/**
 * The invited account changed since this device last saw it (a password reset, or a
 * server's lie): nothing was sent. The Circles screen asks "Is this really {email}?" with
 * their number; "It's them" ([CircleRepository.confirmInviteeAndInvite]) accepts their new
 * details on THIS device only and sends the invitation.
 */
class InviteeChangedException(
    val circleId: String,
    val email: String,
    val userId: String,
    val sharingKeyHex: String,
    val signingKeyHex: String,
    /** Their number (the security code) with the new details; null when it could not be computed. */
    val number: String?,
) : Exception(
    "This account changed since it was last in this circle — for example after a password reset — " +
        "so Pingolin didn't send the invitation, to be safe."
)

/**
 * "It's them" was answered about one account ([InviteeChangedException]), and the address now
 * names another one, or none: nothing was pinned, signed or sent (the answer goes on with
 * exactly the details shown).
 */
class InviteeNotAsConfirmedException(email: String) : Exception("Their details keep changing, so $email wasn't invited.")

/**
 * Pagination state for circles list.
 */
data class CirclesPaginationState(
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val cursor: String? = null,
    val error: String? = null
)

@Singleton
class CircleRepository @Inject constructor(
    private val previousIdentityLoader: PreviousIdentityLoader,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val tracksApi: TrackApi,
    private val circleDao: CircleDao,
    private val circleSortOrderDao: CircleSortOrderDao,
    private val circleMemberDao: CircleMemberDao,
    private val pendingInviteDao: PendingInviteDao,
    private val privacySettingDao: PrivacySettingDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val locationRepository: dagger.Lazy<LocationRepository>,
    private val uxEventBus: com.pingolin.track.util.UxEventBus,
    private val currentKeyGate: CurrentKeyGate,
    private val keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery,
    /** Changes waiting to sync on this phone: they go with a circle this user leaves. */
    private val pendingChanges: com.pingolin.track.domain.pending.PendingChanges,
    /** This account's own open SOS is resealed when a circle's key moves. Lazy: it reads the marks here. */
    private val sosRepository: dagger.Lazy<SosRepository>,
    /** When this account paused each circle: every privacy row stored here feeds it. */
    private val pauseIntervals: com.pingolin.track.util.PauseIntervals,
    /** How wide a bubble each circle got when: every privacy row stored here feeds it too. */
    private val approximateIntervals: com.pingolin.track.util.ApproximateIntervals,
) {
    /** Why a refresh imports keys. Null from [keyImportReason] means it does not. */
    internal enum class KeyImportReason(val rateLimited: Boolean) {
        FORCED(false),
        NEW_CIRCLE(false),
        KEY_VERSION_ADVANCED(false),
        MISSING_KEY(true),
        REQUESTED(true),
    }

    companion object {
        /** Which circles' privacy the circle list itself carries, and which still need the per-circle GET. */
        internal data class PrivacyMirrorPlan(
            val embedded: List<PrivacySettingsResponse>,
            val fetch: List<String>,
        )

        internal fun privacyMirrorPlan(dtos: List<CircleResponse>): PrivacyMirrorPlan {
            val embedded = mutableListOf<PrivacySettingsResponse>()
            val fetch = mutableListOf<String>()
            for (dto in dtos) {
                val posture = dto.myPrivacy
                // Keyed on the list's id: that is the id this device stores the
                // circle under, whatever the embedded object says about itself.
                if (posture != null) embedded += posture.copy(circleId = dto.id) else fetch += dto.id
            }
            return PrivacyMirrorPlan(embedded, fetch)
        }

        /** What the server says when this member has no privacy row in a circle. */
        internal const val NO_PRIVACY_ROW = "Privacy settings not found for this circle"

        /**
         * Whether [response] to GET /privacy/circles/{id} is the server's own "no row yet": a 404 whose
         * body is its envelope, `success` false and the error [NO_PRIVACY_ROW]. Only that may be read as
         * the server's default posture, exact and sharing. Any other 404, an ingress answering for a pod
         * mid-restart or a route that isn't there, says nothing about the posture. Consumes the error
         * body.
         */
        internal fun isNoPrivacyRow(response: Response<*>): Boolean {
            if (response.code() != 404) return false
            val body = runCatching { response.errorBody()?.string() }.getOrNull() ?: return false
            val json = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return false
            fun text(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
            return text("success") == "false" && text("error") == NO_PRIVACY_ROW
        }

        private const val TAG = "CircleRepository"
        // Nil UUID used by server for personal tracking - maps to "self" locally
        private const val SELF_CIRCLE_UUID = "00000000-0000-0000-0000-000000000000"
        private const val SELF_CIRCLE_ID = "self"
        private const val PAGE_SIZE = 20

        /** The members endpoint's largest page; see [allMemberPages]. */
        private const val MEMBER_PAGE_SIZE = 100


        /** The circle list's largest page (the server's maximum); see [laterCirclePages]. */
        private const val CIRCLE_PAGE_SIZE = 100

        /** A bound on the pages one circle refresh will follow. */
        private const val MAX_CIRCLE_PAGES = 20

        /** A bound on the pages one roster fetch will follow (2,000 members). */
        private const val MAX_MEMBER_PAGES = 20

        /** Between two requests for a fresh copy of one circle (the server's own is 10 min). */
        internal const val ACCESS_REQUEST_BACKOFF_MS = 10 * 60_000L
        /** An owner's invitation list is read for renewals at most this often per circle. */
        internal const val RENEWAL_LOOK_INTERVAL_MS = 60_000L
        /** ...and for this user's own invitations that are no longer pending. */
        internal const val OWN_INVITES_LOOK_INTERVAL_MS = 60_000L
        /** The server tells a member at most once a day per circle; no need to ask it more. */
        internal const val UPDATE_REQUEST_INTERVAL_MS = 24 * 60 * 60_000L

        /** What the in-app question calls a member whose name this device does not know. */
        private const val UNNAMED_MEMBER = "Member"

        /** "Let them in" found other keys than the question showed: it is asked again. */
        internal const val LET_IN_DETAILS_CHANGED = "Their details changed, so you're asked again."

        /** GET /circles/{id}/invites returns at most this many. */
        private const val INVITE_LIST_CAP = 1000

        /** A promotion that wasn't made: nothing changed. */
        internal const val PROMOTION_NOT_DONE = "Nothing was changed. Please try again."

        /** An invitation that wasn't sent, for no reason this app can name. */
        internal const val INVITE_NOT_SENT = "Couldn't send the invitation just now."
        /** Past the inviter's allowance of invitation e-mails: the windows slide, so no day is named. */
        internal const val INVITE_LIMIT = "Too many invitations in the last 24 hours. Try again later."
        internal const val CIRCLE_NOT_CREATED = "Couldn't create the circle just now."
        /** A hand-over that didn't go through, or not yet. */
        internal const val HAND_OVER_NOT_DONE = "Couldn't hand the circle over just now."
        /** An invitation's lapse is read with this much allowance for clocks (MemberConfirmation's). */
        private const val INVITATION_GRACE_SECS = 300L
        /** At most this many admissions the backfill signs for one member. */
        internal const val MAX_BACKFILL_IDS = 20

        /** "Is this really {name}?" is asked at most once a day per member. */
        internal const val MEMBER_QUESTION_INTERVAL_MS = 24 * 60 * 60_000L

        /** One delivery of a version to a member per process in this long (delivery is
         * first-wins anyway).
         */
        internal const val REDELIVERY_MS = 10 * 60_000L

        /** Between two membership passes on silent wake-ups. */
        internal const val SILENT_WAKE_INTERVAL_MS = 60_000L

        /** Between two delivery passes that found nothing to hand over (the server re-wakes every 30 min). */
        internal const val DELIVERY_PASS_INTERVAL_MS = 30 * 60_000L

        /** The two domains a member's copy of a circle may be signed under (invitation/delivery, rotation). */
        private val WRAP_DOMAINS = listOf("pingolin.sig.v1.circle_member_wrap", "pingolin.sig.v1.rotation_member_wrap")

        /**
         * Floor between two imports taken for a [KeyImportReason.rateLimited]
         * reason. A circle whose key exchange never completes, or a row that can
         * never decrypt, would otherwise put a full key import on every 5-minute
         * refresh — the cost this whole path exists to remove.
         */
        internal const val CONDITIONAL_KEY_IMPORT_COOLDOWN_MS = 15 * 60_000L

        /**
         * Does this refresh need the oblivious key import?
         *
         * [listed] is (circle id, key version) as the server lists it now;
         * [storedVersions] what this device held before the refresh. A new circle or
         * a moved key version imports at once — both are how a rotation or a join
         * reaches this device. A missing key or an outstanding request imports at
         * most once per [CONDITIONAL_KEY_IMPORT_COOLDOWN_MS].
         */
        internal fun keyImportReason(
            force: Boolean,
            listed: List<Pair<String, Int>>,
            storedVersions: Map<String, Int>,
            hasKey: (String) -> Boolean,
            requested: Boolean,
            msSinceConditionalImport: Long,
        ): KeyImportReason? {
            if (force) return KeyImportReason.FORCED
            if (listed.any { (id, _) -> id !in storedVersions }) return KeyImportReason.NEW_CIRCLE
            if (listed.any { (id, version) -> version > (storedVersions[id] ?: 0) }) {
                return KeyImportReason.KEY_VERSION_ADVANCED
            }
            if (msSinceConditionalImport < CONDITIONAL_KEY_IMPORT_COOLDOWN_MS) return null
            // The self key has its own owner (LocationRepository), and the import
            // deliberately skips it, so its absence is not a reason to import.
            val missing = listed.any { (id, _) ->
                id != SELF_CIRCLE_UUID && id != SELF_CIRCLE_ID && !hasKey(id)
            }
            if (missing) return KeyImportReason.MISSING_KEY
            if (requested) return KeyImportReason.REQUESTED
            return null
        }

        /**
         * The rows of [fresh] that differ from what was stored, re-stamped with
         * [nowMs]. A row equal to its stored copy — mapCircleResponse carries the
         * stored updatedAt over — is left out, so an unchanged refresh writes
         * nothing and wakes no Room observer.
         */
        internal fun rowsThatChanged(
            fresh: List<CircleEntity>,
            known: Map<String, CircleEntity>,
            nowMs: Long,
        ): List<CircleEntity> = fresh.mapNotNull { row ->
            val old = known[row.id]
            if (old != null && old == row.copy(updatedAt = old.updatedAt)) null
            else row.copy(updatedAt = nowMs)
        }

        /**
         * Circles whose session keys are worth prefetching: new to this device, or
         * whose circle key moved on. Everything, when forced.
         */
        internal fun sessionKeyPrefetchTargets(
            circles: List<CircleEntity>,
            known: Map<String, CircleEntity>,
            force: Boolean,
        ): List<CircleEntity> =
            if (force) circles
            else circles.filter { circle ->
                val old = known[circle.id]
                old == null || old.keyVersion != circle.keyVersion
            }

        /**
         * Identity of a pseudonym set for the backfill ledger: order-insensitive,
         * and bound to the user so one account's record can never vouch for
         * another's.
         */
        internal fun pseudonymSetDigest(userId: String, pseudonyms: List<String>): String {
            val canonical = buildString {
                append(userId)
                pseudonyms.sorted().forEach { append('\n').append(it) }
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Invalidate the tracking service's circle cache so new circles are picked up
     * on the next location update, and trigger an immediate location send.
     */
    private fun notifyTrackingServiceCirclesChanged() {
        try {
            val intent = android.content.Intent(context, com.pingolin.track.service.LocationTrackingService::class.java).apply {
                action = com.pingolin.track.service.LocationTrackingService.ACTION_UPDATE_SETTINGS
            }
            context.startService(intent)
        } catch (_: Exception) { /* service may not be running */ }
    }

    private val rotationFailures = mutableMapOf<String, Long>() // circleId -> last failure timestamp ms
    private val ROTATION_BACKOFF_MS = 5 * 60 * 1000L // 5 minutes

    /** One try per circle in this long at this account's own admission after a failure. */
    private val SELF_ADMISSION_RETRY_MS = 10 * 60 * 1000L

    /**
     * Rotations the plan found nothing to do for — a keyless join nobody has approved
     * — with when, and the cause they were planned for. The server keeps the
     * circle flagged until someone answers "Give access?", which can take days, and
     * re-planning costs the key list and a full roster fetch on every 5-minute refresh
     * of an owner's or admin's phone. So it is asked again only every
     * [SKIPPED_ROTATION_RETRY_MS] — the server's own re-flag cadence for keyless
     * members — or at once when the cause changes (somebody left) or the user answers.
     */
    private val skippedRotations = ConcurrentHashMap<String, Pair<Long, String?>>()
    private val SKIPPED_ROTATION_RETRY_MS = 30 * 60 * 1000L

    /**
     * Circles this device is mid-rotation on.
     *
     * The jitter + re-check below stops N DEVICES rewrapping in lockstep, but it
     * does not stop ONE device starting two rotations for the same circle: two
     * refresh paths plus the member_joined push can all call this within the
     * jitter window, all read keyRotationNeeded=true, and all proceed — two
     * concurrent rewraps reading the same current_version and racing the local
     * key-version bump. The loser's CAS fails, so the damage is wasted ECDH work
     * rather than corruption, but it is avoidable.
     *
     * A Set, checked-and-inserted under [rotationLock] so the check and the claim
     * cannot interleave. The iOS app has the equivalent (`rotationsInFlight`).
     */
    private val rotationsInFlight = mutableSetOf<String>()
    private val rotationLock = Any()

    /** True if this call claimed the rotation; false if another already holds it. */
    private fun claimRotation(circleId: String): Boolean = synchronized(rotationLock) {
        rotationsInFlight.add(circleId)
    }

    private fun releaseRotation(circleId: String) = synchronized(rotationLock) {
        rotationsInFlight.remove(circleId)
    }

    /** Last [ensureCircleKey] import, so a keyless circle can't fetch per call. */
    private var lastKeyHealAtMs = 0L
    private val KEY_HEAL_COOLDOWN_MS = 60 * 1000L // 1 minute

    /**
     * True when [circleId]'s key is usable — importing once if it isn't.
     *
     * A key missing at a guard usually means the circle was joined somewhere
     * else since this device last synced, which is fixable without asking the
     * user for anything. The sender heals itself the same way
     * ([com.pingolin.track.service.LocationTrackingService] on repeated send
     * failures, and the heartbeat worker), and the screens people actually
     * touch must too, rather than report a missing key as something to go and
     * fix.
     *
     * Callers keep their own failure message for the case where the import
     * genuinely doesn't produce the key: some of those strings are matched on
     * by [com.pingolin.track.worker.LocationHeartbeatWorker] to trigger its own
     * heal, so they are not free to reword.
     */
    suspend fun ensureCircleKey(circleId: String): Boolean {
        if (cryptoManager.hasCircleKey(circleId)) return true

        val now = System.currentTimeMillis()
        if (now - lastKeyHealAtMs < KEY_HEAL_COOLDOWN_MS) return false
        lastKeyHealAtMs = now

        return try {
            val imported = importCircleKeys().getOrNull() ?: 0
            Log.i(TAG, "ensureCircleKey: imported $imported circle keys")
            cryptoManager.hasCircleKey(circleId)
        } catch (e: Exception) {
            Log.w(TAG, "ensureCircleKey: import failed: ${e.message}")
            false
        }
    }

    // ===== Background refresh economy =====

    /** Set when a decrypt met a circle key this device does not hold; see [requestKeyReimport]. */
    private val keyReimportRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** When the last rate-limited import ran; see [KeyImportReason.rateLimited]. */
    @Volatile private var lastConditionalKeyImportAtMs = 0L

    /**
     * Ask the next [refreshCircles] to re-import circle keys even though the circle
     * list itself shows no change — for a decrypt that failed on a key (or key
     * version) this device does not hold. Rate-limited with the other conditional
     * reasons, so a row that can never decrypt cannot pin an import to every refresh.
     */
    fun requestKeyReimport() {
        keyReimportRequested.set(true)
    }

    /**
     * Where [backfillPseudonyms] remembers the last pseudonym set the server
     * accepted, per circle. Encrypted at rest like every other per-user store here;
     * tests swap in memory.
     */
    internal var backfillLedger: PseudonymBackfillLedger = SecurePrefsBackfillLedger(context)

    private val _paginationState = MutableStateFlow(CirclesPaginationState())
    val paginationState: StateFlow<CirclesPaginationState> = _paginationState.asStateFlow()

    /**
     * Queue of TOFU pin-divergence alerts. Updated by [refreshMembers]
     * whenever a member's sharing or signing pubkey doesn't match what we
     * already have pinned. The UI observes this and pops a confirm dialog
     * for each entry; the user's approve/block choice updates the SDK pin
     * record (or doesn't), and the alert is then removed via [dismissMemberKeyAlert].
     */
    private val _memberKeyAlerts = MutableStateFlow<List<MemberKeyAlert>>(emptyList())
    val memberKeyAlerts: StateFlow<List<MemberKeyAlert>> = _memberKeyAlerts.asStateFlow()

    /** Userspace lookup of latest signing pubkey per (circleId, userId).
     *  Populated by [refreshMembers] and consulted by store/wrap call sites
     *  that need a recipient signing pubkey for the verify-on-store /
     *  sign-on-wrap path. Doesn't replace the SDK's pin store — it's a
     *  best-effort cache so we don't have to re-fetch from the server. */
    // ConcurrentHashMap: refreshMembers populates this off the Main thread
    // (Dispatchers.Default) and runs concurrently per-circle, so writes here can
    // race with reads from store/wrap call sites.
    private val signingPubkeyCache = ConcurrentHashMap<String, String>()

    /** Companion to [signingPubkeyCache]: latest sharing (X25519) pubkey
     *  per member. Used by [MemberDetailFragment] to compute the SAS without
     *  re-hitting the server. */
    private val sharingPubkeyCache = ConcurrentHashMap<String, String>()

    private fun cacheKey(circleId: String, userId: String) = "$circleId:$userId"

    /** Cached recipient signing pubkey, or empty string if not cached.
     *  SDK accepts an empty string as "unknown" without binding it into
     *  the wrap signature input. */
    private fun cachedSigningPubkey(circleId: String, userId: String): String =
        signingPubkeyCache[cacheKey(circleId, userId)].orEmpty()

    /** Per-circle "can the current user rotate keys?" cache, populated from
     *  the `can_rotate_keys` field on the member-list endpoint. Used by
     *  [autoRotateIfNeeded] to gate the rotation hook so non-eligible
     *  clients don't compete in the rotation lottery. */
    // ConcurrentHashMap: refreshMembers/autoRotateIfNeeded write/read this from
    // background coroutines while wipeInMemory() clears it from the logout/
    // force-logout thread, so access can race. (Stores only non-null Boolean.)
    private val canRotateKeysCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Why each circle this process last saw as locked is locked, from
     * [decryptCircleName]; an entry goes when the circle's name decrypts. The name a
     * locked circle shows cannot say why, and the explanation depends on it.
     */
    private val lockedCircleOutcomes = ConcurrentHashMap<String, LockedCircle.WrapOutcome>()

    /**
     * Circles whose name this process has decrypted (or created). Lets
     * [isCircleLocked] tell a circle somebody really called "Locked circle" from one
     * this device cannot open, which matters most to rotation: it re-encrypts the name
     * and must never do so from a placeholder.
     */
    private val readableCircleIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * `key_rotation_cause` per circle from the last circle list, while a rotation is
     * pending. After a departure the person who left still holds the live key until
     * the circle is re-keyed, and owners and admins are told so.
     */
    private val _keyRotationCauses = MutableStateFlow<Map<String, String>>(emptyMap())
    val keyRotationCauses: StateFlow<Map<String, String>> = _keyRotationCauses.asStateFlow()

    /** Record (or, with null, clear) why [circleId]'s rotation is pending. */
    internal fun noteRotationCause(circleId: String, cause: String?) {
        _keyRotationCauses.update { causes ->
            if (cause != null) causes + (circleId to cause) else causes - circleId
        }
    }

    /** Circles whose key moved on without this device; see [CurrentKeyGate]. */
    val behindCircles: StateFlow<Set<String>> = currentKeyGate.behind

    /** See [CurrentKeyGate.recordsBehind]: waiting to sync by this device's records, not locked. */
    val recordsBehindCircles: StateFlow<Set<String>> = currentKeyGate.recordsBehind

    /** See [CurrentKeyGate.holdsCurrentKey]: nothing new is sealed for a circle without it. */
    suspend fun holdsCurrentKey(circleId: String): Boolean = currentKeyGate.holdsCurrentKey(circleId)

    /** See [CurrentKeyGate.requireCurrentKey]. */
    suspend fun requireCurrentKey(circleId: String) = currentKeyGate.requireCurrentKey(circleId)

    /** Who this user has given access to each circle's key; see [KeyAccessApprovals]. */
    internal var keyAccessApprovals: com.pingolin.track.util.KeyAccessApprovals =
        com.pingolin.track.util.SecurePrefsKeyAccessApprovals(context, { sessionManager.getUserId() })

    /** Owner/admin questions about members this device cannot confirm — see [KeyAccessRequest]. */
    private val _keyAccessRequests = MutableStateFlow<List<KeyAccessRequest>>(emptyList())
    val keyAccessRequests: StateFlow<List<KeyAccessRequest>> = _keyAccessRequests.asStateFlow()

    /** "circleId:userId" answered Not now, this process: asked again on the next launch. */
    private val keyAccessDeferred: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "Is this really {email}?" about invitations this account made. */
    private val _inviteRenewalQuestions = MutableStateFlow<List<InviteRenewalQuestion>>(emptyList())
    val inviteRenewalQuestions: StateFlow<List<InviteRenewalQuestion>> = _inviteRenewalQuestions.asStateFlow()

    /** When each circle's invitations were last looked at for a renewal; tests clear it. */
    internal val renewalLooks = ConcurrentHashMap<String, Long>()

    /** Invite id → its statement id, from the owner's list: what withdrawing marks dead. */
    private val inviteStatementIds = ConcurrentHashMap<String, String>()

    /** Signed membership statements; tests swap in a stand-in. */
    internal var membershipStatements: MembershipStatements = SdkMembership(cryptoManager)

    /** This device's pins, read without recording anything; tests swap in a stand-in. */
    internal var memberPins: MemberPins = SdkMembership(cryptoManager)

    /** A circle's first key from a confirmed member only; tests swap in a stand-in. */
    internal var firstKeys: FirstKeys = SdkMembership(cryptoManager)

    /** Invitations agreed to but not acceptable yet; tests swap in a stand-in. */
    internal var pendingInviteAccepts: com.pingolin.track.util.PendingInviteAccepts =
        com.pingolin.track.util.SecurePrefsPendingInviteAccepts(context, { sessionManager.getUserId() })

    /** The keys this device's person confirmed with "It's them". */
    internal var personConfirmedKeys: com.pingolin.track.util.PersonConfirmedKeys =
        com.pingolin.track.util.SecurePrefsPersonConfirmedKeys(context, { sessionManager.getUserId() })

    /** The invitations this device's person answered the late-joiner question about. */
    internal var lateJoinerAnswers: LateJoinerAnswers = SecurePrefsLateJoinerAnswers(context, { sessionManager.getUserId() })

    /** What this device spent of its repair budget, kept across restarts. */
    internal var repairBudget: com.pingolin.track.util.RepairBudget =
        com.pingolin.track.util.SecurePrefsRepairBudget(context, { sessionManager.getUserId() })

    /** Members this user removed without a key change, until its own next rotation names them. */
    internal var ownRemovals: com.pingolin.track.util.OwnRemovals =
        com.pingolin.track.util.SecurePrefsOwnRemovals(context, { sessionManager.getUserId() })

    /** Role changes this user made in circles with records, until its own rotation records them. */
    internal var ownerRoleChanges: com.pingolin.track.util.OwnerRoleChanges =
        com.pingolin.track.util.SecurePrefsOwnerRoleChanges(context, { sessionManager.getUserId() })

    /** The owner's hand-overs this device recorded, until the server's transfer lands. */
    internal var handOvers: com.pingolin.track.util.HandOvers =
        com.pingolin.track.util.SecurePrefsHandOvers(context, { sessionManager.getUserId() })

    /**
     * An account signed in on this phone: every other account's entries in the stores kept per
     * account go, held changes too; [userId]'s own stay, from its last sign-out
     * (com.pingolin.track.util.KeptPerAccount, as the iOS app's RefreshLedger). What holds trust
     * (owed removals, owed role changes) goes for another account only where [mayDelete] says so
     * for it: the typed-email guard (RetainedTrust.onSignIn), whose key-access approvals and kept
     * pins are RetainedTrust's.
     */
    fun keepOnlyAccount(userId: String, mayDelete: (String) -> Boolean) {
        holdingTrust().forEach { runCatching { it.keepOnly(userId, mayDelete) } }
        keptPerAccount().forEach { runCatching { it.keepOnly(userId) } }
    }

    /** This account was erased: no account's entries stay in those stores. */
    fun wipeAccountEntries() = (holdingTrust() + keptPerAccount()).forEach { runCatching { it.wipeAll() } }

    private fun holdingTrust(): List<com.pingolin.track.util.KeptPerAccount> = listOf(ownRemovals, ownerRoleChanges, sosMarks)

    /**
     * What this device saw of each SOS. An "ended" is an obligation: kept across this account's
     * sign-out and dropped for another account only where the typed-email guard says so, as owed
     * removals ([holdingTrust]); gone at erasure.
     */
    internal var sosMarks: com.pingolin.track.util.SosMarks =
        com.pingolin.track.util.SecurePrefsSosMarks(context, { sessionManager.getUserId() })

    /**
     * What this phone sealed into its own SOS. Coordinates: gone with the account's other
     * positions at sign-out (AuthRepository.clearLocalData), not kept across it like the
     * marks.
     */
    internal var ownSosSeals: com.pingolin.track.domain.sos.OwnSosSeals =
        com.pingolin.track.domain.sos.SecurePrefsOwnSosSeals(context, { sessionManager.getUserId() })

    /**
     * What this phone last sealed and sent each circle: the record a re-seal of its frozen last
     * location is built from. Coordinates: gone with the circle (LocationRepository's
     * forgetCircleLocations) and at sign-out (AuthRepository.clearLocalData).
     */
    internal var ownLastSent: com.pingolin.track.location.OwnLastSentStore =
        com.pingolin.track.location.SecurePrefsOwnLastSent(context, { sessionManager.getUserId() })

    /** This account's frozen last location, sealed again under a circle's new key. */
    internal val lastKnownReseal: com.pingolin.track.location.LastKnownReseal by lazy {
        com.pingolin.track.location.LastKnownReseal(
            store = ownLastSent,
            api = tracksApi,
            crypto = cryptoManager,
            privacyOf = { privacySettingDao.get(it) },
            me = { sessionManager.getUserId() },
            // A re-seal is a send: never for an account that may not track (TrackingStop).
            accessOpen = { sessionManager.shouldBeTracking() },
            // Never finer than the circle gets now.
            coarsenRadiusNow = { locationRepository.get().coarsenRadiusMetersFor(it, System.currentTimeMillis()) },
        )
    }

    /** Where re-seals run off the caller's path; tests run them on their own scheduler. */
    internal var resealScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    /** [circleIds]' frozen rows re-sealed where one is due, off the caller's path (a new key version here). */
    private fun scheduleLastKnownReseal(circleIds: Collection<String>) {
        if (circleIds.isEmpty()) return
        resealScope.launch { for (circleId in circleIds) lastKnownReseal.run(circleId) }
    }

    private fun keptPerAccount(): List<com.pingolin.track.util.KeptPerAccount> = listOf(
        repairBudget, lateJoinerAnswers, handOvers, pendingInviteAccepts, personConfirmedKeys, pendingRenewalQuestions,
        pauseIntervals, approximateIntervals,
    )

    /** "Is this really {email}?" raised and not answered yet, kept across restarts. */
    internal var pendingRenewalQuestions: com.pingolin.track.util.PendingRenewalQuestions =
        com.pingolin.track.util.SecurePrefsPendingRenewalQuestions(context, { sessionManager.getUserId() })

    /** Where a question only a person can answer is posted; tests swap in a stand-in. */
    internal var questionNotifier: com.pingolin.track.util.MembershipQuestionNotifier =
        com.pingolin.track.util.AndroidMembershipQuestionNotifier(context, sessionManager)

    init {
        // Writes that need a circle's current copy wait for it a little instead of failing.
        currentKeyGate.catchUp = { circleId -> catchUp(circleId) }
        // Seals wait for a re-key this device owes, as on iPhone.
        currentKeyGate.reKeyPending = { circle -> reKeysHere(circle) }
        // A restore that opened something: the circles load again.
        previousIdentityLoader.onNewlyOpened = {
            runCatching { importCircleKeys() }
            runCatching { refreshCircles() }
        }
    }

    /** The `identities_check` push: look again for identities from before a reset. */
    suspend fun onIdentitiesCheck() = previousIdentityLoader.nudge()

    /**
     * The app came to the foreground: look again while a circle here is still locked since a
     * reset — its copy there doesn't open with this account's keys — or when a re-check is
     * pending.
     */
    suspend fun onForegroundIdentities() = previousIdentityLoader.onForeground(
        lockedSinceReset = lockedCircleOutcomes.values.any { it == LockedCircle.WrapOutcome.UNOPENABLE },
    )

    /** Pauses between looks while [catchUp] waits; tests shorten them. */
    internal var catchUpPausesMs: List<Long> = listOf(1_500L, 1_500L)

    /**
     * Catch up on [circleId] while the person waits: ask the other members' apps for the
     * current copy and look for it for about three seconds, long enough to take a handover
     * already in flight. True when it arrived; the caller then does what the person asked.
     * Otherwise what they asked waits as a change that goes out by itself once this phone
     * catches up, instead of a spinner.
     */
    internal suspend fun catchUp(circleId: String): Boolean {
        if (currentKeyGate.holdsCurrentKey(circleId)) return true
        requestFreshAccess(circleId)
        for (pause in catchUpPausesMs) {
            kotlinx.coroutines.delay(pause)
            runCatching { importCircleKeys() }
            if (currentKeyGate.holdsCurrentKey(circleId)) return true
        }
        Log.i(TAG, "Still behind on $circleId after waiting; the person is told")
        return false
    }

    /** Each circle's sealed name and the version it is sealed under, as the circle list last showed. */
    private val sealedNames = ConcurrentHashMap<String, Pair<EncryptedPayload, Int?>>()

    /** Seconds since the epoch, for invitation expiry; tests move it. */
    internal var nowSecs: () -> Long = { System.currentTimeMillis() / 1000 }

    /** The last complete member list of each circle, as this device judged it; see [applyMemberTrust]. */
    private val trustSnapshots = ConcurrentHashMap<String, CircleTrustSnapshot>()

    private val _memberStates = MutableStateFlow<Map<String, CircleMemberStates>>(emptyMap())

    /** "Joining…" and the owner/admin "kept out" line, per circle. */
    val memberStates: StateFlow<Map<String, CircleMemberStates>> = _memberStates.asStateFlow()

    /** When this process last asked for a fresh copy of each circle. */
    private val accessRequestedAt = ConcurrentHashMap<String, Long>()

    /** This device's last update request per circle/member: when, and whether it was taken. */
    private val updateRequests = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** Who sent this device its first copy of each circle, this process. */
    private val firstKeySenders = ConcurrentHashMap<String, String>()


    /** At a cold start, a database that can't be opened shows no circles, and says so. */
    fun observeCircles(): Flow<List<CircleEntity>> = circleDao.observeAll().orWhenUnopenable(emptyList())

    /** While the database can't be opened, nothing, and a look again: a null would read as "gone". */
    fun observeCircle(circleId: String): Flow<CircleEntity?> = circleDao.observeById(circleId).retryWhenUnopenable()

    fun observeMembers(circleId: String): Flow<List<CircleMemberEntity>> =
        circleMemberDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /** [userId]'s row in [circleId]'s member list as last refreshed, or null. */
    suspend fun member(circleId: String, userId: String): CircleMemberEntity? =
        circleMemberDao.get(circleId, userId)

    /** [circleId]'s member list as last refreshed. */
    suspend fun storedMembers(circleId: String): List<CircleMemberEntity> =
        circleMemberDao.getByCircle(circleId)

    /**
     * This user's own membership row in every circle whose roster is cached
     * locally — the only place their ROLE is recorded. [observeCircles] carries
     * a server-authoritative `isOwner` boolean and nothing else, so "admin"
     * cannot be answered from it.
     *
     * Best-effort by construction: nothing refreshes `circle_members` in the
     * background, so a circle is absent from this until something has fetched
     * its roster — MainViewModel.refreshData fans refreshMembers out over every
     * circle once per app start, which is what normally supplies it. Absence
     * therefore means "unknown", NOT "plain member": callers may use this to
     * OFFER a control and must never use it to withhold one.
     *
     * The user id is read at COLLECTION time rather than when this is called,
     * so a flow held across a re-login observes whoever is actually signed in.
     */
    fun observeMyMemberships(): Flow<List<CircleMemberEntity>> = flow {
        val userId = sessionManager.getUserId()
        if (userId == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(circleMemberDao.observeMemberships(userId))
    }.orWhenUnopenable(emptyList()) // covers the session's read too

    suspend fun getCircles(): List<CircleEntity> = circleDao.getAll()

    /**
     * Persist the device-local Circles-tab ordering. Each circle's index in
     * the list becomes its sortOrder; observeAll()/getAll() then return circles
     * in this order. Stored in a separate table, so server circle refreshes
     * never clobber it.
     */
    suspend fun updateCircleSortOrder(ordered: List<CircleEntity>) {
        circleSortOrderDao.setOrder(
            ordered.mapIndexed { index, circle -> CircleSortOrderEntity(circle.id, index) }
        )
    }

    suspend fun getCircle(circleId: String): CircleEntity? = circleDao.getById(circleId)

    /** The sender to pass for a wrap: its signer on record, or us when there is none. */
    private fun ownSlotSender(signer: String?, selfUserId: String): String =
        signer?.takeIf { it.isNotBlank() } ?: selfUserId

    /** "circleId:version" of own wraps this process has already signed (or tried to). */
    private val signedOwnWraps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Sign the server's unsigned copies of our own wraps. Every creation wrap and
     * every legacy self-labelled slot is unsigned, and a device that accepts "ours"
     * unsigned lets the server install a key of its choosing; signed, it verifies
     * under our own key. The SDK signs only a wrap that opens to the key this device
     * already holds for that version, so the server can never obtain our signature
     * over a key it chose. Once per wrap per process; best-effort.
     */
    private suspend fun signOwnWraps(bundles: List<CircleKeyBundleResponse>) {
        val selfUserId = sessionManager.getUserId() ?: return
        for (bundle in bundles) {
            if (!signedOwnWraps.add("${bundle.circleId}:${bundle.keyVersion}")) continue
            // A key taken on trust at first contact is never signed as ours (the SDK refuses it
            // too): our signature would launder a key the server may have chosen.
            if (runCatching { cryptoManager.circleKeyTakenOnTrust(bundle.circleId, bundle.keyVersion) }.getOrDefault(false)) {
                Log.i(TAG, "Own wrap for ${bundle.circleId} v${bundle.keyVersion} not signed: the key was taken on trust")
                continue
            }
            val encryptedKey = "${bundle.encryptedCircleKey.nonce}${bundle.encryptedCircleKey.ciphertext}"
            val signature = try {
                cryptoManager.signOwnCircleKeyWrap(
                    bundle.circleId, selfUserId, bundle.keyVersion, encryptedKey, bundle.ephemeralPublicKey,
                )
            } catch (e: Exception) {
                // Most often: this wrap does not open to the key we hold, which is
                // exactly the one we must never vouch for.
                Log.w(TAG, "Own wrap for ${bundle.circleId} v${bundle.keyVersion} not signed: ${e.message}")
                null
            } ?: continue
            uploadOwnWrapSignature(
                bundle.circleId, bundle.keyVersion, bundle.encryptedCircleKey, bundle.ephemeralPublicKey, signature,
            )
        }
    }

    private suspend fun uploadOwnWrapSignature(
        circleId: String,
        keyVersion: Int,
        wrap: EncryptedPayload,
        ephemeralPublicKey: String,
        signature: String,
    ) {
        try {
            val response = tracksApi.signOwnWrap(
                circleId,
                com.pingolin.track.data.remote.dto.OwnWrapSignatureRequest(
                    keyVersion = keyVersion,
                    encryptedCircleKey = wrap,
                    ephemeralPublicKey = ephemeralPublicKey,
                    wrapSignature = signature,
                ),
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Signed our own wrap for $circleId v$keyVersion")
            } else {
                // 409 key_wrap_changed: a rotation replaced it meanwhile. Nothing to do.
                Log.w(TAG, "Own wrap signature for $circleId v$keyVersion not stored: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Own wrap signature for $circleId v$keyVersion not sent: ${e.message}")
        }
    }

    /** A circle's first copy arrived before this device could judge its member list: taken later. */
    private class FirstCopyNotYet(circleId: String) : Exception("first copy of $circleId waits for its member list")

    /** [circleId] names the personal key's slot ([personalSlot]): refused wherever a circle is meant. */
    private class NotACircle(circleId: String) : Exception("$circleId is the personal key's slot, not a circle")

    /**
     * Whether [circleId] names the personal key's slot (AccountIds.isPersonalSlot): the nil UUID in
     * any spelling, or "self". It is no circle, whatever the server lists: nothing is wrapped,
     * stored, linked, derived or latched for it as a circle's. The decoder refuses it on the way
     * in; [what] refuses it again here.
     */
    private fun personalSlot(circleId: String, what: String): Boolean {
        if (!com.pingolin.track.util.AccountIds.isPersonalSlot(circleId)) return false
        Log.w(TAG, "$what: the personal key's slot named as a circle; refused")
        return true
    }

    /**
     * Store a copy of [circleId]'s key. The FIRST copy this device takes for a circle comes
     * only from a member confirmed here: its wrap must verify under that member's pinned key,
     * and when the circle's name is sealed at this version the key must open it. With nobody
     * confirmed and nothing to start from, the first copy is taken on trust once, and its
     * sender anchors this device, except where this account's own invitation names an address
     * this device doesn't hold as its own: that copy waits for the address (the e-mail trust
     * check). Anything else is refused ([MobileException.SignatureInvalid]) and the members are
     * asked for a fresh copy. Every later copy goes through storeCircleKey, which holds it to
     * the pins.
     */
    private fun storeCopy(
        circleId: String,
        encryptedKey: String,
        ephemeralPublicKey: String,
        keyVersion: Int,
        senderUserId: String,
        recipientUserId: String,
        wrapSignature: String?,
        senderSigningHint: String?,
    ) {
        if (personalSlot(circleId, "copy")) throw NotACircle(circleId)
        val hasKey = runCatching { cryptoManager.hasCircleKey(circleId) }.getOrDefault(true)
        if (hasKey || senderUserId.equals(recipientUserId, ignoreCase = true)) {
            cryptoManager.storeCircleKey(
                circleId, encryptedKey, ephemeralPublicKey, keyVersion, senderUserId, recipientUserId,
                wrapSignature, senderSigningHint,
            )
            return
        }
        val snapshot = trustSnapshots[circleId] ?: throw FirstCopyNotYet(circleId)
        val confirmedSender = snapshot.result.confirmed.keys
            .firstOrNull { it.equals(senderUserId, ignoreCase = true) }
            ?.takeUnless { snapshot.result.receiveOnly(it) }
        val confirmedKey = confirmedSender?.let { memberPins.pinnedSigningKey(circleId, it) }
        when {
            confirmedKey != null && !wrapSignature.isNullOrBlank() -> {
                val sealedName = sealedNames[circleId]
                    ?.takeIf { (_, sealedAt) -> sealedAt == keyVersion }
                    ?.first?.let { EncryptedData(nonce = it.nonce, ciphertext = it.ciphertext) }
                firstKeys.storeFirstCircleKey(
                    circleId, encryptedKey, ephemeralPublicKey, keyVersion, senderUserId, recipientUserId,
                    wrapSignature, confirmedKey, sealedName,
                )
            }
            !snapshot.result.anchored -> {
                // This account's own invitation names an address this device doesn't hold as this
                // account's (none, or another): the invitation can't anchor its signer here, and
                // nothing is taken on trust in its place. The copy waits until the address is known
                // (AuthRepository.validateSession keeps it current).
                if (snapshot.result.ownEmailInvitationUnconfirmed) {
                    Log.w(TAG, "$circleId: this account's invitation names another address than the one here; its first copy waits")
                    throw FirstCopyNotYet(circleId)
                }
                cryptoManager.storeCircleKey(
                    circleId, encryptedKey, ephemeralPublicKey, keyVersion, senderUserId, recipientUserId,
                    wrapSignature, senderSigningHint,
                )
                firstKeySenders[circleId] = senderUserId
            }
            else -> throw MobileException.SignatureInvalid("first copy of $circleId is not from a member confirmed here")
        }
    }

    /**
     * Fetch and import all circle keys from the server.
     * This uses oblivious key distribution - all keys are fetched at once
     * so the server can't tell which specific circle the user is interested in.
     * Should be called after login and when refreshing circles.
     */
    suspend fun importCircleKeys(): Result<Int> {
        return try {
            // Make any RECOVERED pre-reset identity available BEFORE unwrapping.
            // After an email password reset every existing wrap was made for the
            // old public key, so without this the bundles below all fail to
            // unwrap and the circles go silent with their data intact.
            previousIdentityLoader.ensureLoaded()

            val response = tracksApi.getCircleKeys()
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val keyBundles = response.body()!!.data!!
                // The version each circle is sealed under before this import: one that moves takes
                // this account's own open SOS there along (the raiser's reseal).
                val sealedUnder = keyBundles.map { it.circleId }.distinct()
                    .filterNot { com.pingolin.track.util.AccountIds.isPersonalSlot(it) }
                    .associateWith { heldVersion(it) }
                // Our own wraps the server holds unsigned, found during the import and
                // signed after it (network calls stay out of the bulk-import window).
                val unsignedOwnWraps = mutableListOf<CircleKeyBundleResponse>()
                // Circles whose copy this device refused or cannot open: the other members
                // are asked for a fresh one after the import.
                val unusable = mutableSetOf<String>()
                // Per-bundle ECDH unwrap (storeCircleKey) + key-chain derivation is
                // pure CPU (UniFFI); move it off the Main thread — refreshCircles
                // calls this on the caller's (viewModelScope) dispatcher.
                val importedCount = withContext(Dispatchers.Default) {
                    // Defer the per-storeCircleKey/importKeyChainLink encrypted
                    // key-persist writes and flush once for the whole bulk import.
                    // try/finally guarantees the deferral is always released even if
                    // a bundle throws — a missed end would wedge all future persists.
                    cryptoManager.beginBulkKeyImport()
                    try {
                    var count = 0
                    for (bundle in keyBundles) {
                    try {
                        // Skip bundles with empty keys (member hasn't completed E2EE key exchange)
                        if (bundle.ephemeralPublicKey.isNullOrBlank() ||
                            bundle.encryptedCircleKey.nonce.isNullOrBlank() ||
                            bundle.encryptedCircleKey.ciphertext.isNullOrBlank()) {
                            android.util.Log.d("CircleRepository", "Skipping circle ${bundle.circleId}: incomplete key exchange")
                            continue
                        }

                        // The self key is NOT ours to store.
                        //
                        // It lives in TWO slots — "self" and the nil UUID — and
                        // different readers use different ones: PlaceRepository
                        // unwraps personal place keys via "self", while
                        // sendEnvelopeLocation normalises to the nil UUID and
                        // encrypts with that. Writing one slot here from the
                        // server on every sync, while the other is left alone,
                        // would let the two slots hold DIFFERENT KEYS once the
                        // server's copy stops matching the device's: places read
                        // with one key and locations written with the other, so
                        // personal places become undecryptable while the
                        // location pseudonym never changes and everything else
                        // looks healthy.
                        //
                        // One owner: LocationRepository.ensurePersonalKeyExists /
                        // syncSelfKey, which writes both slots together and
                        // checks the key against the pseudonym the server has
                        // recorded. Skipping it here cannot strand anyone —
                        // every consumer of the self key calls that first.
                        if (com.pingolin.track.util.AccountIds.isPersonalSlot(bundle.circleId)) {
                            android.util.Log.d(
                                "CircleRepository",
                                "Skipping self key here — LocationRepository owns both of its slots"
                            )
                            continue
                        }
                        val circleId = bundle.circleId
                        // For the records import after this one: the links it walks.
                        lastKeyChains[circleId] = bundle.keyChain.orEmpty()

                        // Always attempt to store — storeCircleKey handles version
                        // updates and is idempotent for the same key.
                        // SDK expects hex(nonce || ciphertext) - concatenate without separator
                        val encryptedKey = "${bundle.encryptedCircleKey.nonce}${bundle.encryptedCircleKey.ciphertext}"
                        // Verify-on-store. Resolve the sender's signing key from our
                        // own locally-pinned member directory — NOT the
                        // server-supplied `bundle.senderSigningPubkey`. Verifying
                        // against a signer key the same relay delivered alongside
                        // the wrap is worthless: a malicious relay would just sign
                        // its forged wrap with a matching key. When the sender isn't
                        // pinned yet (cache miss) we pass null and the SDK
                        // grace-accepts, so an unpinned sender is never a lockout.
                        val recipientUserId = sessionManager.getUserId().orEmpty()
                        // A wrap with no signer on record is labelled ours, as the SDK
                        // asks: it can then be accepted only as first contact or as the
                        // key already held, and — signed — only under our own key.
                        val senderUserId = ownSlotSender(bundle.senderUserId, recipientUserId)
                        val senderSigningPubkey =
                            bundle.senderUserId?.let { cachedMemberSigningPubkey(circleId, it) }
                        try {
                            storeCopy(
                                circleId,
                                encryptedKey,
                                bundle.ephemeralPublicKey,
                                bundle.keyVersion,
                                senderUserId,
                                recipientUserId,
                                bundle.wrapSignature,
                                senderSigningPubkey,
                            )
                            if (senderUserId == recipientUserId && bundle.wrapSignature.isNullOrBlank()) {
                                unsignedOwnWraps += bundle
                            }
                        } catch (e: FirstCopyNotYet) {
                            Log.i(TAG, "First copy of $circleId left for after its member list is judged")
                            continue
                        } catch (e: MobileException.SignatureInvalid) {
                            // Not signed by anyone this device confirms in the circle — a
                            // tampered copy, one from an unconfirmed member, or one signed
                            // with a key this device has not accepted. Reject it: skip the
                            // whole bundle so we neither store the key nor derive its chain,
                            // and ask the members for a copy from someone confirmed here.
                            Log.w(TAG, "Wrap signature INVALID for circle $circleId v${bundle.keyVersion} (sender=${bundle.senderUserId}); REJECTING key")
                            unusable += circleId
                            continue
                        } catch (e: MobileException.DecryptionFailed) {
                            // Made for keys this account no longer has (a password reset):
                            // it cannot be opened here.
                            Log.w(TAG, "Copy of circle $circleId v${bundle.keyVersion} does not open with this account's keys")
                            unusable += circleId
                            continue
                        }

                        // Import key chain links to derive previous key
                        // versions. Each link's `from_version` must be
                        // stored locally before its `to_version` can be
                        // derived. The server returns the chain in
                        // INSERTION order (oldest rotation first), so a
                        // single-pass loop would only succeed on the link
                        // whose from_version is the active key we just
                        // stored — every older link's prerequisite has
                        // not been derived yet and would fail silently.
                        // Iterate until no further link succeeds; each
                        // round can unblock the next layer back.
                        bundle.keyChain?.let { links ->
                            val pending = links.toMutableList()
                            var madeProgress = true
                            while (madeProgress && pending.isNotEmpty()) {
                                madeProgress = false
                                val iter = pending.iterator()
                                while (iter.hasNext()) {
                                    val link = iter.next()
                                    try {
                                        cryptoManager.importKeyChainLink(
                                            circleId, link.fromVersion, link.toVersion,
                                            link.encryptedKey, link.nonce
                                        )
                                        iter.remove()
                                        madeProgress = true
                                    } catch (_: Exception) {
                                        // Will retry on the next pass if a
                                        // prerequisite is derived this round.
                                    }
                                }
                            }
                            if (pending.isNotEmpty()) {
                                Log.w(TAG, "Could not import ${pending.size} of ${links.size} chain links for circle $circleId (broken chain or missing root key)")
                            }
                        }

                        count++
                    } catch (e: Exception) {
                        // Stale key for deleted circle or membership revoked — not a critical error
                        android.util.Log.d("CircleRepository", "Skipped key for circle ${bundle.circleId}: ${e.message}")
                    }
                    }
                    count
                    } finally {
                        cryptoManager.endBulkKeyImport()
                    }
                }

                android.util.Log.d("CircleRepository", "Imported $importedCount circle keys")
                signOwnWraps(unsignedOwnWraps)
                for (circleId in unusable) requestFreshAccess(circleId)
                // Every key import is followed by the records check.
                syncRefreshRecords()
                val moved = sealedUnder.filter { (circleId, was) -> heldVersion(circleId)?.let { now -> was == null || now > was } == true }.keys
                if (moved.isNotEmpty()) {
                    // This import is the key read the re-seal needs.
                    runCatching { sosRepository.get().resealOwn(moved, keysRead = true) }
                        .onFailure { Log.w(TAG, "Reseal after a key import failed: ${it.javaClass.simpleName}") }
                    // And this account's frozen last location there, where its real-time sharing is off.
                    scheduleLastKnownReseal(moved)
                    // And its name, which a newcomer can't open under the older key.
                    scheduleOwnNameSeal(moved)
                    // Another member's SOS there that waited on this key is judged again now.
                    runCatching { sosRepository.get().keysMoved(moved) }
                }
                Result.success(importedCount)
            } else {
                Result.failure(Exception("Failed to fetch circle keys"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Is this user still a member of [circleId], according to the authoritative
     * circle LIST rather than one endpoint's response code?
     *
     * `true` still a member · `false` definitively not · `null` couldn't tell.
     *
     * Exists because a 404 from a per-circle endpoint is AMBIGUOUS: "you were
     * removed from this circle" and "that pod is mid-restart" are indistinguishable
     * at the call site, and a rolling deploy produces the second routinely. Deleting
     * local state on the strength of one such response would cost a device its
     * circle — it would stop publishing location there entirely and tell the user
     * it had been removed. `GET /circles` is the one place that actually knows.
     *
     * Callers MUST treat null as "do not delete". Absence of knowledge is not
     * knowledge of absence — the same rule the place-alert roster scoping follows.
     * So absent is `false` only from a list the server gave whole: one with more
     * pages may have left the circle out (past 50 circles; see [refreshCircles]).
     */
    suspend fun isStillAMember(circleId: String): Boolean? {
        val circles = refreshCircles().getOrNull() ?: return null
        if (circles.any { it.id == circleId }) return true
        return if (lastWholeCircleIds != null) false else null
    }

    /**
     * The circle list's pages after [first], as far as they can be had: up to a page that
     * fails, and at most [MAX_CIRCLE_PAGES] in all.
     */
    private suspend fun laterCirclePages(
        first: com.pingolin.track.data.remote.dto.PaginatedResponse<CircleResponse>,
    ): List<CircleResponse> {
        val later = mutableListOf<CircleResponse>()
        var page = first
        var pages = 1
        while (page.hasMore) {
            val cursor = page.cursor ?: break
            if (++pages > MAX_CIRCLE_PAGES) break
            val next = runCatching { tracksApi.getCirclesPaginated(cursor = cursor, limit = CIRCLE_PAGE_SIZE) }.getOrNull()
            page = next?.body()?.takeIf { next.isSuccessful && it.success }?.data ?: break
            later += page.items
        }
        return later
    }

    /**
     * The key version this device holds for [circleId] now, kept with a change that signs when
     * the person decides (a waiting change ends if the member was ejected after it).
     */
    private fun heldVersion(circleId: String): Int? = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrNull()

    /** Every circle id of the last circle list the server gave WHOLE; see [refreshCirclesWhole]. */
    @Volatile private var lastWholeCircleIds: Set<String>? = null

    /**
     * [refreshCircles], then every circle id its list had when the server gave it WHOLE (no
     * further page). Only such a list says a circle is gone for this account: a 404, a failed
     * refresh or a list with more pages says nothing. Null in those cases.
     */
    suspend fun refreshCirclesWhole(): Set<String>? {
        refreshCircles().getOrNull() ?: return null
        return lastWholeCircleIds
    }

    /**
     * Pull the circle list and bring local state up to date with it.
     *
     * The location service calls this every 5 minutes (refreshCachedData), so the
     * default is the CHEAP path. The list itself is fetched every time — it is one
     * small call (more only past 50 circles), and the send path's targets come from
     * it — but the expensive work needs a reason:
     *
     *  - the oblivious key import (GET /circles/keys, then an ECDH unwrap and a
     *    whole encrypted key-file rewrite per bundle) runs only when a circle is
     *    new, its key version moved, a key is missing, or a decrypt asked for one
     *    ([keyImportReason], [requestKeyReimport]);
     *  - session keys are prefetched only for new circles or a key-version change.
     *    The decrypt path already fetches an unknown session key on demand
     *    (LocationRepository.decryptLocationResponseWithResult), so this is only
     *    a head start;
     *  - the pseudonym backfill is sent only when the set it would send changed
     *    since the last one the server accepted ([backfillPseudonyms]);
     *  - Room rows are written only when their content changed. Stamping
     *    updatedAt on every row every time would re-emit every circles observer
     *    every 5 minutes and restart the map's 30 s poll in the background.
     *
     * The per-circle privacy sync still runs on every call. A pause set on another
     * device reaches this one through it: at once on the `privacy_check` wake
     * (PrivacyCheckWorker), else at the next 5-minute pass. Ingest drops what
     * arrives during a pause, but slowing this would still keep this phone sending.
     *
     * [force] restores the full refresh for explicit user refreshes and for flows
     * that just changed membership.
     */
    suspend fun refreshCircles(force: Boolean = false): Result<List<CircleEntity>> {
        Log.d(TAG, "refreshCircles: Starting refresh (force=$force)")
        // Stored keys the platform couldn't read at start-up: read them again, so a one-off
        // failure heals here, keys and pins intact, with no restart. A store that doesn't
        // decode ends the session: nothing works without it.
        if (keyStoreRecovery.retryIfReadFailed() == com.pingolin.track.util.KeyStoreRecovery.Verdict.SIGN_OUT) {
            keyStoreRecovery.signOut(sessionManager)
            return Result.failure(Exception(com.pingolin.track.util.KeyStoreRecovery.SYNC_ERROR))
        }

        return try {
            // What this device held BEFORE the refresh: every "did anything change?"
            // decision below is taken against it. A failed read degrades to the full
            // path (everything looks new), never to skipping work that is needed.
            val stored = runCatching { circleDao.getAll() }.getOrDefault(emptyList())
            // A stored row for a "circle" named by the personal key's slot goes; older
            // versions could store one.
            for (row in stored) if (personalSlot(row.id, "stored circle")) dropNotACircle(row.id)
            // So does one stored under a second spelling of a circle's id: every id taken from here
            // on is the one spelling (CanonicalIdsTrackApi), and the circle comes back under it. Quiet,
            // as for the slot: the circle itself stays.
            val respelled = stored.filter { row -> com.pingolin.track.util.AccountIds.canonical(row.id).let { it != null && it != row.id } }
            if (respelled.isNotEmpty()) {
                Log.w(TAG, "refreshCircles: ${respelled.size} stored circle row(s) under a second spelling of an id; dropped")
                for (row in respelled) dropNotACircle(row.id)
            }
            val known = (stored - respelled.toSet()).associateBy { it.id }

            Log.d(TAG, "refreshCircles: Calling API getCircles")
            val response = tracksApi.getCircles()

            Log.d(TAG, "refreshCircles: Response code=${response.code()}, isSuccessful=${response.isSuccessful}")
            Log.d(TAG, "refreshCircles: Response body success=${response.body()?.success}, data=${response.body()?.data?.items?.size} circles")

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "refreshCircles: HTTP error ${response.code()}: $errorBody")
                return Result.failure(Exception(ApiErrors.plainLine(response.code(), ApiErrors.parse(errorBody)?.errorCode, "Couldn't load your circles just now.")))
            }

            if (response.body()?.success != true) {
                Log.e(TAG, "refreshCircles: API returned success=false, error=${response.body()?.error}")
                return Result.failure(Exception("Couldn't load your circles just now."))
            }

            if (response.body()?.data == null) {
                Log.e(TAG, "refreshCircles: API returned null data")
                return Result.failure(Exception("No circles data returned"))
            }

            val first = response.body()!!.data!!
            // Whole only without a further page (read from the primary, see [refreshCirclesWhole]).
            // Never from later pages: the server orders the first page by the member's own sort
            // position, but a cursor page by day bucket and id, so after a reorder the pages can
            // repeat or skip circles.
            lastWholeCircleIds = if (first.hasMore) null else first.items.map { it.id }.toSet()
            // Circles past the first page are this account's too: kept and shared with like the
            // rest, as far as the pages go.
            val dtos = (first.items + laterCirclePages(first)).distinctBy { it.id }

            // Keys first, so names decrypt with whatever this import brings in.
            prepareKeysFor(dtos, known, force)

            // Per-circle key store (ECDH storeCircleKey) + name decrypt is pure CPU
            // (UniFFI); move it off the Main thread (refreshCircles runs on
            // viewModelScope).
            val circles = withContext(Dispatchers.Default) {
                dtos.map { dto -> mapCircleResponse(dto, known[dto.id]) }
            }
            val changed = rowsThatChanged(circles, known, System.currentTimeMillis())
            if (changed.isNotEmpty()) circleDao.insertAll(changed)
            Log.d(TAG, "refreshCircles: ${circles.size} circles, ${changed.size} written")
            currentKeyGate.noteListed(circles)
            // Who shares approximately in a circle this account is no longer in goes with it:
            // only against the WHOLE list, never a page of it.
            lastWholeCircleIds?.let { whole -> runCatching { locationRepository.get().keepApproximateCircles(whole) } }
            // A circle whose key moved on without this device (a refresh left it pending):
            // ask the members for the current copy. Backed off per circle. Not when the
            // current copy is here and waits on its record: another copy settles nothing,
            // and the records import asks when one would.
            for (circle in circles) {
                if (circle.id !in currentKeyGate.behind.value) continue
                val newest = runCatching { cryptoManager.newestHeldVersion(circle.id) }.getOrNull()
                if (newest == null || newest < circle.keyVersion) requestFreshAccess(circle.id)
            }

            // Auto-rotate keys for circles that need rotation.
            // This happens when a member leaves to ensure forward secrecy, or when the key
            // in use here is one this device may not go on with.
            for (circle in circles) {
                if (rotationDue(circle)) rotateDue(circle, "refreshCircles")
            }
            // A hand-over this device recorded whose transfer hasn't landed yet.
            retryHandOvers()
            // Someone else is owed a circle's current copy: hand it over if this device may.
            // The member list says who; the circle list says where to look.
            for (dto in dtos) {
                if (!dto.deliveryNeeded || dto.keyRotationNeeded == true) continue
                try {
                    deliverIfDue(dto.id)
                } catch (e: Exception) {
                    Log.w(TAG, "refreshCircles: delivery for ${dto.id} failed: ${e.message}")
                }
            }
            // An invitation waits to be renewed: ask "Is this really {email}?" if this account
            // made it. Nothing is renewed without the answer. Questions left unanswered before a
            // restart come back first; a circle with nothing to renew any more (renewed,
            // withdrawn or expired meanwhile) drops its own.
            restoreRenewalQuestions()
            for (dto in dtos) {
                if (!dto.invitesNeedRenewal) {
                    dropRenewalQuestions(dto.id)
                    continue
                }
                runCatching { askAboutRenewals(dto.id) }
                    .onFailure { Log.w(TAG, "refreshCircles: renewal look for ${dto.id} failed: ${it.message}") }
            }

            // Prefetch session keys where a circle is new to this device or its key
            // moved on — the only times the set of live session keys can have
            // changed under us in a way the on-demand fetch would pay for per row.
            for (circle in sessionKeyPrefetchTargets(circles, known, force)) {
                try {
                    prefetchSessionKeys(circle.id)
                } catch (e: Exception) {
                    Log.w(TAG, "refreshCircles: Failed to prefetch session keys for ${circle.id}", e)
                }
            }

            // Tell the server every pseudonym we've ever derived for ourselves in
            // each circle (one per key-chain version) so historical rows tagged with
            // pre-rotation pseudonyms are visible to /locations history. Sent only
            // when that set changed — see backfillPseudonyms.
            for (circle in circles) {
                try {
                    backfillPseudonyms(circle.id, force)
                } catch (e: Exception) {
                    Log.w(TAG, "refreshCircles: Pseudonym backfill failed for ${circle.id}: ${e.message}")
                }
            }

            // Mirror every circle's server privacy posture into the local Room
            // `privacy_settings` table so the no-network background send path
            // enforces approximate / pause / realtime-off from the first send —
            // not only after the user opens a circle's Privacy dialog. Circles the
            // list already carries `my_privacy` for are stored from it; only the
            // rest are fetched, in the background (see mirrorPrivacy). Local-only.
            mirrorPrivacy(dtos)


            Result.success(circles)
        } catch (e: Exception) {
            Log.e(TAG, "refreshCircles: Exception", e)
            Result.failure(e)
        }
    }

    /**
     * Import keys when [keyImportReason] finds a reason to, before any circle in
     * [dtos] is mapped.
     *
     * When there is no reason, one duty of the import still has to happen:
     * decryptCircleName stores the wrap that rides on a circle whose key this
     * device lacks, and that unwrap needs any recovered pre-reset identity loaded
     * first (see the ordering test in CircleRepositoryTest).
     */
    private suspend fun prepareKeysFor(
        dtos: List<CircleResponse>,
        known: Map<String, CircleEntity>,
        force: Boolean,
    ) {
        for (dto in dtos) sealedNames[dto.id] = dto.encryptedName to dto.nameKeyVersion
        val now = System.currentTimeMillis()
        val reason = keyImportReason(
            force = force,
            listed = dtos.map { it.id to (it.keyVersion ?: 1) },
            storedVersions = known.mapValues { it.value.keyVersion },
            hasKey = { id -> runCatching { cryptoManager.hasCircleKey(id) }.getOrDefault(false) },
            // Or our own wrap moved past the key we hold: a device a rotation left behind
            // catches up here, since the circle's own version does not change. Held, not
            // sealed under: a version held but not accepted yet is no reason to import it
            // again on every refresh.
            requested = keyReimportRequested.get() || dtos.any { dto ->
                val ours = dto.memberKeyVersion ?: return@any false
                ours > (runCatching { cryptoManager.newestHeldVersion(dto.id) }.getOrNull() ?: 0)
            },
            msSinceConditionalImport = now - lastConditionalKeyImportAtMs,
        )
        if (reason != null) {
            if (reason.rateLimited) lastConditionalKeyImportAtMs = now
            // Any import answers an outstanding request: it pulls every bundle.
            keyReimportRequested.set(false)
            // A circle this device holds no key for yet: judge its member list first, so
            // whoever is confirmed here is pinned and the first copy is taken only from one
            // of them — the SDK refuses a copy from anyone unpinned once a circle has pins.
            // With nobody confirmed, the first copy anchors this device.
            for (dto in dtos) {
                if (dto.id == SELF_CIRCLE_UUID || dto.id == SELF_CIRCLE_ID) continue
                if (runCatching { cryptoManager.hasCircleKey(dto.id) }.getOrDefault(true)) continue
                runCatching { judgeMemberList(dto.id) }
                    .onFailure { Log.w(TAG, "Could not judge ${dto.id}'s member list before its first key: ${it.message}") }
            }
            val keyResult = importCircleKeys()
            Log.d(TAG, "Key import ($reason): ok=${keyResult.isSuccess}, count=${keyResult.getOrNull()}")
        } else if (dtos.any { dtoWrapNeedsStore(it) }) {
            previousIdentityLoader.ensureLoaded()
        }
    }

    /**
     * Load the first page of circles and reset pagination state. Local rows are
     * upserted, never cleared — see the insertAll call below.
     */
    suspend fun loadInitialCircles(force: Boolean = false): Result<List<CircleEntity>> {
        Log.d(TAG, "loadInitialCircles: Starting initial load (force=$force)")

        _paginationState.value = CirclesPaginationState(isLoading = true)

        return try {
            // Same economy as refreshCircles: keys, session keys and row writes only
            // where this page shows a change, unless the user asked for a full
            // refresh.
            val known = runCatching { circleDao.getAll() }.getOrDefault(emptyList())
                .associateBy { it.id }
            val response = tracksApi.getCirclesPaginated(cursor = null, limit = PAGE_SIZE)

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "loadInitialCircles: HTTP error ${response.code()}: $errorBody")
                val line = ApiErrors.plainLine(response.code(), ApiErrors.parse(errorBody)?.errorCode, "Couldn't load your circles just now.")
                _paginationState.value = _paginationState.value.copy(
                    isLoading = false,
                    error = line
                )
                return Result.failure(Exception(line))
            }

            if (response.body()?.success != true || response.body()?.data == null) {
                Log.e(TAG, "loadInitialCircles: API error: ${response.body()?.error}")
                _paginationState.value = _paginationState.value.copy(
                    isLoading = false,
                    error = "Couldn't load your circles just now."
                )
                return Result.failure(Exception("Couldn't load your circles just now."))
            }

            val paginatedData = response.body()!!.data!!
            prepareKeysFor(paginatedData.items, known, force)
            val circles = paginatedData.items.map { dto -> mapCircleResponse(dto, known[dto.id]) }

            // Upsert page 1 in place — do NOT deleteAll() first. This is only the FIRST
            // page (PAGE_SIZE=20); a deleteAll() here would drop every circle past #20 until
            // a full scroll/restart, silently stopping location sharing to circles 21+
            // (LocationTrackingService fans out over circleDao.getAll()). insertAll is an
            // @Upsert — it updates existing rows in place (no DELETE + cascade), so re-seeing
            // a page-1 circle just refreshes it; later pages arrive via loadMoreCircles.
            // Stale circles (ones the user was removed from) are reconciled elsewhere
            // (member-leave), never pruned here — the full set isn't known until the last
            // page. Only rows whose content changed are written (see rowsThatChanged).
            val changed = rowsThatChanged(circles, known, System.currentTimeMillis())
            if (changed.isNotEmpty()) circleDao.insertAll(changed)
            currentKeyGate.noteListed(circles)

            _paginationState.value = CirclesPaginationState(
                isLoading = false,
                hasMore = paginatedData.hasMore,
                cursor = paginatedData.cursor,
                error = null
            )

            Log.d(TAG, "loadInitialCircles: Loaded ${circles.size} circles, hasMore=${paginatedData.hasMore}")

            for (circle in circles) {
                if (rotationDue(circle)) rotateDue(circle, "loadInitialCircles")
            }

            for (circle in sessionKeyPrefetchTargets(circles, known, force)) {
                try {
                    prefetchSessionKeys(circle.id)
                } catch (e: Exception) {
                    Log.w(TAG, "loadInitialCircles: Failed to prefetch session keys for ${circle.id}", e)
                }
            }

            // Populate the local privacy table for this page's circles before any
            // background send reads it (see refreshCircles). Local-only.
            mirrorPrivacy(paginatedData.items)

            Result.success(circles)
        } catch (e: Exception) {
            Log.e(TAG, "loadInitialCircles: Exception", e)
            _paginationState.value = _paginationState.value.copy(
                isLoading = false,
                error = e.message
            )
            Result.failure(e)
        }
    }

    /**
     * Load the next page of circles, or an empty list when a load is already in
     * flight or there are no more pages.
     */
    suspend fun loadMoreCircles(): Result<List<CircleEntity>> {
        val currentState = _paginationState.value

        if (currentState.isLoading || !currentState.hasMore) {
            Log.d(TAG, "loadMoreCircles: Skipping - isLoading=${currentState.isLoading}, hasMore=${currentState.hasMore}")
            return Result.success(emptyList())
        }

        Log.d(TAG, "loadMoreCircles: Loading next page with cursor=${currentState.cursor}")
        _paginationState.value = currentState.copy(isLoading = true, error = null)

        return try {
            val response = tracksApi.getCirclesPaginated(
                cursor = currentState.cursor,
                limit = PAGE_SIZE
            )

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "loadMoreCircles: HTTP error ${response.code()}: $errorBody")
                val line = ApiErrors.plainLine(response.code(), ApiErrors.parse(errorBody)?.errorCode, "Couldn't load your circles just now.")
                _paginationState.value = _paginationState.value.copy(
                    isLoading = false,
                    error = line
                )
                return Result.failure(Exception(line))
            }

            if (response.body()?.success != true || response.body()?.data == null) {
                Log.e(TAG, "loadMoreCircles: API error: ${response.body()?.error}")
                _paginationState.value = _paginationState.value.copy(
                    isLoading = false,
                    error = "Couldn't load your circles just now."
                )
                return Result.failure(Exception("Couldn't load your circles just now."))
            }

            val paginatedData = response.body()!!.data!!
            val known = runCatching { circleDao.getAll() }.getOrDefault(emptyList())
                .associateBy { it.id }
            val circles = paginatedData.items.map { dto -> mapCircleResponse(dto, known[dto.id]) }

            val changed = rowsThatChanged(circles, known, System.currentTimeMillis())
            if (changed.isNotEmpty()) circleDao.insertAll(changed)
            currentKeyGate.noteListed(circles)

            _paginationState.value = CirclesPaginationState(
                isLoading = false,
                hasMore = paginatedData.hasMore,
                cursor = paginatedData.cursor,
                error = null
            )

            Log.d(TAG, "loadMoreCircles: Loaded ${circles.size} more circles, hasMore=${paginatedData.hasMore}")

            // Sync privacy for the newly paged-in circles too, so users with more
            // than one page don't fail-open on page 2+ circles. Local-only.
            mirrorPrivacy(paginatedData.items)

            Result.success(circles)
        } catch (e: Exception) {
            Log.e(TAG, "loadMoreCircles: Exception", e)
            _paginationState.value = _paginationState.value.copy(
                isLoading = false,
                error = e.message
            )
            Result.failure(e)
        }
    }

    /**
     * Reset pagination state (e.g., on pull-to-refresh).
     */
    fun resetPagination() {
        _paginationState.value = CirclesPaginationState()
    }

    // ===== Per-circle privacy: local mirror (no outbound writes) =====

    /**
     * Mirror the caller's privacy posture for these circles into Room.
     *
     * A circle whose GET /circles row carries `my_privacy` is stored straight from
     * it, through the same [persistPrivacyLocally] the per-circle sync uses: the
     * server builds that object from the same columns and serializer as
     * GET /privacy/circles/{id}, so it IS what that GET would return. A privacy
     * change reaches the user's own devices as the `privacy_check` wake, which reads
     * this list again at once, else at the foreground service's 5-minute read:
     * either way without one privacy GET per circle per refresh. Awaited: a Room
     * write and no network, and it shortens the time the send path could read a
     * stale posture.
     *
     * Circles without it (an older server, or no privacy row yet, which is exactly
     * when the privacy route answers 404) fall back to the per-circle GET in the
     * background.
     */
    private suspend fun mirrorPrivacy(dtos: List<CircleResponse>) {
        val plan = privacyMirrorPlan(dtos)
        for (posture in plan.embedded) persistPrivacyLocally(posture)
        syncPrivacySettingsInBackground(plan.fetch)
    }

    /**
     * Fire-and-forget the per-circle privacy sync on IO so it never blocks the
     * circle refresh / map paint. Bounded to the given circle ids.
     */
    private fun syncPrivacySettingsInBackground(circleIds: List<String>) {
        if (circleIds.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            syncPrivacySettings(circleIds)
        }
    }

    /**
     * One read of [circleId]'s privacy row, into the pause and bubble records: an SOS's press
     * makes one, so a fix this phone held is judged on a record read after it was taken. Never
     * throws.
     */
    suspend fun refreshPrivacy(circleId: String) = syncPrivacySettings(listOf(circleId))

    /**
     * For each circle, fetch the server privacy posture and mirror it into the
     * local Room `privacy_settings` table so the NO-NETWORK background send path
     * (LocationTrackingService / LocationHeartbeatWorker pause+realtime filters +
     * [LocationRepository.coarsenRadiusMetersFor]) enforces approximate / pause /
     * realtime-off BEFORE the user ever opens a circle's Privacy dialog. Without
     * this the table has zero rows after a fresh login / cleared data / second
     * device, so an already-"approximate" (or paused) circle would receive EXACT
     * coordinates from the background service — the exact fail-open this closes.
     *
     * LOCAL-WRITE-ONLY: issues GETs + Room writes only, never an outbound privacy
     * PUT/POST. Sequential small-N; per-circle try/catch so one failure can't
     * abort the rest.
     */
    private suspend fun syncPrivacySettings(circleIds: List<String>) {
        for (circleId in circleIds) {
            try {
                val response = tracksApi.getPrivacySettings(circleId)
                if (response.isSuccessful && response.body()?.success == true) {
                    response.body()?.data?.let { persistPrivacyLocally(it) }
                } else if (isNoPrivacyRow(response)) {
                    // The server's own "no row yet" (the on-create seed is best-effort). Persist
                    // its DEFAULT (exact) posture so the send path reads a known setting —
                    // mirrors CirclesViewModel.loadPrivacySettings. Any other 404 stores nothing:
                    // the send path asks again, and holds back while it can't tell.
                    persistPrivacyLocally(
                        PrivacySettingsResponse(
                            circleId = circleId,
                            shareRealtime = true,
                            shareHistory = true,
                            shareBattery = true,
                            pausedUntil = null,
                            primaryDeviceId = null
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncPrivacySettings: failed for $circleId: ${e.message}")
            }
        }
    }

    /**
     * Ensure ONE circle's privacy row is present in the local Room table BEFORE a
     * realtime send reads it — closing the cold-start / headless FAIL-OPEN window.
     *
     * A device can durably hold circles with NO `privacy_settings` row: a fresh
     * login, cleared data, a second device, or a headless boot (BootReceiver /
     * FCM / UnifiedPush wake) that starts LocationTrackingService before the
     * fire-and-forget [syncPrivacySettingsInBackground] ever runs. While the row is
     * absent EVERY gate fails OPEN — an "approximate" circle sends EXACT coords
     * ([LocationRepository.coarsenRadiusMetersFor] returns 0.0), a paused circle
     * isn't skipped (null -> not paused), and a realtime-off circle still sends
     * (null -> not false). This primes the row so the real posture is honored from
     * the FIRST send.
     *
     * @return true when a CONCRETE row is now present — it already existed, or the
     *   server GET succeeded, INCLUDING the 404 case which persists the server
     *   DEFAULT (exact) posture, an explicit known setting. Returns false ONLY when
     *   the row is still absent because the posture could not be resolved (network
     *   throw, or a non-404 error such as 5xx/401): the caller MUST then FAIL CLOSED
     *   and defer this circle's realtime send — never emit an exact fix to a circle
     *   whose posture is unknown. A row PRESENT & non-approximate legitimately means
     *   EXACT and returns true (we don't coarsen circles that chose exact).
     *
     * LOCAL-WRITE-ONLY: issues a GET + Room write only, never an outbound privacy
     * PUT. Cheap on the hot path: only touches the network when the row is actually
     * missing, which is the exception once any sync has landed.
     */
    suspend fun ensurePrivacyPrimed(circleId: String): Boolean {
        // Self targets have no server privacy row and always send exact by design.
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return true
        // Fast path: a row already exists (steady state) — no network.
        try {
            if (privacySettingDao.get(circleId) != null) return true
        } catch (e: Exception) {
            Log.w(TAG, "ensurePrivacyPrimed: local read failed for $circleId: ${e.message}")
        }
        // Missing row: fetch + persist. Success (incl. 404 -> default-exact) yields a
        // concrete posture; a network/other failure leaves us unknown -> fail closed.
        return try {
            val response = tracksApi.getPrivacySettings(circleId)
            when {
                response.isSuccessful && response.body()?.success == true -> {
                    response.body()?.data?.let { persistPrivacyLocally(it) }
                    // Guard a degenerate 200-with-null-body: only report primed once a
                    // row has actually landed, else treat as unknown -> fail closed.
                    privacySettingDao.get(circleId) != null
                }
                isNoPrivacyRow(response) -> {
                    // The server's own "no row yet" (the on-create seed is best-effort). Persist
                    // its DEFAULT (exact) posture — mirrors [syncPrivacySettings]. Any other 404
                    // is no answer about the posture: unknown, below.
                    persistPrivacyLocally(
                        PrivacySettingsResponse(
                            circleId = circleId,
                            shareRealtime = true,
                            shareHistory = true,
                            shareBattery = true,
                            pausedUntil = null,
                            primaryDeviceId = null
                        )
                    )
                    true
                }
                else -> {
                    // Indeterminate (5xx/401, a 404 not the server's own, etc.): don't fabricate a
                    // posture -> fail closed.
                    Log.w(TAG, "ensurePrivacyPrimed: GET $circleId returned ${response.code()} — unknown posture, fail closed")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensurePrivacyPrimed: fetch failed for $circleId: ${e.message} — fail closed")
            false
        }
    }

    /**
     * This phone just ended a pause of [circleId] itself (Resume, or real-time sharing on again): the
     * first point it takes after that goes at once ([com.pingolin.track.util.PauseIntervals.resumedHere]).
     */
    fun resumedHere(circleId: String) = pauseIntervals.resumedHere(circleId)

    /**
     * Mirror one circle's server privacy posture into the local Room
     * `privacy_settings` table. The single writer of postures there, shared with
     * [com.pingolin.track.ui.circles.CirclesViewModel], which delegates here so
     * the DTO->entity mapping lives in one place. A row goes with its circle
     * ([LocationRepository.forgetCircleLocations]).
     *
     * LOCAL-WRITE-ONLY: copies data the server already returned; issues no
     * outbound privacy write. Runs on IO and never throws.
     */
    suspend fun persistPrivacyLocally(data: PrivacySettingsResponse) {
        try {
            withContext(Dispatchers.IO) {
                val row = mapPrivacyResponse(data)
                // Every privacy row this phone learns, from any path, and the server's spans of not
                // sharing when it gives them: what keeps a point captured during a pause (or with
                // real-time sharing off) from that circle, however late it goes. Not sharing is the
                // server's ingest rule: paused and not past the deadline, or real-time off. First,
                // and whatever the row's own write does: observe never throws.
                val paused = row.isSharingPausedAt()
                pauseIntervals.observe(
                    data.circleId,
                    notSharing = paused || !row.shareRealtime,
                    deadlineMs = row.pausedUntil.takeIf { paused && row.shareRealtime },
                    // Never "no pause" for what doesn't read: a start that doesn't (or a span that isn't
                    // there) covers from the beginning, and an end that doesn't is no end.
                    server = data.notSharingIntervals?.map { span ->
                        com.pingolin.track.util.PauseIntervals.Interval(
                            parseIsoToEpochMillis(span?.start) ?: 0L,
                            span?.end?.let(::parseIsoToEpochMillis),
                        )
                    },
                )
                // And how wide a bubble it got: the one a point is coarsened to, at the least,
                // however late it goes. The bubble the send paths use now ([sendsBubble]); what
                // doesn't read is the widest from the beginning, coarsening being the safe side.
                approximateIntervals.observe(
                    data.circleId,
                    radiusMiles = row.takeIf { it.sendsBubble() }?.let { bubbleMiles(it.approximateRadiusMiles) },
                    deadlineMs = parseIsoToEpochMillis(data.approximateUntil),
                    server = data.approximateIntervals?.map { span ->
                        com.pingolin.track.util.ApproximateIntervals.Span(
                            parseIsoToEpochMillis(span?.start) ?: 0L,
                            span?.end?.let(::parseIsoToEpochMillis),
                            // A width missing or 0 or less doesn't read: the widest.
                            span?.radiusMiles?.takeIf { it > 0 }?.coerceAtMost(LocationRepository.MAX_BUBBLE_RADIUS_MILES)
                                ?: LocationRepository.MAX_BUBBLE_RADIUS_MILES,
                        )
                    },
                )
                // insert() is @Insert(onConflict = REPLACE) -> upsert on the circleId PK.
                privacySettingDao.insert(row)
            }
        } catch (e: Exception) {
            Log.w(TAG, "persistPrivacyLocally: failed for ${data.circleId}: ${e.message}")
        }
    }

    /** Map the server privacy DTO onto the Room entity read by the send path. */
    private fun mapPrivacyResponse(data: PrivacySettingsResponse): PrivacySettingEntity =
        PrivacySettingEntity(
            circleId = data.circleId,
            shareRealtime = data.shareRealtime,
            shareHistory = data.shareHistory,
            shareBattery = data.shareBattery,
            allowAlertsOnMe = data.allowAlertsOnMe,
            sharingMode = data.sharingMode,
            approximateRadiusMiles = data.approximateRadiusMiles,
            // DTO carries RFC3339; entity/readers expect epoch millis.
            pausedUntil = parseIsoToEpochMillis(data.pausedUntil),
            modeBeforePause = data.modeBeforePause,
            primaryDeviceId = data.primaryDeviceId,
            primaryDevicePseudonym = data.primaryDevicePseudonym,
            showAllDevices = data.showAllDevices,
            updatedAt = System.currentTimeMillis()
        )

    /** A stored bubble radius as the send paths apply it: the default when unset, within 1-25 miles. */
    private fun bubbleMiles(stored: Int?): Int =
        (stored ?: LocationRepository.DEFAULT_BUBBLE_RADIUS_MILES).coerceIn(1, LocationRepository.MAX_BUBBLE_RADIUS_MILES)

    /**
     * Parse an RFC3339/ISO-8601 instant into epoch millis; null on null/blank/parse failure.
     * OffsetDateTime, not Instant.parse: the privacy route writes a "+00:00" offset, which
     * Instant.parse rejects before Android 14, and a timed pause would then read as one with no
     * end ([com.pingolin.track.data.local.entity.isSharingPausedAt]: paused until resumed).
     */
    private fun parseIsoToEpochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The row [dto] describes. [existing] is what this device already holds for the
     * circle: its updatedAt is carried over, so an unchanged circle maps to a row
     * EQUAL to the stored one and [rowsThatChanged] can leave it alone.
     */
    private fun mapCircleResponse(dto: CircleResponse, existing: CircleEntity? = null): CircleEntity {
        noteRotationCause(dto.id, dto.keyRotationCause?.takeIf { dto.keyRotationNeeded == true })
        val decryptedName = decryptCircleName(dto)
        return CircleEntity(
            id = dto.id,
            name = decryptedName,
            encryptedKey = dto.encryptedKey?.let { "${it.nonce}:${it.ciphertext}" },
            createdAt = dto.dayBucket.toLong() * 86400 * 1000L,
            updatedAt = existing?.updatedAt ?: System.currentTimeMillis(),
            isOwner = dto.isOwner,
            keyRotationNeeded = dto.keyRotationNeeded ?: false,
            keyVersion = dto.keyVersion ?: 1
        )
    }

    /**
     * Circles flagged for key rotation (a member left, so the key must move on for
     * forward secrecy). Checked on app start and after refreshCircles.
     */
    suspend fun getCirclesNeedingKeyRotation(): List<CircleEntity> {
        return circleDao.getAll().filter { it.keyRotationNeeded }
    }

    private fun decryptCircleName(dto: CircleResponse): String {
        // When this device already holds the circle's key AT THE WRAP'S VERSION, the
        // wrap on the circle is that same key, and storing it again costs an ECDH
        // unwrap plus a rewrite of the whole encrypted key file (store_circle_key
        // persists on every call) — per circle, on every 5-minute refresh. So if the
        // held key opens the name, that is the answer. A held key that does NOT open
        // it falls through to the store below, which replaces the key at that version
        // from the wrap, healing it.
        if (!dtoWrapNeedsStore(dto)) {
            nameWithHeldKey(dto)?.let { return readable(dto.id, it) }
        }
        // Whether the server holds a copy for us here: it sends member_key_version exactly when our
        // row has one. The copy itself comes as encrypted_circle_key and circle_key_nonce, which
        // this DTO doesn't read, so dto.encryptedKey is always null and only the key import ever
        // opens it.
        val hasWrap = dto.memberKeyVersion != null
        // Our wrap's OWN version, not the circle's: they differ for a member a
        // rotation left pending, and a wrap labelled with the circle's version cannot
        // verify (its signature binds the version it was made for). An older server
        // sends no member_key_version; there the two are always equal.
        val circleVersion = dto.keyVersion ?: 1
        val wrapVersion = dto.memberKeyVersion ?: circleVersion
        val leftBehind = wrapVersion < circleVersion
        var refused = false
        return try {
            // A wrapped key rides along with the circle, so store it via ECDH first.
            if (dto.encryptedKey != null && dto.ephemeralPublicKey != null) {
                Log.d(TAG, "decryptCircleName: Storing key for circle ${dto.id}, version=${dto.keyVersion}")
                val recipientUserId = sessionManager.getUserId().orEmpty()
                // Verify the wrap against the SENDER'S LOCALLY-PINNED signing key
                // (null when not pinned → SDK grace-accepts, no lockout). Never the
                // signer key the server delivered next to the wrap — a malicious
                // relay would simply sign its forgery with a matching key.
                val senderSigningPubkey =
                    dto.senderUserId?.let { cachedMemberSigningPubkey(dto.id, it) }
                try {
                    storeCopy(
                        dto.id,
                        "${dto.encryptedKey.nonce}${dto.encryptedKey.ciphertext}",
                        dto.ephemeralPublicKey,
                        wrapVersion,
                        ownSlotSender(dto.senderUserId, recipientUserId),
                        recipientUserId,
                        dto.wrapSignature,
                        senderSigningPubkey,
                    )
                } catch (e: FirstCopyNotYet) {
                    Log.i(TAG, "decryptCircleName: first copy of ${dto.id} waits for its member list")
                } catch (e: MobileException.SignatureInvalid) {
                    // Tampered/mismatched signature on a signed wrap: don't store
                    // it. We fall through without storing, so hasCircleKey() below
                    // stays false (or keeps a previously-verified key) and the name
                    // shows as unavailable rather than trusting a bad wrap.
                    refused = true
                    Log.w(TAG, "decryptCircleName: wrap signature INVALID for circle ${dto.id} — REJECTING key")
                }
                Log.d(TAG, "decryptCircleName: Key stored successfully for circle ${dto.id}")
            } else {
                Log.d(TAG, "decryptCircleName: No key in response for circle ${dto.id} (encryptedKey=${dto.encryptedKey != null}, ephemeralPublicKey=${dto.ephemeralPublicKey != null})")
            }

            if (cryptoManager.hasCircleKey(dto.id)) {
                val encryptedNameData = EncryptedData(
                    nonce = dto.encryptedName.nonce,
                    ciphertext = dto.encryptedName.ciphertext
                )
                val decrypted = cryptoManager.decryptDataV2(dto.id, encryptedNameData, "name")
                if (decrypted.isNotBlank() && !looksLikeRawHex(decrypted)) {
                    Log.d(TAG, "decryptCircleName: SUCCESS for circle ${dto.id}: '$decrypted'")
                    readable(dto.id, decrypted)
                } else {
                    // Do NOT log the decrypted circle name — Log.w survives release.
                    Log.w(TAG, "decryptCircleName: Decrypted name looks like raw hex for circle ${dto.id}")
                    locked(
                        dto.id,
                        when {
                            refused -> LockedCircle.WrapOutcome.REFUSED
                            leftBehind -> LockedCircle.WrapOutcome.NO_WRAP
                            else -> LockedCircle.WrapOutcome.UNOPENABLE
                        }
                    )
                }
            } else {
                Log.w(TAG, "decryptCircleName: No circle key available for ${dto.id} after import attempt")
                // No key at all: a copy there, even an older one a rotation left us with, hasn't opened
                // here. That is an email reset's shape, and the reset's own re-key leaves the member
                // behind too. An older copy of a member who never reset opens at the import, so it
                // never gets here.
                locked(
                    dto.id,
                    when {
                        refused -> LockedCircle.WrapOutcome.REFUSED
                        !hasWrap -> LockedCircle.WrapOutcome.NO_WRAP
                        else -> LockedCircle.WrapOutcome.UNOPENABLE
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "decryptCircleName: FAILED for circle ${dto.id}: ${e.message}", e)
            // A wrap that throws on the way in (not a signature refusal, which is
            // caught above) did not open with this account's keys — the shape an email
            // password reset leaves behind. With no wrap at all, or only an older one
            // (a rotation left us pending), the held key did not open the name: the
            // circle moved on to a key nobody has given us yet.
            locked(
                dto.id,
                when {
                    refused -> LockedCircle.WrapOutcome.REFUSED
                    !hasWrap || leftBehind -> LockedCircle.WrapOutcome.NO_WRAP
                    else -> LockedCircle.WrapOutcome.UNOPENABLE
                }
            )
        }
    }

    private fun readable(circleId: String, name: String): String {
        lockedCircleOutcomes.remove(circleId)
        readableCircleIds.add(circleId)
        return name
    }

    private fun locked(circleId: String, outcome: LockedCircle.WrapOutcome): String {
        lockedCircleOutcomes[circleId] = outcome
        readableCircleIds.remove(circleId)
        return LockedCircle.PLACEHOLDER_NAME
    }

    /** Behind on [circle]'s key here (CurrentKeyGate.behind): a key change waits, whatever the name. */
    private fun behindHere(circle: CircleEntity): Boolean = circle.id in currentKeyGate.behind.value

    /**
     * Whether [circle] shows a placeholder because this device cannot open it. What
     * this process decrypted wins; the stored name only decides for a row it has not
     * looked at yet (e.g. straight after a restart).
     */
    fun isCircleLocked(circle: CircleEntity, countBehind: Boolean = true): Boolean {
        // Behind on its key: locked here even if the older key still opens the name,
        // since nothing can be shared with it from this device. A repair asks only
        // whether the name is known: it is what ends the circle's wait.
        if (countBehind && circle.id in currentKeyGate.behind.value) return true
        if (circle.id in readableCircleIds) return false
        if (lockedCircleOutcomes.containsKey(circle.id)) return true
        return LockedCircle.isPlaceholder(circle.name)
    }

    /**
     * Why [circleId] is locked on this device. Asks the account service whether an email
     * reset left restorable data only when the cause turns on it — a key that would not
     * open, or a circle this process has not looked at yet.
     */
    suspend fun lockedCircleCause(circleId: String): LockedCircle.Cause {
        if (circleId in currentKeyGate.behind.value) return LockedCircle.Cause.BEHIND
        val outcome = lockedCircleOutcomes[circleId]
        val preResetData = if (outcome == null || outcome == LockedCircle.WrapOutcome.UNOPENABLE) {
            previousIdentityLoader.preResetDataExists()
        } else {
            null
        }
        return LockedCircle.causeOf(outcome, preResetData)
    }

    /**
     * True when [dto] carries a wrapped circle key that this device does not already
     * hold at the same version, so decryptCircleName has to store it. Held, whether or not
     * this device may seal under it yet.
     */
    private fun dtoWrapNeedsStore(dto: CircleResponse): Boolean {
        if (dto.encryptedKey == null || dto.ephemeralPublicKey == null) return false
        val wrapVersion = dto.memberKeyVersion ?: dto.keyVersion ?: 1
        val heldAtThisVersion = runCatching {
            cryptoManager.newestHeldVersion(dto.id) == wrapVersion
        }.getOrDefault(false)
        return !heldAtThisVersion
    }

    /** [dto]'s name under the key already held, or null when that key does not open it. */
    private fun nameWithHeldKey(dto: CircleResponse): String? = try {
        if (!cryptoManager.hasCircleKey(dto.id)) {
            null
        } else {
            cryptoManager.decryptDataV2(
                dto.id,
                EncryptedData(nonce = dto.encryptedName.nonce, ciphertext = dto.encryptedName.ciphertext),
                "name"
            ).takeIf { it.isNotBlank() && !looksLikeRawHex(it) }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Check if a string looks like raw hex data (e.g. ciphertext leaked as "decrypted" name).
     * A real circle name would never be a 32+ char all-hex string.
     */
    private fun looksLikeRawHex(value: String): Boolean {
        return value.length >= 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Create [name]. [noticeVersion] is the location notice the creator agreed to, recorded
     * with the creation (no circle without it).
     */
    suspend fun createCircle(name: String, noticeVersion: String): Result<CircleEntity> {
        return try {
            Log.d(TAG, "createCircle: Starting creation of '$name'")
            // A new circle comes with its genesis record, which binds its id, so the server
            // reserves the id first. The key is made under it: nothing to finalize.
            val selfUserId = sessionManager.getUserId() ?: return Result.failure(Exception(CIRCLE_NOT_CREATED))
            val reservation = tracksApi.reserveCircleId()
            val reserved = reservation.body()?.takeIf { reservation.isSuccessful && it.success }?.data?.circleId
                ?: return Result.failure(ApiErrors.toException(reservation, CIRCLE_NOT_CREATED))
            val recorded = cryptoManager.prepareRecordedCircleCreation(selfUserId, reserved)
            val creationData = recorded.creation
            Log.d(TAG, "createCircle: Key and genesis prepared for ${recorded.circleId}")

            val encryptedName = cryptoManager.encryptDataV2(recorded.circleId, name, "name")

            val response = tracksApi.createCircle(
                CreateCircleRequest(
                    encryptedName = EncryptedPayload(
                        nonce = encryptedName.nonce,
                        ciphertext = encryptedName.ciphertext
                    ),
                    encryptedCircleKey = EncryptedPayload(
                        nonce = creationData.encryptedCircleKey.nonce,
                        ciphertext = creationData.encryptedCircleKey.ciphertext
                    ),
                    ephemeralPublicKey = creationData.ephemeralPublicKey,
                    locationConsent = com.pingolin.track.data.remote.dto.GrantLocationConsentRequest(
                        clientNoticeVersion = noticeVersion,
                    ),
                    circleId = recorded.circleId,
                    genesis = RefreshRecordPayload(recorded.genesis.recordHex, recorded.genesis.signatureHex),
                )
            )

            Log.d(TAG, "createCircle: API response code=${response.code()} success=${response.body()?.success}")
            val created = response.body()?.data?.takeIf { response.isSuccessful && response.body()?.success == true }
            if (created != null && !created.id.equals(recorded.circleId, ignoreCase = true)) {
                // Its genesis binds the reserved id: a circle under any other one has no first record.
                Log.e(TAG, "createCircle: the server made the circle under another id than the one it reserved")
                runCatching { cryptoManager.discardCircleCreation(recorded.circleId) }
                return Result.failure(Exception(CIRCLE_NOT_CREATED))
            }
            if (created != null) {
                val dto = created
                Log.d(TAG, "createCircle: SUCCESS id=${dto.id}")
                readableCircleIds.add(dto.id)
                // A circle made here trusts only its creator to begin with, and from then on
                // whoever its members' signed invitations vouch for: nobody the server adds is
                // trusted for being listed. That this device created it is its own record too
                // — the one thing that lets it serve any member it confirms.
                keyAccessApprovals.markCreatedHere(dto.id)

                // Sign the creator's wrap now that the circle id it must bind exists:
                // other devices of this account will only accept it signed once they
                // hold any key for the circle. Best-effort; the next key import signs
                // it if this does not land.
                run {
                    runCatching { cryptoManager.signCircleCreationWrap(creationData, dto.id, selfUserId) }
                        .onSuccess { signature ->
                            signedOwnWraps.add("${dto.id}:1")
                            uploadOwnWrapSignature(
                                dto.id,
                                1,
                                EncryptedPayload(
                                    nonce = creationData.encryptedCircleKey.nonce,
                                    ciphertext = creationData.encryptedCircleKey.ciphertext,
                                ),
                                creationData.ephemeralPublicKey,
                                signature,
                            )
                        }
                        .onFailure { Log.w(TAG, "createCircle: creator wrap not signed: ${it.message}") }
                }

                val circle = CircleEntity(
                    id = dto.id,
                    name = name, // We know the name since we just created it
                    encryptedKey = "${creationData.encryptedCircleKey.nonce}:${creationData.encryptedCircleKey.ciphertext}",
                    createdAt = dto.dayBucket.toLong() * 86400 * 1000L, // Convert day bucket to milliseconds
                    updatedAt = System.currentTimeMillis(),
                    isOwner = true,
                    keyRotationNeeded = false,
                    keyVersion = 1
                )
                circleDao.insert(circle)

                // E2EE: Upload encrypted display name for this circle
                try { uploadEncryptedDisplayNames() } catch (_: Exception) {}

                // A fresh position goes to the new circle now. The person's earlier personal
                // history does not: the location notice covers sharing from here on, and the
                // other clients don't copy it either.
                notifyTrackingServiceCirclesChanged()

                // The genesis is the head this device accepted: the records check that reads it back
                // publishes it, so a fresh install of this account finds it.
                deliveryScope.launch { syncRefreshRecords() }

                Result.success(circle)
            } else {
                // Parse the structured error body (like the invite path) rather
                // than dumping raw JSON, and carry error_code/quota on a 402
                // (circle count limit) so the UI can show the premium prompt.
                val ex = ApiErrors.toException(response, CIRCLE_NOT_CREATED)
                Log.e(TAG, "createCircle: FAILED code=${response.code()} error=${ex.message}")
                // Refused: the key and the genesis made for it go.
                runCatching { cryptoManager.discardCircleCreation(recorded.circleId) }
                Result.failure(ex)
            }
        } catch (e: Exception) {
            // No answer is not a refusal: the server may have taken it, so the creation is kept.
            // Unused, it names a reserved id nobody else is ever given.
            Log.e(TAG, "createCircle: EXCEPTION ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Invite [email] to [circleId]. With [keepIfBehind] (the person's tap), an invitation this
     * phone can't sign because it is behind, or whose address couldn't be checked, is kept and
     * sent by itself once it can: the failure is then
     * [com.pingolin.track.domain.pending.QueuedForSync]. A waiting change's run ([pendingRun])
     * saves the statement's id before the request leaves and sends the change's id as
     * `client_request_id`.
     */
    suspend fun inviteToCircle(
        circleId: String,
        email: String,
        keepIfBehind: Boolean = false,
        pendingRun: com.pingolin.track.domain.pending.PendingRun? = null,
    ): Result<InviteResponse> = invite(circleId, email, keepIfBehind, pendingRun, confirmed = null)

    /**
     * [inviteToCircle]; after "It's them" ([confirmInviteeAndInvite]) only to [confirmed]'s
     * account, the one the person answered about, and kept with that answer when it waits.
     */
    private suspend fun invite(
        circleId: String,
        email: String,
        keepIfBehind: Boolean,
        pendingRun: com.pingolin.track.domain.pending.PendingRun?,
        confirmed: InviteeChangedException?,
    ): Result<InviteResponse> {
        Log.d(TAG, "inviteToCircle: circleId=$circleId email=$email")
        if (personalSlot(circleId, "invitation")) return Result.failure(Exception(INVITE_NOT_SENT))
        // The person's tap, kept as a change that goes out by itself: with their answer to
        // "Is this really {email}?", if they gave one.
        suspend fun kept(addressCheck: Boolean) = pendingChanges.queue<InviteResponse>(
            circleId,
            com.pingolin.track.domain.pending.PendingInputs.Invite(
                pendingChanges.circleName(circleId), email.trim().lowercase(),
                confirmedUserId = confirmed?.userId, confirmedSharingKey = confirmed?.sharingKeyHex,
                confirmedSigningKey = confirmed?.signingKeyHex, addressCheck = addressCheck,
                tapVersion = heldVersion(circleId),
            ),
        )
        return try {
            // The circle's name, sent only to an invitee with no account ([nameForInvitee] below).
            val circleName = circleDao.getById(circleId)?.name
            // An invitation is signed with this device's version of the circle, and one
            // signed while behind would be out of date on arrival: the invite waits until
            // this device has synced — here, a little, while it catches up — and then,
            // when the person asked, waits as a change that goes out by itself.
            if (!currentKeyGate.holdsCurrentKey(circleId) && !catchUp(circleId)) {
                if (!keepIfBehind || pendingRun != null) return Result.failure(KeyNotCurrentException())
                return kept(addressCheck = false)
            }
            val selfUserId = sessionManager.getUserId().orEmpty()
            // Before this invite pins anybody: only the pins this device already held
            // may seed ([seedFromHeldPinsOnce]).
            seedFromHeldPinsOnce()

            // The address check. No answer ([noAnswer]: no connection, a time limit, a 5xx, the
            // server's 503 `lookup_unavailable`, a 429) means it couldn't be done: nothing is signed
            // or sent, never an address-only or unsigned invitation in its place. A waiting run
            // waits; the person's own tap is kept as a change that waits for the check. Any other
            // 4xx but a real "no account" is never kept: it is handled below.
            val keyResponse = try {
                tracksApi.lookupPublicKey(email)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                null
            } catch (e: Exception) {
                Log.w(TAG, "invite: the address check failed: ${e.javaClass.simpleName}")
                return Result.failure(if (pendingRun != null) com.pingolin.track.domain.pending.CouldNotCheck() else Exception(INVITE_NOT_SENT))
            }
            // A 2xx with nothing readable in it (no body, not a success, no data) is no answer
            // either: taken for "no account", it would send the circle's name.
            val unreadable = keyResponse != null && keyResponse.isSuccessful &&
                keyResponse.body()?.takeIf { it.success }?.data == null
            if (keyResponse == null || noAnswer(keyResponse.code()) || unreadable) {
                return when {
                    pendingRun != null -> Result.failure(com.pingolin.track.domain.pending.CouldNotCheck())
                    keepIfBehind -> kept(addressCheck = true)
                    else -> Result.failure(Exception("Couldn't check $email just now."))
                }
            }
            // Whether the directory answered: its account for the address, or a real "no account".
            val lookedUp = keyResponse.isSuccessful || keyResponse.code() == 404
            if (!lookedUp) {
                // Any other 4xx (a bad address, 401, 403) is never taken as a check to wait for: a
                // waiting run waits, "It's them" says it couldn't check, and the plain tap goes on
                // (unsigned).
                if (pendingRun != null) return Result.failure(com.pingolin.track.domain.pending.CouldNotCheck())
                if (confirmed != null) return Result.failure(Exception("Couldn't check $email just now."))
            }
            val lookup = keyResponse.body()?.data
            // The account the directory names for the address, canonical (AccountIds.strict):
            // 8-4-4-4-12 in any case, or 32 hex. Any other id is no answer, never "no account" or
            // a first contact: nothing is pinned, wrapped, signed or sent, and a waiting run waits.
            // None named is the address-only invitation. Only this id is used below: pins, wrap,
            // statement, records.
            val lookedUpUserId = lookup?.userId?.takeIf { it.isNotBlank() }?.let { named ->
                com.pingolin.track.util.AccountIds.strict(named) ?: run {
                    Log.w(TAG, "invite: the address check named an account id that isn't a UUID; nothing sent")
                    return Result.failure(
                        if (pendingRun != null) com.pingolin.track.domain.pending.CouldNotCheck() else Exception("Couldn't check $email just now.")
                    )
                }
            }
            // The circle's name goes only to an address the directory ANSWERED has no account: it
            // reaches that person in the invitation e-mail, and the server keeps it nowhere. An
            // account holder's app sees the inviter, not the circle's name, until they join. Keyed
            // on "no account", never on the invitation's kind: an account that never published keys
            // gets the address-bound kind but is an account. A lookup that didn't answer (another
            // 4xx, the plain tap that still goes) sends no name.
            val nameForInvitee = circleName.takeIf { lookedUp && lookedUpUserId == null }
            // "It's them" is about one account: another one behind the address now, or none, is
            // never invited in its place ([PendingLines.inviteDetailsKeepChanging] for a waiting
            // change). For that account this device takes the details the person confirmed now
            // that the address has answered, never while the invitation waits.
            if (confirmed != null) {
                val named = lookedUpUserId?.takeIf { com.pingolin.track.util.AccountIds.same(it, confirmed.userId) }
                    ?: return Result.failure(InviteeNotAsConfirmedException(email))
                try {
                    cryptoManager.approveMemberKeyChange(circleId, named, confirmed.sharingKeyHex, confirmed.signingKeyHex)
                } catch (e: Exception) {
                    Log.w(TAG, "invite: could not accept $named's new details: ${e.message}")
                    return Result.failure(e)
                }
                sessionManager.getUserId()?.let { owner ->
                    MemberVerificationStore.clearVerified(context, owner, circleId, named)
                }
            }

            // The account the server names for the email, if any, with its keys. The
            // inviter chose this person, so the invite is also this user's approval of
            // their access: no question when they join.
            var invitedUserId: String? = null
            var invitedSharingKey: String? = null
            var invitedSigningKey: String? = null
            val request = try {
                val publicKey = lookup?.sharingPublicKey
                val recipientUserId = lookedUpUserId
                // A waiting invitation of someone the circle's records removed after its
                // person decided ends with [PendingLines.memberLeft]'s line, and signs nothing.
                if (pendingRun != null && !recipientUserId.isNullOrBlank()) {
                    val theirKeys = publicKey?.let { sharing -> lookup?.signingPublicKey?.let { sharing to it } }
                    when (removedSinceDecided(circleId, recipientUserId, pendingRun.tapVersion, theirKeys)) {
                        null -> return Result.failure(com.pingolin.track.domain.pending.CouldNotCheck())
                        true -> return Result.failure(com.pingolin.track.domain.pending.RemovedSinceDecided())
                        false -> Unit
                    }
                }
                invitedUserId = recipientUserId?.takeIf { it.isNotBlank() }
                invitedSharingKey = publicKey?.takeIf { it.length == 64 }
                invitedSigningKey = lookup?.signingPublicKey?.takeIf { it.length == 64 }
                val recipientSigningKey = lookup?.signingPublicKey
                if (publicKey == null) {
                    // Nothing to wrap yet — invitee exposes no sharing key (or no
                    // account matched). The newcomer is handed the circle when they join.
                    Log.d(TAG, "Recipient has no public key, sending invite without pre-encrypted key")
                    InviteRequest(email = email, circleName = nameForInvitee)
                } else if (recipientUserId.isNullOrBlank()) {
                    // SEND-SIDE TOFU (invite): we have a sharing key but no
                    // user_id to key the pin on. NEVER pre-wrap the LIVE circle
                    // key to a key we can't pin/re-check — a malicious server
                    // could substitute it.
                    Log.w(TAG, "Invite lookup returned a sharing key with no user_id — skipping pre-wrap (fail-safe, lazy delivery)")
                    InviteRequest(email = email, circleName = nameForInvitee)
                } else {
                    // SEND-SIDE TOFU (invite): if we already pinned this account (this
                    // circle's pin, else the identity one), BOTH looked-up keys MUST match or
                    // nothing is sent (fail closed). A swapped sharing key would get the circle
                    // key wrapped to an attacker key; a swapped signing key alone would be
                    // bound into the invitation, and whoever holds it would then speak for
                    // them in this circle. First contact pins the account: this user chose
                    // them.
                    val pinnedSharing = memberPins.pinnedSharingKey(circleId, recipientUserId)
                    val pinnedSigning = memberPins.pinnedSigningKey(circleId, recipientUserId)
                    val pinned = pinnedSharing != null || pinnedSigning != null
                    val lookedUpSigning = recipientSigningKey?.takeIf { it.length == 64 }
                    // A half the pin lacks counts as changed.
                    val sharingChanged = pinned && !publicKey.equals(pinnedSharing, ignoreCase = true)
                    val signingChanged = pinned && lookedUpSigning != null &&
                        !lookedUpSigning.equals(pinnedSigning, ignoreCase = true)
                    if (sharingChanged || signingChanged) {
                        Log.w(TAG, "Invite pin mismatch for $circleId/$recipientUserId (sharing=$sharingChanged, signing=$signingChanged) — nothing sent")
                        // With both new keys the user can compare numbers and confirm;
                        // without a signing key there is nothing to confirm.
                        val signing = lookedUpSigning ?: throw RecipientKeyChangedException()
                        throw InviteeChangedException(
                            circleId, email, recipientUserId, publicKey, signing,
                            computeSafetyNumber(recipientUserId, publicKey, signing),
                        )
                    }
                    if (!pinned) {
                        if (recipientSigningKey != null && recipientSigningKey.length == 64) {
                            try {
                                cryptoManager.pinMemberKeys(circleId, recipientUserId, publicKey, recipientSigningKey)
                            } catch (e: Exception) {
                                Log.w(TAG, "Invite first-contact pin failed for $circleId/$recipientUserId: ${e.message}")
                            }
                        } else {
                            Log.d(TAG, "Invite first-contact for $circleId/$recipientUserId has no signing key — wrapping without a full pin")
                        }
                    }
                    if (!invitationKeyMayGo(circleId)) {
                        // The key an invitation carries is a delivery: without it the
                        // invitee waits, and the late-joiner question applies.
                        Log.i(TAG, "Invite to $circleId goes without the circle's key: this account isn't an owner or admin in its chain")
                        InviteRequest(email = email, circleName = nameForInvitee)
                    } else {
                        Log.d(TAG, "Pre-encrypting circle key for invite recipient")
                        // BIND THE RECIPIENT: `recipient_user_id` is part of the signature
                        // input, so a wrap signed for "" never verifies anywhere (a re-invited
                        // member would refuse every such wrap and freeze on an old key).
                        val keyBundle = cryptoManager.wrapCircleKeyForMemberFull(
                            circleId,
                            publicKey,
                            selfUserId,
                            recipientUserId,
                            // Reserved by the SDK and not bound into the signature;
                            // passed for parity with the iOS app rather than effect.
                            recipientSigningKey.orEmpty(),
                        )
                        InviteRequest(
                            email = email,
                            encryptedCircleKey = EncryptedPayload(
                                nonce = keyBundle.encryptedCircleKey.nonce,
                                ciphertext = keyBundle.encryptedCircleKey.ciphertext
                            ),
                            ephemeralPublicKey = keyBundle.ephemeralPublicKey,
                            circleName = nameForInvitee,
                            wrapSignature = keyBundle.wrapSignature.takeIf { it.isNotEmpty() }
                        )
                    }
                }
            } catch (e: RecipientKeyChangedException) {
                // Hard refusal (active key-substitution signal). Do NOT degrade to
                // lazy delivery — propagate so the invite fails and the UI shows it.
                throw e
            } catch (e: InviteeChangedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-encrypt circle key, falling back: ${e.message}")
                InviteRequest(email = email, circleName = nameForInvitee)
            }

            // The signed invitation: to the account the server named, with exactly
            // the keys it showed, or — with no account (or one without both keys) —
            // to the email. Every member's app checks it before showing the newcomer.
            val statement = if (!lookedUp) null else try {
                val userId = invitedUserId
                val sharing = invitedSharingKey
                val signing = invitedSigningKey
                if (userId != null && sharing != null && signing != null) {
                    membershipStatements.signInvitationToAccount(circleId, selfUserId, userId, sharing, signing)
                } else {
                    membershipStatements.signInvitationToEmail(circleId, selfUserId, email)
                }
            } catch (e: Exception) {
                // Sent unsigned: an older server takes an unsigned invitation, and
                // owners/admins are asked about the newcomer.
                Log.w(TAG, "inviteToCircle: invitation not signed: ${e.message}")
                null
            }
            // The removal check at the signature: an eject accepted while this waiting run went
            // on is found now that the statement's version is fixed, and nothing is sent. The
            // check after the lookup above stays: it signs nothing for someone already removed.
            val invitee = invitedUserId
            if (statement != null && pendingRun != null && invitee != null) {
                val keysSigned = invitedSharingKey?.let { sharing -> invitedSigningKey?.let { sharing to it } }
                when (removedSinceDecided(circleId, invitee, pendingRun.tapVersion, keysSigned)) {
                    null -> return Result.failure(com.pingolin.track.domain.pending.CouldNotCheck())
                    true -> return Result.failure(com.pingolin.track.domain.pending.RemovedSinceDecided())
                    false -> Unit
                }
            }
            // A waiting change's run saves the statement's id (and whom it invites, until when)
            // before the request leaves. If that can't be saved, nothing is sent.
            if (statement != null && pendingRun?.onSigned != null) {
                val parsed = membershipStatements.parse(statement.statementHex)
                    ?: return Result.failure(com.pingolin.track.domain.pending.StatementNotSaved())
                pendingRun.onSigned.invoke(
                    com.pingolin.track.domain.pending.SignedStatement(
                        id = parsed.statementId, subject = parsed.subjectUserId, expiresAtSecs = parsed.expiresAt,
                    )
                )
            }
            val signedRequest = request.copy(
                statement = statement?.statementHex,
                statementSignature = statement?.signatureHex,
                clientRequestId = pendingRun?.requestId,
            )

            Log.d(TAG, "inviteToCircle: sending API request")
            val response = tracksApi.inviteToCircle(circleId, signedRequest)
            Log.d(TAG, "inviteToCircle: API response code=${response.code()} success=${response.body()?.success}")

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                Log.d(TAG, "inviteToCircle: SUCCESS")
                // Bound to the invitation signed here and its expiry: the invitee is this user's
                // own only if they join with THIS invitation in time. An unsigned invitation
                // records nothing.
                // The answer names no invitation id: the statement's is learnt from the owner's
                // list, which a withdrawal reads first when it doesn't know it ([revokeInvite]).
                statement?.let { runCatching { membershipStatements.parse(it.statementHex) }.getOrNull() }?.let { signed ->
                    keyAccessApprovals.approveInvitee(circleId, signed.subjectUserId, email, signed.statementId, signed.expiresAt)
                    // A re-invite made from this device clears its own removal of them that no
                    // record has named yet, kept against this invitation: withdrawn from here,
                    // or run out unused, it comes back.
                    signed.subjectUserId?.let { ownRemovals.clearedByInvitation(circleId, it, signed.statementId, signed.expiresAt) }
                }
                Result.success(response.body()!!.data!!)
            } else {
                val refusal = ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())
                val serverError = response.body()?.error ?: refusal?.error
                Log.e(TAG, "inviteToCircle: FAILED code=${response.code()} error=$serverError")
                // Signed at a version the circle has since left (the server takes only
                // its current one): this device is behind, whatever its circle list said.
                if (serverError?.contains("at_version") == true) {
                    requestKeyReimport()
                    return Result.failure(KeyNotCurrentException())
                }
                // Its status and code travel with this app's line: a waiting change decides by them.
                Result.failure(RefusedException(inviteRefusal(response.code(), refusal?.errorCode, serverError), response.code(), refusal?.errorCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "inviteToCircle: EXCEPTION ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * The key an invitation carries is a delivery, and on a circle with refresh records it goes
     * only with this device's own fresh invitation: this account an owner or admin in the
     * chain, and this device holding the version in use. Otherwise the invitation goes without
     * a key, the invitee waits, and the late-joiner question applies. A circle without records
     * always sends it.
     */
    private suspend fun invitationKeyMayGo(circleId: String): Boolean {
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return true
        if (!currentKeyGate.holdsCurrentKey(circleId)) return false
        if (recordsHead(circleId) == 0) syncRefreshRecords()
        if (recordsHead(circleId) == 0) return false
        val self = sessionManager.getUserId() ?: return false
        val role = runCatching { cryptoManager.chainRole(circleId, self) }.getOrNull()
        return role == uniffi.pingolin_mobile.CircleRole.OWNER || role == uniffi.pingolin_mobile.CircleRole.ADMIN
    }

    /**
     * "It's them" for an invitee whose account changed ([InviteeChangedException]): THIS
     * device accepts their new details (nothing is signed about it, and other devices ask
     * their own users) once the address has answered, for that account only, then the
     * invitation goes out as usual.
     */
    suspend fun confirmInviteeAndInvite(
        change: InviteeChangedException,
        keepIfBehind: Boolean = false,
        pendingRun: com.pingolin.track.domain.pending.PendingRun? = null,
    ): Result<InviteResponse> =
        // Behind, or the address can't be checked: kept with the details the person confirmed,
        // taken only when it runs. Else they are taken once the address has answered, for that
        // account only, and the invitation goes out as usual.
        invite(change.circleId, change.email, keepIfBehind = keepIfBehind, pendingRun = pendingRun, confirmed = change)

    /**
     * Invite a user to a circle with a caller-supplied pre-encrypted circle key.
     *
     * DISABLED (fail closed): this entry point receives only a recipient sharing
     * key — no user_id — so it cannot key a TOFU pin on (circleId, userId) nor
     * re-check the key against an existing pin. Per the invite-time key-substitution
     * defense we must never wrap the LIVE circle key to a key we can't pin, so this
     * refuses rather than pre-wrapping blind. Callers with a pinnable identity must
     * use the email-lookup path in [inviteToCircle], which gates the wrap through
     * [CryptoManager.pinnedSharingKey]/[CryptoManager.pinMemberKeys]. Currently
     * unused; kept fail-closed so it can't silently reintroduce the ungated wrap.
     * Always returns [Result.failure].
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun inviteToCircleWithKey(
        circleId: String,
        email: String,
        recipientPublicKeyHex: String
    ): Result<InviteResponse> {
        return Result.failure(
            IllegalStateException(
                "Cannot pre-encrypt the circle key without a pinnable recipient identity; use inviteToCircle(email)"
            )
        )
    }

    /**
     * Transfer circle ownership to [newOwnerId], who must be an active member.
     * Only the current owner may call this; the server enforces it.
     *
     * On a circle with refresh records whose chain has this account as its owner, the hand-over
     * is a record first ([handOverRecord]), and the server's transfer follows its 200 at once
     * ([finishHandOver]); a transfer that fails for a reason that passes is sent again after
     * each circle refresh. A circle whose records can't be read here now, or whose chain forked,
     * isn't handed over from here meanwhile: a transfer first could be recorded only by a later
     * flagged rotation. Elsewhere, the server's transfer alone: no records yet (the first record
     * takes the list's roles), or a chain whose owner is someone else (a claimant's, which stays
     * the server's word).
     */
    suspend fun transferOwnership(circleId: String, newOwnerId: String): Result<Unit> {
        if (personalSlot(circleId, "hand-over")) return Result.failure(Exception(HAND_OVER_NOT_DONE))
        return try {
            // A hand-over that can't be told now hands nothing over: it may name another member.
            val kept = try { handOvers.of(circleId) } catch (e: com.pingolin.track.util.StoreUnreadable) {
                return Result.failure(Exception(HAND_OVER_NOT_DONE))
            }
            kept?.let { recorded ->
                // Recorded already: only its transfer, to the member it names.
                if (!recorded.target.equals(newOwnerId, ignoreCase = true)) return Result.failure(Exception(HAND_OVER_NOT_DONE))
                return finishHandOver(circleId, recorded)
            }
            if (runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) {
                if (recordsHead(circleId) == 0) syncRefreshRecords()
                if (recordsHead(circleId) == 0) return Result.failure(Exception(HAND_OVER_NOT_DONE))
                val self = sessionManager.getUserId() ?: return Result.failure(Exception(HAND_OVER_NOT_DONE))
                if (runCatching { cryptoManager.chainRole(circleId, self) }.getOrNull() == uniffi.pingolin_mobile.CircleRole.OWNER) {
                    val version = handOverRecord(circleId, newOwnerId).getOrElse { return Result.failure(it) }
                    val handOver = com.pingolin.track.util.HandOvers.HandOver(version, newOwnerId)
                    handOvers.remember(circleId, handOver)
                    return finishHandOver(circleId, handOver)
                }
            }
            when (val transfer = transferOnServer(circleId, newOwnerId)) {
                TransferOutcome.Done -> Result.success(Unit)
                is TransferOutcome.Refused -> Result.failure(Exception(ApiErrors.plainLine(transfer.status, transfer.errorCode, HAND_OVER_NOT_DONE)))
                is TransferOutcome.NoAnswer -> Result.failure(transfer.cause)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** What the server did with a transfer_ownership ([transferOnServer]). */
    private sealed interface TransferOutcome {
        object Done : TransferOutcome

        /** Refused for good: they're not an active member any more, or this account isn't the owner. */
        data class Refused(val status: Int, val errorCode: String?) : TransferOutcome

        /** No answer, or one that passes (a 5xx, 408, 429, or a sign-in to renew): sent again later. */
        data class NoAnswer(val cause: Exception) : TransferOutcome
    }

    private suspend fun transferOnServer(circleId: String, newOwnerId: String): TransferOutcome {
        val response = try {
            tracksApi.transferOwnership(circleId, TransferOwnershipRequest(newOwnerId = newOwnerId))
        } catch (e: Exception) {
            return TransferOutcome.NoAnswer(e)
        }
        if (response.isSuccessful && response.body()?.success == true) {
            circleDao.getById(circleId)?.let { circle ->
                circleDao.insert(circle.copy(isOwner = false, updatedAt = System.currentTimeMillis()))
            }
            return TransferOutcome.Done
        }
        val code = response.code()
        val errorCode = ApiErrors.codeOf(response)
        Log.w(TAG, "transferOwnership refused: $code $errorCode")
        return if (code >= 500 || code == 401 || code == 408 || code == 429) {
            TransferOutcome.NoAnswer(Exception(ApiErrors.plainLine(code, errorCode, HAND_OVER_NOT_DONE)))
        } else {
            TransferOutcome.Refused(code, errorCode)
        }
    }

    /**
     * The server's transfer after [handOver]'s record landed. Taken, or refused for good (the
     * member left, say: the chain then names an owner the server doesn't list, accepted as
     * rare), it's forgotten; with no answer it's kept, for the next try ([retryHandOvers]) or
     * the person's.
     */
    private suspend fun finishHandOver(circleId: String, handOver: com.pingolin.track.util.HandOvers.HandOver): Result<Unit> =
        when (val transfer = transferOnServer(circleId, handOver.target)) {
            TransferOutcome.Done -> {
                handOvers.forget(circleId)
                Result.success(Unit)
            }
            is TransferOutcome.Refused -> {
                Log.w(TAG, "hand-over of $circleId (record v${handOver.version}): the server refused its transfer for good (${transfer.status})")
                handOvers.forget(circleId)
                Result.failure(Exception(ApiErrors.plainLine(transfer.status, transfer.errorCode, HAND_OVER_NOT_DONE)))
            }
            is TransferOutcome.NoAnswer -> Result.failure(Exception(HAND_OVER_NOT_DONE))
        }

    /** Every hand-over this device recorded whose transfer hasn't landed: sent again. */
    internal suspend fun retryHandOvers() {
        for ((circleId, handOver) in handOvers.all()) {
            runCatching { finishHandOver(circleId, handOver) }
                .onFailure { Log.w(TAG, "hand-over of $circleId not finished: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * The record handing [circleId] over to [target], by the chain's owner: a rotation naming
     * [target] owner and this account a member, as the server's transfer does. Only while this
     * device holds the circle's current version (after catching up), and [target] is on the
     * complete list, confirmed here, with keys: the record includes them (the rotation plan wraps
     * to them, and the circle's records allow them in), so the new owner gets the new key. The
     * version it made, once the server took it.
     */
    private suspend fun handOverRecord(circleId: String, target: String): Result<Int> {
        if (personalSlot(circleId, "hand-over")) return Result.failure(Exception(HAND_OVER_NOT_DONE))
        if (!currentKeyGate.holdsCurrentVersion(circleId)) {
            catchUp(circleId)
            if (!currentKeyGate.holdsCurrentVersion(circleId)) return Result.failure(KeyNotCurrentException())
        }
        val circle = circleDao.getById(circleId) ?: return Result.failure(Exception(HAND_OVER_NOT_DONE))
        // The name the new key carries, or none: never a stop. Behind on the key, the
        // hand-over has stopped above (holdsCurrentVersion).
        val sealable = sealableName(circle)
        val self = sessionManager.getUserId() ?: return Result.failure(Exception(HAND_OVER_NOT_DONE))
        val keys = readAgainIfItPasses(
            passes = { r -> r.isFailure && r.exceptionOrNull() is java.io.IOException || r.getOrNull()?.let { it.code() >= 500 || it.code() == 429 } == true },
        ) { runCatching { tracksApi.getMemberPublicKeys(circleId) } }.getOrThrow()
        val memberKeys = keys.body()?.data?.takeIf { keys.isSuccessful }
            ?: return Result.failure(Exception(ApiErrors.plainLine(keys.code(), null, HAND_OVER_NOT_DONE)))
        if (!claimRotation(circleId)) return Result.failure(Exception(HAND_OVER_NOT_DONE))
        val change = KeyChange()
        return try {
            val plan = planRotationFor(circle, memberKeys, forDelivery = false)
                ?: return Result.failure(Exception(HAND_OVER_NOT_DONE))
            val send = try {
                buildOrImportOnce { recordedRotationFor(circle, plan, canRotate = true, self, sealable.orEmpty(), handOverTo = target, change = change) }
            } catch (e: RecordsNotImportedYet) {
                notImportedYet(circleId, "hand-over")
                return Result.failure(Exception(HAND_OVER_NOT_DONE))
            } ?: return Result.failure(Exception(HAND_OVER_NOT_DONE))
            when (val sent = sendRotation(circle, send.copy(sealsName = sealable != null), plan.pending.size)) {
                is RotationSent.Landed -> Result.success(sent.version)
                else -> Result.failure(Exception(HAND_OVER_NOT_DONE))
            }
        } catch (e: HandOverNotIncluded) {
            Log.i(TAG, "hand-over of $circleId: the member it goes to wouldn't get the new key here")
            Result.failure(Exception(HAND_OVER_NOT_DONE))
        } catch (e: Exception) {
            Log.w(TAG, "hand-over of $circleId: its record didn't land (${e.javaClass.simpleName})")
            rotationFailures[circleId] = System.currentTimeMillis()
            discardMinted(circleId, change)
            Result.failure(if (e is com.pingolin.track.util.StoreUnreadable) Exception(HAND_OVER_NOT_DONE) else e)
        } finally {
            releaseRotation(circleId)
        }
    }

    /** A hand-over whose record would leave out its new owner: not written. */
    private class HandOverNotIncluded : Exception()

    // ===== Circle ownership claims =====
    //
    // The client half of the dead man's switch for an unreachable owner. The server
    // notifies the owner the moment a claim opens and completes it only on silence for
    // the full window; everything here exists so that "open Pingolin to refuse" is
    // something the owner can actually do.

    /**
     * The circle's live claim, or `null` when there is none.
     *
     * `null` is the ORDINARY answer and not a failure. The route returns
     * `Option<OwnershipClaimResponse>`, so "nobody is claiming this circle" arrives as a
     * 200 whose `data` is JSON null — distinct from the [Result.failure] a real error
     * produces, and callers must keep them apart or every circle looks claimed or none do.
     *
     * Any active member may read this, so the answer is not owner-only.
     */
    suspend fun getOwnershipClaim(circleId: String): Result<OwnershipClaimResponse?> {
        return try {
            val response = tracksApi.getOwnershipClaim(circleId)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.data)
            } else {
                Result.failure(Exception(ownershipClaimError(response, "Couldn't check this circle just now.")))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getOwnershipClaim($circleId) failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Ask to take over [circleId]. Admin only — but that is the SERVER's call, not ours:
     * it answers 403 for a plain member and 400 if the caller already owns the circle, and
     * surfacing its refusal is more honest than a client-side guess that can drift from
     * the roster the server actually holds.
     *
     * A 409 becomes [OwnershipClaimExistsException] rather than a generic failure, so the
     * caller can show the claim that already exists instead of reporting that nothing
     * happened. Success is 201.
     */
    suspend fun claimOwnership(circleId: String): Result<OwnershipClaimResponse> {
        return try {
            val response = tracksApi.claimOwnership(circleId)
            val body = response.body()
            val claim = body?.data
            when {
                response.isSuccessful && body?.success == true && claim != null ->
                    Result.success(claim)
                response.code() == 409 -> Result.failure(
                    OwnershipClaimExistsException(
                        ownershipClaimError(response, "Somebody has already asked to take over this circle")
                    )
                )
                // The route's one 400: the caller already owns the circle.
                response.code() == 400 -> Result.failure(
                    Exception(ownershipClaimError(response, "You already own this circle."))
                )
                else -> Result.failure(
                    Exception(ownershipClaimError(response, "Couldn't ask to take over this circle just now."))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "claimOwnership($circleId) failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Refuse the claim on [circleId]. Owner only, permanent, and the whole reason this
     * feature reaches the client at all.
     *
     * 404 means there is no live claim — which is also what the caller sees if the claim
     * was withdrawn or already refused from another device, so the server's own sentence
     * is surfaced rather than a paraphrase that would imply a failure.
     */
    suspend fun refuseOwnershipClaim(circleId: String): Result<Unit> {
        return try {
            val response = tracksApi.refuseOwnershipClaim(circleId)
            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Ownership claim on $circleId refused")
                Result.success(Unit)
            } else {
                Result.failure(Exception(ownershipClaimError(response, "Couldn't refuse the claim just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "refuseOwnershipClaim($circleId) failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Withdraw the claim THIS user opened on [circleId]. 404 if they have none. */
    suspend fun withdrawOwnershipClaim(circleId: String): Result<Unit> {
        return try {
            val response = tracksApi.withdrawOwnershipClaim(circleId)
            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Ownership claim on $circleId withdrawn")
                Result.success(Unit)
            } else {
                Result.failure(Exception(ownershipClaimError(response, "Couldn't withdraw the claim just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "withdrawOwnershipClaim($circleId) failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * What a person is told about a failed claim call: this app's words, never the server's.
     * By kind ([ApiErrors.plainLine]: the routes' 403 and 404 among them), else [fallback].
     * The server's words go to the log.
     */
    private fun <T> ownershipClaimError(response: Response<ApiResponse<T>>, fallback: String): String {
        val refusal = ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())
        Log.w(TAG, "Ownership claim call refused: ${response.code()} ${refusal?.errorCode} ${response.body()?.error ?: refusal?.error}")
        return ApiErrors.plainLine(response.code(), refusal?.errorCode, fallback)
    }

    /**
     * An invitation the server refused, in this app's words: by its `error_code`, else, for
     * the route's common refusals (plain 400s with no code), by their words, which only pick
     * which of this app's lines to show and are never shown themselves.
     */
    private fun inviteRefusal(status: Int, errorCode: String?, said: String?): String {
        fun uncoded400(phrase: String) = errorCode == null && status == 400 && said.orEmpty().contains(phrase, ignoreCase = true)
        return when {
            errorCode == "circle_already_member" || uncoded400("already a member") -> "They're already in this circle."
            errorCode == "invite_already_exists" || uncoded400("pending invite") -> "They already have an invitation to this circle."
            errorCode == "validation_invalid_email" || uncoded400("email") -> "That email address doesn't look right."
            // Its own 429: not "a moment", which every other 429 is.
            errorCode == "invite_limit_reached" -> INVITE_LIMIT
            else -> ApiErrors.plainLine(status, errorCode, INVITE_NOT_SENT)
        }
    }

    suspend fun leaveCircle(circleId: String): Result<Unit> {
        return try {
            val response = tracksApi.leaveCircle(circleId)
            if (response.isSuccessful) {
                forgetCircleHere(circleId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to leave circle"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * This user is no longer in [circleId] — they left, deleted it, or withdrew their
     * consent, which leaves it, and the server said so: forget it on this phone.
     */
    suspend fun forgetCircleHere(circleId: String) {
        circleDao.deleteById(circleId)
        circleMemberDao.deleteByCircle(circleId)
        locationRepository.get().forgetCircleLocations(circleId)
        // What was waiting to sync for it goes too, with no line: they chose to leave.
        runCatching { pendingChanges.forgetCircle(circleId) }
        forgetCircleKeys(circleId)
    }

    /**
     * A stored row for a circle the server listed under the personal key's slot ([personalSlot]):
     * the row and its member rows go, and nothing else. Never the personal key's own slots, its
     * route, or anything queued under them.
     */
    private suspend fun dropNotACircle(circleId: String) {
        runCatching { circleDao.deleteById(circleId) }
        runCatching { circleMemberDao.deleteByCircle(circleId) }
    }

    /**
     * The circle's keys go with it when this user left it, deleted it or withdrew their consent
     * and the server said so: every key held for it, its refresh records and the circle's secret.
     * Never on a 404 or 403 alone: a circle only those say is gone ([deleteLocalCircle]) keeps
     * its keys.
     */
    private fun forgetCircleKeys(circleId: String) {
        runCatching { cryptoManager.forgetCircle(circleId) }
            .onFailure { Log.w(TAG, "forgetCircle failed for $circleId: ${it.javaClass.simpleName}") }
        lastKeyChains.remove(circleId)
        recordsJudged.remove(circleId)
        recordsAwaitList.remove(circleId)
        headVouched.remove(circleId)
        ownRemovals.forget(circleId)
        ownerRoleChanges.forget(circleId)
        handOvers.forget(circleId)
    }

    suspend fun deleteCircle(circleId: String): Result<Unit> {
        return try {
            val response = tracksApi.deleteCircle(circleId)
            if (response.isSuccessful) {
                circleDao.deleteById(circleId)
                circleMemberDao.deleteByCircle(circleId)
                locationRepository.get().forgetCircleLocations(circleId)
                runCatching { pendingChanges.forgetCircle(circleId) }
                forgetCircleKeys(circleId)
                Result.success(Unit)
            } else {
                Result.failure(Exception(ApiErrors.plainLine(response, "Couldn't delete the circle just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a circle from local database only (no server call).
     * Used to clean up stale circles when server returns 400/404.
     */
    suspend fun deleteLocalCircle(circleId: String) {
        circleDao.deleteById(circleId)
        circleMemberDao.deleteByCircle(circleId)
        // What was kept for it goes with it.
        runCatching { locationRepository.get().forgetCircleLocations(circleId) }
    }

    /**
     * Every member of [circleId]: [first] and the pages after it. Null when a later page
     * cannot be had. A partial roster must never pass for the whole one: the rotation's
     * roster check and the approvals' retain both read absence as "not a member".
     */
    private suspend fun allMemberPages(
        circleId: String,
        first: com.pingolin.track.data.remote.dto.PaginatedResponse<com.pingolin.track.data.remote.dto.MemberResponse>,
    ): List<com.pingolin.track.data.remote.dto.MemberResponse>? {
        val all = first.items.toMutableList()
        var page = first
        var pages = 1
        while (page.hasMore) {
            val cursor = page.cursor ?: return null
            if (++pages > MAX_MEMBER_PAGES) return null
            val next = tracksApi.getCircleMembers(circleId, limit = MEMBER_PAGE_SIZE, cursor = cursor)
            val body = next.body()?.takeIf { next.isSuccessful && it.success }?.data ?: return null
            all += body.items
            page = body
        }
        // By user id: a scan the server restarts from the first page repeats members.
        return all.distinctBy { it.userId }
    }

    /**
     * Fetch [circleId]'s complete member list and judge it ([applyMemberTrust]) without
     * touching the stored rows — for a circle this device has not stored yet. Null when
     * the list cannot be had whole.
     */
    private suspend fun judgeMemberList(circleId: String): CircleTrustSnapshot? {
        val selfUserId = sessionManager.getUserId() ?: return null
        val ownInvites = ownInvitesCheck(circleId)
        val response = tracksApi.getCircleMembers(circleId, limit = MEMBER_PAGE_SIZE)
        val first = response.body()?.takeIf { response.isSuccessful && it.success }?.data ?: return null
        val raw = allMemberPages(circleId, first) ?: return null
        raw.forEach { dto ->
            dto.signingPublicKey?.let { signingPubkeyCache[cacheKey(circleId, dto.userId)] = it }
            dto.sharingPublicKey?.let { sharingPubkeyCache[cacheKey(circleId, dto.userId)] = it }
        }
        return applyMemberTrust(circleId, raw, selfUserId, raw.associate { it.userId to (it.displayName ?: "Member") }, ownInvites)
    }

    /**
     * Every member of [circleId] the server lists, WHOLE (every page) and fresh, whether this
     * device shows them or not; null when that couldn't be had. "They left" is only ever read
     * from this: a member hidden here is still in the circle, and a roster that didn't load
     * says nothing.
     */
    suspend fun memberIdsWhole(circleId: String): Set<String>? = memberRolesWhole(circleId)?.keys

    /** [memberIdsWhole], with each member's role as the server lists it. */
    suspend fun memberRolesWhole(circleId: String): Map<String, String>? {
        val response = runCatching { tracksApi.getCircleMembers(circleId, limit = MEMBER_PAGE_SIZE) }.getOrNull() ?: return null
        val first = response.body()?.takeIf { response.isSuccessful && it.success }?.data ?: return null
        return runCatching { allMemberPages(circleId, first) }.getOrNull()?.associate { it.userId to it.role }
    }

    /**
     * The statement look: which of [ids], statements this account signed, the server holds in
     * [circleId], read from the primary in one snapshot. Asked only with an answer in hand: a
     * 404 is [com.pingolin.track.domain.pending.LookAnswer.NotFound] (gone only if the whole
     * circle list agrees), and no answer, 429 or 5xx is "couldn't look".
     */
    suspend fun statementsHeld(circleId: String, ids: List<String>): com.pingolin.track.domain.pending.LookAnswer {
        if (ids.isEmpty()) return com.pingolin.track.domain.pending.LookAnswer.Held(emptySet())
        val hex = ids.map { com.pingolin.track.domain.pending.PendingChanges.hexId(it) }.distinct()
        val response = runCatching {
            tracksApi.statementsHeld(circleId, com.pingolin.track.data.remote.dto.StatementsHeldRequest(hex))
        }.getOrNull() ?: return com.pingolin.track.domain.pending.LookAnswer.CouldNotLook
        if (response.code() == 404) return com.pingolin.track.domain.pending.LookAnswer.NotFound
        val held = response.body()?.takeIf { response.isSuccessful && it.success }?.data
            ?: return com.pingolin.track.domain.pending.LookAnswer.CouldNotLook
        // Only the ids asked about count: the server can't name one of ours we didn't ask about.
        val asked = hex.toSet()
        return com.pingolin.track.domain.pending.LookAnswer.Held(
            held.held.map { com.pingolin.track.domain.pending.PendingChanges.hexId(it.statementId) }.filter { it in asked }.toSet()
        )
    }

    /**
     * When a change that signs ends: [dead] (statement ids its runs signed that the server
     * doesn't hold) go dead on this device, durably, and an invitation the server holds ([held],
     * with [email]) becomes this user's invitee record, bound to that one statement. False when
     * the dead ids couldn't be written: the change is parked, nothing ends.
     */
    fun settleSignedStatements(
        circleId: String,
        email: String?,
        held: com.pingolin.track.domain.pending.SignedStatement?,
        dead: List<String>,
    ): Boolean {
        if (!keyAccessApprovals.markDeadDurably(circleId, dead)) return false
        val expires = held?.expiresAtSecs
        if (held != null && email != null && expires != null) {
            keyAccessApprovals.approveInvitee(circleId, held.subject, email, held.id, expires)
        }
        return true
    }

    /**
     * Fetch [circleId]'s complete member list, judge it ([applyMemberTrust]) and store the
     * members this device shows; then hand the circle to anyone owed it.
     */
    suspend fun refreshMembers(circleId: String): Result<List<CircleMemberEntity>> =
        refreshMembersAndJudge(circleId, deliver = true)

    /**
     * [refreshMembers] without handing the circle to anyone owed it: for a reader that only
     * needs the names this device shows (NotificationNames), on paths — a push's fetch, the
     * notification list — that must stay light.
     */
    suspend fun refreshRoster(circleId: String): Result<List<CircleMemberEntity>> =
        refreshMembersAndJudge(circleId, deliver = false)

    private suspend fun refreshMembersAndJudge(
        circleId: String,
        deliver: Boolean,
        checkRecords: Boolean = true,
    ): Result<List<CircleMemberEntity>> {
        if (personalSlot(circleId, "member list")) return Result.failure(NotACircle(circleId))
        return try {
            // Before the member list: see [OwnInvitesCheck].
            val ownInvites = ownInvitesCheck(circleId)
            val response = tracksApi.getCircleMembers(circleId, limit = MEMBER_PAGE_SIZE)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val currentUserId = sessionManager.getUserId()
                // The WHOLE roster, every page, not the first one (50) alone.
                val rawMembers = allMemberPages(circleId, response.body()!!.data!!)
                    ?: return Result.failure(Exception("Failed to fetch the rest of the member list"))
                // Per-member display-name decrypt is pure CPU (UniFFI) and refreshMembers
                // runs concurrently per-circle (MainViewModel fan-out), so move it off the
                // Main thread.
                val members = withContext(Dispatchers.Default) {
                    rawMembers.map { dto ->
                    // E2EE: Prefer encrypted display name (decrypted with circle key)
                    val opened = if (dto.encryptedDisplayName != null &&
                        dto.encryptedDisplayName.nonce.isNotBlank() &&
                        dto.encryptedDisplayName.ciphertext.isNotBlank() &&
                        cryptoManager.hasCircleKey(circleId)) {
                        try {
                            val encData = uniffi.pingolin_mobile.EncryptedData(
                                nonce = dto.encryptedDisplayName.nonce,
                                ciphertext = dto.encryptedDisplayName.ciphertext
                            )
                            cryptoManager.decryptDataV2(circleId, encData, "display_name")
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    val name = opened ?: dto.displayName ?: "Member"
                    // Cache the member's keys for later wrap calls and their security
                    // details. Nobody is pinned for being listed: pins are made only for
                    // members this device confirms (in applyMemberTrust).
                    dto.signingPublicKey?.let { signingPubkeyCache[cacheKey(circleId, dto.userId)] = it }
                    dto.sharingPublicKey?.let { sharingPubkeyCache[cacheKey(circleId, dto.userId)] = it }
                    // Why their location may not be updating, refreshed with the list
                    // itself. An older server sends nothing: sharing.
                    val (locationSharing, sharingPausedUntil) =
                        com.pingolin.track.ui.circles.MemberSharing.fromWire(dto.locationSharing, dto.sharingPausedUntil)
                    CircleMemberEntity(
                        circleId = circleId,
                        userId = dto.userId,
                        displayName = name,
                        role = dto.role,
                        canSeeRealtime = dto.canSeeRealtime,
                        canSeeHistory = dto.canSeeHistory,
                        joinedAt = (dto.joinedDayBucket?.toLong() ?: 0L) * 86400L * 1000L,
                        nameOpened = opened != null,
                        locationSharing = locationSharing,
                        sharingPausedUntil = sharingPausedUntil,
                        historySharing = com.pingolin.track.ui.circles.MemberSharing.historyFromWire(dto.historySharing),
                    )
                    }
                }
                // Who this device trusts, from the COMPLETE member list (allMemberPages
                // above): departures retired first, then confirmation, pins, and what the
                // member list may show.
                val trust = currentUserId?.let { self ->
                    applyMemberTrust(circleId, rawMembers, self, members.associate { it.userId to it.displayName }, ownInvites)
                }
                val shown = trust?.let { t -> members.filter { it.userId in t.result.shown } } ?: members
                // Update the per-circle "can I rotate?" cache for the current user.
                if (currentUserId != null) {
                    val me = rawMembers.firstOrNull { it.userId == currentUserId }
                    canRotateKeysCache[circleId] = me?.canRotateKeys ?: false
                }
                // Replace members atomically (single transaction) so the
                // observeByCircle flow never emits the empty intermediate state
                // between delete and insert — an empty frame would flash peer
                // markers as "U" (Unknown) on the map. Members this device
                // does not confirm have no row: not listed, not on the map, not counted.
                circleMemberDao.replaceForCircle(circleId, shown)
                // Drop cached location rows whose userId is no longer a
                // member — otherwise their last upload keeps rendering as
                // a "U" (Unknown) marker on the map.
                val keepIds = shown.map { it.userId }
                if (keepIds.isNotEmpty()) {
                    val pruned = locationRepository.get()
                        .pruneLocationsForMissingMembers(circleId, keepIds)
                    if (pruned > 0) {
                        Log.i(TAG, "Pruned $pruned stale location rows for non-members of $circleId")
                    }
                }
                // Whoever hides their history here leaves only their newest position on this phone,
                // on every list read.
                runCatching { locationRepository.get().purgeHiddenHistory(circleId, shown) }
                Log.d(TAG, "Refreshed ${shown.size} of ${members.size} members for circle $circleId")
                // Records that waited on a member list are judged again against this one.
                if (checkRecords && trust != null && circleId in recordsAwaitList) syncRefreshRecords(listRead = setOf(circleId))
                // The SDK's eligibility check for this account's own row, against the statements this
                // list serves.
                if (trust != null) noteOwnRow(circleId)
                // This account's own sealed name there, when owed: after this load, off its path.
                rawMembers.firstOrNull { com.pingolin.track.util.AccountIds.same(it.userId, currentUserId) }?.let { own ->
                    ownNameScope.launch {
                        runCatching { sealOwnNameIfOwed(circleId, own) }
                            .onFailure { Log.w(TAG, "Own name for $circleId: ${it.javaClass.simpleName}") }
                    }
                }
                // And the late-joiner check.
                if (trust != null) runCatching { raiseLateJoinerQuestions(circleId, members.associate { it.userId to it.displayName }) }
                // A removal this device has yet to record, which this account has since reconsidered.
                if (trust != null) clearRemovalsReconsidered(circleId, rawMembers)
                // Anyone owed the circle whom this device may serve gets it now.
                if (deliver && trust != null && trust.result.confirmed.size > 1) scheduleDelivery(circleId)
                Result.success(shown)
            } else {
                val error = response.body()?.error ?: response.errorBody()?.string() ?: "Unknown error"
                Log.w(TAG, "Failed to fetch members for circle $circleId: $error (code=${response.code()})")
                // The server MIGHT be saying we're not in this circle anymore — but a
                // 404/403 from this one endpoint is also what a mid-restart pod
                // returns, and the error-text match is looser still. CONFIRM against
                // the authoritative circle list before deleting anything (see
                // [isStillAMember]); an inconclusive answer keeps the local row and
                // simply retries next tick.
                val looksRemoved = response.code() == 404 ||
                    response.code() == 403 ||
                    error.contains("Circle not found", ignoreCase = true) ||
                    error.contains("not a member", ignoreCase = true)
                if (looksRemoved) {
                    if (isStillAMember(circleId) == false) {
                        Log.i(TAG, "Pruning local circle $circleId — confirmed absent from the circle list")
                        deleteLocalCircle(circleId)
                    } else {
                        Log.w(TAG, "Circle $circleId member fetch failed but the circle list does not confirm removal — keeping local state")
                    }
                }
                Result.failure(Exception(ApiErrors.plainLine(response.code(), ApiErrors.parse(error)?.errorCode, "Couldn't load the members just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception refreshing members for circle $circleId", e)
            Result.failure(e)
        }
    }

    // ===== Who is in the circle =====

    /** The `error_code` of a refused response, or null. Consumes the error body. */
    private fun errorCodeOf(response: Response<*>): String? = runCatching {
        response.errorBody()?.string()?.let { body ->
            com.google.gson.JsonParser.parseString(body).asJsonObject.get("error_code")
                ?.takeIf { it.isJsonPrimitive }?.asString
        }
    }.getOrNull()

    /** A member as the confirmation rules see them. */
    private fun com.pingolin.track.data.remote.dto.MemberResponse.toConfirmationMember() = MemberConfirmation.Member(
        userId = userId,
        sharingKeyHex = sharingPublicKey,
        signingKeyHex = signingPublicKey,
        keyVersion = keyVersion,
        statements = (listOfNotNull(invitation) + vouches.orEmpty())
            .map { MembershipStatements.Signed(it.statement, it.signature) },
    )

    /** This device's own sharing key, hex (the SDK exports it base64). */
    private fun selfSharingKeyHex(): String? = runCatching {
        java.util.Base64.getDecoder().decode(cryptoManager.exportPublicKey().trim())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Whether [seedFromHeldPinsOnce] has run (or found nothing to do) for this process's account. */
    @Volatile private var heldPinSeedChecked = false

    /**
     * The move to signed invitations. The first time this code runs for this account here,
     * every circle with no approval record is seeded with the members this device ALREADY had
     * pinned in it, as entries from before signed invitations: a device with no earlier
     * approvals has no seed, and its members would otherwise all be hidden. Runs before
     * anything here touches a pin — pins are made only on confirmation from then on, so the
     * set it reads cannot grow into a seed later. Waits until the key store (where the pins
     * live) is loaded.
     */
    private fun seedFromHeldPinsOnce() {
        if (heldPinSeedChecked) return
        val owner = sessionManager.getUserId() ?: return
        if (keyAccessApprovals.heldPinsSeeded()) {
            heldPinSeedChecked = true
            return
        }
        if (runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull().isNullOrBlank()) return
        val export = runCatching { cryptoManager.exportMemberPins(owner) }.getOrNull() ?: return
        val byCircle = circlePinsIn(export, owner) ?: return
        val seeded = keyAccessApprovals.seedFromHeldPins(byCircle)
        heldPinSeedChecked = true
        Log.i(TAG, "Seeded $seeded circle(s) from the members this device had pinned before signed invitations")
    }

    /**
     * Circle id → members pinned in it, from exportMemberPins' JSON ("pins" keyed
     * "<circle>:<user>"; identity pins and markers are not circle pins). Null when it
     * cannot be read.
     */
    private fun circlePinsIn(export: String, owner: String): Map<String, Set<String>>? = runCatching {
        val pins = com.google.gson.JsonParser.parseString(export).asJsonObject.getAsJsonObject("pins")
            ?: return@runCatching emptyMap<String, Set<String>>()
        pins.keySet()
            .filterNot { it.startsWith("*") || it.startsWith("~") }
            .mapNotNull { key ->
                val circle = key.substringBefore(':', "")
                val user = key.substringAfter(':', "")
                if (circle.isEmpty() || user.isEmpty() || user.equals(owner, ignoreCase = true)) null else circle to user
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, users) -> users.toSet() }
    }.getOrNull()

    /**
     * Judge [circleId]'s COMPLETE member list ([rawMembers]) and act on it, in the one
     * order that is right: departures first — their approvals go and leave tombstones —
     * then confirmation ([MemberConfirmation]), then pins for confirmed members only
     * (receive-only members' keys are this device's own record), then every other pin in
     * the circle is retired. Raises "Is this really {name}?" and, on owner/admin devices,
     * the questions about members this device cannot confirm, and publishes "Joining…"
     * and the kept-out count.
     */
    private suspend fun applyMemberTrust(
        circleId: String,
        rawMembers: List<com.pingolin.track.data.remote.dto.MemberResponse>,
        selfUserId: String,
        names: Map<String, String>,
        ownInvites: OwnInvitesCheck? = null,
    ): CircleTrustSnapshot {
        seedFromHeldPinsOnce()
        val activeVersion = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrDefault(0)
        // The invitation each member joined with: what an invitation record of this user must
        // match before it makes them this user's own.
        val invitationIdByUser = rawMembers.mapNotNull { m ->
            m.invitation?.statement?.let { runCatching { membershipStatements.parse(it)?.statementId }.getOrNull() }
                ?.let { m.userId to it }
        }.toMap()
        keyAccessApprovals.retain(circleId, rawMembers.map { it.userId }, activeVersion, invitationIdByUser)
        val myRole = rawMembers.firstOrNull { it.userId.equals(selfUserId, ignoreCase = true) }?.role
        val ownerOrAdmin = myRole == "owner" || myRole == "admin"
        // Members' emails, which member-public-keys shows owners and admins only: fetched
        // when something needs them, at most once per pass.
        var emails: Map<String, String>? = null
        suspend fun emails(): Map<String, String> = emails ?: (
            if (!ownerOrAdmin) emptyMap()
            else runCatching { tracksApi.getMemberPublicKeys(circleId).body()?.data }.getOrNull().orEmpty()
                .mapNotNull { m -> m.email?.let { m.userId to it } }.toMap()
            ).also { emails = it }
        // This user's invitees who had no account when invited: matched by email now,
        // while any are waiting.
        if (keyAccessApprovals.hasInvitedEmails(circleId)) {
            keyAccessApprovals.approveInvitedEmails(circleId, emails(), invitationIdByUser)
                .takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "${it.size} invitee(s) of this user joined $circleId") }
        }

        val roster = rawMembers.map { it.toConfirmationMember() }
        suspend fun judge(
            approvals: Map<String, com.pingolin.track.util.KeyAccessApprovals.Origin> = keyAccessApprovals.origins(circleId),
        ): MemberConfirmation.Result {
            val known = MemberConfirmation.Known(
                approvals = approvals,
                preRound2Seed = keyAccessApprovals.hasPreRound2Seed(circleId),
                tombstones = keyAccessApprovals.tombstones(circleId),
                // A departed member's statements, and invitations withdrawn or refused here:
                // none of them confirms anybody.
                tombstonedStatementIds = keyAccessApprovals.tombstonedStatementIds(circleId) +
                    keyAccessApprovals.deadInvitations(circleId),
                pinnedSharingKey = { memberPins.pinnedSharingKey(circleId, it) },
                pinnedSigningKey = { memberPins.pinnedSigningKey(circleId, it) },
                isOwnPreviousIdentity = { sharing, signing -> memberPins.isOwnPreviousIdentity(sharing, signing) },
                trustedElsewhere = { keyAccessApprovals.trustedInOtherCircles(circleId) },
                vouchedByOwnHead = headVouched[circleId].orEmpty(),
            )
            return withContext(Dispatchers.Default) {
                MemberConfirmation.evaluate(
                    selfUserId = selfUserId,
                    selfSharingKeyHex = selfSharingKeyHex(),
                    selfSigningKeyHex = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull(),
                    selfEmail = sessionManager.getEmail(),
                    circleId = circleId,
                    roster = roster,
                    known = known,
                    statements = membershipStatements,
                )
            }
        }
        var result = judge()
        // Nobody confirmed here, and nothing to start from (a member invited before signed
        // invitations, on a new phone; a password reset without the recovery key): the
        // member who handed this device its first copy is where it starts — trust on first
        // use, once.
        val firstSender = firstKeySenders[circleId]
        // Never past an invitation to an address this device doesn't hold as this account's (the e-mail
        // trust check): that one waits for the address, it isn't taken on trust.
        if (!result.anchored && firstSender != null && roster.any { it.userId == firstSender } &&
            !keyAccessApprovals.hasPreRound2Seed(circleId) && !result.ownEmailInvitationUnconfirmed
        ) {
            keyAccessApprovals.approve(circleId, listOf(firstSender), com.pingolin.track.util.KeyAccessApprovals.Origin.ANCHOR)
            Log.i(TAG, "$circleId: nobody confirmed here; starting from whoever sent the first copy")
            result = judge()
        }
        // Once per circle, on this complete list, never while behind or forked, a stray
        // "Let them in" choice an older build left goes, and its member is asked again.
        // The mark follows only once every drop is on disk.
        if (!keyAccessApprovals.strayChoicesChecked(circleId) && currentKeyGate.holdsCurrentVersion(circleId)) {
            val origins = keyAccessApprovals.origins(circleId)
            val withoutChoices = judge(origins.filterValues { it != com.pingolin.track.util.KeyAccessApprovals.Origin.OWN })
            val strays = MemberConfirmation.strayChoices(origins, withoutChoices, result.ownAdmissions)
            if (keyAccessApprovals.dropChoicesDurably(circleId, strays)) {
                keyAccessApprovals.markStrayChoicesChecked(circleId)
                if (strays.isNotEmpty()) {
                    Log.i(TAG, "$circleId: ${strays.size} choice(s) an older build left without an admission; asked again")
                    result = judge()
                }
            }
        }
        // Never recorded for a confirmation resting only on this account's own statements from
        // before a reset: it would outlive the restored identity. Judged again on every pass
        // instead.
        val recordable = result.newlyConfirmed.filterKeys { it !in result.previousKeyOnly }
        for ((origin, ids) in recordable.entries.groupBy({ it.value }, { it.key })) {
            keyAccessApprovals.approve(circleId, ids, origin)
        }
        keyAccessApprovals.clearTombstones(circleId, recordable.keys)
        keyAccessApprovals.recordStatementIds(circleId, result.statementIds)
        // Which of this account's own invitations and admissions each approval here (made on a statement, or by
        // this person's "Let them in") rests on, while they verify under its key. After a password reset they
        // no longer do, and this record is what still says they were this account's.
        recordApprovalBasis(circleId, result)
        // This user's own invitations that the server withdrew, or no longer lists.
        settleOwnInvitations(circleId, ownInvites, result, ownerOrAdmin)

        val dtos = rawMembers.associateBy { it.userId }
        val alerts = mutableListOf<MemberKeyAlert>()
        fun alert(userId: String, state: PinState, previousPinnedAt: Long, sharing: String, signing: String, receiveOnly: Boolean = false) {
            alerts += MemberKeyAlert(
                circleId = circleId,
                userId = userId,
                displayName = names[userId] ?: UNNAMED_MEMBER,
                state = state,
                previousPinnedAt = previousPinnedAt,
                newSharingHex = sharing,
                newSigningHex = signing,
                number = computeSafetyNumber(userId, sharing, signing),
                receiveOnly = receiveOnly,
            )
        }
        withContext(Dispatchers.Default) {
            // Coalesce the per-pin encrypted key-persist writes into one flush.
            cryptoManager.beginBulkKeyImport()
            try {
                for (userId in result.confirmed.keys) {
                    if (userId.equals(selfUserId, ignoreCase = true)) continue
                    val dto = dtos[userId] ?: continue
                    val sharing = dto.sharingPublicKey ?: continue
                    val signing = dto.signingPublicKey ?: continue
                    if (result.receiveOnly(userId)) {
                        // An email invitee: their keys are recorded here, used only to wrap to
                        // them, never pinned.
                        val recorded = keyAccessApprovals.receiveOnlyKeys(circleId)[userId]
                        if (recorded == null) {
                            keyAccessApprovals.recordReceiveOnlyKeys(circleId, userId, sharing, signing)
                        } else if (!recorded.first.equals(sharing, true) || !recorded.second.equals(signing, true)) {
                            alert(userId, PinState.CHANGED_BOTH, 0L, sharing, signing, receiveOnly = true)
                        }
                        continue
                    }
                    // Pinned on confirmation only: first with the keys the statement
                    // that confirmed them bound — what its signer saw — then checked against
                    // what the member list shows now.
                    result.boundKeys[userId]?.let { (boundSharing, boundSigning) ->
                        if (!boundSharing.equals(sharing, true) || !boundSigning.equals(signing, true)) {
                            runCatching { cryptoManager.pinMemberKeys(circleId, userId, boundSharing, boundSigning) }
                        }
                    }
                    try {
                        val pin = cryptoManager.pinMemberKeys(circleId, userId, sharing, signing)
                        when (pin.state) {
                            PinState.NEW_PINNING, PinState.MATCHED -> {}
                            PinState.CHANGED_SHARING, PinState.CHANGED_SIGNING, PinState.CHANGED_BOTH -> {
                                alert(userId, pin.state, pin.previousPinnedAt, sharing, signing)
                                Log.w(TAG, "Pin divergence for $circleId / $userId: state=${pin.state}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "pin_member_keys failed for $circleId/$userId: ${e.message}")
                    }
                }
            } finally {
                cryptoManager.endBulkKeyImport()
            }
        }
        // Every pin in this circle but confirmed members' goes: an unconfirmed or
        // receive-only member's copies are then refused here, and pins an older build
        // made by merely viewing a member list retire with them. Identity pins stay.
        val keep = result.confirmed.keys.filterNot { result.receiveOnly(it) } + selfUserId
        runCatching { cryptoManager.retainMemberPins(circleId, keep.distinct()) }
            .onSuccess { retired -> if (retired > 0u) Log.i(TAG, "Retired $retired pin(s) in $circleId of people this device does not confirm") }
            .onFailure { Log.w(TAG, "retainMemberPins failed for $circleId: ${it.message}") }

        // A statement about keys other than the ones this device pinned for them: the
        // question, never a new pin.
        for (userId in result.subjectConflict) {
            val dto = dtos[userId] ?: continue
            val sharing = dto.sharingPublicKey ?: continue
            val signing = dto.signingPublicKey ?: continue
            val pinnedSharing = memberPins.pinnedSharingKey(circleId, userId)
            val pinnedSigning = memberPins.pinnedSigningKey(circleId, userId)
            if (!sharing.equals(pinnedSharing, true) || (pinnedSigning != null && !signing.equals(pinnedSigning, true))) {
                alert(userId, PinState.CHANGED_BOTH, 0L, sharing, signing)
            }
        }
        alerts.forEach { enqueueMemberKeyAlert(it) }

        val circle = circleDao.getById(circleId)
        // Asked while this phone is behind too: the answer then waits and goes out by itself.
        // Not asked again about someone whose answer is already waiting.
        val ask = ownerOrAdmin
        val answered = runCatching { pendingChanges.waitingTargets(circleId, com.pingolin.track.domain.pending.PendingKind.LET_IN) }
            .getOrDefault(emptySet())
        raiseAccessQuestions(circle, result, dtos, names, if (ask && result.askable.isNotEmpty()) emails() else emptyMap(), ask, answered)

        val circleVersion = circle?.keyVersion ?: activeVersion
        val joining = result.confirmed.keys.filter { userId ->
            !userId.equals(selfUserId, ignoreCase = true) &&
                dtos[userId]?.let { it.keyVersion == null || it.keyVersion < circleVersion } == true
        }.toSet()
        val keptOut = if (ownerOrAdmin) result.keptOut.size else 0
        // A member listed with a sharing key and no signing key runs an app from before
        // signing keys, usually after a password reset. Nobody here can let them back in
        // until they update, so the server tells THEM; the list says so.
        val askedToUpdate = rawMembers
            .filter { m ->
                !m.userId.equals(selfUserId, ignoreCase = true) &&
                    (m.status == null || m.status == "active") &&
                    !m.sharingPublicKey.isNullOrBlank() && m.signingPublicKey.isNullOrBlank()
            }
            .filter { m -> m.updateRequested || requestMemberUpdate(circleId, m.userId) }
            .map { it.userId }
            .toSet()
        _memberStates.update { it + (circleId to CircleMemberStates(joining, keptOut, askedToUpdate)) }
        if (result.invalid.isNotEmpty() || result.signerUnconfirmed.isNotEmpty() || result.signerChanged.isNotEmpty() ||
            result.statementLess.isNotEmpty() || result.subjectConflict.isNotEmpty()
        ) {
            // Diagnostics: counts only.
            Log.i(
                TAG,
                "Members of $circleId this device does not confirm: invalid=${result.invalid.size} " +
                    "inviterUnconfirmed=${result.signerUnconfirmed.size} inviterChanged=${result.signerChanged.size} " +
                    "olderApp=${result.statementLess.size} conflict=${result.subjectConflict.size} " +
                    "selfReceiveOnly=${result.selfReceiveOnly} ownRowEligible=${ownRowEligible[circleId]}"
            )
        }

        return CircleTrustSnapshot(
            result = result,
            roster = roster,
            accessRequested = rawMembers.filter { it.accessRequested }.map { it.userId }.toSet(),
            ownerOrAdmin = ownerOrAdmin,
            // Serves any member it confirms only as the circle's creator, by its own record:
            // a role the server reports can narrow that, never grant it.
            createdHere = myRole == "owner" && keyAccessApprovals.createdHere(circleId),
            rows = dtos,
        ).also { trustSnapshots[circleId] = it }
    }

    /**
     * Whether a member confirmed here handed [member] the copy they hold: their stored wrap
     * verifies under that member's pinned key (or this user's own). A copy they signed for
     * themselves proves nothing about them.
     */
    private fun handedOverByConfirmed(circleId: String, snapshot: CircleTrustSnapshot, member: MemberConfirmation.Member): Boolean {
        val row = snapshot.rows[member.userId] ?: return false
        val signer = row.wrapSignerUserId ?: return false
        val signature = row.wrapSignature ?: return false
        val version = row.keyVersion ?: return false
        val wrap = row.encryptedCircleKey ?: return false
        val ephemeral = row.ephemeralPublicKey ?: return false
        if (signer.equals(member.userId, ignoreCase = true)) return false
        val selfUserId = sessionManager.getUserId() ?: return false
        val confirmedSigner = snapshot.result.confirmed.keys.firstOrNull { it.equals(signer, ignoreCase = true) } ?: return false
        if (snapshot.result.receiveOnly(confirmedSigner)) return false
        val key = if (signer.equals(selfUserId, ignoreCase = true)) {
            runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull()
        } else {
            memberPins.pinnedSigningKey(circleId, confirmedSigner)
        } ?: return false
        return WRAP_DOMAINS.any { domain ->
            runCatching {
                cryptoManager.verifyWrap(
                    key,
                    signature,
                    uniffi.pingolin_mobile.SignWrapParams(
                        domain = domain,
                        senderUserId = signer,
                        recipientUserId = member.userId,
                        circleId = circleId,
                        placeId = "",
                        keyVersion = version.toUInt(),
                        ephemeralPubkeyHex = ephemeral,
                        wrapNonceHex = wrap.nonce,
                        wrapCiphertextHex = wrap.ciphertext,
                    ),
                )
            }.getOrDefault(false)
        }
    }

    /**
     * Owner/admin devices vouch, once, for the members only they can confirm (see
     * [AdmissionBackfill]): an admission each, so every other member's app can confirm them
     * too. Returns how many the server stored.
     */
    private suspend fun backfillAdmissions(circleId: String): Int {
        val snapshot = trustSnapshots[circleId] ?: return 0
        val selfUserId = sessionManager.getUserId() ?: return 0
        val candidates = AdmissionBackfill.candidates(
            selfUserId = selfUserId,
            // Receive-only lifted where the SDK's eligibility check counts this account's
            // own row.
            result = snapshot.result.copy(selfReceiveOnly = receiveOnlyHere(circleId, snapshot.result)),
            roster = snapshot.roster,
            preRound2Seed = keyAccessApprovals.hasPreRound2Seed(circleId),
            ownerOrAdmin = snapshot.ownerOrAdmin,
            alreadyBackfilled = keyAccessApprovals.backfilled(circleId),
            pinnedSharingKey = { memberPins.pinnedSharingKey(circleId, it) },
            pinnedSigningKey = { memberPins.pinnedSigningKey(circleId, it) },
            handedOverByConfirmed = { handedOverByConfirmed(circleId, snapshot, it) },
        )
        // On a circle with refresh records it signs only as an owner or admin in the chain,
        // and only for a member the chain counts here.
        val recorded = runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)
        fun chainRole(userId: String) = runCatching { cryptoManager.chainRole(circleId, userId) }.getOrNull()
        if (recorded && chainRole(selfUserId) != uniffi.pingolin_mobile.CircleRole.OWNER &&
            chainRole(selfUserId) != uniffi.pingolin_mobile.CircleRole.ADMIN
        ) return 0
        var stored = 0
        for (member in candidates) {
            val userId = member.userId
            if (recorded && chainRole(userId) == null) continue
            // Each id is saved before its request goes out, and looked for before signing again:
            // done once the server holds one; no answer, the next pass looks.
            val ids = keyAccessApprovals.backfillIds(circleId, userId)
            if (ids.isNotEmpty()) {
                val look = statementsHeld(circleId, ids.toList())
                if (look !is com.pingolin.track.domain.pending.LookAnswer.Held) continue
                if (look.hexIds.isNotEmpty()) {
                    keyAccessApprovals.markBackfilled(circleId, listOf(userId))
                    continue
                }
            }
            if (ids.size >= MAX_BACKFILL_IDS) {
                Log.w(TAG, "backfill: $MAX_BACKFILL_IDS admissions for a member of $circleId, none held; not signing again")
                continue
            }
            val admitted = admitStatement(
                circleId, userId, keys = null,
                onSigned = { signed ->
                    if (!keyAccessApprovals.addBackfillId(circleId, userId, signed.id)) throw com.pingolin.track.domain.pending.StatementNotSaved()
                },
                // The same check at the signature: asked again once its version is fixed, so a
                // removal accepted while this pass went on is never signed over (as
                // PendingRun.beforeSend).
                beforeSend = if (!recorded) null else {
                    {
                        val mine = chainRole(selfUserId)
                        if (chainRole(userId) == null ||
                            (mine != uniffi.pingolin_mobile.CircleRole.OWNER && mine != uniffi.pingolin_mobile.CircleRole.ADMIN)
                        ) throw com.pingolin.track.domain.pending.RemovedSinceDecided()
                    }
                },
            )
            if (admitted.isSuccess) {
                keyAccessApprovals.markBackfilled(circleId, listOf(userId))
                stored++
            }
        }
        if (stored > 0) Log.i(TAG, "Vouched for $stored member(s) of $circleId whom other members' apps could not confirm")
        return stored
    }

    /**
     * Whether [userId]'s last word in [circleId]'s refresh records is a removal that counts, at a
     * version above [tapVersion] (the version this device had accepted when its person decided;
     * unknown counts as none). Then a waiting change that would sign for them ends with
     * [PendingLines.memberLeft]'s line. False on a circle without records; null when it can't be
     * told now (it waits). [keys]: theirs, for someone off the member list (an invitee).
     */
    internal suspend fun removedSinceDecided(circleId: String, userId: String, tapVersion: Int?, keys: Pair<String, String>? = null): Boolean? {
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return false
        val snapshot = trustSnapshots[circleId]
            ?: refreshMembersAndJudge(circleId, deliver = false).getOrNull()?.let { trustSnapshots[circleId] }
            ?: return null
        if ((runCatching { cryptoManager.refreshHeadVersions()[circleId] }.getOrNull() ?: 0) == 0) syncRefreshRecords()
        val members = RefreshRecords.recordMembers(snapshot) { id -> confirmedKeys(circleId, id, snapshot) }.toMutableList()
        if (members.none { it.userId.equals(userId, ignoreCase = true) }) {
            members += uniffi.pingolin_mobile.RecordMember(
                userId, keys?.first, keys?.second, false, false, null, emptyList(), false, true,
            )
        }
        val eligibility = runCatching {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
        }.getOrNull() ?: return null
        val row = eligibility.firstOrNull { it.userId.equals(userId, ignoreCase = true) } ?: return null
        val removedAt = row.removedAtVersion ?: return false
        return removedAt > (tapVersion ?: 0)
    }

    /**
     * The owner/admin questions, refreshed from [result]: a member invited by someone this
     * device cannot confirm. A member invited from an app older than signed invitations is
     * never asked about: hidden, and counted as kept out. Asked while this device is behind
     * too: the answer then waits as a change that signs only once this device holds the
     * current version, and nobody whose answer already waits ([answered]) is asked again.
     */
    private fun raiseAccessQuestions(
        circle: CircleEntity?,
        result: MemberConfirmation.Result,
        dtos: Map<String, com.pingolin.track.data.remote.dto.MemberResponse>,
        names: Map<String, String>,
        emails: Map<String, String>,
        ask: Boolean,
        answered: Set<String> = emptySet(),
    ) {
        val circleId = circle?.id ?: return
        val wanted = if (!ask) emptyList() else result.askable.mapNotNull { userId ->
            if ("$circleId:$userId" in keyAccessDeferred) return@mapNotNull null
            if (answered.any { it.equals(userId, ignoreCase = true) }) return@mapNotNull null
            val dto = dtos[userId] ?: return@mapNotNull null
            KeyAccessRequest(
                circleId = circleId,
                circleName = circle.name,
                userId = userId,
                displayName = names[userId]?.takeIf { it.isNotBlank() } ?: "A new member",
                email = emails[userId],
                reason = KeyAccessRequest.Reason.UNCONFIRMED_INVITER,
                number = dto.sharingPublicKey?.let { sharing ->
                    dto.signingPublicKey?.let { signing -> computeSafetyNumber(userId, sharing, signing) }
                },
                sharingKeyHex = dto.sharingPublicKey,
                signingKeyHex = dto.signingPublicKey,
            )
        }
        fun ours(r: KeyAccessRequest) = r.circleId == circleId && r.reason == KeyAccessRequest.Reason.UNCONFIRMED_INVITER
        val before = _keyAccessRequests.value.filter(::ours)
        _keyAccessRequests.update { requests -> requests.filterNot(::ours) + wanted }
        // Taken to the person, not left for whoever next opens the Circles screen; a
        // question that went away takes its notification with it.
        for (request in wanted) if (before.none { it.userId == request.userId }) {
            questionNotifier.waitingToJoin(circleId, circle.name, request.userId, request.displayName)
        }
        for (request in before) if (wanted.none { it.userId == request.userId }) {
            questionNotifier.answered(circleId, request.userId)
        }
    }

    /**
     * This user's own invitation records for a circle, read BEFORE the server's pending
     * invitations, which are read before the member list: an invitation made in between is
     * not judged at all. The two lists still needn't agree — both come from replicas that
     * can lag each other — so an invitation missing from [serverPending] only loses its
     * record ([settleOwnInvitations]). Statement ids.
     */
    internal class OwnInvitesCheck(val records: Map<String, String>, val serverPending: Set<String>)

    /** When each circle's invitations were last read for [ownInvitesCheck]; tests clear it. */
    internal val ownInvitesLooks = ConcurrentHashMap<String, Long>()

    /**
     * Null when this device has no pending invitation records there, looked less than a minute
     * ago, or isn't an owner or admin there by its last member list: only they may read the
     * pending list, and a demoted user is settled by the member list alone.
     */
    private suspend fun ownInvitesCheck(circleId: String): OwnInvitesCheck? {
        val records = keyAccessApprovals.pendingInvitees(circleId)
        if (records.isEmpty()) return null
        val selfUserId = sessionManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return null
        val now = System.currentTimeMillis()
        ownInvitesLooks[circleId]?.let { if (now - it < OWN_INVITES_LOOK_INTERVAL_MS) return null }
        ownInvitesLooks[circleId] = now
        if (!ownerOrAdminHere(circleId, selfUserId)) return null
        val response = runCatching { tracksApi.getCircleInvites(circleId) }.getOrNull() ?: return null
        val invites = response.body()?.takeIf { response.isSuccessful && it.success }?.data ?: return null
        val pending = invites.mapNotNull { dto ->
            dto.statement?.let { runCatching { membershipStatements.parse(it)?.statementId }.getOrNull() }
                ?.also { inviteStatementIds[dto.id] = it }
                ?.let(com.pingolin.track.util.AccountIds::storeKey)
        }.toSet()
        return OwnInvitesCheck(records, pending)
    }

    /**
     * This device's OWN invitation records only, and never an invitation that a member on
     * this list carries:
     *  - This COMPLETE member list shows this user as no longer an owner or admin: the server
     *    withdrew every invitation they sent, so each record goes and its id goes dead here —
     *    the role and what the members carry both from this one list.
     *  - Still an owner or admin, and the server's pending list ([check], read before this
     *    member list) no longer has one: withdrawn from another phone or by another admin,
     *    refused, or accepted just now. The record goes, so it makes nobody this user's own;
     *    the id stays live, since a member who just accepted it may only show on the next list.
     * A server that lies about either can at most keep someone out.
     */
    private fun settleOwnInvitations(
        circleId: String,
        check: OwnInvitesCheck?,
        result: MemberConfirmation.Result,
        ownerOrAdmin: Boolean,
    ) {
        val recorded = keyAccessApprovals.pendingInvitees(circleId).values.toSet()
        if (recorded.isEmpty()) return
        val carried = result.statementIds.values.flatten().map(com.pingolin.track.util.AccountIds::storeKey).toSet()
        if (!ownerOrAdmin) {
            val withdrawn = recorded.filter { it !in carried }
            for (id in withdrawn) {
                keyAccessApprovals.markInvitationDead(circleId, id)
                keyAccessApprovals.withdrawInvitation(circleId, id)
            }
            if (withdrawn.isNotEmpty()) {
                Log.i(TAG, "No longer an owner or admin of $circleId: ${withdrawn.size} invitation(s) of this user withdrawn here")
            }
            return
        }
        check ?: return
        val dropped = check.records.values.filter { it in recorded && it !in carried && it !in check.serverPending }
        for (id in dropped) keyAccessApprovals.withdrawInvitation(circleId, id)
        if (dropped.isNotEmpty()) Log.i(TAG, "${dropped.size} invitation(s) of this user to $circleId are no longer pending: records dropped")
    }

    /** In-flight deliveries, so the member list's own refreshes do not start a second. */
    private val deliveriesInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "circle:user:version" this process delivered, with when: never twice in [REDELIVERY_MS]. */
    private val deliveredAt = ConcurrentHashMap<String, Long>()

    /** Where deliveries run off the caller's path; tests run them on their own scheduler. */
    internal var deliveryScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    /** Deliver from the member list just judged, off the caller's path (on refresh). */
    private fun scheduleDelivery(circleId: String) {
        deliveryScope.launch {
            runCatching { backfillAdmissions(circleId) }
                .onFailure { Log.w(TAG, "backfill for $circleId failed: ${it.message}") }
            runCatching { deliverFromSnapshot(circleId) }
                .onFailure { Log.w(TAG, "delivery for $circleId failed: ${it.message}") }
        }
    }

    /**
     * What promoting [userId] to admin asks first when this device trusts them receive-only —
     * invited by email before they had an account: the promotion's admission lifts that
     * everywhere, so their number is shown. It is worked out from the keys this device
     * RECORDED for them ([promotionKeys]), and those are the keys the tap passes on to be
     * signed. Null for anyone else: nothing is asked.
     */
    data class PromotionQuestion(val keys: Pair<String, String>?, val number: String?)

    fun promotionQuestion(circleId: String, userId: String): PromotionQuestion? {
        val snapshot = trustSnapshots[circleId] ?: return null
        if (!snapshot.result.receiveOnly(userId)) return null
        // Their recorded keys unreadable: nothing is asked now, and the promotion waits for them.
        val keys = runCatching { promotionKeys(circleId, userId) }.getOrElse { return null }
        return PromotionQuestion(keys, keys?.let { (sharing, signing) -> computeSafetyNumber(userId, sharing, signing) })
    }

    /**
     * The keys this device holds for [userId], the only ones a promotion's admission may sign:
     * a receive-only member's RECORDED pair — they are never pinned — else the pinned pair.
     * Null when it holds none.
     */
    internal fun promotionKeys(circleId: String, userId: String): Pair<String, String>? {
        val snapshot = trustSnapshots[circleId] ?: return null
        if (snapshot.result.receiveOnly(userId)) return keyAccessApprovals.receiveOnlyKeys(circleId)[userId]
        val sharing = memberPins.pinnedSharingKey(circleId, userId) ?: return null
        val signing = memberPins.pinnedSigningKey(circleId, userId) ?: return null
        return sharing to signing
    }

    /**
     * Members of [circleId] on its last member list whom this device does not show: their
     * locations are not shown either. Empty before the list was judged.
     */
    fun membersHiddenIn(circleId: String): Set<String> {
        val snapshot = trustSnapshots[circleId] ?: return emptySet()
        return snapshot.roster.map { it.userId }.filterNot { it in snapshot.result.shown }.toSet()
    }

    /**
     * Whether [userId], in any spelling, is one of the members this device shows in [circleId]: on
     * the circle's last COMPLETE member list as this process judged it, and not hidden (this account
     * itself included). Null before this process judged that list: no decision (no cold-start race).
     */
    fun memberShownIn(circleId: String, userId: String): Boolean? {
        val snapshot = trustSnapshots[circleId]
            ?: com.pingolin.track.util.AccountIds.canonical(circleId)?.let { trustSnapshots[it] }
            ?: return null
        val shown = snapshot.result.shown
        return snapshot.roster.any { member ->
            (member.userId == userId || com.pingolin.track.util.AccountIds.same(member.userId, userId)) && member.userId in shown
        }
    }

    /** When [onSilentWake] last ran its membership pass. */
    @Volatile private var lastSilentWakeAtMs = 0L

    /**
     * A silent `alerts_check` wake-up: it carries no circle, and it is also what the server
     * sends after an access request, an admission, a newcomer still waiting, or a delivery to
     * this device. So: hand over what the circle list says is owed, and take any copy that
     * arrived. At most once a minute — wake-ups for alerts come in bursts too.
     */
    suspend fun onSilentWake() {
        val now = System.currentTimeMillis()
        if (now - lastSilentWakeAtMs < SILENT_WAKE_INTERVAL_MS) return
        lastSilentWakeAtMs = now
        // A wake-up is a new reason to look, whatever the last pass found.
        deliveryPasses.clear()
        // The refresh hands over whatever the circle list says is owed ([deliverIfDue]).
        val circles = refreshCircles().getOrNull() ?: return
        val waiting = circles.any { circle ->
            !runCatching { cryptoManager.hasCircleKey(circle.id) }.getOrDefault(true) || circle.id in currentKeyGate.behind.value ||
                circle.id in currentKeyGate.recordsBehind.value
        }
        if (waiting) {
            runCatching { importCircleKeys() }
            runCatching { refreshCircles() }
        }
        // A renewed invitation this person already agreed to join: the renewal wakes
        // this app so it can accept now.
        runCatching { retryPendingAccepts() }
        // Another phone of this account can't use the published personal copy: the
        // holder's check runs now instead of within the hour.
        runCatching { locationRepository.get().onSilentWake() }
        // A copy that arrived may let changes waiting to sync go out.
        pendingChanges.kick()
    }

    /**
     * Refresh [circleId]'s member list and hand the circle's current copy to every
     * confirmed member who is owed it and whom this device may serve — on the
     * member_joined wake-up, the silent wake, and after this user lets someone in.
     * Returns how many copies the server took.
     */
    suspend fun deliverOwedAccess(circleId: String): Int {
        if (personalSlot(circleId, "delivery")) return 0
        refreshMembersAndJudge(circleId, deliver = false).getOrNull() ?: return 0
        return deliverFromSnapshot(circleId)
    }

    /**
     * [deliverOwedAccess] from the last judged member list. Nothing while this user's own
     * app is receive-only, while this device is behind (it would hand over an old copy;
     * the server refuses it anyway), or while another delivery for the circle runs.
     */
    private suspend fun deliverFromSnapshot(circleId: String): Int {
        if (personalSlot(circleId, "delivery")) return 0
        val snapshot = trustSnapshots[circleId] ?: return 0
        val selfUserId = sessionManager.getUserId() ?: return 0
        if (receiveOnlyHere(circleId, snapshot.result) || !sessionManager.isLoggedIn()) return 0
        if (!currentKeyGate.holdsCurrentKey(circleId)) return 0
        val activeVersion = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrDefault(0)
        if (activeVersion <= 0) return 0
        val now = System.currentTimeMillis()
        val targets = try {
            val eDeliver = if (runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) {
                deliverableByRecords(circleId, snapshot) ?: return 0
            } else {
                null
            }
            AccessDelivery.plan(
                selfUserId = selfUserId,
                result = snapshot.result,
                roster = snapshot.roster,
                accessRequested = snapshot.accessRequested,
                activeVersion = activeVersion,
                nowSecs = nowSecs(),
                createdHere = snapshot.createdHere,
                pinnedSharingKey = { memberPins.pinnedSharingKey(circleId, it) },
                receiveOnlyKeys = keyAccessApprovals.receiveOnlyKeys(circleId),
                eDeliver = eDeliver,
            )
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            // Whom it owes rests on records that can't be read now: nothing is handed over.
            Log.w(TAG, "delivery: ${e.message}; nothing handed over for $circleId")
            return 0
        }.filter { target ->
            val at = deliveredAt["$circleId:${target.userId}:$activeVersion"]
            at == null || now - at >= REDELIVERY_MS
        }
        if (targets.isEmpty()) return 0
        if (!deliveriesInFlight.add(circleId)) return 0
        var delivered = 0
        try {
            for (target in targets) {
                val bundle = try {
                    cryptoManager.wrapCircleKeyForMemberFull(
                        circleId, target.sharingKeyHex, selfUserId, target.userId, target.signingKeyHex,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "delivery: could not wrap $circleId for ${target.userId}: ${e.message}")
                    continue
                }
                if (bundle.wrapSignature.isEmpty()) {
                    Log.w(TAG, "delivery: no signature for $circleId → ${target.userId}; not sending an unsigned copy")
                    continue
                }
                val response = tracksApi.deliverAccess(
                    circleId,
                    target.userId,
                    com.pingolin.track.data.remote.dto.DeliverAccessRequest(
                        keyVersion = activeVersion,
                        encryptedCircleKey = EncryptedPayload(
                            nonce = bundle.encryptedCircleKey.nonce,
                            ciphertext = bundle.encryptedCircleKey.ciphertext,
                        ),
                        ephemeralPublicKey = bundle.ephemeralPublicKey,
                        wrapSignature = bundle.wrapSignature,
                    ),
                )
                deliveredAt["$circleId:${target.userId}:$activeVersion"] = System.currentTimeMillis()
                if (response.isSuccessful && response.body()?.success == true) {
                    delivered++
                    Log.i(TAG, "delivery: handed $circleId v$activeVersion to ${target.userId}")
                    continue
                }
                val code = errorCodeOf(response)
                when {
                    // A server older than delivery by any member: no route at all.
                    (response.code() == 404 || response.code() == 405) && code == null -> {
                        deliveryUnsupported = true
                        Log.w(TAG, "delivery: this server cannot take deliveries; joins go back to rotations")
                        break
                    }
                    // Someone else was first: nothing to do.
                    code == "key_already_delivered" -> Log.d(TAG, "delivery: ${target.userId} in $circleId already served")
                    // The circle moved on under us: catch up first; the next pass delivers that.
                    code == "key_version_mismatch" -> {
                        Log.i(TAG, "delivery: $circleId moved past v$activeVersion — catching up")
                        requestKeyReimport()
                        break
                    }
                    else -> Log.w(TAG, "delivery: $circleId → ${target.userId} refused: HTTP ${response.code()} ${code.orEmpty()}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "delivery for $circleId failed: ${e.message}")
        } finally {
            deliveriesInFlight.remove(circleId)
        }
        if (delivered > 0) {
            // Their rows now show the current version: "Joining…" ends with the next list.
            runCatching { refreshMembersAndJudge(circleId, deliver = false) }
        }
        return delivered
    }

    /**
     * On a circle with refresh records, whom the records allow this device to hand the current
     * key (those they include, or those they name with other keys whose current ones this
     * device's person confirmed), from the judged list; null when it can't be told, and nothing
     * is handed over. Records not imported in this process are read first.
     */
    private suspend fun deliverableByRecords(circleId: String, snapshot: CircleTrustSnapshot): Set<String>? {
        if ((runCatching { cryptoManager.refreshHeadVersions()[circleId] }.getOrNull() ?: 0) == 0) syncRefreshRecords()
        val members = RefreshRecords.recordMembers(
            snapshot,
            personConfirmed = { userId, (sharing, signing) -> personConfirmedKeys.confirmed(circleId, userId, sharing, signing) },
        ) { userId -> confirmedKeys(circleId, userId, snapshot) }
        return runCatching { cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.DELIVER) }
            .onFailure { Log.w(TAG, "delivery: E not known for $circleId yet (${it.javaClass.simpleName}); nothing handed over") }
            .getOrNull()?.filter { it.eligible }?.map { it.userId }?.toSet()
    }

    /**
     * Ask the other members' apps for a fresh copy of [circleId]: this device refused the
     * copy it was given, or cannot open it (after a password reset, say). At most once per
     * [ACCESS_REQUEST_BACKOFF_MS] per circle; the server also ignores repeats within ten
     * minutes.
     */
    internal suspend fun requestFreshAccess(circleId: String) {
        val now = System.currentTimeMillis()
        val last = accessRequestedAt[circleId]
        if (last != null && now - last < ACCESS_REQUEST_BACKOFF_MS) return
        accessRequestedAt[circleId] = now
        try {
            val response = tracksApi.requestAccess(circleId)
            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Asked the members of $circleId for a fresh copy")
            } else {
                Log.w(TAG, "Access request for $circleId not taken: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            accessRequestedAt.remove(circleId)
            Log.w(TAG, "Access request for $circleId not sent: ${e.message}")
        }
    }

    // ===== Refresh records =====

    /** Each circle's key-chain links as the last key import served them: what a records import walks. */
    private val lastKeyChains = ConcurrentHashMap<String, List<KeyChainLinkResponse>>()

    /**
     * The link this device's own key change of [circleId] sent, added to [lastKeyChains] once the server took the
     * change. A records import before the next key sync then walks it, so this device's own new record never
     * waits on its own link (LinkMissing). The next key sync replaces the list with the server's.
     */
    private fun noteSentLink(circleId: String, link: uniffi.pingolin_mobile.KeyChainLinkData) {
        lastKeyChains.compute(circleId) { _, served ->
            served.orEmpty().filterNot { it.fromVersion == link.fromVersion && it.toVersion == link.toVersion } +
                KeyChainLinkResponse(link.fromVersion, link.toVersion, link.encryptedKey, link.nonce)
        }
    }

    /** Each circle's last records import, for the repair sweep and the late-joiner check. */
    internal val recordsJudged = ConcurrentHashMap<String, RefreshRecords.Judged>()

    /**
     * Each circle's own record the server took, by its version, until a records import was served that
     * version or the head reached it. No record is built while one is here: a key change that starts after
     * this device's own record was taken waits for an import that judged it. Two changes already building
     * at once aren't ordered by this; the SDK's builder rule, applied later, is.
     */
    private val ownRecordTaken = ConcurrentHashMap<String, Int>()

    /**
     * Circles whose records wait on a member list: none read yet in this process, or a version
     * waiting or refused for a reason a later list may settle. Judged again after their next
     * roster pass.
     */
    private val recordsAwaitList: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Circles a records read that succeeded in this process served complete with no records and no
     * heads: the reply covers every circle this account is in, so these have none. Only they are told
     * as having no records ([recordsState]). The SDK's "no records" is only what it has latched here,
     * and a fresh install, a new phone or cleared data has latched nothing yet.
     */
    private val recordsNoneServed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * One records read and import at a time: a key import and a roster pass can each start one. A
     * repair is built under it too ([recordedRotationFor]).
     */
    internal val recordsMutex = Mutex()

    /**
     * Listed members this account's own head vouched for on a fresh install's first import,
     * with the keys the vouch matched, per circle: anchors of the next roster pass.
     */
    private val headVouched = ConcurrentHashMap<String, Map<String, Pair<String, String>>>()

    /** Circles an import just found vouches for: their roster pass runs once the lock is let go. */
    private val vouchedNow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether the SDK's eligibility check (INCLUDE) counts this account's own row in each circle, as the
     * last records import or member list read it. Absent while unknown: no records imported in this
     * process, or a read that failed.
     */
    private val ownRowEligible = ConcurrentHashMap<String, Boolean>()

    /**
     * Circles where this account's own admission of its current keys is due: the SDK's eligibility check
     * doesn't count its own row (after a password reset the chain still commits its old keys), and the
     * member list shows it as an owner or admin. Its key changes wait there: the SDK's builder refuses
     * every plan until the server serves that admission in its own row.
     */
    private val selfAdmissionsDue: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The upload each circle's admission had for a key version: a 200 ends it for that version; a
     * 403 ([refusedAsRole], the role the list showed) until the listed role or the version changes.
     */
    private data class SelfAdmissionMark(val keyVersion: Int, val refusedAsRole: uniffi.pingolin_mobile.CircleRole?)
    private val selfAdmissionMarks = ConcurrentHashMap<String, SelfAdmissionMark>()

    /** When each circle's admission was last tried: one try per circle per [SELF_ADMISSION_RETRY_MS]. */
    private val selfAdmissionTriedAt = ConcurrentHashMap<String, Long>()

    /** The clock the retry rule reads; tests move it. */
    internal var selfAdmissionClock: () -> Long = { System.currentTimeMillis() }

    /** [KeyAccessApprovals.recordApprovalBasis] from a member list just judged: see its call in [applyMemberTrust]. */
    private fun recordApprovalBasis(circleId: String, result: MemberConfirmation.Result) {
        val self = sessionManager.getUserId() ?: return
        val origins = keyAccessApprovals.origins(circleId)
        fun origin(userId: String) = origins.entries.firstOrNull { com.pingolin.track.util.AccountIds.same(it.key, userId) }?.value
        val basis = result.holding.mapNotNull { (userId, held) ->
            val origin = origin(userId)
            if (origin != com.pingolin.track.util.KeyAccessApprovals.Origin.STATEMENT && origin != com.pingolin.track.util.KeyAccessApprovals.Origin.OWN) {
                return@mapNotNull null
            }
            val own = held.filter { s ->
                com.pingolin.track.util.AccountIds.same(s.signerUserId, self) &&
                    (s.kind == MembershipStatements.Kind.INVITATION_TO_ACCOUNT || s.kind == MembershipStatements.Kind.ADMISSION)
            }.map { it.statementId }
            own.takeIf { it.isNotEmpty() }?.let { userId to it }
        }.toMap()
        if (basis.isNotEmpty()) runCatching { keyAccessApprovals.recordApprovalBasis(circleId, basis) }
    }

    /** This account's role in [snapshot]'s member list (the server's word), as the SDK names it. */
    private fun listedRoleOf(snapshot: CircleTrustSnapshot, self: String): uniffi.pingolin_mobile.CircleRole? =
        RefreshRecords.role(snapshot.rows.entries.firstOrNull { com.pingolin.track.util.AccountIds.same(it.key, self) }?.value?.role)

    /**
     * The SDK's eligibility check (INCLUDE) for this account's own row in [circleId], read against [members]
     * (the rows [snapshot] gives the SDK), and whether its own admission is due there ([selfAdmissionsDue]).
     * A read that fails decides nothing: unknown, not due.
     */
    private fun noteOwnRow(circleId: String, snapshot: CircleTrustSnapshot, members: List<uniffi.pingolin_mobile.RecordMember>) {
        val self = sessionManager.getUserId() ?: return
        val eligible = runCatching {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
                .firstOrNull { com.pingolin.track.util.AccountIds.same(it.userId, self) }?.eligible
        }.getOrNull()
        if (eligible == null) {
            forgetOwnRow(circleId)
            return
        }
        ownRowEligible[circleId] = eligible
        val role = listedRoleOf(snapshot, self)
        if (!eligible && (role == uniffi.pingolin_mobile.CircleRole.OWNER || role == uniffi.pingolin_mobile.CircleRole.ADMIN)) {
            if (selfAdmissionsDue.add(circleId)) Log.i(TAG, "$circleId: this account's own admission of its current keys is due")
        } else {
            selfAdmissionsDue.remove(circleId)
        }
    }

    /** The SDK's eligibility check for this account's own row is not known in [circleId]:
     * nothing is due there, and nothing lifted.
     */
    private fun forgetOwnRow(circleId: String) {
        ownRowEligible.remove(circleId)
        selfAdmissionsDue.remove(circleId)
    }

    /**
     * [noteOwnRow] against the member list just judged, on a circle whose records an import judged in this
     * process, and not forked there (the SDK's eligibility check counts no row on a forked chain).
     */
    private fun noteOwnRow(circleId: String) {
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return
        val judged = recordsJudged[circleId]
        val snapshot = trustSnapshots[circleId]
        if (judged == null || judged.report.forked || snapshot == null) {
            forgetOwnRow(circleId)
            return
        }
        noteOwnRow(circleId, snapshot, RefreshRecords.recordMembers(snapshot) { userId -> confirmedKeys(circleId, userId, snapshot) })
    }

    /** Receive-only here: lifted where the SDK's eligibility check counts this account's own row. */
    private fun receiveOnlyHere(circleId: String, result: MemberConfirmation.Result): Boolean =
        result.selfReceiveOnly && ownRowEligible[circleId] != true

    /** Each circle whose own admission is due ([emitSelfAdmission]), outside the records lock. */
    private suspend fun emitSelfAdmissions() {
        for (circleId in selfAdmissionsDue.toList()) {
            try {
                emitSelfAdmission(circleId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Own admission for $circleId not sent: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * This account, an owner or admin whose own row the SDK's eligibility check doesn't count,
     * publishes its own admission of its CURRENT keys, signed with its current key (the SDK's
     * signMembershipAdmission), through the route every admission takes. Another device counts it
     * only where its person has confirmed those keys; it never confirms them. Into this account's
     * own row only as the server serves it: the member list is read again after the 200. A 200
     * ends it for the key version, a 403 until the listed role or the version changes, and
     * anything else is tried again, once per circle per [SELF_ADMISSION_RETRY_MS].
     */
    private suspend fun emitSelfAdmission(circleId: String) {
        if (circleId !in selfAdmissionsDue) return
        val self = sessionManager.getUserId() ?: return
        val snapshot = trustSnapshots[circleId] ?: return
        val circle = circleDao.getById(circleId) ?: return
        val role = listedRoleOf(snapshot, self) ?: return
        val version = circle.keyVersion
        selfAdmissionMarks[circleId]?.let { mark ->
            if (mark.keyVersion == version && (mark.refusedAsRole == null || mark.refusedAsRole == role)) return
        }
        // Its at_version is the version this device seals under, and the server takes only the circle's current
        // one: a device behind on the key catches up first.
        if (!currentKeyGate.holdsCurrentVersion(circleId)) {
            Log.i(TAG, "Own admission for $circleId waits: this device is behind on the key")
            return
        }
        val sharing = selfSharingKeyHex() ?: return
        val signing = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return
        // This try's slot, taken at once: two passes running together send one.
        val now = selfAdmissionClock()
        var mine = false
        selfAdmissionTriedAt.compute(circleId) { _, last ->
            if (last != null && now - last < SELF_ADMISSION_RETRY_MS) last else now.also { mine = true }
        }
        if (!mine) return
        val signed = try {
            membershipStatements.signAdmission(circleId, self, self, sharing, signing)
        } catch (e: Exception) {
            Log.w(TAG, "Own admission for $circleId not signed: ${e.message}")
            return
        }
        // Sent only for the account it was signed for.
        if (!sessionManager.isLoggedIn() || sessionManager.getUserId()?.let { com.pingolin.track.util.AccountIds.same(it, self) } != true) return
        val response = try {
            tracksApi.storeMembershipStatement(
                circleId,
                self,
                com.pingolin.track.data.remote.dto.StoreStatementRequest(signed.statementHex, signed.signatureHex),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Own admission for $circleId not sent: ${e.message}; tried again later")
            return
        }
        when {
            response.isSuccessful && response.body()?.success == true -> {
                selfAdmissionMarks[circleId] = SelfAdmissionMark(version, refusedAsRole = null)
                Log.i(TAG, "Own admission of this account's current keys stored for $circleId at v$version")
                // Into its own row only as the server serves it: the member list again, and the SDK's
                // eligibility check with it ([noteOwnRow]).
                refreshMembersAndJudge(circleId, deliver = false, checkRecords = false)
                if (circleId !in selfAdmissionsDue) rotationFailures.remove(circleId)
            }
            response.code() == 403 -> {
                selfAdmissionMarks[circleId] = SelfAdmissionMark(version, refusedAsRole = role)
                Log.w(TAG, "Own admission for $circleId refused (403): the server doesn't count this account as an owner or admin")
            }
            else -> Log.w(TAG, "Own admission for $circleId not stored: HTTP ${response.code()}; tried again later")
        }
    }

    /**
     * After every key import this device reads every circle's refresh records above the heads it
     * accepted and checks them itself, in the SDK. A circle whose new records need a member list
     * read after them (none yet in this process, or one a later list may settle) gets one now,
     * and is judged again against it. A read that fails decides nothing: every version stays as
     * it was. [listRead]: circles whose member list was read just now.
     */
    internal suspend fun syncRefreshRecords(listRead: Set<String> = emptySet()) {
        val self = sessionManager.getUserId() ?: return
        try {
            val needList = recordsMutex.withLock { judgeRefreshRecords(self, listRead) }
            passVouched()
            emitSelfAdmissions()
            if (needList.isNullOrEmpty()) return
            // Outside the lock: a roster pass can start a key import, and with it this.
            val read = coroutineScope {
                needList.map { circleId ->
                    async { circleId.takeIf { refreshMembersAndJudge(it, deliver = false, checkRecords = false).isSuccess } }
                }.awaitAll()
            }.filterNotNull().toSet()
            if (read.isNotEmpty()) recordsMutex.withLock { judgeRefreshRecords(self, read) }
            passVouched()
            emitSelfAdmissions()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refresh records not checked: ${e.javaClass.simpleName}")
        } finally {
            // What the records read here say now, which may end a wait or start one.
            runCatching { currentKeyGate.noteRecords(circleDao.getAll().map { it.id }) }
            // And what this account may do in each circle.
            _recordsNoted.update { it + 1 }
        }
    }

    /** The roster pass that confirms whom an import's vouches named, outside the lock. */
    private suspend fun passVouched() {
        val circles = vouchedNow.toList()
        vouchedNow.removeAll(circles.toSet())
        for (circleId in circles) refreshMembersAndJudge(circleId, deliver = false, checkRecords = false)
    }

    /**
     * One read of every circle's records, each circle judged against its last complete member
     * list ([fresh]: read after this read began). The circles whose records need a list read
     * after them, or null when the read failed.
     */
    private suspend fun judgeRefreshRecords(self: String, fresh: Set<String>): Set<String>? {
        val since = runCatching { cryptoManager.refreshHeadVersions() }.getOrNull() ?: return null
        val served = RefreshRecords.fetchAll(since) { asked ->
            runCatching { tracksApi.refreshRecords(RefreshRecordsRequest(asked)) }.getOrNull()
                ?.let { response -> response.body()?.takeIf { response.isSuccessful && it.success }?.data }
        }
        if (served == null) {
            Log.w(TAG, "Refresh records not read: nothing decided this time")
            return null
        }
        for ((circleId, circle) in served) {
            if (circle.complete && circle.records.isEmpty() && circle.heads.isEmpty()) recordsNoneServed += circleId else recordsNoneServed -= circleId
        }
        return served.filter { (circleId, circle) -> !importRefreshRecords(circleId, circle, self, circleId in fresh) }.keys
    }

    /**
     * One circle's records, judged against this device's last complete member list:
     * `rosterComplete` holds for every list a roster pass stored, since each reads every page.
     * False when they need a list read after them: none read yet in this process, or a version
     * waits or was refused for a reason a later list may settle ([fresh]: this list was read
     * after them, so a refusal now holds after a complete pass).
     */
    private suspend fun importRefreshRecords(circleId: String, served: CircleRefreshRecords, self: String, fresh: Boolean): Boolean {
        if (personalSlot(circleId, "refresh records")) return true
        val recorded = runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)
        if (served.records.isEmpty() && !recorded) {
            // A head proves the circle has records: never legacy again here, even
            // while the server keeps them back.
            if (served.heads.isNotEmpty()) runCatching { cryptoManager.noteCircleHasRecords(circleId) }
            return true
        }
        val snapshot = trustSnapshots[circleId]
        if (snapshot == null) {
            // Nothing new judged without a list; heads wait for one too.
            if (served.records.isEmpty()) return true
            recordsAwaitList += circleId
            return false
        }
        val members = RefreshRecords.recordMembers(snapshot) { userId -> confirmedKeys(circleId, userId, snapshot) }
        val report = try {
            withContext(Dispatchers.Default) {
                cryptoManager.importRefreshRecords(
                    circleId,
                    RefreshRecords.signed(served),
                    RefreshRecords.links(lastKeyChains[circleId].orEmpty()),
                    members,
                    true,
                    RefreshRecords.ownHead(served.heads, self),
                    RefreshRecords.memberHeads(served.heads, snapshot.result.confirmed.keys),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refresh records of $circleId not imported: ${e.javaClass.simpleName}")
            return true
        }
        recordsJudged[circleId] = RefreshRecords.Judged(report, againstFreshList = fresh)
        // Whether the SDK's eligibility check counts this account's own row now, and so whether its own admission
        // is due. On a forked chain it counts no row at all: not known.
        if (report.forked) forgetOwnRow(circleId) else noteOwnRow(circleId, snapshot, members)
        // This device's own record is judged once an import was served it, or the head reached it: an import
        // whose read began before the server took it leaves the wait in place.
        val head = recordsHead(circleId)
        ownRecordTaken.computeIfPresent(circleId) { _, own ->
            own.takeUnless { served.records.any { it.version >= own } || head >= own }
        }
        // A fresh install's first import, by this account's own head: the listed members
        // it vouches for anchor the next roster pass, with the keys the vouch matched.
        if (report.headConfirmedMembers.isNotEmpty()) {
            headVouched[circleId] = RefreshRecords.vouched(report.headConfirmedMembers, snapshot)
            vouchedNow += circleId
        }
        val listMaySettle = RefreshRecords.listMaySettle(report)
        if (listMaySettle) recordsAwaitList += circleId else recordsAwaitList -= circleId
        if (report.askForFreshCopy) requestFreshAccess(circleId)
        // A version waiting or refused here, or a fork, is behind: "Waiting to sync".
        currentKeyGate.holdsCurrentKey(circleId)
        publishOwnHead(circleId, self, served.heads, report.forked)
        return fresh || !listMaySettle
    }

    /**
     * The keys this device confirms for [userId] in [circleId]'s judged list: this account's own,
     * a receive-only member's recorded pair (never pinned), else the pin. Null when it holds none.
     */
    private fun confirmedKeys(circleId: String, userId: String, snapshot: CircleTrustSnapshot): Pair<String, String>? {
        if (userId.equals(sessionManager.getUserId(), ignoreCase = true)) {
            val sharing = selfSharingKeyHex() ?: return null
            val signing = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return null
            return sharing to signing
        }
        if (snapshot.result.receiveOnly(userId.lowercase())) {
            return keyAccessApprovals.receiveOnlyKeys(circleId)[com.pingolin.track.util.AccountIds.storeKey(userId)]
        }
        val sharing = memberPins.pinnedSharingKey(circleId, userId) ?: return null
        val signing = memberPins.pinnedSigningKey(circleId, userId) ?: return null
        return sharing to signing
    }

    /**
     * After each import, this account's head for the circle goes to the server when the server
     * serves none, one that doesn't verify under the account's CURRENT key (after a password
     * reset: the same head, signed again), or an older one ([RefreshRecords.headDue]). Only the
     * head this device accepted, nothing while the circle is forked, and at most once per
     * import. A served head that verifies and contradicts this device's chain was compared as a
     * member's is, and forked it.
     */
    private suspend fun publishOwnHead(circleId: String, self: String, heads: List<ServedCircleHead>, forked: Boolean) {
        if (forked) return
        val accepted = runCatching { cryptoManager.refreshHeadVersions()[circleId] }.getOrNull() ?: return
        val ownKey = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return
        val served = RefreshRecords.ownServedHead(heads, self)?.let { head ->
            runCatching { cryptoManager.verifyCircleHead(head.head, head.signature, ownKey) }.getOrNull()
        }
        if (!RefreshRecords.headDue(accepted, served, circleId, self)) return
        val signed = try {
            cryptoManager.signCircleHead(circleId, self)
        } catch (e: Exception) {
            // The head's record isn't known here in full (after an upgrade to record
            // format 2, until the server serves it again): not published now.
            if (RefreshRecords.notImportedYet(e)) {
                Log.i(TAG, "Head of $circleId not published yet: its record isn't known here in full")
            } else {
                Log.e(TAG, "Head of $circleId not signed: ${e.javaClass.simpleName}")
            }
            return
        }
        val reply = runCatching { tracksApi.putCircleHead(circleId, PutHeadRequest(signed.headHex, signed.signatureHex)) }.getOrNull()
        if (reply?.isSuccessful != true || reply.body()?.success != true) {
            Log.w(TAG, "Head of $circleId not published: HTTP ${reply?.code()}")
        }
    }

    /**
     * Admin-initiated member removal. Routes through the atomic `eject-and-rotate`
     * endpoint so the circle key is rotated in the same transaction as the
     * membership delete.
     *
     * No fallback to the plain DELETE: the server answers it with 410 Gone, and
     * the only message such a fallback could give — update the app — is wrong in
     * the case that actually happens: the app IS current and the EJECT failed,
     * usually because a survivor's key pin is unverified. The eject's own failure
     * is returned unchanged, so the person sees the real reason and can fix it,
     * and [revokeMemberAccess] is the deliberate second step.
     *
     * Except while this phone is behind on the circle: it can't re-key it then, and a removal
     * is never kept waiting. It goes through at once without a new key ([revokeMemberAccess]),
     * so the server stops serving them now, and the circle's refresh follows from any member
     * whose phone holds the current version. This phone still records the departure at its
     * next member list, as for any other. [Result.success] says whether the circle was
     * re-keyed.
     */
    suspend fun removeMember(circleId: String, userId: String): Result<Boolean> {
        val ejected = ejectMember(circleId, userId)
        val reason = ejected.exceptionOrNull()
        if (reason !is KeyNotCurrentException && reason !is RemovesWithoutNewKey) return ejected.map { true }
        Log.i(TAG, "Removing $userId from $circleId at once, without a new key (${reason.javaClass.simpleName})")
        return revokeMemberAccess(circleId, userId).map { false }
    }

    /**
     * An eject this device can't record: the removal goes through without a new key, and the
     * circle's next flagged rotation records the departure ([ejectMember]).
     */
    internal class RemovesWithoutNewKey : Exception("This removal goes through without a new key.")

    /**
     * A removal this device has yet to record stands, unless this account changed its mind
     * about them: a statement about that member (an invitation to their account, or an
     * admission) signed by this account under its current key after the removal clears it.
     * Another admin's statement doesn't, nor does an invitation to an address, which names no
     * account (one on their row is the server's word), nor one this account withdrew or that
     * was refused here (its id dead).
     */
    private fun clearRemovalsReconsidered(circleId: String, rows: List<com.pingolin.track.data.remote.dto.MemberResponse>) {
        // Each member this account reconsidered, with the statement that says so.
        val reconsidered = try {
            if (ownRemovals.of(circleId).isEmpty()) return
            val ownKey = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return
            val dead = keyAccessApprovals.deadInvitations(circleId)
            rows.mapNotNull { row ->
                val removedAt = ownRemovals.removedAt(circleId, row.userId) ?: return@mapNotNull null
                (listOfNotNull(row.invitation) + row.vouches.orEmpty()).firstNotNullOfOrNull { dto ->
                    val p = membershipStatements.parse(dto.statement) ?: return@firstNotNullOfOrNull null
                    p.takeIf {
                        p.circleId.equals(circleId, ignoreCase = true) && p.subjectUserId.equals(row.userId, ignoreCase = true) &&
                            p.statementId.lowercase() !in dead &&
                            p.issuedAt > removedAt && membershipStatements.verify(dto.statement, dto.signature, ownKey)
                    }
                }?.let { row.userId to it }
            }
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            // What's owed, or which invitations are dead, isn't known now: every removal stands.
            Log.w(TAG, "$circleId: ${e.message}; removals reconsidered later")
            return
        }
        if (reconsidered.isEmpty()) return
        Log.i(TAG, "$circleId: ${reconsidered.size} removal(s) this account reconsidered won't be recorded from here")
        for ((userId, statement) in reconsidered) {
            // An invitation is kept against: withdrawn from here, or run out unused, the removal comes back.
            if (statement.kind == MembershipStatements.Kind.INVITATION_TO_ACCOUNT) {
                ownRemovals.clearedByInvitation(circleId, userId, statement.statementId, statement.expiresAt)
            } else {
                ownRemovals.settled(circleId, listOf(userId))
            }
        }
    }

    /**
     * Remove somebody WITHOUT rotating the circle key — only after
     * [removeMember] has failed.
     *
     * Returns the server's warning so the caller can show it verbatim. Nothing
     * here rotates, so the local circle row keeps its key version and picks up
     * `keyRotationNeeded` from the next sync; the member row goes immediately,
     * because they are off the roster the moment this returns.
     */
    suspend fun revokeMemberAccess(circleId: String, userId: String): Result<String?> {
        return try {
            // Kept before it's made: a removal this device can't remember would be recorded as a
            // departure. While its removals can't be read, it waits.
            if (!ownRemovals.readable(circleId)) return Result.failure(Exception("Couldn't remove them just now."))
            val response = tracksApi.revokeMemberAccess(circleId, userId)
            if (response.isSuccessful && response.body()?.success == true) {
                // This device's next rotation names them removed. Not on disk yet: held for this
                // session, and written once the file takes it.
                if (!ownRemovals.add(circleId, userId, nowSecs())) Log.w(TAG, "revokeMemberAccess: the removal from $circleId is held for this session")
                circleMemberDao.get(circleId, userId)?.let { circleMemberDao.delete(it) }
                keyAccessApprovals.revoke(circleId, listOf(userId))
                val warning = response.body()?.data?.warning
                Log.w(
                    TAG,
                    "revokeMemberAccess: removed $userId from $circleId WITHOUT rotating - circle owes a rotation"
                )
                Result.success(warning)
            } else {
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.w(TAG, "revokeMemberAccess failed for $circleId/$userId: ${response.code()} ${response.body()?.error ?: errorBody}")
                Result.failure(Exception(ApiErrors.plainLine(response.code(), ApiErrors.parse(errorBody)?.errorCode, "Couldn't remove them just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "revokeMemberAccess exception for $circleId/$userId", e)
            Result.failure(e)
        }
    }

    // ===== Member Role Management =====

    /**
     * Whether a role this user changes in [circleId] takes effect where it counts: on a circle
     * without refresh records the first record takes the list's roles; on one with records,
     * only a record of the chain's owner changes a role. So after an ownership claim the
     * claimant's app doesn't offer role changes as if they would take effect. Records not read
     * here yet, or forked: not offered until they are.
     */
    fun roleChangesTakeEffect(circleId: String): Boolean {
        when (recordsState(circleId)) {
            CircleRights.Records.NONE -> return true
            CircleRights.Records.WAITING -> return false
            CircleRights.Records.READ -> Unit
        }
        val self = sessionManager.getUserId() ?: return true
        return runCatching { cryptoManager.chainRole(circleId, self) }.getOrNull() == uniffi.pingolin_mobile.CircleRole.OWNER
    }

    /**
     * [circleId]'s refresh records as this device has them now, for [rightsIn]. None only once a records
     * read that succeeded here served the circle with none ([recordsNoneServed]): until then nothing is
     * offered on the server's word alone.
     */
    fun recordsState(circleId: String): CircleRights.Records {
        val latched = runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrNull() ?: return CircleRights.Records.WAITING
        return when {
            !latched -> if (circleId in recordsNoneServed) CircleRights.Records.NONE else CircleRights.Records.WAITING
            recordsHead(circleId) == 0 -> CircleRights.Records.WAITING
            else -> CircleRights.Records.READ
        }
    }

    /** What this account may do in [circleId], its role in the server's list being [serverRole]. */
    fun rightsIn(circleId: String, serverRole: String?): CircleRights.Rights {
        val records = recordsState(circleId)
        val chainRole = if (records == CircleRights.Records.READ) {
            sessionManager.getUserId()?.let { self -> runCatching { cryptoManager.chainRole(circleId, self) }.getOrNull() }
        } else {
            null
        }
        return CircleRights.of(serverRole, records, chainRole)
    }

    private val _recordsNoted = MutableStateFlow(0L)

    /** Moves on after every read of the circles' records ([syncRefreshRecords]): what [rightsIn] says may have changed. */
    val recordsNoted: StateFlow<Long> = _recordsNoted.asStateFlow()

    /**
     * Update a member's role in a circle.
     * [role] is "admin" or "member". Only the owner can change roles, and the
     * owner's own role is not changeable here — use [transferOwnership].
     *
     * A new admin signs invitations others must be able to check, so a promotion first
     * stores an admission that lets every member's app confirm them — and lifts an email
     * invitee's receive-only. It signs ONLY the keys this device holds for them
     * ([promotionKeys]), while the member list shows exactly those, and when a number was shown
     * they must be the keys behind it ([decidedKeys]); otherwise nothing is signed and the role
     * stays. The admission carries this device's version, so the whole promotion waits while it
     * is behind; and no role changes before the admission is stored.
     */
    suspend fun updateMemberRole(
        circleId: String,
        userId: String,
        role: String,
        decidedKeys: Pair<String, String>? = null,
        keepIfBehind: Boolean = false,
        pendingRun: com.pingolin.track.domain.pending.PendingRun? = null,
    ): Result<Unit> {
        return try {
            // Kept before it's made: a change this device can't remember would never reach the
            // chain. While its role changes can't be read, nothing is signed or sent.
            val recorded = runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)
            if (recorded && !ownerRoleChanges.readable(circleId)) throw com.pingolin.track.util.StoreUnreadable("owner role changes")
            if (role == "admin") {
                val listed = cachedMemberSharingPubkey(circleId, userId)?.let { sharing ->
                    cachedMemberSigningPubkey(circleId, userId)?.let { signing -> sharing to signing }
                }
                val keys = MemberConfirmation.promotionAdmissionKeys(promotionKeys(circleId, userId), listed, decidedKeys)
                try {
                    currentKeyGate.requireCurrentKey(circleId)
                } catch (e: KeyNotCurrentException) {
                    // The person's promotion waits while this phone is behind, bound to exactly
                    // the keys behind what they saw: nothing is signed for any other.
                    if (!keepIfBehind || pendingRun != null || keys == null) throw e
                    return pendingChanges.queue(
                        circleId,
                        com.pingolin.track.domain.pending.PendingInputs.MakeAdmin(
                            pendingChanges.circleName(circleId), userId,
                            pendingChanges.memberName(circleId, userId) ?: "They", keys.first, keys.second,
                            tapVersion = heldVersion(circleId),
                        ),
                    )
                }
                keys ?: return Result.failure(Exception("Their details changed, so they weren't made an admin."))
                // The admission first, and the role only once it is stored. A waiting change
                // whose look found its admission stored has only the role left.
                if (pendingRun?.admissionHeld != true) {
                    val admitted = admitStatement(circleId, userId, keys, pendingRun?.onSigned, pendingRun?.beforeSend)
                    if (admitted.isFailure) {
                        return if (pendingRun != null) admitted else Result.failure(Exception(PROMOTION_NOT_DONE))
                    }
                }
            }
            val response = tracksApi.updateMemberRole(
                circleId,
                userId,
                UpdateMemberRoleRequest(role = role)
            )
            if (response.isSuccessful && response.body()?.success == true) {
                circleMemberDao.get(circleId, userId)?.let { member ->
                    circleMemberDao.insert(member.copy(role = role))
                }
                // The chain takes a role only from its owner's record. This device records the
                // change in its next rotation, at once where it can (rotateCircleKey).
                if (recorded) {
                    // Not on disk yet: held for this session, and written once the file takes it.
                    if (!ownerRoleChanges.put(circleId, userId, role)) Log.w(TAG, "role change in $circleId is held for this session")
                    deliveryScope.launch {
                        runCatching { rotateCircleKey(circleId, forDelivery = false) }
                            .onFailure { Log.w(TAG, "role change in $circleId not recorded yet: ${it.javaClass.simpleName}") }
                    }
                }
                Result.success(Unit)
            } else {
                Result.failure(ApiErrors.refused(response, "Couldn't change their role just now."))
            }
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            // A waiting change waits (PendingChangeRunner.classify); the live tap is told it didn't go.
            Result.failure(if (pendingRun != null) e else Exception(PROMOTION_NOT_DONE))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Pending Invites Management =====

    fun observePendingInvites(circleId: String): Flow<List<PendingInviteEntity>> =
        pendingInviteDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /** Fetch a circle's pending invites; the server lets its owner and admins see them. */
    suspend fun refreshPendingInvites(circleId: String): Result<List<PendingInviteEntity>> {
        return try {
            val response = tracksApi.getCircleInvites(circleId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val currentDayBucket = (System.currentTimeMillis() / 86400000L).toInt()
                learnStatementIds(response.body()!!.data!!)
                val invites = response.body()!!.data!!.map { dto ->
                    PendingInviteEntity(
                        id = dto.id,
                        circleId = circleId,
                        inviteType = dto.inviteType,
                        email = dto.targetEmail,
                        status = dto.status,
                        createdDayBucket = dto.createdDayBucket,
                        expiresDayBucket = dto.expiresDayBucket,
                        isExpired = dto.expiresDayBucket < currentDayBucket
                    )
                }
                pendingInviteDao.deleteByCircle(circleId)
                pendingInviteDao.insertAll(invites)
                Result.success(invites)
            } else {
                Result.failure(Exception(ApiErrors.plainLine(response, "Couldn't load the invitations just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Each listed invitation's statement id, for a withdrawal from here ([inviteStatementIds]). */
    private fun learnStatementIds(invites: List<com.pingolin.track.data.remote.dto.PendingInviteResponse>) {
        invites.forEach { dto ->
            dto.statement?.let { runCatching { membershipStatements.parse(it)?.statementId }.getOrNull() }
                ?.let { inviteStatementIds[dto.id] = it }
        }
    }

    /** Revoke a pending invite; only the circle owner may. */
    suspend fun revokeInvite(circleId: String, inviteId: String): Result<Unit> {
        return try {
            // The withdrawal marks its statement dead here, so it must know it. Not learnt in this
            // run yet (the dialog shows the rows kept on disk at once, after a restart too), the
            // owner's list is read first; unread, nothing is withdrawn. Without it, a withdrawal
            // would leave the invitation able to vouch for its invitee here until it ran out (the
            // invitation's answer names no id to learn it from).
            if (!inviteStatementIds.containsKey(inviteId)) {
                val listed = tracksApi.getCircleInvites(circleId)
                val rows = listed.body()?.takeIf { listed.isSuccessful && it.success }?.data
                    ?: return Result.failure(Exception(ApiErrors.plainLine(listed, "Couldn't withdraw the invitation just now.")))
                learnStatementIds(rows)
            }
            val response = tracksApi.revokeInvite(circleId, inviteId)
            if (response.isSuccessful && response.body()?.success == true) {
                pendingInviteDao.deleteById(inviteId)
                // Withdrawn for good: never renewed, and its invitee record goes, so nobody
                // becomes this user's own on it.
                inviteStatementIds.remove(inviteId)?.let {
                    keyAccessApprovals.markInvitationDead(circleId, it)
                    keyAccessApprovals.withdrawInvitation(circleId, it)
                    // A removal this re-invite cleared is noted again.
                    ownRemovals.invitationWithdrawn(circleId, it)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception(ApiErrors.plainLine(response, "Couldn't withdraw the invitation just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Owner/admin, on the wake a report sends or when the circle list says so: raise "Is this
     * really {email}?" for each ACCOUNT invitation THIS account made whose invitee's app found it
     * no longer names them. Never on the report alone: the directory is read here, and only
     * keys that really changed, for the same account, are asked about. Never for an address
     * invitation, a withdrawn one, or one already answered.
     */
    internal suspend fun askAboutRenewals(circleId: String) {
        val now = System.currentTimeMillis()
        renewalLooks[circleId]?.let { if (now - it < RENEWAL_LOOK_INTERVAL_MS) return }
        renewalLooks[circleId] = now
        val selfUserId = sessionManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return
        val response = runCatching { tracksApi.getCircleInvites(circleId) }.getOrNull() ?: return
        val invites = response.body()?.data?.takeIf { response.isSuccessful } ?: return
        val circleName = circleDao.getById(circleId)?.name?.takeUnless { LockedCircle.isPlaceholder(it) }
            ?: context.getString(com.pingolin.track.R.string.location_notice_this_circle)
        for (invite in invites) {
            val signed = invite.statement?.let { st -> invite.statementSignature?.let { MembershipStatements.Signed(st, it) } }
            val original = signed?.let { runCatching { membershipStatements.parse(it.statementHex) }.getOrNull() }
            original?.let { inviteStatementIds[invite.id] = it.statementId }
            if (!invite.renewalRequested || signed == null || original == null) continue
            if (original.kind != MembershipStatements.Kind.INVITATION_TO_ACCOUNT || original.circleId != circleId) continue
            // Only the original signer's account asks, about its own invitation, while it is an
            // owner or admin (an honest server's renewal checks the role too).
            if (!original.signerUserId.equals(selfUserId, ignoreCase = true)) continue
            if (!ownerOrAdminHere(circleId, selfUserId)) continue
            if (keyAccessApprovals.invitationDead(circleId, original.statementId)) continue
            if (keyAccessApprovals.renewalAsked(circleId, original.statementId)) continue
            if (!originalStillHolds(circleId, signed, original)) continue
            if (_inviteRenewalQuestions.value.any { it.statementId == original.statementId }) continue
            val subject = original.subjectUserId ?: continue
            val email = invite.targetEmail?.takeIf { it.isNotBlank() } ?: continue
            val found = lookUpInvitee(email) ?: continue
            // Another account behind the address now: never a renewal.
            if (!com.pingolin.track.util.AccountIds.same(found.userId, subject)) continue
            // The same keys as invited: nothing to renew, whatever was reported.
            if (found.sharing.equals(original.subjectSharingKeyHex, true) && found.signing.equals(original.subjectSigningKeyHex, true)) continue
            val question = InviteRenewalQuestion(
                circleId, circleName, invite.id, original.statementId, email, subject,
                found.sharing, found.signing, computeSafetyNumber(subject, found.sharing, found.signing), signed,
            )
            // Asked once per invitation: marked when RAISED, so it is never raised or notified
            // again, and kept until answered, so a restart brings it back as it was.
            keyAccessApprovals.markRenewalAsked(circleId, original.statementId)
            pendingRenewalQuestions.put(question)
            _inviteRenewalQuestions.update { it + question }
            questionNotifier.inviteeChanged(circleId, subject, email)
        }
    }

    /** Unanswered questions from before a restart, as they were raised; never notified again. */
    internal fun restoreRenewalQuestions() {
        val stored = pendingRenewalQuestions.all()
        if (stored.isEmpty()) return
        _inviteRenewalQuestions.update { list -> list + stored.filter { s -> list.none { it.statementId == s.statementId } } }
    }

    /** [circleId] has nothing to renew any more: its questions, if any, are moot. */
    private fun dropRenewalQuestions(circleId: String) {
        _inviteRenewalQuestions.value.filter { it.circleId == circleId }.forEach { question ->
            pendingRenewalQuestions.remove(question.statementId)
            _inviteRenewalQuestions.update { list -> list.filterNot { it.statementId == question.statementId } }
            questionNotifier.answered(question.circleId, question.userId)
        }
    }

    /**
     * This account is an owner or admin of [circleId]: this device created it, else the circle
     * list says so (on a device only createdHere proves owner, and nothing proves admin; an
     * admission has no role byte).
     */
    private suspend fun ownerOrAdminHere(circleId: String, selfUserId: String): Boolean {
        if (keyAccessApprovals.createdHere(circleId)) return true
        if (circleDao.getById(circleId)?.isOwner == true) return true
        val role = runCatching { circleMemberDao.get(circleId, selfUserId)?.role }.getOrNull()
        return role == "owner" || role == "admin"
    }

    /**
     * The original checked here before anyone is asked: signed by this account's own key —
     * its current one, or one restored from before a reset (the renewal is signed with the
     * current key) — unexpired, and still pending by this device's own record
     * ([invitationPending]).
     */
    private fun originalStillHolds(
        circleId: String,
        signed: MembershipStatements.Signed,
        original: MembershipStatements.Statement,
    ): Boolean {
        val ownKey = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return false
        if (!membershipStatements.verify(signed.statementHex, signed.signatureHex, ownKey) &&
            !membershipStatements.signedByOwnPreviousKey(signed.statementHex, signed.signatureHex)
        ) return false
        if (original.expiresAt <= nowSecs()) return false
        return invitationPending(circleId, original)
    }

    /**
     * This device's record of the invitation [original]: made here, for its subject, not
     * appeared, withdrawn, refused or expired, and its id not dead. What a renewal reads
     * instead of the tombstone — a pending invitee is never tombstoned.
     */
    private fun invitationPending(circleId: String, original: MembershipStatements.Statement): Boolean {
        // The records keep every id in its one spelling, and are read so.
        val subject = com.pingolin.track.util.AccountIds.storeKey(original.subjectUserId ?: return false)
        val id = com.pingolin.track.util.AccountIds.storeKey(original.statementId)
        return keyAccessApprovals.pendingInvitees(circleId).any { (who, statementId) ->
            who == subject && statementId == id
        } && !keyAccessApprovals.invitationDead(circleId, id)
    }

    private data class DirectoryKeys(val userId: String, val sharing: String, val signing: String)

    /** What the address check said of an invitee's address. */
    private sealed interface InviteeCheck {
        /** It answered: the account it names with both keys, or none (null, a real "no account"). */
        class Answered(val keys: DirectoryKeys?) : InviteeCheck

        /** No answer: no connection, or a status [noAnswer] counts as none. */
        data object NoAnswer : InviteeCheck

        /** Any other 4xx but 404: handled as a refusal, never kept. */
        data object Refused : InviteeCheck
    }

    /**
     * An address check's status that is no answer: a 5xx, the server's 503 `lookup_unavailable`
     * among them, a 408 (a time limit) or a 429 (the server or a proxy asking to try later).
     */
    private fun noAnswer(status: Int): Boolean = status >= 500 || status == 408 || status == 429

    /** [lookUpInvitee], telling a check that couldn't be done, and a refusal, from "none". */
    private suspend fun checkInvitee(email: String): InviteeCheck {
        val r = try {
            tracksApi.lookupPublicKey(email)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Invitee lookup got no answer: ${e.javaClass.simpleName}")
            return InviteeCheck.NoAnswer
        }
        if (r.code() == 404) return InviteeCheck.Answered(null)
        if (noAnswer(r.code())) return InviteeCheck.NoAnswer
        if (!r.isSuccessful) return InviteeCheck.Refused
        val data = r.body()?.data
        // The account named, canonical (AccountIds.strict, the invite lookup's rule): any other id is no
        // answer, never another account behind the address.
        val userId = data?.userId?.takeIf { it.isNotBlank() }?.let { com.pingolin.track.util.AccountIds.strict(it) ?: return InviteeCheck.Refused }
        val sharing = data?.sharingPublicKey?.takeIf { it.isNotBlank() }
        val signing = data?.signingPublicKey?.takeIf { it.length == 64 }
        return InviteeCheck.Answered(if (userId != null && sharing != null && signing != null) DirectoryKeys(userId, sharing, signing) else null)
    }

    /**
     * The invitee's account and keys from [check] for a renewal, or why not: no answer keeps the
     * person's "It's them" ([keepIfBehind]) or makes a waiting run wait; a refusal (a 4xx) is
     * never kept (a waiting run waits, and "It's them" says it couldn't check); an address that
     * names no account with both keys any more can't be renewed: the question goes, and a
     * waiting run ends.
     */
    private suspend fun renewalKeys(
        check: InviteeCheck,
        question: InviteRenewalQuestion,
        keepIfBehind: Boolean,
        pendingRun: com.pingolin.track.domain.pending.PendingRun?,
    ): Result<DirectoryKeys> = when (check) {
        InviteeCheck.NoAnswer -> when {
            pendingRun != null -> Result.failure(com.pingolin.track.domain.pending.CouldNotCheck())
            keepIfBehind -> Result.failure(keepRenewal(question, addressCheck = true).exceptionOrNull() ?: Exception("Couldn't check ${question.email} just now."))
            else -> Result.failure(Exception("Couldn't check ${question.email} just now."))
        }
        // A waiting run waits, and "It's them" says it couldn't check.
        InviteeCheck.Refused -> Result.failure(
            if (pendingRun != null) com.pingolin.track.domain.pending.CouldNotCheck() else Exception("Couldn't check ${question.email} just now.")
        )
        is InviteeCheck.Answered -> check.keys?.let { Result.success(it) } ?: run {
            answerRenewal(question)
            Result.failure(Exception("The invitation for ${question.email} can't be renewed any more."))
        }
    }

    /**
     * "It's them" to a renewal, kept as a change that goes out by itself: bound to the keys the
     * question showed, until the invitation itself runs out; nothing is signed now, and the
     * question is answered. [addressCheck]: kept because the address couldn't be checked, not
     * for catching up.
     */
    private suspend fun keepRenewal(question: InviteRenewalQuestion, addressCheck: Boolean): Result<Unit> {
        val lapsesAtMs = runCatching { membershipStatements.parse(question.original.statementHex)?.expiresAt }
            .getOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
        answerRenewal(question)
        return pendingChanges.queue(
            question.circleId,
            com.pingolin.track.domain.pending.PendingInputs.RenewInvitation(
                question.circleName, question.inviteId, question.statementId, question.userId, question.email,
                question.sharingKeyHex, question.signingKeyHex, addressCheck = addressCheck,
                tapVersion = heldVersion(question.circleId),
            ),
            expiresAt = lapsesAtMs,
        )
    }

    /** The account the directory names for [email] now, with both keys; null when there is none. */
    private suspend fun lookUpInvitee(email: String): DirectoryKeys? = try {
        val r = tracksApi.lookupPublicKey(email)
        val data = r.body()?.data?.takeIf { r.isSuccessful }
        // Canonical, as [checkInvitee]; any other id is no answer here: nothing is asked until one comes.
        val userId = data?.userId?.let { com.pingolin.track.util.AccountIds.strict(it) }
        val sharing = data?.sharingPublicKey?.takeIf { it.isNotBlank() }
        val signing = data?.signingPublicKey?.takeIf { it.length == 64 }
        if (userId != null && sharing != null && signing != null) DirectoryKeys(userId, sharing, signing) else null
    } catch (e: Exception) {
        Log.w(TAG, "Invitee lookup failed: ${e.message}")
        null
    }

    /**
     * "It's them": renew the invitation for exactly the keys the question showed — if the
     * directory moved since, the question is asked again with the new ones and nothing is
     * signed. The SDK copies everything else from the original and refuses unless this
     * device's records allow it. The circle's copy goes to those keys only. The invitee's
     * app then joins by itself.
     *
     * Never while this phone is behind: the renewal would carry its older version, which the
     * server refuses. It catches up first; if the current copy doesn't come, nothing is
     * signed, the person is told what is true, and the question stays for later.
     */
    suspend fun renewInvitation(
        question: InviteRenewalQuestion,
        keepIfBehind: Boolean = false,
        pendingRun: com.pingolin.track.domain.pending.PendingRun? = null,
    ): Result<Unit> {
        val selfUserId = sessionManager.getUserId()?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(Exception("Not signed in"))
        if (personalSlot(question.circleId, "renewal")) return Result.failure(Exception(INVITE_NOT_SENT))
        try {
            currentKeyGate.requireCurrentKey(question.circleId)
        } catch (e: KeyNotCurrentException) {
            if (!keepIfBehind || pendingRun != null) return Result.failure(e)
            // "It's them" waits while this phone is behind.
            return keepRenewal(question, addressCheck = false)
        }
        // The address check: with no answer a waiting run waits, and the person's "It's
        // them" is kept as a change that goes out once it can. Nothing is signed.
        val now = renewalKeys(checkInvitee(question.email), question, keepIfBehind, pendingRun)
            .getOrElse { return Result.failure(it) }
        if (!com.pingolin.track.util.AccountIds.same(now.userId, question.userId) ||
            !now.sharing.equals(question.sharingKeyHex, true) || !now.signing.equals(question.signingKeyHex, true)
        ) {
            // A waiting change's run: its own rule decides (asked again once, then dropped).
            if (pendingRun != null) {
                return Result.failure(
                    InviteeChangedException(
                        question.circleId, question.email, now.userId, now.sharing, now.signing,
                        computeSafetyNumber(now.userId, now.sharing, now.signing),
                    )
                )
            }
            return reaskOrDrop(question, now)
        }
        return try {
            // The circle NOW: an owner or admin if this device created the circle, else by
            // the circle list's role. The original is this account's own, since only its
            // signer's account asks.
            val ownerOrAdmin = ownerOrAdminHere(question.circleId, selfUserId)
            val original = runCatching { membershipStatements.parse(question.original.statementHex) }.getOrNull()
            // The invitee record, not the tombstone.
            val pending = original != null && invitationPending(question.circleId, original)
            val facts = MembershipStatements.RenewalFacts(
                originalSignerIsOwnerOrAdminNow = ownerOrAdmin && original?.signerUserId.equals(selfUserId, ignoreCase = true),
                renewerIsOwnerOrAdmin = ownerOrAdmin,
                subjectDepartedOrTombstoned = !pending,
                invitationWithdrawnOrDead = !pending,
            )
            val renewed = membershipStatements.renewInvitation(
                question.circleId, selfUserId, question.original, question.sharingKeyHex, question.signingKeyHex, facts,
            )
            // A waiting change's run: the removal check asked again now that the version is fixed
            // (PendingRun.beforeSend).
            pendingRun?.beforeSend?.invoke()
            // The key a renewed invitation carries is a delivery too.
            val wrap = if (currentKeyGate.holdsCurrentKey(question.circleId) && invitationKeyMayGo(question.circleId)) {
                runCatching {
                    cryptoManager.wrapCircleKeyForMemberFull(
                        question.circleId, question.sharingKeyHex, selfUserId, question.userId, question.signingKeyHex,
                    )
                }.getOrNull()
            } else {
                null
            }
            val response = tracksApi.renewInvitation(
                question.circleId,
                question.inviteId,
                com.pingolin.track.data.remote.dto.RenewInvitationRequest(
                    statement = renewed.statementHex,
                    statementSignature = renewed.signatureHex,
                    encryptedCircleKey = wrap?.let { EncryptedPayload(it.encryptedCircleKey.nonce, it.encryptedCircleKey.ciphertext) },
                    ephemeralPublicKey = wrap?.ephemeralPublicKey,
                    wrapSignature = wrap?.wrapSignature,
                ),
            )
            if (response.isSuccessful && response.body()?.data?.renewed == true) {
                answerRenewal(question)
                Result.success(Unit)
            } else {
                val refusal = if (response.isSuccessful) null else errorCodeOf(response)
                Log.w(TAG, "Renewal of ${question.inviteId} not taken: HTTP ${response.code()} $refusal")
                when {
                    // A waiting change's run decides by the refusal's status and code.
                    pendingRun != null && !(response.code() == 409 && refusal == "invite_lookup_changed") ->
                        Result.failure(RefusedException("The invitation for ${question.email} couldn't be renewed just now.", response.code(), refusal))
                    // Their keys moved again since the look-up: the same rule as a change seen
                    // at the answer — asked again once, then dropped.
                    response.code() == 409 && refusal == "invite_lookup_changed" -> {
                        val latest = renewalKeys(checkInvitee(question.email), question, keepIfBehind, pendingRun)
                            .getOrElse { return Result.failure(it) }
                        if (pendingRun != null) {
                            return Result.failure(
                                InviteeChangedException(
                                    question.circleId, question.email, latest.userId, latest.sharing, latest.signing,
                                    computeSafetyNumber(latest.userId, latest.sharing, latest.signing),
                                )
                            )
                        }
                        reaskOrDrop(question, latest)
                    }
                    // Renewed or withdrawn meanwhile, or nobody can renew it any more: nothing to ask.
                    response.code() == 409 || response.code() == 404 -> {
                        answerRenewal(question)
                        Result.failure(Exception("The invitation for ${question.email} can't be renewed any more."))
                    }
                    // Not reached, or refused for now: the question stays for another answer.
                    else -> Result.failure(Exception("The invitation for ${question.email} couldn't be renewed just now."))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Renewal of ${question.inviteId} refused: ${e.message}")
            // A waiting change's run tells a request that got no answer apart.
            if (pendingRun != null) return Result.failure(e)
            // Its records unreadable here: nothing was signed, and the question stays.
            if (e is com.pingolin.track.util.StoreUnreadable) return Result.failure(Exception("The invitation for ${question.email} couldn't be renewed just now."))
            Result.failure(Exception("The invitation for ${question.email} can't be renewed from this phone."))
        }
    }

    /** What a waiting renewal's re-check found of an invitation to renew. */
    sealed interface RenewalNow {
        data class Pending(val question: InviteRenewalQuestion) : RenewalNow
        /** Withdrawn, accepted, run out, or dead here: nothing to renew. */
        data object Ended : RenewalNow
        data object CouldNotRead : RenewalNow
    }

    /**
     * A waiting renewal's re-check: invitation [inviteId] as the server lists it now, with its
     * original statement fetched fresh (a waiting change never keeps it) and held to what a
     * renewal question checks ([askAboutRenewals]): pending, this account's own, its id not dead
     * here. The question carries the keys the person saw ([sharing], [signing]).
     */
    suspend fun renewalNow(
        circleId: String,
        inviteId: String,
        statementId: String,
        email: String,
        userId: String,
        sharing: String,
        signing: String,
    ): RenewalNow {
        val selfUserId = sessionManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return RenewalNow.CouldNotRead
        // Its records unreadable here, whether it's dead or still pending can't be told: it waits.
        val dead = try { keyAccessApprovals.invitationDead(circleId, statementId) } catch (e: com.pingolin.track.util.StoreUnreadable) { return RenewalNow.CouldNotRead }
        if (dead) return RenewalNow.Ended
        val response = runCatching { tracksApi.getCircleInvites(circleId) }.getOrNull() ?: return RenewalNow.CouldNotRead
        val invites = response.body()?.takeIf { response.isSuccessful && it.success }?.data ?: return RenewalNow.CouldNotRead
        // Only a whole list says it ended: the route stops at 1000.
        val invite = invites.firstOrNull { it.id == inviteId }
            ?: return if (invites.size < INVITE_LIST_CAP) RenewalNow.Ended else RenewalNow.CouldNotRead
        val signed = invite.statement?.let { st -> invite.statementSignature?.let { MembershipStatements.Signed(st, it) } }
            ?: return RenewalNow.Ended
        val original = runCatching { membershipStatements.parse(signed.statementHex) }.getOrNull() ?: return RenewalNow.Ended
        val holds = try { originalStillHolds(circleId, signed, original) } catch (e: com.pingolin.track.util.StoreUnreadable) { return RenewalNow.CouldNotRead }
        if (!original.statementId.equals(statementId, ignoreCase = true) || original.circleId != circleId ||
            !original.signerUserId.equals(selfUserId, ignoreCase = true) || !holds
        ) return RenewalNow.Ended
        val circleName = circleDao.getById(circleId)?.name?.takeUnless { LockedCircle.isPlaceholder(it) }
            ?: context.getString(com.pingolin.track.R.string.location_notice_this_circle)
        return RenewalNow.Pending(
            InviteRenewalQuestion(
                circleId, circleName, inviteId, original.statementId, email, userId, sharing, signing,
                computeSafetyNumber(userId, sharing, signing), signed,
            )
        )
    }

    /**
     * "No": final — the invitation is withdrawn and never renewed. A refusal is told in this
     * app's words, never the server's.
     */
    suspend fun declineRenewal(question: InviteRenewalQuestion): Result<Unit> {
        keyAccessApprovals.markInvitationDead(question.circleId, question.statementId)
        ownRemovals.invitationWithdrawn(question.circleId, question.statementId)
        answerRenewal(question)
        return revokeInvite(question.circleId, question.inviteId).recoverCatching {
            throw Exception("Couldn't withdraw the invitation just now.")
        }
    }

    /**
     * The details changed between the question and "It's them": asked again about what is
     * there now — once, and kept so across restarts. Changed again after that, or another
     * account behind the address: nothing is renewed, and it is not asked about again. The
     * same line as on iPhone.
     */
    private fun reaskOrDrop(question: InviteRenewalQuestion, now: DirectoryKeys): Result<Unit> {
        if (question.reasked || !com.pingolin.track.util.AccountIds.same(now.userId, question.userId)) {
            answerRenewal(question)
            return Result.failure(Exception("Their details keep changing, so the invitation wasn't renewed."))
        }
        val again = question.copy(
            sharingKeyHex = now.sharing, signingKeyHex = now.signing,
            number = computeSafetyNumber(question.userId, now.sharing, now.signing),
            reasked = true,
        )
        pendingRenewalQuestions.put(again)
        _inviteRenewalQuestions.update { list -> list.filterNot { it.statementId == question.statementId } + again }
        return Result.failure(Exception("${question.email}'s details changed again, so you're asked about the new ones."))
    }

    private fun answerRenewal(question: InviteRenewalQuestion) {
        keyAccessApprovals.markRenewalAsked(question.circleId, question.statementId)
        pendingRenewalQuestions.remove(question.statementId)
        _inviteRenewalQuestions.update { list -> list.filterNot { it.statementId == question.statementId } }
        questionNotifier.answered(question.circleId, question.userId)
    }

    // ===== Received Invites =====

    /** The invitations this account was last shown, by id: what [acceptInvite] checks. */
    private val receivedInvites = ConcurrentHashMap<String, ReceivedInviteResponse>()

    /** Fetch the circle invites the current user has received. */
    suspend fun fetchReceivedInvites(): Result<List<ReceivedInviteResponse>> {
        return try {
            val response = tracksApi.getReceivedInvites()
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val invites = response.body()!!.data!!
                invites.forEach { receivedInvites[it.id] = it }
                Log.d(TAG, "Fetched ${invites.size} received invites from API")
                Result.success(invites)
            } else {
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.w(TAG, "Failed to fetch received invites: ${response.body()?.error ?: errorBody} (code=${response.code()})")
                Result.failure(Exception(ApiErrors.plainLine(response.code(), ApiErrors.parse(errorBody)?.errorCode, "Couldn't load your invitations just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching received invites", e)
            Result.failure(e)
        }
    }

    /**
     * E2EE: Encrypt and upload the user's display name for all circles that have a key ([sealOwnName]).
     * Called after creating/joining a circle to ensure the name is available encrypted: sent again
     * even where this process sealed it already, as a new membership's row starts with none.
     */
    suspend fun uploadEncryptedDisplayNames() {
        try {
            for (circle in circleDao.getAll()) sealOwnName(circle.id, again = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload encrypted display names: ${e.message}")
        }
    }

    // ===== This account's own sealed name in each circle =====

    /**
     * "circle|version|name" this process has sealed: one write each, so a server that doesn't reflect
     * it back isn't asked again and again (as iOS's ownNamesSealed). A failed write is forgotten, and a
     * later roster load tries again.
     */
    private val ownNamesSealed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Where own-name seals run off the caller's path; tests run them on their own scheduler. */
    internal var ownNameScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    /** [circleIds], whose key here just moved on, get this account's name sealed under it, off the caller's path. */
    private fun scheduleOwnNameSeal(circleIds: Collection<String>) {
        if (circleIds.isEmpty()) return
        ownNameScope.launch {
            for (circleId in circleIds) {
                runCatching { sealOwnName(circleId) }
                    .onFailure { Log.w(TAG, "Own name for $circleId: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Seal this account's name ([OwnName.of]: the display name, else the address) under [circleId]'s
     * CURRENT key, with the field tag every reader opens it with ("display_name"), and send it
     * (PUT /circles/{id}/display-name), as the other clients do. Every app names a member only by
     * the sealed name it opens itself, never the server's plaintext, so without it this account is
     * "A member" on every other phone. Nothing for the personal key's slot, while this device holds
     * no key or isn't current there ([CurrentKeyGate.holdsCurrentKey]), or with no name. Once per
     * circle, version and name in this process ([ownNamesSealed]), unless [again].
     */
    private suspend fun sealOwnName(circleId: String, again: Boolean = false) {
        if (com.pingolin.track.util.AccountIds.isPersonalSlot(circleId)) return
        val name = OwnName.of(sessionManager.getDisplayName(), sessionManager.getEmail()) ?: return
        if (!runCatching { cryptoManager.hasCircleKey(circleId) }.getOrDefault(false)) return
        if (!currentKeyGate.holdsCurrentKey(circleId)) return
        val version = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrNull() ?: return
        val marker = "${com.pingolin.track.util.AccountIds.canonical(circleId) ?: circleId}|$version|$name"
        if (!ownNamesSealed.add(marker) && !again) return
        try {
            val sealed = cryptoManager.encryptDataV2(circleId, name, "display_name")
            val response = tracksApi.setDisplayName(
                circleId,
                mapOf("encrypted_display_name" to mapOf("nonce" to sealed.nonce, "ciphertext" to sealed.ciphertext)),
            )
            if (!response.isSuccessful) {
                ownNamesSealed.remove(marker)
                Log.w(TAG, "Sealing this account's name for $circleId: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            ownNamesSealed.remove(marker)
            Log.w(TAG, "Sealing this account's name for $circleId failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The roster-load backfill: seal this account's name when its own row ([ownRow]) carries none
     * that opens here, under the current version, as that name ([OwnName.owed]). Sealing only at
     * create and join would leave a member who joined before their key came, whose circle rotated
     * since, or who renamed themselves unnamed, or under a stale name, on every other phone.
     * Nothing is sent while it is current.
     */
    private suspend fun sealOwnNameIfOwed(circleId: String, ownRow: com.pingolin.track.data.remote.dto.MemberResponse) {
        val name = OwnName.of(sessionManager.getDisplayName(), sessionManager.getEmail())
        val opened = ownRow.encryptedDisplayName
            ?.takeIf { it.nonce.isNotBlank() && it.ciphertext.isNotBlank() }
            ?.let { blob ->
                runCatching {
                    cryptoManager.decryptDataV2Opened(circleId, EncryptedData(nonce = blob.nonce, ciphertext = blob.ciphertext), "display_name")
                }.getOrNull()
            }
            ?.let { OwnName.Opened(it.text, runCatching { cryptoManager.isCurrentKeyVersion(circleId, it.keyVersion) }.getOrDefault(false)) }
        val current = runCatching { cryptoManager.hasCircleKey(circleId) }.getOrDefault(false) &&
            currentKeyGate.holdsCurrentKey(circleId)
        if (!OwnName.owed(name, opened, holdsCurrentKey = current)) return
        sealOwnName(circleId)
    }

    /**
     * "This invitation is being renewed… You'll join {circle} then, with nothing more to do."
     * — said after the person's yes: what is happening, never "ask them".
     */
    private fun renewing(inviter: String?, circleName: String?): InvitationBeingRenewedException = InvitationBeingRenewedException(
        (if (inviter != null) context.getString(com.pingolin.track.R.string.invite_renewing, inviter)
        else context.getString(com.pingolin.track.R.string.invite_renewing_unknown)) + " " +
            context.getString(
                com.pingolin.track.R.string.invite_renewing_joins_then,
                circleName?.takeIf { it.isNotBlank() } ?: context.getString(com.pingolin.track.R.string.location_notice_this_circle),
            )
    )

    /** Invitations this phone will join by itself once they are renewed. */
    fun waitingToJoin(): Set<String> = pendingInviteAccepts.all().map { it.inviteId }.toSet()

    /**
     * Keep the person's "Join and share my location" for [inviteId] until a renewed
     * invitation names this account ([retryPendingAccepts]). With [report], tell the server
     * it does not match (`POST /invites/{id}/mismatch`) — once per distinct statement.
     * False when the server says nobody can renew it (409 `invite_not_renewable`: its sender
     * can no longer invite people to this circle); nothing is kept then.
     */
    private suspend fun holdForRenewal(inviteId: String, circleId: String?, noticeVersion: String, statement: String?, report: Boolean): Boolean {
        if (circleId.isNullOrEmpty()) return true
        val sha = statement?.let(::sha256Hex)
        val before = pendingInviteAccepts.all().firstOrNull { it.inviteId == inviteId }
        pendingInviteAccepts.put(
            com.pingolin.track.util.PendingInviteAccepts.Pending(inviteId, circleId, noticeVersion, triedStatement = sha)
        )
        if (!report || (before != null && before.triedStatement == sha)) return true
        try {
            val response = tracksApi.reportInviteMismatch(inviteId)
            val refusal = if (response.isSuccessful) null else errorCodeOf(response)
            if (response.code() == 409 && refusal == "invite_not_renewable") {
                pendingInviteAccepts.remove(inviteId)
                return false
            }
            if (!response.isSuccessful) Log.w(TAG, "Mismatch report for $inviteId not taken: HTTP ${response.code()} $refusal")
        } catch (e: Exception) {
            Log.w(TAG, "Mismatch report for $inviteId not sent: ${e.message}")
        }
        return true
    }

    /**
     * "This invitation can't be used any more: the person who sent it can no longer invite
     * people to this circle.": nobody can renew it. A statement, not an ask.
     */
    private fun notRenewable() = Exception(context.getString(com.pingolin.track.R.string.invite_not_renewable))

    /**
     * Accept, by itself, each invitation the person agreed to join that was waiting to be
     * renewed, as soon as a renewed one names this account: on the wake a renewal sends,
     * and whenever the received invitations are read. Once per distinct statement; one that
     * went away is forgotten. Returns how many it joined.
     */
    suspend fun retryPendingAccepts(invites: List<ReceivedInviteResponse>? = null): Int {
        val pending = pendingInviteAccepts.all()
        if (pending.isEmpty()) return 0
        val received = invites ?: fetchReceivedInvites().getOrNull() ?: return 0
        var joined = 0
        for (p in pending) {
            val invite = received.firstOrNull { it.id == p.inviteId }
            if (invite == null) {
                // Withdrawn, expired, or accepted from another device: nothing left to do.
                Log.i(TAG, "Invitation ${p.inviteId} is no longer pending; not accepting it")
                pendingInviteAccepts.remove(p.inviteId)
                continue
            }
            val sha = invite.statement?.let(::sha256Hex)
            if (sha == null || sha == p.triedStatement) continue // not renewed yet
            val result = acceptInvite(p.inviteId, p.circleId, p.noticeVersion)
            val still = pendingInviteAccepts.all().firstOrNull { it.inviteId == p.inviteId }
            when {
                result.isSuccess -> joined++
                // Forgotten by the accept: it can't be used any more.
                still == null -> Unit
                // No connection: the next look tries this statement again.
                result.exceptionOrNull() is java.io.IOException -> Unit
                // Refused with this statement: not again until it changes.
                still.triedStatement != sha -> pendingInviteAccepts.put(still.copy(triedStatement = sha))
            }
        }
        return joined
    }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Accept [inviteId], agreeing to the location notice [noticeVersion] in the same request
     * (joining is agreeing). The server takes both or neither.
     */
    suspend fun acceptInvite(inviteId: String, circleId: String? = null, noticeVersion: String): Result<Unit> {
        if (circleId != null && personalSlot(circleId, "invitation")) return Result.failure(Exception("Couldn't join the circle just now."))
        return try {
            val invite = receivedInvites[inviteId]
                ?: fetchReceivedInvites().getOrNull()?.firstOrNull { it.id == inviteId }
            val inviter = invite?.inviterDisplayName?.takeIf { it.isNotBlank() }
            // The invitation must name THIS account with its keys, or its email: accepting
            // one made out to other keys would join a circle whose members hand the circle
            // to those keys.
            val statement = invite?.statement
            if (invite != null && statement != null) {
                val matches = MemberConfirmation.invitationNamesMe(
                    statement = runCatching { membershipStatements.parse(statement) }.getOrNull(),
                    circleId = invite.circleId,
                    selfUserId = sessionManager.getUserId().orEmpty(),
                    selfSharingKeyHex = selfSharingKeyHex(),
                    selfSigningKeyHex = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull(),
                    selfEmail = sessionManager.getEmail(),
                )
                if (!matches) {
                    val kind = runCatching { membershipStatements.parse(statement)?.kind }.getOrNull()
                    if (kind == MembershipStatements.Kind.INVITATION_TO_EMAIL) {
                        // An address invitation is never renewed: its account accepts it as it is,
                        // so a different address is final. No report.
                        Log.w(TAG, "acceptInvite: invitation $inviteId was sent to a different address")
                        return Result.failure(Exception(context.getString(com.pingolin.track.R.string.invite_different_address)))
                    }
                    // Made out to keys this account had before a password reset, most likely:
                    // the circle is asked to confirm it's them and renew it, and this app
                    // accepts by itself once it names this account.
                    Log.w(TAG, "acceptInvite: invitation $inviteId does not name this account as it is now; asking for a renewal")
                    if (!holdForRenewal(inviteId, invite.circleId, noticeVersion, statement, report = true)) {
                        return Result.failure(notRenewable())
                    }
                    return Result.failure(renewing(inviter, invite.circleName))
                }
            }
            val response = tracksApi.acceptInvite(
                inviteId,
                com.pingolin.track.data.remote.dto.AcceptInviteRequest(
                    com.pingolin.track.data.remote.dto.GrantLocationConsentRequest(clientNoticeVersion = noticeVersion)
                ),
            )
            // Read once: the error body is a one-shot stream.
            val refusal = if (response.code() == 409) errorCodeOf(response) else null
            if (refusal == "invite_not_renewable") {
                // Made out to keys this account no longer has, by someone who can no longer
                // invite people to this circle: nobody can renew it.
                pendingInviteAccepts.remove(inviteId)
                return Result.failure(notRenewable())
            }
            if (refusal == "invite_out_of_date") {
                // It names keys this account no longer has. The server has asked the circle
                // to renew it; this app accepts by itself when it has.
                holdForRenewal(inviteId, invite?.circleId ?: circleId, noticeVersion, statement, report = false)
                return Result.failure(renewing(inviter, invite?.circleName))
            }
            if (response.isSuccessful && response.body()?.success == true) {
                receivedInvites.remove(inviteId)
                pendingInviteAccepts.remove(inviteId)
                // Imports keys and loads the newly joined circle. Forced: joining is
                // exactly the moment session keys and the pseudonym backfill are due.
                refreshCircles(force = true)
                // E2EE: Upload encrypted display name for the new circle
                uploadEncryptedDisplayNames()
                // Any member's app hands us the circle once it is online; the arrival time
                // is unbounded, so POLL with backoff until the key shows up: any fixed
                // delay silently drops the key on the floor whenever the delivery is
                // slower than the guess.
                if (circleId != null) {
                    val deadline = System.currentTimeMillis() + 30_000L
                    var waitMs = 500L
                    while (System.currentTimeMillis() < deadline && !cryptoManager.hasCircleKey(circleId)) {
                        kotlinx.coroutines.delay(waitMs)
                        importCircleKeys()
                        waitMs = (waitMs * 2).coerceAtMost(4_000L)
                    }
                    if (!cryptoManager.hasCircleKey(circleId)) {
                        Log.w(TAG, "acceptInvite: key for $circleId did not arrive within 30s; circle will show as 'Waiting for key'")
                        uxEventBus.emit(com.pingolin.track.util.UxEvent.CircleKeyPending(circleId))
                    } else {
                        Log.i(TAG, "acceptInvite: key for $circleId arrived, circle ready")
                    }
                } else {
                    // Legacy path — invite didn't carry a circleId; best effort.
                    kotlinx.coroutines.delay(2000)
                    importCircleKeys()
                }
                refreshCircles(force = true)
                // Judge the member list now: the members this device confirms are pinned
                // (and, with nobody else to start from, whoever handed us the circle).
                if (circleId != null) runCatching { refreshMembers(circleId) }
                // A fresh position goes to the joined circle now; the earlier personal
                // history does not (see createCircle).
                notifyTrackingServiceCirclesChanged()

                Result.success(Unit)
            } else {
                Result.failure(Exception(ApiErrors.plainLine(response, "Couldn't join the circle just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun declineInvite(inviteId: String): Result<Unit> {
        return try {
            val response = tracksApi.declineInvite(inviteId)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(ApiErrors.plainLine(response, "Couldn't decline the invitation just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Key Rotation =====
    // [autoRotateIfNeeded] is deliberately the ONLY entry point: there is no manual
    // "rotate now" action, because a rotation is never a user intention — it is a
    // mechanical consequence of a membership change or of the 14-day periodic worker.

    /**
     * Rotate this circle's keys if the server flagged it and the current user is
     * eligible. Called after member removal and during circle refresh.
     *
     * On success, existing session keys are invalidated and a fresh one created.
     */
    suspend fun autoRotateIfNeeded(circleId: String) {
        // A join needs no new key: any member's app hands the newcomer the current one,
        // and the server lifts the flag once nobody is owed.
        val cause = _keyRotationCauses.value[circleId]
        if (cause == RotationPlanner.CAUSE_MEMBER_JOINED && !deliveryUnsupported) {
            val circle = circleDao.getById(circleId) ?: return
            // Unless the key in use here is marked and this device may re-key: the newcomer
            // then gets the new key, not one this device doesn't hand on. Or the circle has
            // no records yet and this device writes its first.
            if (!reKeysHere(circle) && !transitionDue(circle) && !roleChangesDue(circle)) {
                if (circle.keyRotationNeeded) deliverIfDue(circleId)
                return
            }
        }
        rotateCircleKey(circleId, forDelivery = false)
    }

    /**
     * The key in use here for [circleId] is one this device may not go on with: it came only
     * through an identity from before a password reset, which may be why the password was
     * reset, or it was taken on trust at first contact (and is handed to nobody from here).
     */
    private fun activeKeyMarked(circleId: String): Boolean = runCatching {
        cryptoManager.activeKeyCameViaPreviousIdentity(circleId) || cryptoManager.activeKeyTakenOnTrust(circleId)
    }.getOrDefault(false)

    /**
     * Whether the refresh sweep should try [autoRotateIfNeeded] for [circle]: the server
     * flagged it, or its key in use is marked ([activeKeyMarked]) and this user may re-key it
     * unflagged (can_rotate_keys: an owner or admin) — then before anything more is sealed
     * under it or delivered, where this device can. A plain member leaves it to the next
     * refresh.
     */
    internal fun rotationDue(circle: CircleEntity): Boolean =
        circle.keyRotationNeeded || reKeysHere(circle) || transitionDue(circle) || repairDue(circle) != null ||
            roleChangesDue(circle)

    /**
     * A role this user changed as the chain's owner that the chain doesn't have yet
     * ([RecordedRotation.rolesDue]): for a member it counts, or the promotion of one it never named
     * once a record built now would include them ([includableNow]; as the iOS app does). The owner
     * may rotate at any time, and its next rotation records the change. Nothing is due on a device
     * whose account isn't the chain's owner: those changes stay the server's (ownership claims),
     * and its next rotation forgets them.
     */
    private fun roleChangesDue(circle: CircleEntity): Boolean {
        // Unreadable: nothing is due until they read (a flagged rotation waits for them too).
        val changes = runCatching { ownerRoleChanges.of(circle.id) }.getOrElse { return false }
        if (changes.isEmpty()) return false
        val self = sessionManager.getUserId() ?: return false
        fun chainRole(userId: String) = runCatching { cryptoManager.chainRole(circle.id, userId) }.getOrNull()
        return RecordedRotation.rolesDue(chainRole(self), changes, ::chainRole) { includableNow(circle.id, it) }
    }

    /**
     * Whether a record built now would include [userId] in [circleId], as [RecordedRotation.rotation]
     * includes a member the rotation plan wraps to and the records allow: on the member list judged
     * here, confirmed with the very keys it lists (a changed key isn't wrapped until accepted), and
     * allowed by the records (a promotion's own admission is enough).
     */
    private fun includableNow(circleId: String, userId: String): Boolean {
        val snapshot = trustSnapshots[circleId] ?: return false
        val row = snapshot.rows.values.firstOrNull { it.userId.equals(userId, ignoreCase = true) } ?: return false
        val keys = confirmedKeys(circleId, row.userId, snapshot) ?: return false
        if (!keys.first.equals(row.sharingPublicKey, ignoreCase = true) || !keys.second.equals(row.signingPublicKey, ignoreCase = true)) return false
        val members = RefreshRecords.recordMembers(snapshot) { id -> confirmedKeys(circleId, id, snapshot) }
        if (members.none { it.userId.equals(userId, ignoreCase = true) && it.confirmed }) return false
        return runCatching {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
        }.getOrNull()?.any { it.userId.equals(userId, ignoreCase = true) && it.eligible } == true
    }

    /**
     * The record this device rejected and repairs now ([RefreshRecords.repairNow], within
     * [repairBudget]). Owners and admins repair at any time, any member on a flagged circle.
     * Nobody is asked.
     */
    private fun repairDue(circle: CircleEntity): uniffi.pingolin_mobile.RecordVersionStatus? {
        if (!(canRotateKeysCache[circle.id] ?: circle.isOwner) && !circle.keyRotationNeeded) return null
        // Its budget unreadable, no repair: what it spent isn't known.
        return try {
            RefreshRecords.repairNow(
                recordsJudged[circle.id],
                repairBudget.repaired(circle.id),
                repairBudget.lastRepairAt(circle.id),
                repairBudget.lastRepairedFor(circle.id),
                System.currentTimeMillis(),
                uncounted = { uncountedHere(circle.id, it) },
            )
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            null
        }
    }

    /** Whether the chain doesn't count [userId] in [circleId] here (no role in it). */
    private fun uncountedHere(circleId: String, userId: String): Boolean =
        runCatching { cryptoManager.chainRole(circleId, userId) }.getOrNull() == null

    /**
     * An owner's or admin's device (can_rotate_keys) writes a record-less circle's first record
     * at once, not at its next flagged rotation: the transition. Not again for a circle whose
     * transition the builder refused here (a listed owner with no keys, say), until
     * [TRANSITION_RETRY_MS] has passed; the circle then stays legacy meanwhile.
     */
    private fun transitionDue(circle: CircleEntity): Boolean {
        if (!(canRotateKeysCache[circle.id] ?: circle.isOwner)) return false
        if (runCatching { cryptoManager.circleHasRefreshRecords(circle.id) }.getOrDefault(true)) return false
        val refused = transitionsRefused[circle.id] ?: return true
        return System.currentTimeMillis() - refused >= TRANSITION_RETRY_MS
    }

    /**
     * [autoRotateIfNeeded] for a circle a sweep found due ([rotationDue]). A record-less circle's
     * first record, due on its own, is written off the sweep's path: after an update every such
     * circle is due at once, and each rotation first waits up to 5 s.
     */
    private suspend fun rotateDue(circle: CircleEntity, where: String) {
        if (!circle.keyRotationNeeded && !reKeysHere(circle)) {
            deliveryScope.launch {
                runCatching { autoRotateIfNeeded(circle.id) }
                    .onFailure { Log.w(TAG, "$where: first record of ${circle.id} not written: ${it.javaClass.simpleName}") }
            }
            return
        }
        try {
            autoRotateIfNeeded(circle.id)
        } catch (e: Exception) {
            Log.w(TAG, "$where: Auto-rotation error for ${circle.id}", e)
        }
    }

    /** When the builder last refused each circle's transition here. */
    private val transitionsRefused = ConcurrentHashMap<String, Long>()

    /** How long a circle whose transition was refused here waits before this device tries again. */
    private val TRANSITION_RETRY_MS = 60 * 60 * 1000L

    /** [circle]'s key in use is marked, and this user may re-key it (can_rotate_keys). */
    private fun reKeysHere(circle: CircleEntity): Boolean =
        (canRotateKeysCache[circle.id] ?: circle.isOwner) && activeKeyMarked(circle.id)

    /** When each circle's last delivery pass ran, and how many copies it handed over. */
    private val deliveryPasses = ConcurrentHashMap<String, Pair<Long, Int>>()

    /**
     * A delivery pass ([deliverOwedAccess]) for [circleId] — unless one found nothing this
     * device may hand over in the last [DELIVERY_PASS_INTERVAL_MS]. A newcomer nobody here
     * may let in (an owner or admin has not answered yet) keeps the circle owed for days,
     * and each look costs a full member list on every refresh of every member's phone. The
     * wake-ups and this user's own answers call [deliverOwedAccess] directly instead.
     */
    internal suspend fun deliverIfDue(circleId: String): Int {
        val now = System.currentTimeMillis()
        val last = deliveryPasses[circleId]
        if (last != null && last.second == 0 && now - last.first < DELIVERY_PASS_INTERVAL_MS) return 0
        val delivered = deliverOwedAccess(circleId)
        deliveryPasses[circleId] = now to delivered
        return delivered
    }

    /**
     * Bring members this user has just cleared — "It's them", "Let them in" — up to date
     * with the current key, without waiting for the server to flag the circle. By
     * delivery; by a rotation only on a server without it.
     */
    suspend fun deliverKeyToApprovedMembers(circleId: String) {
        if (deliveryUnsupported) rotateCircleKey(circleId, forDelivery = true) else deliverOwedAccess(circleId)
    }

    /** The server has no delivery route (an older server): joins fall back to rotations. */
    @Volatile private var deliveryUnsupported = false

    private suspend fun rotateCircleKey(circleId: String, forDelivery: Boolean) {
        if (personalSlot(circleId, "rotation")) return
        val lastFailure = rotationFailures[circleId] ?: 0L
        if (System.currentTimeMillis() - lastFailure < ROTATION_BACKOFF_MS) {
            Log.d(TAG, "autoRotateIfNeeded: Skipping circle $circleId (backoff after recent failure)")
            return
        }

        // Claimed BEFORE the jitter delay, or two callers both sleep, both wake
        // to a still-set flag and both rewrap. Released in the finally so an
        // early return cannot wedge the circle until process death.
        if (!forDelivery) {
            val skipped = skippedRotations[circleId]
            if (skipped != null &&
                System.currentTimeMillis() - skipped.first < SKIPPED_ROTATION_RETRY_MS &&
                skipped.second == _keyRotationCauses.value[circleId]
            ) {
                Log.d(TAG, "autoRotateIfNeeded: $circleId — still waiting on an approval; not re-planning yet")
                return
            }
        }

        if (!claimRotation(circleId)) {
            Log.d(TAG, "autoRotateIfNeeded: $circleId already rotating on this device — skipping")
            return
        }

        val change = KeyChange()
        try {
            val circle = circleDao.getById(circleId) ?: return

            // This account's own admission of its current keys is due here, and the SDK's builder refuses every
            // plan until the server serves it. A wait, not a failure: no new key, no back-off.
            if (circleId in selfAdmissionsDue) {
                Log.i(TAG, "autoRotateIfNeeded: $circleId — waiting for this account's own admission of its current keys to be served")
                return
            }

            // Unflagged, this device re-keys only a circle whose key in use it may not go on
            // with, or writes a record-less circle's first record, and only with the right
            // to (can_rotate_keys, below).
            val reKey = !circle.keyRotationNeeded && !forDelivery && activeKeyMarked(circleId)
            val transition = !circle.keyRotationNeeded && !forDelivery && !reKey && transitionDue(circle)
            // A record this device rejected, repaired by the next version.
            val repair = if (forDelivery) null else repairDue(circle)
            // A role change this user made as the chain's owner, recorded next.
            val roleChange = !forDelivery && roleChangesDue(circle)
            if (!circle.keyRotationNeeded && !forDelivery && !reKey && !transition && repair == null && !roleChange) return

            // Behind on the key: the new version would come from an older key than the
            // circle's, and rotate-keys does not require it to be one past the circle's.
            // A member who holds the current key re-keys. The current version is enough:
            // a re-key this device owes holds its seals back, not this rotation.
            if (repair == null && !currentKeyGate.holdsCurrentVersion(circleId)) {
                Log.d(TAG, "autoRotateIfNeeded: $circleId — this device does not hold the current key; leaving it to one that does")
                return
            }
            // A repair builds on the rejected version, whose key this device holds to read with. One
            // after a misattribution may link from below at once: nothing honest is sealed under that
            // version's key, and the SDK's builder picks the link within its floors.
            if (repair != null && repair.problem != uniffi.pingolin_mobile.RecordProblem.MISATTRIBUTED &&
                (runCatching { cryptoManager.newestHeldVersion(circleId) }.getOrNull() ?: 0) < repair.version
            ) {
                Log.d(TAG, "autoRotateIfNeeded: $circleId — the rejected version's key isn't here; leaving the repair to a device that holds it")
                return
            }

            // The server's per-member `can_rotate_keys` flag says who may rotate an
            // UNflagged circle. The cache is filled from the member-list response in
            // [refreshMembers]; with no entry yet (refreshMembers hasn't run since login)
            // fall back to owner, since owners always have the bit set server-side. While
            // the server has flagged the circle, any member may finish the refresh — a
            // plain member only when nobody would be left pending (checked against the
            // plan below).
            val canRotate = canRotateKeysCache[circleId] ?: circle.isOwner
            if (!canRotate && (forDelivery || reKey || transition || roleChange)) {
                Log.d(TAG, "autoRotateIfNeeded: $circleId — current user lacks can_rotate_keys; deferring to eligible peer")
                return
            }

            // A rewrap has to be submitted, so there is no point starting one
            // without a session. isLoggedIn(), not a null-check on the user id:
            // a forced sign-out KEEPS the stored id (see
            // SessionManager.clearCredentialsOnly), so an id-only check would
            // stop nothing.
            if (!sessionManager.isLoggedIn()) return

            // Randomized 0–5s delay so when N clients refresh simultaneously after a
            // member change, they don't all submit a full rewrap in lockstep and
            // waste N-1 ECDH operations losing the CAS. One client usually lands first.
            val jitterMs = (java.util.Random().nextInt(5_000)).toLong()
            kotlinx.coroutines.delay(jitterMs)

            // Re-check after the delay: someone else may have rotated in the meantime,
            // in which case their success will have cleared the flag on the server,
            // and the next refresh will clear it locally too.
            val recheck = circleDao.getById(circleId) ?: return
            if (!recheck.keyRotationNeeded && !forDelivery && !(reKey && activeKeyMarked(circleId)) && !(transition && transitionDue(recheck)) &&
                repair == null && !(roleChange && roleChangesDue(recheck))
            ) {
                Log.d(TAG, "autoRotateIfNeeded: Flag cleared during jitter for $circleId — another client rotated first")
                return
            }

            Log.d(TAG, "autoRotateIfNeeded: Starting key rotation for circle $circleId (jitter=${jitterMs}ms)")

            val pubKeysResponse = tracksApi.getMemberPublicKeys(circleId)
            if (!pubKeysResponse.isSuccessful || pubKeysResponse.body()?.data == null) {
                Log.w(TAG, "autoRotateIfNeeded: Failed to fetch member public keys for $circleId")
                return
            }
            val memberPublicKeys = pubKeysResponse.body()!!.data!!

            // Behind on its key, this device rotates nothing but a repair. The name never holds a
            // rotation back: the new key carries [sealableName]'s, one opened under the key in use
            // while that is the version this change replaces, else none, and rotate-keys keeps the
            // circle's. A name nobody can open, which any member may plant, so stops nothing. A
            // repair after a misattribution carries none: the key in use there is the misattributing
            // member's, who could have sealed a name under it.
            val misattributed = repair?.problem == uniffi.pingolin_mobile.RecordProblem.MISATTRIBUTED
            if (repair == null && behindHere(circle)) {
                Log.w(TAG, "autoRotateIfNeeded: Cannot rotate - behind on $circleId's key")
                return
            }
            val sealable = if (misattributed) null else sealableName(circle)
            val circleName = sealable.orEmpty()

            val senderUserId = sessionManager.getUserId().orEmpty()
            val plan = planRotationFor(circle, memberPublicKeys, forDelivery) ?: return
            if (trustSnapshots[circleId]?.result?.let { receiveOnlyHere(circleId, it) } == true) {
                // This user's own app refreshes nothing until an owner or admin admits its
                // current keys: other members' apps do it.
                Log.i(TAG, "autoRotateIfNeeded: $circleId — this app is receive-only here; leaving the refresh to others")
                return
            }
            if (!canRotate && !plan.leavesNobodyPending) {
                Log.i(
                    TAG,
                    "autoRotateIfNeeded: $circleId — ${plan.pending.size} member(s) this device cannot confirm; " +
                        "leaving the refresh to an owner or admin"
                )
                return
            }
            if (plan.skip && !transition && !roleChange) {
                Log.i(
                    TAG,
                    "autoRotateIfNeeded: $circleId — nothing to deliver (${plan.unconfirmed.size} unconfirmed, " +
                        "${plan.pending.size} pending); not rotating"
                )
                if (!forDelivery) {
                    skippedRotations[circleId] = System.currentTimeMillis() to _keyRotationCauses.value[circleId]
                }
                return
            }
            skippedRotations.remove(circleId)

            // A circle with refresh records rotates with its record, and an owner's or
            // admin's device writes a record-less circle's first one (the transition).
            val send = recordedRotationFor(circle, plan, canRotate, senderUserId, circleName, repairing = repair, change = change)
                ?: if (transition) {
                    // Its transition refused here: nothing else is due.
                    return
                } else {
                    legacyRotation(circleId, senderUserId, plan, circleName, change)
                }
            val sent = sendRotation(circle, send.copy(sealsName = sealable != null), plan.pending.size)
            // One repair per rejected version, one an hour.
            if (sent is RotationSent.Landed && repair != null) {
                repairBudget.spend(circleId, repair.version, RefreshRecords.rejectedFor(repair) { uncountedHere(circleId, it) }, System.currentTimeMillis())
            }
        } catch (e: RepairChanged) {
            // Nothing was built: the next pass plans the repair due then.
            Log.i(TAG, "autoRotateIfNeeded: $circleId — the repair due changed while this one was planned; planned again at the next pass")
        } catch (e: RecordsNotImportedYet) {
            // Nothing was built, and nothing failed: the next pass tries again, after the next import.
            notImportedYet(circleId, "autoRotateIfNeeded")
        } catch (e: Exception) {
            Log.w(TAG, "autoRotateIfNeeded: Error for circle $circleId", e)
            rotationFailures[circleId] = System.currentTimeMillis()
            discardMinted(circleId, change)
        } finally {
            // Every path out of the body above — the eligibility returns, the
            // flag-cleared-during-jitter return, the TOFU-gate abort, success,
            // and the catch — has to give the claim back, or one skipped
            // rotation wedges this circle for the life of the process.
            releaseRotation(circleId)
        }
    }

    /** What became of a key change sent to rotate-keys ([sendRotation]). */
    private sealed interface RotationSent {
        /** Taken, as [version]: this device's new key is the circle's. */
        data class Landed(val version: Int) : RotationSent

        /** A peer's rotation came first (this one's key goes), or no version came back: the circle is read again. */
        object Superseded : RotationSent

        /** Refused: this device's new key (and record) went. */
        object Refused : RotationSent

        /** A success without its data: nothing is known, and nothing here changes. */
        object NoAnswer : RotationSent
    }

    /**
     * Send [send], a key change of [circle], to rotate-keys, and take in what the server did with
     * it ([RotationSent]). [leftPending]: how many members it leaves pending, for the log.
     */
    private suspend fun sendRotation(circle: CircleEntity, send: RotationToSend, leftPending: Int): RotationSent {
        val circleId = circle.id
        val rotationBundle = send.bundle

        // Build the request from the rotation bundle. `bundle.wrapSignature`
        // is the per-member Ed25519 signature (empty string if no signing
        // key loaded — server treats empty string as null/legacy).
        val memberKeys = rotationBundle.memberKeys.map { entry ->
            MemberKeyUpdate(
                userId = entry.userId,
                encryptedCircleKey = EncryptedPayload(
                    nonce = entry.bundle.encryptedCircleKey.nonce,
                    ciphertext = entry.bundle.encryptedCircleKey.ciphertext
                ),
                ephemeralPublicKey = entry.bundle.ephemeralPublicKey,
                wrapSignature = entry.bundle.wrapSignature.takeIf { it.isNotEmpty() }
            )
        }

        val keyChainLink = send.link?.let { link ->
            mapOf(
                "from_version" to link.fromVersion,
                "to_version" to link.toVersion,
                "encrypted_key" to link.encryptedKey,
                "nonce" to link.nonce
            )
        }

        val request = RotateKeysRequest(
            encryptedName = EncryptedPayload(
                nonce = rotationBundle.encryptedName.nonce,
                ciphertext = rotationBundle.encryptedName.ciphertext
            ).takeIf { send.sealsName },
            memberKeys = memberKeys,
            // With a record, the version it moves to (new = expected + 1).
            expectedKeyVersion = if (send.record != null) rotationBundle.newKeyVersion - 1 else circle.keyVersion,
            keyChainLink = keyChainLink,
            pendingMembers = send.pending.takeIf { it.isNotEmpty() },
            refreshRecord = send.record?.let { RefreshRecordPayload(it.recordHex, it.signatureHex) },
        )

        var response = tracksApi.rotateCircleKeys(circleId, request)
        // rotate-keys takes one rotation per circle every 30 s, so the delivery a quick
        // "Give access" starts, right after the rotation that asked, is refused. Wait
        // what the server asks, once, and send the same rotation again.
        rotationRetryDelayMs(response)?.let { waitMs ->
            Log.i(TAG, "autoRotateIfNeeded: $circleId rate-limited — sending the rotation again in ${waitMs / 1000}s")
            kotlinx.coroutines.delay(waitMs)
            response = tracksApi.rotateCircleKeys(circleId, request)
        }

        if (response.isSuccessful && response.body()?.success == true) {
            val rotateResponse = response.body()?.data ?: return RotationSent.NoAnswer

            // A LOST race comes back 200, not 409: the server notices the key is
            // already at a later version, skips the write and sets `already_rotated`.
            // Adopting the peer's rotation from the server is the only correct move
            // here — the version it reports is the CURRENT one, not ours, and our
            // local key is stale either way.
            if (rotateResponse.alreadyRotated == true) {
                Log.i(TAG, "autoRotateIfNeeded: $circleId already rotated by a peer — re-fetching")
                rotationFailures.remove(circleId)
                // Not taken: this device's own new key (and record) goes.
                discardOwnRotation(circleId)
                adoptPeerRotation(circleId)
                return RotationSent.Superseded
            }

            // Null means the server did not name a version. Writing what we guessed
            // would poison the NEXT rotation's expected_key_version, so leave the
            // local row alone and let the next refresh reconcile it.
            val newVersion = rotateResponse.newKeyVersion
            if (newVersion == null) {
                Log.w(TAG, "autoRotateIfNeeded: $circleId rotated but no new_key_version in the response — re-fetching")
                rotationFailures.remove(circleId)
                adoptPeerRotation(circleId)
                return RotationSent.Superseded
            }

            // The server clears the flag in the same statement that bumps the
            // version (`key_rotation_needed = FALSE`), so this mirrors it rather
            // than reading a field the response does not carry.
            circleDao.insert(circle.copy(
                keyVersion = newVersion,
                keyRotationNeeded = false
            ))
            send.link?.let { noteSentLink(circleId, it) }

            Log.i(
                TAG,
                "autoRotateIfNeeded: Rotation successful for $circleId, new version: $newVersion" +
                    if (leftPending > 0) " ($leftPending member(s) left pending)" else ""
            )
            rotationFailures.remove(circleId)
            currentKeyGate.noteCurrent(circleId)
            // Nothing more is built for this circle until an import has judged this record.
            if (send.record != null) ownRecordTaken[circleId] = send.bundle.newKeyVersion
            // Writes that waited on this device's re-key go now.
            pendingChanges.kick()
            // This account's own open SOS there goes along to the new key, which this
            // device just made: no key read first.
            runCatching { sosRepository.get().resealOwn(setOf(circleId), keysRead = true) }
                .onFailure { Log.w(TAG, "Reseal after a rotation failed: ${it.javaClass.simpleName}") }
            // And this account's frozen last location there, where its real-time sharing is off.
            scheduleLastKnownReseal(listOf(circleId))
            // And its name, which a newcomer can't open under the older key.
            scheduleOwnNameSeal(listOf(circleId))
            // Its record named this user's removals without a key change.
            if (send.settles.isNotEmpty()) ownRemovals.settled(circleId, send.settles)
            // And the role changes it recorded.
            if (send.rolesSettle.isNotEmpty()) ownerRoleChanges.settled(circleId, send.rolesSettle)

            try {
                cryptoManager.invalidateSessionKeysForCircle(circleId)
                ensureSessionKey(circleId)
            } catch (e: Exception) {
                Log.w(TAG, "autoRotateIfNeeded: Failed to refresh session key after rotation for $circleId", e)
            }
            return RotationSent.Landed(newVersion)
        } else {
            // No 409 branch: rotate-keys reports a lost race as 200 +
            // `already_rotated` (handled above). KEY_VERSION_MISMATCH/409 comes only
            // from eject-and-rotate, whose `new_key_version` must be exactly one
            // greater than the current one — this endpoint has no such check, so a
            // 409 branch here could never run.
            val error = response.body()?.error ?: response.message() ?: "Unknown error"
            Log.w(TAG, "autoRotateIfNeeded: Rotation failed for $circleId: ${response.code()} $error")
            rotationFailures[circleId] = System.currentTimeMillis()
            discardOwnRotation(circleId)
            // Records this device hasn't read, or read too late (409 key_refresh_record_stale,
            // 400 key_refresh_record_required): read them now, and the next refresh builds again.
            val refused = ApiErrors.codeOf(response)
            if (refused == "key_refresh_record_stale" || refused == "key_refresh_record_required") syncRefreshRecords()
            return RotationSent.Refused
        }
    }

    /**
     * How long a rotate-keys 429 asks us to wait — "Key rotation rate limit: try again in
     * N s" — plus a second, or null for any other answer.
     */
    private fun rotationRetryDelayMs(response: retrofit2.Response<*>): Long? {
        if (response.code() != 429) return null
        val error = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
        val seconds = Regex("""try again in (\d+)\s*s""").find(error)?.groupValues?.get(1)?.toLongOrNull()
            ?: response.headers()["Retry-After"]?.toLongOrNull()
            ?: 30L
        return (seconds.coerceIn(1L, 60L) + 1) * 1000
    }

    /**
     * Drop the key version [prepareKeyRotation][CryptoManager.prepareKeyRotation] made
     * active here when the server did not take the rotation (a non-2xx, or a throw on
     * the way). Without it this device keeps encrypting under a key nobody else holds
     * and labels its next attempt one version too high. Safe when the outcome is
     * unknown: had the server taken it, the next key import brings the same, signed
     * key back. A no-op when this device minted nothing.
     */
    private fun discardOwnRotation(circleId: String): Boolean =
        runCatching { cryptoManager.discardUnconfirmedRotation(circleId) }
            .onSuccess { discarded -> if (discarded) Log.i(TAG, "Discarded this device's unconfirmed rotation of $circleId") }
            .onFailure { Log.w(TAG, "discardUnconfirmedRotation failed for $circleId: ${it.message}") }
            .getOrDefault(false)

    /** One key change's own new key, once minted: what its failure may discard, and nothing else. */
    private class KeyChange {
        @Volatile var minted: Int? = null
    }

    /**
     * [discardOwnRotation] for a [change] that failed, only when it minted a key. One refused before minting
     * ("v3 of this circle waits here") must not drop an EARLIER unconfirmed rotation: that is the circle's key
     * in use, and without it this device seals nothing for the circle until its next key sync.
     */
    private fun discardMinted(circleId: String, change: KeyChange) {
        val minted = change.minted
        if (minted == null) {
            Log.i(TAG, "Nothing was minted for $circleId here; an earlier key change of it is kept")
            return
        }
        val discarded = discardOwnRotation(circleId)
        Log.i(TAG, "This failed key change of $circleId minted v$minted: discarded=$discarded")
    }

    /** What a rotation sends: the SDK's bundle, the link down, its record (none without records), and pending_members. */
    private data class RotationToSend(
        val bundle: uniffi.pingolin_mobile.KeyRotationBundle,
        val link: uniffi.pingolin_mobile.KeyChainLinkData?,
        val record: uniffi.pingolin_mobile.SignedRefreshRecord?,
        val pending: List<String>,
        /** This user's removals without a key change the record names: forgotten once it lands. */
        val settles: Set<String> = emptySet(),
        /**
         * This user's role changes the record writes, or never can from here, each with the role it
         * settles: forgotten once it lands, while the change still holds that role.
         */
        val rolesSettle: Map<String, String> = emptyMap(),
        /** False: the change goes without the name, and rotate-keys keeps the circle's. */
        val sealsName: Boolean = true,
    )

    /** The repair due changed after a rotation admitted one: nothing was built. */
    private class RepairChanged : Exception()

    /** The SDK's locked "not yet" from the record builder (RefreshRecords.notImportedYet): nothing was built. */
    private class RecordsNotImportedYet : Exception()

    /**
     * Circles whose last build the SDK said "not yet" for ([RecordsNotImportedYet]): logged once each, until
     * a build of theirs goes through. Every pass tries again, with no back-off.
     */
    internal val notImportedYetNoted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun notImportedYet(circleId: String, what: String) {
        if (notImportedYetNoted.add(circleId)) {
            Log.i(TAG, "$what: $circleId — its records aren't imported far enough here yet; tried again after the next import")
        }
    }

    /**
     * [build], a person's own eject or hand-over: when the SDK says "not yet", the records are read once
     * and it builds once more; still not yet, [RecordsNotImportedYet] again. Never under the records lock:
     * neither path is a repair.
     */
    private suspend fun <T> buildOrImportOnce(build: suspend () -> T): T = try {
        build()
    } catch (e: RecordsNotImportedYet) {
        syncRefreshRecords()
        build()
    }

    /** A rotation without a record, as on a legacy circle: the plan, and a one-step link. */
    private fun legacyRotation(
        circleId: String,
        senderUserId: String,
        plan: RotationPlanner.Plan,
        circleName: String,
        change: KeyChange? = null,
    ): RotationToSend {
        if (personalSlot(circleId, "rotation")) throw NotACircle(circleId)
        // Generate the new key and wrap it for every member it goes to in one SDK
        // call, passing our own user_id as sender plus a signing pubkey per recipient.
        val rotationBundle = cryptoManager.prepareKeyRotation(
            circleId,
            senderUserId,
            plan.wrap.map { it.userId },
            plan.wrap.map { it.sharingPublicKey },
            // Signing pubkeys per member, for caller-side TOFU pinning; the SDK does
            // not bind them into the wrap signature input. Empty for a member the
            // server has no signing key for.
            plan.wrap.map { it.signingPublicKey.orEmpty() },
            circleName
        ).also { change?.minted = it.newKeyVersion }
        // Create key chain link (old key encrypted with new key)
        val link = try {
            cryptoManager.createKeyChainLink(circleId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create key chain link for auto-rotation: ${e.message}")
            null
        }
        return RotationToSend(rotationBundle, link, null, plan.pending)
    }

    /**
     * Ownership claims: [circleId] has refresh records, read here, and their chain has this
     * account as neither its owner nor an admin, so no record of this device's can carry a
     * removal. Records not read yet are read first; a circle whose records still aren't, or
     * whose chain forked, isn't judged here.
     */
    private suspend fun lacksChainRightToRemove(circleId: String): Boolean {
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return false
        if (recordsHead(circleId) == 0) syncRefreshRecords()
        if (recordsHead(circleId) == 0) return false
        val self = sessionManager.getUserId() ?: return false
        val role = runCatching { cryptoManager.chainRole(circleId, self) }.getOrNull()
        return role != uniffi.pingolin_mobile.CircleRole.OWNER && role != uniffi.pingolin_mobile.CircleRole.ADMIN
    }

    /** The version of [circleId]'s records this device took as its head: 0 before they're read in this process, or while forked. */
    private fun recordsHead(circleId: String): Int = runCatching { cryptoManager.refreshHeadVersions()[circleId] }.getOrNull() ?: 0

    /**
     * An eject by the chain's owner or an admin (the eject checks that next) may rotate from
     * below [circleId]'s version V, whose key never came here, when this device took V's record:
     * its head, accepted or a reference (never waiting or rejected), not forked (the head reads 0
     * then), and V is the circle's version. The SDK builds on V's record (its members carried
     * over, its removals and roles honored) and links down to the highest version held here,
     * within the floors. What V's holders sealed under it stays out of reach through the chain.
     */
    private suspend fun ejectsFromBelow(circleId: String): Boolean {
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return false
        if (recordsHead(circleId) == 0) syncRefreshRecords()
        val circle = circleDao.getById(circleId) ?: return false
        return recordsHead(circleId) == circle.keyVersion
    }

    /**
     * The name a key change of [circle] carries to the new key: the circle's sealed name as this process last
     * read it from the list, opened now, only when the version that opened it is the one this device seals
     * under (the SDK's one test) AND that is the version the change replaces. Else null: the change omits the
     * name, and the server keeps the circle's (rotate-keys and eject take none). So a name sealed under an
     * older key, which a removed member still holds, is never carried into the new one, nor one under a
     * version this device holds but hasn't accepted.
     */
    private fun sealableName(circle: CircleEntity): String? {
        val (sealed, _) = sealedNames[circle.id] ?: return null
        val opened = runCatching {
            cryptoManager.decryptDataV2Opened(circle.id, EncryptedData(nonce = sealed.nonce, ciphertext = sealed.ciphertext), "name")
        }.getOrNull() ?: return null
        if (!runCatching { cryptoManager.isCurrentKeyVersion(circle.id, opened.keyVersion) }.getOrDefault(false)) return null
        if (runCatching { cryptoManager.getActiveVersion(circle.id) }.getOrNull() != circle.keyVersion) return null
        return opened.text.takeIf { it.isNotBlank() && !looksLikeRawHex(it) && !LockedCircle.isPlaceholder(it) }
    }

    /**
     * [circle]'s key change with its record, from the member list [planRotationFor] just judged.
     * On a circle with refresh records, the records decide who's included among those [plan]
     * would wrap to ([RecordedRotation.rotation]); on one with none, an owner's or admin's
     * device ([canRotate]) writes its first record, the transition, by the current rules
     * ([RecordedRotation.transition]). [removing]: the member an eject removes. [repairing]: the
     * rejected record a repair answers, as it was admitted; built only while it is still the one
     * due, or [RepairChanged]. Null: a key change without a record, as on a legacy circle (a
     * plain member's device on a record-less circle, or a transition the builder refused: the
     * circle stays legacy). A circle with records that can't be rotated from here now throws.
     */
    private suspend fun recordedRotationFor(
        circle: CircleEntity,
        plan: RotationPlanner.Plan,
        canRotate: Boolean,
        self: String,
        circleName: String,
        removing: String? = null,
        repairing: uniffi.pingolin_mobile.RecordVersionStatus? = null,
        handOverTo: String? = null,
        /** The attempt this builds for: told the version its key was minted at, the moment it is. */
        change: KeyChange? = null,
    ): RotationToSend? {
        val circleId = circle.id
        if (personalSlot(circleId, "rotation")) throw NotACircle(circleId)
        // This device's own record the server took isn't judged here yet. Built now, the next record would
        // take that same version and overwrite its key. It waits for an import served that record, which
        // accepts it at once with the link it sent (noteSentLink). containsKey: on a ConcurrentHashMap, `in`
        // is Java's contains(value).
        if (ownRecordTaken.containsKey(circleId)) throw RecordsNotImportedYet()
        val recorded = runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)
        if (!recorded && !canRotate) return null
        // The SDK's checks read the records imported in this process: none yet, read them now.
        if (recorded && (runCatching { cryptoManager.refreshHeadVersions()[circleId] }.getOrNull() ?: 0) == 0) syncRefreshRecords()
        val snapshot = trustSnapshots[circleId] ?: throw IllegalStateException("no member list judged for $circleId")
        val members = RefreshRecords.recordMembers(snapshot) { userId -> confirmedKeys(circleId, userId, snapshot) }
        val listed = snapshot.rows.keys
        val wrap = plan.wrap.map { it.userId }.toSet()
        // Re-invites from here that ran out unused bring back the removals they cleared, unless
        // their member joined with them: settled on this complete list, before the removals are read.
        if (recorded) ownRemovals.settleRunOut(circleId, listed, nowSecs())
        val roleChanges = if (recorded) ownerRoleChanges.of(circleId) else emptyMap()
        fun chainRole(userId: String) = runCatching { cryptoManager.chainRole(circleId, userId) }.getOrNull()
        val chainOwner = roleChanges.isNotEmpty() && chainRole(self) == uniffi.pingolin_mobile.CircleRole.OWNER
        // An admin in the chain whom the list shows as the owner names itself owner in the record
        // that removes the chain's owner, or, with the chain's owner off the list, in its next
        // record. While that owner's departure doesn't count yet, the builder refuses the claim,
        // and the record goes without it, once.
        val removesChainOwner = removing != null && chainRole(removing) == uniffi.pingolin_mobile.CircleRole.OWNER
        val claims = recorded && chainRole(self) == uniffi.pingolin_mobile.CircleRole.ADMIN &&
            RefreshRecords.role(snapshot.rows.entries.firstOrNull { it.key.equals(self, ignoreCase = true) }?.value?.role) == uniffi.pingolin_mobile.CircleRole.OWNER &&
            (removesChainOwner || listed.none { chainRole(it) == uniffi.pingolin_mobile.CircleRole.OWNER })
        val eligible = if (recorded) {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
                .filter { it.eligible }.map { it.userId }.toSet()
        } else {
            emptySet()
        }
        // The builder's previous record is the last one this device rejected after its head, if
        // any: every listed member it names, included or pending, is carried pending when it can't
        // be included (or named removed), or the builder refuses the plan (the report's
        // tipNamedListed). This device's own record taken, nothing is built until an import has
        // judged it (above), so the tip is that import's.
        val rejectedIncluded = if (recorded) {
            RefreshRecords.rejectedIncluded(recordsJudged[circleId]?.report?.tipNamedListed.orEmpty(), listed)
        } else {
            emptySet()
        }
        fun rotationPlan(claimsOwnership: Boolean) = RecordedRotation.rotation(
            self, listed, wrap, eligible,
            chainRole = ::chainRole,
            ownRemovals = ownRemovals.of(circleId) + setOfNotNull(removing?.lowercase()),
            ownerRoleChanges = roleChanges.mapNotNull { (userId, role) -> RefreshRecords.role(role)?.let { userId to it } }.toMap() +
                // The hand-over: the new owner, and this account a member.
                (handOverTo?.let { mapOf(it.lowercase() to uniffi.pingolin_mobile.CircleRole.OWNER, self.lowercase() to uniffi.pingolin_mobile.CircleRole.MEMBER) }.orEmpty()),
            claimsOwnership = claimsOwnership,
            rejectedIncluded = rejectedIncluded,
        )
        val (kind, plannedFirst) = if (recorded) {
            (if (repairing != null) uniffi.pingolin_mobile.RefreshRecordKind.REPAIR else uniffi.pingolin_mobile.RefreshRecordKind.ROTATION) to rotationPlan(claims)
        } else {
            val keyless = snapshot.rows.values.filter { it.sharingPublicKey.isNullOrBlank() || it.signingPublicKey.isNullOrBlank() }
                .map { it.userId }.toSet()
            uniffi.pingolin_mobile.RefreshRecordKind.TRANSITION to RecordedRotation.transition(
                self, snapshot.rows.mapValues { (_, row) -> RefreshRecords.role(row.role) }, keyless, wrap, removing,
            )
        }
        var recordedPlan = plannedFirst
        // The hand-over's new owner gets the new key, or it isn't written.
        if (handOverTo != null && recordedPlan.included.none { it.equals(handOverTo, ignoreCase = true) }) throw HandOverNotIncluded()
        // A repair builds on what the SDK's chain holds now, so the one admitted, and the name chosen for
        // it, must be the one due: checked, and built, under the lock every records import holds. The
        // lock isn't reentrant: nothing under it may take it again.
        if (repairing != null) recordsMutex.lock()
        val bundle = try {
            if (repairing != null && repairDue(circle)?.let { it.version == repairing.version && it.problem == repairing.problem } != true) {
                throw RepairChanged()
            }
            try {
                cryptoManager.prepareRecordedRotation(circleId, self, kind, recordedPlan.entries, members, rosterComplete = true, currentCircleNamePlaintext = circleName)
                    .also { change?.minted = it.rotation.newKeyVersion }
            } catch (e: MobileException) {
                // The SDK's locked "not yet": nothing is built, and never the claim's retry, which
                // would only be told the same.
                if (RefreshRecords.notImportedYet(e)) throw RecordsNotImportedYet()
                if (recorded && claims && !removesChainOwner) {
                    Log.i(TAG, "rotation: $circleId — the claim to the chain's ownership isn't taken yet; the record goes without it")
                    recordedPlan = rotationPlan(claimsOwnership = false)
                    try {
                        cryptoManager.prepareRecordedRotation(circleId, self, kind, recordedPlan.entries, members, rosterComplete = true, currentCircleNamePlaintext = circleName)
                            .also { change?.minted = it.rotation.newKeyVersion }
                    } catch (again: MobileException) {
                        if (RefreshRecords.notImportedYet(again)) throw RecordsNotImportedYet()
                        throw again
                    }
                } else {
                    if (recorded) throw e
                    Log.i(TAG, "rotation: $circleId — its first record wasn't written here (${e.javaClass.simpleName}); it stays as it is")
                    transitionsRefused[circleId] = System.currentTimeMillis()
                    return null
                }
            }
        } finally {
            if (repairing != null) recordsMutex.unlock()
        }
        notImportedYetNoted.remove(circleId)
        // Everyone the key list names, as the rotation plan has it: the server wants each active member
        // wrapped or named.
        val survivors = (plan.wrap.map { it.userId } + plan.pending).distinct()
            .filterNot { removing != null && it.equals(removing, ignoreCase = true) }
        return RotationToSend(
            bundle.rotation, bundle.rotation.keyChainLink, bundle.refreshRecord, recordedPlan.requestPending(survivors, self),
            settles = if (recorded) ownRemovals.of(circleId) else emptySet(),
            // Written with their roles, or no longer listed; all of them where this account isn't
            // the chain's owner, which only its owner's record could write. Each by member and role.
            rolesSettle = RecordedRotation.rolesSettle(chainOwner, roleChanges, listed, recordedPlan),
        )
    }

    /**
     * The rotation plan for [circle] (see [RotationPlanner]), with what it sets in
     * motion: the complete member list is fetched and judged now — null when it cannot
     * be, since nothing may be wrapped against a roster this device could not check — and
     * confirmed members' keys are gated. Everyone else is left pending.
     */
    private suspend fun planRotationFor(
        circle: CircleEntity,
        memberPublicKeys: List<MemberPublicKeyResponse>,
        forDelivery: Boolean,
        cause: String? = _keyRotationCauses.value[circle.id],
        leaving: String? = null,
    ): RotationPlanner.Plan? {
        val circleId = circle.id
        val selfUserId = sessionManager.getUserId() ?: return null
        val snapshot = refreshMembersAndJudge(circleId, deliver = false).getOrNull()?.let { trustSnapshots[circleId] }
        if (snapshot == null) {
            Log.w(TAG, "rotation: could not fetch the complete member list for $circleId — wrapping nothing")
            return null
        }
        val roster = snapshot.roster.map { it.userId }.toSet()
        if (leaving != null) {
            // The member being removed is still on the roster until the eject lands, so
            // they are still pinned — and a still-pinned member could sign a wrap for the
            // version this removal is about to create. Retire it first. If the removal
            // fails, the next member list confirms and pins them again.
            runCatching { cryptoManager.retainMemberPins(circleId, (snapshot.result.confirmed.keys - leaving + selfUserId).toList()) }
                .onFailure { Log.w(TAG, "rotation: could not retire $leaving's pin in $circleId: ${it.message}") }
        }
        val confirmed = snapshot.result.confirmed.keys - setOfNotNull(leaving)
        // Only confirmed members' keys are checked: anyone else gets neither a
        // pin nor a wrap.
        val listed = memberPublicKeys.filter { it.userId in roster && it.userId in confirmed }
        val blocked = gateMembersForRotation(circleId, listed, snapshot.result)
        val plan = RotationPlanner.plan(
            selfUserId = selfUserId,
            circleKeyVersion = circle.keyVersion,
            memberKeys = memberPublicKeys.map { member ->
                // An email invitee is wrapped to the key this device recorded for them.
                keyAccessApprovals.receiveOnlyKeys(circleId)[member.userId]
                    ?.takeIf { snapshot.result.receiveOnly(member.userId) }
                    ?.let { (sharing, signing) -> member.copy(sharingPublicKey = sharing, signingPublicKey = signing) }
                    ?: member
            },
            roster = roster,
            blocked = blocked,
            confirmed = confirmed,
            cause = cause,
            forDelivery = forDelivery,
        )
        if (plan.notInRoster.isNotEmpty()) {
            Log.w(
                TAG,
                "rotation: $circleId — ${plan.notInRoster.size} account(s) listed for the key are not in " +
                    "the member list; refusing to wrap to them"
            )
        }
        return plan
    }

    private fun dismissKeyAccessRequest(request: KeyAccessRequest) {
        _keyAccessRequests.update { requests ->
            requests.filterNot { it.circleId == request.circleId && it.userId == request.userId }
        }
        questionNotifier.answered(request.circleId, request.userId)
    }

    /**
     * "Let them in" (owner/admin), the person's tap: kept as a waiting change bound to exactly
     * the keys behind the number the question showed, with the version held now, as the iOS app
     * keeps every tap (the let-in order holds for the live answer too). The tap pins, approves
     * and signs nothing; the runner does, at once when this phone holds the circle's current
     * version, else once it does ([grantKeyAccess]). A refusal ends it with its line, and no
     * answer leaves it waiting: never a choice without an admission. Kept, the question is
     * answered; the failure says so when this phone is behind ("Waiting to sync…"), and it is
     * quiet when it goes now. Nothing kept ([PendingLines.tooMany], or no keys to bind): the
     * question stays for another answer, and the failure says why.
     */
    suspend fun letIn(request: KeyAccessRequest): Result<Unit> {
        val sharing = request.sharingKeyHex
        val signing = request.signingKeyHex
        if (sharing == null || signing == null) return Result.failure(Exception(LET_IN_DETAILS_CHANGED))
        val inputs = com.pingolin.track.domain.pending.PendingInputs.LetIn(
            request.circleName, request.userId, request.displayName, request.reason.name, sharing, signing,
            tapVersion = heldVersion(request.circleId),
        )
        val kept = pendingChanges.queue<Unit>(request.circleId, inputs).exceptionOrNull()
        // A late joiner's answer covers the invitation, whatever becomes of the change.
        if (kept is com.pingolin.track.domain.pending.QueuedForSync && request.reason == KeyAccessRequest.Reason.LATE_JOINER) {
            request.invitation?.let { lateJoinerAnswers.answer(request.circleId, it) }
        }
        if (kept !is com.pingolin.track.domain.pending.QueuedForSync) {
            return Result.failure(
                kept?.takeIf { it.javaClass == Exception::class.java }
                    ?: Exception(com.pingolin.track.domain.pending.PendingLines.notDone(com.pingolin.track.domain.pending.PendingInputs.subject(inputs)))
            )
        }
        dismissKeyAccessRequest(request)
        return if (currentKeyGate.holdsCurrentKey(request.circleId)) Result.success(Unit) else Result.failure(kept)
    }

    /**
     * A waiting "Let them in" runs: this user's own choice, so this device delivers to them, and
     * an admission tells every other member's app too, so nobody else is asked. Both bind exactly
     * the keys behind the number the question showed: they are PINNED before anything else, so
     * the next pass never takes other keys on first contact, and a swap after the answer is a
     * key change, never delivered to. The member list showing other keys signs and pins
     * nothing: the question comes back with the new number.
     *
     * The order: the admission, then the choice, read back, then the hand-over, so a change that
     * ends without its admission leaves no choice behind. One whose admission is already stored
     * (the statement look) pins and signs nothing again: only the choice is left
     * ([recordLetInChoice]).
     */
    suspend fun grantKeyAccess(
        request: KeyAccessRequest,
        pendingRun: com.pingolin.track.domain.pending.PendingRun,
    ): Result<Unit> {
        dismissKeyAccessRequest(request)
        if (pendingRun.admissionHeld) {
            return if (recordLetInChoice(request.circleId, request.userId)) Result.success(Unit)
            else Result.failure(com.pingolin.track.domain.pending.StatementNotSaved())
        }
        val sharing = request.sharingKeyHex
        val signing = request.signingKeyHex
        val listedNow = sharing != null && signing != null &&
            cachedMemberSharingPubkey(request.circleId, request.userId).equals(sharing, ignoreCase = true) &&
            cachedMemberSigningPubkey(request.circleId, request.userId).equals(signing, ignoreCase = true)
        val pinned = listedNow && runCatching {
            cryptoManager.pinMemberKeys(request.circleId, request.userId, sharing!!, signing!!).state
        }.getOrNull().let { it == PinState.NEW_PINNING || it == PinState.MATCHED }
        if (!pinned) {
            Log.w(TAG, "Let them in for ${request.userId} in ${request.circleId}: their details changed since the question; asked again")
            runCatching { refreshMembersAndJudge(request.circleId, deliver = false) }
            return Result.failure(Exception(LET_IN_DETAILS_CHANGED))
        }
        // The choice only once its own admission is stored. A choice that didn't reach the disk
        // leaves the change waiting, and its next run finds the admission held (the statement
        // look) and records only the choice.
        val admitted = admitStatement(request.circleId, request.userId, sharing!! to signing!!, pendingRun.onSigned, pendingRun.beforeSend)
        if (admitted.isFailure) return admitted
        return if (recordLetInChoice(request.circleId, request.userId)) admitted
        else Result.failure(com.pingolin.track.domain.pending.StatementNotSaved())
    }

    /**
     * A let-in: this device's choice for [userId], whose admission (a waiting change's own) is
     * stored: recorded, read back, then the circle handed over. False when the choice didn't
     * reach the disk: nothing is handed over, and the change waits.
     */
    suspend fun recordLetInChoice(circleId: String, userId: String): Boolean {
        if (!keyAccessApprovals.approveDurably(circleId, listOf(userId), com.pingolin.track.util.KeyAccessApprovals.Origin.OWN)) return false
        keyAccessDeferred.remove("$circleId:$userId")
        deliveryPasses.remove(circleId)
        deliverOwedAccess(circleId)
        return true
    }

    /** What a waiting let-in's re-check found. */
    enum class LetInNow {
        /** Still waiting to be let in: asked about on this device. */
        WAITING,
        /** Confirmed on this device by this person's own choice ([com.pingolin.track.util.KeyAccessApprovals.Origin.OWN]). */
        CHOSEN_HERE,
        /** Confirmed on this device otherwise: someone else let them in. */
        LET_IN,
        /** Neither: nobody would be asked about them any more, nor are they confirmed. */
        NOT_ASKED,
    }

    /**
     * A waiting let-in's re-check: whether [userId] is still waiting to be let into [circleId] on
     * this device, from the member list judged whole and fresh; null when it couldn't be had.
     */
    suspend fun letInNow(circleId: String, userId: String): LetInNow? {
        refreshMembersAndJudge(circleId, deliver = false).getOrNull() ?: return null
        val result = trustSnapshots[circleId]?.result ?: return null
        val origin = result.confirmed.entries.firstOrNull { it.key.equals(userId, ignoreCase = true) }?.value
        return when {
            result.askable.any { it.equals(userId, ignoreCase = true) } -> LetInNow.WAITING
            origin == com.pingolin.track.util.KeyAccessApprovals.Origin.OWN -> LetInNow.CHOSEN_HERE
            origin != null -> LetInNow.LET_IN
            else -> LetInNow.NOT_ASKED
        }
    }

    /**
     * A waiting promotion's re-check: the keys a promotion of [userId] would sign now — the keys
     * this device holds for them, as the member list shows them, judged fresh — or null.
     */
    suspend fun promotionKeysNow(circleId: String, userId: String): Pair<String, String>? {
        refreshMembersAndJudge(circleId, deliver = false).getOrNull() ?: return null
        return MemberConfirmation.promotionAdmissionKeys(promotionKeys(circleId, userId), listedKeys(circleId, userId), null)
    }

    /**
     * "It's them" to [keys], answered while a promotion waited: taken through the live answer
     * ([approveMemberKeyChange]) when the run gets to it, never before.
     */
    suspend fun acceptMemberKeys(circleId: String, userId: String, name: String, keys: Pair<String, String>) {
        val receiveOnly = trustSnapshots[circleId]?.result?.receiveOnly(userId) == true
        approveMemberKeyChange(
            MemberKeyAlert(
                circleId = circleId, userId = userId, displayName = name, state = PinState.CHANGED_BOTH,
                previousPinnedAt = 0L, newSharingHex = keys.first, newSigningHex = keys.second, receiveOnly = receiveOnly,
            )
        )
    }

    /**
     * A waiting change asks the person again: in the background, the existing question
     * notifier, once, as for the live question.
     */
    fun notifyPendingQuestion(
        circleId: String,
        inputs: com.pingolin.track.domain.pending.PendingInputs,
        ask: com.pingolin.track.domain.pending.PendingQuestions.Ask,
    ) {
        val questions = com.pingolin.track.domain.pending.PendingQuestions
        when (ask.ask) {
            questions.INVITEE ->
                (inputs as? com.pingolin.track.domain.pending.PendingInputs.Invite)?.let { questionNotifier.inviteeChanged(circleId, ask.userId, it.email) }
            questions.RENEWAL ->
                (inputs as? com.pingolin.track.domain.pending.PendingInputs.RenewInvitation)?.let { questionNotifier.inviteeChanged(circleId, ask.userId, it.email) }
            questions.MEMBER ->
                questionNotifier.memberReset(circleId, ask.userId, (inputs as? com.pingolin.track.domain.pending.PendingInputs.MakeAdmin)?.memberName)
            questions.LET_IN ->
                (inputs as? com.pingolin.track.domain.pending.PendingInputs.LetIn)?.let {
                    questionNotifier.waitingToJoin(circleId, inputs.circleName, ask.userId, it.memberName)
                }
        }
    }

    /**
     * The person answered what a waiting change asked: its notification goes. "Not now" to
     * letting someone in ([deferLetIn]) keeps the live question away until a restart, as the
     * live question's own "Not now" does.
     */
    fun pendingQuestionAnswered(circleId: String, userId: String, deferLetIn: Boolean = false) {
        if (deferLetIn) keyAccessDeferred.add("$circleId:$userId")
        questionNotifier.answered(circleId, userId)
    }

    /**
     * "Withdraw" to a waiting renewal's question (as for the live question): the invitation is
     * withdrawn for good and never renewed. Nothing is signed.
     */
    suspend fun withdrawInvitation(circleId: String, inviteId: String, statementId: String): Result<Unit> {
        keyAccessApprovals.markInvitationDead(circleId, statementId)
        ownRemovals.invitationWithdrawn(circleId, statementId)
        // Its statement is known here: the withdrawal needn't read the list to name it. One the
        // list has named since (the row's statement replaced) is kept, and goes dead too.
        inviteStatementIds.putIfAbsent(inviteId, statementId)
        return revokeInvite(circleId, inviteId).recoverCatching { throw Exception("Couldn't withdraw the invitation just now.") }
    }

    /** [userId]'s keys in [circleId] as the member list last showed them, or null. */
    fun listedKeys(circleId: String, userId: String): Pair<String, String>? =
        cachedMemberSharingPubkey(circleId, userId)?.let { sharing ->
            cachedMemberSigningPubkey(circleId, userId)?.let { signing -> sharing to signing }
        }

    /** "Not now": they stay out, and are asked about again after a restart. */
    fun deferKeyAccess(request: KeyAccessRequest) {
        // "Not now" is the late joiner's answer, for good; they stay waiting until someone
        // invites them again.
        if (request.reason == KeyAccessRequest.Reason.LATE_JOINER) {
            request.invitation?.let { lateJoinerAnswers.answer(request.circleId, it) }
        } else {
            keyAccessDeferred.add("${request.circleId}:${request.userId}")
        }
        dismissKeyAccessRequest(request)
    }

    /**
     * After a roster pass: the late-joiner questions this device asks about [circleId], raised
     * as [KeyAccessRequest.Reason.LATE_JOINER] beside the others, and taken to the person as the
     * "waiting to join" notification. None on a circle without records.
     */
    private suspend fun raiseLateJoinerQuestions(circleId: String, names: Map<String, String>) {
        val circle = circleDao.getById(circleId) ?: return
        val snapshot = trustSnapshots[circleId] ?: return
        val self = sessionManager.getUserId() ?: return
        fun ours(r: KeyAccessRequest) = r.circleId == circleId && r.reason == KeyAccessRequest.Reason.LATE_JOINER
        val raised = _keyAccessRequests.value.filter(::ours)
        val pass = lateJoinerQuestions(circleId, snapshot, self, raised)
        // Closed because their member got in meanwhile, or left the list: answered for good.
        for (invitation in pass.closed) {
            if (!lateJoinerAnswers.answer(circleId, invitation)) Log.w(TAG, "$circleId: a closed late-joiner question's answer wasn't kept")
        }
        val wanted = pass.questions.mapNotNull { q ->
            val row = snapshot.rows[q.userId] ?: return@mapNotNull null
            val keys = confirmedKeys(circleId, q.userId, snapshot) ?: return@mapNotNull null
            KeyAccessRequest(
                circleId = circleId,
                circleName = circle.name,
                userId = q.userId,
                displayName = names[q.userId]?.takeIf { it.isNotBlank() } ?: row.displayName ?: "A new member",
                email = null,
                reason = KeyAccessRequest.Reason.LATE_JOINER,
                sharingKeyHex = keys.first,
                signingKeyHex = keys.second,
                lateCase = q.case,
                // Their name, never the stand-in this device keeps for a member it has none for.
                inviterName = LateJoiners.inviterName(q) { signer ->
                    names[signer]?.takeUnless { it.isBlank() || it == UNNAMED_MEMBER }
                        ?: circleMemberDao.anyDisplayName(signer)?.takeUnless { it == UNNAMED_MEMBER }
                },
                invitation = q.invitation,
            )
        }
        _keyAccessRequests.update { requests -> requests.filterNot(::ours) + wanted }
        for (request in wanted) if (raised.none { it.userId == request.userId }) {
            questionNotifier.waitingToJoin(circleId, circle.name, request.userId, request.displayName)
        }
        for (request in raised) if (wanted.none { it.userId == request.userId }) {
            questionNotifier.answered(circleId, request.userId)
        }
    }

    /**
     * [LateJoiners.questions] for [circleId] as [snapshot] judged it: the SDK's eligibility
     * flags, the invitations, the chain's roles; with the [raised] questions it closes
     * ([LateJoiners.closed]), whose invitations count as answered from this pass on.
     */
    private suspend fun lateJoinerQuestions(
        circleId: String,
        snapshot: CircleTrustSnapshot,
        self: String,
        raised: List<KeyAccessRequest>,
    ): LateJoiners.Pass {
        val none = LateJoiners.Pass(emptyList(), emptyList())
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return none
        fun chainRole(userId: String) = runCatching { cryptoManager.chainRole(circleId, userId) }.getOrNull()
        val selfRole = chainRole(self)
        // Only the chain's owner, or an inviter who is an owner or admin in it, is ever asked.
        if (selfRole != uniffi.pingolin_mobile.CircleRole.OWNER && selfRole != uniffi.pingolin_mobile.CircleRole.ADMIN) return none
        val members = RefreshRecords.recordMembers(snapshot) { userId -> confirmedKeys(circleId, userId, snapshot) }
        val eligibility = runCatching {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
        }.getOrNull() ?: return none
        val closed = LateJoiners.closed(raised.mapNotNull { r -> r.invitation?.let { r.userId to it } }, eligibility)
        // An answer already waiting to go out is not asked again.
        val waiting = runCatching { pendingChanges.waitingTargets(circleId, com.pingolin.track.domain.pending.PendingKind.LET_IN) }
            .getOrDefault(emptySet())
        val candidates = eligibility.mapNotNull { e ->
            val userId = e.userId
            if (userId.equals(self, ignoreCase = true) || waiting.any { it.equals(userId, ignoreCase = true) }) return@mapNotNull null
            if (!snapshot.result.confirmed.containsKey(userId.lowercase())) return@mapNotNull null
            lateJoinerCandidate(circleId, snapshot, self, e, ::chainRole)
        }
        val questions = LateJoiners.questions(self, selfRole, candidates, lateJoinerAnswers.answered(circleId) + closed, raised.map { it.userId }.toSet())
        return LateJoiners.Pass(questions, closed)
    }

    /**
     * [e]'s member as [LateJoiners.questions] reads them: the invitation they joined with, as it holds here
     * ([joinedWithHere]), its signer's role in the chain, and the flags [LateJoiners.Candidate] carries about
     * it (ownPreResetBasis, bindsListedKeys, chainUnforked). Every snapshot is of a complete member list
     * (allMemberPages), and only records imported in this process, the last import not forked, count as
     * unforked.
     */
    private fun lateJoinerCandidate(
        circleId: String,
        snapshot: CircleTrustSnapshot,
        self: String,
        e: uniffi.pingolin_mobile.MemberEligibility,
        chainRole: (String) -> uniffi.pingolin_mobile.CircleRole?,
    ): LateJoiners.Candidate {
        val row = snapshot.rows[e.userId]
        val joined = row?.let { joinedWithHere(circleId, self, it) }
        val invitation = joined?.first?.let(LateJoiners::invitation)
        return LateJoiners.Candidate(
            e.userId, e, invitation, invitation?.let { chainRole(it.signer) },
            ownPreResetBasis = row != null && joined != null && ownPreResetBasis(circleId, self, row, joined, snapshot),
            bindsListedKeys = row != null && joined != null && bindsListedKeys(joined.first, row),
            chainUnforked = recordsJudged[circleId]?.report?.forked == false,
        )
    }

    /**
     * [joined], the invitation [row]'s member joined with, is this account's own from before its password reset
     * (its current key doesn't verify it), and the member is confirmed here only through it: (i) a restored key
     * from before the reset verifies it, and nothing else confirms them (previousKeyOnly); or (ii) this device's
     * own approval of them rests on it by id ([ownApprovalNames]), whether a restored key verifies it or nothing
     * does. One that nothing here backs is the server's word, and asks nothing.
     */
    private fun ownPreResetBasis(
        circleId: String,
        self: String,
        row: com.pingolin.track.data.remote.dto.MemberResponse,
        joined: Pair<MembershipStatements.Statement, MembershipStatements.Signed>,
        snapshot: CircleTrustSnapshot,
    ): Boolean {
        val (statement, signed) = joined
        if (!com.pingolin.track.util.AccountIds.same(statement.signerUserId, self)) return false
        val ownKey = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return false
        if (runCatching { membershipStatements.verify(signed.statementHex, signed.signatureHex, ownKey) }.getOrDefault(true)) return false
        val previousKeyOnly = snapshot.result.previousKeyOnly.any { com.pingolin.track.util.AccountIds.same(it, row.userId) }
        if (previousKeyOnly && runCatching { membershipStatements.signedByOwnPreviousKey(signed.statementHex, signed.signatureHex) }.getOrDefault(false)) {
            return true
        }
        return ownApprovalNames(circleId, row.userId, statement.statementId)
    }

    /**
     * This device's own approval of [member], made on a statement or by this person's "Let them in" (never a
     * seed), rests on [statementId] by its record ([com.pingolin.track.util.KeyAccessApprovals.approvalBasis]).
     */
    private fun ownApprovalNames(circleId: String, member: String, statementId: String): Boolean {
        val key = com.pingolin.track.util.AccountIds.storeKey(member)
        val origin = keyAccessApprovals.origins(circleId).entries.firstOrNull { com.pingolin.track.util.AccountIds.storeKey(it.key) == key }?.value
        if (origin != com.pingolin.track.util.KeyAccessApprovals.Origin.STATEMENT && origin != com.pingolin.track.util.KeyAccessApprovals.Origin.OWN) return false
        val id = com.pingolin.track.util.AccountIds.storeKey(statementId)
        return keyAccessApprovals.approvalBasis(circleId).entries
            .filter { com.pingolin.track.util.AccountIds.storeKey(it.key) == key }
            .any { (_, ids) -> ids.any { com.pingolin.track.util.AccountIds.storeKey(it) == id } }
    }

    /** [statement] binds exactly the keys [row] lists for its member. */
    private fun bindsListedKeys(statement: MembershipStatements.Statement, row: com.pingolin.track.data.remote.dto.MemberResponse): Boolean {
        val sharing = statement.subjectSharingKeyHex
        val signing = statement.subjectSigningKeyHex
        return sharing != null && signing != null &&
            sharing.equals(row.sharingPublicKey, ignoreCase = true) && signing.equals(row.signingPublicKey, ignoreCase = true)
    }

    /**
     * The invitation [row]'s member joined with, as it holds here ([LateJoiners.joinedWith]):
     * one this account signed, under its own key, current or from before a reset; anyone
     * else's, under the key this device pinned for them. An unpinned signer's, or one that
     * doesn't hold, counts for nothing.
     */
    private fun lateJoinerInvitation(circleId: String, self: String, row: com.pingolin.track.data.remote.dto.MemberResponse): MembershipStatements.Statement? =
        joinedWithHere(circleId, self, row)?.first

    /**
     * [lateJoinerInvitation], with the signed statement it read. Also this account's own that no key
     * held now verifies, when this device's own approval of the member rests on it by id
     * ([ownApprovalNames]; nothing wider).
     */
    private fun joinedWithHere(
        circleId: String,
        self: String,
        row: com.pingolin.track.data.remote.dto.MemberResponse,
    ): Pair<MembershipStatements.Statement, MembershipStatements.Signed>? {
        val ownKey = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull()
        return LateJoiners.joinedWithSigned(
            (listOfNotNull(row.invitation) + row.vouches.orEmpty()).map { MembershipStatements.Signed(it.statement, it.signature) },
            row.userId, circleId, membershipStatements::parse,
        ) { statement, signed ->
            if (statement.signerUserId.equals(self, ignoreCase = true)) {
                ownKey != null && membershipStatements.verify(signed.statementHex, signed.signatureHex, ownKey) ||
                    membershipStatements.signedByOwnPreviousKey(signed.statementHex, signed.signatureHex) ||
                    (!com.pingolin.track.util.AccountIds.same(row.userId, self) && ownApprovalNames(circleId, row.userId, statement.statementId))
            } else {
                memberPins.pinnedSigningKey(circleId, statement.signerUserId)
                    ?.let { membershipStatements.verify(signed.statementHex, signed.signatureHex, it) } == true
            }
        }
    }

    /**
     * The joiner's own screen: when the invitation this account joined with ran out (5 minutes'
     * grace for clocks) before any phone handed it the circle, whom that asks to let it in: the
     * inviter, or the circle's owner when the inviter isn't an owner or admin, by this device's
     * own member list. The roles are the server's word, and display only: with no key yet this
     * device can't read the chain. The invitation isn't: only one that holds under the key this
     * device pinned for its signer counts ([lateJoinerInvitation]). Their name as this device
     * has it, or none: never a stand-in. Null otherwise: the usual line.
     */
    suspend fun invitationRanOutAsked(circleId: String): LockedCircle.RanOut? {
        val self = sessionManager.getUserId() ?: return null
        if (runCatching { cryptoManager.hasCircleKey(circleId) }.getOrDefault(true)) return null
        val rows = trustSnapshots[circleId]?.rows?.values ?: return null
        val own = rows.firstOrNull { it.userId.equals(self, ignoreCase = true) } ?: return null
        val invitation = lateJoinerInvitation(circleId, self, own) ?: return null
        if (invitation.expiresAt <= 0 || invitation.expiresAt + INVITATION_GRACE_SECS >= nowSecs()) return null
        val inviter = rows.firstOrNull { it.userId.equals(invitation.signerUserId, ignoreCase = true) }
        val asked = inviter?.takeIf { it.role == "owner" || it.role == "admin" } ?: rows.firstOrNull { it.role == "owner" } ?: return null
        return LockedCircle.RanOut(
            circleMemberDao.get(circleId, asked.userId)?.displayName?.takeUnless { it.isBlank() || it == UNNAMED_MEMBER }
                ?: asked.displayName?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * A waiting let-in's re-check for a late joiner: confirmed here, so whether the circle's
     * records let them have the key now. Let in meanwhile (an admission from any device):
     * [LetInNow.LET_IN]; still a late joiner: [LetInNow.WAITING]; neither: [LetInNow.NOT_ASKED].
     * Null when it can't be told.
     */
    suspend fun lateJoinerNow(circleId: String, userId: String): LetInNow? {
        refreshMembersAndJudge(circleId, deliver = false).getOrNull() ?: return null
        val snapshot = trustSnapshots[circleId] ?: return null
        val members = RefreshRecords.recordMembers(snapshot) { id -> confirmedKeys(circleId, id, snapshot) }
        val e = runCatching {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
        }.getOrNull()?.firstOrNull { it.userId.equals(userId, ignoreCase = true) } ?: return null
        return when {
            e.eligible -> LetInNow.LET_IN
            e.invitationExpired || e.invitationSignerNotChainAdmin || e.emailInvitationWithoutSignedId -> LetInNow.WAITING
            // Still invited only before this account's reset, as the question read it.
            invitedBeforeReset(circleId, snapshot, e) -> LetInNow.WAITING
            else -> LetInNow.NOT_ASKED
        }
    }

    /** Whether [e]'s member is still a late joiner of [LateJoiners.Case.INVITED_BEFORE_RESET] here, owner or admin in the chain. */
    private fun invitedBeforeReset(circleId: String, snapshot: CircleTrustSnapshot, e: uniffi.pingolin_mobile.MemberEligibility): Boolean {
        val self = sessionManager.getUserId() ?: return false
        fun chainRole(userId: String) = runCatching { cryptoManager.chainRole(circleId, userId) }.getOrNull()
        val selfRole = chainRole(self)
        if (selfRole != uniffi.pingolin_mobile.CircleRole.OWNER && selfRole != uniffi.pingolin_mobile.CircleRole.ADMIN) return false
        return LateJoiners.case(self, lateJoinerCandidate(circleId, snapshot, self, e, ::chainRole)) == LateJoiners.Case.INVITED_BEFORE_RESET
    }

    /**
     * Sign and store an admission (kind 3) for [userId]'s CURRENT keys: owner/admin devices
     * only, never while this device is behind, and only for keys this device holds for them
     * — never for a key change it has not accepted. A promotion passes the exact [keys] to
     * sign ([promotionKeys]). Returns whether the server stored it.
     */
    private suspend fun admit(circleId: String, userId: String, keys: Pair<String, String>? = null): Boolean =
        admitStatement(circleId, userId, keys, onSigned = null).isSuccess

    /**
     * [admit], telling how it went: stored; refused ([RefusedException] with its status and code);
     * not reached (an IOException); or not tried (behind, keys not confirmed, not signed). A
     * waiting change's run ([onSigned]) has the statement's id saved before the request leaves:
     * if it can't be, nothing is sent.
     */
    private suspend fun admitStatement(
        circleId: String,
        userId: String,
        keys: Pair<String, String>?,
        onSigned: (suspend (com.pingolin.track.domain.pending.SignedStatement) -> Unit)?,
        beforeSend: (suspend () -> Unit)? = null,
    ): Result<Unit> {
        val selfUserId = sessionManager.getUserId() ?: return Result.failure(Exception(PROMOTION_NOT_DONE))
        if (!currentKeyGate.holdsCurrentKey(circleId)) {
            Log.i(TAG, "admission for $userId in $circleId: this device is behind; not signing")
            return Result.failure(KeyNotCurrentException())
        }
        val sharing = keys?.first ?: cachedMemberSharingPubkey(circleId, userId) ?: return Result.failure(Exception(PROMOTION_NOT_DONE))
        val signing = keys?.second ?: cachedMemberSigningPubkey(circleId, userId) ?: return Result.failure(Exception(PROMOTION_NOT_DONE))
        val pinnedSharing = memberPins.pinnedSharingKey(circleId, userId)
        val pinnedSigning = memberPins.pinnedSigningKey(circleId, userId)
        if ((pinnedSharing != null && !pinnedSharing.equals(sharing, true)) ||
            (pinnedSigning != null && !pinnedSigning.equals(signing, true))
        ) {
            Log.w(TAG, "admission for $userId in $circleId: their key changed and is not confirmed; not signing")
            return Result.failure(Exception(PROMOTION_NOT_DONE))
        }
        val signed = try {
            membershipStatements.signAdmission(circleId, selfUserId, userId, sharing, signing)
        } catch (e: Exception) {
            Log.w(TAG, "admission for $userId in $circleId not signed: ${e.message}")
            return Result.failure(Exception(PROMOTION_NOT_DONE))
        }
        // A waiting change's run: the removal check asked again now that the version is fixed
        // (PendingRun.beforeSend).
        if (beforeSend != null) {
            try {
                beforeSend()
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        if (onSigned != null) {
            val id = membershipStatements.parse(signed.statementHex)?.statementId
                ?: return Result.failure(com.pingolin.track.domain.pending.StatementNotSaved())
            try {
                onSigned(com.pingolin.track.domain.pending.SignedStatement(id = id, subject = userId))
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return try {
            val response = tracksApi.storeMembershipStatement(
                circleId,
                userId,
                com.pingolin.track.data.remote.dto.StoreStatementRequest(signed.statementHex, signed.signatureHex),
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Admitted $userId to $circleId")
                Result.success(Unit)
            } else {
                Log.w(TAG, "admission for $userId in $circleId not stored: HTTP ${response.code()}")
                Result.failure(RefusedException(PROMOTION_NOT_DONE, response.code(), ApiErrors.codeOf(response)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "admission for $userId in $circleId not sent: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Take the circle as the server now has it, after a peer won the rotation race.
     *
     * Nothing local is authoritative at this point: the winner generated the new key,
     * wrapped it for us, and bumped `key_version`. So re-import the wrapped key and
     * overwrite the row from the server's own copy rather than writing any version we
     * computed — a wrong `keyVersion` here becomes the next rotation's
     * `expected_key_version` and loses that race too.
     *
     * Best-effort throughout: a failure just leaves the next refresh to reconcile.
     */
    private suspend fun adoptPeerRotation(circleId: String) {
        runCatching { importCircleKeys() }.onFailure {
            Log.w(TAG, "adoptPeerRotation: key import failed for $circleId: ${it.message}")
        }
        val dto = runCatching { tracksApi.getCircle(circleId) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()?.takeIf { it.success }
            ?.data
        if (dto == null) {
            Log.w(TAG, "adoptPeerRotation: could not re-fetch $circleId — next refresh will reconcile")
            return
        }
        circleDao.insert(mapCircleResponse(dto))
        Log.i(TAG, "adoptPeerRotation: $circleId now at key v${dto.keyVersion}")
        try {
            cryptoManager.invalidateSessionKeysForCircle(circleId)
            ensureSessionKey(circleId)
        } catch (e: Exception) {
            Log.w(TAG, "adoptPeerRotation: Failed to refresh session key for $circleId", e)
        }
    }

    // ===== Session Keys =====

    /**
     * Submit every pseudonym the current user has derived for themself in
     * this circle (one per known circle-key version) to the server. The server
     * records them so the /locations history endpoint resolves rows tagged
     * with pre-rotation pseudonyms.
     *
     * Idempotent: the server dedups on (circle_id, user_pseudonym).
     *
     * Sent only when the set changed. What we would send is a pure function of the
     * keys we hold, so repeating the last set the server ACCEPTED asks a question
     * it has already answered, at one POST per circle every 5 minutes. A rotation
     * or a newly imported chain link adds a pseudonym, changes the digest, and
     * sends again. [force] sends regardless.
     *
     * Security: we only submit pseudonyms for our OWN user_id; the server
     * links them to the caller's user_id from the auth token, not the body.
     */
    private suspend fun backfillPseudonyms(circleId: String, force: Boolean = false) {
        val userId = sessionManager.getUserId() ?: return
        // SELF circle uses a different endpoint namespace and doesn't
        // participate in circle-level pseudonym history; nor does anything named by its slot.
        if (com.pingolin.track.util.AccountIds.isPersonalSlot(circleId)) return

        val pseudonyms = try {
            cryptoManager.deriveUserPseudonymsAllVersions(circleId, userId)
        } catch (e: Exception) {
            Log.d(TAG, "backfillPseudonyms: skip $circleId — no key available: ${e.message}")
            return
        }
        if (pseudonyms.isEmpty()) return

        val digest = pseudonymSetDigest(userId, pseudonyms)
        if (!force && backfillLedger.digestFor(circleId) == digest) return

        try {
            val response = tracksApi.backfillPseudonyms(
                circleId,
                com.pingolin.track.data.remote.dto.PseudonymBackfillRequest(pseudonyms = pseudonyms)
            )
            if (response.isSuccessful && response.body()?.success == true) {
                // Recorded only once the server has the set, so a failed POST is
                // retried on the next refresh rather than forgotten.
                backfillLedger.record(circleId, digest)
                val data = response.body()?.data
                if (data != null && (data.inserted > 0)) {
                    Log.i(TAG, "backfillPseudonyms($circleId): +${data.inserted} new, ${data.skipped} already on file")
                }
            } else {
                Log.w(TAG, "backfillPseudonyms($circleId) failed: ${response.code()} ${response.body()?.error}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "backfillPseudonyms($circleId) threw: ${e.message}")
        }
    }

    suspend fun prefetchSessionKeys(circleId: String) {
        try {
            val response = tracksApi.getActiveSessionKeys(circleId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val sessionKeys = response.body()!!.data!!
                var importedCount = 0

                for (key in sessionKeys) {
                    try {
                        val expiresAtSecs = try {
                            Instant.parse(key.expiresAt).epochSecond
                        } catch (e: Exception) {
                            // Default to 6 hours from now if parsing fails
                            System.currentTimeMillis() / 1000 + 21600
                        }

                        cryptoManager.importPeerSessionKey(
                            circleId,
                            key.sessionKeyId,
                            key.encryptedSessionKey,
                            key.keyNonce,
                            key.keyVersion,
                            expiresAtSecs
                        )
                        importedCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to import session key ${key.sessionKeyId} for circle $circleId: ${e.message}")
                    }
                }

                Log.d(TAG, "Prefetched $importedCount/${sessionKeys.size} session keys for circle $circleId")
            } else {
                Log.d(TAG, "No active session keys for circle $circleId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prefetch session keys for circle $circleId", e)
        }
    }

    /**
     * Ensure a valid session key exists for [circleId], creating and publishing one
     * if not. Never throws: a failure invalidates the local key so encryption falls
     * back to the circle key.
     */
    suspend fun ensureSessionKey(circleId: String) {
        try {
            if (cryptoManager.hasActiveSessionKey(circleId)) {
                return
            }

            // Create a new session key (6 hours = 21600 seconds)
            val sessionKeyData = cryptoManager.createSessionKey(circleId, 21600L)

            val deviceId = sessionManager.getDeviceId()
            val request = PublishSessionKeyRequest(
                sessionKeyId = sessionKeyData.sessionKeyId,
                deviceId = deviceId,
                encryptedSessionKey = sessionKeyData.encryptedSessionKey,
                keyNonce = sessionKeyData.keyNonce,
                keyVersion = sessionKeyData.keyVersion,
                expiresAt = sessionKeyData.expiresAt
            )

            val response = tracksApi.publishSessionKey(circleId, request)
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "Published session key ${sessionKeyData.sessionKeyId} for circle $circleId")
            } else {
                Log.w(TAG, "Failed to publish session key for circle $circleId: ${response.body()?.error}")
                // Invalidate the locally-created session key so encryption falls back to circle key
                cryptoManager.invalidateSessionKeysForCircle(circleId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure session key for circle $circleId: ${e.message}")
            // Invalidate on any failure so we don't encrypt with an unpublished key
            try {
                cryptoManager.invalidateSessionKeysForCircle(circleId)
            } catch (invalidateEx: Exception) {
                Log.w(TAG, "Failed to invalidate session key for circle $circleId: ${invalidateEx.message}")
            }
        }
    }

    // ===== TOFU pin alerts (member key change) =====

    /**
     * Add a [MemberKeyAlert] to the queue, deduping on (circleId, userId) — unless this
     * user answered "Not now" about them within the last day: a server can mint a new
     * "change" after every answer, and the question must not become a siege.
     */
    private fun enqueueMemberKeyAlert(alert: MemberKeyAlert) {
        val asked = keyAccessApprovals.lastAsked(alert.circleId, alert.userId)
        if (asked != null && System.currentTimeMillis() - asked < MEMBER_QUESTION_INTERVAL_MS) return
        _memberKeyAlerts.value = _memberKeyAlerts.value
            .filterNot { it.circleId == alert.circleId && it.userId == alert.userId } + alert
        // Taken to the person. A member whose app is too old never gets here: there is
        // nothing to answer, and the server tells them to update.
        questionNotifier.memberReset(alert.circleId, alert.userId, alert.displayName.takeUnless { it == UNNAMED_MEMBER })
    }

    /** Drop the head of the queue once the dialog has been dismissed (either
     *  by approve or block). The next dialog pop is driven by the UI. */
    fun dismissMemberKeyAlert(circleId: String, userId: String) {
        _memberKeyAlerts.value = _memberKeyAlerts.value
            .filterNot { it.circleId == circleId && it.userId == userId }
        questionNotifier.answered(circleId, userId)
    }

    /**
     * SECURITY: drop the process-lifetime member-key state this @Singleton holds
     * so it can't leak across a same-process account switch. The pinned-key
     * caches carry decrypted member pubkeys and the alert queue may hold a prior
     * user's pending TOFU dialogs; clearAllKeys()/clearAllTables() on logout don't
     * touch these in-memory fields. Called from AuthRepository.clearLocalData and
     * the force-logout callback. Resets to the fresh-process empty state.
     */
    fun wipeInMemory() {
        signingPubkeyCache.clear()
        sharingPubkeyCache.clear()
        canRotateKeysCache.clear()
        lockedCircleOutcomes.clear()
        readableCircleIds.clear()
        _keyRotationCauses.value = emptyMap()
        currentKeyGate.clear()
        _keyAccessRequests.value = emptyList()
        questionNotifier.clear()
        keyAccessDeferred.clear()
        trustSnapshots.clear()
        _memberStates.value = emptyMap()
        accessRequestedAt.clear()
        firstKeySenders.clear()
        sealedNames.clear()
        heldPinSeedChecked = false
        deliveredAt.clear()
        deliveryPasses.clear()
        receivedInvites.clear()
        signedOwnWraps.clear()
        skippedRotations.clear()
        // The next account has had no records read here yet: nothing is offered on the server's word.
        recordsNoneServed.clear()
        // This account's own admission and eligibility flag for its own row: the next account starts unknown.
        ownRowEligible.clear()
        selfAdmissionsDue.clear()
        selfAdmissionMarks.clear()
        selfAdmissionTriedAt.clear()
        // Its once-per-session cache would otherwise skip the next account's
        // previous identities on this process, and answer for its reset.
        previousIdentityLoader.reset()
        _memberKeyAlerts.value = emptyList()
        _inviteRenewalQuestions.value = emptyList()
        renewalLooks.clear()
        ownInvitesLooks.clear()
        inviteStatementIds.clear()
        updateRequests.clear()
        keyReimportRequested.set(false)
        lastConditionalKeyImportAtMs = 0L
        // The one persisted piece of the refresh economy: it names this user's
        // circles, so it goes with the rest of their state.
        runCatching { backfillLedger.clear() }
    }

    /**
     * "It's them": THIS device accepts the member's new keys — its pin, or an email invitee's
     * recorded keys — and, as this user's own choice, hands them the circle. Nothing is signed
     * and nothing reaches other devices: each asks its own user. Except on an owner's or
     * admin's device for a member the chain doesn't count: that "It's them" also lets them in
     * ([letInAfterKeyChange]).
     */
    suspend fun approveMemberKeyChange(alert: MemberKeyAlert) {
        seedFromHeldPinsOnce()
        val approved = try {
            if (alert.receiveOnly) {
                keyAccessApprovals.recordReceiveOnlyKeys(alert.circleId, alert.userId, alert.newSharingHex, alert.newSigningHex)
                keyAccessApprovals.approve(alert.circleId, listOf(alert.userId), com.pingolin.track.util.KeyAccessApprovals.Origin.OWN_KIND2)
                personConfirmedKeys.confirm(alert.circleId, alert.userId, alert.newSharingHex, alert.newSigningHex)
            } else {
                cryptoManager.approveMemberKeyChange(
                    alert.circleId,
                    alert.userId,
                    alert.newSharingHex,
                    alert.newSigningHex,
                )
                keyAccessApprovals.approve(alert.circleId, listOf(alert.userId), com.pingolin.track.util.KeyAccessApprovals.Origin.OWN)
                // The keys this device's person confirmed.
                personConfirmedKeys.confirm(alert.circleId, alert.userId, alert.newSharingHex, alert.newSigningHex)
            }
            // The pin is now overwritten with the NEW keys, so any prior "verified
            // out of band" marker no longer applies — drop it so the SAS badge
            // can't keep vouching for a key the user never actually compared.
            sessionManager.getUserId()?.let { owner ->
                MemberVerificationStore.clearVerified(context, owner, alert.circleId, alert.userId)
            }
            Log.i(TAG, "Approved member key change for ${alert.circleId}/${alert.userId}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "approveMemberKeyChange failed for ${alert.circleId}/${alert.userId}: ${e.message}")
            false
        }
        dismissMemberKeyAlert(alert.circleId, alert.userId)
        // Their app asked for a fresh copy (it cannot open its old one): hand it over.
        if (approved) {
            runCatching { deliverKeyToApprovedMembers(alert.circleId) }
                .onFailure { Log.w(TAG, "approveMemberKeyChange: delivery for ${alert.circleId} failed: ${it.message}") }
            // An email invitee's "It's them" admits nobody: their own question does (EMAIL_WITHOUT_ID).
            if (!alert.receiveOnly) letInAfterKeyChange(alert)
        }
    }

    /**
     * "It's them" on an owner's or admin's device, about a member the chain doesn't count, lets them in as well.
     * The records' eligibility check takes a member the chain never named only on a statement binding the keys
     * the member list shows, and the one they joined with binds their keys from before their reset: an admission
     * of exactly the keys the person confirmed, signed with this account's current key, goes out as a waiting
     * "Let them in" does (only while this phone holds the key in use, else it waits; into their row only as
     * served). Never for a member the chain counts (the check takes them on the person's word), never from a
     * plain member's device, never without this yes: the backfill still skips them. And only for the stuck
     * state: the check, read with the keys the person confirmed, doesn't let them in and raises no flag, and no
     * owner or admin removed them; a flag's own question decides those, and a check this device can't read (no
     * import in this process, forked, an error) decides nothing.
     */
    private suspend fun letInAfterKeyChange(alert: MemberKeyAlert) {
        val circleId = alert.circleId
        if (afterKeyChange(circleId, alert.userId, alert.newSharingHex to alert.newSigningHex) != LetInNow.WAITING) return
        val circle = circleDao.getById(circleId) ?: return
        val inputs = com.pingolin.track.domain.pending.PendingInputs.LetIn(
            circle.name, alert.userId, alert.displayName, KeyAccessRequest.Reason.KEY_CHANGE.name,
            alert.newSharingHex, alert.newSigningHex, tapVersion = heldVersion(circleId),
        )
        val kept = pendingChanges.queue<Unit>(circleId, inputs).exceptionOrNull()
        if (kept !is com.pingolin.track.domain.pending.QueuedForSync) {
            Log.w(TAG, "$circleId: letting ${alert.userId} in after It's them wasn't kept: ${kept?.javaClass?.simpleName}")
        }
    }

    /**
     * The re-check before a waiting "Let them in" from "It's them" signs: the stuck state, read now with the
     * keys the person confirmed ([keys]). Let in meanwhile (the records allow them, or the chain counts them
     * now): [LetInNow.LET_IN]; still stuck: [LetInNow.WAITING]; a flag, a removal, or a check this device
     * can't read: [LetInNow.NOT_ASKED]. Null when the member list can't be had.
     */
    suspend fun keyChangeLetInNow(circleId: String, userId: String, keys: Pair<String, String>): LetInNow? {
        refreshMembersAndJudge(circleId, deliver = false).getOrNull() ?: return null
        return afterKeyChange(circleId, userId, keys)
    }

    /**
     * Where "It's them" for [userId]'s [keys] leaves them on this device ([letInAfterKeyChange]):
     * [LetInNow.WAITING] only in the stuck state, an owner or admin here (chain role), the chain not
     * counting them, and the SDK's eligibility check, read with [keys] on records imported in this
     * process and not forked, neither letting them in nor raising a flag or a removal; [LetInNow.LET_IN]
     * when the chain counts them or the check lets them in; [LetInNow.NOT_ASKED] otherwise, an error
     * included.
     */
    private suspend fun afterKeyChange(circleId: String, userId: String, keys: Pair<String, String>): LetInNow {
        val self = sessionManager.getUserId() ?: return LetInNow.NOT_ASKED
        if (!runCatching { cryptoManager.circleHasRefreshRecords(circleId) }.getOrDefault(false)) return LetInNow.NOT_ASKED
        val selfRole = runCatching { cryptoManager.chainRole(circleId, self) }.getOrNull()
        if (selfRole != uniffi.pingolin_mobile.CircleRole.OWNER && selfRole != uniffi.pingolin_mobile.CircleRole.ADMIN) return LetInNow.NOT_ASKED
        // Not known (the chain unreadable) is never "not counted".
        val counted = runCatching { cryptoManager.chainRole(circleId, userId) }.getOrElse { return LetInNow.NOT_ASKED } != null
        if (counted) return LetInNow.LET_IN
        if (recordsJudged[circleId]?.report?.forked != false) return LetInNow.NOT_ASKED
        val snapshot = trustSnapshots[circleId] ?: return LetInNow.NOT_ASKED
        val members = RefreshRecords.recordMembers(snapshot) { id -> confirmedKeys(circleId, id, snapshot) }.map { m ->
            if (!com.pingolin.track.util.AccountIds.same(m.userId, userId)) m
            else m.copy(sharingPublicKeyHex = keys.first, signingPublicKeyHex = keys.second, confirmed = true)
        }
        val e = runCatching {
            cryptoManager.refreshEligibility(circleId, members, uniffi.pingolin_mobile.EligibilityPurpose.INCLUDE)
        }.getOrNull()?.firstOrNull { com.pingolin.track.util.AccountIds.same(it.userId, userId) } ?: return LetInNow.NOT_ASKED
        if (e.eligible) return LetInNow.LET_IN
        if (e.invitationExpired || e.invitationSignerNotChainAdmin || e.emailInvitationWithoutSignedId || e.removedByOwnerOrAdmin) return LetInNow.NOT_ASKED
        return LetInNow.WAITING
    }

    /** "Not now" on "Is this really {name}?": asked again tomorrow at the earliest. */
    fun deferMemberKeyChange(alert: MemberKeyAlert) {
        keyAccessApprovals.markAsked(alert.circleId, alert.userId)
        dismissMemberKeyAlert(alert.circleId, alert.userId)
    }

    /**
     * SEND-SIDE TOFU gate run BEFORE a circle-key rotation/eject wraps the
     * fresh key to each member's SERVER-SUPPLIED sharing key. Without it, a
     * compromised/blind server that substitutes a member's sharing key in
     * `getMemberPublicKeys` would get the new circle key wrapped to an
     * attacker-held key and could decrypt every post-rotation location for the
     * circle. Mirrors the receive-side pin check [refreshMembers] already runs
     * ([CryptoManager.pinMemberKeys]).
     *
     * Returns the set of BLOCKED user_ids. They get no wrap of the new key: the
     * caller lists them as `pending_members` and the rotation goes ahead for
     * everyone else (aborting instead would let one unapproved key change keep a
     * removed member on the live key). A Changed* pin surfaces the existing
     * member-key-change alarm; approving it delivers the key
     * ([deliverKeyToApprovedMembers]).
     *
     * A member with only a sharing key (signing key not migrated on the server)
     * is allowed as genuine first-contact TOFU — UNLESS we already pinned that
     * member WITH a signing key, in which case a now-signing-less response is a
     * downgrade: we compare the server sharing key against the existing pin (SDK
     * [CryptoManager.pinnedSharingKey]) and block on mismatch, so a server can't
     * strip signing to dodge the pin.
     */
    private suspend fun gateMembersForRotation(
        circleId: String,
        memberKeys: List<MemberPublicKeyResponse>,
        trust: MemberConfirmation.Result,
    ): Set<String> {
        val currentUserId = sessionManager.getUserId()
        val blocked = mutableSetOf<String>()
        val alerts = mutableListOf<MemberKeyAlert>()
        val receiveOnlyKeys = keyAccessApprovals.receiveOnlyKeys(circleId)
        for (dto in memberKeys) {
            if (dto.userId == currentUserId) continue // never gate self
            if (trust.receiveOnly(dto.userId)) {
                // An email invitee is never pinned: their recorded keys stand in.
                val recorded = receiveOnlyKeys[dto.userId]
                if (recorded == null || !recorded.first.equals(dto.sharingPublicKey, true) ||
                    !recorded.second.equals(dto.signingPublicKey, true)
                ) {
                    blocked.add(dto.userId)
                    Log.w(TAG, "rotation gate: $circleId/${dto.userId} (email invitee) shows keys this device has not accepted — blocking")
                }
                continue
            }
            val sharingHex = dto.sharingPublicKey
            if (sharingHex.length != 64) {
                Log.w(TAG, "rotation gate: member ${dto.userId} has no usable sharing key — blocking")
                blocked.add(dto.userId)
                continue
            }
            val signingHex = dto.signingPublicKey
            if (signingHex == null || signingHex.length != 64) {
                // Signing key not migrated yet: first-contact TOFU allowed.
                // BUT if we ALREADY pinned this member WITH a signing key, a now
                // signing-less response is a downgrade — a malicious server must not
                // be able to strip signing + substitute sharing to dodge the pin.
                // Compare the server sharing key against the existing pin and block
                // on mismatch; genuine first-contact (no pin) stays TOFU-allowed.
                val pinnedSharing = cryptoManager.pinnedSharingKey(circleId, dto.userId)
                if (pinnedSharing != null && !pinnedSharing.equals(sharingHex, ignoreCase = true)) {
                    val displayName = circleMemberDao.get(circleId, dto.userId)?.displayName ?: "Member"
                    alerts.add(
                        MemberKeyAlert(
                            circleId = circleId,
                            userId = dto.userId,
                            displayName = displayName,
                            state = PinState.CHANGED_SHARING,
                            // No SDK getter exposes a sharing-only pin's timestamp
                            // (pinnedSharingKey returns just the key; pinMemberKeys/
                            // PinResult.previousPinnedAt needs a signing key we don't
                            // have here), so leave 0L. The signing-strip alert doesn't
                            // offer Approve, so "last verified" isn't shown for it.
                            previousPinnedAt = 0L,
                            newSharingHex = sharingHex,
                            // Empty: the server stripped the signing key. Marks this
                            // as the non-approvable downgrade case (see below).
                            newSigningHex = signingHex.orEmpty(),
                            signingUnavailable = true,
                        )
                    )
                    blocked.add(dto.userId)
                    Log.w(TAG, "rotation gate: signing-strip downgrade for $circleId/${dto.userId} — pinned sharing key mismatch, blocking rotation")
                }
                continue
            }
            try {
                val pin = cryptoManager.pinMemberKeys(circleId, dto.userId, sharingHex, signingHex)
                when (pin.state) {
                    PinState.NEW_PINNING, PinState.MATCHED -> { /* safe to wrap */ }
                    PinState.CHANGED_SHARING, PinState.CHANGED_SIGNING, PinState.CHANGED_BOTH -> {
                        val displayName = circleMemberDao.get(circleId, dto.userId)?.displayName ?: "Member"
                        alerts.add(
                            MemberKeyAlert(
                                circleId = circleId,
                                userId = dto.userId,
                                displayName = displayName,
                                state = pin.state,
                                previousPinnedAt = pin.previousPinnedAt,
                                newSharingHex = sharingHex,
                                newSigningHex = signingHex,
                                number = computeSafetyNumber(dto.userId, sharingHex, signingHex),
                            )
                        )
                        blocked.add(dto.userId)
                        Log.w(TAG, "rotation gate: pin divergence for $circleId/${dto.userId} state=${pin.state} — blocking rotation")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "rotation gate: pin check failed for ${dto.userId}: ${e.message} — blocking")
                blocked.add(dto.userId)
            }
        }
        for (alert in alerts) {
            // Their app is too old to confirm: nothing anyone here can decide. The server
            // tells them to update and they stay out of the rotation meanwhile.
            if (alert.signingUnavailable) requestMemberUpdate(alert.circleId, alert.userId)
            else enqueueMemberKeyAlert(alert)
        }
        return blocked
    }

    /**
     * Ask the server to tell [userId] that their app is too old for [circleId]: a push and an
     * email to THEM, since an old app cannot show text it does not know. True when the server
     * has asked them (now or within the last day). At most once a day per member from this
     * device; the server keeps its own day as well.
     */
    internal suspend fun requestMemberUpdate(circleId: String, userId: String): Boolean {
        val key = "$circleId:$userId"
        val now = System.currentTimeMillis()
        // A refusal (e.g. 409 member_up_to_date while the list lags) is not re-sent on every
        // refresh either: again after the access-request backoff.
        updateRequests[key]?.let { (at, asked) ->
            if (now - at < if (asked) UPDATE_REQUEST_INTERVAL_MS else ACCESS_REQUEST_BACKOFF_MS) return asked
        }
        val asked = try {
            val response = tracksApi.requestMemberUpdate(circleId, userId)
            (response.isSuccessful && response.body()?.data?.requested == true).also { taken ->
                if (!taken) Log.w(TAG, "Update request for a member of $circleId not taken: HTTP ${response.code()} ${errorCodeOf(response)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update request for a member of $circleId not sent: ${e.message}")
            false
        }
        updateRequests[key] = now to asked
        return asked
    }

    // ===== Safety number =====

    /** Compute a 60-digit safety number for SAS verification. Caller-provided
     *  peer keys must be hex-encoded. Returns the SAS string formatted by the
     *  SDK (12 groups of 5 separated by single ASCII spaces). */
    fun computeSafetyNumber(
        peerUserId: String,
        peerSharingHex: String,
        peerSigningHex: String,
    ): String? {
        val selfUserId = sessionManager.getUserId() ?: return null
        return try {
            cryptoManager.computeSafetyNumber(selfUserId, peerUserId, peerSharingHex, peerSigningHex)
        } catch (e: Exception) {
            Log.w(TAG, "computeSafetyNumber failed: ${e.message}")
            null
        }
    }

    /** Latest cached signing pubkey (hex) for a member. May be null if the
     *  server hasn't migrated the member or we haven't refreshed members yet. */
    fun cachedMemberSigningPubkey(circleId: String, userId: String): String? =
        signingPubkeyCache[cacheKey(circleId, userId)]

    /** Latest cached sharing (X25519) pubkey (hex) for a member. */
    fun cachedMemberSharingPubkey(circleId: String, userId: String): String? =
        sharingPubkeyCache[cacheKey(circleId, userId)]

    /** Pauses before each further try of a read that failed for a reason that passes; tests shorten them. */
    internal var readRetryPausesMs: List<Long> = listOf(2_000L, 5_000L)

    /**
     * [read] again after a moment while [passes] says its failure is one that passes — no
     * network, a 5xx, a 429 — instead of asking the person to try again. For reads only:
     * nothing is sent twice.
     */
    private suspend fun <T> readAgainIfItPasses(passes: (T) -> Boolean, read: suspend () -> T): T {
        var result = read()
        for (pause in readRetryPausesMs) {
            if (!passes(result)) return result
            kotlinx.coroutines.delay(pause)
            result = read()
        }
        return result
    }

    // ===== Admin eject + atomic rotation =====

    /**
     * Atomically eject [targetUserId] from [circleId] and rotate the circle key for
     * the surviving members: fetch member public keys, drop the ejected user from the
     * wrap set, generate a fresh key wrapped for the survivors, and POST the whole
     * bundle to `eject-and-rotate`.
     *
     * Doing it in one server-side transaction is the point — a separate delete and
     * rotate leaves a window in which the ejected member is gone from the roster but
     * still holds a live circle key.
     */
    suspend fun ejectMember(circleId: String, targetUserId: String): Result<Unit> {
        if (personalSlot(circleId, "eject")) return Result.failure(Exception("Couldn't remove them just now."))
        val change = KeyChange()
        return try {
            // Behind on the key: a removal re-keys, and this device cannot until it has caught
            // up. Its current version is enough: a re-key it owes is what this does. An owner's
            // or admin's eject may instead go from below a version whose key never came here
            // ([ejectsFromBelow]).
            var fromBelow = false
            if (!currentKeyGate.holdsCurrentVersion(circleId)) {
                catchUp(circleId)
                if (!currentKeyGate.holdsCurrentVersion(circleId)) {
                    fromBelow = ejectsFromBelow(circleId)
                    if (!fromBelow) return Result.failure(KeyNotCurrentException())
                }
            }
            // Nothing is built before this account's own admission of its current keys is served.
            if (circleId in selfAdmissionsDue) return Result.failure(KeyNotCurrentException())
            // Ownership claims: a record's "removed" needs this account to be the chain's
            // owner or an admin. An admin on the server's list only (a claimant's
            // server-only promotion, say) removes without a new key instead.
            if (lacksChainRightToRemove(circleId)) return Result.failure(RemovesWithoutNewKey())
            // Read again while it fails for a reason that passes, rather than asking the
            // person to try again.
            val pubKeysResponse = readAgainIfItPasses(
                passes = { r -> r.isFailure && r.exceptionOrNull() is java.io.IOException || r.getOrNull()?.let { it.code() >= 500 || it.code() == 429 } == true },
            ) { runCatching { tracksApi.getMemberPublicKeys(circleId) } }.getOrThrow()
            if (!pubKeysResponse.isSuccessful || pubKeysResponse.body()?.data == null) {
                Log.w(TAG, "member list for $circleId not read: HTTP ${pubKeysResponse.code()}")
                return Result.failure(Exception(ApiErrors.plainLine(pubKeysResponse.code(), null, "Couldn't load the member list just now.")))
            }
            val all = pubKeysResponse.body()!!.data!!
            val survivors = all.filter { it.userId != targetUserId }
            if (survivors.isEmpty()) {
                return Result.failure(Exception("Cannot eject the only member"))
            }

            val circle = circleDao.getById(circleId)
                ?: return Result.failure(Exception("Circle $circleId not found locally"))
            // The new key carries the name only as [sealableName] gives it, else none, and the
            // circle keeps its own: the name never stops an eject. From below it is always none:
            // this device then seals under a version below the circle's, which is not the version
            // the change replaces (the key in use there is the removed member's). Behind on the
            // key, the eject has stopped above (holdsCurrentVersion), unless from below.
            val sealable = sealableName(circle)
            val circleName = sealable.orEmpty()

            val senderUserId = sessionManager.getUserId().orEmpty()

            // The same plan a rotation makes: survivors with an unapproved key change,
            // or never given access by this user, are left pending instead of stopping
            // the removal — letting one of them block the eject would leave the removed
            // member on the live key. Accounts the member list does not show are never
            // wrapped to. A removal always re-keys.
            val plan = readAgainIfItPasses(passes = { it == null }) {
                planRotationFor(
                    circle,
                    survivors,
                    forDelivery = false,
                    cause = RotationPlanner.CAUSE_MEMBER_REMOVED,
                    leaving = targetUserId,
                )
            } ?: return Result.failure(Exception("Couldn't check this circle's member list just now."))

            // On a circle with records the eject's record names them removed; an owner's or
            // admin's eject of a record-less circle writes its first record, without them.
            val canRotate = canRotateKeysCache[circleId] ?: circle.isOwner
            val send = try {
                buildOrImportOnce { recordedRotationFor(circle, plan, canRotate, senderUserId, circleName, removing = targetUserId, change = change) }
            } catch (e: RecordsNotImportedYet) {
                notImportedYet(circleId, "ejectMember")
                return Result.failure(KeyNotCurrentException())
            }
            val bundle = send?.bundle ?: cryptoManager.prepareKeyRotation(
                circleId,
                senderUserId,
                plan.wrap.map { it.userId },
                plan.wrap.map { it.sharingPublicKey },
                plan.wrap.map { it.signingPublicKey.orEmpty() },
                circleName,
            ).also { change.minted = it.newKeyVersion }
            val pending = send?.pending ?: plan.pending

            val wraps = bundle.memberKeys.map { entry ->
                com.pingolin.track.data.remote.dto.EjectMemberWrap(
                    userId = entry.userId,
                    encryptedCircleKey = entry.bundle.encryptedCircleKey.ciphertext,
                    circleKeyNonce = entry.bundle.encryptedCircleKey.nonce,
                    ephemeralPublicKey = entry.bundle.ephemeralPublicKey,
                    wrapSignature = entry.bundle.wrapSignature.takeIf { it.isNotEmpty() }
                )
            }

            // Carry the two things that keep pre-rotation data readable: an eject
            // IS a rotation. Without the re-encrypted name and the chain link,
            // anyone holding only the new key (another client, a fresh install, a
            // later joiner) could never read the circle name or historical data
            // again, while the phone that did the ejecting would look fine
            // because it still holds the old keys locally.
            val keyChainLink = bundle.keyChainLink?.let { link ->
                mapOf(
                    "from_version" to link.fromVersion,
                    "to_version" to link.toVersion,
                    "encrypted_key" to link.encryptedKey,
                    "nonce" to link.nonce,
                )
            }
            if (keyChainLink == null) {
                Log.w(TAG, "ejectMember: no key chain link for $circleId — pre-rotation data will not be readable by key-only holders")
            }

            val response = tracksApi.ejectAndRotate(
                circleId,
                com.pingolin.track.data.remote.dto.EjectAndRotateRequest(
                    ejectUserId = targetUserId,
                    newKeyVersion = bundle.newKeyVersion,
                    memberWraps = wraps,
                    encryptedName = com.pingolin.track.data.remote.dto.EncryptedPayload(
                        nonce = bundle.encryptedName.nonce,
                        ciphertext = bundle.encryptedName.ciphertext,
                    ).takeIf { sealable != null },
                    keyChainLink = keyChainLink,
                    pendingMembers = pending.takeIf { it.isNotEmpty() },
                    refreshRecord = send?.record?.let { RefreshRecordPayload(it.recordHex, it.signatureHex) },
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()?.data
                // `new_key_version` is the server's name for it. Fall back to the version
                // we submitted (the server accepted it, so it is what was stored) rather
                // than to zero: a 0 written into Room would become the next rotation's
                // expected_key_version. The rotation flag is cleared in the same UPDATE
                // that bumps the version server-side, so it is set, not read.
                val newVersion = data?.newKeyVersion ?: bundle.newKeyVersion
                circleDao.insert(circle.copy(
                    keyVersion = newVersion,
                    keyRotationNeeded = false,
                ))
                bundle.keyChainLink?.let { noteSentLink(circleId, it) }
                circleMemberDao.get(circleId, targetUserId)?.let { circleMemberDao.delete(it) }
                // Removed by this user: a server that kept listing them would get them
                // asked about, not wrapped to.
                keyAccessApprovals.revoke(circleId, listOf(targetUserId))
                currentKeyGate.noteCurrent(circleId)
                // Writes that waited on this device's re-key go now.
                pendingChanges.kick()
                // Its record named this user's removals without a key change, and wrote the
                // role changes it could.
                if (send?.record != null) ownRecordTaken[circleId] = bundle.newKeyVersion
                send?.settles?.takeIf { it.isNotEmpty() }?.let { ownRemovals.settled(circleId, it) }
                send?.rolesSettle?.takeIf { it.isNotEmpty() }?.let { ownerRoleChanges.settled(circleId, it) }
                Log.i(
                    TAG,
                    "ejectMember: ejected $targetUserId from $circleId, key v$newVersion" +
                        if (pending.isNotEmpty()) " (${pending.size} survivor(s) left pending)" else ""
                )
                Result.success(Unit)
            } else {
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.w(TAG, "ejectMember failed for $circleId/$targetUserId: ${response.code()} ${response.body()?.error ?: errorBody}")
                discardOwnRotation(circleId)
                val refused = ApiErrors.parse(errorBody)?.errorCode
                // Records this device hasn't read, or read too late: read them now, for the next try.
                if (refused == "key_refresh_record_stale" || refused == "key_refresh_record_required") syncRefreshRecords()
                Result.failure(Exception(ApiErrors.plainLine(response.code(), refused, "Couldn't remove them just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ejectMember exception for $circleId/$targetUserId", e)
            discardMinted(circleId, change)
            // Its removals or role changes unreadable here, it waits: nothing was sent.
            Result.failure(if (e is com.pingolin.track.util.StoreUnreadable) Exception("Couldn't remove them just now.") else e)
        }
    }
}

/**
 * The last pseudonym set the server accepted from this device, per circle.
 * See [CircleRepository.backfillPseudonyms].
 */
internal interface PseudonymBackfillLedger {
    fun digestFor(circleId: String): String?
    fun record(circleId: String, digest: String)
    fun clear()
}

/**
 * [PseudonymBackfillLedger] in its own encrypted preferences file, opened on first
 * use. Every failure reads as "not recorded", which costs at most one redundant,
 * idempotent POST.
 */
private class SecurePrefsBackfillLedger(
    private val context: android.content.Context,
) : PseudonymBackfillLedger {
    private val prefs by lazy { com.pingolin.track.util.SecurePrefs.open(context, FILE_NAME) }

    override fun digestFor(circleId: String): String? =
        runCatching { prefs.getString(circleId, null) }.getOrNull()

    override fun record(circleId: String, digest: String) {
        runCatching { prefs.edit().putString(circleId, digest).apply() }
    }

    override fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    private companion object {
        const val FILE_NAME = "pseudonym_backfill_ledger"
    }
}
