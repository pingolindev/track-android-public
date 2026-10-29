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
 * What refuses this phone's session store now, or null when it opens: the Keystore down or
 * busy, or the store itself, all of the storage family [DatabaseUnavailable.isUnavailable]
 * knows. A start that meets one says so, or waits, instead of crashing, and never takes it for
 * "signed out": nothing can tell while the store doesn't open. Anything else is unexpected, and
 * throws.
 */
fun SessionManager.storeRefusal(): Exception? = try {
    isLoggedIn()
    null
} catch (e: Exception) {
    if (!DatabaseUnavailable.isUnavailable(e)) throw e
    // The class only: a message could name a key or a path.
    Log.w("SessionStore", "The session store can't be opened now: ${e.javaClass.simpleName}")
    e
}

/**
 * A request refused before it left, because the session store couldn't be opened: an
 * IOException, so its caller takes it as a lost connection, and one that never left, so an
 * SOS raise reads WAITING, not "maybe sent", and a queued change waits.
 */
class StoreRefusedIOException(cause: Throwable) : java.io.IOException("The session store can't be opened now", cause)
