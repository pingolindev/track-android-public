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

package com.pingolin.track.ui.alerts

/**
 * How a low-battery or inactivity rule notifies, as its editor shows it.
 *
 * These rules offer Push only, no e-mail. A rule stored with e-mail and no push still notifies:
 * the server wakes the phones for either flag and the phones post it. Shown with Push off, a
 * save would send push false and e-mail false — a rule that notifies nobody on a server that
 * does not turn e-mail-only rules into push ones. So the editor shows such a rule with Push on,
 * the same mapping, and a save keeps it notifying.
 */
internal object RuleChannels {
    /** Whether the Push box starts ticked for a stored rule. */
    fun pushShown(notifyPush: Boolean, notifyEmail: Boolean): Boolean = notifyPush || notifyEmail
}
