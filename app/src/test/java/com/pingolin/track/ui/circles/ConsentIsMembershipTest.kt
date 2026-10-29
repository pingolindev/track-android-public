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

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.data.remote.dto.LocationConsentNoticeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Consent is membership: if you don't consent, you can't be in the circle. The notice
 * says what agreeing does — joining, or creating — and "Not now" does neither. No
 * wording says "member, sharing nothing".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConsentIsMembershipTest {

    private val notice = LocationConsentNoticeResponse(version = "v2", digest = "d", text = "## Stopping\n\nLeave the circle.")

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).get().also {
            it.setTheme(com.pingolin.track.R.style.Theme_Track)
        }

    private fun shown(): androidx.appcompat.app.AlertDialog =
        ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog

    private fun button(which: Int): Button = shown().getButton(which)

    @Test
    fun `accepting an invitation says it joins, and Not now joins nothing`() {
        var granted: String? = null
        var declined = false
        LocationConsentDialog.show(
            activity(), "Family", notice, LocationConsentDialog.Purpose.JOIN,
            onGrant = { granted = it }, onDecline = { declined = true },
        )

        assertEquals("Join and share my location", button(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertEquals("Not now", button(DialogInterface.BUTTON_NEGATIVE).text.toString())
        button(DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(declined)
        assertNull(granted)
    }

    @Test
    fun `creating a circle says it creates, and agreeing hands back the version shown`() {
        var granted: String? = null
        LocationConsentDialog.show(
            activity(), "Hiking", notice, LocationConsentDialog.Purpose.CREATE,
            onGrant = { granted = it }, onDecline = {},
        )

        assertEquals("Create and share my location", button(DialogInterface.BUTTON_POSITIVE).text.toString())
        button(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("v2", granted)
    }

    @Test
    fun `the notice cannot be dismissed by a stray tap or back`() {
        LocationConsentDialog.show(
            activity(), "Family", notice, LocationConsentDialog.Purpose.JOIN, onGrant = {}, onDecline = {},
        )
        val dialog = shown()
        assertFalse(org.robolectric.Shadows.shadowOf(dialog as android.app.Dialog).isCancelable)
    }

    private fun click(which: Int) {
        button(which).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /** Every piece of text on screen in [dialog], in order. */
    private fun textsOf(dialog: android.app.Dialog): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView && v !is Button) out += v.text.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(dialog.window!!.decorView)
        return out
    }

    /**
     * When the notice cannot be fetched, the dialog shows the bundled copy of the served
     * text — including v1.1's membership sentence, which sits before the question — and the
     * version it declares is what the agreement names.
     */
    @Test
    fun `without the server's text the bundled notice is shown, membership sentence and all`() {
        var granted: String? = null
        LocationConsentDialog.show(
            activity(), "Family", null, LocationConsentDialog.Purpose.JOIN,
            onGrant = { granted = it }, onDecline = {},
        )

        val texts = textsOf(shown())
        assertTrue(texts.toString(), texts.contains(
            "Sharing your location is part of being in a circle. You can pause it any time; to stop sharing for good, " +
                "you leave the circle. As its owner, you delete it, or transfer ownership and then leave."
        ))
        assertTrue(texts.toString(), texts.contains("STOPPING"))
        // The question is the button, not text; the front matter and title are not shown.
        assertFalse(texts.toString(), texts.any { it.contains("Share my location with this circle") })
        assertFalse(texts.toString(), texts.any {
            it.contains("version:") || it.contains("effective:") || it.contains("Before you share with this circle")
        })
        // v1.3 and later: the first sentence ends at "anywhere."; "we can't read it." is not shown.
        assertTrue(texts.toString(), texts.any {
            it.startsWith("Your position is encrypted on your phone before it goes anywhere. Travelling alongside, unencrypted:")
        })
        assertFalse(texts.toString(), texts.any { Regex("can.t\\s+read it").containsMatchIn(it) })

        click(DialogInterface.BUTTON_POSITIVE)
        // The record names the text the person saw: the bundled copy's own version.
        assertEquals("1.5", granted)
    }

    /**
     * "Not now" on creating, as on every client: nothing is created, nothing is said,
     * and the create sheet stays open with the name. Agreeing closes it and creates.
     */
    @Test
    fun `not now on creating keeps the sheet open with the name, and agreeing creates`() {
        val context = activity()
        val created = mutableListOf<Pair<String, String>>()
        var notices = 0
        val sheet = CreateCircleSheet.show(context) { name, answered ->
            notices++
            LocationConsentDialog.show(
                context, name, notice, LocationConsentDialog.Purpose.CREATE,
                onGrant = { version -> answered(true); created += name to version }, onDecline = { answered(false) },
            )
        }
        val field = sheet.findViewById<android.widget.EditText>(com.pingolin.track.R.id.etCircleName)!!

        // A blank name does nothing, and the sheet stays.
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sheet.isShowing)
        assertTrue(ShadowDialog.getLatestDialog() === sheet)

        field.setText("Hiking")
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Create and share my location", button(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertTrue("the sheet stays under the notice", sheet.isShowing)
        // One notice at a time: Create is off while it is up, so it cannot be agreed to twice.
        assertFalse(sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertEquals(1, notices)

        click(DialogInterface.BUTTON_NEGATIVE) // "Not now"
        assertTrue(created.isEmpty())
        assertTrue(sheet.isShowing)
        assertEquals("Hiking", field.text.toString())
        assertTrue(sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)

        sheet.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        click(DialogInterface.BUTTON_POSITIVE) // "Create and share my location"
        assertEquals(listOf("Hiking" to "v2"), created)
        assertFalse(sheet.isShowing)
    }

    @Test
    fun `no copy is left for a member who shares nothing, and declining says the invitation waits`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for (gone in listOf(
            "nothing of yours is being sent",
            "Review and start sharing",
            "You are not sharing your location with this circle",
            "name=\"location_notice_backlog",
            "name=\"location_notice_declined_hint\"",
            "name=\"consent_detail_never_answered\"",
        )) {
            assertFalse("still there: $gone", strings.contains(gone, ignoreCase = true))
        }
        assertTrue(strings.contains("You didn\\'t join %1\$s. Your invitation is still in Invitations if you change your mind."))
        // …and "Invitations" is what the received invitations are called, so the line is true.
        assertTrue(strings.contains("<string name=\"received_invites_title\">Invitations</string>"))
    }

    /**
     * The circle screen has no "Stop sharing my location" button, confirmation or words.
     * What it has: the row saying that sharing is part of being in the circle, the Pause
     * controls, and Leave.
     */
    @Test
    fun `the circle screen offers no Stop sharing, and keeps what it said and the pause`() {
        val ids = com.pingolin.track.R.id::class.java.fields.map { it.name }.toSet()
        assertFalse("btnConsentAction" in ids)
        // …and the line saying how to stop for good, beside Pause and Resume.
        for (kept in listOf(
            "layoutLocationConsent", "tvConsentStatus", "tvConsentDetail", "tvOwnSharingStatus",
            "tvSharingIsMembership", "btnPauseSharing", "btnResume",
        )) {
            assertTrue("missing: $kept", kept in ids)
        }

        val strings = com.pingolin.track.R.string::class.java.fields.map { it.name }.toSet()
        for (gone in listOf(
            "consent_action_stop", "consent_leave_title", "consent_leave_body", "consent_leave_confirm",
            "consent_leave_pause", "consent_leave_cancel", "consent_leave_owner_body",
        )) {
            assertFalse("still there: $gone", gone in strings)
        }
        assertTrue("consent_status_sharing" in strings && "consent_detail_sharing" in strings && "sharing_is_membership" in strings)

        // Inflated as the screen shows it: no "Stop sharing my location", and no button that
        // stops sharing (the section's own sentence says how: by leaving).
        val dialog = android.view.LayoutInflater.from(activity()).inflate(com.pingolin.track.R.layout.dialog_circle_privacy, null)
        fun views(v: android.view.View): List<android.view.View> =
            listOf(v) + (if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList())
        val texts = views(dialog).filterIsInstance<android.widget.TextView>()
        assertTrue(texts.none { it.text.contains("Stop sharing my location", ignoreCase = true) })
        assertTrue(texts.filterIsInstance<Button>().none { it.text.contains("stop sharing", ignoreCase = true) })
        assertTrue(dialog.findViewById<android.view.View>(com.pingolin.track.R.id.tvConsentDetail) != null)
    }

    /**
     * The section says how to stop for good (GDPR Article 7(3)), one line for every
     * member, owner included ("transfer ownership", and a transfer alone doesn't stop
     * sharing, so "and then leave"): shown whatever the consent row or the own-status
     * line say.
     */
    @Test
    fun `the sharing section says how to stop for good, word for word`() {
        val sentence = "Sharing your location is part of being in this circle. You can pause it any time; " +
            "to stop sharing for good, you leave the circle. As its owner, you delete it, or transfer ownership and then leave."
        val context = activity()
        assertEquals(sentence, context.getString(com.pingolin.track.R.string.sharing_is_membership))

        val dialog = android.view.LayoutInflater.from(context).inflate(com.pingolin.track.R.layout.dialog_circle_privacy, null)
        val line = dialog.findViewById<android.widget.TextView>(com.pingolin.track.R.id.tvSharingIsMembership)!!
        assertEquals(sentence, line.text.toString())
        assertEquals(android.view.View.VISIBLE, line.visibility)
        // Its own line, not inside the consent row (shown only for a live grant).
        val consentRow = dialog.findViewById<android.view.ViewGroup>(com.pingolin.track.R.id.layoutLocationConsent)!!
        assertTrue(line.parent !== consentRow)
        // The own-status line stays beside it.
        assertTrue(dialog.findViewById<android.view.View>(com.pingolin.track.R.id.tvOwnSharingStatus) != null)
        // Nothing in the app hides or rewrites it.
        val fragment = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertFalse(fragment.contains("tvSharingIsMembership"))
    }
}
