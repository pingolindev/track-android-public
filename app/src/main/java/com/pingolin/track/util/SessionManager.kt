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
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pingolin.track.service.TrackingStop
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages user session data using EncryptedSharedPreferences.
 *
 * SECURITY: All session tokens, credentials, and sensitive user data are stored
 * encrypted using:
 * - AES-256-SIV for key encryption
 * - AES-256-GCM for value encryption
 * - Hardware-backed Android Keystore for the master key
 *
 * On first access, data in the legacy unencrypted SharedPreferences file
 * ("tracks_secure_prefs") is copied into the encrypted store. The legacy file is
 * deliberately left in place afterwards — see [migrateFromLegacyPrefs].
 */
@Singleton
open class SessionManager @Inject constructor(
    private val context: Context,
    // Made at the store's first open, inside createEncryptedPrefs' catch: never while Hilt
    // injects, where a Keystore that is down would crash every start.
    private val masterKey: dagger.Lazy<MasterKey>? = null
) {
    /** Backwards-compatible single-arg constructor used by test fakes. */
    constructor(context: Context) : this(context, null)

    companion object {
        private const val TAG = "SessionManager"

        /** Name for the encrypted preferences file. */
        private const val ENCRYPTED_PREFS_NAME = "pingolin_session_encrypted"

        /**
         * Legacy prefs file name used before the EncryptedSharedPreferences migration.
         * Data is copied from this file on first access; the file itself is left in
         * place (see [migrateFromLegacyPrefs]).
         */
        private const val LEGACY_PREFS_NAME = "tracks_secure_prefs"

        private const val KEY_MIGRATION_DONE = "session_migration_done"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_ENCRYPTED_PRIVATE_KEY = "encrypted_private_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_REGISTERED = "device_registered"
        private const val KEY_REGISTERED_DEVICE_ID = "registered_device_id"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_PUSH_TOKEN_SYNCED = "push_token_synced"
        private const val KEY_LOCATION_RATIONALE_DECLINED = "location_rationale_declined"
        private const val KEY_LOCATION_ASKED = "location_permission_asked"
        private const val KEY_PUSH_TYPE = "push_type"
        private const val KEY_PUSH_ENDPOINT = "push_endpoint"

        // Push notification types
        const val PUSH_TYPE_FCM = "fcm"
        const val PUSH_TYPE_UNIFIED_PUSH = "unifiedpush"
        private const val KEY_ALWAYS_ON_NOTIFICATIONS = "always_on_notifications"
        private const val KEY_NOTIFICATION_DETAIL = "notification_detail_level"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_LAST_BACKGROUND_TIME = "last_background_time"
        private const val KEY_LAST_SUCCESSFUL_SEND_AT = "last_successful_send_at"
        // Baseline for the moved / not-moved send decision — see
        // getLastHistorySentPosition. Cleared with the session: it is a position, and
        // it belongs to the user who was signed in when it was taken.
        private const val KEY_LAST_HISTORY_SENT_LAT = "last_history_sent_lat"
        private const val KEY_LAST_HISTORY_SENT_LNG = "last_history_sent_lng"
        private const val KEY_LAST_HISTORY_SENT_AT = "last_history_sent_at"
        private const val KEY_LAST_SELF_KEY_REPUBLISH_AT = "last_self_key_republish_at"
        private const val KEY_UNUSABLE_SELF_KEY_COPY = "unusable_self_key_copy"
        private const val KEY_USE_SERVER_GEOCODER = "use_server_geocoder"
        private const val KEY_FORCE_AUTH_NEXT_LAUNCH = "force_auth_next_launch"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DISTANCE_UNIT = "distance_unit"

        // App Lock settings
        private const val KEY_APP_LOCK_METHOD = "app_lock_method"
        private const val KEY_APP_LOCK_PIN_HASH = "app_lock_pin_hash"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout"

        // PIN lockout settings (stored in EncryptedSharedPreferences)
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"

        // App Lock methods
        const val APP_LOCK_NONE = "none"
        const val APP_LOCK_PIN = "pin"
        const val APP_LOCK_BIOMETRICS = "biometrics"

        // Notification display detail levels (Signal-style). Controls how
        // much decrypted content AlertHandler renders into tray
        // notifications — a pure display-layer choice; the push transport
        // is always a content-free tickle and the in-app bell list always
        // shows full detail (it sits behind the app lock).
        const val NOTIF_DETAIL_FULL = "full"
        const val NOTIF_DETAIL_NAMES = "names"
        const val NOTIF_DETAIL_GENERIC = "generic"

        // Auto-lock timeout values in milliseconds
        const val AUTO_LOCK_IMMEDIATELY = 0L
        const val AUTO_LOCK_1_MIN = 60 * 1000L
        const val AUTO_LOCK_2_MIN = 2 * 60 * 1000L
        const val AUTO_LOCK_3_MIN = 3 * 60 * 1000L
        const val AUTO_LOCK_5_MIN = 5 * 60 * 1000L
        const val AUTO_LOCK_10_MIN = 10 * 60 * 1000L
        const val AUTO_LOCK_15_MIN = 15 * 60 * 1000L
        const val AUTO_LOCK_30_MIN = 30 * 60 * 1000L
        const val AUTO_LOCK_60_MIN = 60 * 60 * 1000L

        // Default auto-lock timeout (3 minutes)
        private const val DEFAULT_AUTO_LOCK_TIMEOUT = 3 * 60 * 1000L
        private const val KEY_BATTERY_SAVER_ENABLED = "battery_saver_enabled"
        private const val KEY_BATTERY_SAVER_INTERVAL = "battery_saver_interval"
        private const val KEY_TRACKING_INTERVAL = "tracking_interval"
        private const val KEY_SUBSCRIPTION_TIER = "subscription_tier"
        private const val KEY_EFFECTIVE_PREMIUM = "effective_premium"
        private const val KEY_PREMIUM_SOURCE = "premium_source"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_MAP_LIGHT_STYLE = "map_light_style"
        private const val KEY_MAP_DARK_STYLE = "map_dark_style"
        // PMTiles style keys
        private const val KEY_MAP_PMTILES_LIGHT = "map_pmtiles_light"
        private const val KEY_MAP_PMTILES_DARK = "map_pmtiles_dark"
        private const val KEY_MAP_PMTILES_TILES_URL = "map_pmtiles_tiles_url"
        private const val KEY_MAP_PMTILES_TILE_TEMPLATE = "map_pmtiles_tile_template"

        /** Distance unit preference — see [getDistanceUnit]. */
        const val UNIT_KM = "km"
        const val UNIT_MI = "mi"

        /**
         * Regions that use miles for road distances. Everywhere else gets km,
         * which is the right default far more often than a hardcoded one.
         */
        private val MILE_COUNTRIES = setOf("US", "GB", "LR", "MM")

        // Theme modes
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // Default PMTiles configuration (Pingolin's self-hosted tiles)
        /**
         * The raw archive. NOT read by the map — the tile route serves tiles, see
         * [DEFAULT_PMTILES_TILE_TEMPLATE]. Kept because it is a valid URL on the
         * asset host, which is where MapPrewarmer derives the sprite base from, and
         * because the map can fall back to it if the tile route is ever abandoned.
         */
        const val DEFAULT_PMTILES_TILES_URL = "https://pmtiles.pingolin.com/planet.pmtiles"

        /**
         * Where the map actually reads tiles.
         *
         * The archive is 134 GB and the CDN will not cache an object that size, so
         * every range request for it comes back BYPASS: every directory walk and
         * every tile would be an origin round trip, from every device, shared with
         * nobody — about 125 s to first render on a cold cache, against 2.5 s
         * through this route.
         *
         * A separate key from the archive URL on purpose. The two are not
         * interchangeable — MapPrewarmer derives the sprite base by chopping the
         * last path segment off the archive URL, and doing that to a tile template
         * yields ".../tiles/v4/{z}/{x}" and a set of broken sprite requests.
         */
        const val DEFAULT_PMTILES_TILE_TEMPLATE =
            "https://pmtiles.pingolin.com/tiles/v4/{z}/{x}/{y}.mvt"

        /** Zoom range of the archive behind the tile route, from its own header.
         *  Mandatory once a source uses `tiles` instead of `url`: with `url`
         *  MapLibre reads them from the archive, with `tiles` nothing tells it. */
        const val PMTILES_MIN_ZOOM = 0
        const val PMTILES_MAX_ZOOM = 15
        const val DEFAULT_PMTILES_LIGHT_STYLE = "https://pmtiles.pingolin.com/styles/light.json"
        const val DEFAULT_PMTILES_DARK_STYLE = "https://pmtiles.pingolin.com/styles/dark.json"

        // Default battery saver interval in minutes
        const val DEFAULT_BATTERY_SAVER_INTERVAL = 15

        // Default tracking interval in seconds. Keep this tight: at a 15-minute default,
        // stationary devices look frozen on circle peers' maps. The distance toggle covers
        // the "user is moving" case, but a plugged-in stationary device still needs a
        // "still alive" heartbeat to feel live. 60s is the compromise — roughly 60 sends/hr
        // at idle, manageable on battery and in line with comparable apps, while the OS
        // location callback rate caps the practical maximum around 2/min anyway. Users who
        // want a slower beat pick 5m/10m/15m from the chip group in settings.
        const val DEFAULT_TRACKING_INTERVAL = 60

        // Time-based tracking (send at regular interval)
        private const val KEY_TIME_BASED_TRACKING_ENABLED = "time_based_tracking_enabled"

        // Distance-based tracking settings (legacy key kept for migration)
        private const val KEY_DISTANCE_TRACKING_ENABLED = "distance_tracking_enabled"
        private const val KEY_DISTANCE_THRESHOLD = "distance_threshold"
        const val DEFAULT_DISTANCE_THRESHOLD = 100 // meters

        // Send on distance (send when moved beyond threshold)
        private const val KEY_ALSO_SEND_ON_DISTANCE = "also_send_on_distance"
        private const val KEY_ALSO_SEND_DISTANCE_THRESHOLD = "also_send_distance_threshold"

        // Migration flag for old "Only Send When Moving" → new two-toggle model
        private const val KEY_TRACKING_MODEL_MIGRATED = "tracking_model_migrated"

        // The removed device-wide Pause Tracking switch: read only to move it (PauseTrackingMigration)
        private const val KEY_TRACKING_PAUSED = "tracking_paused"
        // Whose that switch is (an account id); kept across sign-out, like the switch itself
        private const val KEY_TRACKING_PAUSED_ACCOUNT = "tracking_paused_account"
        // Set when PauseTrackingMigration has moved it: the one note that says so is still to show
        private const val KEY_PAUSE_TRACKING_MOVED_NOTE = "pause_tracking_moved_note"

        // Stationary throttling
        private const val KEY_STATIONARY_THROTTLING_ENABLED = "stationary_throttling_enabled"

        // Default circle to show on map launch
        private const val KEY_DEFAULT_CIRCLE_ID = "default_circle_id"
        private const val KEY_DEFAULT_CIRCLE_SETTLED = "default_circle_settled"

        // Screenshot protection (FLAG_SECURE)
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"

        // Tracking enabled state (user's explicit on/off choice)
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"

        // Tracking health (see TrackingHealth / TrackingJournal)
        private const val KEY_LAST_FIX_AT = "tracking_last_fix_at"
        private const val KEY_RESTART_FAILURES = "tracking_restart_failures"
        private const val KEY_RESTART_PENDING = "tracking_restart_pending"
        private const val KEY_TRACKING_WANTED_AT_SIGNOUT = "tracking_wanted_at_signout"

        // Motion detection and battery optimization
        private const val KEY_LAST_MOTION_STATE = "last_motion_state"
        private const val KEY_PUSH_UNAVAILABLE_SHOWN = "push_unavailable_shown"
        private const val KEY_BATTERY_OPT_SHOWN = "battery_opt_shown"

        // App version tracking (for detecting updates)
        private const val KEY_LAST_APP_VERSION_CODE = "last_app_version_code"
        private const val KEY_GUARDIAN_MANAGED = "guardian_managed"
        private const val KEY_REGISTRATION_PENDING = "registration_pending"
        private const val KEY_REGISTRATION_REJECTED = "registration_rejected"
        private const val KEY_REGISTRATION_REVIEW_READ = "registration_review_read"
        private const val KEY_GUARDIAN_HANDOVER_DATE = "guardian_handover_date"

        // Language/Time/Date format defaults
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_TIME_FORMAT = "12h"
        const val DEFAULT_DATE_FORMAT = "mdy"

        // Environment URLs are configured via build flavors (see BuildConfig); these
        // constants are the fallback for when those fields are unavailable.
        private const val AUTH_DEV = "https://api-auth.pingolindev.com/"
        private const val AUTH_PROD = "https://api-auth.pingolin.com/"

        private const val API_DEV = "https://api-track.pingolindev.com/"
        private const val API_PROD = "https://api-track.pingolin.com/"

        private const val INGEST_DEV = "https://ingest-track.pingolindev.com"
        private const val INGEST_PROD = "https://ingest-track.pingolin.com"

        private const val PUSH_DEV = "https://push-track.pingolindev.com"
        private const val PUSH_PROD = "https://push-track.pingolin.com"
    }

    /**
     * The encrypted SharedPreferences instance, created lazily.
     * On first access, migrates any legacy unencrypted data.
     */
    protected open val prefs: SharedPreferences by lazy {
        val encryptedPrefs = createEncryptedPrefs()
        migrateFromLegacyPrefs(encryptedPrefs)
        encryptedPrefs
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        try {
            val key = masterKey?.get() ?: SecurePrefs.masterKey(context)
            return SecurePrefs.open(context, ENCRYPTED_PREFS_NAME, key)
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to create EncryptedSharedPreferences", e)
            throw SecurityException(
                "Cannot initialize secure session storage. " +
                "Please ensure your device's security features are enabled and try again.",
                e
            )
        }
    }

    /**
     * Copies the legacy unencrypted SharedPreferences file into the
     * EncryptedSharedPreferences store, type by type.
     *
     * One-time: a migration flag in the encrypted store stops it re-running. The legacy
     * file is NOT deleted afterwards — see the comment at the end of this function.
     */
    private fun migrateFromLegacyPrefs(encryptedPrefs: SharedPreferences) {
        if (encryptedPrefs.getBoolean(KEY_MIGRATION_DONE, false)) {
            return
        }

        try {
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val legacyAll = legacyPrefs.all

            if (legacyAll.isNullOrEmpty()) {
                encryptedPrefs.edit().putBoolean(KEY_MIGRATION_DONE, true).commit()
                return
            }

            Log.i(TAG, "Migrating ${legacyAll.size} entries from legacy SharedPreferences to EncryptedSharedPreferences")

            val editor = encryptedPrefs.edit()
            for ((key, value) in legacyAll) {
                if (key == KEY_MIGRATION_DONE) continue
                // Reserved keys belonging to androidx.security.crypto must be skipped.
                // The legacy store can contain them after a library upgrade or an Auto
                // Backup restore, and writing one throws SecurityException — which without
                // this guard aborts the whole migration and leaves the user with no session.
                if (key.startsWith("__androidx_security_crypto")) {
                    Log.d(TAG, "Skipping reserved key during migration: $key")
                    continue
                }
                // Per-key try/catch so one unexpected entry can't strand the
                // rest. Tokens are the most important thing to carry forward.
                try {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                        else -> Log.w(TAG, "Skipping unsupported type for key '$key': ${value?.javaClass?.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate key '$key' (${value?.javaClass?.simpleName}): ${e.message}")
                }
            }
            editor.putBoolean(KEY_MIGRATION_DONE, true)
            editor.commit()

            // Do NOT clear the legacy file. That same name ("tracks_secure_prefs") is also
            // where KeyStorageCallbackImpl persists the SDK's crypto_private_key /
            // crypto_circle_keys_json entries, so wiping it here destroys the user's private
            // key on upgrade and breaks every circle-key import, device registration and
            // decryption until they sign in again. AppModule points the SDK callback at the
            // new encrypted file; the legacy file is left alone so unrelated state survives.
            Log.i(TAG, "Legacy SharedPreferences migration complete")
        } catch (e: Exception) {
            // If migration fails, log but don't crash -- the app can still function
            // with a fresh encrypted store. Tokens will need to be re-acquired via login.
            Log.e(TAG, "Failed to migrate legacy SharedPreferences, continuing with empty encrypted store", e)
            encryptedPrefs.edit().putBoolean(KEY_MIGRATION_DONE, true).commit()
        }
    }

    enum class Environment { DEV, PROD }

    /**
     * Get the build-time configured environment.
     * This is set by Gradle product flavors (dev, prod).
     *
     * An unrecognised name — including "STAGING" left in prefs by an
     * earlier version — falls back to DEV rather than throwing.
     */
    fun getBuildEnvironment(): Environment {
        return try {
            Environment.valueOf(com.pingolin.track.BuildConfig.ENVIRONMENT)
        } catch (e: Exception) {
            Environment.DEV
        }
    }

    /**
     * This account still waits for an admin's approval (the assisted lane), as /auth/me last
     * said: Track's start shows the under-review screen instead of an app that can't work.
     */
    fun isRegistrationPending(): Boolean = prefs.getBoolean(KEY_REGISTRATION_PENDING, false)

    /**
     * An admin turned this account's request down, as /auth/me last said (its `registration_status`
     * "rejected"): Track's start shows the not-approved screen. Like a waiting account's, its token
     * carries no Track audience, so nothing of Track's may run for it.
     */
    fun isRegistrationRejected(): Boolean = prefs.getBoolean(KEY_REGISTRATION_REJECTED, false)

    /** Not approved by an admin: still waiting for one, or turned down ([isRegistrationRejected]). Nothing of Track's runs. */
    fun isRegistrationUnapproved(): Boolean = isRegistrationPending() || isRegistrationRejected()

    /**
     * Keeps what /auth/me last said of this account's review: still waiting for an admin
     * ([pending]), or turned down ([rejected]). The one place either is written, so it is where an
     * account held back stops: nothing of Track's runs for it, and an earlier version, which
     * couldn't know, may have started it all (on an update, BootReceiver finds neither flag
     * yet and restores sharing). That stop is a sign-out's ([TrackingStop.all]), and it wipes
     * nothing: the session stays.
     */
    fun keepRegistrationReview(pending: Boolean, rejected: Boolean) {
        prefs.edit()
            .putBoolean(KEY_REGISTRATION_PENDING, pending)
            .putBoolean(KEY_REGISTRATION_REJECTED, rejected)
            .putBoolean(KEY_REGISTRATION_REVIEW_READ, true)
            .apply()
        if (pending || rejected) TrackingStop.all(context)
    }

    /**
     * Whether this install has read the account's review at all ([keepRegistrationReview]). An
     * earlier version never did: until it is read once, an account an admin hasn't approved isn't
     * known as one.
     */
    fun isRegistrationReviewRead(): Boolean = prefs.getBoolean(KEY_REGISTRATION_REVIEW_READ, false)

    /**
     * Whether a guardian set this account up and holds its password.
     *
     * Kept here rather than on UserEntity so reading it costs no Room
     * migration: it is one bit, it changes only when the server says so, and
     * the map needs it on every frame it draws.
     *
     * Defaults FALSE, which is the safe direction — a missing value shows no
     * disclosure rather than accusing a guardian who is not there.
     */
    fun isGuardianManaged(): Boolean = prefs.getBoolean(KEY_GUARDIAN_MANAGED, false)

    fun setGuardianManaged(value: Boolean) {
        prefs.edit().putBoolean(KEY_GUARDIAN_MANAGED, value).apply()
        _guardianDisclosure.value = GuardianDisclosure(value, getGuardianHandoverDate())
    }

    /** The guardian's named handover date as "YYYY-MM-DD", or null. */
    fun getGuardianHandoverDate(): String? = prefs.getString(KEY_GUARDIAN_HANDOVER_DATE, null)

    fun setGuardianHandoverDate(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_GUARDIAN_HANDOVER_DATE) else putString(KEY_GUARDIAN_HANDOVER_DATE, value)
        }.apply()
        _guardianDisclosure.value = GuardianDisclosure(isGuardianManaged(), value)
    }

    /** What the map's guardian line says, as /auth/me last told this account. */
    data class GuardianDisclosure(val managed: Boolean, val handoverDate: String?)

    // The same two values as a flow, for the map's guardian line. /auth/me answers at app start
    // (validateSession), and its answer can land after the map's first layout and its resume: read
    // only at those, the line wouldn't show in a guardian-managed account's first session after
    // sign-in until the map resumed again. The map binds the line on every change. Lazy for the
    // reason _effectivePremium is.
    private val _guardianDisclosure by lazy { MutableStateFlow(GuardianDisclosure(isGuardianManaged(), getGuardianHandoverDate())) }
    val guardianDisclosure: StateFlow<GuardianDisclosure> get() = _guardianDisclosure.asStateFlow()

    /**
     * Whether the day the guardian named has arrived.
     *
     * String comparison on ISO dates, which sorts correctly and cannot be moved
     * by a timezone. Null or malformed reads as NOT due, erring toward saying
     * nothing rather than announcing a handover on a date we cannot
     * substantiate.
     */
    fun isGuardianHandoverDue(): Boolean {
        val named = getGuardianHandoverDate() ?: return false
        if (named.length < 10) return false
        val today = java.time.LocalDate.now().toString()
        return named.substring(0, 10) <= today
    }

    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + (expiresIn * 1000))
            .apply()
    }

    open fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun saveAccessToken(accessToken: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken).apply()
        // A stored token means there is a session again, so the once-per-session
        // sign-out guard re-arms. Without this the FIRST forced sign-out would
        // be the only one this install ever performs, and a genuine revocation
        // after a later sign-in would be silently swallowed.
        forceLogoutDone.set(false)
    }

    open fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    /**
     * Persist a new refresh token in isolation. Used by the /auth/refresh
     * response handler: the server rotates the refresh token on every
     * call, so the client MUST overwrite the previous value the instant
     * it receives a response.
     *
     * @return true only if the value is readable back afterwards.
     *
     * Verified rather than assumed. These live in EncryptedSharedPreferences
     * behind the Android keystore, and the keystore can be unavailable
     * (`keystore2 … Required super decryption key is not in memory …
     * Error::Rc(r#LOCKED)`). A rotated refresh token that completes on the wire
     * and never reaches disk leaves the phone believing it holds a token it
     * does not. Unchecked, that would be indistinguishable from success, so the
     * caller is told.
     *
     * commit(), not apply(): the point is to know the outcome.
     */
    fun saveRefreshToken(refreshToken: String): Boolean {
        return try {
            val wrote = prefs.edit().putString(KEY_REFRESH_TOKEN, refreshToken).commit()
            val readBack = prefs.getString(KEY_REFRESH_TOKEN, null)
            val ok = wrote && readBack == refreshToken
            if (!ok) Log.e(TAG, "saveRefreshToken: persist FAILED (wrote=$wrote, readBack matches=${readBack == refreshToken})")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "saveRefreshToken: persist threw: ${e.javaClass.simpleName}", e)
            false
        }
    }

    /**
     * Persist a refreshed access/refresh pair in ONE synchronous write, and read BOTH back.
     *
     * Background work refreshes like anything else. With the auth server's commit-on-first-use
     * refresh (a rotation commits only when the NEW refresh token is first presented, and the
     * previous one keeps working at any age until then), a refresh killed before this write
     * leaves the phone with the previous pair, which still works, and one killed after it
     * leaves the new pair. What must never happen is that the phone believes it holds a pair
     * it does not, so neither token goes through an unchecked apply(), and both are read back.
     *
     * @return true only if both values read back.
     */
    fun saveTokenPair(accessToken: String, refreshToken: String): Boolean {
        return try {
            val wrote = prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .commit()
            val ok = wrote &&
                prefs.getString(KEY_ACCESS_TOKEN, null) == accessToken &&
                prefs.getString(KEY_REFRESH_TOKEN, null) == refreshToken
            if (!ok) Log.e(TAG, "saveTokenPair: persist FAILED (wrote=$wrote)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "saveTokenPair: persist threw: ${e.javaClass.simpleName}", e)
            false
        } finally {
            // As saveAccessToken: a stored token means there is a session again.
            forceLogoutDone.set(false)
        }
    }

    fun isTokenExpired(): Boolean {
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        return System.currentTimeMillis() >= expiry - 60000 // 1 minute buffer
    }

    fun saveUser(userId: String, email: String, displayName: String?, publicKey: String) {
        prefs.edit()
            .putString(KEY_USER_ID, AccountIds.canonical(userId) ?: userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_PUBLIC_KEY, publicKey)
            .apply()
    }

    /**
     * This account's id, canonical ([AccountIds]): one saved before is written back canonical at its
     * first read. A saved id that isn't a UUID is no account (null), and the launch ends the session
     * ([savedAccountIdInvalid]).
     */
    open fun getUserId(): String? {
        val saved = prefs.getString(KEY_USER_ID, null) ?: return null
        val canonical = AccountIds.canonical(saved) ?: return null
        if (canonical != saved) runCatching { prefs.edit().putString(KEY_USER_ID, canonical).apply() }
        return canonical
    }

    /** A saved account id that isn't a UUID: MainActivity's launch ends the session. */
    fun savedAccountIdInvalid(): Boolean = prefs.getString(KEY_USER_ID, null)?.let { AccountIds.canonical(it) == null } ?: false

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    /** This account's address as the server has it now ([AuthRepository.validateSession]): it can change elsewhere. */
    fun setEmail(email: String) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    fun getDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    fun getSubscriptionTier(): String = prefs.getString(KEY_SUBSCRIPTION_TIER, "Free") ?: "Free"

    fun setSubscriptionTier(tier: String) {
        prefs.edit().putString(KEY_SUBSCRIPTION_TIER, tier).apply()
    }

    // Effective premium entitlement (own subscription OR circle-derived) and its
    // source ("own"|"circle"|"none"), exposed as StateFlows so the Settings tier
    // badge and the server-side geocoder toggle gate update reactively across
    // both settings screens. Seeded from prefs so a returning premium user
    // doesn't briefly show as Free before the fetch lands.
    //
    // MUST be lazy: `prefs` is an open property (TestableSessionManager
    // overrides it with a fake), and an eager initializer here runs during
    // BASE-class construction — before the subclass override's backing
    // field exists — so the overridden getter returns null and the read
    // NPEs. Lazy defers the first prefs read to first access, which is
    // always post-construction.
    private val _effectivePremium by lazy { MutableStateFlow(prefs.getBoolean(KEY_EFFECTIVE_PREMIUM, false)) }
    val effectivePremium: StateFlow<Boolean> get() = _effectivePremium.asStateFlow()
    private val _premiumSource by lazy { MutableStateFlow(prefs.getString(KEY_PREMIUM_SOURCE, "none") ?: "none") }
    val premiumSource: StateFlow<String> get() = _premiumSource.asStateFlow()

    fun setEffectivePremium(value: Boolean) {
        prefs.edit().putBoolean(KEY_EFFECTIVE_PREMIUM, value).apply()
        _effectivePremium.value = value
    }

    fun setPremiumSource(value: String) {
        prefs.edit().putString(KEY_PREMIUM_SOURCE, value).apply()
        _premiumSource.value = value
    }

    fun getPublicKey(): String? = prefs.getString(KEY_PUBLIC_KEY, null)

    fun saveEncryptedPrivateKey(encryptedKey: String) {
        prefs.edit().putString(KEY_ENCRYPTED_PRIVATE_KEY, encryptedKey).apply()
    }

    fun getEncryptedPrivateKey(): String? = prefs.getString(KEY_ENCRYPTED_PRIVATE_KEY, null)

    fun saveDeviceId(deviceId: String) {
        // Use commit() for synchronous write to ensure persistence
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).commit()
    }

    /**
     * The device ID, generating and persisting a random UUID on first call.
     *
     * Random rather than derived from any device or signing identity: it stays stable
     * across app updates and across debug/release builds, and lets one user hold several
     * devices without collision. Reinstalling produces a new ID, which is intended —
     * the backend treats it as a new device.
     */
    open fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId != null) {
            // Reject a deterministic UUID left by an older scheme. In
            // xxxxxxxx-xxxx-Vxxx-xxxx-xxxxxxxxxxxx the V position holds the version:
            // '4' is random, '3'/'5' are name-derived and must be replaced.
            if (deviceId.length >= 15) {
                val versionChar = deviceId[14]
                if (versionChar != '4') {
                    android.util.Log.w("SessionManager", "Clearing old deterministic device ID (version $versionChar)")
                    prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_DEVICE_REGISTERED).remove(KEY_REGISTERED_DEVICE_ID).commit()
                    deviceId = null
                }
            }
        }

        if (deviceId != null) {
            return deviceId
        }

        deviceId = UUID.randomUUID().toString()
        if (com.pingolin.track.BuildConfig.DEBUG) {
            android.util.Log.i("SessionManager", "Generated new random device ID: $deviceId")
        }

        // A brand-new device ID invalidates any registration recorded against the old one.
        prefs.edit()
            .remove(KEY_DEVICE_REGISTERED)
            .remove(KEY_REGISTERED_DEVICE_ID)
            .commit()

        saveDeviceId(deviceId)
        return deviceId
    }

    /** Unlike [getDeviceId], never generates one — for callers that only want to know. */
    fun getDeviceIdOrNull(): String? = prefs.getString(KEY_DEVICE_ID, null)

    /**
     * Forces a fresh device ID on the next [getDeviceId]. Called when the server rejects
     * the current one. commit() rather than apply() so the clear survives an immediate kill.
     */
    fun clearDeviceId() {
        android.util.Log.w("SessionManager", "Clearing device ID due to server rejection")
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_REGISTERED)
            .remove(KEY_REGISTERED_DEVICE_ID)
            .commit()
    }

    /**
     * Marks the device registered with the backend, recording WHICH device ID was
     * registered so [isDeviceRegistered] can notice the ID later changing underneath it.
     */
    fun setDeviceRegistered(registered: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_DEVICE_REGISTERED, registered)
            if (registered) {
                putString(KEY_REGISTERED_DEVICE_ID, getDeviceId())
            } else {
                remove(KEY_REGISTERED_DEVICE_ID)
            }
        }.commit()
    }

    /**
     * Check if the device has been registered with the backend.
     * Returns false if:
     * - The registration flag is not set
     * - The registered device ID is missing or doesn't match the current device ID
     */
    open fun isDeviceRegistered(): Boolean {
        val isRegisteredFlag = prefs.getBoolean(KEY_DEVICE_REGISTERED, false)
        val registeredDeviceId = prefs.getString(KEY_REGISTERED_DEVICE_ID, null)
        val currentDeviceId = getDeviceId()

        if (com.pingolin.track.BuildConfig.DEBUG) {
            android.util.Log.d("SessionManager", "isDeviceRegistered: flag=$isRegisteredFlag, registeredId=$registeredDeviceId, currentId=$currentDeviceId")
        }

        if (!isRegisteredFlag) {
            return false
        }

        if (registeredDeviceId == null) {
            android.util.Log.w("SessionManager", "Registration flag is true but no registered device ID - clearing")
            setDeviceRegistered(false)
            return false
        }

        if (registeredDeviceId != currentDeviceId) {
            if (com.pingolin.track.BuildConfig.DEBUG) {
                android.util.Log.w("SessionManager", "Device ID changed from $registeredDeviceId to $currentDeviceId - clearing registration")
            }
            setDeviceRegistered(false)
            return false
        }

        return true
    }

    // ===== Push Notification Management =====

    /**
     * Emits whenever push settings (type/token/endpoint) change, so observers
     * (e.g. SettingsFragment) can refresh their status UI. Notably this fires when
     * the UnifiedPush endpoint arrives asynchronously in
     * [com.pingolin.track.service.TrackUnifiedPushReceiver.onNewEndpoint] after the
     * user picks a distributor — the moment the transient "registration pending"
     * state becomes a live UnifiedPush registration.
     */
    private val _pushSettingsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val pushSettingsChanged: kotlinx.coroutines.flow.SharedFlow<Unit> = _pushSettingsChanged

    /**
     * Saves the push registration for either transport: FCM carries a [token] and no
     * [endpoint], UnifiedPush carries an [endpoint] and no token. [type] must be
     * [PUSH_TYPE_FCM] or [PUSH_TYPE_UNIFIED_PUSH]. Always marks the settings unsynced.
     */
    fun savePushSettings(token: String?, type: String, endpoint: String? = null) {
        prefs.edit()
            .putString(KEY_PUSH_TOKEN, token)
            .putString(KEY_PUSH_TYPE, type)
            .putString(KEY_PUSH_ENDPOINT, endpoint)
            .putBoolean(KEY_PUSH_TOKEN_SYNCED, false)
            .apply()
        _pushSettingsChanged.tryEmit(Unit)
    }

    /** Called when Firebase hands us a new token. */
    fun savePushToken(token: String) {
        savePushSettings(token, PUSH_TYPE_FCM, null)
    }

    /**
     * Restore a registration this install lost but the server still holds.
     *
     * Unlike [savePushSettings] this marks the settings SYNCED: the values came
     * from the server, so pushing them back would be a write of data the server
     * already has. It also leaves the token alone — an FCM token is never
     * returned by the server (it is encrypted at rest) and the client re-obtains
     * its own from Firebase.
     */
    fun restorePushRegistration(type: String, endpoint: String?) {
        prefs.edit()
            .putString(KEY_PUSH_TYPE, type)
            .apply { if (endpoint != null) putString(KEY_PUSH_ENDPOINT, endpoint) }
            .putBoolean(KEY_PUSH_TOKEN_SYNCED, true)
            .apply()
        _pushSettingsChanged.tryEmit(Unit)
    }

    fun getPushToken(): String? = prefs.getString(KEY_PUSH_TOKEN, null)

    /** [PUSH_TYPE_FCM], [PUSH_TYPE_UNIFIED_PUSH], or null if push was never set up. */
    fun getPushType(): String? = prefs.getString(KEY_PUSH_TYPE, null)

    fun getPushEndpoint(): String? = prefs.getString(KEY_PUSH_ENDPOINT, null)

    /**
     * Has the user already been shown why Track wants their location, and said
     * "not now"?
     *
     * Signing in leads to the OS location prompt, and then — on API 29+, where
     * background access is granted only in Settings — to the Android permission
     * settings page, so a rationale screen explains why first. It helps only if
     * declining it STICKS: an explanation that reappears on every trip to the
     * map is worse than none.
     */
    fun hasDeclinedLocationRationale(): Boolean =
        prefs.getBoolean(KEY_LOCATION_RATIONALE_DECLINED, false)

    fun setDeclinedLocationRationale(declined: Boolean) {
        prefs.edit().putBoolean(KEY_LOCATION_RATIONALE_DECLINED, declined).apply()
    }

    /**
     * Whether Android's own location question has been asked on this install. With
     * shouldShowRequestPermissionRationale false, it tells "never asked" (Android will ask) from
     * "refused for good" (Android won't, and only its settings can turn location on). It belongs to
     * the device's permission, not to an account, so a sign-out keeps it.
     */
    fun hasAskedForLocation(): Boolean = prefs.getBoolean(KEY_LOCATION_ASKED, false)

    fun setAskedForLocation() {
        prefs.edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
    }

    fun isPushTokenSynced(): Boolean = prefs.getBoolean(KEY_PUSH_TOKEN_SYNCED, false)

    /** Set after a successful device registration or token update. */
    fun setPushTokenSynced(synced: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_TOKEN_SYNCED, synced).apply()
    }

    /** Called on logout. */
    fun clearPushToken() {
        prefs.edit()
            .remove(KEY_PUSH_TOKEN)
            .remove(KEY_PUSH_TYPE)
            .remove(KEY_PUSH_ENDPOINT)
            .remove(KEY_PUSH_TOKEN_SYNCED)
            .apply()
    }

    // There is no stored environment: `getBuildEnvironment()` above is the
    // only answer, and it cannot drift from the URLs because both come from
    // BuildConfig.

    /**
     * All the base-URL getters below read build-time configuration set by the product
     * flavor. The `catch` arms are unreachable — a BuildConfig String is a compile-time
     * constant and reading one cannot throw — and they too resolve the environment from
     * BuildConfig, so no code path can pair one flavor's URLs with another's name.
     */
    fun getAuthBaseUrl(): String {
        return try {
            com.pingolin.track.BuildConfig.AUTH_BASE_URL
        } catch (e: Exception) {
            when (getBuildEnvironment()) {
                Environment.DEV -> AUTH_DEV
                Environment.PROD -> AUTH_PROD
            }
        }
    }

    fun getApiBaseUrl(): String {
        return try {
            com.pingolin.track.BuildConfig.API_BASE_URL
        } catch (e: Exception) {
            when (getBuildEnvironment()) {
                Environment.DEV -> API_DEV
                Environment.PROD -> API_PROD
            }
        }
    }

    /**
     * Base URL for PUBLIC endpoints (e.g. /api/config) — these must go through the web
     * proxy, not the API host. Outside production the API host is behind mTLS and rejects
     * a client that has no certificate; in production the two resolve to the same URL, so
     * routing everything through here is always safe.
     */
    fun getWebBaseUrl(): String {
        return try {
            com.pingolin.track.BuildConfig.WEB_BASE_URL
        } catch (e: Exception) {
            when (getBuildEnvironment()) {
                Environment.DEV -> "https://track.pingolindev.com/"
                Environment.PROD -> API_PROD
            }
        }
    }

    /** The ingest service, used for gRPC location streaming. */
    fun getIngestUrl(): String {
        return try {
            com.pingolin.track.BuildConfig.INGEST_BASE_URL
        } catch (e: Exception) {
            when (getBuildEnvironment()) {
                Environment.DEV -> INGEST_DEV
                Environment.PROD -> INGEST_PROD
            }
        }
    }

    fun getPushServerUrl(): String? {
        return try {
            com.pingolin.track.BuildConfig.PUSH_BASE_URL
        } catch (e: Exception) {
            when (getBuildEnvironment()) {
                Environment.DEV -> PUSH_DEV
                Environment.PROD -> PUSH_PROD
            }
        }
    }

    // ===== Always-On Notifications =====

    fun setAlwaysOnNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_ON_NOTIFICATIONS, enabled).apply()
    }

    fun isAlwaysOnNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_ALWAYS_ON_NOTIFICATIONS, false)

    fun setNotificationDetailLevel(level: String) {
        prefs.edit().putString(KEY_NOTIFICATION_DETAIL, level).apply()
    }

    /** Defaults to full detail; unknown stored values also fall back to full. */
    open fun getNotificationDetailLevel(): String =
        when (val v = prefs.getString(KEY_NOTIFICATION_DETAIL, NOTIF_DETAIL_FULL)) {
            NOTIF_DETAIL_NAMES, NOTIF_DETAIL_GENERIC -> v
            else -> NOTIF_DETAIL_FULL
        }

    open fun isLoggedIn(): Boolean {
        return getAccessToken() != null && getUserId() != null
    }

    // ===== Force Logout Mechanism =====

    private val _forceLogout = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    /**
     * Flow that emits when the user should be force logged out.
     * Observe this in MainActivity to redirect to login.
     */
    val forceLogout: kotlinx.coroutines.flow.SharedFlow<Unit> = _forceLogout

    /**
     * Callback invoked during a forced sign-out to STOP the things that keep
     * running without a session — the location foreground service, the
     * background revival workers, posted notifications. Registered from the DI
     * graph because SessionManager owns none of them.
     *
     * It must NOT wipe keys, the database or cached files. A forced sign-out is
     * usually the server refusing one refresh, not the account going away, and
     * the account has to survive it so re-authenticating restores the device.
     * The wipe belongs to the two paths that genuinely end an account's life on
     * this device: AuthRepository.logout, and AuthRepository's account-switch
     * check when a DIFFERENT user signs in. See [clearCredentialsOnly].
     */
    private var forceLogoutStopCallback: (() -> Unit)? = null

    /**
     * Register the stop-only callback invoked by [triggerForceLogout].
     * Typically called once from the DI graph (e.g., Application).
     */
    fun setForceLogoutStopCallback(callback: () -> Unit) {
        forceLogoutStopCallback = callback
    }

    /**
     * Guards the teardown so a burst of concurrent 401s runs it ONCE per
     * session, not once per caller. Placed here, at the teardown, rather than
     * at any single call site, so a future caller inherits the guard. Reset
     * when a new session is established — without that a later, genuine
     * sign-out would be swallowed.
     */
    private val forceLogoutDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Sign out because the server refused the session: drop the credentials,
     * stop tracking and the background workers, notify observers, and tell the
     * user. Called when a token refresh comes back 401.
     *
     * Deliberately RECOVERABLE — the account's keys and data stay on the
     * device, so signing in again restores it rather than re-importing it.
     * [clearCredentialsOnly] carries the reasoning.
     */
    @JvmOverloads
    fun triggerForceLogout(
        reason: TrackingStopReason = TrackingStopReason.SESSION_REJECTED,
        detail: String = "",
    ) {
        // Once per session. Several callers can see a 401 at the same moment,
        // and running the teardown repeatedly means duplicate journal entries,
        // duplicate notifications and duplicate crypto wipes.
        if (!forceLogoutDone.compareAndSet(false, true)) {
            Log.d(TAG, "triggerForceLogout: already signed out for this session, ignoring")
            return
        }

        // Journal BEFORE clearing. This is the single most valuable
        // entry in the whole journal: after a forced sign-out,
        // BootReceiver and the watchdog both gate on isLoggedIn(),
        // so every automatic recovery route becomes a deliberate
        // no-op and tracking stays dead with nothing said. The
        // server's session row may still be live, so the detail
        // (which call site, what the server actually answered) is
        // the difference between diagnosing it and guessing.
        // clearSession() removes named keys, not the whole file,
        // so the journal survives it.
        val wantedTracking = isTrackingEnabled()
        runCatching {
            trackingJournal.record(
                event = TrackingEvent.SIGNED_OUT,
                reason = reason,
                lastFixAgeMs = lastLocationFixAt().let {
                    if (it <= 0L) TrackingHealth.NO_FIX
                    else (System.currentTimeMillis() - it).coerceAtLeast(0L)
                },
                detail = detail,
            )
        }

        // Persist the intent before the clear below destroys it:
        // without this the app cannot later tell "signed out while
        // sharing" (a state nothing repairs automatically) from
        // "signed out and not sharing" (fine). AuthRepository reads
        // it back on a same-user sign-in to switch tracking on
        // again, so the user does not have to notice it is off.
        setTrackingWantedAtSignOut(wantedTracking)

        // Credentials only — NOT clearSession(). See [clearCredentialsOnly].
        clearCredentialsOnly()
        forceLogoutStopCallback?.invoke()
        _forceLogout.tryEmit(Unit)

        // Tell the user. A silent sign-out means no tracking, no
        // warning, and no automatic path that will ever repair it —
        // the worst of the three states this device can be in.
        // Only raised when tracking was actually switched on, so a
        // signed-out install that was not sharing anyway stays
        // quiet.
        if (wantedTracking) {
            runCatching { TrackingHealthNotifier.showSignedOut(context) }
        }
    }

    /**
     * End the SESSION, keep the ACCOUNT.
     *
     * Removes the credentials and switches tracking off, and nothing else: the
     * user id, email, display name, key material, app lock, cached rows and
     * files all stay. [isLoggedIn] answers false afterwards because the access
     * token is gone, so every background revival path still stands down.
     *
     * Used by [triggerForceLogout], which fires when the server refuses ONE
     * refresh. That is not the same fact as "this account is finished on this
     * device": a rotation race — two refresh paths, the loser holding a
     * retired token — causes it while the server's session row is live the
     * whole time. Wiping keys and the database there would turn a recoverable
     * interruption into a re-import, with nothing to come back to but a cold
     * account.
     *
     * Retaining the user id is what makes recovery work, and it is also the
     * thing that would leak: with an id still stored, a DIFFERENT user signing
     * in on this device would land on the previous user's decrypted rows.
     * AuthRepository's account-switch check is what prevents that — it
     * compares the incoming id against the stored one and runs clearLocalData
     * BEFORE importing the new user's keys, on both the login and the register
     * path. That check is load-bearing for this method. Do not weaken either
     * without the other.
     */
    fun clearCredentialsOnly() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            // Tracking cannot upload without a session, so stop asking. The
            // wanted-state was saved to KEY_TRACKING_WANTED_AT_SIGNOUT first.
            .remove(KEY_TRACKING_ENABLED)
            .apply()
    }

    fun clearSession() {
        // Note: We intentionally do NOT clear KEY_DEVICE_ID here.
        // The device ID represents the physical device, not the user session.
        // It persists across logins so the backend can track the same device.
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_USER_ID)
            .remove(KEY_REGISTRATION_PENDING)
            .remove(KEY_REGISTRATION_REJECTED)
            .remove(KEY_REGISTRATION_REVIEW_READ)
            .remove(KEY_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_ENCRYPTED_PRIVATE_KEY)
            .remove(KEY_DEVICE_REGISTERED)  // Re-register device with new user
            .remove(KEY_REGISTERED_DEVICE_ID)
            .remove(KEY_PUSH_TOKEN)
            .remove(KEY_PUSH_TYPE)
            .remove(KEY_PUSH_ENDPOINT)
            .remove(KEY_PUSH_TOKEN_SYNCED)
            .remove(KEY_TRACKING_ENABLED)  // Don't auto-start tracking for a different user
            // SECURITY: app-lock + premium/circle belong to the PRIOR user and must
            // not carry into the next account. Reset app lock fully (method + hash +
            // biometric flag): clearing only the PIN hash would leave method=PIN with
            // a null hash, which the unlock check fails closed on — locking the next
            // user out. Subscription tier / effective-premium / premium-source and
            // the default circle are per-user seeds; the next user's are fetched
            // fresh after login.
            .remove(KEY_APP_LOCK_METHOD)
            .remove(KEY_APP_LOCK_PIN_HASH)
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_SUBSCRIPTION_TIER)
            .remove(KEY_EFFECTIVE_PREMIUM)
            .remove(KEY_PREMIUM_SOURCE)
            .remove(KEY_DEFAULT_CIRCLE_ID)
            .remove(KEY_DEFAULT_CIRCLE_SETTLED)
            // Whether a guardian holds this account's password, and the day it's handed over, are
            // this account's: kept, the next account's map would say "visible to guardian" until its
            // own /auth/me answered. Gone, it says nothing until then: the safe direction
            // (isGuardianManaged).
            .remove(KEY_GUARDIAN_MANAGED)
            .remove(KEY_GUARDIAN_HANDOVER_DATE)
            // A position belongs to the user who was signed in when it was taken, and
            // it is the only raw coordinate this store holds. The next user starts with
            // no baseline, which makes their first fix a history send — the correct
            // anchor, and the same thing a fresh install does.
            .remove(KEY_LAST_HISTORY_SENT_LAT)
            .remove(KEY_LAST_HISTORY_SENT_LNG)
            .remove(KEY_LAST_HISTORY_SENT_AT)
            .apply()
        // Re-sync the premium StateFlows to their cleared defaults so any UI still
        // observing them doesn't keep showing the prior user's tier until the next
        // server fetch lands.
        _effectivePremium.value = false
        _premiumSource.value = "none"
        _guardianDisclosure.value = GuardianDisclosure(managed = false, handoverDate = null)
    }

    // ===== App Lock Settings =====

    /**
     * Set the app lock method.
     * @param method One of APP_LOCK_NONE, APP_LOCK_PIN, or APP_LOCK_BIOMETRICS
     */
    fun setAppLockMethod(method: String) {
        prefs.edit().putString(KEY_APP_LOCK_METHOD, method).apply()
        // Migrate from legacy biometric setting
        if (method == APP_LOCK_BIOMETRICS) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, true).apply()
        } else if (method == APP_LOCK_NONE) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, false).apply()
        }
    }

    /**
     * Get the current app lock method.
     * @return One of APP_LOCK_NONE, APP_LOCK_PIN, or APP_LOCK_BIOMETRICS
     */
    fun getAppLockMethod(): String {
        // Check new setting first
        val method = prefs.getString(KEY_APP_LOCK_METHOD, null)
        if (method != null) return method

        // Migrate from legacy biometric setting
        return if (prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)) {
            APP_LOCK_BIOMETRICS
        } else {
            APP_LOCK_NONE
        }
    }

    /**
     * Check if app lock is enabled (either PIN or biometrics).
     */
    fun isAppLockEnabled(): Boolean = getAppLockMethod() != APP_LOCK_NONE

    /**
     * Enable or disable biometric app lock (legacy method for backward compatibility).
     */
    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled) {
            setAppLockMethod(APP_LOCK_BIOMETRICS)
        } else {
            setAppLockMethod(APP_LOCK_NONE)
        }
    }

    /**
     * Check if biometric app lock is enabled (legacy method for backward compatibility).
     */
    fun isBiometricEnabled(): Boolean = getAppLockMethod() == APP_LOCK_BIOMETRICS

    /**
     * Set the auto-lock timeout in milliseconds.
     * @param timeoutMs One of the AUTO_LOCK_* constants
     */
    fun setAutoLockTimeout(timeoutMs: Long) {
        prefs.edit().putLong(KEY_AUTO_LOCK_TIMEOUT, timeoutMs).apply()
    }

    /**
     * Get the auto-lock timeout in milliseconds.
     */
    fun getAutoLockTimeout(): Long {
        return prefs.getLong(KEY_AUTO_LOCK_TIMEOUT, DEFAULT_AUTO_LOCK_TIMEOUT)
    }

    /**
     * Save the PIN hash for app lock.
     *
     * @param pinHash The PBKDF2 `salt$hash` string
     *   from [PinHasher.hashPin]
     */
    fun setAppLockPinHash(pinHash: String) {
        prefs.edit().putString(KEY_APP_LOCK_PIN_HASH, pinHash).apply()
    }

    /**
     * Get the stored PIN hash.
     */
    fun getAppLockPinHash(): String? = prefs.getString(KEY_APP_LOCK_PIN_HASH, null)

    /**
     * Clear the PIN hash (call when disabling PIN lock).
     */
    fun clearAppLockPinHash() {
        prefs.edit().remove(KEY_APP_LOCK_PIN_HASH).apply()
    }

    // ===== PIN Lockout (stored in EncryptedSharedPreferences) =====

    /**
     * Get the number of failed PIN attempts.
     */
    fun getPinFailedAttempts(): Int = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0)

    /**
     * Set the number of failed PIN attempts.
     */
    fun setPinFailedAttempts(count: Int) {
        prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, count).apply()
    }

    /**
     * Get the PIN lockout expiry timestamp.
     */
    fun getPinLockoutUntil(): Long = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)

    /**
     * Set the PIN lockout expiry timestamp.
     */
    fun setPinLockoutUntil(until: Long) {
        prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL, until).apply()
    }

    /**
     * Reset PIN lockout state (e.g., after successful PIN entry or lockout expiry).
     */
    fun resetPinLockout() {
        prefs.edit()
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    /**
     * Record when app went to background (for timeout calculation).
     */
    fun recordBackgroundTime() {
        prefs.edit().putLong(KEY_LAST_BACKGROUND_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Wall-clock timestamp (ms) of the most recent successful location send
     * to any target. Persisted here — not just held in-memory in
     * LocationTrackingService — so the heartbeat worker (which runs in a
     * separate WorkManager context) can read it and decide whether to fire
     * a staleness-guard send even when the foreground service reports
     * isRunning=true.
     *
     * 0 = never.
     */
    fun getLastSuccessfulSendAt(): Long = prefs.getLong(KEY_LAST_SUCCESSFUL_SEND_AT, 0L)

    fun setLastSuccessfulSendAt(ts: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_SEND_AT, ts).apply()
    }

    /**
     * The position of the last HISTORY send, and when it was made. Null until one
     * has happened on this install.
     *
     * The baseline for "has this device moved far enough to be worth a history row?".
     * It has to be persisted rather than held in memory because the two senders are
     * in different processes' lifetimes: LocationTrackingService keeps its own
     * in-memory copy for the hot path, but LocationHeartbeatWorker is constructed
     * fresh on every 15-minute tick and would otherwise have no idea where it last
     * reported from — and a sender with no baseline sends history every time, which
     * is the behaviour this exists to stop.
     *
     * Written ONLY by history sends. A liveness check-in must not advance it, or a
     * slowly-drifting device would creep an unbounded distance without ever crossing
     * the threshold.
     *
     * Stored as raw double bits: SharedPreferences has no putDouble, and a Float
     * rounds a latitude to roughly half a metre.
     */
    fun getLastHistorySentPosition(): SentPosition? {
        if (!prefs.contains(KEY_LAST_HISTORY_SENT_LAT)) return null
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_HISTORY_SENT_LAT, 0L))
        val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_HISTORY_SENT_LNG, 0L))
        if (lat.isNaN() || lng.isNaN()) return null
        return SentPosition(lat, lng, prefs.getLong(KEY_LAST_HISTORY_SENT_AT, 0L))
    }

    fun setLastHistorySentPosition(latitude: Double, longitude: Double, atMs: Long) {
        prefs.edit()
            .putLong(KEY_LAST_HISTORY_SENT_LAT, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_HISTORY_SENT_LNG, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(KEY_LAST_HISTORY_SENT_AT, atMs)
            .apply()
    }

    /** Where and when this device last sent a history row. See [getLastHistorySentPosition]. */
    data class SentPosition(val latitude: Double, val longitude: Double, val atMs: Long)

    /**
     * When this device last pushed its own self key over the server's copy.
     *
     * Only a rate limit. The heal is automatic (LocationRepository
     * .republishSelfKeyIfHeld), and a client that mints over a key it cannot
     * open can replace ours right back — two healers with no brake would trade
     * writes on every sync.
     *
     * 0 = never.
     */
    fun getLastSelfKeyRepublishAt(): Long = prefs.getLong(KEY_LAST_SELF_KEY_REPUBLISH_AT, 0L)

    fun setLastSelfKeyRepublishAt(ts: Long) {
        prefs.edit().putLong(KEY_LAST_SELF_KEY_REPUBLISH_AT, ts).apply()
    }

    /**
     * The personal copy this device last reported as unusable, as "{userId}:{digest}", so the
     * flag goes up once per distinct copy. Cleared when a copy opens here. Null when nothing
     * is outstanding.
     */
    fun getUnusableSelfKeyCopy(): String? = prefs.getString(KEY_UNUSABLE_SELF_KEY_COPY, null)

    fun setUnusableSelfKeyCopy(copy: String?) {
        prefs.edit().apply {
            if (copy == null) remove(KEY_UNUSABLE_SELF_KEY_COPY) else putString(KEY_UNUSABLE_SELF_KEY_COPY, copy)
        }.apply()
    }

    // ===== Opt-in server-side geocoder =====
    // Off by default; flipping it routes reverse-geocode through the server's
    // /api/v1/geocode/* proxy (premium feature: credits are issued against the
    // JWT and subscription, see GeocodeTokenManager). The on-device SQLite
    // geocoder remains the fallback if the toggle is off or the server returns
    // 4xx/5xx.

    fun getUseServerGeocoder(): Boolean = prefs.getBoolean(KEY_USE_SERVER_GEOCODER, false)

    fun setUseServerGeocoder(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_SERVER_GEOCODER, enabled).apply()
    }

    /**
     * Check if app lock authentication is required based on time in background.
     * Returns true if app lock is enabled AND app was in background longer than timeout.
     */
    fun requiresAppLockAuth(): Boolean {
        if (!isAppLockEnabled()) return false
        if (!isLoggedIn()) return false

        // SECURITY: If auth was canceled on a previous launch, always require re-auth.
        // This prevents bypass via: cancel → reopen within timeout window.
        if (prefs.getBoolean(KEY_FORCE_AUTH_NEXT_LAUNCH, false)) return true

        val timeout = getAutoLockTimeout()
        if (timeout == AUTO_LOCK_IMMEDIATELY) return true

        val lastBackground = prefs.getLong(KEY_LAST_BACKGROUND_TIME, 0)
        // SECURITY: no recorded background time means a fresh launch after a kill, and
        // must require auth. Treating it as "not yet timed out" hands an attacker a
        // bypass — finishAffinity() clears the record and the lock never engages.
        if (lastBackground == 0L) return true

        val elapsed = System.currentTimeMillis() - lastBackground
        return elapsed > timeout
    }

    /** Mark that the next app launch MUST require authentication (e.g., user canceled biometric). */
    fun setForceAuthOnNextLaunch(force: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_AUTH_NEXT_LAUNCH, force).apply()
    }

    /** Legacy narrowing of [requiresAppLockAuth] to the biometrics method only. */
    fun requiresBiometricAuth(): Boolean = requiresAppLockAuth() && getAppLockMethod() == APP_LOCK_BIOMETRICS

    /** Call after a successful auth. */
    fun clearBackgroundTime() {
        prefs.edit().remove(KEY_LAST_BACKGROUND_TIME).apply()
    }

    // ===== Screenshot Protection =====

    /**
     * Screenshot and task-switcher protection (FLAG_SECURE). Defaults to BLOCKED —
     * secure by default, so the user opts out rather than in.
     */
    fun setBlockScreenshots(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, blocked).apply()
    }

    fun isBlockScreenshotsEnabled(): Boolean {
        return prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, true)
    }

    // ===== Default Circle =====

    fun setDefaultCircleId(circleId: String?) {
        val edit = prefs.edit().putBoolean(KEY_DEFAULT_CIRCLE_SETTLED, true)
        if (circleId != null) {
            edit.putString(KEY_DEFAULT_CIRCLE_ID, circleId).apply()
        } else {
            edit.remove(KEY_DEFAULT_CIRCLE_ID).apply()
        }
    }

    fun getDefaultCircleId(): String? = prefs.getString(KEY_DEFAULT_CIRCLE_ID, null)

    /**
     * Whether this account's default circle on the map has been settled: set (by the person, or to the
     * first circle they joined) or switched off ("Default on map"). The first circle is made the
     * default only while nothing was ever settled, so a default switched off stays off.
     */
    fun isDefaultCircleSettled(): Boolean =
        prefs.getBoolean(KEY_DEFAULT_CIRCLE_SETTLED, false) || getDefaultCircleId() != null

    // ===== Theme Settings =====

    /** [mode] must be [THEME_SYSTEM], [THEME_LIGHT] or [THEME_DARK]. */
    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    /**
     * Preferred distance unit, [UNIT_KM] or [UNIT_MI].
     *
     * Unset defaults to the device region rather than to a fixed unit: showing
     * "454 km" to someone in Ohio is the kind of small wrongness that makes an
     * app feel foreign. The setting exists because the region is only a guess —
     * plenty of people want the other one.
     */
    open fun getDistanceUnit(): String =
        prefs.getString(KEY_DISTANCE_UNIT, null) ?: defaultDistanceUnit()

    fun setDistanceUnit(unit: String) {
        prefs.edit().putString(KEY_DISTANCE_UNIT, unit).apply()
    }

    /** True when distances should be rendered in miles. */
    open fun useMiles(): Boolean = getDistanceUnit() == UNIT_MI

    private fun defaultDistanceUnit(): String =
        if (java.util.Locale.getDefault().country.uppercase() in MILE_COUNTRIES) UNIT_MI else UNIT_KM

    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    // ===== Battery Saver Settings =====

    /** Battery saver mode batches location uploads instead of streaming them. */
    fun setBatterySaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_SAVER_ENABLED, enabled).apply()
    }

    fun isBatterySaverEnabled(): Boolean = prefs.getBoolean(KEY_BATTERY_SAVER_ENABLED, false)

    fun setBatterySaverInterval(minutes: Int) {
        prefs.edit().putInt(KEY_BATTERY_SAVER_INTERVAL, minutes).apply()
    }

    /** Battery-saver upload interval, in MINUTES. */
    fun getBatterySaverInterval(): Int {
        return prefs.getInt(KEY_BATTERY_SAVER_INTERVAL, DEFAULT_BATTERY_SAVER_INTERVAL)
    }

    fun setTrackingInterval(seconds: Int) {
        prefs.edit().putInt(KEY_TRACKING_INTERVAL, seconds).apply()
    }

    /** Tracking interval, in SECONDS. */
    fun getTrackingInterval(): Int {
        return prefs.getInt(KEY_TRACKING_INTERVAL, DEFAULT_TRACKING_INTERVAL)
    }

    fun getTrackingIntervalMs(): Long {
        return getTrackingInterval() * 1000L
    }

    // ===== Language & Format Preferences =====

    fun setLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    /** [format] is '12h' or '24h'. */
    fun setTimeFormat(format: String) {
        prefs.edit().putString(KEY_TIME_FORMAT, format).apply()
    }

    fun getTimeFormat(): String {
        return prefs.getString(KEY_TIME_FORMAT, DEFAULT_TIME_FORMAT) ?: DEFAULT_TIME_FORMAT
    }

    /** [format] is 'mdy', 'dmy' or 'ymd'. */
    fun setDateFormat(format: String) {
        prefs.edit().putString(KEY_DATE_FORMAT, format).apply()
    }

    fun getDateFormat(): String {
        return prefs.getString(KEY_DATE_FORMAT, DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT
    }

    // ===== Map Style Settings =====

    /** Legacy pre-PMTiles style storage; kept for older server configs. */
    fun saveMapStyles(lightStyle: String, darkStyle: String) {
        prefs.edit()
            .putString(KEY_MAP_LIGHT_STYLE, lightStyle)
            .putString(KEY_MAP_DARK_STYLE, darkStyle)
            .apply()
    }

    fun savePMTilesConfig(
        tilesUrl: String,
        lightStyle: String,
        darkStyle: String,
        tileTemplate: String? = null,
    ) {
        val editor = prefs.edit()
            .putString(KEY_MAP_PMTILES_TILES_URL, tilesUrl)
            .putString(KEY_MAP_PMTILES_LIGHT, lightStyle)
            .putString(KEY_MAP_PMTILES_DARK, darkStyle)
        // Only WRITE when the server sent one. A null must not clobber a
        // stored template: a response without one would silently roll the
        // device back to the hard-coded v4 literal, undoing a cache bump.
        if (tileTemplate != null) {
            editor.putString(KEY_MAP_PMTILES_TILE_TEMPLATE, tileTemplate)
        }
        editor.apply()
    }

    /** The .pmtiles DATA file, not a style document. Falls back to the bundled default. */
    fun getPMTilesTilesUrl(): String {
        return prefs.getString(KEY_MAP_PMTILES_TILES_URL, null)
            ?: DEFAULT_PMTILES_TILES_URL
    }

    /**
     * Tile URL template for the map's vector source.
     *
     * Prefers the server-supplied value (`/api/config` → `pmtiles.tileTemplate`,
     * stored by [savePMTilesConfig]), falling back to the compiled-in literal.
     * It has to come from the server: tiles are served `immutable,
     * max-age=31536000`, so a cached tile can outlive a rebuild by a year and
     * no CDN purge reaches a device; a new template is the only cache bust.
     *
     * The tile server accepts any `/tiles/v<N>/` and serves the same archive, so
     * a device that never picks up a new template keeps rendering correctly —
     * it just does not get the cache bust.
     *
     * @see DEFAULT_PMTILES_TILE_TEMPLATE
     */
    fun getPMTilesTileTemplate(): String =
        prefs.getString(KEY_MAP_PMTILES_TILE_TEMPLATE, null) ?: DEFAULT_PMTILES_TILE_TEMPLATE

    /**
     * Get the light map style URL (PMTiles).
     * Returns the default Pingolin PMTiles style if not configured.
     */
    fun getMapLightStyle(): String {
        return prefs.getString(KEY_MAP_PMTILES_LIGHT, null)
            ?: DEFAULT_PMTILES_LIGHT_STYLE
    }

    /**
     * Get the dark map style URL (PMTiles).
     * Returns the default Pingolin PMTiles style if not configured.
     */
    fun getMapDarkStyle(): String {
        return prefs.getString(KEY_MAP_PMTILES_DARK, null)
            ?: DEFAULT_PMTILES_DARK_STYLE
    }

    /**
     * Save all preferences from auth response.
     */
    fun savePreferences(language: String?, timeFormat: String?, dateFormat: String?, theme: String?) {
        prefs.edit().apply {
            language?.let { putString(KEY_LANGUAGE, it) }
            timeFormat?.let { putString(KEY_TIME_FORMAT, it) }
            dateFormat?.let { putString(KEY_DATE_FORMAT, it) }
            theme?.let { putString(KEY_THEME_MODE, it) }
            apply()
        }
    }

    // ===== Time-Based Tracking =====

    fun setTimeBasedTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TIME_BASED_TRACKING_ENABLED, enabled).apply()
    }

    fun isTimeBasedTrackingEnabled(): Boolean = prefs.getBoolean(KEY_TIME_BASED_TRACKING_ENABLED, true)

    // ===== Distance-Based Tracking =====

    fun setDistanceSendingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALSO_SEND_ON_DISTANCE, enabled).apply()
    }

    fun isDistanceSendingEnabled(): Boolean = prefs.getBoolean(KEY_ALSO_SEND_ON_DISTANCE, true)

    fun setDistanceSendingThreshold(meters: Int) {
        prefs.edit().putInt(KEY_ALSO_SEND_DISTANCE_THRESHOLD, meters).apply()
    }

    fun getDistanceSendingThreshold(): Int {
        return prefs.getInt(KEY_ALSO_SEND_DISTANCE_THRESHOLD, DEFAULT_DISTANCE_THRESHOLD)
    }

    /**
     * The device-wide "Pause Tracking" switch earlier versions offered (a person pauses each circle
     * instead), as it applies to the account signed in now. Every gate reads it here. Nothing sets
     * it. An install that had it on keeps it, and it stops all tracking, only until
     * [com.pingolin.track.domain.repository.PauseTrackingMigration] has paused every circle on the
     * server and cleared it: lifting it any sooner would start sending to circles that aren't
     * paused yet.
     *
     * It is one account's. While another account is signed in it is parked: false here, so that
     * account tracks as it chose, and nothing of it is moved onto that account's circles. Its owner's
     * next sign-in finds it again and the move goes on. Whose it is isn't known after an update made
     * while signed out: then it holds for whoever is signed in, and the move claims it for them
     * ([claimTrackingPauseFor]), a visible pause they can resume, never a silent resume.
     */
    fun isTrackingPaused(): Boolean {
        if (!prefs.getBoolean(KEY_TRACKING_PAUSED, false)) return false
        val owner = prefs.getString(KEY_TRACKING_PAUSED_ACCOUNT, null) ?: return true
        return owner == getUserId()
    }

    /** Only for [com.pingolin.track.domain.repository.PauseTrackingMigration], once every circle is paused. */
    fun clearTrackingPaused() {
        prefs.edit().remove(KEY_TRACKING_PAUSED).remove(KEY_TRACKING_PAUSED_ACCOUNT).apply()
    }

    /**
     * Whether [userId], signed in, may move the old switch. One nobody has claimed is claimed for
     * it: the old switch held for whoever was signed in. Another account's is not this one's to
     * move, and stays parked for its owner.
     */
    fun claimTrackingPauseFor(userId: String): Boolean {
        if (!prefs.getBoolean(KEY_TRACKING_PAUSED, false)) return false
        val owner = prefs.getString(KEY_TRACKING_PAUSED_ACCOUNT, null)
        if (owner == null) prefs.edit().putString(KEY_TRACKING_PAUSED_ACCOUNT, userId).apply()
        return owner == null || owner == userId
    }

    /**
     * Before a sign-in replaces the saved account: a switch nobody has claimed yet, on a phone still
     * holding the id of the account it was signed in as (a forced sign-out keeps it), is that
     * account's. Another account signing in then finds it parked, not theirs.
     */
    fun noteTrackingPauseOwnerBeforeSignIn() {
        if (!prefs.getBoolean(KEY_TRACKING_PAUSED, false) || prefs.contains(KEY_TRACKING_PAUSED_ACCOUNT)) return
        getUserId()?.let { prefs.edit().putString(KEY_TRACKING_PAUSED_ACCOUNT, it).apply() }
    }

    /** The move is done: say so once ([takePauseTrackingMovedNote]); [hadCircles] picks the words. */
    fun markPauseTrackingMovedNote(hadCircles: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_TRACKING_MOVED_NOTE, hadCircles).apply()
    }

    /**
     * Once after [markPauseTrackingMovedNote]: whether there were circles to pause. Null when no
     * note is due, and from the second read on: the note shows one time.
     */
    fun takePauseTrackingMovedNote(): Boolean? {
        if (!prefs.contains(KEY_PAUSE_TRACKING_MOVED_NOTE)) return null
        val hadCircles = prefs.getBoolean(KEY_PAUSE_TRACKING_MOVED_NOTE, true)
        prefs.edit().remove(KEY_PAUSE_TRACKING_MOVED_NOTE).apply()
        return hadCircles
    }

    /**
     * Migrate old "Only Send When Moving" + "Also Send on Distance" to the new
     * two-toggle model (Send on interval / Send on distance).
     * Called once during SessionManager init.
     */
    fun migrateTrackingModel() {
        if (prefs.getBoolean(KEY_TRACKING_MODEL_MIGRATED, false)) return

        val oldOnlySendWhenMoving = prefs.getBoolean(KEY_DISTANCE_TRACKING_ENABLED, false)
        val oldAlsoSendOnDistance = prefs.getBoolean(KEY_ALSO_SEND_ON_DISTANCE, false)

        if (oldOnlySendWhenMoving && !oldAlsoSendOnDistance) {
            // "Only send when moving" was on → map to: time OFF, distance ON
            prefs.edit()
                .putBoolean(KEY_TIME_BASED_TRACKING_ENABLED, false)
                .putBoolean(KEY_ALSO_SEND_ON_DISTANCE, true)
                .putInt(KEY_ALSO_SEND_DISTANCE_THRESHOLD,
                    prefs.getInt(KEY_DISTANCE_THRESHOLD, DEFAULT_DISTANCE_THRESHOLD))
                .apply()
            Log.i(TAG, "Migrated 'Only Send When Moving' → distance-only mode")
        }
        // If alsoSendOnDistance was already on, it maps cleanly to the new model (both on)
        // If neither was on, time-based defaults to true which is correct

        prefs.edit()
            .putBoolean(KEY_TRACKING_MODEL_MIGRATED, true)
            .remove(KEY_DISTANCE_TRACKING_ENABLED)
            .remove(KEY_DISTANCE_THRESHOLD)
            .remove(KEY_STATIONARY_THROTTLING_ENABLED)
            .apply()
        Log.i(TAG, "Tracking model migration complete")
    }

    // ===== Tracking Enabled State =====

    /**
     * The user's explicit on/off choice, persisted so that app launch and device boot
     * do NOT auto-start tracking on their own — only a stored `true` may.
     */
    fun setTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRACKING_ENABLED, enabled).apply()
    }

    /** Defaults to false: tracking stays off until the user turns it on. */
    fun isTrackingEnabled(): Boolean = prefs.getBoolean(KEY_TRACKING_ENABLED, false)

    // ===== Tracking health =====
    //
    // So that tracking never stops silently, with nothing recorded and
    // nobody told. See TrackingHealth for the rules and TrackingJournal for
    // the durable record.

    /** The durable why-tracking-stopped record, backed by this session's prefs. */
    open val trackingJournal: TrackingJournal by lazy { TrackingJournal(prefs) }

    /**
     * The user's intent, as four separate facts that all have to hold. Kept
     * here so the service, the watchdog and the health check cannot disagree
     * about what "should be tracking" means. An account an admin hasn't approved
     * yet, or turned down, isn't tracked: Track refuses its every call, so a
     * restart (the sharing-stop keep-alive's among them) would only bring back a
     * 401 loop.
     */
    open fun shouldBeTracking(): Boolean =
        isLoggedIn() && isTrackingEnabled() && !isTrackingPaused() && !isRegistrationUnapproved()

    /** Stamped on every accepted fix. */
    open fun recordLocationFix(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FIX_AT, atMs).apply()
    }

    /** 0 when this install has never accepted a fix. */
    open fun lastLocationFixAt(): Long = prefs.getLong(KEY_LAST_FIX_AT, 0L)

    /** Consecutive watchdog cycles that tried to recover tracking and failed. */
    open fun trackingRestartFailures(): Int = prefs.getInt(KEY_RESTART_FAILURES, 0)

    open fun setTrackingRestartFailures(count: Int) {
        prefs.edit().putInt(KEY_RESTART_FAILURES, count.coerceAtLeast(0)).apply()
    }

    /**
     * True between a watchdog restart attempt and the following tick that
     * judges whether it worked. This is what turns "we asked" into "we
     * checked" — a watchdog that only started the service would assume
     * success.
     */
    open fun isTrackingRestartPending(): Boolean = prefs.getBoolean(KEY_RESTART_PENDING, false)

    open fun setTrackingRestartPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_RESTART_PENDING, pending).apply()
    }

    /** Called once tracking is confirmed working again. */
    open fun clearTrackingRecoveryState() {
        prefs.edit()
            .putInt(KEY_RESTART_FAILURES, 0)
            .putBoolean(KEY_RESTART_PENDING, false)
            .remove(KEY_TRACKING_WANTED_AT_SIGNOUT)
            .apply()
    }

    /**
     * Was tracking switched on at the moment the app signed itself out?
     *
     * Needed because `clearSession()` removes KEY_TRACKING_ENABLED, so after a
     * sign-out the app can no longer tell whether it had been sharing — which
     * is exactly the fact required to decide whether the silence is a problem.
     * Deliberately NOT in clearSession()'s removal list so it survives.
     */
    open fun setTrackingWantedAtSignOut(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_TRACKING_WANTED_AT_SIGNOUT, wanted).apply()
    }

    open fun trackingWantedAtSignOut(): Boolean =
        prefs.getBoolean(KEY_TRACKING_WANTED_AT_SIGNOUT, false)

    // ===== Display Name =====

    /** Local only — does not push the change to the server. */
    fun setDisplayName(displayName: String?) {
        prefs.edit().putString(KEY_DISPLAY_NAME, displayName).apply()
    }

    // ===== App Version Tracking =====

    /**
     * True on the first run after an app update, and on the very first run ever. Reading
     * it also records the new version, so it answers true only once per update.
     *
     * Drives device re-registration: a push token can be invalidated while the app isn't
     * running, deactivating the device server-side, and an update is the reliable moment
     * to notice and repair that.
     */
    fun checkAndUpdateAppVersion(currentVersionCode: Long): Boolean {
        val lastVersionCode = prefs.getLong(KEY_LAST_APP_VERSION_CODE, 0L)
        val isNewVersion = currentVersionCode != lastVersionCode

        if (isNewVersion) {
            android.util.Log.i("SessionManager", "App version changed: $lastVersionCode -> $currentVersionCode")
            prefs.edit().putLong(KEY_LAST_APP_VERSION_CODE, currentVersionCode).apply()
        }

        return isNewVersion
    }

    /** 0 when no version has ever been recorded. */
    fun getLastAppVersionCode(): Long {
        return prefs.getLong(KEY_LAST_APP_VERSION_CODE, 0L)
    }

    // ===== Motion Detection State =====

    /** Null when nothing was ever saved, or when the stored name no longer parses. */
    fun getLastMotionState(): MotionState? {
        val name = prefs.getString(KEY_LAST_MOTION_STATE, null) ?: return null
        return try {
            MotionState.valueOf(name)
        } catch (e: Exception) {
            null
        }
    }

    /** Persisted so the polling profile is sane across process death. */
    fun setLastMotionState(state: MotionState) {
        prefs.edit().putString(KEY_LAST_MOTION_STATE, state.name).apply()
    }

    // ===== Battery Optimization Dialog =====

    /** Latched so the battery-optimisation exemption prompt is only ever asked once. */
    fun isBatteryOptShown(): Boolean = prefs.getBoolean(KEY_BATTERY_OPT_SHOWN, false)

    fun setBatteryOptShown(shown: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_OPT_SHOWN, shown).apply()
    }

    /**
     * Whether we have told this user that nothing can deliver their alerts.
     *
     * Deliberately NOT sticky the way the battery prompt is: that one asks for a
     * setting the user either grants or refuses, so asking twice is nagging.
     * This one reports a state that can change without the user doing anything
     * here — installing a distributor fixes it — and while it holds, SOS and
     * place alerts do not arrive at all. [MainActivity] clears the flag as soon
     * as a delivery path appears, so a user who dismisses it, uninstalls their
     * distributor months later, and is once again unreachable gets told again.
     */
    fun isPushUnavailableShown(): Boolean = prefs.getBoolean(KEY_PUSH_UNAVAILABLE_SHOWN, false)

    fun setPushUnavailableShown(shown: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_UNAVAILABLE_SHOWN, shown).apply()
    }

    // ===== Location engine: read-only helpers =====

    /**
     * Whether [clearTrackingRecoveryState] would change anything, so the service
     * can ask before clearing on an accepted fix: clearing on EVERY fix would be an
     * EncryptedSharedPreferences file rewrite each time, since re-putting an
     * unchanged value still re-encrypts it. Reads only.
     */
    open fun hasTrackingRecoveryState(): Boolean =
        trackingRestartFailures() > 0 || isTrackingRestartPending() || trackingWantedAtSignOut()
}
