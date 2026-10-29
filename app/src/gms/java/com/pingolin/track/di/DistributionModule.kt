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

import com.pingolin.track.location.FusedLocationProvider
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.util.FcmTokenProvider
import com.pingolin.track.util.FirebaseFcmTokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * GMS half of the flavour-specific bindings.
 *
 * `src/libre/java/com/pingolin/track/di/DistributionModule.kt` is the same
 * module name binding the Google-free implementations instead; exactly one of
 * the two is ever on a variant's compile path, so there is no duplicate-binding
 * conflict.
 *
 * These two bindings are the whole runtime seam between the flavours. Everything
 * else that differs (`PlayIntegrityManager`, `PlanManagement`,
 * `KeepAliveGeofence`) is the same class name in both source sets and needs no
 * module at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {

    @Provides
    @Singleton
    fun provideLocationProvider(impl: FusedLocationProvider): LocationProvider = impl

    @Provides
    @Singleton
    fun provideFcmTokenProvider(impl: FirebaseFcmTokenProvider): FcmTokenProvider = impl
}
