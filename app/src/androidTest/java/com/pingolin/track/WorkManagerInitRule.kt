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

package com.pingolin.track

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit TestRule that initializes WorkManager for instrumentation tests.
 *
 * [com.pingolin.track.TrackApplication] (production) implements
 * [Configuration.Provider], but under instrumentation we boot
 * HiltTestApplication — which doesn't. Any code path that touches
 * [androidx.work.WorkManager.getInstance] (e.g. LocationSyncWorker enqueue
 * in MainActivity.onCreate / validateSessionAndInitialize) crashes with
 * "WorkManager is not initialized properly" unless we hand it a config
 * up front.
 *
 * Use as a @Rule in any test that launches MainActivity or otherwise
 * reaches a WorkManager.getInstance call.
 */
class WorkManagerInitRule : TestWatcher() {

    override fun starting(description: Description) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        } catch (e: IllegalStateException) {
            // Already initialized — safe to ignore on subsequent test classes.
            Log.d("WorkManagerInitRule", "WorkManager already initialized: ${e.message}")
        }
    }
}
