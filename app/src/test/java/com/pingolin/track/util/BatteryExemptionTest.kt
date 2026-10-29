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

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * On phones whose battery manager stops background apps, Track asks once to run in the background,
 * and Android's own one-tap question follows, in place of a long walk through the app's settings.
 * Not asked again after "Not now", unless sharing really stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatteryExemptionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun question() = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))

    @Test
    fun `the app may ask Android's one-tap question`() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
    }

    @Test
    fun `the request opens Android's own question for this app first`() {
        assertTrue(BatteryOptimizationHelper.requestExemption(context))

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started.action)
        assertEquals("package:${context.packageName}", started.dataString)
    }

    @Test
    fun `the setup question only where the phone has Android's`() {
        assertFalse(BatteryOptimizationHelper.canAskForExemption(context))

        shadowOf(context.packageManager).addResolveInfoForIntent(
            question(),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations"
                }
            },
        )

        assertTrue(BatteryOptimizationHelper.canAskForExemption(context))
    }

    @Test
    fun `the way back to it is Android's list, and the phone's own page falls back to this app's`() {
        assertTrue(BatteryOptimizationHelper.openExemptionList(context))
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            shadowOf(context as Application).nextStartedActivity.action,
        )
        // No OEM page on this test phone: this app's own settings page, never the question.
        assertTrue(BatteryOptimizationHelper.openBatterySettings(context))
        val page = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, page.action)
        assertEquals("package:${context.packageName}", page.dataString)
    }

    @Test
    fun `the words, word for word`() {
        assertEquals("Let Track run in the background?", context.getString(R.string.background_exemption_title))
        assertEquals(
            "This phone can stop apps that run in the background, and then your circles may stop getting your " +
                "location. Letting Track run in the background helps keep sharing going. Android will ask you to " +
                "confirm. It can use more battery, and you can change it any time in Track's settings.",
            context.getString(R.string.background_exemption_body),
        )
        assertEquals("Continue", context.getString(R.string.background_exemption_continue))
        assertEquals("Not now", context.getString(R.string.background_exemption_not_now))
        assertEquals("Run in the background", context.getString(R.string.settings_background_exemption_title))
        // Allowed: a tap opens Android's list, where it changes back, and the line says so.
        assertEquals(
            "Allowed. Android's battery optimization no longer limits Track. Tap to change it.",
            context.getString(R.string.settings_background_exemption_allowed),
        )
        assertEquals(
            "Not allowed. Android may stop Track in the background to save battery, and your circles may stop " +
                "getting your location. Tap to allow it.",
            context.getString(R.string.settings_background_exemption_not_allowed),
        )
        assertEquals("This phone's own battery settings", context.getString(R.string.settings_oem_battery_title))
        assertEquals(
            "Some phones have their own setting that can stop Track. Open it and check that Track may run in " +
                "the background.",
            context.getString(R.string.settings_oem_battery_summary),
        )
    }

    @Test
    fun `asked once, only on such phones, and every answer is kept`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val ask = main.substring(main.indexOf("private fun checkBatteryOptimization()"))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        val exempt = ask.indexOf("if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) return")
        val asked = ask.indexOf("if (sessionManager.isBatteryOptShown()) return")
        val phone = ask.indexOf("if (!BatteryOptimizationHelper.isAggressiveOem()) return")
        // The words say Android asks next: only where it can.
        val android = ask.indexOf("if (!BatteryOptimizationHelper.canAskForExemption(this)) return")
        assertTrue("not exempt, not asked yet, the phone, Android's question",
            exempt >= 0 && asked > exempt && phone > asked && android > phone)
        assertTrue(ask.contains("BatteryOptimizationHelper.requestExemption(this)"))
        // "Allow" or "Open settings", "Not now", and a dismissal: each one is the answer.
        assertEquals(3, Regex("""sessionManager\.setBatteryOptShown\(true\)""").findAll(ask).count())
        assertTrue(ask.contains(".setOnCancelListener { sessionManager.setBatteryOptShown(true) }"))
        // There is no long walk through the app's settings.
        assertFalse(main.contains("getManufacturerInfo"))
        assertFalse(File("src/main/java/com/pingolin/track/util/BatteryOptimizationHelper.kt").readText()
            .contains("ManufacturerInfo"))
    }

    @Test
    fun `Settings keeps the way back, on those phones only`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        val setup = settings.substring(settings.indexOf("private fun setupBackgroundExemption()"))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(setup.contains("val shown = BatteryOptimizationHelper.isAggressiveOem()"))
        assertTrue(setup.contains("binding.layoutBackgroundExemption.visibility = if (shown) View.VISIBLE else View.GONE"))
        assertTrue(setup.contains("binding.layoutOemBatterySettings.visibility = if (shown) View.VISIBLE else View.GONE"))
        // Not allowed: a tap asks Android; allowed: its list, where it changes back.
        assertTrue(setup.contains(
            "if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(activity)) {\n" +
                "                BatteryOptimizationHelper.openExemptionList(activity)\n" +
                "            } else {\n" +
                "                BatteryOptimizationHelper.requestExemption(activity)"
        ))
        assertTrue(setup.contains("BatteryOptimizationHelper.openBatterySettings(requireActivity())"))
        // Said again at every return from Android's screens.
        val resume = settings.substring(settings.indexOf("override fun onResume()"))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(resume.contains("refreshBackgroundExemption()"))
        val layout = File("src/main/res/layout/fragment_settings.xml").readText()
        for (id in listOf("layoutBackgroundExemption", "layoutOemBatterySettings")) {
            val row = layout.substring(layout.indexOf("android:id=\"@+id/$id\""))
            assertTrue(id, row.substring(0, row.indexOf(">")).contains("android:visibility=\"gone\""))
        }
    }

    @Test
    fun `asked again only when sharing really stops, once an outage`() {
        val watchdog = File("src/main/java/com/pingolin/track/receiver/WatchdogAlarmReceiver.kt").readText()
        assertTrue(watchdog.contains(
            "if (failures == TrackingHealth.ESCALATE_AFTER_FAILURES) sessionManager.setBatteryOptShown(false)"
        ))
        // Both places where a restart is counted as failed.
        assertEquals(2, Regex("""rearmBackgroundPromptAt\(failures\)""").findAll(watchdog).count())
    }
}
