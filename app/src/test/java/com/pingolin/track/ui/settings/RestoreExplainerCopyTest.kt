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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the restore explainer tells a person before they pick a file.
 *
 * The restore keeps a live privacy row whole and writes the file's only where a
 * circle has none (RestoreRepository), so the explainer must not say "your
 * privacy and notification settings are replaced by the ones in the file": that
 * is the opposite of what happens to their privacy, right before the step that
 * relies on it. Notification preferences ARE replaced (one PUT over the whole
 * object).
 *
 * The two paragraphs are pinned exactly, the break included: SettingsFragment
 * shows the string as the dialog's message, and a "\n\n" lost to a space would
 * run them together.
 */
// Robolectric 4.14.1 has no SDK 36 image, so pin 34 as the rest of the suite
// does; a plain Application keeps TrackApplication's startup out of a test that
// only reads a string resource.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RestoreExplainerCopyTest {

    private fun explainer(): String =
        ApplicationProvider.getApplicationContext<Application>().getString(R.string.restore_data_explainer)

    @Test
    fun `the explainer's two paragraphs, word for word`() {
        assertEquals(
            listOf(
                "Choose a Track export file. Its places and alerts are added, and nothing already here is deleted. " +
                    "Circle memberships aren't restored.",
                "Your notification settings are replaced by the ones in the file. Your privacy settings for each " +
                    "circle stay as they are. The file's are used only for a circle that has none yet.",
            ),
            explainer().split("\n\n"),
        )
    }

    @Test
    fun `it never says a restore replaces privacy settings`() {
        assertFalse(
            "the explainer says privacy settings are replaced",
            Regex("privacy[^.;]*replaced", RegexOption.IGNORE_CASE).containsMatchIn(explainer()),
        )
    }
}
