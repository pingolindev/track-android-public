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

package com.pingolin.track.di

import com.pingolin.track.util.SecurePrefs
import android.content.Context
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pingolin.track.data.local.TrackDatabase
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.AuthRateLimiter
import com.pingolin.track.util.CircuitBreakerInterceptor
import com.pingolin.track.util.DatabasePassphraseManager
import com.pingolin.track.util.LazyPassphraseOpenHelperFactory
import com.pingolin.track.util.RateLimitInterceptor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.TokenAuthenticator
import com.pingolin.track.util.refusingCompromisedKeys
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.pingolin.track.BuildConfig
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.BoundaryChecker
import uniffi.pingolin_mobile.BoundaryMonitor
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.InactivityMonitor
import uniffi.pingolin_mobile.SecureStorage
import com.pingolin.track.util.LazySecurePrefs
import com.pingolin.track.util.SecureStorageCallbackImpl
import com.pingolin.track.util.KeyStorageCallbackImpl
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabasePassphraseManager(
        @ApplicationContext context: Context,
        // Lazy, as every holder of the MasterKey: making it is a Keystore call, and none may run
        // while Hilt injects. The first use opens it, inside that holder's catch.
        masterKey: dagger.Lazy<MasterKey>
    ): DatabasePassphraseManager {
        return DatabasePassphraseManager(context, masterKey)
    }

    /**
     * Provides an encrypted Room database using SQLCipher.
     *
     * The encryption passphrase is:
     * - 256-bit randomly generated on first app launch
     * - Encrypted with Android Keystore (hardware-backed)
     * - Unique per device installation
     *
     * This protects all local data including:
     * - Location history
     * - User profiles
     * - Circle memberships
     * - Place definitions
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseManager: DatabasePassphraseManager
    ): TrackDatabase {
        // Lazy factory: defers DatabasePassphraseManager.getPassphrase() (a hardware
        // Keystore op) until the DB is actually opened. The Keystore key requires
        // an unlocked device; doing the op eagerly here crashes the app whenever
        // Android cold-starts it while the phone is locked (post-update, post-doze).
        val factory = LazyPassphraseOpenHelperFactory(passphraseManager)
        return buildDatabase(context, factory)
    }

    private fun buildDatabase(
        context: Context,
        factory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory
    ): TrackDatabase {
        return Room.databaseBuilder(
            context,
            TrackDatabase::class.java,
            "tracks_database"
        )
            .openHelperFactory(factory)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .fallbackToDestructiveMigration(true)
            .addMigrations(
                TrackDatabase.MIGRATION_1_2,
                TrackDatabase.MIGRATION_2_3,
                TrackDatabase.MIGRATION_3_4,
                TrackDatabase.MIGRATION_4_5,
                TrackDatabase.MIGRATION_5_6,
                TrackDatabase.MIGRATION_6_7,
                TrackDatabase.MIGRATION_7_8,
                TrackDatabase.MIGRATION_8_9,
                TrackDatabase.MIGRATION_9_10,
                TrackDatabase.MIGRATION_10_11,
                TrackDatabase.MIGRATION_11_12,
                TrackDatabase.MIGRATION_12_13,
                TrackDatabase.MIGRATION_13_14,
                TrackDatabase.MIGRATION_14_15,
                TrackDatabase.MIGRATION_15_16,
                TrackDatabase.MIGRATION_16_17,
                TrackDatabase.MIGRATION_17_18,
                TrackDatabase.MIGRATION_18_19,
                TrackDatabase.MIGRATION_19_20,
                TrackDatabase.MIGRATION_20_21,
                TrackDatabase.MIGRATION_21_22,
                TrackDatabase.MIGRATION_22_23,
                TrackDatabase.MIGRATION_23_24,
                TrackDatabase.MIGRATION_24_25,
                TrackDatabase.MIGRATION_25_26,
                TrackDatabase.MIGRATION_26_27,
                TrackDatabase.MIGRATION_27_28,
                TrackDatabase.MIGRATION_28_29,
                TrackDatabase.MIGRATION_29_30,
                TrackDatabase.MIGRATION_30_31,
                TrackDatabase.MIGRATION_31_32,
                TrackDatabase.MIGRATION_32_33,
                TrackDatabase.MIGRATION_33_34,
                TrackDatabase.MIGRATION_34_35,
                TrackDatabase.MIGRATION_35_36,
                TrackDatabase.MIGRATION_36_37,
                TrackDatabase.MIGRATION_37_38,
                TrackDatabase.MIGRATION_38_39,
                TrackDatabase.MIGRATION_39_40,
                TrackDatabase.MIGRATION_40_41,
                TrackDatabase.MIGRATION_41_42,
                TrackDatabase.MIGRATION_42_43,
                TrackDatabase.MIGRATION_43_44,
                TrackDatabase.MIGRATION_44_45,
                TrackDatabase.MIGRATION_45_46,
                TrackDatabase.MIGRATION_46_47,
                TrackDatabase.MIGRATION_47_48,
                TrackDatabase.MIGRATION_48_49
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideMasterKey(@ApplicationContext context: Context): MasterKey {
        return SecurePrefs.masterKey(context)
    }

    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context,
        masterKey: dagger.Lazy<MasterKey>
    ): android.content.SharedPreferences = LazySecurePrefs {
        // Opened at its first use, never while Hilt injects: a refusal is that use's
        // SecureStorageUnavailableException, which its callers handle.
        SecurePrefs.open(
            context,
            // This file holds the SDK's persisted crypto keys
            // (crypto_private_key, crypto_circle_keys_json, etc.)
            // via KeyStorageCallbackImpl. SessionManager uses a
            // separate file ("pingolin_session_encrypted") for
            // session tokens — they intentionally do NOT share a
            // backing file.
            //
            // NEVER blanket-clear this file from a migration or a
            // "tidy the old prefs" path: it is the device's only
            // copy of those keys, and wiping it strands the user
            // until they sign out and back in to re-derive from
            // the server's encrypted_private_key.
            "tracks_secure_prefs",
            masterKey.get(),
        )
    }

    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context,
        masterKey: dagger.Lazy<MasterKey>
    ): SessionManager {
        return SessionManager(context, masterKey)
    }

    @Provides
    @Singleton
    fun provideKeyStorageCallback(
        encryptedSharedPreferences: android.content.SharedPreferences
    ): KeyStorageCallbackImpl {
        return KeyStorageCallbackImpl(encryptedSharedPreferences)
    }

    /**
     * The place-alert match hash, bound to the real CryptoManager.
     *
     * Kept as a function type so PlaceAlertEvaluator can be unit-tested without the
     * UniFFI native library — see EventTypeHasher.
     */
    @Provides
    @Singleton
    fun provideEventTypeHasher(
        cryptoManager: CryptoManager,
    ): com.pingolin.track.domain.placealerts.EventTypeHasher =
        com.pingolin.track.domain.placealerts.EventTypeHasher { circleId, eventType ->
            cryptoManager.computeEventTypeHash(circleId, eventType)
        }

    /**
     * Provides CryptoManager with key persistence enabled.
     *
     * The KeyStorageCallback allows the CryptoManager to persist:
     * - User's X25519 private key (derived public key is recomputed)
     * - Circle encryption keys (XChaCha20-Poly1305)
     *
     * Keys are stored encrypted in EncryptedSharedPreferences using:
     * - AES-256-SIV for key names
     * - AES-256-GCM for values
     * - Hardware-backed Android Keystore for the master key
     */
    @Provides
    @Singleton
    fun provideCryptoManager(keyStorageCallback: KeyStorageCallbackImpl, owedWipe: com.pingolin.track.util.OwedWipe): CryptoManager {
        val cryptoManager = CryptoManager()
        cryptoManager.registerSecureStorage(keyStorageCallback)

        // A wipe a sign-out couldn't finish is paid before anything is loaded; while it stays
        // owed, nothing is. What a load found is keyStoreStatus()'s to say
        // (KeyStoreRecovery), never an exception's.
        if (com.pingolin.track.util.KeyStoreRecovery.loadAtLaunch(cryptoManager, owedWipe)) {
            Log.i("AppModule", "Successfully loaded persisted crypto keys")
        } else {
            Log.d("AppModule", "No persisted crypto keys loaded (${cryptoManager.keyStoreStatus().load})")
        }

        return cryptoManager
    }

    @Provides
    @Singleton
    fun provideInactivityMonitor(): InactivityMonitor {
        return InactivityMonitor()
    }

    /** Detects boundary crossings for THIS device's own location. */
    @Provides
    @Singleton
    fun provideBoundaryChecker(): BoundaryChecker {
        return BoundaryChecker()
    }

    /**
     * Watches circle MEMBERS cross boundaries: decrypts their incoming location
     * updates, checks them against the loaded boundaries and fires a callback on
     * a crossing. Every step happens client-side — the server never sees a
     * plaintext location or boundary, which is what keeps the feature E2EE.
     */
    @Provides
    @Singleton
    fun provideBoundaryMonitor(cryptoManager: CryptoManager): BoundaryMonitor {
        return BoundaryMonitor(cryptoManager)
    }

    @Provides
    @Singleton
    fun provideSecureStorageCallback(
        encryptedSharedPreferences: android.content.SharedPreferences
    ): SecureStorageCallbackImpl {
        return SecureStorageCallbackImpl(encryptedSharedPreferences)
    }

    /**
     * The SDK's SecureStorage, wired to the Android callback so circle keys and
     * other sensitive values land in EncryptedSharedPreferences.
     */
    @Provides
    @Singleton
    fun provideSecureStorage(callback: SecureStorageCallbackImpl): SecureStorage {
        val storage = SecureStorage()
        storage.registerCallback(callback)
        return storage
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder().create()
    }

    /**
     * Provides a process-wide Coil ImageLoader capped at 10% of the JVM heap.
     * Without this cap Coil defaults to ~25% which is wasteful given how few
     * images this app loads. 10% gives plenty of headroom for the rest of the app.
     */
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext ctx: Context): ImageLoader {
        return ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.10).build() }
            .build()
    }

    /**
     * One ConnectionPool shared by every OkHttpClient below, so the API, map and
     * MapLibre clients reuse connections instead of each holding their own set.
     * 10 idle connections and a 5-minute keep-alive suit a mobile app talking to
     * a handful of endpoints.
     */
    @Provides
    @Singleton
    fun provideConnectionPool(): ConnectionPool {
        return ConnectionPool(
            maxIdleConnections = 10,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES
        )
    }

    // No certificate pinning, on purpose. Every host sits behind Cloudflare
    // Universal SSL, which picks the issuing CA and rotates it without notice,
    // so a pin here would hard-fail every request the day Cloudflare swaps CA.
    // Pinning would need Advanced Certificate Manager (to fix the CA), root
    // pins with a backup, and a remote kill switch. Instead the clients use
    // system trust, less any path through a key known to be compromised
    // (CompromisedKeys).

    /** System trust, refusing every path through a [com.pingolin.track.util.CompromisedKeys] key: on every client below. */
    @Provides
    @Singleton
    fun provideCompromisedKeyTrust(@ApplicationContext context: Context): com.pingolin.track.util.CompromisedKeyTrustManager =
        com.pingolin.track.util.CompromisedKeyTrustManager.platform(context.noBackupFilesDir, debug = BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun provideCircuitBreakerInterceptor(): CircuitBreakerInterceptor {
        return CircuitBreakerInterceptor()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        sessionManager: SessionManager,
        tokenAuthenticator: TokenAuthenticator,
        rateLimitInterceptor: RateLimitInterceptor,
        circuitBreakerInterceptor: CircuitBreakerInterceptor,
        connectionPool: ConnectionPool,
        trust: com.pingolin.track.util.CompromisedKeyTrustManager,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .refusingCompromisedKeys(trust)
            .connectionPool(connectionPool)
            // Automatic token refresh on 401 responses
            .authenticator(tokenAuthenticator)
            .addInterceptor(circuitBreakerInterceptor)
            // Handle 429 rate limiting with retry and backoff
            .addInterceptor(rateLimitInterceptor)
            .addInterceptor { chain ->
                val original = chain.request()
                // A session store that can't be opened now fails the request as
                // a lost connection does: OkHttp hands an IOException to the
                // caller, which handles it. Anything else thrown here kills the
                // app on OkHttp's thread.
                val token = try {
                    sessionManager.getAccessToken()
                } catch (e: Exception) {
                    if (!com.pingolin.track.util.DatabaseUnavailable.isUnavailable(e)) throw e
                    throw com.pingolin.track.util.StoreRefusedIOException(e)
                }
                // Advertise the device model in the UA so the auth backend can
                // label sessions with the real device (e.g. "Google Pixel 7")
                // instead of the generic "on Android". Sanitized to a safe
                // charset so it can't break the UA/header or the server parser.
                val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    .replace(Regex("[^A-Za-z0-9 ._-]"), "")
                    .trim()
                    .take(60)
                // Session-less anonymous requests (e.g. geocode token
                // redemption, POST /geocode/reverse) opt OUT of auth via this
                // sentinel header: they must carry NO Authorization so the
                // backend cannot relink the request to a user. Strip the marker
                // so it never goes on the wire.
                val anonymous = original.header("X-Pingolin-Anon") != null
                val requestBuilder = original.newBuilder()
                    .header("User-Agent", "Track/${BuildConfig.VERSION_NAME} ($deviceModel; Android ${android.os.Build.VERSION.RELEASE}) okhttp")

                if (anonymous) {
                    requestBuilder.removeHeader("X-Pingolin-Anon")
                    // Session-less requests must not carry a device-identifying UA.
                    // Override the detailed manufacturer/model/OS-release UA set above
                    // with a generic value so an anonymous geocode redemption can't be
                    // fingerprinted back to this specific device/OS build.
                    requestBuilder.header("User-Agent", "Track/${BuildConfig.VERSION_NAME} okhttp")
                } else if (token != null) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                chain.proceed(requestBuilder.build())
            }

        // Only enable HTTP logging in debug builds
        // SECURITY: Use HEADERS level (not BODY) to avoid logging sensitive request/response bodies
        // Body logging could expose tokens, keys, encrypted data, and user information
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                // Redact all potentially sensitive headers from logs
                redactHeader("Authorization")
                redactHeader("X-Attestation-Token")
                redactHeader("X-Device-Id")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
                redactHeader("X-Api-Key")
                redactHeader("X-Internal-Api-Key")
                // The refresh token itself (a logged one works until the next refresh).
                redactHeader("X-Refresh-Token")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * MapFragment's style fetch. It is intentionally small: tiles, sprites and
     * glyphs go through MapLibre's ambient cache instead of this OkHttp cache.
     */
    @Provides
    @Singleton
    @Named("mapClient")
    fun provideMapOkHttpClient(
        @ApplicationContext context: Context,
        connectionPool: ConnectionPool,
        trust: com.pingolin.track.util.CompromisedKeyTrustManager,
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "map_cache")
        val cache = Cache(cacheDir, 5L * 1024 * 1024)

        return OkHttpClient.Builder()
            .refusingCompromisedKeys(trust)
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectionPool(connectionPool)
            .cache(cache)
            // Add cache control interceptor for responses without cache headers
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val url = chain.request().url.toString()

                val cacheControl = when {
                    url.contains(".pmtiles") -> CacheControl.Builder()
                        .maxAge(7, TimeUnit.DAYS)
                        .build()
                    url.contains("/sprites/") -> CacheControl.Builder()
                        .maxAge(7, TimeUnit.DAYS)
                        .build()
                    url.contains("style") || url.contains(".json") -> CacheControl.Builder()
                        .maxAge(24, TimeUnit.HOURS)
                        .build()
                    else -> CacheControl.Builder()
                        .maxAge(1, TimeUnit.HOURS)
                        .build()
                }

                response.newBuilder()
                    .header("Cache-Control", cacheControl.toString())
                    .removeHeader("Pragma")
                    .build()
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // Longer timeout for large tile fetches
            .build()
    }

    /**
     * The client MapLibre itself uses: TrackApplication hands it to
     * HttpRequestUtil.setOkHttpClient(), so every tile, sprite and glyph fetch
     * goes through it.
     *
     * What retains tiles is MapLibre Native's own 128 MiB ambient cache, sized
     * in TrackApplication. There is deliberately no second, range-keyed cache:
     * tiles are fetched as individual `.mvt` URLs, not byte ranges of a
     * `.pmtiles` archive, so such a cache would match nothing while occupying
     * a large cache budget.
     */
    @Provides
    @Singleton
    @Named("mapLibreClient")
    fun provideMapLibreOkHttpClient(
        connectionPool: ConnectionPool,
        trust: com.pingolin.track.util.CompromisedKeyTrustManager,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .refusingCompromisedKeys(trust)
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectionPool(connectionPool)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthRetrofit(okHttpClient: OkHttpClient, sessionManager: SessionManager): Retrofit {
        return Retrofit.Builder()
            .baseUrl(sessionManager.getAuthBaseUrl())
            .client(withoutRateLimitRetries(okHttpClient))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * [client] without [RateLimitInterceptor]'s retries, for auth: a 429 there is an answer the
     * person reads at once ("too many wrong codes" on the code step), never a load to wait out
     * for up to 20 s first (the iOS app does the same). Everything else stays shared: the pool,
     * the authenticator, the timeouts and the headers.
     */
    internal fun withoutRateLimitRetries(client: OkHttpClient): OkHttpClient =
        client.newBuilder().apply { interceptors().removeAll { it is RateLimitInterceptor } }.build()

    @Provides
    @Singleton
    @Named("api")
    fun provideApiRetrofit(okHttpClient: OkHttpClient, sessionManager: SessionManager): Retrofit {
        return Retrofit.Builder()
            .baseUrl(sessionManager.getApiBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(@Named("auth") retrofit: Retrofit): AuthApi {
        return retrofit.create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideTrackApi(@Named("api") retrofit: Retrofit): TrackApi {
        // Account ids in their one spelling where they're decoded.
        return com.pingolin.track.data.remote.api.CanonicalIdsTrackApi(retrofit.create(TrackApi::class.java))
    }

    // ===== Attestation Services =====

    @Provides
    @Singleton
    fun providePlayIntegrityManager(@ApplicationContext context: Context): com.pingolin.track.util.PlayIntegrityManager {
        return com.pingolin.track.util.PlayIntegrityManager(context)
    }

    @Provides
    @Singleton
    fun provideHardwareAttestation(): com.pingolin.track.util.HardwareAttestation {
        return com.pingolin.track.util.HardwareAttestation()
    }

    @Provides
    @Singleton
    fun provideAttestationManager(
        @ApplicationContext context: Context,
        authApi: AuthApi,
        playIntegrityManager: com.pingolin.track.util.PlayIntegrityManager,
        hardwareAttestation: com.pingolin.track.util.HardwareAttestation
    ): com.pingolin.track.util.AttestationManager {
        return com.pingolin.track.util.AttestationManager(context, authApi, playIntegrityManager, hardwareAttestation)
    }

    // ===== DAOs =====

    @Provides
    fun provideUserDao(database: TrackDatabase) = database.userDao()

    @Provides
    fun provideCircleDao(database: TrackDatabase) = database.circleDao()

    @Provides
    fun provideCircleSortOrderDao(database: TrackDatabase) = database.circleSortOrderDao()

    @Provides
    fun provideCircleMemberDao(database: TrackDatabase) = database.circleMemberDao()

    @Provides
    fun provideDeviceDao(database: TrackDatabase) = database.deviceDao()

    @Provides
    fun providePlaceDao(database: TrackDatabase) = database.placeDao()

    @Provides
    fun providePlaceAlertDao(database: TrackDatabase) = database.placeAlertDao()

    @Provides
    fun provideLocationDao(database: TrackDatabase) = database.locationDao()

    @Provides
    fun provideCheckInDao(database: TrackDatabase) = database.checkInDao()

    @Provides
    fun provideSosAlertDao(database: TrackDatabase) = database.sosAlertDao()

    @Provides
    fun providePrivacySettingDao(database: TrackDatabase) = database.privacySettingDao()

    @Provides
    fun providePendingLocationDao(database: TrackDatabase) = database.pendingLocationDao()

    @Provides
    fun providePendingCrossingDao(database: TrackDatabase) = database.pendingCrossingDao()

    @Provides
    fun provideQueuedCircleLocationDao(database: TrackDatabase) = database.queuedCircleLocationDao()

    @Provides
    fun providePendingChangeDao(database: TrackDatabase) = database.pendingChangeDao()

    @Provides
    fun provideDestinationDao(database: TrackDatabase) = database.destinationDao()

    @Provides
    fun provideInactivityAlertDao(database: TrackDatabase) = database.inactivityAlertDao()

    @Provides
    fun provideAlertRuleDao(database: TrackDatabase) = database.alertRuleDao()

    @Provides
    fun provideNotificationDao(database: TrackDatabase) = database.notificationDao()

    @Provides
    fun providePendingInviteDao(database: TrackDatabase) = database.pendingInviteDao()

    @Provides
    fun provideLocalInactivityEventDao(database: TrackDatabase) = database.localInactivityEventDao()

    @Provides
    fun provideMemberNotSharingSeenDao(database: TrackDatabase) = database.memberNotSharingSeenDao()

    @Provides
    fun provideLocalBatteryEventDao(database: TrackDatabase) = database.localBatteryEventDao()

    @Provides
    fun providePlaceAlertMemberStateDao(database: TrackDatabase) = database.placeAlertMemberStateDao()

    @Provides
    fun providePlaceAlertEventDao(database: TrackDatabase) = database.placeAlertEventDao()

    @Provides
    fun providePlaceAlertShownDao(database: TrackDatabase) = database.placeAlertShownDao()
}
