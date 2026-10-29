/*
 * Copyright (c) 2025 Pingolin LLC
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

// Keep the Kotlin daemon alive.
//
// AGP pulls kotlin-daemon-client up to 2.4.0 while the Kotlin Gradle Plugin is
// 2.3.10 — `buildEnvironment` shows "kotlin-daemon-client:2.3.10 -> 2.4.0" —
// and KGP then calls a connectAndLease overload 2.4.0 does not have:
//
//   Failed to compile with Kotlin daemon: java.lang.NoSuchMethodError:
//   'CompileServiceSession KotlinCompilerClient.connectAndLease(...)'
//   Using fallback strategy: Compile without Kotlin daemon
//
// Nothing FAILS, so it is easy to miss: every Kotlin compilation quietly takes
// the in-process path instead. That costs little time, so the pin is for the
// silence, not the speed: a stack trace in every build log is noise that hides
// real ones, and the fallback is a latent trap if this module grows.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-daemon-client:2.3.10")
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.9.7" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    // Third-party licence inventory, generated from the dependencies' POM
    // metadata. See :app:generateThirdPartyLicenses.
    id("com.jaredsburrows.license") version "0.9.9" apply false
}
