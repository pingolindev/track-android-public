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

import android.util.Log

/**
 * The circle a push names ([data]'s "circle_id", or a notification row's metadata of the same
 * shape): its one spelling ([AccountIds.circle]), or null when it names none, being no UUID or the
 * personal key's slot. The ONLY way a push names a circle: a push naming one circle under a second
 * spelling would otherwise start that circle's delivery or rotation in a namespace of its own. A
 * null runs nothing circle-specific.
 */
fun circleIdOf(data: Map<String, *>): String? {
    val raw = data["circle_id"] as? String ?: return null
    if (raw.isBlank()) return null
    return AccountIds.circle(raw) ?: null.also { Log.w("PushCircleId", "a push names no circle a circle may have; nothing circle-specific runs") }
}
