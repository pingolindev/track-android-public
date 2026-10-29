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

package com.pingolin.track.ui.circles

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * When a circle's privacy settings can't be read, the dialog must not show a form of
 * defaults: every write round-trips the whole row, so changing anything on such a form would
 * lift a pause, end approximate sharing and re-allow alerts on this person. The controls
 * show only once THAT circle's settings have loaded; a failed read says so, with Try again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PrivacyFormTest {

    private fun settings(circleId: String) = PrivacySettingsResponse(
        circleId = circleId, shareRealtime = true, shareHistory = true, shareBattery = true,
        pausedUntil = null, primaryDeviceId = null,
    )

    @Test
    fun `the form is ready only with this circle's own settings`() {
        assertEquals(PrivacyForm.READY, privacyForm("c-1", settings("c-1"), null))
        // Another circle's settings are never this circle's form.
        assertEquals(PrivacyForm.LOADING, privacyForm("c-1", settings("c-2"), null))
        assertEquals(PrivacyForm.LOADING, privacyForm("c-1", null, null))
        assertEquals(PrivacyForm.FAILED, privacyForm("c-1", null, "c-1"))
        assertEquals(PrivacyForm.FAILED, privacyForm("c-1", settings("c-2"), "c-1"))
        // Another circle's failure says nothing about this one.
        assertEquals(PrivacyForm.LOADING, privacyForm("c-1", null, "c-2"))
    }

    private fun inflated(): View {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        return android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_circle_privacy, null)
    }

    private fun View.isInside(container: View): Boolean {
        var p = parent
        while (p is View) {
            if (p === container) return true
            p = p.parent
        }
        return false
    }

    @Test
    fun `before anything loads the dialog shows no controls, only that it is loading`() {
        val root = inflated()
        val controls = root.findViewById<ViewGroup>(R.id.layoutPrivacyControls)
        val pause = root.findViewById<ViewGroup>(R.id.layoutPauseControls)
        val state = root.findViewById<View>(R.id.layoutPrivacyLoadState)

        assertEquals(View.GONE, controls.visibility)
        assertEquals(View.GONE, pause.visibility)
        assertEquals(View.VISIBLE, state.visibility)
        assertEquals("Loading your settings for this circle…", root.findViewById<TextView>(R.id.tvPrivacyLoadState).text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.btnPrivacyRetry).visibility)

        // Every control that writes the circle's row is inside the hidden form.
        for (id in listOf(
            R.id.switchShareRealtime, R.id.switchShareHistory, R.id.switchShareBattery, R.id.switchBubbleMode,
            R.id.sliderBubbleRadius, R.id.switchShowAllDevices, R.id.switchAllowAlertsOnMe,
        )) {
            assertTrue(root.resources.getResourceEntryName(id), root.findViewById<View>(id).isInside(controls))
        }
        for (id in listOf(R.id.layoutPauseStatus, R.id.btnResume, R.id.btnPauseSharing)) {
            assertTrue(root.resources.getResourceEntryName(id), root.findViewById<View>(id).isInside(pause))
        }
        // "Default on map" is this phone's own choice, not the circle's row: it stays usable.
        assertFalse(root.findViewById<View>(R.id.switchDefaultCircle).isInside(controls))
    }

    @Test
    fun `the dialog shows the form only when ready, and a failed read offers Try again`() {
        val fragment = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(fragment.contains("val form = privacyForm(circle.id, viewModel.privacySettings.value, viewModel.privacyLoadFailed.value)"))
        assertTrue(fragment.contains("val ready = form == PrivacyForm.READY"))
        assertTrue(fragment.contains("dialogBinding.layoutPrivacyControls.visibility = if (ready) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("dialogBinding.layoutPauseControls.visibility = if (ready) View.VISIBLE else View.GONE"))
        // Try again reads both again: the settings, and the consent row's state that failed with them.
        assertTrue(fragment.contains(
            "dialogBinding.btnPrivacyRetry.setOnClickListener {\n" +
                "            viewModel.loadPrivacySettings(circle.id)\n" +
                "            viewModel.loadLocationConsent(circle.id)\n" +
                "        }"
        ))
        assertTrue(fragment.contains("kotlinx.coroutines.flow.combine(viewModel.privacySettings, viewModel.privacyLoadFailed)"))

        val context = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        assertEquals(
            "Couldn't load your settings for this circle, so they can't be changed here right now.",
            context.getString(R.string.privacy_settings_load_failed),
        )
        assertEquals("Try again", context.getString(R.string.privacy_settings_retry))
    }

    @Test
    fun `nothing in the dialog says whether the location is shared until this circle's settings load`() {
        val root = inflated()
        assertEquals(View.GONE, root.findViewById<View>(R.id.layoutLocationConsent).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.tvOwnSharingStatus).visibility)

        val fragment = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(fragment.contains(
            "dialogBinding.layoutLocationConsent.visibility = if (consentGranted && settings != null) View.VISIBLE else View.GONE"
        ))
        assertFalse("unknown isn't sharing", fragment.contains("?: SharingStatus.On"))
        // Said again whenever the form changes, so a copy dropped after a write hides it too.
        val form = fragment.substring(
            fragment.indexOf("fun renderPrivacyForm()"),
            fragment.indexOf("dialogBinding.btnPrivacyRetry.setOnClickListener"),
        )
        assertTrue(form.contains("renderOwnSharing()"))
    }
}
