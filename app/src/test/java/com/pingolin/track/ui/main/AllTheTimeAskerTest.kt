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

import android.Manifest
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
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
 * Asked on an opening while "Allow all the time" is missing; not when it is granted or
 * when they are in no circle; once per opening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AllTheTimeAskerTest {

    private var now = 1_000_000_000L
    private var continued = 0
    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var asker: AllTheTimeAsker

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().also { it.setTheme(com.pingolin.track.R.style.Theme_Track) }
        asker = AllTheTimeAsker(activity, mayAskNow = { true }, onContinue = { continued++ }, clock = { now })
        activity.lifecycle.addObserver(asker)
        controller.setup()
        shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(activity.application).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun asked(): Int = ShadowDialog.getShownDialogs().count {
        it is AlertDialog && it.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
            ?.text?.toString() == "Keep sharing when the app is closed"
    }

    private fun press(which: Int) {
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Out of the app for [ms], then back: stop, then restart as Android does. */
    private fun leaveFor(ms: Long) {
        controller.pause().stop()
        now += ms
        controller.restart().resume()
    }

    @Test
    fun `asked on an opening while Allow all the time is missing, and Continue goes to it`() {
        asker.maybeAsk(sharesWithACircle = true)

        assertEquals(1, asked())
        press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(1, continued)
    }

    @Test
    fun `not asked once it is granted`() {
        shadowOf(controller.get().application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        asker.maybeAsk(sharesWithACircle = true)

        assertEquals(0, asked())
    }

    @Test
    fun `not asked of someone in no circle`() {
        asker.maybeAsk(sharesWithACircle = false)

        assertEquals(0, asked())
    }

    @Test
    fun `once per opening - Not now puts it off to the next opening, not for good`() {
        asker.maybeAsk(sharesWithACircle = true)
        press(DialogInterface.BUTTON_NEGATIVE) // "Not now"
        asker.maybeAsk(sharesWithACircle = true)
        assertEquals("not twice in one opening", 1, asked())

        leaveFor(10_000L) // a file picker, or the settings page: the same opening
        asker.maybeAsk(sharesWithACircle = true)
        assertEquals(1, asked())

        leaveFor(AllTheTimeAsk.SAME_OPENING_MS + 1) // opened again
        asker.maybeAsk(sharesWithACircle = true)
        assertEquals(2, asked())
        assertTrue("Not now never blocked the app", !controller.get().isFinishing)
    }

    @Test
    fun `the first-run chain's own question counts for the opening`() {
        asker.show(onNotNow = {})
        asker.maybeAsk(sharesWithACircle = true)

        assertEquals(1, asked())
    }

    @Test
    fun `not asked while something else holds the screen`() {
        val locked = AllTheTimeAsker(controller.get(), mayAskNow = { false }, onContinue = {}, clock = { now })

        locked.maybeAsk(sharesWithACircle = true)

        assertEquals(0, asked())
    }
}
