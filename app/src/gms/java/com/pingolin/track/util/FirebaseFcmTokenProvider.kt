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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GMS FLAVOUR [FcmTokenProvider] — the real thing, backed by Firebase Cloud
 * Messaging.
 *
 * Each method is one direct Firebase call: [isAvailable] is
 * `isGooglePlayServicesAvailable()`, [token] is
 * `FirebaseMessaging.getInstance().token.await()`, [deleteToken] is
 * `deleteToken().await()`. The libre counterpart is `NoFcmTokenProvider`.
 */
@Singleton
class FirebaseFcmTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FcmTokenProvider {

    companion object {
        private const val TAG = "FirebaseFcmTokenProvider"
    }

    /**
     * Play services present on THIS DEVICE, which a gms APK cannot assume — it
     * installs and runs perfectly well on a de-Googled phone, where FCM simply
     * never delivers. Answering false there is what makes
     * [PushNotificationManager.getPreferredProvider] fall through to UnifiedPush.
     */
    override fun isAvailable(): Boolean {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            availability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            Log.d(TAG, "Google Play Services check failed: ${e.message}")
            false
        }
    }

    override suspend fun token(): String = FirebaseMessaging.getInstance().token.await()

    override suspend fun deleteToken() {
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}
