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

import com.google.gson.GsonBuilder
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.data.local.entity.DestinationEntity.Ownership
import com.pingolin.track.data.remote.dto.DestinationResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A destination is this account's only when the id sealed by its creator AND the server's own
 * creator field (`user_id`) both name this account. Any member can seal any id; the server alone
 * can say anything. Neither half alone makes it this account's list entry, its to cancel, arrive
 * at or delete, "Your destination" on the map, or its AUTOMATIC arrival.
 */
class DestinationOwnershipTest {

    private val me = "b1000000-0000-4000-8000-0000000000e1"
    private val other = "b1000000-0000-4000-8000-0000000000e2"

    private fun destination(id: String = "d-1", sealed: String, creator: String?, status: String = "active") =
        DestinationEntity(
            id = id, userId = sealed, circleId = "b1000000-0000-4000-8000-0000000000c1", destName = "Home",
            destLatitude = 52.5, destLongitude = 13.4, etaDayBucket = null, status = status,
            startedDayBucket = 20_000, createdDayBucket = 20_000, updatedDayBucket = 20_000, creatorId = creator,
        )

    @Test
    fun `mine needs both halves, in any spelling`() {
        assertTrue(destination(sealed = me, creator = me).isMine(me))
        assertTrue(destination(sealed = me.uppercase(), creator = me.replace("-", "")).isMine(me))
        assertTrue(destination(sealed = me, creator = me).isMine(me.uppercase()))
        assertFalse("sealed as me by a member", destination(sealed = me, creator = other).isMine(me))
        assertFalse("the server alone", destination(sealed = other, creator = me).isMine(me))
        assertFalse("no creator field: fail closed", destination(sealed = me, creator = null).isMine(me))
        assertFalse(destination(sealed = other, creator = other).isMine(me))
        assertFalse(destination(sealed = me, creator = me).isMine(null))
        assertFalse("no UUID is anyone", destination(sealed = "self", creator = "self").isMine("self"))
    }

    @Test
    fun `the no answer is kept apart from the no`() {
        assertEquals(Ownership.MINE, destination(sealed = me, creator = me.uppercase()).ownership(me))
        assertEquals("sealed as me, creator not known yet", Ownership.UNKNOWN, destination(sealed = me, creator = null).ownership(me))
        assertEquals(Ownership.NOT_MINE, destination(sealed = me, creator = other).ownership(me))
        assertEquals("sealed as someone else: never mine", Ownership.NOT_MINE, destination(sealed = other, creator = null).ownership(me))
        assertEquals(Ownership.NOT_MINE, destination(sealed = other, creator = me).ownership(me))
        assertEquals(Ownership.NOT_MINE, destination(sealed = me, creator = me).ownership(null))
    }

    @Test
    fun `the automatic arrival takes only this account's own live destinations`() {
        val all = listOf(
            destination("mine", sealed = me, creator = me),
            destination("sealed-as-me", sealed = me, creator = other),
            destination("server-says-me", sealed = other, creator = me),
            destination("theirs", sealed = other, creator = other),
            destination("not-known-yet", sealed = me, creator = null),
            destination("mine-over", sealed = me, creator = me, status = DestinationRepository.STATUS_ARRIVED),
        )
        assertEquals(listOf("mine"), DestinationRepository.ownActive(all, me).map { it.id })
        assertEquals(emptyList<String>(), DestinationRepository.ownActive(all, null).map { it.id })
    }

    @Test
    fun `the answer carries the server's creator, and an older server's still decodes`() {
        val gson = GsonBuilder().create()
        fun decode(userId: String?): DestinationResponse {
            val creator = if (userId == null) "" else """"user_id": "$userId","""
            return gson.fromJson(
                """{"id": "d-1", $creator "encrypted_user_id": {"nonce": "00", "ciphertext": "00"}, "circle_id": "c",
                   "encrypted_destination": {"nonce": "00", "ciphertext": "00"}, "notify_arrival": true, "status": "active",
                   "started_day_bucket": 20000, "created_day_bucket": 20000, "updated_day_bucket": 20000}""",
                DestinationResponse::class.java,
            )
        }
        assertEquals(me, decode(me).userId)
        assertNull("an older server still decodes", decode(null).userId)
    }

    private fun source(path: String) = java.io.File("src/main/java/com/pingolin/track/$path").readText()

    /** Every place that decides it asks the one rule. */
    @Test
    fun `every site asks the one rule`() {
        val repository = source("domain/repository/DestinationRepository.kt")
        assertTrue("the list", repository.contains("return destinationDao.observeAll().map { all -> all.filter { it.isMine(userId) } }"))
        assertTrue("the free limit's own", repository.contains("return ownActive(destinationDao.getByStatus(STATUS_ACTIVE), userId)"))
        assertTrue(repository.contains("active.filter { it.status == STATUS_ACTIVE && it.isMine(me) }"))
        assertEquals("the arrival, the cancel (update) and the delete", 3, repository.split("            requireMine(destinationId)\n").size - 1)
        assertEquals("both writers carry the server's creator", 2, repository.split("creatorId = dto.userId,").size - 1)
        val service = source("service/LocationTrackingService.kt")
        assertTrue("the automatic arrival", service.contains("DestinationRepository.ownActive(destinationDao.getByStatus(\"active\"), userId)"))
        assertFalse(service.contains(".filter { it.userId == userId }\n            if (mine.isEmpty()) return"))
        val list = source("ui/destinations/DestinationsViewModel.kt")
        assertTrue(list.contains("isMine = d.isMine(me),"))
        assertTrue(list.contains("creatorName = if (d.isMine(me)) null else creatorNameFor(d),"))
        assertFalse(list.contains("d.userId == me"))
        val map = source("ui/map/MapFragment.kt")
        assertTrue("the label", map.contains("val name = if (dest.isMine(selfUserId)) {"))
        assertTrue("the badge", map.contains("destinations.any { it.status == \"active\" && !it.isMine(selfId) }"))
        assertFalse(map.contains("dest.userId == selfUserId"))
        assertFalse(map.contains("it.userId != selfId"))
        val entry = source("data/remote/api/CanonicalIdsTrackApi.kt")
        assertTrue("the creator in its one spelling", entry.contains("val creator = AccountIds.canonical(raw) ?: return refusals.refuse()"))
        assertTrue("the upgrade keeps the database", source("di/AppModule.kt").contains("TrackDatabase.MIGRATION_39_40"))
        val daos = source("data/local/dao/Daos.kt")
        assertFalse("no raw compare of the sealed id", daos.contains("SELECT * FROM destinations WHERE userId = :userId"))
    }
}
