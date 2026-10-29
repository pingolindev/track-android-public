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

package com.pingolin.track.util

import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Show a confirmation whose positive button DELETES something, with that button
 * painted in the theme's error colour.
 *
 * WHY THIS EXISTS
 *   A confirm that renders both buttons identically — same colour, same weight —
 *   leaves "Delete" and "Cancel" distinguished only by their words, and
 *   Material's guidance is explicit that a destructive action should not look
 *   like the safe one sitting beside it. On the account dialog the two buttons
 *   end four paragraphs of consequences that cannot be undone.
 *
 *   The colour cannot come from the dialog THEME: a theme-level positive-button
 *   style paints every positive button in the app red, including Save and OK. It
 *   has to be per-dialog, which means per call site, which means a helper — or
 *   twelve copies of the same four lines, eleven of which will drift.
 *
 * USE IT INSTEAD OF `.show()` on any dialog whose positive button destroys,
 * removes, revokes or signs out. Everything else keeps `.show()`.
 *
 * `colorError` lives in appcompat, not material — the same lookup SettingsFragment
 * already uses for the recovery-email warning.
 */
fun MaterialAlertDialogBuilder.showDestructive(): AlertDialog {
    val dialog = show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
        button.setTextColor(
            MaterialColors.getColor(button, androidx.appcompat.R.attr.colorError)
        )
    }
    return dialog
}
