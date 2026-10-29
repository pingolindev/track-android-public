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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.MalformedJsonException
import com.pingolin.track.BuildConfig
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import com.pingolin.track.worker.AlertsCheckWorker
import com.pingolin.track.worker.NotificationTickleWorker
import com.pingolin.track.worker.PushTokenSyncWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.io.StringReader

/**
 * UnifiedPush receiver for handling push notifications from
 * self-hosted or privacy-focused push distributors (e.g., ntfy).
 *
 * UnifiedPush provides a standardized way to receive push notifications
 * without relying on Google's FCM or proprietary services.
 *
 * Supported distributors:
 * - ntfy (https://ntfy.sh)
 * - NextPush
 * - Molly (Signal fork)
 * - Any UP-compatible distributor
 *
 * Note: Uses Hilt EntryPoint for dependency injection since MessagingReceiver
 * (a BroadcastReceiver subclass) cannot use standard @AndroidEntryPoint injection.
 */
class TrackUnifiedPushReceiver : MessagingReceiver() {

    /**
     * Hilt entry point for accessing dependencies in this receiver.
     * BroadcastReceivers cannot use constructor injection, so we use EntryPointAccessors.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UnifiedPushReceiverEntryPoint {
        fun sessionManager(): SessionManager
    }

    companion object {
        private const val TAG = "TracksUnifiedPush"

        // Security limits for JSON parsing
        private const val MAX_MESSAGE_SIZE = 64 * 1024 // 64 KB
        private const val MAX_STRING_LENGTH = 4096 // Max length for any string field
        private const val MAX_FIELDS = 50 // Max number of fields in the JSON object

        // Known trusted UnifiedPush distributors (package names)
        // These are the packages allowed to send us push messages
        private val TRUSTED_DISTRIBUTORS: Set<String> = setOfNotNull(
            "io.heckel.ntfy",           // ntfy
            "io.heckel.ntfy.dev",       // ntfy (dev)
            "org.unifiedpush.distributor.fcm",  // UP FCM Distributor
            "org.unifiedpush.distributor.nextpush", // NextPush
            "im.molly.app",             // Molly (Signal fork)
            // System shell (adb) — trusted in DEBUG builds only, for testing.
            // Never in release, where it would let the shell UID inject push
            // messages.
            "com.android.shell".takeIf { BuildConfig.DEBUG },
        )
    }

    /**
     * SECURITY: Verify the caller is a trusted UnifiedPush distributor.
     * This prevents malicious apps from sending crafted push messages.
     */
    private fun isCallerTrusted(context: Context): Boolean {
        val callingUid = Binder.getCallingUid()

        // Allow calls from our own process (for testing)
        if (callingUid == android.os.Process.myUid()) {
            return true
        }

        // Get the package name(s) for the calling UID
        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false

        // Check if any of the caller's packages are trusted
        for (packageName in packages) {
            if (packageName in TRUSTED_DISTRIBUTORS) {
                return true
            }
            // Also allow system apps (UID < 10000). Exclude the shell UID (2000)
            // in release builds: com.android.shell runs as UID 2000, and because
            // this receiver is exported the blanket system-UID allowance would
            // otherwise let adb inject push messages (wake the location FGS /
            // spoof alerts). Shell is trusted only in DEBUG (for testing),
            // mirroring the DEBUG-gating of com.android.shell in
            // TRUSTED_DISTRIBUTORS above.
            if (callingUid < 10000 && (BuildConfig.DEBUG || callingUid != 2000)) {
                return true
            }
        }

        Log.w(TAG, "Rejecting push message from untrusted caller: $callingUid, packages: ${packages.toList()}")
        return false
    }

    private val gson = Gson()

    private fun getEntryPoint(context: Context): UnifiedPushReceiverEntryPoint {
        return EntryPointAccessors.fromApplication(
            context.applicationContext,
            UnifiedPushReceiverEntryPoint::class.java
        )
    }

