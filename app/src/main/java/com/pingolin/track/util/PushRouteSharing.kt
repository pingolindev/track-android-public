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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Is this phone's location going nowhere for want of a push route?
 *
 * The server keeps nothing of a device but its push route, and refuses every location from one
 * without it. So on a phone with no notification app the circle gets nothing, while the phone
 * still has a marker of its own to draw and nothing to show for the failure. A Google-free build
 * with no UnifiedPush distributor lands here, and so does a normal build with no Play services.
 *
 * Asks what the server said rather than the build flavour or the push settings alone, so it can't
 * cry wolf: a phone that has not been refused says nothing. Firebase is left out because a phone
 * that has it is waiting for a token that is on its way, and a first sign-in's first fix can beat
 * it; the words here would be false a few seconds later.
 */
@Singleton
class PushRouteSharing @Inject constructor(
    private val sessionManager: SessionManager,
    private val pushNotificationManager: PushNotificationManager,
) {
    fun isBlocked(): Boolean {
        if (!sessionManager.isPushRouteRefused()) return false
        return blocked(
            refused = true,
            hasRoute = sessionManager.getPushToken() != null || sessionManager.getPushEndpoint() != null,
            provider = pushNotificationManager.getPreferredProvider(),
        )
    }

    companion object {
        /** The rule on its own. A route clears it at once: the next send registers the device. */
        fun blocked(
            refused: Boolean,
            hasRoute: Boolean,
            provider: PushNotificationManager.PushProvider,
        ): Boolean = refused && !hasRoute && provider != PushNotificationManager.PushProvider.FCM
    }
}
