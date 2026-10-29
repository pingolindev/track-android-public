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

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.UpdatePushTokenRequest
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import com.pingolin.track.worker.PushTokenSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for handling push notifications.
 *
 * Responsibilities:
 * - Receive new FCM tokens and sync with backend
 * - Treat each push as a content-free signal (see [PushSignals]) and act on it
 *
 * Security:
 * - Push tokens are stored in EncryptedSharedPreferences via SessionManager
 * - Nothing in a message is rendered: notification content is fetched over this
 *   app's own authenticated connection by [NotificationTickleFetcher]
 * - No sensitive data is logged
 */
@AndroidEntryPoint
class TrackFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var tracksApi: TrackApi

    /**
     * The content-fetching half of a tickle, shared with TrackUnifiedPushReceiver.
     * It is not FCM-specific, so it lives in NotificationTickleFetcher, where the
     * libre build can reach it too.
     */
    @Inject
    lateinit var tickleFetcher: NotificationTickleFetcher

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "TracksFirebaseMsgSvc"

        // Push notification payload keys
        private const val KEY_TYPE = "type"
        private const val KEY_CIRCLE_ID = "circle_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PLACE_ID = "place_id"
        private const val KEY_PLACE_NAME = "place_name"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_MESSAGE = "message"
        private const val KEY_BATTERY_LEVEL = "battery_level"
        private const val KEY_IS_CRITICAL = "is_critical"
        private const val KEY_NEEDS_KEY_ROTATION = "needs_key_rotation"

    }

    /**
     * Fires on first launch after install, whenever Firebase rotates the token, and when
     * the app is restored onto a new device.
     *
     * Each of those must force re-registration, which both syncs the new token and flips
     * `is_active` back to TRUE — a device deactivated because pushes to the OLD token were
     * failing would otherwise stay dead with a perfectly good new token.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received")
        // The session store can't be opened now: nothing kept, never a crash. The token is
        // read again and registered at Track's next start.
        if (sessionManager.storeRefusal() != null) return

        sessionManager.savePushToken(token)

        // Clearing both flags is what makes the next ensureDeviceRegistered() actually
        // reach the server rather than short-circuit on cached state.
        sessionManager.setDeviceRegistered(false)
        sessionManager.setPushTokenSynced(false)
        Log.d(TAG, "Marked device for re-registration with new token")

        if (sessionManager.isLoggedIn()) {
            serviceScope.launch {
                syncPushTokenWithBackend(token)
            }
        } else {
            Log.d(TAG, "Not logged in, will sync token on next device registration")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Push message received from: ${remoteMessage.from}")
        // The session store can't be opened now: never a crash. What the push asks for
        // goes to its worker, with nothing read from the store, and is done once it
        // opens: an SOS must not wait for the 15-minute poll.
        if (sessionManager.storeRefusal() != null) {
            PushSignals.handOff(applicationContext, PushSignals.actionFor(remoteMessage.data))
            return
        }

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            handleDataMessage(data)
        }

        // Handle notification payload (shown automatically if app is in background)
        remoteMessage.notification?.let {
            // Content-free: never log title/body. The server sends tickle-only data pushes
            // (see handleDataMessage's PRIVACY note), so a notification payload isn't expected
            // here — and logging its content would leak notification text into logcat.
            Log.d(TAG, "Notification payload present")
            // If app is in foreground, we handle it via data message
            // If in background, system handles notification display
        }
    }

    /**
     * PRIVACY: The server sends content-free "tickle" pushes via FCM/APNs.
     * Google/Apple see only {type: "tickle"} — no notification content.
     * On receiving a tickle, the app fetches the actual notification from
     * the Track API over its own authenticated TLS connection.
     */
    private fun handleDataMessage(data: Map<String, String>) {
        val type = data[KEY_TYPE]
        if (type.isNullOrEmpty()) {
            Log.w(TAG, "Push message missing type field")
            return
        }

        Log.d(TAG, "Handling push notification type: $type")

        // Every type other than the two content-free wake-ups is a reason to fetch,
        // never something to render. Handing any other type straight to
        // AlertHandler with the payload's own fields — names, place labels,
        // `local_evaluated` — would let whoever could send this app a push have it
        // draw an SOS or a "detected on this device" arrival with text of their
        // choosing. The server only ever sends the three types PushSignals knows.
        val action = PushSignals.actionFor(type)
        if (action == PushSignals.Action.FETCH_NOTIFICATIONS) {
            Log.d(TAG, "Tickle received — fetching notifications from API")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                // A tickle carries no content: if the fetch fails, the notification is
                // simply never seen, and the transport will not send another. This scope
                // dies with the process and has no retry of its own, so hand a failure to
                // WorkManager — the same worker the UnifiedPush transport already uses,
                // which is constrained on network and backs off.
                val ok = try {
                    tickleFetcher.fetchAndShowNotifications()
                } catch (e: Exception) {
                    Log.w(TAG, "Tickle fetch threw", e)
                    false
                }
                if (!ok) {
                    Log.w(TAG, "Tickle fetch did not complete — handing off to WorkManager")
                    com.pingolin.track.worker.NotificationTickleWorker.runNow(applicationContext)
                }
            }
            return
        }

        if (action == PushSignals.Action.CHECK_OWN_ALERTS) {
            // Client-evaluated alerts (low battery, inactivity). Emptier than a
            // tickle: a tickle means a notification is waiting to be fetched,
            // this means only "look at your own data". Nothing has been decided
            // — the server cannot decide it, having no key for the battery level
            // or the threshold — so the worker refreshes, decrypts and compares
            // locally, and shows the user nothing unless one of THIS device's
            // rules matches.
            Log.d(TAG, "alerts_check received — re-evaluating own rules locally")
            com.pingolin.track.worker.AlertsCheckWorker.runNow(applicationContext)
            return
        }

        if (action == PushSignals.Action.REFRESH_PRIVACY) {
            // This account's privacy changed in a circle: read the circle list again now,
            // so the send gates stop or start within seconds. Nothing is shown.
            com.pingolin.track.worker.PrivacyCheckWorker.runNow(applicationContext)
            return
        }

        if (action == PushSignals.Action.REFRESH_SOS) {
            // An SOS sealed again under a new key: look at the list again, quietly.
            // Nothing rings for it; the receivers' rules decide what it shows.
            com.pingolin.track.worker.SosRefreshWorker.runNow(applicationContext)
            return
        }

        if (action == PushSignals.Action.CHECK_IDENTITIES) {
            // A restore in the account portal: look again for identities from
            // before a password reset, through the ordinary signed-in call.
            // Nothing shown.
            com.pingolin.track.worker.IdentitiesCheckWorker.runNow(applicationContext)
            return
        }

        if (action == PushSignals.Action.WAKE_LOCATION) {
            // Server-side staleness wake-up. The server has detected this
            // device hasn't reported a location in the past hour (or so) and
            // is asking us to start the foreground tracking service. The
            // payload itself contains nothing privacy-sensitive — Google
            // sees only {type: "wake_location"}; the user-id / device-id is
            // implicit in the FCM token routing.
            //
            // We start the service unconditionally (subject to the user not
            // being logged out and not having explicitly paused tracking).
            // LocationTrackingService's onStartCommand already idempotent-
            // ly handles being started while running, so this is safe even
            // if the FG service is already alive. If it's dead — which is
            // the whole reason the server fired this push — startForeground
            // brings it back.
            handleWakeLocation()
            return
        }
    }

    /**
     * Restart LocationTrackingService in response to a server-side
     * `wake_location` push. The push fires when the backend's stale-
     * device cron has noticed that this device hasn't reported a
     * location in too long (typically because the FG service was
     * killed by the OS/OEM Doze and the WorkManager heartbeat hasn't
     * fired yet either).
     *
     * Bails out only if the user is logged out or has explicitly paused tracking.
     * Do NOT add a gate on `isTrackingEnabled`: that pref can drift out of sync with
     * reality (see LocationHeartbeatWorker's staleness watchdog), and refusing to wake on
     * a stale `false` is what turns a dead service into a gap of many hours.
     */
    private fun handleWakeLocation() {
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "wake_location: not logged in, ignoring")
            return
        }
        if (sessionManager.isTrackingPaused()) {
            Log.d(TAG, "wake_location: tracking is paused, ignoring")
            return
        }
        try {
            val intent = Intent(applicationContext, LocationTrackingService::class.java)
                .apply { action = LocationTrackingService.ACTION_START }
            ContextCompat.startForegroundService(applicationContext, intent)
            Log.i(TAG, "wake_location: started LocationTrackingService")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, SecurityException
            // (missing FG service permission grant), etc. Nothing more we
            // can do from a push handler without the user opening the app.
            Log.w(TAG, "wake_location: failed to start LocationTrackingService", e)
        }
    }

    /**
     * Sync the push token with the backend.
     * Called when a new token is received and user is logged in.
     * Schedules a WorkManager retry if sync fails.
     */
    private suspend fun syncPushTokenWithBackend(token: String) {
        try {
            val deviceId = sessionManager.getDeviceIdOrNull()
            if (deviceId == null) {
                Log.d(TAG, "No device ID yet, token will be sent on device registration")
                return
            }

            Log.d(TAG, "Syncing FCM push token with backend for device: $deviceId")

            val response = tracksApi.updateDevicePushToken(
                deviceId = deviceId,
                request = UpdatePushTokenRequest(
                    pushToken = token,
                    pushType = SessionManager.PUSH_TYPE_FCM,
                    pushEndpoint = null
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                sessionManager.setPushTokenSynced(true)
                Log.d(TAG, "FCM push token synced successfully")
            } else {
                Log.w(TAG, "Failed to sync push token: ${response.body()?.error}")
                // Schedule retry via WorkManager
                PushTokenSyncWorker.schedule(applicationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception syncing push token", e)
            // Schedule retry via WorkManager
            PushTokenSyncWorker.schedule(applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Firebase messaging service destroyed")
    }

}
