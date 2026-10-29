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
 * Maps place icon ID strings (as stored in the database) to their emoji equivalents.
 * This mirrors the mapping used in the web UI.
 */
object PlaceIconUtils {

    val PLACE_ICON_EMOJIS = mapOf(
        "home" to "\uD83C\uDFE0",         // 🏠
        "work" to "\uD83C\uDFE2",         // 🏢
        "school" to "\uD83C\uDFEB",       // 🏫
        "gym" to "\uD83C\uDFCB\uFE0F",   // 🏋️
        "restaurant" to "\uD83C\uDF7D\uFE0F", // 🍽️
        "coffee" to "\u2615",             // ☕
        "shopping" to "\uD83D\uDED2",     // 🛒
        "medical" to "\uD83C\uDFE5",      // 🏥
        "park" to "\uD83C\uDF33",         // 🌳
        "bar" to "\uD83C\uDF7A",          // 🍺
        "gas_station" to "\u26FD",        // ⛽
        "airport" to "\u2708\uFE0F",      // ✈️
        "hotel" to "\uD83C\uDFE8",        // 🏨
        "parking" to "\uD83C\uDD7F\uFE0F", // 🅿️
        "church" to "\u26EA",             // ⛪
        "library" to "\uD83D\uDCDA",      // 📚
        "star" to "\u2B50",               // ⭐
        "heart" to "\u2764\uFE0F"         // ❤️
    )

    private const val DEFAULT_EMOJI = "\uD83D\uDCCD" // 📍

    /**
     * Returns the emoji for a given icon ID string, or the default pin emoji if not found.
     */
    fun getEmoji(iconId: String?): String {
        if (iconId == null) return DEFAULT_EMOJI
        return PLACE_ICON_EMOJIS[iconId] ?: DEFAULT_EMOJI
    }
}
