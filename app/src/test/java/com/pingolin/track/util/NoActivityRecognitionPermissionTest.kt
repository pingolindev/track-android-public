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
 * No manifest, in any source set, may request ACTIVITY_RECOGNITION.
 *
 * ## Why this exists
 *
 * The permission would serve only the Play services Activity Recognition API.
 * Google Play classes it as a sensitive permission and asks the app to declare
 * health features for it — on top of the ACCESS_BACKGROUND_LOCATION declaration
 * a location-sharing app already carries — and all it would power is the choice
 * of a GPS poll interval (`LocationTrackingService.POLL_*_MS`).
 *
 * Motion detection uses the device's sensors instead ([MotionDetectionManager],
 * in `src/main`, for both flavours), which need no permission.
 *
 * ## Why a test
 *
 * The cost of adding it is not a compile error — it is a Play review question
 * months later, on a build someone is trying to ship. `MotionDetectionManager`
 * is also the obvious place to "improve" motion detection by reaching for the
 * Play API. This fails at the moment of the change instead.
 *
 * ## If this fails
 *
 * Do not add the permission without weighing a sensitive-permission review and
 * a "Physical activity" prompt against what the better motion signal actually
 * buys. TYPE_SIGNIFICANT_MOTION and TYPE_ACCELEROMETER are not behind any
 * permission and already cover STILL-vs-MOVING, which is the whole of
 * [MotionState].
 */
class NoActivityRecognitionPermissionTest {

    @Test
    fun `no source set requests ACTIVITY_RECOGNITION`() {
        val srcRoot = findSourceSetRoot()
        val manifests = srcRoot.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()

        // Without this the walk below could pass on an empty list, which is the
        // one result that proves nothing.
        assertTrue(
            "Found no AndroidManifest.xml under $srcRoot — fix this lookup rather " +
                "than deleting the test; an empty walk is not a passing guard.",
            manifests.size >= 2,
        )

        val offenders = manifests
            .filter { m ->
                m.readText()
                    .lineSequence()
                    // Comments explaining the absence are expected and must not
                    // trip this; only a real element counts.
                    .any { it.contains("<uses-permission") && it.contains("ACTIVITY_RECOGNITION") }
            }
            .map { it.relativeTo(srcRoot).path }
            .sorted()

        assertEquals(
            "ACTIVITY_RECOGNITION is deliberately not requested — see this test's KDoc " +
                "before re-adding it.",
            emptyList<String>(),
            offenders,
        )
    }

    /** `MotionDetectionManager` must stay the shared, permission-free one. */
    @Test
    fun `motion detection lives in src-main and names no Play services API`() {
        val srcRoot = findSourceSetRoot()
        val shared = File(srcRoot, "main/java/com/pingolin/track/util/MotionDetectionManager.kt")
        assertTrue("MotionDetectionManager is not in src/main: ${shared.path}", shared.isFile)

        val text = shared.readText()
        assertTrue(
            "src/main MotionDetectionManager must not reference Play services",
            !text.contains("com.google.android.gms"),
        )
        assertTrue(
            "src/main MotionDetectionManager should drive itself from the platform sensors",
            text.contains("TYPE_SIGNIFICANT_MOTION"),
        )

        for (flavour in listOf("gms", "libre")) {
            val stray = File(srcRoot, "$flavour/java/com/pingolin/track/util/MotionDetectionManager.kt")
            assertTrue(
                "src/$flavour must not carry its own MotionDetectionManager — " +
                    "src/main holds the single shared implementation.",
                !stray.exists(),
            )
        }
    }

    private fun findSourceSetRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src")
            if (File(candidate, "main/java/com/pingolin/track").isDirectory) return candidate
            val direct = File(dir, "src")
            if (File(direct, "main/java/com/pingolin/track").isDirectory) return direct
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate app/src from ${File("").absolutePath}. Fix this lookup " +
                "rather than deleting the test — an unfindable tree means the guard is " +
                "not guarding anything."
        )
    }
}
