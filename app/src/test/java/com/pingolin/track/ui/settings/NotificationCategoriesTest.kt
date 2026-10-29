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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification-categories dialog offers no switch for the emergency family,
 * though the server has `sos_push`/`sos_email` columns for it.
 *
 * Those columns govern nothing. The emergency family is `Undeniable` in the
 * server's preference gate: it bypasses the global switch, quiet hours and every
 * per-category opt-out, and it is checked BEFORE the preferences are loaded so a
 * failed query cannot silence it either. `sos_resolved` is undeniable for the same
 * reason — an alarm nobody can silence, paired with an all-clear they can, leaves
 * a permanent "someone needs attention" marker with nothing able to clear it.
 *
 * So a switch would render a promise the system does not keep, in the direction
 * that matters most: a worried user could turn it off, believe they had, and keep
 * receiving emergency alerts. The row says it is always on, and — the part worth a
 * test — has no server keys behind it at all, so there is nothing the dialog could
 * write for it even by accident.
 */
class NotificationCategoriesTest {

    /**
     * A function, not a field: as a field its `single` would throw during
     * construction and fail every test in the class at once, hiding which property
     * actually broke.
     */
    private fun emergency() = NotificationCategories.ALL.single { it.isLocked }

    /**
     * Deleting the row is the wrong answer. Someone who goes looking for that
     * switch has to be able to find out why there is none, and a bare gap invites
     * a later change that "restores" it by making emergencies deniable — the exact
     * outcome this prevents.
     */
    @Test
    fun `the emergency category is still listed, in place`() {
        assertEquals("Circle notifications", emergency().label)
        // Its position in the list does not change by being locked: it must not
        // be shuffled to the bottom, which reads as "deprecated" rather than
        // "not yours to change".
        assertEquals(4, NotificationCategories.ALL.indexOf(emergency()))
        assertEquals(
            listOf(
                "Circle invites", "Member joined", "Place alerts",
                "Safety alerts", "Circle notifications", "Check-ins", "Destinations",
            ),
            NotificationCategories.ALL.map { it.label },
        )
    }

    /** The row says what it is where the user is reading, not only if they tap it. */
    @Test
    fun `the locked row says it is always on`() {
        assertEquals("Circle notifications — always on", emergency().pushRowLabel)
        assertTrue(NotificationCategories.LOCKED_NOTE.contains("can't be turned off"))
        assertTrue(NotificationCategories.LOCKED_TOAST.contains("can't be turned off"))
    }

    /**
     * The whole point. A locked category carries no server keys, so the guarantee
     * is structural rather than a filter a later refactor of the loop can drop.
     */
    @Test
    fun `the locked category has no server keys to write`() {
        assertEquals(null, emergency().pushKey)
        assertEquals(null, emergency().emailKey)
    }

    /**
     * Writing `sos_push: false` would have been worse than the switch itself: it
     * records the user's belief that they turned emergencies off, which the server
     * then ignores.
     */
    @Test
    fun `the saved payload never mentions the emergency columns`() {
        // Answer "off" to everything — the state that would write the false.
        val update = NotificationCategories.buildUpdate(
            pushEnabled = false,
            emailEnabled = false,
            push = { false },
            email = { false },
        )

        assertFalse("sos_push governs nothing and must not be written", update.containsKey("sos_push"))
        assertFalse("sos_email governs nothing and must not be written", update.containsKey("sos_email"))
        assertTrue(
            "no key may carry the old name in any form: ${update.keys}",
            update.keys.none { it.startsWith("sos") },
        )
    }

    /** The categories that ARE preferences still round-trip, unchanged. */
    @Test
    fun `every deniable category still reaches the payload`() {
        val update = NotificationCategories.buildUpdate(
            pushEnabled = true,
            emailEnabled = true,
            push = { true },
            email = { false },
        )

        assertEquals(true, update["push_enabled"])
        assertEquals(true, update["email_enabled"])
        listOf(
            "circle_invite_push", "member_joined_push", "place_alert_push",
            "safety_alert_push", "check_in_push", "destination_push",
        ).forEach { assertEquals("$it must still be settable", true, update[it]) }
        listOf(
            "circle_invite_email", "member_joined_email", "place_alert_email",
        ).forEach { assertEquals("$it must still be settable", false, update[it]) }

        // Destinations and Check-ins are push-only server-side; inventing a column
        // for either would 400 the whole PUT and take the other categories down.
        assertFalse(update.containsKey("destination_email"))
        assertFalse(update.containsKey("check_in_email"))
    }

    // ===== check_in_push, and "absent" as a third answer =====

    private fun checkIns() = NotificationCategories.ALL.single { it.label == "Check-ins" }

    /**
     * Push-only by design: nothing sends a check-in by email, so the server has
     * no `check_in_email` column. Same shape as Destinations.
     */
    @Test
    fun `check-ins is a push-only row next to the locked one`() {
        assertEquals("check_in_push", checkIns().pushKey)
        assertEquals(null, checkIns().emailKey)
        assertFalse("it is a real preference, unlike the row above it", checkIns().isLocked)
        // Beside the locked row: the contrast is the point.
        assertEquals(
            NotificationCategories.ALL.indexOfFirst { it.isLocked } + 1,
            NotificationCategories.ALL.indexOf(checkIns()),
        )
    }

