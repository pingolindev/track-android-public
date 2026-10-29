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

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Grants every runtime permission [com.pingolin.track.ui.main.MainActivity] asks
 * for, so it never launches the permission UI during a test.
 *
 * ## Why this exists
 *
 * MainActivity requests ACCESS_FINE/COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION
 * and POST_NOTIFICATIONS on startup. Any of them left ungranted fires a
 * REQUEST_PERMISSIONS intent; on API 30+ a background-location request routes to
 * permissioncontroller's MANAGE_APP_PERMISSION *Activity*, which comes to the
 * foreground and PAUSES MainActivity ~80ms after it resumed. Espresso then fails
 * the whole class with `NoActivityResumedException: No activities in stage
 * RESUMED`, or — when the permission window lingers —
 * `RootViewWithoutFocusException`. Neither message points at the cause.
 *
 * ## Why not just extend GrantPermissionRule
 *
 * The permission set is not uniform across devices: POST_NOTIFICATIONS
 * does not exist below API 33 and ACCESS_BACKGROUND_LOCATION is special-cased
 * by the platform. Naming an unknown permission is an error, so each grant is
 * attempted independently and a failure is logged rather than thrown — a
 * permission that cannot exist on this API level is not a reason to fail the
 * test.
 *
 * Use alongside [WorkManagerInitRule] in any test that launches MainActivity.
 */
class MainActivityPermissionsRule : TestWatcher() {

    override fun starting(description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName

        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        for (permission in permissions) {
            try {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, permission)
            } catch (e: Exception) {
                // Not fatal: the app simply must not be blocked ON THIS device by
                // a permission the platform will not grant here.
                Log.w(TAG, "Could not grant $permission: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "MainActivityPermissionsRule"
    }
}
