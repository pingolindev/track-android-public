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

package com.pingolin.track.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A location-sharing app's privacy policy must be readable from inside it: Settings has a Privacy
 * Policy and a Terms of Service row, each opening the website's own page, the ones the iPhone app
 * links to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LegalLinksTest {

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> view.children.flatMap { texts(it).asSequence() }.toList()
        else -> emptyList()
    }

    @Test
    fun `Settings has a Privacy Policy and a Terms of Service row, each saying it opens the browser`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        val root = LayoutInflater.from(activity).inflate(R.layout.fragment_settings, null)
        assertEquals(listOf("Privacy Policy", "Opens in your browser"), texts(root.findViewById(R.id.cardPrivacyPolicy)))
        assertEquals(listOf("Terms of Service", "Opens in your browser"), texts(root.findViewById(R.id.cardTermsOfService)))
    }

    @Test
    fun `each row opens the website's own page`() {
        assertEquals("https://pingolin.com/privacy", SettingsFragment.PRIVACY_POLICY_URL)
        assertEquals("https://pingolin.com/terms", SettingsFragment.TERMS_URL)
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertTrue(settings.contains("binding.cardPrivacyPolicy.setOnClickListener { openUrl(PRIVACY_POLICY_URL) }"))
        assertTrue(settings.contains("binding.cardTermsOfService.setOnClickListener { openUrl(TERMS_URL) }"))
    }
}
