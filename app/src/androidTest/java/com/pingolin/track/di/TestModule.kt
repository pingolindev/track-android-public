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

package com.pingolin.track.di

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.domain.repository.BoundaryRepository
import com.pingolin.track.domain.repository.DecryptedBoundaryEvent
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uniffi.pingolin_mobile.BoundaryCrossing
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Singleton

/**
 * Test infrastructure holder for instrumented tests.
 *
 * This module deliberately does not use
 * `@TestInstallIn(replaces = [AppModule::class])`: AppModule provides *many*
 * bindings (DAOs, OkHttpClient, ImageLoader, Gson, etc.), and a replacement that
 * does not re-provide every one of them leaves Dagger unable to assemble the
 * SingletonC component, so the androidTest source set does not compile.
 *
 * Instead the real AppModule runs on the device (Keystore is available, so
 * EncryptedSharedPreferences / MasterKey work), and individual tests construct
 * fake/mock doubles directly where they need them. The Fake* classes below are
 * helpers that tests can instantiate without going through Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestModule {
    // Intentionally empty — on a real device the real AppModule works
    // fine (Keystore, encrypted prefs, OkHttp, DAOs, etc. all function). The
    // Fake* classes below are helpers tests can construct directly when they
    // need a controllable double without going through the Hilt graph.
}

/**
 * Fake SessionManager for testing.
 * Provides controllable session state without touching production storage.
 * Overrides prefs with plain SharedPreferences to avoid Android Keystore
 * dependency in test environments.
 */
class FakeSessionManager(
    context: Context
) : SessionManager(context) {

    // Public in the fake so instrumented tests can read/write to verify
    // service side-effects without mocking every SessionManager setter.
    public override val prefs: SharedPreferences =
        context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)


    private var fakeAccessToken: String? = "test_access_token"
    private var fakeRefreshToken: String? = "test_refresh_token"
    private var fakeUserId: String? = "test_user_id"
    private var fakeDeviceId: String = "test_device_id"
    private var fakeDeviceRegistered: Boolean = false

    override fun getAccessToken(): String? = fakeAccessToken
    override fun getRefreshToken(): String? = fakeRefreshToken
    override fun getUserId(): String? = fakeUserId
    override fun getDeviceId(): String = fakeDeviceId
    override fun isDeviceRegistered(): Boolean = fakeDeviceRegistered
    override fun isLoggedIn(): Boolean = fakeAccessToken != null && fakeUserId != null

    fun setFakeAccessToken(token: String?) {
        fakeAccessToken = token
    }

    fun setFakeRefreshToken(token: String?) {
        fakeRefreshToken = token
    }

    fun setFakeUserId(userId: String?) {
        fakeUserId = userId
    }

    fun setFakeDeviceId(deviceId: String) {
        fakeDeviceId = deviceId
    }

    fun setFakeDeviceRegistered(registered: Boolean) {
        fakeDeviceRegistered = registered
    }

    fun reset() {
        fakeAccessToken = "test_access_token"
        fakeRefreshToken = "test_refresh_token"
        fakeUserId = "test_user_id"
        fakeDeviceId = "test_device_id"
        fakeDeviceRegistered = false
    }
}

/**
 * Fake LocationRepository for testing.
 * Stores locations in memory and provides controllable behavior.
 */
class FakeLocationRepository {
    private val locations = mutableListOf<LocationEntity>()
    private val _locationsFlow = MutableStateFlow<List<LocationEntity>>(emptyList())

    var sendLocationResult: Result<Unit> = Result.success(Unit)
    var isStreamingState: Boolean = false
    var deviceRegistrationResult: Boolean = true

    fun observeLocationsByCircle(circleId: String): Flow<List<LocationEntity>> {
        return flowOf(locations.filter { it.circleId == circleId })
    }

    suspend fun sendLocation(
        circleId: String,
        location: Location,
        batteryLevel: Int?
    ): Result<Unit> {
        if (sendLocationResult.isSuccess) {
            val entity = LocationEntity(
                id = System.currentTimeMillis(),
                circleId = circleId,
                userId = "test_user",
                deviceId = "test_device",
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                batteryLevel = batteryLevel,
                timestamp = location.time,
                isSynced = true,
                dayBucket = (location.time / 86400000L).toInt()
            )
            locations.add(entity)
            _locationsFlow.value = locations.toList()
        }
        return sendLocationResult
    }

    fun startStreaming(deviceId: String) {
        isStreamingState = true
    }

    fun stopStreaming() {
        isStreamingState = false
    }

    fun isStreaming(): Boolean = isStreamingState

    suspend fun ensureDeviceRegistered(): Boolean = deviceRegistrationResult

    fun clearLocations() {
        locations.clear()
        _locationsFlow.value = emptyList()
    }

    fun getStoredLocations(): List<LocationEntity> = locations.toList()
}

/**
 * Fake BoundaryRepository for testing.
 * Simulates boundary checking with controllable crossing detection.
 */
class FakeBoundaryRepository {
    private val loadedBoundaries = mutableMapOf<String, Int>()
    private val crossingsToReturn = mutableListOf<BoundaryCrossing>()
    // No subscriptions flow: the real BoundaryRepository has none.
    private val boundaryStates = MutableStateFlow<Map<String, List<BoundaryStateResponse>>>(emptyMap())
    private val recentEvents = MutableStateFlow<List<DecryptedBoundaryEvent>>(emptyList())

    val boundaryStatesFlow: StateFlow<Map<String, List<BoundaryStateResponse>>> = boundaryStates
    val recentEventsFlow: StateFlow<List<DecryptedBoundaryEvent>> = recentEvents

    var loadBoundariesResult: Result<Int> = Result.success(0)
    var reportCrossingResult: Result<ReportBoundaryCrossingResponse> = Result.success(
        ReportBoundaryCrossingResponse(eventId = "test_event_id", notificationsSent = 0)
    )

    suspend fun loadBoundariesForCircle(circleId: String): Result<Int> {
        if (loadBoundariesResult.isSuccess) {
            val count = loadBoundariesResult.getOrNull() ?: 0
            loadedBoundaries[circleId] = count
        }
        return loadBoundariesResult
    }

    suspend fun checkLocation(latitude: Double, longitude: Double): List<BoundaryCrossing> {
        return crossingsToReturn.toList()
    }

    suspend fun reportCrossing(
        placeId: String,
        circleId: String,
        crossingUserId: String,
        eventType: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<ReportBoundaryCrossingResponse> {
        return reportCrossingResult
    }

    fun getUserDisplayName(userId: String): String {
        return "Test User"
    }

    fun setCrossingsToReturn(crossings: List<BoundaryCrossing>) {
        crossingsToReturn.clear()
        crossingsToReturn.addAll(crossings)
    }

    fun getLoadedBoundariesCount(circleId: String): Int {
        return loadedBoundaries[circleId] ?: 0
    }

    fun clear() {
        loadedBoundaries.clear()
        crossingsToReturn.clear()
    }
}

/**
 * Fake AuthRepository for testing.
 * Provides controllable authentication results.
 */
class FakeAuthRepository {
    var refreshTokenResult: Result<Unit> = Result.success(Unit)
    var isLoggedInState: Boolean = true

    suspend fun refreshToken(): Result<Unit> = refreshTokenResult

    fun isLoggedIn(): Boolean = isLoggedInState

    fun setRefreshTokenFailure(message: String) {
        refreshTokenResult = Result.failure(Exception(message))
    }

    fun setRefreshTokenSuccess() {
        refreshTokenResult = Result.success(Unit)
    }
}
