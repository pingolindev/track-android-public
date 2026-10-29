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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/** A push registration that failed inside the service's library, in this app's words. */
private const val SERVICE_DID_NOT_ANSWER = "The notification service didn't answer just now."

/**
 * Unified push notification manager supporting multiple push providers:
 * - FCM (Firebase Cloud Messaging) - Google devices
 * - UnifiedPush - Self-hosted/privacy-focused alternative
 *
 * The manager automatically detects available services and registers
 * with the most appropriate provider.
 */
@Singleton
class PushNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    // Firebase behind an interface so this class — shared by both distribution
    // flavours — never names it. src/gms binds the real one, src/libre one that
    // reports unavailable. See FcmTokenProvider.
    private val fcmTokenProvider: FcmTokenProvider
) {
    companion object {
        private const val TAG = "PushNotificationManager"
    }

    enum class PushProvider {
        FCM,          // Firebase Cloud Messaging (Google)
        UNIFIED_PUSH, // UnifiedPush protocol
        NONE          // No push provider available
    }

    sealed class RegistrationResult {
        /** Registration finished and a token or endpoint is already in hand. */
        data class Success(
            val provider: PushProvider,
            val token: String?,
            val endpoint: String?
        ) : RegistrationResult()

        /**
         * NOT a failure: UnifiedPush delivers its endpoint asynchronously to
         * onNewEndpoint, which saves it and schedules the backend sync. Callers must
         * treat this as success-in-progress and not retry.
         */
        data class Pending(
            val provider: PushProvider,
            val message: String
        ) : RegistrationResult()

        data class Failure(
            val provider: PushProvider,
            val error: String
        ) : RegistrationResult()
    }

    /**
     * Detect which push services are available on this device.
     * Returns the services in order of preference.
     */
    fun detectAvailableProviders(): List<PushProvider> {
        val providers = mutableListOf<PushProvider>()

        // Check for FCM. False in a libre build unconditionally, and in a gms
        // build on a device without Play Services.
        if (fcmTokenProvider.isAvailable()) {
            providers.add(PushProvider.FCM)
            Log.d(TAG, "FCM available: Google Play Services detected")
        }

        // Check for UnifiedPush distributor
        if (isUnifiedPushAvailable()) {
            providers.add(PushProvider.UNIFIED_PUSH)
            Log.d(TAG, "UnifiedPush available: Distributor app detected")
        }

        if (providers.isEmpty()) {
            Log.w(TAG, "No push providers available on this device")
        }

        return providers
    }

    /**
     * Preference order: UnifiedPush, then FCM, then none at all.
     *
     * UnifiedPush wins because having a distributor installed means the user
     * deliberately chose a privacy-respecting transport. FCM is the battery-efficient
     * default for everyone else.
     */
    fun getPreferredProvider(): PushProvider {
        val available = detectAvailableProviders()
        return when {
            available.contains(PushProvider.UNIFIED_PUSH) -> PushProvider.UNIFIED_PUSH
            available.contains(PushProvider.FCM) -> PushProvider.FCM
            // There is deliberately NO universal fallback. Do not reintroduce one that
            // cannot actually deliver: an unreachable "backup" transport only produces a
            // status line promising a safety net that does not exist. NONE is honest.
            else -> PushProvider.NONE
        }
    }

    /** Null when push has never been configured on this device. */
    fun getCurrentProvider(): PushProvider? {
        return when (sessionManager.getPushType()) {
            SessionManager.PUSH_TYPE_FCM -> PushProvider.FCM
            SessionManager.PUSH_TYPE_UNIFIED_PUSH -> PushProvider.UNIFIED_PUSH
            else -> null
        }
    }

    suspend fun registerForPushNotifications(): RegistrationResult {
        val provider = getPreferredProvider()
        return registerWithProvider(provider)
    }

    /**
     * Called at app startup after session validation. Returns null — doing nothing — when
     * a provider is already configured, so a choice the user made by hand is never
     * silently replaced by the auto-detected preference.
     */
    suspend fun autoDetectAndRegister(): RegistrationResult? {
        val currentProvider = getCurrentProvider()
        val currentType = sessionManager.getPushType()

        if (currentProvider != null && currentType != null) {
            Log.d(TAG, "Push already configured: $currentProvider, skipping auto-detect")

            return null
        }

        Log.i(TAG, "No push provider configured, auto-detecting...")
        val preferred = getPreferredProvider()
        Log.i(TAG, "Auto-detected preferred provider: $preferred")

        val result = registerWithProvider(preferred)

        return result
    }

    suspend fun registerWithProvider(provider: PushProvider): RegistrationResult {
        Log.d(TAG, "Registering with provider: $provider")

        return when (provider) {
            PushProvider.FCM -> registerWithFcm()
            PushProvider.UNIFIED_PUSH -> registerWithUnifiedPush()
            PushProvider.NONE -> RegistrationResult.Failure(
                PushProvider.NONE,
                "No push provider available"
            )
        }
    }

    private suspend fun registerWithFcm(): RegistrationResult {
        return try {
            val token = fcmTokenProvider.token()
            Log.d(TAG, "FCM registration successful")

            sessionManager.savePushSettings(
                token = token,
                type = SessionManager.PUSH_TYPE_FCM,
                endpoint = null
            )

            // Sync immediately rather than waiting for the next natural sync — until the
            // backend holds this token, nothing can be delivered to the device at all.
            if (sessionManager.isLoggedIn()) {
                com.pingolin.track.worker.PushTokenSyncWorker.schedule(context)
                Log.d(TAG, "Scheduled push token sync with backend")
            }

            RegistrationResult.Success(
                provider = PushProvider.FCM,
                token = token,
                endpoint = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "FCM registration failed", e)
            RegistrationResult.Failure(
                PushProvider.FCM,
                // The library's own words stay in the log
                // above.
                SERVICE_DID_NOT_ANSWER
            )
        }
    }

    /**
     * Register with UnifiedPush distributor.
     * Registration is asynchronous - the endpoint will be delivered via the receiver.
     *
     * Returns:
     * - Success if an endpoint is already available (re-registration case)
     * - Pending if registration initiated but endpoint not yet received
     * - Failure if registration could not be initiated
     */
    private suspend fun registerWithUnifiedPush(): RegistrationResult {
        return try {
            val distributors = UnifiedPush.getDistributors(context)
            if (distributors.isEmpty()) {
                Log.w(TAG, "No UnifiedPush distributors found")
                return RegistrationResult.Failure(
                    PushProvider.UNIFIED_PUSH,
                    "No UnifiedPush distributor installed"
                )
            }

            // First distributor wins by default; the user can pick another in settings.
            val distributor = distributors.first()
            Log.d(TAG, "Using UnifiedPush distributor: $distributor")

            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context)

            Log.d(TAG, "UnifiedPush registration initiated with $distributor")

            // A surviving endpoint means this is a re-registration and there is nothing to
            // wait for; otherwise TrackUnifiedPushReceiver.onNewEndpoint will deliver one
            // and schedule the sync itself.
            val existingEndpoint = sessionManager.getPushEndpoint()
            if (existingEndpoint != null && sessionManager.getPushType() == SessionManager.PUSH_TYPE_UNIFIED_PUSH) {
                RegistrationResult.Success(
                    provider = PushProvider.UNIFIED_PUSH,
                    token = null,
                    endpoint = existingEndpoint
                )
            } else {
                RegistrationResult.Pending(
                    provider = PushProvider.UNIFIED_PUSH,
                    message = "Registration initiated with $distributor. Endpoint pending."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "UnifiedPush registration failed", e)
            RegistrationResult.Failure(
                PushProvider.UNIFIED_PUSH,
                // The library's own words stay in the log
                // above.
                SERVICE_DID_NOT_ANSWER
            )
        }
    }


    /** Clears local push settings even if the provider-side teardown fails. */
    suspend fun unregister() {
        val currentProvider = getCurrentProvider()
        Log.d(TAG, "Unregistering from provider: $currentProvider")

        when (currentProvider) {
            PushProvider.FCM -> {
                try {
                    fcmTokenProvider.deleteToken()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete FCM token", e)
                }
            }
            PushProvider.UNIFIED_PUSH -> {
                try {
                    UnifiedPush.unregister(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister from UnifiedPush", e)
                }
            }
            else -> {}
        }

        sessionManager.clearPushToken()
    }

    private fun isUnifiedPushAvailable(): Boolean {
        return try {
            val distributors = UnifiedPush.getDistributors(context)
            if (distributors.isNotEmpty()) {
                Log.d(TAG, "UnifiedPush distributors found: $distributors")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "UnifiedPush check failed: ${e.message}")
            false
        }
    }

    fun getUnifiedPushDistributors(): List<String> {
        return try {
            UnifiedPush.getDistributors(context)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The explicit-choice counterpart to [registerWithUnifiedPush], for the settings UI. */
    suspend fun registerWithUnifiedPushDistributor(distributor: String): RegistrationResult {
        return try {
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context)

            Log.d(TAG, "UnifiedPush registration initiated with $distributor")

            val existingEndpoint = sessionManager.getPushEndpoint()
            if (existingEndpoint != null && sessionManager.getPushType() == SessionManager.PUSH_TYPE_UNIFIED_PUSH) {
                RegistrationResult.Success(
                    provider = PushProvider.UNIFIED_PUSH,
                    token = null,
                    endpoint = existingEndpoint
                )
            } else {
                RegistrationResult.Pending(
                    provider = PushProvider.UNIFIED_PUSH,
                    message = "Registration initiated with $distributor. Endpoint pending."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "UnifiedPush registration failed with $distributor", e)
            RegistrationResult.Failure(
                PushProvider.UNIFIED_PUSH,
                // The library's own words stay in the log
                // above.
                SERVICE_DID_NOT_ANSWER
            )
        }
    }

    /** Called from FCM's service or the UnifiedPush receiver when their token changes. */
    fun handleTokenUpdate(token: String?, type: String, endpoint: String? = null) {
        Log.d(TAG, "Token update received: type=$type")
        sessionManager.savePushSettings(token, type, endpoint)
    }

    /** The shape the device-registration API expects. */
    fun getPushRegistrationInfo(): PushRegistrationInfo {
        return PushRegistrationInfo(
            token = sessionManager.getPushToken(),
            type = sessionManager.getPushType(),
            endpoint = sessionManager.getPushEndpoint()
        )
    }

    data class PushRegistrationInfo(
        val token: String?,
        val type: String?,
        val endpoint: String?
    )
}
