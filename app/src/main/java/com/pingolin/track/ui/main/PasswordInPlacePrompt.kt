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
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pingolin.track.R
import com.pingolin.track.domain.repository.AuthRepository.InPlaceUnlock
import kotlinx.coroutines.launch

/**
 * This phone lost the account's signing key (its key store), which nothing in the app can
 * repair by itself: the password, typed once, puts it back. Asked in the foreground, at
 * most once per run of the app, and only while nothing else holds the screen (the app
 * lock). "Not now" leaves it until the next run. It opens the key on THIS session ([unlock]
 * is the re-auth), so nobody is signed out or asked for two-factor again.
 *
 * @param isDue whether the signing key is missing now.
 * @param onSignInNeeded the server can't re-authenticate this session: only a sign-in
 * helps.
 */
class PasswordInPlacePrompt(
    private val activity: AppCompatActivity,
    private val mayAskNow: () -> Boolean,
    private val isDue: () -> Boolean,
    private val unlock: suspend (CharArray) -> InPlaceUnlock,
    private val onSignInNeeded: () -> Unit,
) : DefaultLifecycleObserver {

    override fun onResume(owner: LifecycleOwner) = maybeAsk()

    /** Ask now if it is due and has not been asked in this run of the app. */
    fun maybeAsk() {
        if (askedThisRun || !mayAskNow() || activity.isFinishing || activity.isDestroyed) return
        if (!isDue()) return
        askedThisRun = true
        show()
    }

    private fun show() {
        val input = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        val field = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.password_in_place_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(input)
            // To accessibility services as dots, as the screen shows it.
            com.pingolin.track.util.SecretFields.keepHidden(this)
            val side = activity.resources.getDimensionPixelSize(R.dimen.password_in_place_padding)
            setPadding(side, 0, side, 0)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.password_in_place_title)
            .setMessage(R.string.password_in_place_body)
            .setView(field)
            // Set below, so a wrong password keeps the dialog open.
            .setPositiveButton(R.string.password_in_place_continue, null)
            .setNegativeButton(R.string.password_in_place_not_now, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val go = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            go.setOnClickListener { submit(dialog, field, input) }
        }
        dialog.show()
    }

    private fun submit(dialog: AlertDialog, field: TextInputLayout, input: TextInputEditText) {
        val text = input.text ?: return
        if (text.isEmpty()) return
        val password = CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
        text.clear()
        field.error = null
        setBusy(dialog, input, busy = true)
        activity.lifecycleScope.launch {
            val outcome = unlock(password)
            if (!dialog.isShowing) return@launch
            when (outcome) {
                InPlaceUnlock.UNLOCKED, InPlaceUnlock.NOTHING_TO_UNLOCK -> dialog.dismiss()
                InPlaceUnlock.SIGN_IN_NEEDED -> {
                    dialog.dismiss()
                    onSignInNeeded()
                }
                InPlaceUnlock.WRONG_PASSWORD -> {
                    setBusy(dialog, input, busy = false)
                    field.error = activity.getString(R.string.password_in_place_wrong)
                }
                InPlaceUnlock.FAILED -> {
                    setBusy(dialog, input, busy = false)
                    field.error = activity.getString(R.string.password_in_place_failed)
                }
            }
        }
    }

    private fun setBusy(dialog: AlertDialog, input: TextInputEditText, busy: Boolean) {
        input.isEnabled = !busy
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = !busy
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = !busy
    }

    companion object {
        /** Once per run of the app (process), not per screen: a rotation must not ask again. */
        @Volatile
        internal var askedThisRun = false
    }
}
