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

package com.pingolin.track.ui.settings

import android.content.res.Resources
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.QuotaDto

/**
 * Copy for the Plan card's history grace countdown.
 *
 * Kept out of [SettingsFragment] so the sentence can be asserted directly:
 * the wording is what matters, and a fragment-rendering test would only ever
 * show that a TextView was visible.
 */
object PlanCardCopy {

    /**
     * The whole grace-countdown decision, from the quota response: whether to say
     * anything at all, and which of the two remedies applies.
     *
     * @return the sentence, or null when nothing is counting down and the note
     *   should be hidden. The server sends `history_expires_in_days` ONLY to
     *   someone who is on the free tier now and was premium recently, so null
     *   covers both "premium is active" and "there was never premium to lose" —
     *   neither of which should nag.
     *
     * Lives here rather than in [SettingsFragment] because the two decisions —
     * show it, and which subject it is about — are the parts worth asserting, and
     * a fragment can only be asserted on with an instrumentation test.
     */
    fun historyExpiring(res: Resources, quota: QuotaDto): String? {
        // Explicit null check, not a truthy one: 0 is a real value meaning the
        // history goes TODAY, which is the most urgent case there is.
        val daysLeft = quota.historyExpiresInDays ?: return null
        return historyExpiring(
            res,
            daysLeft = daysLeft.toInt(),
            keptDays = quota.historyDays.toInt(),
            circleScoped = quota.historyExpiresCircleId != null,
        )
    }

    /**
     * "Location history older than 2 days is deleted in 5 days. Resubscribe, or
     * have a premium member join your circle, to keep it." — or, when the clock
     * belongs to the user's own history, the same sentence ending "Resubscribe to
     * keep it."
     *
     * @param daysLeft the server's `history_expires_in_days`. Clamped at zero
     *   server-side, so 0 is a real value meaning "today" — rendering it through
     *   the plural would produce "in 0 days", which reads like an error rather
     *   than a deadline.
     * @param keptDays the server's `history_days` — what survives the deletion.
     *   Taken from the quota response rather than a hard-coded free-tier 2, so a
     *   server configured differently is reported honestly. The paid summary
     *   follows the same rule.
     * @param circleScoped whether the server's `history_expires_circle_id` is
     *   present. It is the ONLY thing that separates the two subjects the
     *   countdown can describe: the server's expiry covers the user's own
     *   history as well as their circles', and it reports whichever expires
     *   first. Getting this wrong in the false direction tells a user to have a
     *   premium member join a circle, which does nothing for their personal
     *   history — the nightly cull judges that on their own premium. Defaults to
     *   true so a caller that does not pass it keeps the circle wording rather
     *   than silently switching every user to the personal sentence.
     */
    fun historyExpiring(
        res: Resources,
        daysLeft: Int,
        keptDays: Int,
        circleScoped: Boolean = true,
    ): String {
        val kept = res.getQuantityString(R.plurals.plan_history_kept, keptDays, keptDays)
        val when_ = when {
            daysLeft <= 0 -> res.getString(R.string.plan_history_when_today)
            daysLeft == 1 -> res.getString(R.string.plan_history_when_tomorrow)
            else -> res.getQuantityString(R.plurals.plan_history_when_days, daysLeft, daysLeft)
        }
        val template =
            if (circleScoped) R.string.plan_history_expiring_circle
            else R.string.plan_history_expiring_personal
        return res.getString(template, kept, when_)
    }
}
