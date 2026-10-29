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

package com.pingolin.track.domain.repository

/**
 * What became of one location sent now to a circle ([LocationRepository.sendLocation]), so that
 * what the map says is what happened: "sent" only for one that went.
 */
enum class SendOutcome {
    /** Handed to the live connection. */
    SENT,
    /** Kept on this phone to go when it can: offline, or the connection dropped around the send. */
    QUEUED,
    /** Kept until this phone holds the circle's current settings. */
    WAITING_FOR_CIRCLE,
    /** Not sent there: sharing with the circle is paused. */
    PAUSED,
    /** Not sent there: real-time sharing with the circle is off. */
    REALTIME_OFF,
}
