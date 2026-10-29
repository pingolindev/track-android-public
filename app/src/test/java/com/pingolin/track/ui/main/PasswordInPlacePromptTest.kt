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

package com.pingolin.track.ui.main

import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pingolin.track.domain.repository.AuthRepository.InPlaceUnlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * When this phone lost its signing key, the password is asked once, in the foreground, in
 * place: never while the app lock holds the screen, never twice in a run of the app, and a
 * wrong password keeps the question open with one plain line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PasswordInPlacePromptTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var prompt: PasswordInPlacePrompt
    private var due = true
    private var locked = false
    private var answer = InPlaceUnlock.UNLOCKED
    private val typed = mutableListOf<String>()
    private var signInNeeded = 0

    @Before
    fun setUp() {
        PasswordInPlacePrompt.askedThisRun = false
        ShadowDialog.reset()
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().also { it.setTheme(com.pingolin.track.R.style.Theme_Track) }
        prompt = PasswordInPlacePrompt(
            activity,
            mayAskNow = { !locked },
            isDue = { due },
            unlock = { password -> typed += String(password); answer },
            onSignInNeeded = { signInNeeded++ },
        )
        activity.lifecycle.addObserver(prompt)
    }

    private fun shown(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { dialog ->
        dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString() == "Enter your password"
    }

    private fun type(dialog: AlertDialog, password: String) {
        val input = findInput(dialog.window!!.decorView)!!
        input.setText(password)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findInput(view: android.view.View): TextInputEditText? = when (view) {
        is TextInputEditText -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findInput(view.getChildAt(it)) }
        else -> null
    }

    private fun findField(view: android.view.View): TextInputLayout? = when (view) {
        is TextInputLayout -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findField(view.getChildAt(it)) }
        else -> null
    }

    @Test
    fun `asked in the foreground when the signing key is missing, and the password goes to the unlock`() {
        controller.setup()
        val dialog = shown()!!

        type(dialog, "correct horse")

        assertEquals(listOf("correct horse"), typed)
        assertFalse("unlocked: gone", dialog.isShowing)
    }

    @Test
    fun `a wrong password keeps the question open with one plain line`() {
        answer = InPlaceUnlock.WRONG_PASSWORD
        controller.setup()
        val dialog = shown()!!

        type(dialog, "wrong")

        assertTrue(dialog.isShowing)
        assertEquals("That password isn't right.", findField(dialog.window!!.decorView)!!.error?.toString())
        assertEquals("the field is emptied", "", findInput(dialog.window!!.decorView)!!.text.toString())

        answer = InPlaceUnlock.UNLOCKED
        type(dialog, "correct horse")
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `once per run - not after Not now, a rotation or the next resume`() {
        controller.setup()
        shown()!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowDialog.reset()

        controller.pause().stop()
        controller.restart().resume()
        prompt.maybeAsk()

        assertEquals(null, shown())
    }

    @Test
    fun `not asked when nothing is missing, nor while the app lock holds the screen`() {
        due = false
        controller.setup()
        assertEquals(null, shown())

        due = true
        locked = true
        prompt.maybeAsk()
        assertEquals(null, shown())

        // The lock lifts: now it is asked.
        locked = false
        prompt.maybeAsk()
        assertTrue(shown() != null)
    }

    @Test
    fun `the server asks for a sign-in - the question closes and the sign-in path takes over`() {
        answer = InPlaceUnlock.SIGN_IN_NEEDED
        controller.setup()
        val dialog = shown()!!

        type(dialog, "correct horse")

        assertFalse(dialog.isShowing)
        assertEquals(1, signInNeeded)
    }
}
