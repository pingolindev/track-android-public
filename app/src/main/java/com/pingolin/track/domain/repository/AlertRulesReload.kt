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

import android.util.Log
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The low-battery and inactivity rules this phone raises alerts from, reloaded from the
 * server. MonitoringManager judges only the rows held here, and every other refresh of them is
 * the Alerts screen, an edit on this phone or a queued change. Without this, after a sign-in,
 * a new install or a database made again, no such alert would fire until the person opened
 * Alerts, and a rule made on another device would never arrive.
 *
 * The same shape as the iOS app's MonitoringManager.refreshIfStale: a list not read in this
 * process for this sign-in is stale, and so is one older than the caller's age, [MAX_AGE_MS]
 * for the background pass. The background pass reloads a stale list before it reads which
 * circles to judge; the alerts_check wake-up reloads at once; a return to the app reloads a
 * list older than [RETURN_MAX_AGE_MS], so a turn of the screen or a dialog closing fetches
 * nothing. A list counts as read only when it came whole and every rule in it was read here:
 * right after a sign-in the circle keys may not be in yet, and a rule this phone couldn't open
 * is read again [RETRY_MS] later, and then less often, up to the hour. Best-effort: a failed or
 * partial fetch keeps the rows already here (the refreshes never write a weaker copy) and is
 * tried again at the next pass.
 */
@Singleton
class AlertRulesReload @Inject constructor(
    private val alertRules: AlertRuleRepository,
    private val inactivityAlerts: InactivityAlertRepository,
    private val sessionManager: SessionManager,
) {
    /** A seam for the tests. */
    internal var now: () -> Long = System::currentTimeMillis

    private val mutex = Mutex()

    /**
     * One list's last read: the account it was for, when, and how many reads in a row left a
     * rule unread here (0: every rule was read).
     */
    private class Read(val account: String, val at: Long, val misses: Int)

    @Volatile private var rulesRead: Read? = null
    @Volatile private var inactivityRead: Read? = null

    /** Bumped by [forget], so a read that was under way at a sign-out records nothing. */
    @Volatile private var signIn = 0

    /**
     * Reloads each list older than [maxAgeMs], or both when [force]. Never throws.
     */
    suspend fun reloadIfStale(force: Boolean = false, maxAgeMs: Long = MAX_AGE_MS) = mutex.withLock {
        val account = sessionManager.getUserId() ?: return@withLock
        val session = signIn
        val at = now()
        fun due(read: Read?): Boolean {
            if (force || read == null || read.account != account) return true
            val age = if (read.misses == 0) maxAgeMs else minOf(maxAgeMs, RETRY_MS shl minOf(read.misses - 1, 4))
            return at - read.at >= age
        }
        fun after(read: Read?, allRead: Boolean) =
            Read(account, at, if (allRead) 0 else (read?.takeIf { it.account == account }?.misses ?: 0) + 1)

        if (due(rulesRead)) {
            // Whole: every page, to the last. A list cut short isn't read.
            val whole = runCatching { alertRules.refreshAlertRulesWhole() }.getOrNull()
            if (whole == null) {
                Log.w(TAG, "Alert rules not reloaded; keeping the rules here")
            } else if (signIn == session) {
                rulesRead = after(rulesRead, whole.allRead)
                Log.d(TAG, "Alert rules reloaded: ${whole.rules.size} of ${whole.ids.size}")
            }
        }
        if (due(inactivityRead)) {
            val whole = runCatching { inactivityAlerts.refreshInactivityAlertsWhole() }.getOrNull()
            if (whole == null) {
                Log.w(TAG, "Inactivity alerts not reloaded; keeping the alerts here")
            } else if (signIn == session) {
                inactivityRead = after(inactivityRead, whole.allRead)
                Log.d(TAG, "Inactivity alerts reloaded: ${whole.alerts.size} of ${whole.ids.size}")
            }
        }
    }

    /**
     * Sign-out: what was read goes with the tables it was written to, so the next sign-in, the
     * same account's included, reads both lists at once. Doesn't wait for a read under way.
     */
    fun forget() {
        signIn++
        rulesRead = null
        inactivityRead = null
    }

    companion object {
        private const val TAG = "AlertRulesReload"

        /** iOS's: a rule list older than an hour is fetched again at the next pass. */
        const val MAX_AGE_MS = 60 * 60_000L

        /** A return to the app fetches a list older than this; sooner, nothing. */
        const val RETURN_MAX_AGE_MS = 5 * 60_000L

        /** A list with a rule not read here is read again after this, then twice as long each time. */
        const val RETRY_MS = 5 * 60_000L
    }
}
