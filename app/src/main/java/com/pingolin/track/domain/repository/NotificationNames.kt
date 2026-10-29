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
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who and where a notification row is about, from this device's own data only.
 *
 * The rules ([NamedNotificationCopy] has the words):
 * - A name is only ever one this device OPENED with the circle's key
 *   ([com.pingolin.track.data.local.entity.CircleMemberEntity.nameOpened]), never the
 *   server's words — the same rule an alarm's raiser is named by. The server supplies ids at
 *   most.
 * - Only members this device shows: the stored roster holds exactly those
 *   ([CircleRepository.refreshMembers]). A miss refetches the roster once per circle per
 *   [Pass] ([CircleRepository.refreshRoster]: no hand-over on these paths); still a miss —
 *   someone who left, or isn't confirmed — stays "A member".
 * - A place this device doesn't hold stays "a place", with no fetch.
 * - Who asked to be checked on is named only by the receivers' rules
 *   ([SosRepository.raiserToName]), never from the stored alert.
 */
@Singleton
class NotificationNames @Inject constructor(
    private val circleMemberDao: CircleMemberDao,
    private val circleDao: CircleDao,
    private val placeDao: PlaceDao,
    private val tracksApi: TrackApi,
    private val cryptoManager: uniffi.pingolin_mobile.CryptoManager,
    private val sessionManager: SessionManager,
    private val circleRepository: dagger.Lazy<CircleRepository>,
    private val sosRepository: dagger.Lazy<SosRepository>,
) {
    /** One batch of rows: each circle's roster is refetched, and each SOS looked up, at most once in it. */
    class Pass {
        internal val refetched = HashSet<String>()
        internal val raisers = HashMap<String, String?>()
    }

    /** What [type]'s row is about, named from [metadata] (the row's own, stringified). */
    suspend fun namesFor(
        type: String,
        circleId: String?,
        metadata: Map<String, String>,
        pass: Pass,
    ): NamedNotificationCopy.Names = when (type) {
        "place_alert" -> NamedNotificationCopy.Names(
            person = member(circleId, metadata["target_user_id"], pass),
            place = place(metadata["place_id"]),
        )
        "check_in" -> NamedNotificationCopy.Names(
            person = member(circleId, sealedUserId(circleId, metadata["encrypted_user_id"], metadata["user_id_nonce"]), pass),
            place = place(metadata["place_id"]),
        )
        "sos_alert" -> raiserNames(circleId, metadata, pass)
        // Who CLOSED the alert, when the row says (`closed_by`); an older row without it still says
        // whose request it was.
        "sos_resolved" -> {
            val closedBy = metadata["closed_by"]?.takeIf { it.isNotBlank() }
            when {
                closedBy == null -> raiserNames(circleId, metadata, pass)
                isMe(closedBy) -> NamedNotificationCopy.Names(personIsMe = true, closer = true)
                else -> NamedNotificationCopy.Names(person = member(circleId, closedBy, pass), closer = true)
            }
        }
        // Who answered my request (`acknowledged_by`). A row without it keeps the server's words.
        "sos_acknowledged" -> NamedNotificationCopy.Names(person = member(circleId, metadata["acknowledged_by"], pass))
        "member_joined" -> NamedNotificationCopy.Names(
            person = member(circleId, metadata["joined_user_id"], pass),
            circle = circle(circleId),
        )
        "circle_ownership_transferred" -> {
            val newOwner = metadata["new_owner_id"]
            if (isMe(newOwner)) NamedNotificationCopy.Names(personIsMe = true)
            else NamedNotificationCopy.Names(person = member(circleId, newOwner, pass))
        }
        "circle_ownership_claim" -> NamedNotificationCopy.Names(
            person = member(circleId, claimant(circleId, metadata["claim_id"]), pass),
            // Which circle: an owner of several can't tell from "your circle".
            circle = circle(circleId),
        )
        // This account's own pause ran out there: the circle, by the name this device keeps for it.
        "privacy_resumed" -> NamedNotificationCopy.Names(circle = circle(circleId))
        // This account's request to take over a circle was refused: which circle.
        "circle_ownership_refused" -> NamedNotificationCopy.Names(circle = circle(circleId))
        else -> NamedNotificationCopy.Names()
    }

    /**
     * [userId]'s name in [circleId]: an opened name of a member this device shows, refetching
     * the roster once per circle in [pass] on a miss; else null.
     */
    suspend fun member(circleId: String?, userId: String?, pass: Pass): String? {
        val circle = circleId?.takeIf { it.isNotBlank() } ?: return null
        val user = AccountIds.canonical(userId) ?: return null
        if (isMe(user)) return null
        stored(circle, user)?.let { return it }
        if (!pass.refetched.add(AccountIds.storeKey(circle))) return null
        runCatching { circleRepository.get().refreshRoster(circle) }
        return stored(circle, user)
    }

    private suspend fun stored(circleId: String, userId: String): String? =
        runCatching { circleMemberDao.getByCircle(circleId) }.getOrNull()
            ?.firstOrNull { AccountIds.same(it.userId, userId) }
            ?.takeIf { it.nameOpened }
            ?.displayName
            ?.takeIf { it.isNotBlank() }

    /** Who asked to be checked on, by the receivers' rules ([sosRaiser]); this account as "me". */
    private suspend fun raiserNames(circleId: String?, metadata: Map<String, String>, pass: Pass): NamedNotificationCopy.Names {
        val raiser = sosRaiser(circleId, metadata["sos_id"], pass)
        return if (isMe(raiser)) NamedNotificationCopy.Names(personIsMe = true)
        else NamedNotificationCopy.Names(person = member(circleId, raiser, pass))
    }

    /**
     * "Ana and 1 other are responding" for [acknowledgers] (oldest first): the OLDEST of them
     * this device can name, else "Someone", and how many others. As iOS's live card: the words
     * hold still while more people answer, and the notices name each new one.
     */
    suspend fun respondingCopy(circleId: String, acknowledgers: List<String>): String {
        val pass = Pass()
        val named = acknowledgers.firstNotNullOfOrNull { member(circleId, it, pass) }
        return NamedNotificationCopy.respondingCopy(named ?: "Someone", acknowledgers.size - 1)
    }

    /** The name of a place this device holds, else null: no fetch for one it can't open. */
    suspend fun place(placeId: String?): String? =
        placeId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { placeDao.getById(id) }.getOrNull()?.name?.takeIf { it.isNotBlank() }
        }

    private suspend fun circle(circleId: String?): String? =
        circleId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { circleDao.getById(id) }.getOrNull()?.name?.takeIf { it.isNotBlank() }
        }

    /** A user id the sender sealed with the circle's key (field tag "user_id"), opened here. */
    fun sealedUserId(circleId: String?, ciphertext: String?, nonce: String?): String? {
        if (circleId.isNullOrBlank() || ciphertext.isNullOrBlank() || nonce.isNullOrBlank()) return null
        return runCatching {
            cryptoManager.decryptDataV2(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(nonce = nonce, ciphertext = ciphertext),
                "user_id",
            )
        }.getOrNull()?.let { AccountIds.canonical(it) }
    }

    /**
     * Who asked to be checked on, by the receivers' rules ([SosRepository.raiserToName]): only when
     * the alert opens under the circle's current key, or this device saw it current before. Once
     * per SOS in [pass].
     */
    private suspend fun sosRaiser(circleId: String?, sosId: String?, pass: Pass): String? {
        val circle = circleId?.takeIf { it.isNotBlank() } ?: return null
        val id = AccountIds.canonical(sosId) ?: return null
        if (id in pass.raisers) return pass.raisers[id]
        return runCatching { sosRepository.get().raiserToName(id, circle) }.getOrNull()
            .also { pass.raisers[id] = it }
    }

    /** Who is asking to take over the circle, while the claim [claimId] is still the live one. */
    private suspend fun claimant(circleId: String?, claimId: String?): String? {
        if (circleId.isNullOrBlank() || claimId.isNullOrBlank()) return null
        val claim = runCatching { tracksApi.getOwnershipClaim(circleId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.data ?: return null
        return claim.claimantUserId.takeIf { AccountIds.same(claim.claimId, claimId) }
    }

    private fun isMe(userId: String?): Boolean = AccountIds.same(userId, sessionManager.getUserId())
}
