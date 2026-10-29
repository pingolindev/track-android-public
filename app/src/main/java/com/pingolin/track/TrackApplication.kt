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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.pingolin.track.util.ConnectivityMonitor
import com.pingolin.track.util.CrashReporter
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import com.pingolin.track.worker.PushTokenSyncWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import uniffi.pingolin_mobile.LogLevel
import uniffi.pingolin_mobile.initLogging
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class TrackApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    // EVERY ROOT HERE IS dagger.Lazy. Eagerly injecting them puts their
    // whole transitive construction inside super.onCreate(), measured at
    // 118ms — 63% of bindApplication — on an A16. Of the eight, exactly one
    // (mapLibreClient) is needed synchronously in onCreate; the rest are
    // on-demand or already behind a Handler.post, so Lazy stops startup
    // building what it never uses. Re-measure with `atrace -a <pkg>` and
    // Trace.beginSection markers added just for the measurement; do not
    // leave those in the build.
    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    // Coil 3 hands this a PlatformContext (a Context typealias on Android).
    // On demand: Coil asks the first time something loads an image, which is
    // never during startup.
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()


    @Inject
    lateinit var sessionManager: dagger.Lazy<SessionManager>

    // AlertHandler is deliberately not injected here: it is a @Singleton
    // with no init block, and TrackUnifiedPushReceiver gets its own through
    // an @EntryPoint when a push actually arrives, so injecting it at
    // startup would only add to what startup pays for.

    @Inject
    lateinit var crashReporter: dagger.Lazy<CrashReporter>

    @Inject
    lateinit var connectivityMonitor: dagger.Lazy<ConnectivityMonitor>

    // Only ever touched inside the force-logout callback below.
    @Inject
    lateinit var locationRepository: dagger.Lazy<com.pingolin.track.domain.repository.LocationRepository>

    // Changes waiting to sync: the gate's watch starts after onCreate, and the
    // force-logout callback stops their job.
    @Inject
    lateinit var pendingChangeTriggers: dagger.Lazy<com.pingolin.track.domain.pending.PendingChangeTriggers>

    @Inject
    lateinit var pendingChangeScheduler: dagger.Lazy<com.pingolin.track.domain.pending.PendingChangeScheduler>

    /** A help request that hasn't gone: taken up at start, and forgotten at a forced sign-out. */
    @Inject
    lateinit var sosDispatcher: dagger.Lazy<com.pingolin.track.domain.sos.SosDispatcher>

    /** Only for the start-up look at an account's review that this build has never read. */
    @Inject
    lateinit var authRepository: dagger.Lazy<com.pingolin.track.domain.repository.AuthRepository>

    /** That look: off the main thread, and never in a start's way. */
    private val reviewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    @Named("mapLibreClient")
    lateinit var mapLibreClient: dagger.Lazy<OkHttpClient>

    // Read only by [workManagerConfiguration], which WorkManager calls on its
    // own first use — not during startup.
    @Inject
    lateinit var workerFactory: dagger.Lazy<HiltWorkerFactory>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .build()

    companion object {
        /**
         * MapLibre ambient tile cache cap. Matches the iOS app's
         * MapCacheConfig.maximumCacheBytes so the two platforms hold a
         * comparable working set; 256 MiB pushed peak memory past the
         * foreground budget on older hardware, so both use 128 MiB.
         */
        private const val MAP_AMBIENT_CACHE_BYTES = 128L * 1024 * 1024

        const val LOCATION_CHANNEL_ID = "location_tracking"
        const val ALERTS_CHANNEL_ID = "alerts"
        const val SOS_CHANNEL_ID = "sos_emergency"

        /**
         * Place crossings this device detected ITSELF, by evaluating locations it
         * already holds against a private place (see PlaceAlertEvaluator).
         *
         * Deliberately separate from [ALERTS_CHANNEL_ID], which carries
         * server-routed pushes: a locally-evaluated crossing and a server push of
         * the SAME crossing would otherwise be indistinguishable from outside the
         * app. The channel id makes the difference observable — it shows up as
         * `channel=place_alerts_local` in `adb shell dumpsys notification
         * --noredact`, which is how a test can prove an alert came from local
         * evaluation and not from the blind server. User-visible benefit too:
         * these can be silenced independently.
         */
        const val PLACE_ALERTS_LOCAL_CHANNEL_ID = "place_alerts_local"

        init {
            // 13MB of dlopen on the main thread — this runs at class-init,
            // before onCreate, so it lands inside bindApplication. Looks
            // alarming and is not: it takes 8.0ms + 1.5ms on an A16 because the
            // pages are already warm, so moving the load off the main thread
            // would buy nothing.
            // Load SQLCipher native library (required for sqlcipher-android 4.6.1+)
            System.loadLibrary("sqlcipher")
            System.loadLibrary("pingolin_mobile")
        }

        /** Call whenever the theme preference changes. */
        fun applyThemeMode(themeMode: String) {
            val mode = when (themeMode) {
                SessionManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SessionManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    override fun onCreate() {
        // super.onCreate() is where Hilt builds the graph and populates the
        // @Inject fields above — not a formality, and the reason those
        // fields are Lazy.
        super.onCreate()
        // SECURITY (privacy): the native SDK logs plaintext coordinates at INFO
        // (e.g. geocoding tracing), and R8 only strips android.util.Log.d/v — not
        // native tracing — so INFO in a release build would leak coordinates into
        // logcat. Gate the SDK log level to WARN in release; keep INFO in debug.
        initLogging(if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.WARN)
        createNotificationChannels()
        // Retired stores (RetiredStores) go, off the main thread; a purge that fails is
        // tried again at the next launch and sign-in.
        Thread({ com.pingolin.track.util.RetiredStores.purge(this) }, "retired-stores").start()

        // Injected fields are only populated once super.onCreate() has returned and
        // Hilt has run, so every use of an @Inject field in onCreate goes through a
        // post to the main looper.
        android.os.Handler(mainLooper).post {
            crashReporter.get().initialize()
        }

        // Must precede both the first map view and setOkHttpClient below.
        MapLibre.getInstance(this)

        HttpRequestUtil.setOkHttpClient(mapLibreClient.get())

        // Size MapLibre's ambient cache — where the tile bytes ACTUALLY live.
        // Without this call MapLibre Native runs on its built-in default; the
        // iOS app pins the same 128 MiB (MapCacheConfig.maximumCacheBytes).
        //
        // It matters more than it looks: production serves individual .mvt
        // tiles, so MapLibre's ambient cache is the only layer retaining tile
        // bytes across map opens.
        //
        // Must run BEFORE any map view sets a style, or MapLibre trims against
        // the old size while the new request is in flight (the iOS app
        // documents the same constraint). Async with a callback, so failure is
        // logged rather than silently swallowed.
        try {
            OfflineManager.getInstance(this)
                .setMaximumAmbientCacheSize(MAP_AMBIENT_CACHE_BYTES, object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        android.util.Log.i(
                            "TrackApplication",
                            "MapLibre ambient cache cap=${MAP_AMBIENT_CACHE_BYTES / 1024 / 1024} MiB",
                        )
                    }
                    override fun onError(message: String) {
                        android.util.Log.w("TrackApplication", "ambient cache cap failed: $message")
                    }
                })
        } catch (e: Exception) {
            android.util.Log.w("TrackApplication", "ambient cache cap threw", e)
        }

        // Registered from here rather than from AuthRepository to avoid a circular
        // dependency between SessionManager and AuthRepository.
        android.os.Handler(mainLooper).post {
            sessionManager.get().setForceLogoutStopCallback {
                // The forced-sign-out teardown: STOP, do not WIPE.
                //
                // A forced sign-out means the server refused one refresh. That is
                // usually a rotation race or a revoked session, not the end of the
                // account on this device, so the keys, the database and the cached
                // files deliberately survive it — signing in again then restores
                // the device instead of re-importing it.
                // SessionManager.clearCredentialsOnly carries the full reasoning,
                // including why keeping the stored user id is safe:
                // AuthRepository's account-switch check runs clearLocalData before
                // a DIFFERENT user's keys are imported, on both the login and the
                // register path.
                //
                // What is left here is everything that would otherwise keep
                // running against a session that no longer exists. Each item is a
                // live process, an armed alarm or a posted notification — not
                // stored state.
                //
                // Sharing first, and all that brings it back by itself, or calls
                // Track with this session: the service (nothing it samples can be
                // uploaded), its keep-alive and geofence, the heartbeat, the
                // watchdog, the queue's sync and the notification poll. The chosen
                // sign-out stops the same list, and so does an account an admin
                // hasn't approved.
                com.pingolin.track.service.TrackingStop.all(this@TrackApplication)
                // Releases the in-memory location stream and its listeners. Not a
                // wipe — the cached rows stay; this stops the repository fanning out
                // fixes that nothing can upload.
                runCatching { locationRepository.get().cleanup() }
                // Every other background path has to be cancelled too: an uncancelled one
                // survives logout forever because nothing else ever calls its cancel().
                //  - PushTokenSyncWorker: retry-with-backoff worker that keeps waking to
                //    POST push settings for a now-dead token.
                runCatching { PushTokenSyncWorker.cancel(this@TrackApplication) }
                //  - The changes waiting to sync: kept with the database, like
                //    the keys, so the same person signing back in finds them, but
                //    nothing runs them against a session that no longer exists.
                runCatching { pendingChangeScheduler.get().cancel() }
                //  - A help request waiting for a connection: it never goes out,
                //    or shows, after its account's session ended.
                runCatching { sosDispatcher.get().forgetAccount() }
                // Posted notifications: alert/check-in/emergency/destination pushes
                // render DECRYPTED title and body into the status bar. They are stale
                // the moment the session ends, and a notification tapped after a
                // sign-out opens a screen with nothing behind it.
                runCatching { NotificationManagerCompat.from(this@TrackApplication).cancelAll() }
            }
        }

        // Start monitoring connectivity so the pending queue flushes the moment
        // the network comes back, instead of waiting for the 15-min periodic tick.
        android.os.Handler(mainLooper).post {
            connectivityMonitor.get().start()
        }

        // Changes waiting to sync go out once this phone catches up.
        android.os.Handler(mainLooper).post {
            pendingChangeTriggers.get().start()
        }

        // A help request this account left pending when the process ended: it goes on
        // waiting for a connection, or the person is told it may not have gone.
        android.os.Handler(mainLooper).post {
            // A session store that can't be opened now has nothing to resume, and no crash.
            if (sessionManager.get().storeRefusal() == null && sessionManager.get().isLoggedIn()) {
                runCatching { sosDispatcher.get().resumePending() }
            }
        }

        android.os.Handler(mainLooper).post {
            // A session store that can't be opened now: the phone's own theme, no crash.
            if (sessionManager.get().storeRefusal() == null) applyThemeMode(sessionManager.get().getThemeMode())
        }

        // What brings the location service back should it die without a word: held at
        // every start while sharing is wanted, asked of the store first.
        android.os.Handler(mainLooper).post {
            val session = sessionManager.get()
            if (session.storeRefusal() == null && session.shouldBeTracking()) {
                com.pingolin.track.service.LocationKeepAlive.register(
                    this, session.isBatterySaverEnabled(), session.getBatterySaverInterval(),
                )
            }
        }

        // An account whose review this build has never read: looked at once, in the background, never
        // before a start, which mustn't wait on the network. Data from an earlier version has no review
        // state, so an account an admin hasn't approved can still have its sharing restored by an update
        // (BootReceiver); the answer stops it all (SessionManager.keepRegistrationReview), whether or not
        // the app is ever opened. Until a look gets an answer, each start asks again.
        android.os.Handler(mainLooper).post {
            val session = sessionManager.get()
            if (session.storeRefusal() == null && session.isLoggedIn() && !session.isRegistrationReviewRead()) {
                reviewScope.launch { runCatching { authRepository.get().lookAtReview() } }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // IMPORTANCE_LOW: notification stays in the shade, no heads-up
            // popup, no sound or vibration. Matches the standard pattern
            // for foreground-service "process is running" notifications.
            // Note: existing installs that already have this channel keep
            // whatever importance the OS recorded (channel settings are
            // sticky). Clear app data or set it manually to re-pick this.
            val locationChannel = NotificationChannel(
                LOCATION_CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when location tracking is active"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }

            val alertsChannel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Alerts & Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Place alerts, check-ins, and other notifications"
                enableVibration(true)
            }

            val sosChannel = NotificationChannel(
                // The ID must never change — a new one is a new channel, and the
                // user's importance and Do-Not-Disturb choices do not follow it.
                // The NAME is what they read in system Settings, and it must not
                // say "SOS": nothing else in the app does.
                SOS_CHANNEL_ID,
                "Circle emergency alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency alerts from circle members"
                enableVibration(true)
                setBypassDnd(true)
            }

            // Locally-evaluated place crossings. Same importance as the general
            // alerts channel — the user asked to be told when someone reaches their
            // place, and where the detection happened must not change how loudly it
            // arrives. Separate only so the two sources are distinguishable (and
            // independently silenceable).
            val placeAlertsLocalChannel = NotificationChannel(
                PLACE_ALERTS_LOCAL_CHANNEL_ID,
                "Place Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Arrivals and departures at your places, detected on this device"
                enableVibration(true)
            }

            notificationManager.createNotificationChannels(
                listOf(locationChannel, alertsChannel, sosChannel, placeAlertsLocalChannel)
            )
        }
    }
}
