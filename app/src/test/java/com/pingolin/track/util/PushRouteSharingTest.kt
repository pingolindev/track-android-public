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

package com.pingolin.track.util

import com.pingolin.track.R
import com.pingolin.track.ui.map.mapSharingLine
import com.pingolin.track.util.PushNotificationManager.PushProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A phone with no notification app shares nothing: the server refuses it. Found on a real run of
 * the Google-free build against prod, 2026-11-07: prod held no location after minutes of fixes,
 * while the first-run dialog said sharing "keeps working either way", the circle said "You are
 * sharing" and the phone's own marker said "now".
 */
class PushRouteSharingTest {

    private fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()
    private val english = File("src/main/res/values/strings.xml").readText()

    @Test
    fun `a phone the server refused, with no route, is not sharing`() {
        assertTrue(PushRouteSharing.blocked(refused = true, hasRoute = false, provider = PushProvider.NONE))
    }

    @Test
    fun `nor is one with a distributor installed that is not connected yet`() {
        assertTrue(PushRouteSharing.blocked(refused = true, hasRoute = false, provider = PushProvider.UNIFIED_PUSH))
    }

    @Test
    fun `a route ends it at once`() {
        assertFalse(PushRouteSharing.blocked(refused = true, hasRoute = true, provider = PushProvider.UNIFIED_PUSH))
    }

    @Test
    fun `a phone waiting for its Firebase token is not told it is not sharing`() {
        assertFalse(PushRouteSharing.blocked(refused = true, hasRoute = false, provider = PushProvider.FCM))
    }

    @Test
    fun `a phone the server has not refused is told nothing`() {
        assertFalse(PushRouteSharing.blocked(refused = false, hasRoute = false, provider = PushProvider.NONE))
    }

    @Test
    fun `the map says so, after location itself`() {
        assertNull(mapSharingLine(locationOff = null, noPushRoute = false, hasCircles = true))
        assertEquals(R.string.map_not_sharing_no_push, mapSharingLine(null, noPushRoute = true, hasCircles = true))
        assertEquals(R.string.map_not_saving_no_push, mapSharingLine(null, noPushRoute = true, hasCircles = false))
        // No usable location comes first: there is nothing to share, and a tap asks for it.
        assertEquals(R.string.map_location_not_allowed, mapSharingLine(R.string.map_location_not_allowed, true, true))
    }

    @Test
    fun `every place that says the phone is sharing asks`() {
        assertTrue("the circle", "pushRouteSharing.isBlocked()" in source("ui/circles/CirclesFragment.kt"))
        val map = source("ui/map/MapFragment.kt")
        assertTrue("the map's line", "mapSharingLine(offLine, pushRouteSharing.isBlocked(), hasCircles)" in map)
        assertTrue("the marker's card", "if (pushRouteSharing.isBlocked()) getString(R.string.own_sharing_no_push)" in map)
        assertTrue("the map follows it", "sessionManager.pushSettingsChanged.collect { bindLocationOff() }" in map)
        assertTrue("the notification", "pushRouteSharing.isBlocked()" in source("service/LocationTrackingService.kt"))
    }

    @Test
    fun `nothing promises sharing goes on without a notification app`() {
        assertFalse("sharing keeps working" in source("ui/main/MainActivity.kt"))
        assertFalse("sharing keeps working" in english)
        assertTrue("can\\'t share your location until one is connected" in english)
    }

    @Test
    fun `the way there ends with opening the delivery app again`() {
        // Android won't let ntfy start listening from the background, which is where being chosen
        // leaves it, so until it is opened again ntfy.sh answers 507 and no alert arrives. Measured
        // on a Google-free emulator, 2026-11-07: the push failed, and arrived once ntfy was opened.
        assertTrue("then open ntfy once more so it can listen for alerts" in english)
    }

    @Test
    fun `signing out forgets it, as it belongs to the session`() {
        val session = source("util/SessionManager.kt")
        val clear = session.substringAfter("fun clearSession()").substringBefore(".commit()")
        assertTrue(".remove(KEY_PUSH_ROUTE_REFUSED)" in clear || ".remove(KEY_PUSH_ROUTE_REFUSED)" in session.substringAfter("fun clearSession()").take(1800))
    }
}