    private fun getSessionManager(context: Context): SessionManager {
        return getEntryPoint(context).sessionManager()
    }

    /**
     * The endpoint is the URL the server POSTs to for delivery — it arrives here
     * asynchronously, some time after registration was requested.
     */
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        if (!isCallerTrusted(context)) return
        val endpointUrl = endpoint.url
        Log.d(TAG, "New UnifiedPush endpoint registered: $endpointUrl")

        val sessionManager = getSessionManager(context)
        // The session store can't be opened now: nothing kept, no crash. Track registers its
        // push again at its next start.
        if (sessionManager.storeRefusal() != null) return
        sessionManager.savePushSettings(
            token = null, // UnifiedPush doesn't use tokens
            type = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            endpoint = endpointUrl
        )

        // Sync through WorkManager rather than inline, so a temporarily unavailable
        // network retries instead of losing the endpoint.
        if (sessionManager.isLoggedIn()) {
            PushTokenSyncWorker.schedule(context)
            Log.i(TAG, "UnifiedPush endpoint saved, sync scheduled")
        } else {
            Log.i(TAG, "UnifiedPush endpoint saved, will sync on login")
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        if (!isCallerTrusted(context)) return
        Log.e(TAG, "UnifiedPush registration failed for instance: $instance, reason: $reason")
    }

    override fun onUnregistered(context: Context, instance: String) {
        if (!isCallerTrusted(context)) return
        Log.d(TAG, "UnifiedPush unregistered for instance: $instance")

        // Only clear if UnifiedPush is still the active provider — a switch to FCM also
        // unregisters here, and wiping the new FCM settings would leave no push at all.
        val sessionManager = getSessionManager(context)
        if (sessionManager.storeRefusal() != null) return // no crash
        if (sessionManager.getPushType() == SessionManager.PUSH_TYPE_UNIFIED_PUSH) {
            sessionManager.clearPushToken()
        }
    }

    /**
     * This receiver is EXPORTED, so treat every message as hostile until proven
     * otherwise: the caller must be a trusted distributor, the payload must be within
     * MAX_MESSAGE_SIZE, and it must survive strict JSON validation before anything acts
     * on it.
     */
    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        if (!isCallerTrusted(context)) {
            Log.w(TAG, "Rejecting push message from untrusted caller")
            return
        }

