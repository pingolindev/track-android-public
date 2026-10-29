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

package com.pingolin.track.ui.places

import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.repository.PlaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a place alert may be homed.
 *
 * A place alert is a notification to a CIRCLE that one of its members arrived at
 * or left a place. Two rules follow from that:
 *
 *  - **It needs a circle.** The picker offers no circle-less "Just me" entry on
 *    a personal place: saving there would produce an alert whose only target is
 *    the user themselves — a mode the feature isn't for.
 *  - **A circle place is locked to its own circle.** Its alert routes and hashes
 *    under that circle's key, so any other circle yields match hashes the
 *    mover's crossings never match and the alert simply never fires.
 */
class PlaceAlertHomesTest {

    private fun circle(id: String, name: String = id) = CircleEntity(
        id = id,
        name = name,
        encryptedKey = null,
        createdAt = 0L,
        updatedAt = 0L,
        isOwner = true,
    )

    private fun place(circleId: String) = PlaceEntity(
        id = "place-1",
        circleId = circleId,
        name = "Home",
        address = null,
        latitude = 37.6391,
        longitude = -120.9969,
        radius = 150,
        icon = null,
        color = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private val personal = place(PlaceRepository.PERSONAL_CIRCLE_ID)

    // --- No "Just me" -------------------------------------------------------

    @Test
    fun `a personal place offers every circle and nothing else`() {
        val circles = listOf(circle("c1"), circle("c2"))

        val homes = eligibleAlertHomes(personal, circles)

        assertEquals(
            "the picker must offer exactly the user's circles — no circle-less entry",
            listOf("c1", "c2"),
            homes.map { it.id },
        )
    }

    @Test
    fun `a personal place with no circles has nowhere to home an alert`() {
        assertTrue(
            "with no circle there is nobody to watch and nobody to tell",
            eligibleAlertHomes(personal, emptyList()).isEmpty(),
        )
    }

    @Test
    fun `the personal-circle UUID counts as personal too`() {
        val circles = listOf(circle("c1"))
        val byUuid = place(PlaceRepository.PERSONAL_CIRCLE_UUID)

        assertEquals(listOf("c1"), eligibleAlertHomes(byUuid, circles).map { it.id })
    }

    // --- Circle places stay locked to their circle ---------------------------

    @Test
    fun `a circle place offers only its own circle`() {
        val circles = listOf(circle("c1"), circle("c2"), circle("c3"))

        val homes = eligibleAlertHomes(place("c2"), circles)

        assertEquals(listOf("c2"), homes.map { it.id })
    }

    @Test
    fun `a circle place whose circle is not on this device has no home`() {
        assertTrue(
            eligibleAlertHomes(place("c9"), listOf(circle("c1"))).isEmpty(),
        )
    }

    // --- Which home the picker opens on --------------------------------------

    @Test
    fun `a new alert on a personal place opens on the first circle`() {
        val homes = listOf(circle("c1"), circle("c2"))

        assertEquals(0, initialAlertHomeIndex(homes, personal, storedCircleId = null))
    }

    @Test
    fun `editing opens on the alert's own routing circle`() {
        val homes = listOf(circle("c1"), circle("c2"), circle("c3"))

        assertEquals(2, initialAlertHomeIndex(homes, personal, storedCircleId = "c3"))
    }

    @Test
    fun `a legacy circle-less alert falls back to the first circle`() {
        // Older stored alerts may have no routing circle at all. The picker has no
        // entry for that, so it must land somewhere valid rather than out of
        // bounds: the first circle, which is what saving re-homes it to.
        val homes = listOf(circle("c1"), circle("c2"))

        assertEquals(0, initialAlertHomeIndex(homes, personal, storedCircleId = null))
    }

    @Test
    fun `a stored circle that is no longer eligible falls back rather than going out of bounds`() {
        val homes = listOf(circle("c1"))

        val index = initialAlertHomeIndex(homes, personal, storedCircleId = "left-this-circle")

        assertEquals(0, index)
        assertTrue("index must address a real entry", index in homes.indices)
    }

    @Test
    fun `editing a circle place opens on that circle`() {
        val homes = listOf(circle("c2"))

        assertEquals(0, initialAlertHomeIndex(homes, place("c2"), storedCircleId = null))
    }

    // --- a place shared into a circle ---------------------------------------

    @Test
    fun `a place shared with you opens on the circle it came through`() {
        val homes = listOf(circle("c1"), circle("c2"), circle("c3"))

        assertEquals(1, initialAlertHomeIndex(homes, personal.copy(wrapViaCircle = "c2"), storedCircleId = null))
    }

    @Test
    fun `your own place opens on the first circle it is shared into`() {
        val homes = listOf(circle("c1"), circle("c2"), circle("c3"))

        assertEquals(2, initialAlertHomeIndex(homes, personal.copy(sharedCircles = "gone, c3,c2"), storedCircleId = null))
    }

    @Test
    fun `the alert's own circle still wins on edit`() {
        val homes = listOf(circle("c1"), circle("c2"), circle("c3"))

        assertEquals(0, initialAlertHomeIndex(homes, personal.copy(wrapViaCircle = "c2"), storedCircleId = "c1"))
    }
}
