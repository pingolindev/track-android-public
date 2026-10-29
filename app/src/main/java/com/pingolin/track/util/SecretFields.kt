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

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Fields a secret is typed into in code: a password, a PIN.
 *
 * Each hides what is typed by its input type alone, single-line by that type too. A field set up the
 * other way round shows it: `setSingleLine(true)` after a password input type replaces the
 * password's masking with a single-line one (TextView.applySingleLine), so a PIN field would show
 * each digit as it is typed, on screen and to accessibility services. A field with no password type
 * at all shows the password in clear text and lets the keyboard learn it.
 *
 * [keepHidden]: Material's text field fills its field's accessibility node with the raw text, so a
 * hidden password, passphrase or PIN would reach every accessibility service in plain text (and
 * every UI dump), where a platform password field gives dots.
 */
object SecretFields {

    private const val DOT = '\u2022'

    /** A password field: hidden as typed, and a password to the keyboard, which keeps nothing of it. */
    fun password(context: Context): TextInputEditText = TextInputEditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /** A PIN field of at most [digits] digits, hidden as typed. */
    fun pin(context: Context, digits: Int): TextInputEditText = TextInputEditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(digits))
    }

    /**
     * [layout]'s field tells accessibility services what the screen shows: dots while it hides what
     * was typed, the text once its eye shows it. The rest of Material's node (hint, error, helper text)
     * stays. Call it once the field is in [layout], which sets its own delegate when the field joins it.
     */
    fun keepHidden(layout: TextInputLayout) {
        layout.setTextInputAccessibilityDelegate(object : TextInputLayout.AccessibilityDelegate(layout) {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val field = layout.editText ?: return
                val typed = field.text
                if (!typed.isNullOrEmpty() && field.transformationMethod is PasswordTransformationMethod) {
                    info.text = DOT.toString().repeat(typed.length)
                }
            }
        })
    }
}
