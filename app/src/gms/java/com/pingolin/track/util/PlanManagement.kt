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
 * GMS FLAVOUR: whether the premium prompt may link to the account portal.
 *
 * It may not. This build ships through Google Play without Play Billing, and
 * Play's payments policy prohibits directing users to an external purchase
 * method for digital goods — so the prompt's "Manage your plan at pingolin.com"
 * line stays plain text here, exactly as `TrackPremiumBottomSheet` and
 * `bottom_sheet_premium.xml` document. The libre counterpart, which ships
 * outside Play, turns the same pointer into a button.
 *
 * Same object, same package, same API in both flavours so `src/main` can
 * reference it without knowing which distribution it is in.
 */
object PlanManagement {
    /** Play build: no tappable route to the portal from a quota prompt. */
    const val CAN_LINK_TO_PORTAL: Boolean = false
}
