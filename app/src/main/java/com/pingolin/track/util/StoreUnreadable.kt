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
 * A store's file is there and can't be read now: the Keystore refused it, or a value in it is
 * damaged. What it holds isn't known, so it's never "nothing stored": nothing is written over it
 * or deleted from it, what it decides waits, and it's read again at its next use (as the iOS
 * app's StoredFile).
 */
class StoreUnreadable(store: String, cause: Throwable? = null) : Exception("$store can't be read now", cause) {

    companion object {
        /** [read]'s value, or [StoreUnreadable] for [store] when it throws. */
        inline fun <T> reading(store: String, read: () -> T): T = try {
            read()
        } catch (e: StoreUnreadable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnreadable(store, e)
        }
    }
}
