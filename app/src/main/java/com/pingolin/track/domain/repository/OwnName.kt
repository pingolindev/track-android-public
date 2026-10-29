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

package com.pingolin.track.domain.repository

/**
 * This account's own sealed name in each circle, as iOS's CircleManager.ownName / ownNameOwed.
 *
 * Every app names a member only by the sealed name it opens itself, never the plaintext the server sends, so
 * an account whose roster row carries no sealed name that opens is "A member" on every other phone. Pure, so
 * each rule is tested without a repository.
 */
internal object OwnName {

    /**
     * The name this account goes by in its circles, as the other clients seal it: the display name,
     * else the address. Never blank: null when there is nothing to seal.
     */
    fun of(displayName: String?, email: String?): String? =
        listOf(displayName, email).firstNotNullOfOrNull { candidate -> candidate?.trim()?.takeIf { it.isNotEmpty() } }

    /** This account's row's sealed name as it opened here, and whether under the version this device seals with. */
    data class Opened(val text: String, val current: Boolean)

    /**
     * Whether this account owes a circle its sealed name: this device holds the circle's current key, there is
     * a name, and the account's own row carries none that opens here under the current version as that name.
     * [opened]: the row's sealed name as it opened here, null when it has none or it doesn't open.
     */
    fun owed(name: String?, opened: Opened?, holdsCurrentKey: Boolean): Boolean {
        if (!holdsCurrentKey || name.isNullOrBlank()) return false
        if (opened == null) return true
        return !opened.current || opened.text != name.trim()
    }
}
