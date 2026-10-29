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
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R

/**
 * Asks for "Allow all the time" at each opening of the app, once: someone who chose "Not
 * now" is asked again at the next opening.
 *
 * An opening is a launch, or a return after more than [AllTheTimeAsk.SAME_OPENING_MS]
 * away; a quick return — a file picker, the settings page this question itself opened — is
 * the same opening. Observe the activity's lifecycle with it, and call [maybeAsk] whenever
 * the facts may have changed; [AllTheTimeAsk.due] decides.
 *
 * @param mayAskNow false while something else holds the screen (the app lock).
 * @param onContinue sends them to Android's page for it (on Android 10+ the request opens
 *   the SETTINGS page, where they pick "Allow all the time" themselves).
 */
class AllTheTimeAsker(
    private val activity: AppCompatActivity,
    private val mayAskNow: () -> Boolean,
    private val onContinue: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {

    /** The question was put in this opening (here, or by the first-run chain through [show]). */
    var askedThisOpening = false
        private set
    private var leftForegroundAt = 0L

    override fun onStart(owner: LifecycleOwner) {
        if (leftForegroundAt != 0L && AllTheTimeAsk.isNewOpening(clock() - leftForegroundAt)) {
            askedThisOpening = false
        }
        leftForegroundAt = 0L
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!activity.isChangingConfigurations) leftForegroundAt = clock()
    }

    /** Ask now if it is due: this person shares with a circle and has only "while using". */
    fun maybeAsk(sharesWithACircle: Boolean) {
        if (!mayAskNow() || activity.isFinishing || activity.isDestroyed) return
        val preciseGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!AllTheTimeAsk.due(
                sdkInt = Build.VERSION.SDK_INT,
                preciseGranted = preciseGranted,
                backgroundGranted = backgroundGranted,
                sharesWithACircle = sharesWithACircle,
                askedThisOpening = askedThisOpening,
            )
        ) return
        show(onNotNow = {})
    }

    /**
     * "Keep sharing when the app is closed", which says where Continue goes: being dropped
     * on the settings page with no warning only confuses. Counts as this opening's question.
     * [onNotNow] runs on "Not now", which never blocks the app: the question comes back at
     * the next opening.
     */
    fun show(onNotNow: () -> Unit) {
        askedThisOpening = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.background_location_rationale_title)
            .setMessage(R.string.background_location_rationale_body)
            .setPositiveButton(R.string.location_rationale_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.location_rationale_skip) { _, _ -> onNotNow() }
            .setCancelable(false)
            .show()
    }
}
