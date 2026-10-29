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

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper for navigating manufacturer-specific battery optimization settings.
 *
 * Many OEMs (Samsung, Xiaomi, Huawei, OnePlus, Oppo, Vivo) aggressively kill
 * background services. This helper asks Android's own one-tap question to let the app
 * run in the background, and provides the correct intent for each manufacturer's
 * battery/autostart settings page so users can whitelist the app.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * Returns true if the device manufacturer is known to aggressively kill background apps.
     * Stock Android (Google/Pixel), Motorola, Nokia, Sony do not need the dialog.
     */
    fun isAggressiveOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("samsung") ||
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            m.contains("huawei") || m.contains("honor") ||
            m.contains("oneplus") ||
            m.contains("oppo") || m.contains("realme") ||
            m.contains("vivo") ||
            m.contains("meizu") ||
            m.contains("letv") || m.contains("leeco")
    }

    /**
     * Check if the app is already exempt from battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Whether this phone has Android's own one-tap question for this app. */
    fun canAskForExemption(context: Context): Boolean =
        exemptionRequest(context).resolveActivity(context.packageManager) != null

    /**
     * Ask Android to let this app run in the background: its own one-tap question
     * (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) where the phone has it, else the battery-optimisation
     * list, else this app's own settings page. Never an OEM's deep link first: the question is the
     * one tap, the rest are a long way round.
     *
     * @return true if one of them was opened
     */
    fun requestExemption(context: Context): Boolean =
        tryStartActivity(context, exemptionRequest(context)) || openExemptionList(context)

    @SuppressLint("BatteryLife")
    private fun exemptionRequest(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Try to open the manufacturer-specific battery settings page ("This phone's own battery
     * settings"). Falls back to this app's own settings page.
     *
     * @return true if an intent was successfully launched
     */
    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val pkg = context.packageName

        // Try manufacturer-specific intents first
        val manufacturerIntents = when {
            manufacturer.contains("samsung") -> listOf(
                // Samsung Device Care / Battery settings
                createIntent("com.samsung.android.lool", "com.samsung.android.lool.activity.MainActivity"),
                createIntent("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                // App-specific settings page (user can find Battery > Allow background activity)
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
                createIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                createIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                createIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                createIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            manufacturer.contains("oneplus") -> listOf(
                createIntent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                createIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                createIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            manufacturer.contains("vivo") -> listOf(
                createIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            else -> emptyList()
        }

        // Try each manufacturer-specific intent
        for (intent in manufacturerIntents) {
            if (tryStartActivity(context, intent)) {
                return true
            }
        }

        // Fallback: this app's own settings page, where its battery use is set
        return tryStartActivity(context, appDetails(context))
    }

    /**
     * Android's list of apps its battery optimization doesn't limit: where an exemption changes back
     * (there is no one-tap way back). Else this app's own settings page.
     */
    fun openExemptionList(context: Context): Boolean =
        tryStartActivity(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            tryStartActivity(context, appDetails(context))

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }

    private fun createIntent(packageName: String, className: String): Intent {
        return Intent().apply {
            component = ComponentName(packageName, className)
        }
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            // From outside an activity (a receiver, the application) a start needs its own task.
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "Opened: ${intent.component ?: intent.action}")
            true
        } catch (e: Exception) {
            Log.d(TAG, "Intent not available: ${intent.component ?: intent.action}")
            false
        }
    }
}