        if (message.content.size > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "Rejecting oversized push message: ${message.content.size} bytes")
            return
        }

        val messageStr = String(message.content, Charsets.UTF_8)
        // Length only, never content — the body can carry notification text.
        Log.d(TAG, "UnifiedPush message received (${messageStr.length} chars)")

        try {
            val data = parseNotificationData(messageStr)
            if (data != null) {
                val sessionManager = getSessionManager(context)
                // The session store can't be opened now: never a crash. What the push asks
                // for goes to its worker, with nothing read from the store, and is done
                // once it opens.
                if (sessionManager.storeRefusal() != null) {
                    PushSignals.handOff(context, PushSignals.actionFor(data))
                    return
                }
                if (!sessionManager.isLoggedIn()) {
                    Log.w(TAG, "Ignoring push message - user not logged in")
                    return
                }
                handleNotification(context, data)
            } else {
                Log.w(TAG, "Failed to parse notification data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing UnifiedPush message", e)
        }
    }

    /**
     * Parse notification data from the push message with strict validation.
     *
     * Security considerations:
     * - Only accepts flat JSON objects (no nested structures)
     * - Limits number of fields to prevent memory exhaustion
     * - Limits string length for each field
     * - Rejects non-string values to prevent type confusion
     */
    private fun parseNotificationData(message: String): Map<String, String>? {
        return try {
            val reader = JsonReader(StringReader(message))

            val result = mutableMapOf<String, String>()
            reader.beginObject()

            var fieldCount = 0
            while (reader.hasNext()) {
                // Limit number of fields
                if (++fieldCount > MAX_FIELDS) {
                    Log.w(TAG, "JSON has too many fields (> $MAX_FIELDS)")
                    return null
                }

                val name = reader.nextName()

                // Validate field name length
                if (name.length > MAX_STRING_LENGTH) {
                    Log.w(TAG, "JSON field name too long: ${name.length} chars")
                    return null
                }

                // Only accept string values, skip others
                val token = reader.peek()
                when (token) {
                    com.google.gson.stream.JsonToken.STRING -> {
                        val value = reader.nextString()
                        // Validate value length
                        if (value.length > MAX_STRING_LENGTH) {
                            Log.w(TAG, "JSON field value too long: ${value.length} chars for field $name")
                            return null
                        }
                        result[name] = value
                    }
                    com.google.gson.stream.JsonToken.NULL -> {
                        reader.nextNull()
                        // Skip null values
                    }
                    com.google.gson.stream.JsonToken.NUMBER -> {
                        // Convert numbers to strings for compatibility
                        result[name] = reader.nextString()
                    }
                    com.google.gson.stream.JsonToken.BOOLEAN -> {
                        // Convert booleans to strings
                        result[name] = reader.nextBoolean().toString()
                    }
                    else -> {
                        // Reject nested objects/arrays (potential DoS via deeply nested structures)
                        Log.w(TAG, "Rejecting nested structure in notification JSON")
                        return null
                    }
                }
            }

            reader.endObject()
            reader.close()

            result
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Invalid JSON syntax", e)
            null
        } catch (e: JsonParseException) {
            Log.e(TAG, "Failed to parse JSON", e)
            null
        } catch (e: MalformedJsonException) {
            Log.e(TAG, "Malformed JSON", e)
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Unexpected JSON structure", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification JSON", e)
            null
        }
    }

    private fun handleNotification(context: Context, data: Map<String, String>) {
        Log.d(TAG, "Handling notification type: ${data["type"] ?: data["notification_type"]}")

        // Only the type is read. Everything else in the message came from a plain
        // POST that anybody holding the endpoint URL can send — see PushSignals.
        when (PushSignals.actionFor(data)) {
            PushSignals.Action.FETCH_NOTIFICATIONS -> {
                Log.d(TAG, "Push received via UnifiedPush — fetching notifications from API")
                NotificationTickleWorker.runNow(context)
            }
            PushSignals.Action.WAKE_LOCATION -> handleWakeLocation(context)
            PushSignals.Action.CHECK_OWN_ALERTS -> AlertsCheckWorker.runNow(context)
            PushSignals.Action.CHECK_IDENTITIES -> com.pingolin.track.worker.IdentitiesCheckWorker.runNow(context)
            PushSignals.Action.REFRESH_SOS -> com.pingolin.track.worker.SosRefreshWorker.runNow(context)
            PushSignals.Action.REFRESH_PRIVACY -> com.pingolin.track.worker.PrivacyCheckWorker.runNow(context)
            PushSignals.Action.IGNORE -> Log.w(TAG, "Ignoring a push with no type")
        }
    }

    /**
     * Restart the location foreground service in response to a server `wake_location`
     * push. Mirrors TrackFirebaseMessagingService.handleWakeLocation so UnifiedPush
     * users get the same auto-revival as FCM users. onMessage() has already verified
     * the user is logged in; we still honour an explicit tracking pause.
     */
    private fun handleWakeLocation(context: Context) {
        val sessionManager = getSessionManager(context)
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "wake_location: not logged in, ignoring")
            return
        }
        if (sessionManager.isTrackingPaused()) {
            Log.d(TAG, "wake_location: tracking is paused, ignoring")
            return
        }
        try {
            val intent = Intent(context, LocationTrackingService::class.java)
                .apply { action = LocationTrackingService.ACTION_START }
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "wake_location: started LocationTrackingService via UnifiedPush")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when the app
            // isn't in an allowed state — best-effort, same caveat as the FCM path.
            Log.w(TAG, "wake_location: failed to start LocationTrackingService", e)
        }
    }
}
