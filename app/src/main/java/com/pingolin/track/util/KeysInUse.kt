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

import uniffi.pingolin_mobile.CryptoManager

/**
 * Whether this device holds a key IN USE for [circleId]: what sealing, minting, wrapping and
 * publishing need, the SDK's `hasCircleKeyInUse`: the key `getActiveVersion` names.
 * `hasCircleKey` also counts a personal slot holding only the RETIRED key from before a
 * password reset, and a circle whose keys are all for reading only, which still decrypt old
 * rows, so READ gates keep it.
 */
fun CryptoManager.hasKeyInUse(circleId: String): Boolean =
    runCatching { hasCircleKeyInUse(circleId) }.getOrDefault(false)
