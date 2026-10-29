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

package com.pingolin.track.testutil

import com.pingolin.track.data.local.dao.SosAlertDao
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.SosAlertResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KeyStoreLoad
import uniffi.pingolin_mobile.KeyStoreStatus
import uniffi.pingolin_mobile.Location
import uniffi.pingolin_mobile.OpenedLocation
import uniffi.pingolin_mobile.OpenedText

/** The sos_alerts table, in memory, with the queries' own filters. */
class FakeSosAlertDao : SosAlertDao {
    val rows = MutableStateFlow<Map<String, SosAlertEntity>>(emptyMap())
    val deleted = mutableListOf<String>()

    override fun observeActive(graceCutoff: Long): Flow<List<SosAlertEntity>> = rows.map { all ->
        all.values.filter { it.status == "active" && (!it.dismissedByMe || (it.dismissedAt ?: 0L) > graceCutoff) }
    }
    override fun observeActiveMappable(): Flow<List<SosAlertEntity>> = rows.map { all ->
        all.values.filter { it.status == "active" && !it.dismissedByMe && !it.acknowledgedByMe && it.latitude != null && it.longitude != null }
    }
    override fun observeByCircle(circleId: String, limit: Int): Flow<List<SosAlertEntity>> =
        rows.map { all -> all.values.filter { it.circleId == circleId }.take(limit) }
    override suspend fun countActiveRaisedBy(userId: String, circleId: String): Int =
        rows.value.values.count { it.userId == userId && it.circleId == circleId && it.status == "active" }
    override suspend fun getById(id: String): SosAlertEntity? = rows.value[id]
    override suspend fun getActiveIds(): List<String> = rows.value.values.filter { it.status == "active" }.map { it.id }
    override suspend fun getActive(): List<SosAlertEntity> = rows.value.values.filter { it.status == "active" }
    override suspend fun deleteById(id: String) {
        deleted += id
        rows.value = rows.value - id
    }
    override suspend fun setStatus(id: String, status: String) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(status = status)) }
    }
    override suspend fun insert(alert: SosAlertEntity) {
        rows.value = rows.value + (alert.id to alert)
    }
    override suspend fun update(alert: SosAlertEntity) {
        if (alert.id in rows.value) rows.value = rows.value + (alert.id to alert)
    }
}

/**
 * A circle key the tests can rotate: a sealed field is "{text}@{version}", and it opens only when
 * [held] has that version; [current] is the version this device seals under. "{lat},{lng}@{v}" for
 * a position, or "{lat},{lng},{taken},{accuracy}@{v}" with when it was taken and how close (a position
 * with neither seals in the short form). A position is sealed as the SDK's location codec carries it:
 * coordinates in 1e-7 degree steps, the accuracy as an f32. Every seal is recorded in [sealedTexts],
 * by field tag, and every sealed position whole, as given, in [sealedPositions].
 */
class FakeSosKeys(var current: Int = 2, val held: MutableSet<Int> = mutableSetOf(1, 2)) {
    val sealedTexts = mutableListOf<Pair<String, String>>()
    val sealedPositions = mutableListOf<Location>()
    var behind = false
    var load = KeyStoreLoad.LOADED
    var entriesUnreadable = false

    private fun parse(ciphertext: String): Pair<String, Int> {
        val at = ciphertext.lastIndexOf('@')
        require(at > 0) { "doesn't open" }
        val version = ciphertext.substring(at + 1).toInt()
        require(version in held) { "version $version not held" }
        return ciphertext.substring(0, at) to version
    }

