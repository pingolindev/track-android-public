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
 * Play Integrity error types.
 *
 * Lives in `src/main` while [PlayIntegrityManager] itself has a copy per
 * distribution flavour, so the two copies cannot drift on the contract
 * [AttestationManager] catches: it handles [PlayIntegrityException] to fall back
 * to hardware attestation or Turnstile, and that fallback is the entire reason a
 * libre build can still register.
 */
enum class PlayIntegrityError {
    PLAY_SERVICES_UNAVAILABLE,
    TOKEN_REQUEST_FAILED,
    VERIFICATION_FAILED
}

/**
 * Exception thrown by Play Integrity operations
 */
class PlayIntegrityException(
    val error: PlayIntegrityError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
