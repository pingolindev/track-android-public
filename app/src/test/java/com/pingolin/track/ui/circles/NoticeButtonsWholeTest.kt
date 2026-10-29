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

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * Both of the location notice's buttons are whole at every font scale, the decline too: it is a
 * legal answer and is always there to tap. A body capped at a fixed share of the screen would leave
 * the dialog's button bar to pay for the rest, cutting "Not now" in half on a 411 x 891 dp screen
 * at a font scale of 1.1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class NoticeButtonsWholeTest {

    private fun shownAt(fontScale: Float): androidx.appcompat.app.AlertDialog {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(com.pingolin.track.R.style.Theme_Track)
        // The bundled notice, as long as the server's.
        LocationConsentDialog.show(activity, "Rivera Family", null, LocationConsentDialog.Purpose.JOIN, onGrant = {}, onDecline = {})
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        return ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
    }

    private fun assertButtonsWhole(fontScale: Float) {
        val dialog = shownAt(fontScale)
        val decline = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        val bar = (decline.parent as View).parent as ScrollView
        val buttons = bar.getChildAt(0)
        assertTrue("laid out at $fontScale", bar.height > 0 && decline.height > 0)
        assertTrue(
            "at font scale $fontScale the button bar shows ${bar.height} of its ${buttons.height} px",
            bar.height >= buttons.height,
        )
        val minTouch = 48 * decline.resources.displayMetrics.density
        assertTrue("\"Not now\" is ${decline.height} px tall at $fontScale", decline.height >= minTouch - 1)
    }

    @Test fun `at the phone's usual font`() = assertButtonsWhole(1.0f)

    @Test fun `at 1_1`() = assertButtonsWhole(1.1f)

    @Test fun `at 1_3`() = assertButtonsWhole(1.3f)

    @Test fun `at 2_0`() = assertButtonsWhole(2.0f)
}
