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
 * LIBRE FLAVOUR: whether the premium prompt may link to the account portal.
 *
 * It may. This build is distributed outside Google Play, so the Play payments
 * policy that keeps the gms prompt's pointer as plain text does not apply, and
 * the one moment a user wants to change their plan gets a button that opens
 * the portal's subscription page (`AccountRecovery.subscriptionUrl`, on the
 * configured `ACCOUNT_BASE_URL`, so a prod build never reaches the dev portal).
 *
 * Same object, same package, same API as the gms counterpart.
 */
object PlanManagement {
    /** Libre build: the prompt offers a "Manage plan" button. */
    const val CAN_LINK_TO_PORTAL: Boolean = true
}
