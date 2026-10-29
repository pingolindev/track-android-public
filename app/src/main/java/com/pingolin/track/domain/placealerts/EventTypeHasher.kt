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

package com.pingolin.track.domain.placealerts

/**
 * BLAKE3(circle key || event type) — the hash a place alert is matched on.
 *
 * The mover seals a crossing's type this way (BoundaryRepository.reportCrossing),
 * an alert stores the same hash for each direction it watches
 * (PlaceRepository.eventHash), and the blind server fires by comparing one against
 * the other without learning either. [PlaceAlertEvaluator] recomputes them to read a
 * direction back off a server push.
 *
 * A function type rather than the CryptoManager itself, for the same reason
 * [com.pingolin.track.service.EncryptedOccurredAt] takes its `decrypt` as a lambda:
 * the real implementation is UniFFI, whose native library JVM unit tests cannot
 * load, and the logic worth testing here is which event-type strings get tried and
 * which alert's circle key they are keyed with — not the cipher, which mobile-sdk
 * covers against a known key.
 *
 * Returns null when there is no key for the circle (never held, or rotated away).
 */
fun interface EventTypeHasher {
    fun hash(circleId: String, eventType: String): String?
}
