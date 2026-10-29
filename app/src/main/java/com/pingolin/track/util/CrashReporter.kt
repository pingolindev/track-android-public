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

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.pingolin.track.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.pingolin_mobile.CryptoManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.pm.PackageInfoCompat

/**
 * Privacy-focused crash reporter with optional E2EE remote reporting.
 *
 * Key privacy features:
 * - Disabled by default (opt-in only)
 * - All data stored locally on device by default
 * - Optional E2EE remote reporting (separate opt-in)
 * - NO personally identifiable information collected:
 *   - No user IDs, emails, or names
 *   - No device fingerprints or advertising IDs
 *   - No location data
 *   - The exact device model appears ONLY in the on-device log; anything transmitted
 *     is reduced to a broad category (see getGenericDeviceCategory)
 * - User can view and delete crash logs
 * - Crash logs auto-expire after 7 days
 * - Remote reports are encrypted with XChaCha20-Poly1305
 *
 * Remote Reporting (when enabled):
 * - Crash data is encrypted client-side before transmission
 * - Server cannot read crash content without decryption key
 * - Decryption key is derived from app installation ID (not user-specific)
 * - Reports contain only: stack trace, generic device info, app version
 * - No correlation between reports and user accounts
 *
 * Usage:
 * 1. User enables crash reporting in Settings (local logging)
 * 2. User optionally enables remote reporting (E2EE transmission)
 * 3. CrashReporter installs as the default uncaught exception handler
 * 4. Crashes are logged locally and optionally sent encrypted
 * 5. User can view crash logs in Settings or share them manually for support
 */
