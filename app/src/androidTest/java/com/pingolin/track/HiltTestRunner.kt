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

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom test runner for Hilt instrumented tests.
 *
 * Configures the test environment to use HiltTestApplication instead of the main app's
 * Application class. This allows Hilt test modules to replace production dependencies
 * with test doubles (fakes, mocks).
 *
 * Usage: Set as testInstrumentationRunner in build.gradle.kts:
 *   testInstrumentationRunner = "com.pingolin.track.HiltTestRunner"
 *
 * Native library loading:
 *   TrackApplication.companion.init {} loads libsqlcipher and libpingolin_mobile.
 *   HiltTestApplication doesn't run TrackApplication's companion initializers, so
 *   Room+SQLCipher blow up with UnsatisfiedLinkError the moment anything touches
 *   the database. We load them here before newApplication() returns so every
 *   instrumented test has the natives available.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?
    ): Application {
        // Load the same native libraries TrackApplication's companion would normally load.
        // Safe to call System.loadLibrary twice — it's a no-op after the first load.
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            // Re-thrown below if loading actually failed on this device/abi
            android.util.Log.e("HiltTestRunner", "Failed to load sqlcipher: ${e.message}")
        }
        try {
            System.loadLibrary("pingolin_mobile")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("HiltTestRunner", "Failed to load pingolin_mobile: ${e.message}")
        }
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
