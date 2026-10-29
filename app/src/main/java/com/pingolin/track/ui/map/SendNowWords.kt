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

package com.pingolin.track.ui.map

import com.pingolin.track.domain.repository.LockedCircle

/**
 * The circle name a "Send location now" line may say: its readable name, never blank and never a locked
 * circle's "Locked circle" list label (or an older app version's placeholder). Without one, each line has a
 * whole unnamed version, so no stand-in is put into a frame: "this circle" in a frame reads wrong in some
 * languages, and the list label would make the map say "Location sent to Locked circle".
 */
internal fun sendNowReadableName(name: String?): String? =
    name?.takeIf { it.isNotBlank() && !LockedCircle.isPlaceholder(it) }
