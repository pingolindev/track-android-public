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

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One account's member pins kept across a sign-out under two spellings of its id (AccountIds),
 * merged into one export blob before a single import: the SDK's import lets a later record replace
 * a held one, so importing the blobs in turn would let the second win. Per pin, the rule the SDK
 * and the web share: equal keys make one pin (the earliest first pinning, the latest check, the
 * later approval); different keys make the pin that reads as changed until the person approves
 * one (both keys 64 zeros: never matched, never a first contact). The ids in a pin's key are
 * taken canonical first (AccountIds), so two spellings of one member are one pin by the same
 * rule, whichever SDK imports it (the format-2 SDK does the same at load).
 */
internal object MemberPinBlobs {

    /** Both keys of a pin that reads as changed until approved. */
    const val CHANGED_UNTIL_APPROVED = "0000000000000000000000000000000000000000000000000000000000000000"

    private const val IDENTITY_PINS = "identity_pins"
    private val MAPS = listOf("pins", IDENTITY_PINS)

    /** [blobs] as one blob for [owner]; null for a single blob, which is imported as it is. */
    fun merged(blobs: List<String>, owner: String): String? {
        if (blobs.size < 2) return null
        val parsed = blobs.map { JsonParser.parseString(it).asJsonObject }
        val out = parsed.first().deepCopy()
        out.addProperty("owner_user_id", owner)
        for (map in MAPS) {
            val pins = JsonObject()
            for (blob in parsed) {
                val records = blob.get(map)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                for ((raw, record) in records.entrySet()) {
                    val key = if (map == IDENTITY_PINS) AccountIds.canonical(raw) ?: raw else pinKey(raw)
                    val held = pins.get(key)?.asJsonObject
                    pins.add(key, if (held == null) record.deepCopy() else pin(held, record.asJsonObject))
                }
            }
            out.add(map, pins)
        }
        return out.toString()
    }

    /** A member pin's key, `{circle}:{user}`, with both ids canonical when both are UUIDs; any other key as it is. */
    private fun pinKey(key: String): String {
        val parts = key.split(':', limit = 2)
        if (parts.size != 2) return key
        val circle = AccountIds.canonical(parts[0]) ?: return key
        val user = AccountIds.canonical(parts[1]) ?: return key
        return "$circle:$user"
    }

    private fun pin(a: JsonObject, b: JsonObject): JsonObject {
        val same = a.text(SHARING).equals(b.text(SHARING), ignoreCase = true) &&
            a.text(SIGNING).equals(b.text(SIGNING), ignoreCase = true)
        return a.deepCopy().apply {
            if (!same) {
                addProperty(SHARING, CHANGED_UNTIL_APPROVED)
                addProperty(SIGNING, CHANGED_UNTIL_APPROVED)
            }
            listOfNotNull(a.number(FIRST), b.number(FIRST)).minOrNull()?.let { addProperty(FIRST, it) }
            listOfNotNull(a.number(VERIFIED), b.number(VERIFIED)).maxOrNull()?.let { addProperty(VERIFIED, it) }
            val approved = if (same) listOfNotNull(a.number(APPROVED), b.number(APPROVED)).maxOrNull() else null
            if (approved != null) addProperty(APPROVED, approved) else add(APPROVED, JsonNull.INSTANCE)
        }
    }

    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.number(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private const val SHARING = "sharing_pubkey_hex"
    private const val SIGNING = "signing_pubkey_hex"
    private const val FIRST = "first_pinned_at"
    private const val VERIFIED = "last_verified_at"
    private const val APPROVED = "user_approved_change_at"
}
