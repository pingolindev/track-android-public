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

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.databinding.DialogCreateCircleBinding

/**
 * The "Create Circle" sheet. Pressing Create does NOT close it: creating a circle is
 * agreeing to share with it, so the location notice comes first, and only agreeing
 * closes the sheet. "Not now" on the notice leaves the sheet open with the name kept
 * and says nothing — the same on every client.
 *
 * @param onCreate called with the trimmed, non-blank name, and `answered` to call with
 *   whether the notice was agreed to: true closes the sheet, false lets Create be
 *   pressed again.
 */
object CreateCircleSheet {

    fun show(context: Context, onCreate: (name: String, answered: (agreed: Boolean) -> Unit) -> Unit): AlertDialog {
        val binding = DialogCreateCircleBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Create Circle")
            .setView(binding.root)
            // No listener here: the builder's would dismiss on every press.
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .show()
        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        // One notice at a time: a second press while it is up would stack another, and
        // agreeing to both would create the circle twice.
        var asking = false
        create.setOnClickListener {
            val name = binding.etCircleName.text?.toString()?.trim().orEmpty()
            if (asking || name.isBlank()) return@setOnClickListener
            asking = true
            create.isEnabled = false
            onCreate(name) { agreed ->
                asking = false
                if (agreed) dialog.dismiss() else create.isEnabled = true
            }
        }
        return dialog
    }
}
