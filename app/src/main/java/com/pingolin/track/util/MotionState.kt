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
 * Whether the device is moving, as coarsely as the tracking service needs.
 *
 * Deliberately BINARY plus "don't know". `LocationTrackingService` is the only
 * consumer and uses it to pick a GPS polling profile
 * (`POLL_MOVING_MS` / `POLL_STILL_MS` / `POLL_UNKNOWN_MS`) and to anchor a trip
 * at its start — none of which needs to know walking from driving, which is why
 * the device's own motion sensors are enough to drive it
 * (`MotionDetectionManager`).
 *
 * UNKNOWN is a real answer, not a placeholder: it means nothing has told us yet,
 * and `LocationTrackingService.maybeInferStill` is allowed to promote it to
 * STILL from GPS evidence alone. A detector that cannot work on this device must
 * therefore leave the state UNKNOWN rather than guessing STILL.
 *
 * Lives in `src/main`, apart from `MotionDetectionManager`, as the contract
 * `SessionManager` persists and `LocationTrackingService` switches on.
 */
enum class MotionState { STILL, MOVING, UNKNOWN }
