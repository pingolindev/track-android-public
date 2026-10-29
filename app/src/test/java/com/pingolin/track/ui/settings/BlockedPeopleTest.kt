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

import com.pingolin.track.data.remote.dto.BlockedPersonResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Blocked people": the words, the two small decisions of the screen, and where the screen is
 * reached from.
 */
class BlockedPeopleTest {

    // --- The words -----------------------------------------------------------------------------

    private fun strings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    /**
     * The exact wording ("They are not notified", and the description's "even after changing
     * their email address"), letter for letter: the copy does not change in passing. (`\'` is
     * how the resource file writes an apostrophe.)
     */
    @Test
    fun `the words, word for word`() {
        val s = strings()
        assertEquals("Blocked people", s["blocked_people_title"])
        assertEquals("""People you block can\'t invite you to a circle, even after changing their email address. They are not notified.""", s["blocked_people_description"])
        assertEquals("Email address", s["blocked_people_email_label"])
        assertEquals("Block", s["blocked_people_block"])
        assertEquals("Unblock", s["blocked_people_unblock"])
        assertEquals("""You haven\'t blocked anyone.""", s["blocked_people_empty"])
        // For a screen reader: the word and the address, nothing else.
        assertEquals("Unblock %1\$s", s["blocked_people_unblock_cd"])
        // The wording for a full list.
        assertEquals("Your list of blocked people is full. Unblock someone to add another.", s["blocked_people_full"])
    }

    @Test
    fun `a full list is known by the server's code for it, and by nothing else`() {
        assertTrue(BlockedPeople.isListFull("blocked_people_limit"))
        assertFalse(BlockedPeople.isListFull(null))
        assertFalse(BlockedPeople.isListFull(""))
        assertFalse(BlockedPeople.isListFull("invite_already_exists"))
        assertFalse(BlockedPeople.isListFull("BLOCKED_PEOPLE_LIMIT"))
        assertEquals("blocked_people_limit", BlockedPeople.LIST_FULL_CODE)
    }

    /** The screen says it in those words: nothing else of the app's does, and the server's text is never shown. */
    @Test
    fun `the screen shows its own words for a full list, and the request's own line for anything else`() {
        val screen = File("src/main/java/com/pingolin/track/ui/settings/BlockedPeopleFragment.kt").readText()
        assertTrue(screen.contains("binding.tvFailure.text = if (state.listFull) getString(R.string.blocked_people_full) else state.failure.orEmpty()"))
        assertTrue(screen.contains("binding.tvFailure.visibility = if (state.listFull || state.failure != null) View.VISIBLE else View.GONE"))
    }

    // --- The address to block ------------------------------------------------------------------

    private val nobody = emptyList<String>()

    @Test
    fun `the address is what was typed, without the spaces around it`() {
        assertEquals("sam@example.test", BlockedPeople.addressToBlock("  sam@example.test \n", nobody, null))
        assertEquals("Sam.O+circles@Example.co.uk", BlockedPeople.addressToBlock("Sam.O+circles@Example.co.uk", nobody, null))
    }

    @Test
    fun `what is no address is nothing to send`() {
        for (typed in listOf("", "   ", "sam", "sam@", "@example.test", "sam@example", "sam example@x.test", "a@b@c.test")) {
            assertNull(typed, BlockedPeople.addressToBlock(typed, nobody, null))
        }
    }

    @Test
    fun `an address already on the list is nothing to send, whatever its case`() {
        val blocked = listOf("sam@example.test")
        assertNull(BlockedPeople.addressToBlock("SAM@Example.TEST", blocked, null))
        assertNull(BlockedPeople.addressToBlock(" sam@example.test ", blocked, null))
        assertEquals("pat@example.test", BlockedPeople.addressToBlock("pat@example.test", blocked, null))
    }