    /**
     * These columns are nullable and NULL means "inherit push_enabled", not "off".
     * `check_in_push` is NULL for every user who has not set it, and the dialog
     * renders such a row as ticked — because inheriting an enabled global IS on.
     * If Save wrote that rendered tick back, merely opening the dialog and
     * pressing Save would convert everyone from "inherit" to "pinned", for a
     * preference they never expressed.
     *
     * Deliberately NOT a payload that omits everything: the same call answers with a
     * real value for a row the user did touch and for a row the server already had.
     * An implementation that dropped every key, or wrote every key, fails this.
     */
    @Test
    fun `a row the user never set is left out, while the ones they did set are sent`() {
        val stored = mapOf(
            // Untouched and never set server-side -> must not appear.
            "check_in_push" to null,
            "destination_push" to null,
            // Untouched but already stored -> must be repeated, not dropped.
            "circle_invite_push" to true,
            "member_joined_push" to false,
            // The user actually toggled these two in this dialog.
            "place_alert_push" to false,
            "safety_alert_push" to true,
        )
        val update = NotificationCategories.buildUpdate(
            pushEnabled = true,
            emailEnabled = false,
            push = { stored[it.pushKey] },
            email = { null },
        )

        assertFalse(
            "a NULL check_in_push must stay NULL: writing the rendered tick pins an inherit",
            update.containsKey("check_in_push"),
        )
        assertFalse(update.containsKey("destination_push"))

        // ...and the payload is not simply empty, which is what makes the two
        // assertions above mean something.
        assertEquals(true, update["circle_invite_push"])
        assertEquals(false, update["member_joined_push"])
        assertEquals(false, update["place_alert_push"])
        assertEquals(true, update["safety_alert_push"])
        assertEquals(true, update["push_enabled"])
        assertEquals(false, update["email_enabled"])
    }

    /**
     * The other half: once the user DOES answer, the answer is written — including
     * `false`, which is the whole point of giving them the control. "Absent" is only
     * correct for a preference nobody has expressed.
     */
    @Test
    fun `turning check-ins off writes a real false`() {
        val update = NotificationCategories.buildUpdate(
            pushEnabled = true,
            emailEnabled = false,
            push = { if (it.pushKey == "check_in_push") false else null },
            email = { null },
        )

        assertTrue(update.containsKey("check_in_push"))
        assertEquals(false, update["check_in_push"])
    }

    /** And an explicit `true` is not confused with "no answer". */
    @Test
    fun `turning check-ins on writes a real true`() {
        val update = NotificationCategories.buildUpdate(
            pushEnabled = false,
            emailEnabled = false,
            push = { if (it.pushKey == "check_in_push") true else null },
            email = { null },
        )

        assertEquals(true, update["check_in_push"])
    }

    /**
     * [NotificationCategories.answerFor] is the half of the rule that PRODUCES
     * the null. buildUpdate honouring a null is useless if the dialog hands it
     * the rendered tick instead — that type-checks and passes every test above.
     *
     * The row that matters is the third one: an unset `check_in_push` under an
     * enabled global displays as ticked, and reporting that tick is exactly the
     * silent inherit-to-pinned conversion this exists to stop.
     */
    @Test
    fun `an untouched row reports what was stored, never what is displayed`() {
        // Touched: the user's answer, false included.
        assertEquals(false, NotificationCategories.answerFor(touched = true, shown = false, stored = null))
        assertEquals(true, NotificationCategories.answerFor(touched = true, shown = true, stored = null))
        assertEquals(false, NotificationCategories.answerFor(touched = true, shown = false, stored = true))

        // Untouched and never stored: nothing to report, whatever is on screen.
        assertEquals(null, NotificationCategories.answerFor(touched = false, shown = true, stored = null))
        assertEquals(null, NotificationCategories.answerFor(touched = false, shown = false, stored = null))

        // Untouched but stored: repeat it, do not drop the user's real preference.
        assertEquals(true, NotificationCategories.answerFor(touched = false, shown = false, stored = true))
        assertEquals(false, NotificationCategories.answerFor(touched = false, shown = true, stored = false))
    }

    /**
     * The locked row is unaffected by any of this. It has no key, so there is no
     * "absent vs false" question to get wrong for it.
     */
    @Test
    fun `the locked row stays absent whatever the answer function returns`() {
        listOf<Boolean?>(null, true, false).forEach { answer ->
            val update = NotificationCategories.buildUpdate(
                pushEnabled = true,
                emailEnabled = true,
                push = { answer },
                email = { answer },
            )
            assertFalse(update.keys.any { it.startsWith("sos") })
        }
    }

