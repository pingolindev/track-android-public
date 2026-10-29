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

import android.view.View
import android.widget.TextView
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The dot menu at a large font, on a small phone, where its labels can meet: the notification bell's over
 * the circle selector, which names the circle the menu's sends go to; the controls' labels, once a
 * guardian-managed account's line moves the controls down, against the stack's on the other side; and a long
 * circle name running the selector under the bell. Measured on the app's own layout (MapLayout): at 1.0 and
 * 2.0, in English and German, for a guardian-managed account and a plain one, with a short name and the
 * longest a name may be, the menu shut and open. No label, line or card meets another label, a control, the
 * selector or the bell, and at the usual font with a short name every label still shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class MenuLabelsInTheClearTest {

    private companion object {
        // As long as a circle's name may be (dialog_create_circle.xml: maxLength 50).
        const val LONG_NAME = "The Hendersons, the Okafors and the Lakehouse gang"

        val STACK_LABELS = listOf(R.id.labelDestinations, R.id.labelHistory, R.id.labelSearch, R.id.labelQuickCheckIn, R.id.labelSendLocation)
    }

    private lateinit var map: MapLayout
    private val Int.dp get() = (this * map.density).toInt()

    /** Each view's text, as the layouts name it: id to string name, in both layouts. */
    private val textOf: Map<String, String> by lazy {
        val android = "http://schemas.android.com/apk/res/android"
        listOf("fragment_map", "activity_main").flatMap { layout ->
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(File("src/main/res/layout/$layout.xml"))
            val all = doc.getElementsByTagName("*")
            (0 until all.length).map { all.item(it) as org.w3c.dom.Element }
                .filter { it.getAttributeNS(android, "text").startsWith("@string/") }
                .map { it.getAttributeNS(android, "id").removePrefix("@+id/") to it.getAttributeNS(android, "text").removePrefix("@string/") }
        }.toMap()
    }

    /** A language's strings, read from its file: the unit tests' resources are English. */
    private fun strings(language: String): Map<String, String> {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values-$language/strings.xml"))
        val all = doc.getElementsByTagName("string")
        return (0 until all.length).map { all.item(it) as org.w3c.dom.Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
    }

    /** The map as [language] has it, at [scale]: the selector named [name], the guardian line if [guardian]. */
    private fun mapIn(language: String, scale: Float, guardian: Boolean, name: String) {
        map = MapLayout(scale)
        if (language != "en") {
            val words = strings(language)
            for ((id, string) in textOf) {
                val view = map.activity.window.decorView.findViewById<View>(map.activity.resources.getIdentifier(id, "id", map.activity.packageName))
                if (view is TextView) words[string]?.let { view.text = it }
            }
        }
        map.view<TextView>(R.id.tvCircleName).text = name
        map.view<View>(R.id.cardCircleSelector).visibility = View.VISIBLE
        map.view<View>(R.id.mapControlsLeft).visibility = View.VISIBLE
        map.view<View>(R.id.tvGuardianManaged).visibility = if (guardian) View.VISIBLE else View.GONE
        map.stack.place()
        map.settle()
    }

    /** The menu opened as expandFabs opens it, then fitted as the map fits it after the layout. */
    private fun openMenu() {
        map.view<View>(R.id.tvLocationOff).visibility = View.GONE
        map.view<View>(R.id.fabRowDestinations).visibility = View.VISIBLE
        map.view<View>(R.id.fabRowHistory).visibility = View.VISIBLE
        map.labels().forEach { it.visibility = View.VISIBLE }
        map.settle()
        fitMenuLabels(map.root.rootView, gapPx = 8.dp)
        map.settle()
    }

    private fun box(v: View): Box = map.rectOf(v).let { Box(it.left, it.top, it.right, it.bottom) }

    /** No shown label meets anything shown; no line or card meets the selector or the bell. */
    private fun assertInTheClear(case: String) {
        val labels = map.labels().filter { it.isShown }
        val chrome = map.menuChrome().filter { it.isShown }
        for (label in labels) {
            for (other in labels + chrome) {
                if (other === label) continue
                assertTrue(
                    "$case: ${map.name(label)} ${map.rectOf(label)} meets ${map.name(other)} ${map.rectOf(other)}",
                    box(label).clearOf(box(other), 1),
                )
            }
        }
        val selector = map.view<View>(R.id.cardCircleSelector)
        for (card in chrome.filter { it !== selector && it !== map.bell }) {
            for (top in listOf(selector, map.bell)) {
                assertTrue("$case: ${map.name(card)} ${map.rectOf(card)} meets ${map.name(top)} ${map.rectOf(top)}", box(card).clearOf(box(top), 1))
            }
        }
        assertTrue("$case: the selector ${map.rectOf(selector)} meets the bell ${map.rectOf(map.bell)}", box(selector).clearOf(box(map.bell), 8.dp))
    }

    private fun shown(id: Int) = map.root.rootView.findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun `at 1_0 and 2_0, in English and German, guardian and plain, short and long names, shut and open`() {
        for (language in listOf("en", "de")) for (scale in listOf(1.0f, 2.0f)) for (guardian in listOf(true, false)) {
            for (name in listOf(if (language == "de") "Nur ich" else "Just me", LONG_NAME)) {
                val case = "$language, $scale, ${if (guardian) "guardian" else "plain"}, \"$name\""
                mapIn(language, scale, guardian, name)
                assertInTheClear("$case, shut")
                openMenu()
                assertInTheClear("$case, open")
                // The stack's labels name its actions, a send among them: they claim their room first, and
                // a control's label that meets one gives way (in English at 2.0, "Layers" to "Destinations").
                for (id in STACK_LABELS) assertTrue("$case: ${map.activity.resources.getResourceEntryName(id)} shows", shown(id))
            }
        }
    }

    @Test
    fun `at the usual font with a short name, every label shows`() {
        for (language in listOf("en", "de")) for (guardian in listOf(true, false)) {
            mapIn(language, 1.0f, guardian, if (language == "de") "Nur ich" else "Just me")
            openMenu()
            for (label in map.labels()) {
                assertEquals("$language, ${if (guardian) "guardian" else "plain"}: ${map.name(label)}", View.VISIBLE, label.visibility)
            }
            assertInTheClear("$language at 1.0")
        }
    }

    @Test
    fun `at 2_0 in German with the guardian line, what met is hidden, and the stack's labels stay`() {
        mapIn("de", 2.0f, guardian = true, name = "Nur ich")
        openMenu()
        // What meets on a phone: the bell's label over the selector, and the controls' labels in
        // the stack's rows. The stack's labels, which name its actions, all stay.
        assertFalse("the bell's label", shown(R.id.labelNotifications))
        assertFalse("Recenter on me", shown(R.id.labelRecenterOnMe))
        assertFalse("Reset north", shown(R.id.labelCompass))
        for (id in STACK_LABELS) assertTrue(map.activity.resources.getResourceEntryName(id), shown(id))
        assertInTheClear("de at 2.0")
    }

    @Test
    fun `a name that comes after the first layout takes the room it needs, and no more`() {
        // The map names the selector once its circles load, after the card's first layout: a 0dp
        // name would keep the first name's width and cut every later one to it.
        for (scale in listOf(1.0f, 2.0f)) {
            mapIn("en", scale, guardian = false, name = "Just me")
            val name = map.view<TextView>(R.id.tvCircleName)
            val card = map.view<View>(R.id.cardCircleSelector)
            val first = map.rectOf(card).width()
            assertEquals("\"Just me\" whole at $scale", 0, name.layout.getEllipsisCount(0))

            name.text = LONG_NAME
            map.settle()
            val reach = map.rectOf(map.bell).left - 8.dp
            assertTrue("a long name: the card as wide as it may be at $scale, to $reach: ${map.rectOf(card)}", kotlin.math.abs(map.rectOf(card).right - reach) <= 1)
            assertTrue("cut at its end at $scale", name.layout.getEllipsisCount(0) > 0)
            assertEquals("the arrow whole at $scale", 18.dp, (name.parent as android.view.ViewGroup).getChildAt(1).width)

            name.text = "Lakehouse"
            map.settle()
            assertTrue("a shorter one: the card shrinks at $scale", map.rectOf(card).right < map.rectOf(map.bell).left - 8.dp)
            assertEquals("and it isn't cut at $scale", 0, name.layout.getEllipsisCount(0))

            name.text = "Just me"
            map.settle()
            assertEquals("back to the first at $scale", first, map.rectOf(card).width())
            assertInTheClear("names changed at $scale")
        }
    }

    @Test
    fun `the labels come back when the menu opens again where they fit`() {
        mapIn("de", 2.0f, guardian = true, name = "Nur ich")
        openMenu()
        assertFalse(shown(R.id.labelCompass))
        // Shut as collapseFabs shuts it, then the line goes (the account handed over) and the menu opens again.
        map.labels().forEach { it.visibility = View.GONE }
        map.view<View>(R.id.fabRowDestinations).visibility = View.GONE
        map.view<View>(R.id.fabRowHistory).visibility = View.GONE
        map.view<View>(R.id.tvGuardianManaged).visibility = View.GONE
        map.stack.place()
        map.settle()
        openMenu()
        assertInTheClear("again, no line")
        assertTrue("Recenter on me fits again", shown(R.id.labelRecenterOnMe))
    }
}