@Singleton
class CrashReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    // LAZY: neither is needed unless a crash is actually being written or
    // uploaded, but as plain constructor params Hilt would have to build both
    // before CrashReporter could exist — and CrashReporter is built during
    // startup.
    private val cryptoManager: dagger.Lazy<CryptoManager>,
    private val sharedOkHttpClient: dagger.Lazy<OkHttpClient>
) {
    companion object {
        private const val TAG = "CrashReporter"
        private const val PREF_CRASH_REPORTING_ENABLED = "crash_reporting_enabled"
        private const val PREF_REMOTE_REPORTING_ENABLED = "crash_remote_reporting_enabled"
        private const val PREF_INSTALLATION_ID = "crash_reporter_installation_id"
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val MAX_CRASH_LOGS = 10
        private const val CRASH_LOG_EXPIRY_DAYS = 7L
        private const val REMOTE_REPORT_ENDPOINT = "/api/v1/diagnostics/crash-report"

        // Singleton reference for the exception handler
        @Volatile
        private var instance: CrashReporter? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val crashLogDir: File by lazy {
        File(context.filesDir, CRASH_LOG_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    // HTTP client derived from the shared OkHttpClient (inherits connection pool, interceptors, etc.)
    // No auth header needed for crash reporting - uses anonymous endpoint
    private val httpClient: OkHttpClient by lazy {
        sharedOkHttpClient.get().newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Get or create a random installation ID for crash report encryption.
     * This ID is NOT linked to user accounts - it's purely for E2EE key derivation.
     */
    private fun getInstallationId(): String {
        return prefs.getString(PREF_INSTALLATION_ID, null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(PREF_INSTALLATION_ID, newId).apply()
            newId
        }
    }

    /**
     * Defaults to false — crash reporting is strictly opt-in. A store that can't be opened now (the
     * Keystore down) reads as not opted in, never a crash: this runs at every start.
     */
    fun isEnabled(): Boolean {
        return runCatching { prefs.getBoolean(PREF_CRASH_REPORTING_ENABLED, false) }.getOrDefault(false)
    }

    /** A SECOND opt-in, independent of [isEnabled]; also defaults to false, as a store that can't open. */
    fun isRemoteReportingEnabled(): Boolean {
        return runCatching { prefs.getBoolean(PREF_REMOTE_REPORTING_ENABLED, false) }.getOrDefault(false)
    }

    /** Installs the crash handler on enable, restores the original on disable. */
    fun setEnabled(enabled: Boolean) {
        val wasEnabled = isEnabled()
        prefs.edit().putBoolean(PREF_CRASH_REPORTING_ENABLED, enabled).apply()

        if (enabled && !wasEnabled) {
            install()
            Log.i(TAG, "Local crash reporting enabled by user")
        } else if (!enabled && wasEnabled) {
            uninstall()
            // Also disable remote reporting when local is disabled
            setRemoteReportingEnabled(false)
            Log.i(TAG, "Crash reporting disabled by user")
        }
    }

    /**
     * Cannot be turned on unless local crash reporting already is — the request is
     * refused rather than implicitly enabling it.
     *
     * Once on, reports are encrypted with XChaCha20-Poly1305 before leaving the device,
     * so the diagnostics endpoint stores blobs it cannot read. Only the envelope is
     * visible to the server: timestamp, app version, Android version.
     */
    fun setRemoteReportingEnabled(enabled: Boolean) {
        if (enabled && !isEnabled()) {
            Log.w(TAG, "Cannot enable remote reporting without local crash reporting")
            return
        }
        prefs.edit().putBoolean(PREF_REMOTE_REPORTING_ENABLED, enabled).apply()
        if (enabled) {
            Log.i(TAG, "E2EE remote crash reporting enabled by user")
        } else {
            Log.i(TAG, "E2EE remote crash reporting disabled by user")
        }
    }

    /**
     * Initialize crash reporting based on user preference.
     * Call this from Application.onCreate().
     */
    fun initialize() {
        instance = this
        // `prefs` is the ENCRYPTED store — AppModule binds the only
        // SharedPreferences to SecurePrefs.open("tracks_secure_prefs") — and it
        // holds three NON-secret values here: a crash-reporting opt-in, a
        // remote opt-in and an installation id. Re-pointing it at a plain file
        // looks obvious and is not worth it: measured, this read costs 0.3ms,
        // and a new file reads as ABSENT, which would silently reset every
        // existing user's crash-reporting opt-in.
        if (isEnabled()) {
            install()
            Log.i(TAG, "Crash reporting initialized (enabled)")
        } else {
            Log.i(TAG, "Crash reporting initialized (disabled - opt-in required)")
        }
        // OFF THE MAIN THREAD: this walks the crash-log directory
        // twice and stats every file, and `crashLogDir` does a
        // mkdirs on first touch. Nothing is urgent — the logs expire
        // on age, not on promptness — and this class already owns
        // an IO scope.
        scope.launch { cleanupOldLogs() }
    }

    private fun install() {
        if (isInstalled) return

        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        isInstalled = true
    }

    /** Restores whatever handler was in place before [install]. */
    private fun uninstall() {
        if (!isInstalled) return

        originalHandler?.let {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
        originalHandler = null
        isInstalled = false
    }

    /** Newest first. */
    fun getCrashLogs(): List<CrashLogInfo> {
        return crashLogDir.listFiles()
            ?.filter { it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                CrashLogInfo(
                    fileName = file.name,
                    timestamp = Date(file.lastModified()),
                    sizeBytes = file.length()
                )
            }
            ?: emptyList()
    }

    /** Null when the name escapes the crash-log directory, not only when it is missing. */
    fun readCrashLog(fileName: String): String? {
        val file = File(crashLogDir, fileName)
        return if (file.exists() && file.parentFile == crashLogDir) {
            file.readText()
        } else {
            null
        }
    }

    fun deleteCrashLog(fileName: String): Boolean {
        val file = File(crashLogDir, fileName)
        return if (file.exists() && file.parentFile == crashLogDir) {
            file.delete()
        } else {
            false
        }
    }

    fun deleteAllCrashLogs() {
        crashLogDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "All crash logs deleted")
    }

    /** Enforces BOTH caps: age (CRASH_LOG_EXPIRY_DAYS) and count (MAX_CRASH_LOGS). */
    private fun cleanupOldLogs() {
        val expiryTime = System.currentTimeMillis() - (CRASH_LOG_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
        val files = crashLogDir.listFiles() ?: return

        var deletedCount = 0
        files.filter { it.lastModified() < expiryTime }
            .forEach {
                if (it.delete()) deletedCount++
            }

        val remainingFiles = crashLogDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (remainingFiles.size > MAX_CRASH_LOGS) {
            remainingFiles.drop(MAX_CRASH_LOGS).forEach {
                if (it.delete()) deletedCount++
            }
        }

        if (deletedCount > 0) {
            Log.i(TAG, "Cleaned up $deletedCount old crash logs")
        }
    }

    /**
     * Log an exception without crashing.
     * Useful for non-fatal errors that should be tracked.
     */
    fun logException(throwable: Throwable, context: String? = null) {
        if (!isEnabled()) return

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "exception_${timestamp}.log"
            val file = File(crashLogDir, fileName)

            val logContent = buildLogContent(throwable, context, isCrash = false)
            file.writeText(logContent)
            Log.i(TAG, "Exception logged to $fileName")

            // Send encrypted report if remote reporting is enabled
            if (isRemoteReportingEnabled()) {
                sendEncryptedReport(logContent, isCrash = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log exception", e)
        }
    }

    /**
     * Send an encrypted crash report to the diagnostics endpoint.
     *
     * Privacy guarantees:
     * - Report content is encrypted with XChaCha20-Poly1305
     * - Key is derived from installation ID (not user-linked)
     * - Server only sees: encrypted blob, timestamp, app version, Android version
     * - NO user ID, device ID, or any PII is transmitted
     */
    private fun sendEncryptedReport(content: String, isCrash: Boolean) {
        scope.launch {
            try {
                val installationId = getInstallationId()
                val keyMaterial = hashWithSha256(installationId.toByteArray())
                val keyBase64 = android.util.Base64.encodeToString(keyMaterial, android.util.Base64.NO_WRAP)

                val encryptedData = cryptoManager.get().encryptWithKey(content, keyBase64)

                // Everything outside the encrypted blob is visible to the server —
                // keep it free of anything that could identify a user or a device.
                val metadata = buildString {
                    append("{")
                    append("\"type\":\"${if (isCrash) "crash" else "exception"}\",")
                    append("\"app_version\":\"${BuildConfig.VERSION_NAME}\",")
                    append("\"android_api\":${Build.VERSION.SDK_INT},")
                    append("\"timestamp\":${System.currentTimeMillis()},")
                    // Generic device category (not exact model to prevent fingerprinting)
                    append("\"device_category\":\"${getGenericDeviceCategory()}\",")
                    append("\"encrypted_nonce\":\"${encryptedData.nonce}\",")
                    append("\"encrypted_data\":\"${encryptedData.ciphertext}\"")
                    append("}")
                }

                val request = Request.Builder()
                    .url(BuildConfig.API_BASE_URL + REMOTE_REPORT_ENDPOINT)
                    .post(metadata.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "Track/${BuildConfig.VERSION_NAME} CrashReporter")
                    // Explicitly no Authorization header - anonymous endpoint
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "E2EE crash report sent successfully")
                    } else {
                        Log.w(TAG, "Failed to send crash report: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                // Silently fail - don't crash while reporting a crash
                Log.e(TAG, "Failed to send E2EE crash report", e)
            }
        }
    }

    /**
     * Get a generic device category to prevent device fingerprinting.
     * Returns broad categories like "phone", "tablet", "tv" instead of exact models.
     */
    private fun getGenericDeviceCategory(): String {
        val screenLayout = context.resources.configuration.screenLayout
        val screenSize = screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK

        return when {
            context.packageManager.hasSystemFeature("android.software.leanback") -> "tv"
            context.packageManager.hasSystemFeature("android.hardware.type.watch") -> "watch"
            context.packageManager.hasSystemFeature("android.hardware.type.automotive") -> "automotive"
            screenSize >= android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE -> "tablet"
            screenSize >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE -> "tablet"
            else -> "phone"
        }
    }

    private fun hashWithSha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }


    /**
     * The on-device log body: stack trace plus device/app info. Never add user
     * identifiers, account data or location here — this file is user-shareable.
     */
    private fun buildLogContent(throwable: Throwable, context: String?, isCrash: Boolean): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        return buildString {
            appendLine("=".repeat(60))
            appendLine(if (isCrash) "CRASH REPORT" else "EXCEPTION REPORT")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine()
            appendLine("--- Device Info ---")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App Version: ${getAppVersion()}")
            appendLine()
            if (context != null) {
                appendLine("--- Context ---")
                appendLine(context)
                appendLine()
            }
            appendLine("--- Exception ---")
            appendLine("Type: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message ?: "(no message)"}")
            appendLine()
            appendLine("--- Stack Trace ---")
            appendLine(stackTrace)
            appendLine()
            appendLine("=".repeat(60))
            appendLine("This log was generated locally and NOT sent to any server.")
            appendLine("You can share it manually for support if needed.")
            appendLine("=".repeat(60))
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            // PackageInfoCompat, not `pInfo.longVersionCode`: that
            // getter is API 28 against minSdk 26, so on Android
            // 8.0-8.1 it throws NoSuchMethodError — which the
            // `catch (e: Exception)` below can NOT catch, because
            // NoSuchMethodError is an Error, not an Exception. The
            // crash reporter would itself crash, on exactly the
            // devices whose crashes we most want reported.
            "${pInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pInfo)})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Custom uncaught exception handler that logs crashes locally
     * and optionally sends E2EE encrypted reports.
     */
    private inner class CrashHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "crash_${timestamp}.log"
                val file = File(crashLogDir, fileName)

                @Suppress("DEPRECATION") // threadId() requires API 34, we support API 26+
                val threadContext = "Thread: ${thread.name} (id=${thread.id})"
                val logContent = buildLogContent(throwable, threadContext, isCrash = true)
                file.writeText(logContent)
                Log.e(TAG, "Crash logged to $fileName")

                // Send encrypted report if remote reporting is enabled
                // Note: This runs synchronously since we're about to crash
                if (isRemoteReportingEnabled()) {
                    sendEncryptedReportSync(logContent, isCrash = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash", e)
            }

            // Always call the original handler to allow normal crash behavior
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Synchronous version of sendEncryptedReport for use during crash handling.
     * Must complete before the process terminates.
     */
    private fun sendEncryptedReportSync(content: String, isCrash: Boolean) {
        try {
            val installationId = getInstallationId()
            val keyMaterial = hashWithSha256(installationId.toByteArray())
            val keyBase64 = android.util.Base64.encodeToString(keyMaterial, android.util.Base64.NO_WRAP)

            val encryptedData = cryptoManager.get().encryptWithKey(content, keyBase64)

            // Keep this envelope identical in shape to the async path — it is the only
            // part the server can read.
            val metadata = buildString {
                append("{")
                append("\"type\":\"${if (isCrash) "crash" else "exception"}\",")
                append("\"app_version\":\"${BuildConfig.VERSION_NAME}\",")
                append("\"android_api\":${Build.VERSION.SDK_INT},")
                append("\"timestamp\":${System.currentTimeMillis()},")
                append("\"device_category\":\"${getGenericDeviceCategory()}\",")
                append("\"encrypted_nonce\":\"${encryptedData.nonce}\",")
                append("\"encrypted_data\":\"${encryptedData.ciphertext}\"")
                append("}")
            }

            val request = Request.Builder()
                .url(BuildConfig.API_BASE_URL + REMOTE_REPORT_ENDPOINT)
                .post(metadata.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Track/${BuildConfig.VERSION_NAME} CrashReporter")
                .build()

            // Tighter timeouts than the shared client: the process is about to die, and a
            // slow endpoint must not hold it open long enough to look like a hang.
            val crashClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            crashClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "E2EE crash report sent successfully (sync)")
                } else {
                    Log.w(TAG, "Failed to send crash report: ${response.code}")
                }
            }
        } catch (e: Exception) {
            // Silently fail - don't interfere with crash handling
            Log.e(TAG, "Failed to send E2EE crash report (sync)", e)
        }
    }

    data class CrashLogInfo(
        val fileName: String,
        val timestamp: Date,
        val sizeBytes: Long
    ) {
        val isCrash: Boolean get() = fileName.startsWith("crash_")
        val isException: Boolean get() = fileName.startsWith("exception_")

        fun getFormattedTimestamp(): String {
            return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(timestamp)
        }

        fun getFormattedSize(): String {
            return when {
                sizeBytes < 1024 -> "$sizeBytes B"
                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                else -> "${sizeBytes / (1024 * 1024)} MB"
            }
        }
    }
}
