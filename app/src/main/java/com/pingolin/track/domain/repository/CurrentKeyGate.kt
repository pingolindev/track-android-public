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
import com.pingolin.track.data.local.dao.SosAlertDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nothing new is sealed for a circle whose key moved on without this device.
 *
 * A rotation goes ahead around the members it cannot vouch for — an unapproved key
 * change, somebody not given access yet — and leaves them pending, still holding the
 * previous key. So does everyone removed since. Whatever a pending device sealed under
 * that key, a removed member could read. Until the current key reaches this device, its
 * locations are not sent to that circle, nothing it writes there is encrypted, and it
 * does not re-key the circle. An SOS is the one exception (as on iPhone): it
 * is sent, and while it is active the user's location keeps going to that circle, under
 * the older key if that is all this device holds.
 *
 * Judged from the circle's `key_version` as the circle list last reported it against
 * the newest key this device may seal under: on a circle with refresh records, the highest
 * version whose record it accepted. A key it holds for reading only (its version waits on
 * its record or was refused here, or the circle's records forked) is behind as well. So is
 * one this device's own records say it is behind on, whatever version the circle list
 * shows ([recordsBehind]).
 */
@Singleton
class CurrentKeyGate @Inject constructor(
    private val circleDao: CircleDao,
    private val cryptoManager: CryptoManager,
    private val sosAlertDao: SosAlertDao,
    private val sessionManager: SessionManager,
) {
    private val _behind = MutableStateFlow<Set<String>>(emptySet())

    /** Circles this device is behind on, as last judged; shown on their rows. */
    val behind: StateFlow<Set<String>> = _behind.asStateFlow()

    private val _recordsBehind = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Circles this device's own records say it is behind on (the SDK's isBehind: behind is the
     * device's own knowledge), whatever version the circle list shows: a version above the one it
     * seals under waits here, one was passed over, its chain forked, or its records can't be read.
     * Nothing new is sealed or handed over for them, and they show as waiting to sync. Not
     * [behind]: the row isn't locked, and a rotation, an eject, a repair and a hand-over go on
     * (the SDK's builder is their gate, and a repair is what ends a pass-over).
     */
    val recordsBehind: StateFlow<Set<String>> = _recordsBehind.asStateFlow()

    /**
     * Whether anything new may be sealed for [circleId]. False when this device holds
     * only an older key than the circle's, or only keys it may read with, and when it
     * holds none at all (nothing could be sealed anyway); false too while its records say
     * it is behind ([recordsBehind]). True for the personal key, which is never rotated, and
     * for a circle this device has neither listed nor any records of.
     */
    suspend fun holdsCurrentKey(circleId: String): Boolean = judge(circleId, forRotation = false)

    /**
     * Whether this device holds [circleId]'s current version: what a rotation starts from. As
     * [holdsCurrentKey], except that a re-key this device owes ([reKeyPending]) doesn't hold it
     * back: that rotation is what ends it.
     */
    suspend fun holdsCurrentVersion(circleId: String): Boolean = judge(circleId, forRotation = true)

    /**
     * Set by CircleRepository: this device re-keys [CircleEntity] before anything more is sealed
     * under its key in use (the key came only through an identity from before a password reset,
     * or was taken on trust, and this user may re-key it). Until that rotation lands its seals
     * wait, as on iPhone. Not "behind": nothing is missing from another member, and this device's
     * own refresh ends it.
     */
    @Volatile
    internal var reKeyPending: ((CircleEntity) -> Boolean)? = null

    private suspend fun judge(circleId: String, forRotation: Boolean): Boolean {
        if (circleId == LocationRepository.SELF_CIRCLE_ID || circleId == LocationRepository.SELF_CIRCLE_UUID) return true
        val onKey = judgeKey(circleId, forRotation)
        if (forRotation) return onKey
        // Asked for seals only, whatever the key: never for a rotation ([holdsCurrentVersion]).
        return !recordsSayBehind(circleId) && onKey
    }

    /** [circleId]'s key against the circle list's version, noted in [behind]. */
    private suspend fun judgeKey(circleId: String, forRotation: Boolean): Boolean {
        val circle = try {
            circleDao.getById(circleId)
        } catch (e: Exception) {
            Log.w(TAG, "Circle $circleId not readable: ${e.message} — not judged")
            null
        } ?: return true
        val held = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrNull()
        if (held == null) {
            if (readsOnly(circleId) && circleId !in _behind.value) {
                _behind.update { it + circleId }
                Log.w(TAG, "Behind on $circleId: its keys here are for reading only")
            }
            return false
        }
        val current = held >= circle.keyVersion
        val wasBehind = circleId in _behind.value
        _behind.update { if (current) it - circleId else it + circleId }
        if (!current && !wasBehind) Log.w(TAG, "Behind on $circleId's key: hold v$held, the circle is at v${circle.keyVersion}")
        if (!current || forRotation) return current
        return reKeyPending?.invoke(circle) != true
    }

    /**
     * Whether this user's location may be sealed for [circleId]: [holdsCurrentKey], or an
     * SOS this user raised there is still active. Reaching the circle in an emergency
     * matters more than who else may read it.
     */
    suspend fun mayStreamLocationTo(circleId: String): Boolean {
        if (holdsCurrentKey(circleId)) return true
        val userId = sessionManager.getUserId() ?: return false
        val sosActive = runCatching { sosAlertDao.countActiveRaisedBy(userId, circleId) > 0 }.getOrDefault(false)
        if (sosActive) Log.d(TAG, "SOS active in $circleId: its location goes out under the key this device holds")
        return sosActive
    }

    /**
     * How this device catches up on a circle it is behind on, waiting a little: set by
     * CircleRepository (which asks the members for the current copy and looks again).
     */
    @Volatile
    internal var catchUp: (suspend (String) -> Boolean)? = null

    /**
     * [holdsCurrentKey], or [KeyNotCurrentException] — for a write the user asked for. When
     * this device is behind, it first catches up ([catchUp]) and the write goes ahead if the
     * current copy arrives, instead of the person being told to try again once synced.
     */
    suspend fun requireCurrentKey(circleId: String) {
        if (holdsCurrentKey(circleId)) return
        if (catchUp?.invoke(circleId) == true && holdsCurrentKey(circleId)) return
        throw KeyNotCurrentException()
    }

    /** Judge every circle in [circles], freshly listed, at once. */
    fun noteListed(circles: List<CircleEntity>) {
        val behind = circles.filter { circle ->
            val held = runCatching { cryptoManager.getActiveVersion(circle.id) }.getOrNull()
            if (held == null) readsOnly(circle.id) else held < circle.keyVersion
        }.map { it.id }.toSet()
        val listed = circles.map { it.id }.toSet()
        _behind.update { (it - listed) + behind }
        noteRecords(listed)
    }

    /**
     * [circleIds] per this device's records now ([recordsBehind]): after a listing, a records
     * import, or a rotation of its own.
     */
    fun noteRecords(circleIds: Collection<String>) {
        val ids = circleIds.toSet()
        val behind = ids.filter(::readBehind).toSet()
        _recordsBehind.update { (it - ids) + behind }
    }

    /** [circleId] noted in [recordsBehind] as its records say now. */
    private fun recordsSayBehind(circleId: String): Boolean {
        val behind = readBehind(circleId)
        val was = circleId in _recordsBehind.value
        _recordsBehind.update { if (behind) it + circleId else it - circleId }
        if (behind && !was) Log.w(TAG, "Behind on $circleId by its records here: nothing new is sealed for it")
        return behind
    }

    /** The SDK's isBehind; a read that fails is behind. */
    private fun readBehind(circleId: String): Boolean = runCatching { cryptoManager.isBehind(circleId) }.getOrDefault(true)

    /** Keys held for [circleId], and none it may seal under: see the class comment. */
    private fun readsOnly(circleId: String): Boolean =
        runCatching { cryptoManager.newestHeldVersion(circleId) != null }.getOrDefault(false)

    /** This device just made [circleId]'s current key (a rotation it ran landed). */
    fun noteCurrent(circleId: String) {
        _behind.update { it - circleId }
        noteRecords(listOf(circleId))
    }

    /** Sign-out: the next account judges its own circles. */
    fun clear() {
        _behind.value = emptySet()
        _recordsBehind.value = emptySet()
    }

    private companion object {
        const val TAG = "CurrentKeyGate"
    }
}

/**
 * A write refused because this device does not hold the circle's current key (see
 * [CurrentKeyGate]), after [CurrentKeyGate.requireCurrentKey] has already waited for it;
 * its message is shown as it stands. It says what happened, not "try again", and promises
 * nothing. The same words on every client.
 */
class KeyNotCurrentException : Exception(
    "Not done: this phone is still waiting to sync this circle's settings from another member."
)
