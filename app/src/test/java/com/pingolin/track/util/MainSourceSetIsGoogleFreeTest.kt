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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Guards the flavour split: nothing in `src/main` may reference Firebase,
 * Google Play services or the Play Core/Integrity libraries. Those artifacts
 * are scoped to `gmsImplementation` in app/build.gradle.kts, so anything in
 * `src/main` that names them does not exist on the libre variant's classpath.
 *
 * WHY A TEST AND NOT A CONVENTION
 *   The libre flavour's whole value is the absence of these artifacts, and the
 *   natural way to get a location fix is to call
 *   `LocationServices.getFusedLocationProviderClient(context)` at the point you
 *   need one — which compiles fine, because the person adding it is almost
 *   certainly building a gms variant. The break then surfaces only when someone
 *   assembles libre, which may be days later in CI, as an unresolved reference
 *   in a file that looks nothing like the change that caused it.
 *
 *   This runs in BOTH flavours (it lives in `src/test`, not `src/testLibre`), so
 *   the gms build that introduced the import is the one that fails.
 *
 * IF THIS TEST FAILS
 *   Put the Google-specific code in `src/gms`, and give `src/libre` a
 *   counterpart. There are two worked examples: `PlayIntegrityManager` (same
 *   class name in both source sets) and
 *   [com.pingolin.track.location.LocationProvider] / [FcmTokenProvider]
 *   (interface in `src/main`, an implementation in each). Do NOT relax the list
 *   below.
 *
 *   A third option is to find that the Google API buys nothing the platform
 *   cannot: `MotionDetectionManager` lives in `src/main` and runs on the
 *   device's sensors alone, with no Play services Activity Recognition copy —
 *   which also spares the app an ACTIVITY_RECOGNITION permission Play would
 *   flag.
 *
 * NOT guarded, deliberately: `com.google.android.material` (Material
 * Components), `com.google.code.gson` and `com.google.dagger` / Hilt are
 * Apache-2.0 libraries with no Google services behind them. They ship in the
 * libre APK and that is fine.
 */
class MainSourceSetIsGoogleFreeTest {

    private val guarded = listOf(
        "com.google.firebase",
        "com.google.android.gms",
        "com.google.android.play",
    )

    @Test
    fun `src-main references no Firebase, Play services or Play Integrity`() {
        val sourceRoot = findMainSourceSet()
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { file -> guarded.any { file.readText().contains(it) } }
            .map { it.relativeTo(sourceRoot).path }
            .sorted()
            .toList()

        assertEquals(
            "These files in src/main reference Google artifacts that only the gms " +
                "flavour resolves, so the libre variant cannot compile them. Move the " +
                "code to src/gms and add a src/libre counterpart.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The gms source set is where these references are SUPPOSED to be. Asserting
     * it is non-empty keeps the test above honest: if src/gms were ever emptied
     * or renamed, the guard would still pass while guarding nothing.
     *
     * Pinned as an exact list rather than "not empty" so that DELETING one of
     * these — say, dropping the fused provider and quietly re-adding fused calls
     * to src/main — fails here too.
     */
    @Test
    fun `src-gms is where the Google references actually live`() {
        // findMainSourceSet() returns <root>/app/src/main, so one level up is
        // the source-set root that holds main/, gms/ and libre/.
        val gms = File(findMainSourceSet().parentFile, "gms")
        val referencing = gms.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { file -> guarded.any { file.readText().contains(it) } }
            .map { it.relativeTo(gms).path }
            .sorted()
            .toList()

        assertEquals(
            "Expected the Firebase service and its manifest entry, the fused location " +
                "provider, the keep-alive geofence, the Play Integrity manager and the FCM " +
                "token provider to be the Google surface of this app. Found: $referencing",
            listOf(
                "AndroidManifest.xml",
                "java/com/pingolin/track/location/FusedLocationProvider.kt",
                "java/com/pingolin/track/service/KeepAliveGeofence.kt",
                "java/com/pingolin/track/service/TrackFirebaseMessagingService.kt",
                "java/com/pingolin/track/util/FirebaseFcmTokenProvider.kt",
                "java/com/pingolin/track/util/PlayIntegrityManager.kt",
            ),
            referencing,
        )
    }

    /**
     * The libre source set must hold a counterpart for every gms class that
     * `src/main` injects by type or by name — otherwise the libre variant does
     * not compile, or worse, compiles with a Hilt binding missing at runtime.
     *
     * Checked by FILE rather than by class so it runs in the gms variant too:
     * `src/libre` is not on the gms compile path, so these types cannot be named
     * here.
     */
    @Test
    fun `src-libre carries a counterpart for every Google-backed capability`() {
        val libre = File(findMainSourceSet().parentFile, "libre")
        listOf(
            "java/com/pingolin/track/location/PlatformLocationProvider.kt",
            "java/com/pingolin/track/util/NoFcmTokenProvider.kt",
            "java/com/pingolin/track/util/PlayIntegrityManager.kt",
            "java/com/pingolin/track/di/DistributionModule.kt",
            // Not Google-backed, but flavour-paired for the same reason: src/main
            // names it, so a missing counterpart breaks one variant's build.
            "java/com/pingolin/track/util/PlanManagement.kt",
        ).forEach {
            assertTrue("src/libre is missing $it", File(libre, it).isFile)
        }

        val offenders = libre.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { file -> guarded.any { file.readText().contains(it) } }
            .map { it.relativeTo(libre).path }
            .sorted()
            .toList()
        assertEquals(
            "src/libre exists precisely to contain no Google references.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Walks up from the working directory rather than assuming it: Gradle
     * running unit tests from the module dir is a default, not a guarantee, and
     * a test that silently scanned an empty tree would pass forever.
     */
    private fun findMainSourceSet(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main")
            if (File(candidate, "java/com/pingolin/track").isDirectory) return candidate
            val direct = File(dir, "src/main")
            if (File(direct, "java/com/pingolin/track").isDirectory) return direct
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate src/main from ${File("").absolutePath}. Fix this lookup " +
                "rather than deleting the test — an unfindable tree means the guard is " +
                "not guarding anything."
        )
    }
}
