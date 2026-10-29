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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-cutting UX events that repositories and services emit, and the
 * hosting Activity observes to surface toasts/snackbars.
 *
 * Repository code reports on its own failures via return values and Log.
 * The bus is a separate channel for *user-visible* consequences — things a
 * user would want a notice about (you were removed, sharing is stale, etc).
 *
 * One-shot semantics: uses a SharedFlow with no replay, so events only fire
 * for current observers. Repositories don't need to care whether the UI is
 * attached; if no-one's listening, the event is dropped on the floor.
 */
sealed class UxEvent {
    /**
     * A circle we thought we were in is gone from the server's list of ours (a 404, then the whole
     * list without it). Removed, left from another device, or deleted by its owner: this can't tell
     * which, so it says only that. A deletion has the server's own notice ("A circle was
     * deleted").
     */
    data class CircleGone(val circleName: String) : UxEvent()

    /**
     * No successful location send to any circle in a while. `ageMillis` is how long
     * since the last success. Emitted once per transition into "stale" — not on every
     * failed send — to avoid notification spam.
     */
    data class SendingStale(val ageMillis: Long) : UxEvent()

    /** After an invite accept, the circle key never arrived — user can see the circle but not locations yet. */
    data class CircleKeyPending(val circleId: String) : UxEvent()

    /** Stream or pull decryption dropped locations because keys are missing. */
    data class DecryptionKeyMissing(val circleId: String, val count: Int) : UxEvent()

    /**
     * User requested a history range the server silently clamped (typically
     * free-tier selecting "7 days" and getting back 2). `requestedDays` is
     * what they asked for, `allowedDays` is what they actually got.
     */
    data class HistoryClamped(val requestedDays: Int, val allowedDays: Int) : UxEvent()

    /**
     * The server could not fit the requested window under its row cap, so it
     * dropped the OLDEST groups — the far end of the trail is missing.
     *
     * Distinct from [HistoryClamped] in cause and in remedy. That one is a plan
     * limit and is lifted by upgrading; this one is a size limit and is avoided
     * by asking for a NARROWER date range. There is no "next page" to offer: the
     * response already spans the whole window.
     *
     * The server sets its `truncated` flag for exactly this purpose, so that the
     * loss is never silent.
     */
    data class HistoryTruncated(val circleId: String) : UxEvent()

    /**
     * This device's own location fixes got too rough to judge some of the user's
     * places by, so those alerts are not being evaluated. `placeCount` is how many
     * places went dark.
     *
     * A fix whose error exceeds a geofence's radius says nothing about it — see
     * PlaceAlertRules.canResolve. Declining to guess is right; declining SILENTLY
     * is how a feature disappears without anyone being able to tell it from a bug.
     * Emitted on the transition only (PlaceAlertEvaluator.noteCoarseFixes), like
     * [SendingStale].
     *
     * Chiefly reached from LocationHeartbeatWorker's LocationAccuracy.BALANCED
     * request, NOT from battery-saver — the tracking service asks for HIGH in
     * every mode. See LocationPolicy.MIN_ACCURACY_BALANCED.
     */
    data class PlaceAlertsTooCoarse(val placeCount: Int) : UxEvent()
}

@Singleton
class UxEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<UxEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<UxEvent> = _events.asSharedFlow()

    fun emit(event: UxEvent) {
        _events.tryEmit(event)
    }
}
