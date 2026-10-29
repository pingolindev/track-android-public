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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The formats that carry words follow the language the app's words are in: the phone's own locale when it
 * speaks that language and script, else the words' language. So where the app's words are English, a Chinese
 * phone writes "300 m", not "300米", inside an English line. Robolectric: the rule reads likely subtags from
 * android.icu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UiLocaleTest {

    private fun tag(t: String) = Locale.forLanguageTag(t)

    @Test
    fun `the phone's locale when it speaks the words' language and script, else the words' language`() {
        assertEquals(tag("en"), uiLocaleFor("en", tag("zh-CN")))
        assertEquals(tag("en"), uiLocaleFor("en", tag("de-DE")))
        assertEquals(tag("en-GB"), uiLocaleFor("en", tag("en-GB")))
        assertEquals(tag("zh-CN"), uiLocaleFor("zh-Hans", tag("zh-CN")))
        // Same language, other script: never Traditional unit words (公尺) inside Simplified text.
        assertEquals(tag("zh-Hans"), uiLocaleFor("zh-Hans", tag("zh-TW")))
        assertEquals(tag("es-MX"), uiLocaleFor("es", tag("es-MX")))
        assertEquals(tag("pt-PT"), uiLocaleFor("pt", tag("pt-PT")))
    }

    /** Every values folder with words states their language in ui_language.xml (values/ "en", values-pt "pt"). */
    @Test
    fun `each values folder with words states its own language`() {
        val res = File("src/main/res")
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists() }!!
        assertTrue("found ${folders.map { it.name }}", folders.size >= 8)
        for (folder in folders) {
            val want = when {
                folder.name == "values" -> "en"
                folder.name.startsWith("values-b+") -> folder.name.removePrefix("values-b+").replace('+', '-')
                else -> folder.name.removePrefix("values-").replace("-r", "-")
            }
            val marker = File(folder, "ui_language.xml")
            assertTrue("${folder.name} has no ui_language.xml", marker.exists())
            val got = Regex("""<string name="ui_language">([^<]*)</string>""").find(marker.readText())?.groupValues?.get(1)
            assertEquals(folder.name, want, got)
            assertFalse("${folder.name}: the marker is a tag, not a translation (keep it out of strings.xml)",
                File(folder, "strings.xml").readText().contains("name=\"ui_language\""))
        }
    }

    @Test
    fun `every format that carries words asks for it, and the unit stays the region's`() {
        fun src(path: String) = File("src/main/java/com/pingolin/track/$path").readText()
        val sos = src("ui/sos/SosPositionText.kt")
        val adapter = src("ui/places/AddressSearchAdapter.kt")
        val editor = src("ui/places/PlaceAlertEditor.kt")
        val circles = src("ui/circles/CirclesFragment.kt")
        assertTrue(sos.contains("formatDistance(it / 1000.0, useMiles, uiLocale(context))"))
        assertEquals(2, Regex("""useMiles, uiLocale\(root\.context\)\)""").findAll(adapter).count())
        assertTrue(adapter.contains("CompactDecimalFormat.getInstance(\n                uiLocale(root.context),"))
        assertTrue(editor.contains("java.text.DateFormatSymbols.getInstance(com.pingolin.track.util.uiLocale(requireContext())).shortWeekdays"))
        assertTrue(editor.contains("formatDistance(meters / 1000.0, useMiles, com.pingolin.track.util.uiLocale(requireContext()))"))
        assertEquals(2, Regex("""sessionManager\.useMiles\(\), com\.pingolin\.track\.util\.uiLocale\(requireContext\(\)\)\)""").findAll(circles).count())
        // No format that carries words reads the phone's language by itself.
        for ((name, text) in listOf("AddressFormat" to src("ui/places/AddressFormat.kt"), "AddressSearchAdapter" to adapter, "PlaceAlertEditor" to editor)) {
            assertFalse(name, Regex("""(MeasureFormat|CompactDecimalFormat|DateFormatSymbols)\.getInstance\(\s*(java\.util\.)?Locale\.getDefault\(\)""").containsMatchIn(text))
            assertFalse(name, text.contains("ConfigurationCompat.getLocales(resources.configuration)[0] ?: java.util.Locale.getDefault()"))
        }
        // Which unit is the user's setting, defaulting from the phone's REGION, never from the words' language.
        val session = src("util/SessionManager.kt")
        assertTrue(session.contains("if (java.util.Locale.getDefault().country.uppercase() in MILE_COUNTRIES) UNIT_MI else UNIT_KM"))
        assertFalse(session.contains("uiLocale"))
    }
}
