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

package com.pingolin.track.service

/**
 * What a push message asks this app to do: the one decision every transport shares.
 *
 * A push is a SIGNAL, never content. The server sends mobile devices exactly six
 * types, all content-free: `tickle` (a notification row is waiting; fetch it over our
 * own authenticated connection and render that), `wake_location`, `alerts_check`,
 * `identities_check` (a restore of pre-reset data; look again), `sos_refresh` (an SOS
 * was sealed again; look again, quietly) and `privacy_check` (this account's privacy
 * changed in a circle; read the circle list again, quietly). Nothing else in a message
 * is read.
 *
 * That matters most for UnifiedPush. A distributor delivers an unsigned, unencrypted
 * POST to an endpoint URL, so whoever runs the distributor (often a public ntfy
 * instance) or learns the URL can send this app anything. Rendering typed payloads
 * straight from the message would turn `{"type":"sos_alert","user_name":"Mum"}` from a
 * stranger into a max-priority "Mum needs attention", and post a `place_arrival` with
 * `local_evaluated=true` as a crossing this device had detected itself. So a type we
 * do not know is only a reason to fetch: the worst a forged message can do is make the
 * app ask the server what is waiting, which a real tickle does anyway.
 *
 * Pure, so the mapping is unit-tested; the transports around it (a BroadcastReceiver,
 * a FirebaseMessagingService) cannot run on the JVM.
 */
internal object PushSignals {

    enum class Action {
        /** Fetch the waiting notification rows and show them. */
        FETCH_NOTIFICATIONS,

        /** Restart the tracking service; the server thinks this device went dark. */
        WAKE_LOCATION,

        /** Re-evaluate this device's own rules against its own data. */
        CHECK_OWN_ALERTS,

        /** Look again for identities from before a password reset. */
        CHECK_IDENTITIES,

        /**
         * Fetch the active SOS list again and judge it, quietly: its raiser sealed one again
         * under a new key. Nothing rings for it.
         */
        REFRESH_SOS,

        /**
         * Read the circle list again at once, `my_privacy` and all, quietly: this account paused,
         * resumed or changed its sharing in a circle, here or on another phone. Nothing shows.
         */
        REFRESH_PRIVACY,

        /** A message with no type at all: nothing we sent. */
        IGNORE,
    }

    /** A message's action, from its type alone: whatever else it carries changes nothing. */
    fun actionFor(data: Map<String, String>): Action = actionFor(data["type"] ?: data["notification_type"])

    fun actionFor(type: String?): Action = when {
        type.isNullOrBlank() -> Action.IGNORE

        // Server-initiated location revival: the server noticed this device went dark
        // and is asking for the foreground tracking service back.
        type == "wake_location" -> Action.WAKE_LOCATION

        // Emptier than a tickle: that says a notification is waiting to be fetched,
        // this says only "look at your own data". The server holds no key for a
        // battery level or a threshold, so nothing has been decided.
        type == "alerts_check" -> Action.CHECK_OWN_ALERTS

        // A restore in the account portal: the only thing it says is "check again",
        // through the ordinary signed-in call, at most once per three minutes.
        type == "identities_check" -> Action.CHECK_IDENTITIES

        // An SOS sealed again: no ids, no row, no sound.
        type == "sos_refresh" -> Action.REFRESH_SOS

        // A privacy change of this account's: no ids, no row, no sound. A forged one makes
        // the app read its own circle list, which the 5-minute refresh does anyway.
        type == "privacy_check" -> Action.REFRESH_PRIVACY

        // `tickle`, and any other type, including typed payloads (sos_alert,
        // place_alert_enter, ...): fetch, never render.
        else -> Action.FETCH_NOTIFICATIONS
    }

    /**
     * What [action] asks for, handed to its worker with nothing read from this phone's stores: a
     * push that arrives while the session store can't be opened is done once it opens, each
     * worker looking again by itself. An SOS must not wait for the 15-minute poll. A location
     * wake is the watchdog's: it looks again soon, never later than a look already due, and
     * starts tracking once the store opens.
     */
    fun handOff(context: android.content.Context, action: Action) {
        when (action) {
            Action.FETCH_NOTIFICATIONS -> com.pingolin.track.worker.NotificationTickleWorker.runNow(context)
            Action.CHECK_OWN_ALERTS -> com.pingolin.track.worker.AlertsCheckWorker.runNow(context)
            Action.REFRESH_PRIVACY -> com.pingolin.track.worker.PrivacyCheckWorker.runNow(context)
            Action.REFRESH_SOS -> com.pingolin.track.worker.SosRefreshWorker.runNow(context)
            Action.CHECK_IDENTITIES -> com.pingolin.track.worker.IdentitiesCheckWorker.runNow(context)
            Action.WAKE_LOCATION -> com.pingolin.track.receiver.WatchdogAlarmReceiver.scheduleSoon(context)
            Action.IGNORE -> Unit
        }
    }
}