    @Test
    fun `one's own address is nothing to send, whatever its case`() {
        assertNull(BlockedPeople.addressToBlock("Me@Example.test", nobody, "me@example.test"))
        assertNull(BlockedPeople.addressToBlock("me@example.test", nobody, " ME@EXAMPLE.TEST "))
        assertEquals("you@example.test", BlockedPeople.addressToBlock("you@example.test", nobody, "me@example.test"))
        // An address of one's own that is not known stops nothing.
        assertEquals("me@example.test", BlockedPeople.addressToBlock("me@example.test", nobody, null))
    }

    /** "Already on it" is not known before the list is read, so nothing is sent before then. */
    @Test
    fun `before the list has been read there is nothing to send`() {
        assertNull(BlockedPeople.addressToBlock("sam@example.test", null, null))
    }

    // --- The list ------------------------------------------------------------------------------

    @Test
    fun `the list is shown by address, whatever its case`() {
        // Capitals sort before every small letter as plain text: "Mia" and "Zoe" would both
        // come before "adam" if case counted.
        val given = listOf("Zoe@x.test", "adam@x.test", "Mia@x.test", "bea@x.test").map { BlockedPersonResponse(it) }
        assertEquals(
            listOf("adam@x.test", "bea@x.test", "Mia@x.test", "Zoe@x.test"),
            BlockedPeople.inListOrder(given).map { it.blockedEmail },
        )
    }

    // --- Where it is reached from --------------------------------------------------------------

    @Test
    fun `Settings has the row, and it opens the screen`() {
        val settings = File("src/main/res/layout/fragment_settings.xml").readText()
        assertTrue(settings.contains("""android:id="@+id/btnBlockedPeople""""))
        val row = settings.substringAfter("""android:id="@+id/btnBlockedPeople"""").substringBefore("</LinearLayout>")
        assertTrue("a row that can be reached and pressed", row.contains("""android:clickable="true"""") && row.contains("""android:focusable="true""""))
        assertTrue("as tall as a touch target", row.contains("""android:minHeight="48dp""""))
        assertTrue(row.contains("""android:text="@string/blocked_people_title""""))

        val fragment = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertTrue(
            fragment.contains(
                "binding.btnBlockedPeople.setOnClickListener {\n            findNavController().navigate(R.id.action_settings_to_blocked_people)",
            ),
        )
        val graph = File("src/main/res/navigation/nav_graph.xml").readText()
        assertTrue(
            graph.contains(
                """android:id="@+id/action_settings_to_blocked_people"
            app:destination="@id/blockedPeopleFragment"""",
            ),
        )
        assertTrue(
            graph.contains(
                """android:id="@+id/blockedPeopleFragment"
        android:name="com.pingolin.track.ui.settings.BlockedPeopleFragment"""",
            ),
        )
    }

    @Test
    fun `the screen shows the description, a labelled field, the button and the empty state`() {
        val screen = File("src/main/res/layout/fragment_blocked_people.xml").readText()
        assertTrue(screen.contains("""app:title="@string/blocked_people_title""""))
        assertTrue(screen.contains("""android:text="@string/blocked_people_description""""))
        // The hint on the layout is the field's label.
        assertTrue(screen.contains("""android:hint="@string/blocked_people_email_label""""))
        assertTrue(screen.contains("""android:inputType="textEmailAddress""""))
        assertTrue(screen.contains("""android:text="@string/blocked_people_block""""))
        assertTrue(screen.contains("""android:text="@string/blocked_people_empty""""))
        // Off until there is an address to send.
        val button = screen.substringAfter("""android:id="@+id/btnBlock"""").substringBefore("/>")
        assertTrue(button.contains("""android:enabled="false""""))

        val row = File("src/main/res/layout/item_blocked_person.xml").readText()
        assertTrue(row.contains("""android:text="@string/blocked_people_unblock""""))
        val adapter = File("src/main/java/com/pingolin/track/ui/settings/BlockedPeopleAdapter.kt").readText()
        assertTrue(
            "each Unblock button says whom",
            adapter.contains("getString(R.string.blocked_people_unblock_cd, person.blockedEmail)"),
        )
    }
}