    fun install(crypto: CryptoManager) {
        every { crypto.decryptDataV2Opened(any(), any(), any()) } answers {
            val (text, version) = parse(arg<EncryptedData>(1).ciphertext)
            OpenedText(text, version)
        }
        every { crypto.decryptDataV2(any(), any(), any()) } answers { parse(arg<EncryptedData>(1).ciphertext).first }
        every { crypto.decryptLocationOpened(any(), any()) } answers {
            val (text, version) = parse(arg<EncryptedData>(1).ciphertext)
            val parts = text.split(",")
            val taken = parts.getOrNull(2)?.toLong() ?: 0L
            val accuracy = parts.getOrNull(3)?.toDoubleOrNull()
            OpenedLocation(Location(parts[0].toDouble(), parts[1].toDouble(), null, null, null, accuracy, taken, null, null), version)
        }
        every { crypto.isCurrentKeyVersion(any(), any()) } answers { arg<Int>(1) == current }
        every { crypto.getActiveVersion(any()) } answers { current }
        every { crypto.isBehind(any()) } answers { behind }
        every { crypto.keyStoreStatus() } answers { KeyStoreStatus(load, false, entriesUnreadable) }
        every { crypto.encryptDataV2(any(), any(), any()) } answers {
            val text = arg<String>(1)
            sealedTexts += arg<String>(2) to text
            EncryptedData(nonce = "resealed-${arg<String>(2)}-${sealedTexts.size}", ciphertext = "$text@$current")
        }
        every { crypto.encryptLocation(any(), any()) } answers {
            val point = arg<Location>(1)
            sealedTexts += "location" to "${point.latitude},${point.longitude}"
            sealedPositions += point
            val taken = if (point.timestamp == 0L && point.accuracy == null) "" else ",${point.timestamp},${point.accuracy?.let(::f32)}"
            EncryptedData(
                nonce = "resealed-location-${sealedTexts.size}",
                ciphertext = "${packed(point.latitude)},${packed(point.longitude)}$taken@$current",
            )
        }
    }

    companion object {
        /** [degrees] as the location codec carries them: i32 of × 1e7, rounded half away from zero (`f64::round`), read back ÷ 1e7. */
        fun packed(degrees: Double): Double {
            val scaled = degrees * 1e7
            val e7 = if (scaled < 0) -Math.floor(-scaled + 0.5) else Math.floor(scaled + 0.5)
            return e7.toLong().toDouble() / 1e7
        }

        /** An accuracy as the codec carries it: an f32. */
        fun f32(value: Double): Double = value.toFloat().toDouble()

        /** A field sealed under [version]: "{text}@{version}", with a nonce of its own ([tag] keeps payloads apart). */
        fun field(text: String, version: Int, tag: String = "a") = EncryptedPayload(nonce = "n-$tag-$text-$version", ciphertext = "$text@$version")

        /** An SOS as the server serves it, every field sealed under [version] unless given its own. */
        fun sos(
            id: String,
            circleId: String,
            raiser: String,
            sealedAt: Long,
            version: Int = 2,
            status: String = "active",
            device: String = "dev-raiser",
            idVersion: Int = version,
            timeVersion: Int = version,
            placeVersion: Int = version,
            tag: String = "a",
            acknowledgedByMe: Boolean = false,
        ) = SosAlertResponse(
            id = id,
            circleId = circleId,
            encryptedLocation = field("37.5,-122.25", placeVersion, tag),
            encryptedDeviceState = null,
            status = status,
            encryptedUserId = field(raiser, idVersion, tag),
            encryptedDeviceId = field(device, version, tag),
            dayBucket = (sealedAt / 86_400_000L).toInt(),
            encryptedTimestamp = field(sealedAt.toString(), timeVersion, tag),
            resolvedDayBucket = null,
            acknowledgedByMe = acknowledgedByMe,
        )
    }
}

/** What this phone sealed into its own SOS, over [prefs]: another store over the same prefs is a restart. */
fun ownSosSeals(owner: String, prefs: InMemoryPrefs = InMemoryPrefs()) =
    com.pingolin.track.domain.sos.SecurePrefsOwnSosSeals(mockk(relaxed = true), { owner }, { prefs })

/** A chooser holding nothing: no fix, no last-known position, no pause or bubble read; [cause] as given. */
fun quietPositions(
    session: com.pingolin.track.util.SessionManager,
    newest: com.pingolin.track.location.NewestOwnFix = com.pingolin.track.location.NewestOwnFix(session),
    cause: () -> Boolean = { false },
) = com.pingolin.track.domain.sos.SosPositionChooser(
    newest,
    mockk<com.pingolin.track.location.LocationProvider> { coEvery { lastLocation() } returns null },
    com.pingolin.track.util.PauseIntervals.inMemory(),
    com.pingolin.track.util.ApproximateIntervals.inMemory(),
    cause,
)
