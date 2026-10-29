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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the inline errors on the sign-in form.
 *
 * Pressing Sign In on an empty form marks the fields themselves, not just a
 * Toast, so an accessibility service finds the error on the field. These are
 * the rules that decide which box turns red and what it says.
 */
class AuthFormValidationTest {

    @Test
    fun `an empty login form faults both fields`() {
        val errors = AuthFormValidation.forLogin(email = "", passwordLength = 0)
        assertEquals(
            listOf(AuthField.EMAIL, AuthField.PASSWORD),
            errors.map { it.field },
        )
        assertEquals(AuthFormValidation.EMAIL_REQUIRED, errors[0].message)
        assertEquals(AuthFormValidation.PASSWORD_REQUIRED, errors[1].message)
    }

    @Test
    fun `a filled login form faults nothing`() {
        assertTrue(
            AuthFormValidation.forLogin("user1@example.test", passwordLength = 12).isEmpty()
        )
    }

    /**
     * The email field carries the FIRST error, because it is the first field: the
     * activity focuses `errors.first()`, so the order here decides where the
     * keyboard lands.
     */
    @Test
    fun `the email fault comes first so focus lands on the top field`() {
        val errors = AuthFormValidation.forLogin("", passwordLength = 0)
        assertEquals(AuthField.EMAIL, errors.first().field)
    }

    @Test
    fun `an address without an at sign or a dotted domain is rejected`() {
        listOf("user", "user@", "@host.com", "user@host", "user name@host.com").forEach { bad ->
            val errors = AuthFormValidation.forLogin(bad, passwordLength = 8)
            assertEquals(
                "should have faulted \"$bad\"",
                listOf(AuthField.EMAIL),
                errors.map { it.field },
            )
            assertEquals(AuthFormValidation.EMAIL_INVALID, errors.first().message)
        }
    }

    /**
     * Deliberately permissive. Plus-addressing is common, and a stricter
     * pattern's only achievement would be rejecting addresses that work.
     */
    @Test
    fun `real-world addresses pass`() {
        listOf(
            "test-user1+tag2@example.test",
            "a.b@c.co.uk",
            "UPPER@Example.COM",
        ).forEach { good ->
            assertTrue("should have accepted \"$good\"", AuthFormValidation.forLogin(good, 8).isEmpty())
        }
    }

    /**
     * Password STRENGTH is not this function's job — the server owns those rules
     * and AuthViewModel already reports them. A second copy here is the one that
     * goes stale.
     */
    @Test
    fun `a short password is not faulted here`() {
        assertTrue(AuthFormValidation.forLogin("a@b.com", passwordLength = 1).isEmpty())
    }
}
