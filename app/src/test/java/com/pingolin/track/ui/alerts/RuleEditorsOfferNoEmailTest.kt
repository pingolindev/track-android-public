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

package com.pingolin.track.ui.alerts

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Low-battery and inactivity rules can't be e-mailed. They are judged on the watcher's own
 * phone, so the server never learns who watches whom, and an e-mail would hand it every
 * firing. Neither editor (the rule editor and the inactivity dialog) offers an "Email
 * notification" box on those rules; Push stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleEditorsOfferNoEmailTest {

    private fun checkBoxes(view: View): List<CheckBox> = when (view) {
        is CheckBox -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { checkBoxes(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `the battery and inactivity rule editors offer push, and no email`() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Track)
        for (layout in listOf(R.layout.dialog_alert_editor, R.layout.dialog_create_inactivity_alert)) {
            val labels = checkBoxes(LayoutInflater.from(context).inflate(layout, null)).map { it.text.toString() }
            assertTrue("push stays: $labels", "Push notification" in labels)
            assertTrue("no email box: $labels", labels.none { it.contains("mail", ignoreCase = true) })
        }
    }
}
