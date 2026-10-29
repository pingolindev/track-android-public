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

package com.pingolin.track.ui.auth

/**
 * What is wrong with the sign-in form, per field.
 *
 * WHY IT EXISTS
 *   A Toast on an empty form ("Please enter email and password") leaves both
 *   fields exactly as they were: it reads as silent validation, with no error on
 *   any field for accessibility services either, and a button that appears to do
 *   nothing is the shape users retry rather than correct. A Toast also cannot
 *   say WHICH field.
 *
 *   [AuthViewModel] keeps its own checks; they are the backstop for anything
 *   that reaches it another way, and they are what guards the SRP call. This
 *   runs first, at the button, and answers a different question: which box
 *   should the user look at.
 *
 * Pure, so the rules are unit-tested rather than inferred from a screenshot.
 */
enum class AuthField { EMAIL, PASSWORD }

data class AuthFieldError(val field: AuthField, val message: String)

object AuthFormValidation {

    const val EMAIL_REQUIRED = "Enter your email address"
    const val EMAIL_INVALID = "Enter a valid email address"
    const val PASSWORD_REQUIRED = "Enter your password"

    /**
     * Deliberately loose: one @, something before it, a dot-bearing domain after.
     * The server and the mailbox are the real authorities on whether an address
     * exists, and a strict client-side pattern's only achievement is rejecting
     * addresses that work. This catches the typo class — "user", "user@", "a@b" —
     * that would otherwise cost a round trip to learn about.
     */
    private val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun forLogin(email: String, passwordLength: Int): List<AuthFieldError> {
        val errors = mutableListOf<AuthFieldError>()
        errors += emailErrors(email)
        if (passwordLength == 0) errors += AuthFieldError(AuthField.PASSWORD, PASSWORD_REQUIRED)
        return errors
    }

    private fun emailErrors(email: String): List<AuthFieldError> = when {
        email.isBlank() -> listOf(AuthFieldError(AuthField.EMAIL, EMAIL_REQUIRED))
        !EMAIL_SHAPE.matches(email) -> listOf(AuthFieldError(AuthField.EMAIL, EMAIL_INVALID))
        else -> emptyList()
    }
}
