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

import com.pingolin.track.location.LocationProvider
import com.pingolin.track.location.PlatformLocationProvider
import com.pingolin.track.util.FcmTokenProvider
import com.pingolin.track.util.NoFcmTokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * LIBRE half of the flavour-specific bindings — see the gms copy at
 * `src/gms/java/com/pingolin/track/di/DistributionModule.kt`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {

    @Provides
    @Singleton
    fun provideLocationProvider(impl: PlatformLocationProvider): LocationProvider = impl

    @Provides
    @Singleton
    fun provideFcmTokenProvider(impl: NoFcmTokenProvider): FcmTokenProvider = impl
}
