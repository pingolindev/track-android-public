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

import android.app.Dialog
import android.content.DialogInterface
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import com.pingolin.track.domain.sos.SosDeliveryWatcher.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The dialog over Track that says a request reached nobody can't be missed, as the Safety tab's
 * own can't. Not cancelable; told on OK only; one at a time; shown again by an activity made
 * again; never above the app lock; gone when nothing is left to tell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeliveryVerdictDialogTest {

    private var told = 0

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().get().apply { setTheme(R.style.Theme_Track) }

    private fun shownDialogs(): List<Dialog> = ShadowDialog.getShownDialogs().filter { it.isShowing }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `said word for word, not cancelable, and told on OK only`() {
        val activity = activity()
        val dialog = DeliveryVerdictDialog { told++ }
        dialog.render(activity, Verdict.ALONE, locked = false)
        idle()
        val shown = shownDialogs().single() as AlertDialog
        assertEquals(
            "Nobody else is in this circle, so there was no one to notify. If you need help now, contact someone directly, or notify a different circle.",
            shown.findViewById<TextView>(android.R.id.message)!!.text.toString(),
        )
        assertFalse("a stray tap or Back doesn't put it away", shadowOf(shown).isCancelable)
        assertNull("OK only", shown.getButton(DialogInterface.BUTTON_NEGATIVE)?.takeIf { it.visibility == android.view.View.VISIBLE })
        assertEquals("shown is not told", 0, told)

        shown.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, told)
        assertFalse(shown.isShowing)
    }

    @Test
    fun `one at a time, however often it is asked for, and the latest verdict when it changes`() {
        val activity = activity()
        val dialog = DeliveryVerdictDialog { told++ }
        dialog.render(activity, Verdict.NO_ANSWER, locked = false)
        idle()
        val first = shownDialogs().single()
        // Back from the background: the STARTED collection gives the same verdict again. The same
        // dialog stays up, never taken down and made again.
        dialog.render(activity, Verdict.NO_ANSWER, locked = false)
        idle()
        assertSame(first, shownDialogs().single())
        assertTrue(first.isShowing)

        // A later request's verdict takes its place, so OK always answers the one still to tell.
        dialog.render(activity, Verdict.UNDELIVERABLE, locked = false)
        idle()
        val shown = shownDialogs().single() as AlertDialog
        assertEquals(
            "Nobody in this circle has a device we can notify. Contact them another way.",
            shown.findViewById<TextView>(android.R.id.message)!!.text.toString(),
        )
        assertEquals(0, told)
    }

    @Test
    fun `an activity made again shows it again until OK, and one that can't show it doesn't tell it`() {
        val first = activity()
        val dialog = DeliveryVerdictDialog { told++ }
        dialog.render(first, Verdict.ALONE, locked = false)
        idle()
        // Rotation, a dark-mode switch: the outgoing activity takes its dialog
        // down, untold.
        dialog.dismiss()
        idle()
        assertTrue(shownDialogs().isEmpty())
        assertEquals(0, told)

        val again = DeliveryVerdictDialog { told++ }
        again.render(activity(), Verdict.ALONE, locked = false)
        idle()
        assertEquals(1, shownDialogs().size)

        // An activity going away can't show it: nothing shown, nothing told.
        val leaving = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get().apply {
            setTheme(R.style.Theme_Track)
            finish()
        }
        val other = DeliveryVerdictDialog { told++ }
        other.render(leaving, Verdict.NO_ANSWER, locked = false)
        idle()
        assertFalse(other.showing)
        assertEquals(0, told)
    }

    @Test
    fun `never above the app lock, and gone when nothing is left to tell`() {
        val activity = activity()
        val dialog = DeliveryVerdictDialog { told++ }
        dialog.render(activity, Verdict.ALONE, locked = true)
        idle()
        assertTrue(shownDialogs().isEmpty())

        dialog.render(activity, Verdict.ALONE, locked = false)
        idle()
        assertTrue(dialog.showing)
        // The lock comes up over it: it goes, untold, and comes back after unlocking.
        dialog.render(activity, Verdict.ALONE, locked = true)
        idle()
        assertFalse(dialog.showing)
        dialog.render(activity, Verdict.ALONE, locked = false)
        idle()
        assertTrue(dialog.showing)

        // The request ended, or its account signed out: untold turns null, and the dialog goes.
        dialog.render(activity, null, locked = false)
        idle()
        assertFalse(dialog.showing)
        assertTrue(shownDialogs().isEmpty())
        assertEquals(0, told)
    }
}
