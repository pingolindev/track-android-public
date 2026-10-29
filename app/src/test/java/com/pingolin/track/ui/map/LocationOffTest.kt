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

package com.pingolin.track.ui.map

import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import com.pingolin.track.util.LocationAccess
import com.pingolin.track.util.LocationAsk
import com.pingolin.track.util.locationAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Not now" at the first location question is not the end of it: the map says location is off
 * until it is on, a tap asks, and so does every action that shares it, creating a circle "and
 * share my location" among them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LocationOffTest {

    private fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    @Test
    fun `what the phone allows`() {
        assertEquals(LocationAccess.ON, LocationAccess.of(fine = true, coarse = true, servicesOn = true))
        assertEquals("Not now, or Don't allow", LocationAccess.NOT_ALLOWED, LocationAccess.of(fine = false, coarse = false, servicesOn = true))
        assertEquals(LocationAccess.NOT_ALLOWED, LocationAccess.of(fine = false, coarse = false, servicesOn = false))
        assertEquals("approximate only", LocationAccess.APPROXIMATE, LocationAccess.of(fine = false, coarse = true, servicesOn = true))
        assertEquals(LocationAccess.SERVICES_OFF, LocationAccess.of(fine = true, coarse = true, servicesOn = false))
    }

    @Test
    fun `the map says so for each way location is off, and nothing when it's on`() {
        assertNull(locationOffLine(LocationAccess.ON, hasCircles = true))
        assertNull(locationOffLine(LocationAccess.ON, hasCircles = false))
        assertEquals(R.string.map_location_not_allowed, locationOffLine(LocationAccess.NOT_ALLOWED, hasCircles = true))
        assertEquals(R.string.map_location_approximate, locationOffLine(LocationAccess.APPROXIMATE, hasCircles = true))
        assertEquals(R.string.map_location_services_off, locationOffLine(LocationAccess.SERVICES_OFF, hasCircles = true))
        // Someone in no circle ("Just Me") isn't told "your circles can't see where you are".
        assertEquals(R.string.map_location_not_allowed_no_circles, locationOffLine(LocationAccess.NOT_ALLOWED, hasCircles = false))
        assertEquals(R.string.map_location_approximate_no_circles, locationOffLine(LocationAccess.APPROXIMATE, hasCircles = false))
        assertEquals(R.string.map_location_services_off_no_circles, locationOffLine(LocationAccess.SERVICES_OFF, hasCircles = false))

        val context = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        assertEquals(
            "Location is off for Track, so your circles can't see where you are. Tap to turn it on.",
            context.getString(R.string.map_location_not_allowed),
        )
        assertEquals(
            "Precise location is off for Track, so your circles can't see where you are. Tap to turn it on.",
            context.getString(R.string.map_location_approximate),
        )
        assertEquals(
            "Location is off on this phone, so your circles can't see where you are. Tap to turn it on.",
            context.getString(R.string.map_location_services_off),
        )
        assertEquals("Location is off for Track. Tap to turn it on.", context.getString(R.string.map_location_not_allowed_no_circles))
        assertEquals("Precise location is off for Track. Tap to turn it on.", context.getString(R.string.map_location_approximate_no_circles))
        assertEquals("Location is off on this phone. Tap to turn it on.", context.getString(R.string.map_location_services_off_no_circles))
        assertEquals("Turn on location in Settings", context.getString(R.string.location_settings_title))
        assertEquals(
            "Android won't ask again. To share where you are, open Settings and allow location for Pingolin Track under Permissions → Location.",
            context.getString(R.string.location_settings_body),
        )
        assertEquals("Location is off on this phone", context.getString(R.string.location_services_off_title))
        // True with circles or none, as is the approximate-only dialog's.
        assertEquals(
            "Track can't get your location until Location is turned on.",
            context.getString(R.string.location_services_off_body),
        )
        assertEquals(
            "Pingolin Track has approximate location only, which is far too rough to share your position — nothing will be recorded." +
                "\n\nOpen Settings and turn on Use precise location under Permissions → Location.",
            context.getString(R.string.location_precise_required_body),
        )
    }

    @Test
    fun `the line is on the map, hidden until needed, and tapping it asks`() {
        // The map's layout can't be inflated here (MapLibre's view needs its native library): read it.
        val layout = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/res/layout/fragment_map.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val views = layout.getElementsByTagName("TextView")
        val line = (0 until views.length).map { views.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "id") == "@+id/tvLocationOff" }
        assertEquals("gone", line.getAttributeNS(android, "visibility"))
        assertEquals("true", line.getAttributeNS(android, "clickable"))
        assertEquals("@string/map_location_not_allowed", line.getAttributeNS(android, "text"))
        // Clear of the map's control column (16dp + 40dp buttons), which drew over its start.
        assertEquals("64dp", line.getAttributeNS(android, "layout_marginStart"))

        val map = source("ui/map/MapFragment.kt")
        assertTrue(map.contains("hasCircles = mainViewModel.circles.value.isNotEmpty(),"))
        assertTrue(map.contains("mainViewModel.circles.collect { bindLocationOff() }"))
        // Read again whenever the map comes back: Android's settings and its question change it.
        val resume = map.substring(map.indexOf("override fun onResume()"))
        assertTrue(resume.substring(0, resume.indexOf("\n    }\n")).contains("bindLocationOff()"))
        assertTrue(map.contains("val line = locationOffLine(\n            com.pingolin.track.util.LocationAccess.of(requireContext()),"))
        assertTrue(map.contains("b.tvLocationOff.setOnClickListener { askForLocation() }"))
        // Out of the way of the controls' labels while the action menu is open, back after.
        val expand = map.substring(map.indexOf("private fun expandFabs()")).let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(expand.contains("binding.tvLocationOff.visibility = View.GONE"))
        // And nothing brings it back while they're open: onResume, or the circles changing.
        assertTrue(map.contains("b.tvLocationOff.visibility = if (line != null && !isFabExpanded) View.VISIBLE else View.GONE"))
        val collapse = map.substring(map.indexOf("private fun collapseFabs()")).let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(collapse.contains("bindLocationOff()"))
        // Send location now and continuous tracking ask, instead of a snackbar with nothing to do,
        // and so does Recenter on my location: never a silent no-op.
        assertFalse(map.contains("Location permission required"))
        assertEquals(3, Regex("""\) != PackageManager.PERMISSION_GRANTED\s*\) \{\s*(// [^\n]*\s*)?askForLocation\(\)""").findAll(map).count())
    }

    @Test
    fun `agreeing to share in a circle asks for location where Track can't have it`() {
        val circles = source("ui/circles/CirclesFragment.kt")
        for (agreed in listOf(
            "viewModel.acceptInviteAfterConsent(invite.id, invite.circleId, invite.circleName, version)\n                    askForLocationAfterAgreeing()",
            "viewModel.createCircle(name, version)\n                    askForLocationAfterAgreeing()",
        )) {
            assertTrue(agreed, circles.contains(agreed))
        }
        assertTrue(circles.contains("if (access != com.pingolin.track.util.LocationAccess.ON) {"))
    }

    @Test
    fun `asking covers every way location is off, and Not now at the first question isn't the end`() {
        val activity = source("ui/main/MainActivity.kt")
        val ask = activity.substring(activity.indexOf("fun askForLocation()"))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(ask.contains("askedBefore = sessionManager.hasAskedForLocation(),"))
        assertTrue(ask.contains("androidExplains = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),"))
        assertTrue(ask.contains("LocationAsk.QUESTION -> showLocationRationale(onNotNow = {})"))
        // Where Android may not ask again: its own question alone, never straight to
        // Settings, which would say "Android won't ask again" where it would. The answer
        // offers Settings.
        assertTrue(ask.contains("LocationAsk.ANDROID -> locationPermissionRequest.launch(\n                arrayOf("))
        assertFalse(ask.contains("showLocationSettingsDialog()"))
        assertTrue(ask.contains("LocationAsk.PRECISE -> showPreciseLocationRequiredDialog()"))
        assertTrue(ask.contains("LocationAsk.SERVICES -> showLocationServicesDialog()"))
        // The first-run flag only stops the question at every opening; asking doesn't read it.
        assertFalse(ask.contains("hasDeclinedLocationRationale"))
        // Where Android won't ask any more, its settings page is the way, and the dialog says so.
        assertTrue(activity.contains(
            "!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {\n                // It was asked"
        ))
        assertFalse(activity.contains("Location permission is required for tracking"))
        // Android's question is recorded as asked when it is put, and when Android answered for itself.
        val question = activity.substring(activity.indexOf("private fun showLocationRationale("))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(question.contains("sessionManager.setAskedForLocation()\n                locationPermissionRequest.launch("))
        assertTrue(activity.contains("// It was asked, whatever this install recorded (installs from an older version\n                // have no such record).\n                sessionManager.setAskedForLocation()"))
    }

    @Test
    fun `where Android may not ask again, its own question goes alone - no promise that it will ask`() {
        // Never asked: Track's question, then Android's.
        assertEquals(LocationAsk.QUESTION, locationAsk(LocationAccess.NOT_ALLOWED, askedBefore = false, androidExplains = false))
        // Refused once: Android explains, and will ask again.
        assertEquals(LocationAsk.QUESTION, locationAsk(LocationAccess.NOT_ALLOWED, askedBefore = true, androidExplains = true))
        // Refused for good, or an "Only this time" that ended: Android may not ask, so "Android will ask
        // next" may be untrue. Android's own question goes alone.
        assertEquals(LocationAsk.ANDROID, locationAsk(LocationAccess.NOT_ALLOWED, askedBefore = true, androidExplains = false))
        assertEquals(LocationAsk.NOTHING, locationAsk(LocationAccess.ON, askedBefore = true, androidExplains = false))
        assertEquals(LocationAsk.PRECISE, locationAsk(LocationAccess.APPROXIMATE, askedBefore = true, androidExplains = false))
        assertEquals(LocationAsk.SERVICES, locationAsk(LocationAccess.SERVICES_OFF, askedBefore = false, androidExplains = false))
    }

    @Test
    fun `the line goes under a standing line that wraps into its place`() {
        assertEquals("nothing above", 300, locationOffTop(300, emptyList(), gapPx = 20))
        assertEquals("one line above, clear of the slot", 300, locationOffTop(300, listOf(150 to 40), gapPx = 20))
        assertEquals("a wrapped sync-error line reaching into the slot", 350, locationOffTop(300, listOf(150 to 40, 250 to 80), gapPx = 20))
        val map = source("ui/map/MapFragment.kt")
        assertTrue(map.contains("binding.tvSyncError.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeLocationOff() }"))
        assertTrue(map.contains("binding.tvGuardianManaged.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeLocationOff() }"))
        assertTrue(map.contains("val top = locationOffTop(locationOffSlotPx, above, gapPx = 8.dpToPx())"))
        // Until the insets come, its slot is the layout's own: a 0 would put the line over the
        // circle selector.
        val setup = map.substring(map.indexOf("private fun setupUI()"))
        assertTrue(setup.indexOf("locationOffSlotPx = (binding.tvLocationOff.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin") in 0 until setup.indexOf("addOnLayoutChangeListener"))
    }
}
