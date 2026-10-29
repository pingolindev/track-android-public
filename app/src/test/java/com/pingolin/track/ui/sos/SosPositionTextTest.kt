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

package com.pingolin.track.ui.sos

import android.app.Application
import android.text.format.DateUtils
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.domain.sos.SosPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The words an SOS's position gets, on the Safety row, the map's label and its sheet, and the
 * raiser's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SosPositionTextTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val now = System.currentTimeMillis()
    private val min = 60_000L

    private fun alert(
        fixAt: Long? = null,
        accuracy: Double? = null,
        state: String? = null,
        asked: Long = now - 5 * min,
    ) = SosAlertEntity(
        id = "0505abcd-0000-4000-8000-00000000abcd", circleId = "c", userId = "u", status = "active",
        latitude = if (state == null) 37.33 else null, longitude = if (state == null) -122.0 else null,
        acknowledgedBy = "", createdAt = asked, resolvedAt = null,
        fixAt = fixAt, accuracy = accuracy, positionState = state,
    )

    private fun time(at: Long) = SosPositionText.time(context, at)

    /** The row's words for [alert] as judged now. */
    private fun status(alert: SosAlertEntity, mine: Boolean) = SosPositionText.status(context, SosPosition.of(alert, now), mine)

    @Test
    fun `receivers - the markers, the fallback 12 minutes after the raise, last known and updated`() {
        assertEquals("Sending current location", status(alert(state = SosAlertEntity.POSITION_PENDING), false))
        assertEquals(
            "a raiser's phone that died: unavailable",
            "Location unavailable",
            status(alert(state = SosAlertEntity.POSITION_PENDING, asked = now - 12 * min), false),
        )
        assertEquals("Location unavailable", status(alert(state = SosAlertEntity.POSITION_UNAVAILABLE), false))
        val asked = now - 5 * min
        assertEquals(
            "Last known location · ${time(asked - 30 * min)}",
            status(alert(fixAt = asked - 30 * min, asked = asked), false),
        )
        assertEquals(
            "Location updated · ${time(asked + 2 * min)}",
            status(alert(fixAt = asked + 2 * min, asked = asked), false),
        )
        assertNull("taken as it was sent: the default words", status(alert(fixAt = asked, asked = asked), false))
        assertNull("a row from before", status(alert(fixAt = null), false))
    }

    @Test
    fun `the raiser's own card says what went out`() {
        assertEquals("Sending your current location", status(alert(state = SosAlertEntity.POSITION_PENDING), true))
        assertEquals("Sent without your location", status(alert(state = SosAlertEntity.POSITION_UNAVAILABLE), true))
        val asked = now - 5 * min
        assertEquals(
            "Sent with your location from ${time(asked - 30 * min)}",
            status(alert(fixAt = asked - 30 * min, asked = asked), true),
        )
        assertEquals(
            "Your location was added at ${time(asked + 2 * min)}",
            status(alert(fixAt = asked + 2 * min, asked = asked), true),
        )
    }

    @Test
    fun `an area says how far, in the user's units - a pin says nothing`() {
        val mile = alert(fixAt = now - 30 * min, accuracy = 1609.344)
        assertEquals("Within about 1.0 mi", SosPositionText.within(context, mile, useMiles = true))
        assertEquals("Within about 1.6 km", SosPositionText.within(context, mile, useMiles = false))
        assertEquals("Within about 300 m", SosPositionText.within(context, alert(fixAt = now, accuracy = 300.0), useMiles = false))
        assertNull(SosPositionText.within(context, alert(fixAt = now, accuracy = 100.0), useMiles = false))
        assertEquals(
            "Last known location · ${time(now - 30 * min)}\nWithin about 1.0 mi",
            mile.copy(createdAt = now).let { SosPositionText.lines(context, it, SosPosition.of(it, now), mine = false, useMiles = true) },
        )
    }

    @Test
    fun `the map's label - last known, updated, an area's size, else sent from here`() {
        val asked = now - 5 * min
        val sentFromHere = { "Sent from here · 5m" }
        assertEquals("Last known location", SosPositionText.mapLabel(context, alert(fixAt = asked - 30 * min, asked = asked), false, now, sentFromHere))
        assertEquals(
            "Location updated · ${time(asked + 2 * min)}",
            SosPositionText.mapLabel(context, alert(fixAt = asked + 2 * min, asked = asked), false, now, sentFromHere),
        )
        assertEquals("Within about 300 m", SosPositionText.mapLabel(context, alert(fixAt = asked, accuracy = 300.0, asked = asked), false, now, sentFromHere))
        assertEquals("Sent from here · 5m", SosPositionText.mapLabel(context, alert(fixAt = asked, accuracy = 12.0, asked = asked), false, now, sentFromHere))
    }

    @Test
    fun `an area's label - last known or updated when it says so, then how far`() {
        val asked = now - 5 * min
        assertEquals(
            "Last known location\nWithin about 1.0 mi",
            SosPositionText.areaLabel(context, alert(fixAt = asked - 30 * min, accuracy = 1609.344, asked = asked), useMiles = true, now = now),
        )
        assertEquals(
            "Location updated · ${time(asked + 2 * min)}\nWithin about 300 m",
            SosPositionText.areaLabel(context, alert(fixAt = asked + 2 * min, accuracy = 300.0, asked = asked), useMiles = false, now = now),
        )
        assertEquals("taken as sent", "Within about 300 m",
            SosPositionText.areaLabel(context, alert(fixAt = asked, accuracy = 300.0, asked = asked), useMiles = false, now = now))
    }

    @Test
    fun `the map draws an area with its label at the centre - no disc, no pulse, nothing that reads as an exact pin`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val markers = map.substring(map.indexOf("private fun updateSosMarkers("), map.indexOf("private fun sosMemberIds()"))
        val area = markers.substring(markers.indexOf("if (com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) != null) {"), markers.indexOf("} else {"))
        assertTrue("the label, not the disc", area.contains("addStringProperty(\"iconId\", getOrCreateSosAreaLabel(style, label))"))
        assertFalse(area.contains("getOrCreateSosIcon("))
        assertTrue(area.contains("addBooleanProperty(\"area\", true)"))
        assertTrue("the area itself", markers.contains("ApproximateArea.featureJson(MARKER_RED"))
        val pulse = map.substring(map.indexOf("CircleLayer(SOS_PULSE_LAYER_ID, SOS_SOURCE_ID)"), map.indexOf("createSosIcon(style)"))
        assertTrue("no pulse under an area's label", pulse.contains(".withFilter(Expression.not(Expression.has(\"area\")))"))
        val label = map.substring(map.indexOf("private fun getOrCreateSosAreaLabel("), map.indexOf("private fun drawWarningTriangle("))
        assertFalse("no disc in an area's label", label.contains("drawCircle("))
    }

    @Test
    fun `a time today is the clock time - another day's has its date`() {
        val earlier = now - 3 * 24 * 60 * min
        assertEquals(DateUtils.formatDateTime(context, earlier, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH), time(earlier))
        assertTrue(time(earlier).length > DateUtils.formatDateTime(context, earlier, DateUtils.FORMAT_SHOW_TIME).length)
    }

    @Test
    fun `the map's sheet shows an area no address and no coordinates, and looks none up`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val sheet = map.substring(map.indexOf("private fun showSosBottomSheet("), map.indexOf("private fun showPlaceBottomSheet("))
        assertTrue(sheet.contains("val area = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) != null"))
        assertTrue("the coordinates", sheet.contains("text = coordinates(lat, lng)\n            visibility = if (area) View.GONE else View.VISIBLE"))
        assertTrue("the address", sheet.contains("tvAddress.visibility = if (area) View.GONE else View.VISIBLE"))
        assertTrue("no lookup", sheet.contains("if (!area) viewLifecycleOwner.lifecycleScope.launch {"))
    }

    @Test
    fun `an area is fitted whole on the map, wherever the map is pointed at it`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val camera = map.substring(map.indexOf("private fun cameraFor("), map.indexOf("private fun showSosBottomSheet("))
        assertTrue(camera.contains("val radius = req.radiusMeters ?: return"))
        assertTrue(camera.contains("CameraUpdateFactory.newLatLngBounds("))
        assertEquals("a pending zoom and a live one", 2, Regex("animateCamera\\(cameraFor\\((pendingZoom|req)\\), 1000\\)").findAll(map).count())
        assertTrue("the edge arrow", map.contains("radiusMeters = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert)"))
        val safety = File("src/main/java/com/pingolin/track/ui/sos/SosFragment.kt").readText()
        assertTrue("Show on map", safety.contains("mainViewModel.requestMapZoom(lat, lng, radiusMeters = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert))"))
        val main = File("src/main/java/com/pingolin/track/ui/main/MainViewModel.kt").readText()
        assertTrue("a tapped notification", main.contains("requestMapZoom(lat, lng, radiusMeters = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert))"))
    }

    @Test
    fun `a tapped SOS with no position opens the Safety tab, where its row says why`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainViewModel.kt").readText()
        val zoom = main.substring(main.indexOf("suspend fun zoomToSos("), main.indexOf("private var initialized"))
        assertTrue("a stored marker row waits on nothing", zoom.indexOf("if (alert != null && alert.positionState != null) return false") in 0 until zoom.indexOf("sosRepository.refreshActiveAlerts()"))
        assertTrue(zoom.contains("val lat = alert?.latitude ?: return false"))
        assertTrue(zoom.contains("val lng = alert.longitude ?: return false"))
        for (path in listOf("src/main/java/com/pingolin/track/ui/main/MainActivity.kt", "src/main/java/com/pingolin/track/ui/notifications/NotificationsFragment.kt")) {
            val src = File(path).readText()
            assertTrue(path, Regex("if \\((viewModel|mainViewModel)\\.zoomToSos\\(sosId\\)\\) [^\\n]+\\n\\s+else navController\\.navigate\\(R\\.id\\.sosFragment\\)").containsMatchIn(src))
        }
    }

    @Test
    fun `the sheet follows an update - it opens again on the new position, or goes with the alert`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val sheet = map.substring(map.indexOf("private fun showSosBottomSheet("), map.indexOf("private fun showPlaceBottomSheet("))
        assertTrue(sheet.contains("mainViewModel.mappableSosAlerts.collect { alerts ->"))
        assertTrue(sheet.contains("now.latitude != alert.latitude || now.longitude != alert.longitude || now.fixAt != alert.fixAt"))
        assertTrue(sheet.contains("showSosBottomSheet(now)"))
        assertTrue(sheet.contains("dialog.setOnDismissListener { follow.cancel() }"))
        assertTrue("an area says no \"Sent from here\"", sheet.contains("?: getString(R.string.sos_location_snapshot).takeUnless { area },"))
    }

    @Test
    fun `no SOS text says its location does not update`() {
        assertEquals(
            "Sent from here. This location was recorded when the notification was sent.",
            context.getString(R.string.sos_location_snapshot),
        )
        val strings = File("src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("does not update"))
    }
}
