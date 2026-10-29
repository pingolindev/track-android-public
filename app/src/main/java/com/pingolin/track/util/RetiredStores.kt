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

import android.content.Context
import android.util.Log

/**
 * Files earlier versions kept and this one does not, purged best-effort at every launch and
 * sign-in, as iOS's purgeRetiredItems: what they held is never read again, and a file left behind
 * would keep it on the phone. A purge that fails fails nothing; the next launch or sign-in tries
 * again.
 */
object RetiredStores {

    private const val TAG = "RetiredStores"

    /**
     * The name each circle was last read under, which earlier versions kept in
     * "circle_names_read_here_2" (and, before that, "circle_names_read_here"); nothing
     * reads these files now.
     */
    internal val FILES = listOf("circle_names_read_here_2", "circle_names_read_here")

    fun purge(context: Context) {
        for (file in FILES) {
            runCatching { context.deleteSharedPreferences(file) }
                .onFailure { Log.w(TAG, "A retired store couldn't be purged now: ${it.javaClass.simpleName}") }
        }
    }
}
