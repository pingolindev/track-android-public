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
 * An account's id as this app keeps and compares it: the lower-case, hyphenated 8-4-4-4-12 form
 * of its 16 bytes, whatever spelling the server sent. The server's word is taken only as a UUID.
 * [canonical] reads every spelling the SDK's parser (uuid 1.24) takes: 36 characters in any case,
 * 32 hex, `{…}` and `urn:uuid:…`. Anything else (whitespace, any other string) is no account id,
 * and is never used as a key or passed on.
 */
object AccountIds {

    private val HYPHENATED = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val PLAIN = Regex("[0-9a-fA-F]{32}")
    private const val URN = "urn:uuid:"

    /** [raw]'s canonical form, or null when it isn't a UUID in any spelling the SDK takes. */
    fun canonical(raw: String?): String? = when {
        raw == null -> null
        // 36 characters inside either wrapper: only the hyphenated form fits.
        raw.length == 38 && raw.startsWith('{') && raw.endsWith('}') -> strict(raw.substring(1, 37))
        raw.length == 45 && raw.startsWith(URN) -> strict(raw.substring(URN.length))
        else -> strict(raw)
    }

    /**
     * [raw]'s canonical form when it is 8-4-4-4-12 hex in any case, or 32 hex; null for anything
     * else. The narrower rule an invitation's looked-up id is held to.
     */
    fun strict(raw: String?): String? {
        val hex = when {
            raw == null -> return null
            HYPHENATED.matches(raw) -> raw.replace("-", "")
            PLAIN.matches(raw) -> raw
            else -> return null
        }.lowercase()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** Whether [a] and [b] spell the same account: both UUIDs, with the same bytes. */
    fun same(a: String?, b: String?): Boolean {
        val one = canonical(a) ?: return false
        return one == canonical(b)
    }

    /** The personal key's slot: the nil UUID, as the SDK keeps it. */
    const val PERSONAL_SLOT = "00000000-0000-0000-0000-000000000000"

    /**
     * Whether [id] names the personal key's slot: the nil UUID in any spelling [canonical] reads,
     * or this app's own slot name "self". Never a circle, whatever the server lists: nothing is
     * wrapped, stored, linked or derived for it as a circle's.
     */
    fun isPersonalSlot(id: String?): Boolean =
        id != null && (id.equals("self", ignoreCase = true) || canonical(id) == PERSONAL_SLOT)

    /**
     * [raw] as a circle's id, in its one spelling ([canonical]); null when it names no circle: the
     * personal key's slot in any spelling ([isPersonalSlot]), or no UUID. One circle is never two
     * to this app, however the server spells it.
     */
    fun circle(raw: String?): String? = raw?.takeUnless { isPersonalSlot(it) }?.let { canonical(it) }

    /**
     * [id] as a store keys it: its one spelling ([canonical]) when it's a UUID, otherwise itself,
     * trimmed and lower-cased (as on the web). A string that is no UUID is never a spelling of a
     * real circle or account, so it opens no second namespace.
     */
    fun storeKey(id: String): String = canonical(id) ?: id.trim().lowercase()
}
