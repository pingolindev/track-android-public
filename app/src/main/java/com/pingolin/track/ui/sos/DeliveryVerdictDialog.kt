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

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.domain.sos.SosDeliveryWatcher
import com.pingolin.track.domain.sos.deliveryWords

/**
 * The dialog over any screen of Track that says a help request that went reached nobody, or may
 * not have arrived. As the Safety tab's own, it can't be missed: not cancelable, and said until
 * OK, which alone marks it told. One at a time; again in an activity made again (rotation, a
 * dark-mode switch); never above the app lock (it comes back after unlocking); and gone when
 * nothing is left to tell (the request ended, or its account signed out).
 */
internal class DeliveryVerdictDialog(private val told: () -> Unit) {

    private var dialog: AlertDialog? = null
    private var shown: SosDeliveryWatcher.Verdict? = null

    val showing: Boolean get() = dialog?.isShowing == true

    /** Shows [verdict], the one still to tell, unless [locked]; null takes the dialog down. */
    fun render(activity: Activity, verdict: SosDeliveryWatcher.Verdict?, locked: Boolean) {
        if (verdict == null || locked) return dismiss()
        if (showing && shown == verdict) return
        // Not shown is not told: it waits for a screen that can show it.
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()
        val (title, body) = deliveryWords(verdict)
        shown = verdict
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> told() }
            .create()
            .also { made ->
                made.setOnDismissListener { if (dialog === made) dialog = null }
                made.show()
            }
    }

    /** Takes the dialog down without telling: it shows again where it can be seen. */
    fun dismiss() {
        val open = dialog
        dialog = null
        open?.dismiss()
    }
}
