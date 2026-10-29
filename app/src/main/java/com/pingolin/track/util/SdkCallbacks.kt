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
 * The SDK's callbacks that return no Result (BoundaryMonitorCallback, InactivityAlertCallback,
 * StreamErrorCallback, and the others it declares that way) can't take a throw: UniFFI panics on
 * one, taking the SDK down with it. Every body this app gives them runs through here: caught,
 * logged by its type (never its message, which can quote what it held), and never rethrown.
 */
internal inline fun sdkCallback(tag: String, what: String, body: () -> Unit) {
    try {
        body()
    } catch (e: Throwable) {
        Log.e(tag, "$what failed (${e.javaClass.simpleName}); not passed on to the SDK")
    }
}