    /**
     * The email step lists only rows with an email channel. Both the push-only
     * "Destinations" and the locked row drop out of it — the locked row because it
     * has no column at all, which is the same reason it cannot be written.
     */
    @Test
    fun `the email step lists only categories with an email channel`() {
        val labels = NotificationCategories.withEmail.map { it.label }
        assertFalse(labels.contains("Circle notifications"))
        assertFalse(labels.contains("Destinations"))
        assertFalse("nothing sends a check-in by email", labels.contains("Check-ins"))
        assertFalse("nothing can send a safety alert by email", labels.contains("Safety alerts"))
        assertEquals(listOf("Circle invites", "Member joined", "Place alerts"), labels)
    }

    // ===== e-mail switches that would send nothing =====

    private fun safetyAlerts() = NotificationCategories.ALL.single { it.label == "Safety alerts" }

    /**
     * Low battery and inactivity are judged on the watcher's own phone, so the server never
     * learns who watches whom, and an e-mail would hand it every firing. There is no e-mail
     * switch for them, and `safety_alert_email` is never written, whatever the dialog says.
     */
    @Test
    fun `safety alerts are push-only, and their email column is never written`() {
        assertEquals("safety_alert_push", safetyAlerts().pushKey)
        assertEquals(null, safetyAlerts().emailKey)
        listOf<Boolean?>(true, false, null).forEach { answer ->
            val update = NotificationCategories.buildUpdate(
                pushEnabled = true,
                emailEnabled = true,
                push = { answer },
                email = { answer },
            )
            assertFalse("safety_alert_email for $answer: ${update.keys}", update.containsKey("safety_alert_email"))
        }
    }

    /**
     * Circle notifications ARE e-mailed, to every member with an address, whatever `sos_email`
     * says. The email step says so beside the locked row, as the web shows that row locked,
     * rather than leaving a silent gap.
     */
    @Test
    fun `the email step says circle notifications are always emailed`() {
        assertEquals("Circle notifications — always on", NotificationCategories.lockedStatement)
        assertTrue(NotificationCategories.EMAIL_LOCKED_NOTE.contains("always emailed"))
        assertFalse(NotificationCategories.EMAIL_LOCKED_NOTE.contains("SOS", ignoreCase = true))
    }

    @Test
    fun `deniable is every row except the locked one`() {
        assertEquals(
            NotificationCategories.ALL.size - 1,
            NotificationCategories.deniable.size,
        )
        assertFalse(NotificationCategories.deniable.any { it.isLocked })
    }

    /**
     * Web and iOS both call this family "Circle notifications", and the app says
     * "notify my circle", never "SOS". The server column keeps its `sos_` name — a
     * wire detail, and exactly why a user-visible "SOS" is easy to miss here.
     */
    @Test
    fun `no row says SOS`() {
        NotificationCategories.ALL.forEach { category ->
            assertFalse(
                "user-visible copy must not say SOS: ${category.pushRowLabel}",
                category.pushRowLabel.contains("SOS", ignoreCase = true),
            )
        }
        assertFalse(NotificationCategories.LOCKED_NOTE.contains("SOS", ignoreCase = true))
        assertFalse(NotificationCategories.LOCKED_TOAST.contains("SOS", ignoreCase = true))
    }

    /**
     * The wider rule "SOS" is one instance of.
     *
     * Track is not a safety app — the Terms of Service say so, no alert reaches
     * police, fire or ambulance, and nobody at Pingolin watches them — so copy
     * promising that help gets through ("…so someone who needs help always gets
     * through") contradicts the product's own disclaimer, in the place a
     * worried user goes looking for reassurance. The vocabulary is banned in a
     * test rather than in a style guide because the next person to edit this
     * copy will read the test.
     */
    @Test
    fun `no copy claims a safety service`() {
        val copy = buildList {
            add(NotificationCategories.LOCKED_NOTE)
            add(NotificationCategories.EMAIL_LOCKED_NOTE)
            add(NotificationCategories.LOCKED_TOAST)
            add(NotificationCategories.lockedStatement)
            NotificationCategories.ALL.forEach { add(it.pushRowLabel); add(it.label) }
        }
        copy.forEach { line ->
            NotificationCategories.BANNED_COPY.forEach { banned ->
                assertFalse(
                    "user-visible copy must not say \"$banned\": $line",
                    line.contains(banned, ignoreCase = true),
                )
            }
        }
    }

    /**
     * The locked row is rendered by the dialog HEADER, not as a list entry, so
     * the dialog needs its text without going through [NotificationCategories.ALL]
     * and finding the locked one itself. If a second locked category is ever
     * added, `single` throws here rather than silently showing one of two.
     */
    @Test
    fun `the locked statement is the locked row's label`() {
        assertEquals("Circle notifications — always on", NotificationCategories.lockedStatement)
    }

    /**
     * What the push list is built from: `deniable`, not
     * [NotificationCategories.ALL], whose locked row would be drawn as a ticked,
     * tappable checkbox that reads as a control the user owns. Every row in it
     * must have something to write.
     */
    @Test
    fun `every row offered as a checkbox is one the server will store`() {
        assertEquals(NotificationCategories.ALL.size - 1, NotificationCategories.deniable.size)
        NotificationCategories.deniable.forEach { category ->
            assertFalse(category.isLocked)
            assertTrue("a listed row must have a push key: ${category.label}", category.pushKey != null)
        }
    }
}
