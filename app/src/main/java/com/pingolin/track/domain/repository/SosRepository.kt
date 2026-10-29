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
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.SosAlertDao
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.sos.OwnSosSeal
import com.pingolin.track.domain.sos.OwnSosSeals
import com.pingolin.track.domain.sos.SosJudge
import com.pingolin.track.domain.sos.SosPositionChooser
import com.pingolin.track.domain.sos.SosPositionMarker
import com.pingolin.track.domain.sos.SosSeal
import com.pingolin.track.domain.sos.SosUpdateRule
import com.pingolin.track.domain.sos.SosVerdict
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.OwedWipe
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SosMarks
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KeyStoreLoad
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SosRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val sosAlertDao: SosAlertDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    /** The SOS marks, the roster and a key refresh. Lazy: CircleRepository reseals through this class. */
    private val circleRepository: dagger.Lazy<CircleRepository>,
    private val currentKeyGate: CurrentKeyGate,
    private val owedWipe: OwedWipe,
    /** Takes down a post for an SOS that isn't live. Lazy: AlertHandler reaches this class too. */
    private val alertHandler: dagger.Lazy<AlertHandler>,
    /** The names this device shows, for the alarm: never the server's words. */
    private val circleDao: CircleDao,
    private val circleMemberDao: CircleMemberDao,
    /** Which position this phone's own SOS carries, and a fix taken after its press. */
    private val positions: SosPositionChooser,
) {
    companion object {
        private const val TAG = "SosRepository"

        /** Milliseconds per day for day bucket calculation */
        private const val MS_PER_DAY = 86400000L

        /**
         * Local-only terminal status for an alert that dropped out of
         * `/sos/active` and whose real status we couldn't re-fetch (404 because
         * we lost circle access, or the network failed). We record "closed"
         * rather than guessing "resolved", so the UI never claims an outcome
         * the server didn't state. Either way it stops rendering: every active
         * query filters on `status = 'active'`.
         */
        const val STATUS_CLOSED = "closed"

        /** A live SOS: what every active view reads (SosAlertDao), and iOS's isLive. */
        private const val STATUS_ACTIVE = "active"

        /**
         * What only an ending writes (the server's resolve and false_alarm), as iOS's isClosed:
         * seen once, the SOS never alarms here again.
         */
        private val ENDED = setOf("resolved", "false_alarm")

        /** An SOS its raiser may still reseal: the statuses the server allows a reseal
         * in.
         */
        private val OPEN = setOf("active", "acknowledged")

        /**
         * How long a pushed SOS's check may take before it rings anyway: no usable answer inside the
         * budget is "can't check". As iOS's extension.
         */
        internal const val CHECK_BUDGET_MS = 10_000L

        /** Reseals of one SOS this device sends in an hour; the server refuses past its own 10. */
        internal const val MAX_RESEALS_PER_HOUR = 10

        /**
         * How long the circle/key read before a re-seal may take: one that doesn't answer in time
         * leaves the run to the key this device holds, checked as usual.
         */
        internal const val KEY_READ_BUDGET_MS = 15_000L

        /**
         * How long a position update waits while its circle shows `key_rotation_needed`: the
         * rotation, and the re-seal it brings, go first; a flag nobody acts on holds it no longer.
         */
        internal const val ROTATION_DEFER_MS = 2 * 60_000L

        /**
         * How often one SOS that doesn't open here may cost a circle refresh: a server listing a row that
         * never opens can't make any pass loop on it.
         */
        internal const val UNOPENED_REFRESH_INTERVAL_MS = 60_000L

        private const val HOUR_MS = 3_600_000L
    }

    /** The clock the rules read; tests move it. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    /**
     * Where an SOS is opened: per-alert decrypt is pure CPU (UniFFI), off the caller's thread.
     * Tests open in place, so the check's budget runs on their clock alone.
     */
    internal var openOn: kotlin.coroutines.CoroutineContext = Dispatchers.Default

    private val marks: SosMarks get() = circleRepository.get().sosMarks

    /** What this phone sealed into its own SOS. */
    private val ownSeals: OwnSosSeals get() = circleRepository.get().ownSosSeals

    /**
     * Safety-screen alerts, re-emitted every minute.
     *
     * The minute tick is what keeps the "removed in Xh Ym" countdown honest and
     * drops a row the moment its 24h undo window closes: Room only re-emits when
     * the TABLE changes, and the passage of time is not a table change, so
     * without this a dismissed alert would sit there with a frozen countdown
     * until something unrelated touched the database.
     */
    fun observeActiveAlerts(): Flow<List<SosAlertEntity>> =
        flow {
            while (true) {
                emit(Unit)
                kotlinx.coroutines.delay(60_000)
            }
        }.flatMapLatest {
            sosAlertDao.observeActive(System.currentTimeMillis() - SosAlertEntity.DISMISS_GRACE_MS)
        }.orWhenUnopenable(emptyList()) // no crash at a cold start, said once

    /**
     * Active alerts the MAP should draw: undismissed, unacknowledged by this
     * user, and with coordinates that actually decrypted. Cross-circle by
     * design — an SOS is a safety signal, so it renders whatever circle the
     * map is currently filtered to.
     *
     * A quiet SOS, older than the alarm age by its sealed time, is listed and
     * never pinned (as iOS's isMappable). Judged at each emission, with no
     * ticker of its own: one that ages past it while the map is open goes at
     * the next change to the table.
     */
    fun observeMappableAlerts(): Flow<List<SosAlertEntity>> =
        sosAlertDao.observeActiveMappable().map { alerts ->
            val now = now()
            alerts.filter { now - it.createdAt <= SosMarks.MAX_SOS_ALARM_AGE_MS }
        }.orWhenUnopenable(emptyList())

    fun observeAlertsByCircle(circleId: String): Flow<List<SosAlertEntity>> =
        sosAlertDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    suspend fun getAlert(alertId: String): SosAlertEntity? = sosAlertDao.getById(alertId)

    /**
     * Who asked to be checked on in the SOS [sosId], for a notification row that names them, by
     * the receivers' rules (as iOS's `raiser(of:marks:)`): the raiser only when the alert, as
     * served now, opens under the circle's CURRENT key, or this device saw that payload current
     * before (the alarm path's own sticky mark, [judge]). Never the stored row's raiser: that one
     * opened under ANY key this device holds, and an ended alert, or one this device can't check,
     * is stored without the rules, so a server replaying history, or a removed member's forgery,
     * would name whoever it says. Marks that don't read, an alert of another circle, or no answer:
     * nobody.
     */
    suspend fun raiserToName(sosId: String, circleId: String): String? {
        val dto = runCatching { tracksApi.getSosAlert(sosId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.data ?: return null
        if (!AccountIds.same(dto.id, sosId) || !AccountIds.same(dto.circleId, circleId)) return null
        val opened = withContext(openOn) { open(dto) }
        val raiser = opened.raiser ?: return null
        val seenCurrent = runCatching { marks.seenCurrent(dto.circleId, dto.id, opened.hash) }.getOrDefault(false)
        return raiser.takeIf { opened.current || seenCurrent }
    }

    /**
     * Compute day bucket from timestamp (days since Unix epoch).
     */
    private fun computeDayBucket(timestampMs: Long): Int =
        (timestampMs / MS_PER_DAY).toInt()

    /**
     * Encrypt timestamp as a Long (milliseconds since epoch).
     */
    private fun encryptTimestamp(circleId: String, timestampMs: Long): EncryptedPayload =
        sealed(circleId, timestampMs.toString(), "timestamp")

    private fun sealed(circleId: String, text: String, fieldTag: String): EncryptedPayload {
        val encrypted = cryptoManager.encryptDataV2(circleId, text, fieldTag)
        return EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
    }

    private fun EncryptedPayload.data() = EncryptedData(nonce = nonce, ciphertext = ciphertext)

    /** [encrypted]'s text and the circle-key version that opened it; null when it doesn't open. */
    private fun openText(circleId: String, encrypted: EncryptedPayload, fieldTag: String) =
        runCatching { cryptoManager.decryptDataV2Opened(circleId, encrypted.data(), fieldTag) }.getOrNull()

    /**
     * One SOS as served, opened: each sealed field with the circle-key version that opened it.
     * [versions] are those of the fields that opened.
     */
    internal data class Opened(
        val raiser: String?,
        val sealedAt: Long?,
        val point: uniffi.pingolin_mobile.Location?,
        val versions: List<Int>,
        /** The raiser id, time and position (or its marker) all opened, each under the version this device seals the circle under. */
        val current: Boolean,
        /** [SosJudge.payloadHash] of the sealed fields as served. */
        val hash: String,
        /**
         * No position, as the raiser's phone sealed it: [SosAlertEntity.POSITION_PENDING] or
         * [SosAlertEntity.POSITION_UNAVAILABLE]. An opened field, like a position: the SOS is shown
         * and alarms, with no pin.
         */
        val marker: String? = null,
    ) {
        /** A field didn't open: it may be sealed under a version this device doesn't hold yet. */
        val unopened: Boolean get() = raiser == null || sealedAt == null || (point == null && marker == null)
    }

    internal fun open(dto: SosAlertResponse): Opened {
        val circleId = dto.circleId
        val raiser = openText(circleId, dto.encryptedUserId, "user_id")
        val time = openText(circleId, dto.encryptedTimestamp, "timestamp")
        val point = runCatching { cryptoManager.decryptLocationOpened(circleId, dto.encryptedLocation.data()) }.getOrNull()
        // No position: the marker, sealed as the circle's "location" text.
        val marker = if (point != null) null else openText(circleId, dto.encryptedLocation, "location")
            ?.let { opened -> SosPositionMarker.read(opened.text)?.let { state -> state to opened.keyVersion } }
        val sealedAt = time?.text?.toLongOrNull()
        val versions = listOfNotNull(raiser?.keyVersion, time?.keyVersion, point?.keyVersion ?: marker?.second)
        val current = raiser != null && sealedAt != null && (point != null || marker != null) &&
            versions.all { runCatching { cryptoManager.isCurrentKeyVersion(circleId, it) }.getOrDefault(false) }
        return Opened(
            raiser = raiser?.text,
            sealedAt = sealedAt,
            point = point?.location,
            versions = versions,
            current = current,
            hash = SosJudge.payloadHash(dto.encryptedUserId, dto.encryptedLocation, dto.encryptedTimestamp, dto.encryptedDeviceId),
            marker = marker?.first,
        )
    }

    /** What one SOS is to this device. */
    internal sealed class Judged {
        /**
         * This device can't tell: its marks don't read, or it is behind on the circle's key. A
         * push for it rings anyway, in the generic words; the list leaves what it holds as it is.
         */
        object CantCheck : Judged()

        data class Ruled(val verdict: SosVerdict) : Judged()
    }

    /**
     * Whether this device is behind on [circleId]'s key by its own knowledge: it holds an older
     * version than the circle's (CurrentKeyGate), or its records say so (the SDK's isBehind). A read
     * that fails is behind.
     */
    private suspend fun behindOn(circleId: String): Boolean =
        !currentKeyGate.holdsCurrentVersion(circleId) || runCatching { cryptoManager.isBehind(circleId) }.getOrDefault(true)

    /**
     * Whether [opened] may be sealed under a version this device doesn't hold yet: a field didn't
     * open, or one opened under a version above the one it seals under. One that opened under an
     * OLDER version is history whatever this device's state.
     */
    private fun mayBeNewer(circleId: String, opened: Opened): Boolean {
        if (opened.unopened) return true
        val active = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrNull() ?: return true
        return opened.versions.any { it > active }
    }

    /**
     * The receivers' rules for one SOS, with the marks they leave: ended is noted one way; a payload
     * seen current is noted; a raiser off this device's COMPLETE roster ends its stickiness, one
     * way.
     */
    internal suspend fun judge(dto: SosAlertResponse, opened: Opened, now: Long): Judged {
        val circleId = dto.circleId
        val at = opened.sealedAt ?: now
        if (dto.status in ENDED) {
            marks.markEnded(circleId, dto.id, at)
            return Judged.Ruled(SosVerdict.HIDDEN)
        }
        // Not live, and not ended: no alarm, and no active view lists it (they read 'active' only).
        if (dto.status != STATUS_ACTIVE) return Judged.Ruled(SosVerdict.QUIET)
        if (!opened.current && mayBeNewer(circleId, opened) && behindOn(circleId)) return Judged.CantCheck
        if (!marks.readable()) {
            // Without its marks this device can't know "ended" or "sticky": a live SOS within the
            // alarm age can't be checked. Past it, or with no time that opened,
            // the marks change nothing: nothing alarms.
            val sealedAt = opened.sealedAt
            if (sealedAt != null && now - sealedAt <= SosMarks.MAX_SOS_ALARM_AGE_MS) return Judged.CantCheck
            return Judged.Ruled(SosJudge.verdict(ended = false, current = opened.current, sticky = false, sealedAt = sealedAt, now = now))
        }
        if (opened.current) marks.markSeenCurrent(circleId, dto.id, opened.hash, at)
        val seen = marks.seenCurrent(circleId, dto.id, opened.hash)
        // Null: this process hasn't judged that circle's complete member list yet, so no decision.
        val shown = opened.raiser?.let { circleRepository.get().memberShownIn(circleId, it) }
        if (!opened.current && seen && shown == false) marks.markRosterEnded(circleId, dto.id, at)
        val sticky = seen && shown != false && !marks.rosterEnded(circleId, dto.id)
        return Judged.Ruled(SosJudge.verdict(marks.ended(circleId, dto.id), opened.current, sticky, opened.sealedAt, now))
    }

    /**
     * Keep [dto] here as [judged] says: ALARMING or QUIET are listed; HIDDEN goes, with any post of
     * it; an ending keeps its row under the ended status; what can't be checked is listed when its
     * raiser and time opened, and otherwise left as it is. A post of an SOS that isn't alarming
     * goes: checked and not live, nothing shown. The row's time is the SEALED time.
     */
    private suspend fun store(
        dto: SosAlertResponse,
        opened: Opened,
        judged: Judged,
        fallback: SosAlertEntity? = null,
    ): SosAlertEntity? {
        val notLive = dto.status in ENDED || (judged as? Judged.Ruled)?.verdict.let { it == SosVerdict.HIDDEN || it == SosVerdict.QUIET }
        if (notLive) runCatching { alertHandler.get().cancelSos(dto.id) }
        val kept = when {
            dto.status in ENDED -> toEntity(dto, opened, fallback)
            judged is Judged.Ruled && judged.verdict == SosVerdict.HIDDEN -> {
                sosAlertDao.deleteById(dto.id)
                return null
            }
            judged is Judged.Ruled -> toEntity(dto, opened, fallback)
            opened.raiser != null && opened.sealedAt != null -> toEntity(dto, opened, fallback)
            else -> return fallback
        }
        return kept?.also { sosAlertDao.insert(it) }
    }

    /**
     * Build the local entity from a server response and what opened.
     *
     * Every endpoint (trigger / acknowledge / dismiss / resolve / cancel /
     * refresh) returns the same shape and builds its entity here, so a field
     * added for one path can't be missed on the others — losing the per-viewer
     * flags on, say, the resolve path would resurrect a dismissed marker.
     *
     * [fallback] supplies what the ciphertext can't: the row this device
     * already holds (mutation paths) or the raise this device just made (the
     * trigger). Never the server's day bucket or "now": a row whose raiser or
     * time didn't open has no date of its own, and isn't made.
     */
    private fun toEntity(dto: SosAlertResponse, opened: Opened, fallback: SosAlertEntity? = null): SosAlertEntity? {
        val userId = opened.raiser ?: fallback?.userId ?: return null
        // This phone's own SOS shows what it sealed (its own record), never a served copy that
        // differs: its position and raise time (iOS shows its record too).
        val mine = runCatching { ownSeals.get(dto.id) }.getOrNull()
            ?.takeIf { AccountIds.same(userId, sessionManager.getUserId()) && AccountIds.same(it.circleId, dto.circleId) }
        val createdAt = mine?.raisedAt ?: opened.sealedAt ?: fallback?.createdAt ?: return null
        // What opened says where the position is (a marker: nowhere yet); only what didn't open falls back.
        val seal = mine?.seal
            ?: opened.point?.let { SosSeal.Point(it.latitude, it.longitude, it.timestamp, it.accuracy) }
            ?: opened.marker?.let { SosSeal.Marker(it) }
        val point = seal as? SosSeal.Point
        val marker = (seal as? SosSeal.Marker)?.state
        val held = seal == null
        return SosAlertEntity(
            id = dto.id,
            circleId = dto.circleId,
            userId = userId,
            status = dto.status,
            latitude = point?.latitude ?: fallback?.latitude?.takeIf { held },
            longitude = point?.longitude ?: fallback?.longitude?.takeIf { held },
            acknowledgedBy = fallback?.acknowledgedBy ?: "",
            createdAt = createdAt,
            resolvedAt = dto.resolvedDayBucket?.let { it.toLong() * MS_PER_DAY },
            acknowledgedByMe = dto.acknowledgedByMe,
            dismissedByMe = dto.dismissedByMe,
            dismissedAt = parseInstantMillis(dto.dismissedAt),
            acknowledgmentCount = dto.acknowledgmentCount,
            // When it was TAKEN, and how close. A take time that isn't one (0) is unknown, never
            // 1970.
            fixAt = point?.takenAt?.takeIf { it > 0 } ?: fallback?.fixAt?.takeIf { held },
            accuracy = point?.accuracy ?: fallback?.accuracy?.takeIf { held },
            positionState = marker ?: fallback?.positionState?.takeIf { held },
        )
    }

    /** An SOS's location field as this device sealed it, and what it holds, opened back here. */
    private class SealedPosition(val payload: EncryptedPayload, val sealedAs: SosSeal?)

    /**
     * [position] as an SOS's location field: the packed position with when it was TAKEN and how close
     * (every other optional field nil), or the marker, sealed as the circle's "location" text. With
     * what it holds as the codec carries it, opened back from these very bytes here: the record
     * compares exactly with a served copy of its own position, and is never the server's echo. Null
     * when it doesn't open back.
     */
    private fun sealedPosition(circleId: String, position: SosSeal): SealedPosition = when (position) {
        is SosSeal.Point -> {
            val location = uniffi.pingolin_mobile.Location(
                latitude = position.latitude,
                longitude = position.longitude,
                altitude = null,
                speed = null,
                heading = null,
                accuracy = position.accuracy,
                // When it was taken, never the raise time: that is the sealed timestamp's.
                timestamp = position.takenAt,
                batteryLevel = null,
                isCharging = null,
            )
            val data = cryptoManager.encryptLocation(circleId, location)
            val sealedAs = runCatching { cryptoManager.decryptLocationOpened(circleId, data).location }.getOrNull()
                ?.let { SosSeal.Point(it.latitude, it.longitude, it.timestamp, it.accuracy) }
            SealedPosition(EncryptedPayload(nonce = data.nonce, ciphertext = data.ciphertext), sealedAs)
        }
        is SosSeal.Marker -> SealedPosition(sealed(circleId, SosPositionMarker.text(position.state), "location"), position)
    }

    /**
     * This device's record of what it sealed into [sosId]: [sealed] as opened back here, never the
     * server's echo. One that didn't open back leaves no record: a run then goes by what it reads, as
     * after a reinstall, rather than by doubles no served copy equals.
     */
    private fun keepSealed(sosId: String, circleId: String, raisedAt: Long, sealed: SealedPosition) {
        val sealedAs = sealed.sealedAs
        if (sealedAs != null) ownSeals.put(sosId, OwnSosSeal(circleId, raisedAt, sealedAs)) else ownSeals.remove(sosId)
    }

    /** The raise this device just sent, as a row: what its own copy falls back to where a field didn't open. */
    private fun sentRow(dto: SosAlertResponse, userId: String, raisedAt: Long, position: SosSeal): SosAlertEntity {
        val point = position as? SosSeal.Point
        return SosAlertEntity(
            id = dto.id, circleId = dto.circleId, userId = userId, status = dto.status,
            latitude = point?.latitude, longitude = point?.longitude, acknowledgedBy = "", createdAt = raisedAt, resolvedAt = null,
            fixAt = point?.takenAt, accuracy = point?.accuracy, positionState = (position as? SosSeal.Marker)?.state,
        )
    }

    /** A mutation's answer for its caller when no row is kept here: what opened, else what this device held. */
    private fun answered(dto: SosAlertResponse, opened: Opened, existing: SosAlertEntity?): SosAlertEntity =
        toEntity(dto, opened, existing) ?: SosAlertEntity(
            id = dto.id,
            circleId = dto.circleId,
            userId = existing?.userId.orEmpty(),
            status = dto.status,
            latitude = null,
            longitude = null,
            acknowledgedBy = "",
            createdAt = existing?.createdAt ?: 0L,
            resolvedAt = dto.resolvedDayBucket?.let { it.toLong() * MS_PER_DAY },
            acknowledgedByMe = dto.acknowledgedByMe,
            dismissedByMe = dto.dismissedByMe,
            dismissedAt = parseInstantMillis(dto.dismissedAt),
            acknowledgmentCount = dto.acknowledgmentCount,
        )

    /**
     * Raise an SOS in [circleId] with [position], what [SosPositionChooser] chose at the press made
     * at [pressAt]: never held back for a location, it fails only for network or server reasons. What
     * it sealed is this phone's record of it, and the press starts its 10-minute window for updates.
     * [raisedAt] is when it was asked, the countdown's end: sealed as the raise time, and kept as it,
     * however late the raise goes out.
     */
    suspend fun triggerSos(
        circleId: String,
        position: SosSeal,
        pressAt: Long,
        raisedAt: Long,
        deviceState: String? = null,
    ): Result<SosAlertEntity> {
        return try {
            val userId = sessionManager.getUserId() ?: return Result.failure(Exception("No user"))
            val deviceId = sessionManager.getDeviceId()
            // The raise time, never the send: every "asked at", the record and the day bucket.
            val now = raisedAt
            val dayBucket = computeDayBucket(now)

            // Deliberately NOT held back when this device lacks the circle's
            // current key (CurrentKeyGate): an SOS still goes out, sealed under the
            // older key, which a member removed since could read. Reaching the
            // circle matters more here.

            // E2EE: Encrypt user ID with circle key
            val encryptedUserId = sealed(circleId, userId, "user_id")

            // E2EE: Encrypt device ID with circle key
            val encryptedDeviceId = sealed(circleId, deviceId, "device_id")

            // E2EE: Encrypt timestamp with circle key. The RAISE time: "asked at" and the 24 h bound.
            val encryptedTimestamp = encryptTimestamp(circleId, now)

            // The position, with when it was taken, or the marker (always a location field).
            val sealedLocation = sealedPosition(circleId, position)
            val encryptedLocation = sealedLocation.payload

            // Optionally encrypt device state (battery level, etc.)
            val encryptedDeviceState = deviceState?.let { sealed(circleId, it, "device_state") }

            val response = tracksApi.triggerSos(
                TriggerSosRequest(
                    deviceId = deviceId,
                    circleId = circleId,
                    encryptedLocation = encryptedLocation,
                    encryptedDeviceState = encryptedDeviceState,
                    encryptedUserId = encryptedUserId,
                    encryptedDeviceId = encryptedDeviceId,
                    dayBucket = dayBucket,
                    encryptedTimestamp = encryptedTimestamp
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val dto = response.body()!!.data!!
                val opened = open(dto)
                // This device's own raise: seen current here (its sealed fields are what it just
                // sealed), so it stays this device's own across the next rotation until it reseals.
                if (opened.current) runCatching { marks.markSeenCurrent(dto.circleId, dto.id, opened.hash, opened.sealedAt ?: now) }
                // What it sealed, kept as its own, and the window its updates go in.
                keepSealed(dto.id, circleId, now, sealedLocation)
                ownRuns[AccountIds.storeKey(dto.id)] = OwnRun(pressAt, circleId)
                val alert = toEntity(
                    dto,
                    opened,
                    // What this device just sent, where its own copy didn't open.
                    sentRow(dto, userId, now, position),
                )!!
                // The circle has it: a local record that can't be written now (a database
                // that can't be opened) never makes it "not sent", which would offer to
                // ring everyone again. The row comes from the next listing.
                runCatching { sosAlertDao.insert(alert) }
                    .onFailure { Log.w(TAG, "Sent; its local record not written: ${it.javaClass.simpleName}") }
                Result.success(alert)
            } else {
                Result.failure(Exception("Could not notify your circle"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Shared body of every per-alert mutation. All five endpoints return the same
     * SOSResponse and differ only in which one is called and what the failure reads like, so
     * they share this one body that decrypts and rebuilds the entity.
     *
     * The answer is judged like any other: an SOS the rules hide here isn't kept because a
     * button reached it, and an ending is noted.
     */
    private suspend fun mutate(
        alertId: String,
        failureMessage: String,
        call: suspend (String) -> retrofit2.Response<ApiResponse<SosAlertResponse>>
    ): Result<SosAlertEntity> {
        return try {
            val response = call(alertId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val dto = response.body()!!.data!!
                val existing = sosAlertDao.getById(alertId)
                val opened = open(dto)
                val judged = judge(dto, opened, now())
                // Upsert, not update: a mutation can arrive for an alert this
                // device never cached (dismissing straight from a notification),
                // and Room's @Update silently no-ops on a missing row. The rules may
                // keep nothing here (an SOS this device hides); the server still did
                // what was asked.
                val alert = store(dto, opened, judged, existing) ?: answered(dto, opened, existing)
                // Resolved or a false alarm: what this phone sealed into it goes.
                if (dto.status in ENDED) forgetOwn(alertId)
                Result.success(alert)
            } else {
                Result.failure(Exception(failureMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Outward "I'm responding" signal. Does NOT close the alert for anyone else. */
    suspend fun acknowledgeSos(alertId: String): Result<SosAlertEntity> =
        mutate(alertId, "Could not acknowledge this notification") { tracksApi.acknowledgeSos(it) }

    /**
     * Private per-viewer hide. Tells no other member anything, changes no shared
     * state, and leaves the alert active for everyone else.
     */
    suspend fun dismissSos(alertId: String): Result<SosAlertEntity> =
        mutate(alertId, "Could not dismiss this notification") { tracksApi.dismissSos(it) }

    /**
     * Undo a dismissal, within the grace window. Restores the list row to
     * normal AND puts the marker back on the map — both read the same
     * dismissedByMe flag, so one call fixes both surfaces.
     */
    suspend fun undismissSos(alertId: String): Result<SosAlertEntity> =
        mutate(alertId, "Could not restore this notification") { tracksApi.undismissSos(it) }

    /**
     * Tell the server the push for [alertId] reached this device.
     *
     * Best-effort and deliberately silent: nothing the user does depends on it,
     * and the alert itself must not be disturbed because a bookkeeping call
     * failed. The cost of it not landing is that the raiser sees "no
     * confirmation" rather than "confirmed" — a pessimistic answer, which is the
     * right direction for this to fail in.
     */
    suspend fun reportSosReceipt(alertId: String) {
        runCatching {
            tracksApi.postSosReceipt(alertId, SosReceiptRequest(platform = "android"))
        }.onFailure {
            Log.w(TAG, "SOS receipt for $alertId not reported: ${it.message}")
        }
    }

    /**
     * How far one of MY OWN alerts got. Null when the answer could not be
     * obtained, which is NOT the same as "it failed" — callers must not render
     * it as such.
     */
    suspend fun getSosDelivery(alertId: String): SosDeliveryResponse? =
        runCatching {
            val response = tracksApi.getSosDelivery(alertId)
            response.body()?.takeIf { response.isSuccessful && it.success }?.data
        }.getOrNull()

    /** RFC 3339 → epoch millis, or null when absent/unparseable. */
    private fun parseInstantMillis(raw: String?): Long? =
        raw?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }
                .getOrElse {
                    runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
                        .getOrNull()
                }
        }

    /** Closes the alert for the whole circle. The raiser's alone: alerts are personal. */
    suspend fun resolveSos(alertId: String): Result<SosAlertEntity> =
        mutate(alertId, "Could not resolve this notification") { tracksApi.resolveSos(it) }

    /** Raiser-only "false alarm". Closes the alert for the whole circle. */
    suspend fun cancelSos(alertId: String): Result<SosAlertEntity> =
        mutate(alertId, "Could not mark this as a false alarm") { tracksApi.cancelSos(it) }

    /**
     * Every SOS the server lists, judged and kept as the rules show it, each one judged alarming
     * rung once here ([ringIfNew]), and this account's own open SOS resealed where a rotation left
     * it behind (the resume path of [resealOwn]). A launch, a foreground, a follow-up and a server's
     * quiet `sos_refresh` land here.
     */
    suspend fun refreshActiveAlerts(): Result<List<SosAlertEntity>> {
        return try {
            val listed = refreshListed() ?: return Result.failure(Exception("Failed to fetch alerts"))
            runCatching { resealOwn() }.onFailure { Log.w(TAG, "Reseal pass failed: ${it.javaClass.simpleName}") }
            Result.success(listed.values.mapNotNull { it.kept })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One listed SOS after a pass: as served, what opened, what the rules said, and the row kept. [refreshed]: this pass
     * refreshed the circles for it, as it didn't open ([judgedAfterRefresh]).
     */
    private data class Listed(
        val dto: SosAlertResponse,
        val opened: Opened,
        val judged: Judged,
        val kept: SosAlertEntity?,
        val refreshed: Boolean = false,
    )

    /** One SOS judged in a pass, not stored yet. */
    private data class Judging(val dto: SosAlertResponse, val opened: Opened, val judged: Judged, val refreshed: Boolean = false)

    /**
     * Each SOS's last circle refresh for a payload that didn't open here: when, and whether it answered.
     * Keyed by the SOS id in its one spelling.
     */
    private val unopenedRefreshes = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * An active SOS a field of which didn't open, that the rules hid as this device knows its keys (HIDDEN),
     * may be sealed under a key it hasn't heard of yet: another member rotated, and nothing pushes that. One
     * circle refresh for the pass, at most once a minute per SOS: it imports a key that moved and lists the
     * circle's version (CircleRepository.refreshCircles). Then those are opened and judged again: they open;
     * or this device is behind now, so they can't be checked (a push rings the generic alarm); or, still not
     * opening with nothing behind, the rules stand. A refresh that failed, this time or the last one for
     * that SOS within the minute, leaves them "can't check", never hidden.
     */
    private suspend fun judgedAfterRefresh(judging: List<Judging>): List<Judging> {
        val stale = judging.filter { it.dto.status == STATUS_ACTIVE && it.opened.unopened && it.judged.hidden() }
        if (stale.isEmpty()) return judging
        val now = now()
        unopenedRefreshes.entries.removeIf { now - it.value.first > SosMarks.MAX_SOS_ALARM_AGE_MS }
        val due = stale.filter { entry ->
            unopenedRefreshes[AccountIds.storeKey(entry.dto.id)]?.let { now - it.first >= UNOPENED_REFRESH_INTERVAL_MS } ?: true
        }.map { AccountIds.storeKey(it.dto.id) }.toSet()
        if (due.isNotEmpty()) {
            // Claimed before it runs: a pass meanwhile doesn't refresh again, and can't check them until it answers.
            for (key in due) unopenedRefreshes[key] = now to false
            val ok = try {
                circleRepository.get().refreshCircles().isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            for (key in due) unopenedRefreshes[key] = now to ok
            Log.i(TAG, "SOS ${due.joinToString { it.take(8) }} didn't open here: one circle refresh, ok=$ok")
        }
        val after = now()
        return judging.map { entry ->
            if (entry !in stale) return@map entry
            val key = AccountIds.storeKey(entry.dto.id)
            val answered = unopenedRefreshes[key]?.second == true
            val opened = withContext(openOn) { open(entry.dto) }
            val judged = judge(entry.dto, opened, after).let { if (!answered && opened.unopened && it.hidden()) Judged.CantCheck else it }
            Log.i(TAG, "SOS ${key.take(8)} judged again: " + ((judged as? Judged.Ruled)?.verdict?.name ?: "can't check"))
            Judging(entry.dto, opened, judged, refreshed = key in due)
        }
    }

    private fun Judged.hidden() = (this as? Judged.Ruled)?.verdict == SosVerdict.HIDDEN

    /**
     * The active SOS a pass left waiting on a key, by circle (each in its one spelling): can't be checked, or hidden
     * as they didn't open. A key that arrives for that circle judges them again at once ([keysMoved]).
     */
    private val waitingOnKey = ConcurrentHashMap<String, Set<String>>()

    private fun noteWaiting(entries: Collection<Listed>) {
        val waiting = entries.filter { entry ->
            entry.dto.status == STATUS_ACTIVE && (entry.judged is Judged.CantCheck || entry.opened.unopened && entry.judged.hidden())
        }.groupBy({ AccountIds.storeKey(it.dto.circleId) }, { AccountIds.storeKey(it.dto.id) })
        waitingOnKey.keys.retainAll(waiting.keys)
        for ((circle, ids) in waiting) waitingOnKey[circle] = ids.toSet()
    }

    /** Where [keysMoved]'s pass runs; tests run it in their own scope. */
    internal var passScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One pass at a time for [keysMoved]; a call while one runs asks for one more after it. */
    private val passRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var passLoopStarted = false

    /**
     * A key import moved [circleIds]' held versions (CircleRepository.importCircleKeys): an SOS a pass left
     * waiting on a key there is judged again now, in one pass, rather than at the next list refresh. An
     * alarm judged then rings once ([ringIfNew]); one whose push already rang the generic alarm only gets
     * its row.
     */
    fun keysMoved(circleIds: Set<String>) {
        val moved = circleIds.map(AccountIds::storeKey).toSet()
        if (waitingOnKey.keys.none { it in moved }) return
        synchronized(passRequests) {
            if (!passLoopStarted) {
                passLoopStarted = true
                passScope.launch {
                    for (request in passRequests) {
                        runCatching { refreshActiveAlerts() }
                            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "SOS pass after a key failed: ${it.javaClass.simpleName}") }
                    }
                }
            }
        }
        passRequests.trySend(Unit)
    }

    /**
     * One pass over GET /sos/active: every listed SOS judged and kept as the rules show it, each one
     * judged alarming rung once here but the [pushed] one (its push rings it), and the ones this
     * device held as active that the list dropped closed out. Keyed by the SOS id in its one
     * spelling. Null when the answer is no usable list (not a 2xx with data); throws as the call
     * does on transport and decoding.
     */
    private suspend fun refreshListed(pushed: String? = null): Map<String, Listed>? {
        // Snapshot the locally-active ids BEFORE the request goes out. An
        // alert inserted while it's in flight (this device triggering its
        // own SOS) isn't in this set, so the sweep below can't mark it
        // stale on the strength of a response that predates it.
        val knownActiveIds = sosAlertDao.getActiveIds().toSet()

        val response = tracksApi.getActiveSosAlerts()
        val dtos = response.body()?.takeIf { response.isSuccessful && it.success }?.data ?: return null
        // Ids the SERVER still considers active. Taken from the raw DTOs, not
        // from what the rules keep below: an SOS the rules hide is the
        // server's still, and treating it as "gone" would close it out.
        val returnedIds = dtos.map { it.id }.toSet()

        val now = now()
        // Per-alert decrypt is pure CPU (UniFFI); off the caller's thread.
        val opened = withContext(openOn) { dtos.map { it to open(it) } }
        // Each stored only once judged for good: one that didn't open waits for its circle refresh, so a first
        // verdict never takes its row or post down before the refresh answers.
        val judging = judgedAfterRefresh(opened.map { (dto, o) -> Judging(dto, o, judge(dto, o, now)) })
        val listed = LinkedHashMap<String, Listed>()
        for (entry in judging) {
            val kept = store(entry.dto, entry.opened, entry.judged, sosAlertDao.getById(entry.dto.id))
            listed[AccountIds.storeKey(entry.dto.id)] = Listed(entry.dto, entry.opened, entry.judged, kept, entry.refreshed)
        }
        noteWaiting(listed.values)
        reconcileStale(knownActiveIds - returnedIds)
        runCatching { marks.pruneBefore(now - SosMarks.MAX_SOS_ALARM_AGE_MS) }
        // What this phone sealed into its own SOS goes 24 h past the raise.
        runCatching { ownSeals.pruneBefore(now - SosMarks.MAX_SOS_ALARM_AGE_MS) }
        for ((key, entry) in listed) if (key != pushed) ringIfNew(entry)
        return listed
    }

    /** One ring decision at a time, across every pass and the push path. */
    private val ringLock = Any()

    /**
     * The SOS [sosId]'s alarm, [post]ed with its sound only the first time on this device, and
     * silently, or not at all, after: that it sounded here is a one-way mark ([SosMarks.rang]),
     * noted once the post went, so a stop between the two rings again rather than never. One at a
     * time, so two passes never both sound it. Marks that don't read say it never rang. [at]
     * dates the mark, as the SOS's other marks: its sealed time where that opened, else now.
     */
    fun ringOnce(circleId: String, sosId: String, at: Long? = null, post: (sound: Boolean) -> Unit) {
        synchronized(ringLock) {
            val sound = !runCatching { marks.rang(circleId, sosId) }.getOrDefault(false)
            post(sound)
            if (sound) runCatching { marks.markRang(circleId, sosId, at ?: now()) }
        }
    }

    /**
     * [entry] rings here once if its pass judged it alarming: the checked push's post, with the
     * names this device opened, the first time; left as it is after. Never what can't be checked,
     * quiet or hidden (only a push rings what it can't check), and never this account's own SOS: the
     * server pushes it to the other members only.
     */
    private suspend fun ringIfNew(entry: Listed) {
        if ((entry.judged as? Judged.Ruled)?.verdict != SosVerdict.ALARMING) return
        val me = sessionManager.getUserId() ?: return
        val raiser = entry.opened.raiser ?: return
        if (AccountIds.same(raiser, me)) return
        val names = alarming(entry)
        runCatching {
            ringOnce(entry.dto.circleId, entry.dto.id, entry.opened.sealedAt) { sound ->
                if (sound) alertHandler.get().ringSos(entry.dto.id, names.raiserName, names.circleName)
            }
        }.onFailure { Log.w(TAG, "SOS alarm not posted: ${it.javaClass.simpleName}") }
    }

    /** What a pushed SOS's check found. */
    sealed class PushCheck {
        /** This device learned nothing about the SOS: it rings, in the generic words. */
        object CantCheck : PushCheck()

        /** Checked, and alarming: the alarm, with the names this device shows (null: the generic words). */
        data class Alarming(val raiserName: String?, val circleName: String?) : PushCheck()

        /** Checked, and not live: nothing is shown, and an earlier post goes. */
        object NotLive : PushCheck()
    }

    /**
     * The check a pushed SOS gets before it rings: the list, the receivers' rules, and the line
     * between "can't check" and "checked":
     *  - can't check (it rings): this device's keys, session or marks don't read or a wipe is owed;
     *    no usable answer inside [CHECK_BUDGET_MS] (no network, a timeout, any status but a 2xx with
     *    a list, an answer that doesn't decode); behind on the circle's key after one key refresh;
     *  - checked: the SOS absent from the list, or the rules on it: only current or sticky alarms.
     * Never throws.
     */
    suspend fun checkPushed(sosId: String?): PushCheck {
        val id = sosId?.takeIf { AccountIds.canonical(it) != null } ?: return PushCheck.CantCheck
        return try {
            withTimeoutOrNull(CHECK_BUDGET_MS) { checkListed(id) } ?: PushCheck.CantCheck
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SOS check couldn't run (${e.javaClass.simpleName}): it rings")
            PushCheck.CantCheck
        }
    }

    /** Whether this device can run the check at all: signed in with a session, its keys loaded whole, no wipe owed. */
    private fun canCheckHere(): Boolean {
        if (sessionManager.getUserId().isNullOrEmpty()) return false
        if (runCatching { sessionManager.getAccessToken() }.getOrNull().isNullOrEmpty()) return false
        if (owedWipe.isOwed()) return false
        val status = runCatching { cryptoManager.keyStoreStatus() }.getOrNull() ?: return false
        return status.load == KeyStoreLoad.LOADED && !status.entriesUnreadable
    }

    private suspend fun checkListed(sosId: String): PushCheck {
        if (!canCheckHere()) return PushCheck.CantCheck
        val listed = refreshListed(pushed = AccountIds.storeKey(sosId)) ?: return PushCheck.CantCheck
        var entry = listed[AccountIds.storeKey(sosId)] ?: return PushCheck.NotLive
        // Not after this pass refreshed the circles for it: that refresh imported what moved.
        if (entry.judged is Judged.CantCheck && entry.dto.status == STATUS_ACTIVE && !entry.refreshed && behindOn(entry.dto.circleId)) {
            // Behind on the circle's key: one key refresh inside the budget, then the rules
            // again. Still behind: it can't be checked.
            circleRepository.get().importCircleKeys()
            val dto = entry.dto
            val opened = withContext(openOn) { open(dto) }
            val judged = judge(dto, opened, now())
            entry = Listed(dto, opened, judged, store(dto, opened, judged, sosAlertDao.getById(dto.id)))
        }
        return when (val judged = entry.judged) {
            is Judged.CantCheck -> PushCheck.CantCheck
            is Judged.Ruled -> if (judged.verdict == SosVerdict.ALARMING) alarming(entry) else PushCheck.NotLive
        }
    }

    /**
     * The alarm's names, opened on this device and never the server's words: the raiser's sealed
     * name, only if this device shows them (member rows hold the shown members only, under their
     * one spelling), and the circle's own sealed name. Else none: the generic words.
     */
    private suspend fun alarming(entry: Listed): PushCheck.Alarming {
        val circleId = entry.dto.circleId
        val raiser = entry.opened.raiser?.let { AccountIds.canonical(it) }
        val raiserName = raiser?.let { runCatching { circleMemberDao.get(circleId, it) }.getOrNull() }
            ?.takeIf { it.nameOpened }?.displayName
        val circleName = runCatching { circleDao.getById(circleId)?.name }.getOrNull()
            ?.takeUnless { LockedCircle.isPlaceholder(it) }
        return PushCheck.Alarming(raiserName?.takeIf { it.isNotBlank() }, circleName?.takeIf { it.isNotBlank() })
    }

    /** When each SOS was last resealed from here, for the hourly cap. */
    private val reseals = ConcurrentHashMap<String, List<Long>>()

    /**
     * One own SOS's update window in this process: its press, the updates the rule made, and when
     * an update first waited on a due rotation. After a restart the window runs from the sealed
     * raise time, and counts from zero.
     */
    internal class OwnRun(
        val pressAt: Long,
        /** The circle the press raised it in, in this process: the user's own choice, never a served one. */
        val circleId: String? = null,
    ) {
        var updates = 0
        var deferredSince: Long? = null
    }

    /** By SOS id in its one spelling. */
    private val ownRuns = ConcurrentHashMap<String, OwnRun>()

    /** One run at a time per own SOS, a rotation's re-seal and an update alike. By SOS id in its one spelling. */
    private val ownLocks = ConcurrentHashMap<String, Mutex>()

    /** What a run of [runOwnSos] came to. */
    enum class OwnRunResult {
        /** Nothing was due, or it isn't this device's to re-seal. */
        NOTHING,

        /** Re-sealed as it stood: under the current key, or back to what this phone sealed. */
        RESEALED,

        /** Re-sealed with a new position or marker (the
         * update rule).
         */
        UPDATED,

        /** An update waits on a rotation its circle shows due: run again within 30 s. */
        DEFERRED,

        /** Due and not sealed now: no current key here, the hourly budget, or an answer that didn't land. */
        HELD,

        /** The alert ended, or this account left: nothing more. */
        ENDED,
    }

    /**
     * This account's own open SOS in [circleIds] (every circle when null), raised on this device,
     * each given one run ([runOwnSos]). Runs after a key import that moved a circle's version and after
     * a rotation this device landed ([keysRead]: those just read the keys), and at every refresh.
     */
    suspend fun resealOwn(circleIds: Set<String>? = null, keysRead: Boolean = false) {
        val me = sessionManager.getUserId() ?: return
        val circles = circleIds?.map(AccountIds::storeKey)?.toSet()
        val mine = sosAlertDao.getActive().filter {
            AccountIds.same(it.userId, me) && (circles == null || AccountIds.storeKey(it.circleId) in circles)
        }
        for (alert in mine) runCatching { runOwnSos(alert.id, keysRead = keysRead) }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Reseal of ${alert.id} failed: ${it.javaClass.simpleName}") }
    }

    /**
     * One run of "re-seal with the best position held" for this account's own open SOS [alertId],
     * raised on this device (PUT /sos/{id}/reseal). One at a time per alert. It re-seals when the
     * update rule has an update ([SosUpdateRule.next]: [fix], or a newer one
     * [SosPositionChooser.newestAfterPress] holds), when the alert isn't current here, or when what
     * the server serves isn't what this phone sealed:
     *  - what it seals is this phone's own record, or the update; the served position only where there
     *    is no record (a reinstall). Never "now" for the raise time: the ORIGINAL one, so a re-seal
     *    never extends the 24 h. The same raiser, device and device state;
     *  - before sealing, one bounded circle/key read unless the caller just made one ([keysRead]),
     *    then the current key or nothing; an update waits up to 2 minutes while its circle shows a
     *    rotation due;
     *  - at most [MAX_RESEALS_PER_HOUR] an hour; a 404 or 429 stops it quietly, and a 404 or an ending
     *    forgets the record.
     */
    suspend fun runOwnSos(alertId: String, fix: SosSeal.Point? = null, keysRead: Boolean = false): OwnRunResult {
        val me = sessionManager.getUserId() ?: return OwnRunResult.NOTHING
        // A look first, outside the lock: a run with nothing to seal reads no keys.
        val look = plan(alertId, me, fix)
        if (look is Planned.Done) return look.result
        // Outside the lock too: a key import this read makes re-seals the alert itself first (resealOwn
        // after the import), and that run takes the lock.
        if (!keysRead) readKeys()
        return ownLocks.getOrPut(AccountIds.storeKey(alertId)) { Mutex() }.withLock {
            when (val planned = plan(alertId, me, fix)) {
                is Planned.Done -> planned.result
                is Planned.Seal -> seal(planned)
            }
        }
    }

    private sealed class Planned {
        class Done(val result: OwnRunResult) : Planned()

        /**
         * [current] is what this phone sealed (or, with no record, what opened); [next] the update
         * rule's update, if one is due; [catchUp]: the served alert isn't current here, or isn't
         * [current].
         */
        class Seal(
            val alert: OwnAlert,
            val run: OwnRun,
            val current: SosSeal,
            val next: SosSeal?,
            val catchUp: Boolean,
        ) : Planned()
    }

    /**
     * What a run re-seals the alert from: as served, its raiser and device as they opened, and its
     * ORIGINAL raise time: this phone's record's, else as it opened.
     */
    private class OwnAlert(
        val dto: SosAlertResponse,
        val raiser: String,
        val device: String,
        val sealedAt: Long,
        /** The circle this device raised it in, or null when it can't name it: then no record is written. */
        val raisedIn: String?,
    )

    /** The plan of a run: the alert as served now, opened, and what (if anything) to
     * seal.
     */
    private suspend fun plan(alertId: String, me: String, fix: SosSeal.Point?): Planned {
        val response = tracksApi.getSosAlert(alertId)
        if (response.code() == 404) return ended(alertId)
        val dto = response.body()?.takeIf { response.isSuccessful && it.success }?.data
            ?: return Planned.Done(OwnRunResult.HELD)
        // Another alert than the one asked for is none of this run's business (as raiserToName).
        if (!AccountIds.same(dto.id, alertId)) return Planned.Done(OwnRunResult.NOTHING)
        if (dto.status in ENDED) return ended(alertId)
        if (dto.status !in OPEN) return Planned.Done(OwnRunResult.NOTHING)
        val circleId = dto.circleId
        val opened = withContext(openOn) { open(dto) }
        val raiser = opened.raiser ?: return Planned.Done(OwnRunResult.NOTHING)
        val sealedAt = opened.sealedAt ?: return Planned.Done(OwnRunResult.NOTHING)
        if (!AccountIds.same(raiser, me)) return Planned.Done(OwnRunResult.NOTHING)
        val device = runCatching { cryptoManager.decryptDataV2(circleId, dto.encryptedDeviceId.data(), "device_id") }.getOrNull()
        // Raised on this device: its other devices leave it to this one.
        if (device == null || device != sessionManager.getDeviceId()) return Planned.Done(OwnRunResult.NOTHING)
        val kept = ownSeals.get(alertId)
        val key = AccountIds.storeKey(alertId)
        // The circle this device raised it in, as it knows it: its record's, or this process's press's.
        // Never the served circle: a check-in of this phone's in another circle seals the same
        // "user_id", "device_id" and "timestamp" there, so a server can serve it as this alert.
        val raisedIn = kept?.circleId ?: ownRuns[key]?.circleId
        val record = kept?.seal
        val served = opened.point?.let { SosSeal.Point(it.latitude, it.longitude, it.timestamp, it.accuracy) }
            ?: opened.marker?.let { SosSeal.Marker(it) }
        // What this phone sealed; what the server serves only where it holds no record. The raise
        // time too: the one this phone sealed, never a served one that differs.
        val current = record ?: served ?: return Planned.Done(OwnRunResult.NOTHING)
        val raisedAt = kept?.raisedAt ?: sealedAt
        val run = ownRuns.getOrPut(key) { OwnRun(raisedAt) }
        val newest = listOfNotNull(fix, positions.newestAfterPress(run.pressAt)).maxByOrNull { it.takenAt }
        // No update into a circle this device can't name: with neither a record nor a press.
        val next = if (raisedIn == null) null else SosUpdateRule.next(
            current = current,
            fix = newest,
            cause = current == SosSeal.PENDING && positions.cause(),
            pressAt = run.pressAt,
            now = now(),
            updates = run.updates,
        )
        // Decoded against decoded, exactly: the record is what the codec carries.
        val differs = record != null && served != record
        val catchUp = !opened.current || differs || raisedAt != sealedAt
        if (next == null && !catchUp) {
            // An update that stopped being due waits no more: the next one gets its own 2 minutes.
            run.deferredSince = null
            return Planned.Done(OwnRunResult.NOTHING)
        }
        // Served in another circle than the one it was raised in: nothing, an update and a catch-up alike.
        if (raisedIn != null && !AccountIds.same(raisedIn, circleId)) {
            servedElsewhere()
            return Planned.Done(OwnRunResult.NOTHING)
        }
        if (next == null) run.deferredSince = null
        return Planned.Seal(OwnAlert(dto, raiser, device, raisedAt, raisedIn), run, current, next, catchUp)
    }

    /** Once a process, with no ids: an own SOS was served in another circle than this device raised it in. */
    private val servedElsewhereLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun servedElsewhere() {
        if (servedElsewhereLogged.compareAndSet(false, true)) Log.w(TAG, "An own SOS was served in another circle than it was raised in: not re-sealed")
    }

    /** The seal of a run: the key, the rotation defer and the budget, then the PUT; the record
     * follows what landed.
     */
    private suspend fun seal(planned: Planned.Seal): OwnRunResult {
        val circleId = planned.alert.dto.circleId
        // Behind here too: sealing again under an older key would help nobody. The rotation's own
        // run carries the best position once this device holds the key.
        if (!currentKeyGate.holdsCurrentKey(circleId)) return OwnRunResult.HELD
        val now = now()
        val next = planned.next ?: return put(planned, planned.current, update = false, now)
        // A rotation its circle shows due, and the re-seal it brings, go first; for at most 2
        // minutes from the first wait, as a flag nobody acts on mustn't hold the update back. What
        // this phone sealed already is re-sealed meanwhile where the alert needs it.
        val due = runCatching { circleDao.getById(circleId)?.keyRotationNeeded }.getOrNull() == true
        if (due) {
            val since = planned.run.deferredSince ?: now.also { planned.run.deferredSince = it }
            if (now - since < ROTATION_DEFER_MS) {
                if (planned.catchUp) put(planned, planned.current, update = false, now)
                return OwnRunResult.DEFERRED
            }
        }
        return put(planned, next, update = true, now)
    }

    /** The PUT of [payload] into the alert [planned] read, the budget allowing; the record follows what landed. */
    private suspend fun put(planned: Planned.Seal, payload: SosSeal, update: Boolean, now: Long): OwnRunResult {
        val dto = planned.alert.dto
        val circleId = dto.circleId
        val recent = reseals[dto.id].orEmpty().filter { now - it < HOUR_MS }
        if (recent.size >= MAX_RESEALS_PER_HOUR) return OwnRunResult.HELD
        reseals[dto.id] = recent + now
        val state = dto.encryptedDeviceState?.let {
            runCatching { cryptoManager.decryptDataV2(circleId, it.data(), "device_state") }.getOrNull()
        }
        val sealedLocation = sealedPosition(circleId, payload)
        val request = ResealSosRequest(
            encryptedLocation = sealedLocation.payload,
            encryptedDeviceState = state?.let { sealed(circleId, it, "device_state") },
            encryptedUserId = sealed(circleId, planned.alert.raiser, "user_id"),
            encryptedDeviceId = sealed(circleId, planned.alert.device, "device_id"),
            // The ORIGINAL raise time, never now.
            encryptedTimestamp = encryptTimestamp(circleId, planned.alert.sealedAt),
        )
        val answer = tracksApi.resealSos(dto.id, request)
        if (answer.code() == 404) {
            Log.i(TAG, "Reseal of ${dto.id}: not found (no route here yet, or it closed)")
            return ended(dto.id).result
        }
        val resealed = answer.body()?.takeIf { answer.isSuccessful && it.success }?.data ?: run {
            // A 429 among them: the server's own budget. Quietly, until the next run.
            Log.w(TAG, "Reseal of ${dto.id} answered ${answer.code()}")
            return OwnRunResult.HELD
        }
        // The record keeps the circle this device raised it in; none is made for one it can't name.
        planned.alert.raisedIn?.let { keepSealed(dto.id, it, planned.alert.sealedAt, sealedLocation) }
        if (update) {
            planned.run.updates++
            planned.run.deferredSince = null
        }
        val reopened = withContext(openOn) { open(resealed) }
        store(resealed, reopened, judge(resealed, reopened, now), sosAlertDao.getById(resealed.id))
        return if (update) OwnRunResult.UPDATED else OwnRunResult.RESEALED
    }

    /**
     * Whether a later fix could still change this account's own SOS [alertId] inside its window
     * ([SosUpdateRule.mayImprove], against what this phone sealed): the raising phone keeps looking
     * while it can.
     */
    fun ownUpdateDue(alertId: String): Boolean {
        val run = ownRuns[AccountIds.storeKey(alertId)] ?: return false
        val record = runCatching { ownSeals.get(alertId) }.getOrNull()?.seal ?: return false
        return SosUpdateRule.mayImprove(record, run.pressAt, now(), run.updates)
    }

    /** Whether this device holds [alertId] as open. */
    suspend fun isOpenHere(alertId: String): Boolean =
        runCatching { sosAlertDao.getById(alertId)?.status in OPEN }.getOrDefault(false)

    /** The alert ended, or this account left it: its record and window go. */
    private fun ended(alertId: String): Planned.Done {
        forgetOwn(alertId)
        return Planned.Done(OwnRunResult.ENDED)
    }

    private fun forgetOwn(alertId: String) {
        runCatching { ownSeals.remove(alertId) }
        ownRuns.remove(AccountIds.storeKey(alertId))
    }

    /**
     * One bounded circle/key read before a re-seal: the circle list (each circle's key version and
     * `key_rotation_needed`) and a key import where a version moved. One that doesn't answer in time
     * leaves the run to the key this device holds.
     */
    private suspend fun readKeys() {
        try {
            withTimeoutOrNull(KEY_READ_BUDGET_MS) { circleRepository.get().refreshCircles() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Key read before a reseal failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Close out alerts this device still holds as active but the server no
     * longer lists — acknowledged, resolved or cancelled by somebody else.
     *
     * [refreshActiveAlerts] only inserts, and the `sos_acknowledged` /
     * `sos_resolved` pushes just draw a notification, so without this a row
     * only leaves `active` on the one device that pressed the button.
     * Everywhere else the alert would stay active forever — on the map, a
     * permanent SOS icon and edge arrow.
     *
     * The per-alert GET gives us the real status instead of a guess. The set
     * is tiny (locally-active alerts that just disappeared), so the extra
     * requests are bounded by how many emergencies closed since the last
     * refresh. An ending is noted one way, and a post of it goes: off the
     * list, it isn't live.
     */
    private suspend fun reconcileStale(staleIds: Set<String>) {
        for (id in staleIds) {
            val dto = try {
                val response = tracksApi.getSosAlert(id)
                response.body()?.data?.takeIf { response.isSuccessful }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            sosAlertDao.setStatus(id, dto?.status ?: STATUS_CLOSED)
            if (dto != null && dto.status in ENDED) {
                val at = runCatching { open(dto).sealedAt }.getOrNull() ?: now()
                runCatching { marks.markEnded(dto.circleId, id, at) }
            }
            runCatching { alertHandler.get().cancelSos(id) }
        }
    }
}
