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

/**
 * What a person reads about a failure: never text that isn't this app's (as iOS's
 * `Error.plainText`). The SDK's errors describe themselves in key talk (`Key not found: …`),
 * and its network error can carry the server's words; a socket's or a parser's message is for
 * developers. Those get this app's line, and their detail goes to the log only. An exception
 * this app raised itself says what it says: repositories fail with plain
 * `Exception("Couldn't … just now.")` or one of this app's own exception types.
 */
fun Throwable.plainText(fallback: String): String = when (this) {
    is uniffi.pingolin_mobile.MobileException.NetworkException -> UNREACHABLE_TEXT
    is uniffi.pingolin_mobile.MobileException -> fallback
    is java.io.IOException -> UNREACHABLE_TEXT
    else -> message?.takeIf { it.isNotBlank() && saidByThisApp(this) } ?: fallback
}

/** As [plainText], with null where this app's own fallback line would go: for a view model, which has no resources. */
fun Throwable.plainTextOrNull(): String? = plainText("").takeIf { it.isNotEmpty() }

/** "Pingolin couldn't be reached." The same words as on iPhone. */
const val UNREACHABLE_TEXT = "Pingolin couldn't be reached. Check your connection and try again."

/** This phone's secure storage (its Keystore) refused. No key talk. */
const val SECURE_STORAGE_TEXT = "Track couldn't set up its secure storage on this phone. Please try again."

/** The database's key refused in a way that may pass: nothing was deleted. No key talk. */
const val DATABASE_KEY_REFUSED_TEXT = "Track couldn't open what it had saved on this phone. Please try again."

private fun saidByThisApp(t: Throwable): Boolean =
    t.javaClass == Exception::class.java || t.javaClass.name.startsWith("com.pingolin.track.")
