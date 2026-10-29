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
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Destinations the user has chosen not to see on this device.
 *
 * The map shows every member's destinations, and only the creator may cancel
 * one (the backend enforces that on DELETE). So for the majority of what is on
 * screen — other people's — hiding is the only "I don't need to see this" that
 * can exist. It is deliberately local: the destination stays live for everyone
 * else, and the user is expressing a preference about their own map, not
 * revoking somebody's plan.
 *
 * Stored per destination id, alongside the circle it belongs to so [prune] can
 * clean up without needing every circle's destinations at once.
 */
@Singleton
class HiddenDestinationsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _hidden = MutableStateFlow(load())

    /** Emits on every change so the map can drop a marker immediately. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private fun load(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()

    private fun circleOf(): Map<String, String> =
        prefs.getStringSet(KEY_CIRCLES, emptySet())
            ?.mapNotNull { entry ->
                val i = entry.indexOf('|')
                if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
            }
            ?.toMap()
            ?: emptyMap()

    private fun persist(ids: Set<String>, circles: Map<String, String>) {
        // The id->circle map is trimmed to what is still hidden, so it can never
        // outlive the entries it describes.
        val trimmed = circles.filterKeys { it in ids }
        prefs.edit {
            putStringSet(KEY_IDS, ids)
            putStringSet(KEY_CIRCLES, trimmed.map { "${it.key}|${it.value}" }.toSet())
        }
        _hidden.value = ids
    }

    fun isHidden(destinationId: String): Boolean = _hidden.value.contains(destinationId)

    fun hide(destinationId: String, circleId: String?) {
        if (destinationId.isBlank() || isHidden(destinationId)) return
        val circles = circleOf().toMutableMap()
        if (!circleId.isNullOrBlank()) circles[destinationId] = circleId
        persist(_hidden.value + destinationId, circles)
    }

    fun unhide(destinationId: String) {
        if (!isHidden(destinationId)) return
        persist(_hidden.value - destinationId, circleOf())
    }

    /**
     * Forget hides for destinations [circleId] no longer has.
     *
     * Without this the set only grows: a destination that flips to `arrived`
     * leaves the list for good and its hide would sit in prefs forever for a row
     * nobody can see again.
     *
     * Scoped to one circle because a caller rarely holds every circle's
     * destinations at once; pruning globally against a partial list would drop
     * live hides. An entry whose circle is unknown (stored by an older version)
     * is kept rather than guessed at.
     */
    fun prune(circleId: String, liveIds: Collection<String>) {
        if (circleId.isBlank()) return
        val owner = circleOf()
        val live = liveIds.toSet()
        val kept = _hidden.value.filter { id ->
            val c = owner[id]
            c != circleId || id in live
        }.toSet()
        if (kept.size != _hidden.value.size) persist(kept, owner)
    }

    /** Sign-out: the next account must not inherit this one's hides. */
    fun clear() {
        persist(emptySet(), emptyMap())
    }

    private companion object {
        const val PREFS = "hidden_destinations"
        const val KEY_IDS = "ids"
        const val KEY_CIRCLES = "circles"
    }
}
