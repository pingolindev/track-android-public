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

package com.pingolin.track.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import android.util.TypedValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.textfield.TextInputEditText
import com.pingolin.track.R
import com.pingolin.track.databinding.ActivityMainBinding
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.ui.auth.AuthActivity
import com.pingolin.track.util.KeystoreEncryption
import com.pingolin.track.util.RootDetector
import com.pingolin.track.util.BatteryOptimizationHelper
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import com.pingolin.track.util.DatabaseUnavailable
import com.pingolin.track.worker.LocationHeartbeatWorker
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.TrackingHealthNotifier
import com.pingolin.track.worker.LocationSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * The primary navigation control: a BottomNavigationView on phones and on a
     * 7-inch tablet in portrait, a NavigationRailView from w840dp up (see
     * res/layout-w840dp/activity_main.xml).
     *
     * Cast, because ViewBinding types `binding.bottomNav` as plain `View`. When
     * one id is declared with DIFFERENT classes across layout variants it does
     * not resolve to the nearest common ancestor — it falls back to View (the
     * generated ActivityMainBinding for devGmsDebug reads `public final View
     * bottomNav`).
     *
     * Both classes extend NavigationBarView, which carries every API this
     * activity uses on it (menu, setOnItemSelectedListener, getOrCreateBadge),
     * so the cast holds for both layouts and is made exactly once here rather
     * than at each of the call sites below.
     */
    private val navBar: NavigationBarView
        get() = binding.bottomNav as NavigationBarView
    private lateinit var navController: NavController

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var keystoreEncryption: KeystoreEncryption

    @Inject
    lateinit var locationRepository: com.pingolin.track.domain.repository.LocationRepository

    @Inject
    lateinit var pushNotificationManager: com.pingolin.track.util.PushNotificationManager

    /** Used only by [reconcilePushRegistrationWithServer] to ask the server what it can
     * deliver here.
     */
    @Inject
    lateinit var tracksApi: com.pingolin.track.data.remote.api.TrackApi

    @Inject
    lateinit var uxEventBus: com.pingolin.track.util.UxEventBus

    @Inject
    lateinit var premiumStatusRefresher: com.pingolin.track.util.PremiumStatusRefresher

    @Inject
    lateinit var geocodeTokenManager: dagger.Lazy<com.pingolin.track.data.remote.GeocodeTokenManager>

    @Inject
    lateinit var sosDispatcher: dagger.Lazy<com.pingolin.track.domain.sos.SosDispatcher>

    /** The key store as the launch found it: a session whose store doesn't decode ends. */
    @Inject
    lateinit var keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery

    @Inject
    lateinit var pauseTrackingMigration: com.pingolin.track.domain.repository.PauseTrackingMigration

    @Inject
    lateinit var databasePassphraseManager: com.pingolin.track.util.DatabasePassphraseManager

    @Inject
    lateinit var alertRulesReload: com.pingolin.track.domain.repository.AlertRulesReload

    private var isAppLocked = false

    /** "Allow all the time", asked once per opening of the app. */
    private val allTheTime = AllTheTimeAsker(
        activity = this,
        mayAskNow = { !isAppLocked },
        onContinue = { backgroundLocationRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
    ).also { lifecycle.addObserver(it) }

    /** The password, asked once in place when this phone lost its signing key. */
    private val passwordInPlace = PasswordInPlacePrompt(
        activity = this,
        mayAskNow = { !isAppLocked },
        isDue = { authRepository.signingKeyMissing() },
        unlock = { authRepository.unlockSigningKeyInPlace(it) },
        onSignInNeeded = {
            sessionManager.triggerForceLogout(
                com.pingolin.track.util.TrackingStopReason.SESSION_REJECTED,
                "re-auth: the server asks for a sign-in",
            )
        },
    ).also { lifecycle.addObserver(it) }

    // Drives the notification bell's top inset.
    private var currentDestinationId: Int = 0
    private var statusBarInsetPx: Int = 0

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        // Asked from the first-run chain: any answer but yes is that chain's "Not now". The
        // question stops coming back at every opening (the map's "Location is off" line and the
        // actions that share location still ask), and the rest of first-run setup carries on:
        // notifications, the battery question, push delivery. Going to Settings carries it on at
        // the next opening instead, never on top of Settings.
        val atStartup = locationAskedAtStartup.also { locationAskedAtStartup = false }
        if (!fineLocationGranted && atStartup) sessionManager.setDeclinedLocationRationale(true)
        val carryOn: () -> Unit = { if (atStartup) checkNotificationPermission() }

        when {
            fineLocationGranted -> {
                // Location granted — start tracking now (don't wait for remaining permissions)
                startTrackingIfPermitted()
                checkBackgroundLocationPermission()
            }

            // "Approximate location" — the system's other way of saying
            // yes, and the one that produces NOTHING, so it is never
            // treated as the grant above: it starts nothing, because
            // startTrackingIfPermitted requires FINE and silently no-ops;
            // and where something else does start the service (boot
            // receiver, watchdog alarm, push wake) every coarse fix is
            // kilometre-scale and gets discarded by the 100m accuracy
            // filter. Either way the user would have answered the prompt,
            // believe tracking is on, and get no location and no
            // explanation.
            coarseLocationGranted -> showPreciseLocationRequiredDialog(onNotNow = carryOn)

            // No. Where Android won't ask any more (refused twice, or "don't ask again"),
            // only its settings page can turn location on, so say so. Otherwise the map's
            // "Location is off" line stays, and asks again when tapped.
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                // It was asked, whatever this install recorded (installs from an older version
                // have no such record).
                sessionManager.setAskedForLocation()
                showLocationSettingsDialog(onNotNow = carryOn)
            }

            else -> carryOn()
        }
    }

    /** The location question came from the first-run chain, which carries on after its answer. */
    private var locationAskedAtStartup = false

    /**
     * Ask for location again: from the map's "Location is off" line, and after an action that
     * shares it (creating or joining a circle, Send location now, continuous tracking). "Not
     * now" at the first question keeps it from being asked at every opening, never for good.
     */
    fun askForLocation() {
        if (isFinishing || isDestroyed) return
        val ask = com.pingolin.track.util.locationAsk(
            com.pingolin.track.util.LocationAccess.of(this),
            askedBefore = sessionManager.hasAskedForLocation(),
            androidExplains = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        when (ask) {
            com.pingolin.track.util.LocationAsk.NOTHING -> Unit
            com.pingolin.track.util.LocationAsk.QUESTION -> showLocationRationale(onNotNow = {})
            // Android may not ask again, so not Track's "Android will ask next": its own question
            // alone. Where it shows nothing, the answer offers Settings (above); where it asks,
            // after an "Only this time" that ended or a reset, the person answers it.
            com.pingolin.track.util.LocationAsk.ANDROID -> locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            com.pingolin.track.util.LocationAsk.PRECISE -> showPreciseLocationRequiredDialog()
            com.pingolin.track.util.LocationAsk.SERVICES -> showLocationServicesDialog()
        }
    }

    /** Why Track asks, then Android's own question. [onNotNow]: what "Not now" does besides closing it. */
    private fun showLocationRationale(onNotNow: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_rationale_title)
            .setMessage(R.string.location_rationale_body)
            .setPositiveButton(R.string.location_rationale_continue) { _, _ ->
                sessionManager.setAskedForLocation()
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            .setNegativeButton(R.string.location_rationale_skip) { _, _ -> onNotNow() }
            // Not cancellable to the side: the two answers are Continue and
            // Not now, and a dismissed dialog that silently means "not now"
            // would set the flag without the user choosing it.
            .setCancelable(false)
            .show()
    }

    /**
     * Android won't ask for location any more: its settings page is the way to turn it on.
     * [onNotNow]: what closing it without going there does besides.
     */
    private fun showLocationSettingsDialog(onNotNow: () -> Unit = {}) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_settings_title)
            .setMessage(R.string.location_settings_body)
            .setPositiveButton(R.string.location_precise_required_open_settings) { dialog, _ ->
                dialog.dismiss()
                openAppSettings()
            }
            .setNegativeButton(R.string.location_precise_required_dismiss) { dialog, _ -> dialog.dismiss(); onNotNow() }
            .setOnCancelListener { onNotNow() }
            .show()
    }

    /** Location Services are off on this phone: nothing is shared until they're on. */
    private fun showLocationServicesDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_services_off_title)
            .setMessage(R.string.location_services_off_body)
            .setPositiveButton(R.string.location_precise_required_open_settings) { dialog, _ ->
                dialog.dismiss()
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                    openAppSettings()
                }
            }
            .setNegativeButton(R.string.location_precise_required_dismiss) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * A help request that went reached nobody, or may not have arrived: said over any screen
     * of Track until OK, which alone marks it told.
     */
    private val deliveryVerdict = com.pingolin.track.ui.sos.DeliveryVerdictDialog { sosDispatcher.get().deliveries.told() }

    /**
     * Tell the user that "Approximate location" cannot drive tracking, and give
     * them the one route that can change it.
     *
     * Re-requesting ACCESS_FINE_LOCATION shows the system's precise-location
     * upgrade prompt only until the user has refused it; after that the request
     * returns instantly with the same answer and the app has nothing left to
     * offer but Settings — where "Use precise location" lives. So go straight
     * there rather than re-prompting into a dialog that may never appear.
     */
    private fun showPreciseLocationRequiredDialog(onNotNow: () -> Unit = {}) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_precise_required_title)
            .setMessage(R.string.location_precise_required_body)
            .setPositiveButton(R.string.location_precise_required_open_settings) { dialog, _ ->
                dialog.dismiss()
                openAppSettings()
            }
            .setNegativeButton(R.string.location_precise_required_dismiss) { dialog, _ -> dialog.dismiss(); onNotNow() }
            .setCancelable(true)
            .setOnCancelListener { onNotNow() }
            .show()
    }

    /** The app's own settings page — reachable on every device, unlike the deep links. */
    private fun openAppSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** Once, after this phone's database was made again because its key was gone. */
    private fun showDatabaseRebuiltNote() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.database_rebuilt_note)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Once, after [com.pingolin.track.domain.repository.PauseTrackingMigration]: plain words, nothing to do. */
    private fun showPauseTrackingMovedNote(hadCircles: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setMessage(if (hadCircles) R.string.pause_tracking_moved else R.string.pause_tracking_removed_no_circles)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Start tracking if location permission is granted and tracking isn't paused */
    private fun startTrackingIfPermitted() {
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation && !LocationTrackingService.isRunning && !sessionManager.isTrackingPaused()) {
            viewModel.startTracking()
        }
    }

    private val backgroundLocationRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkNotificationPermission()
        } else {
            Toast.makeText(
                this,
                "Background location is needed for continuous tracking",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notifications are recommended for alerts",
                Toast.LENGTH_SHORT
            ).show()
        }
        continueStartupChecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // This phone's session store can't be opened now: said in place of the app, which looks
        // again by itself, never a crash, and never "signed out", which nothing can tell here.
        if (sessionManager.storeRefusal() != null) {
            com.pingolin.track.ui.start.CantStartActivity.showFor(this)
            return
        }

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        // Stored keys that don't decode: nothing works, and the session ends, to the sign-in
        // screen with the sync error (KeyStoreRecovery). A seed or entries that didn't read raise
        // its banner instead, and the app goes on.
        if (keyStoreRecovery.check() == com.pingolin.track.util.KeyStoreRecovery.Verdict.SIGN_OUT) {
            keyStoreRecovery.signOut(sessionManager)
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        // A saved account id that isn't a UUID is no account: nothing is kept or read under it, and
        // the session ends, so the next sign-in saves a canonical one (AccountIds).
        if (sessionManager.savedAccountIdInvalid()) {
            sessionManager.triggerForceLogout(com.pingolin.track.util.TrackingStopReason.ACCOUNT_ID_INVALID, "the saved account id isn't a UUID")
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        // If app lock is enabled, clear any restored fragments to prevent
        // ViewModel initialization before authentication completes
        if (sessionManager.isAppLockEnabled()) {
            supportFragmentManager.fragments.forEach { fragment ->
                supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SECURITY: Apply FLAG_SECURE based on user preference (default: enabled)
        // This prevents screenshots and task-switcher thumbnails from exposing location data
        applyScreenshotProtection()

        // The lock check has to precede every other bit of initialization:
        // nothing should touch the network or build a fragment before unlock.
        if (sessionManager.requiresAppLockAuth()) {
            isAppLocked = true
            showLockScreen()
        } else {
            initializeApp()
        }
    }

    /** Initialize the app after authentication (or if no lock is required). */
    private fun initializeApp() {
        // An assisted-lane account an admin hasn't approved yet, or turned down: Track refuses its every
        // call, so its own screen says so, as the web's does, before anything is built or asked.
        if (sessionManager.isRegistrationUnapproved()) {
            com.pingolin.track.ui.start.UnderReviewActivity.show(this)
            return
        }
        binding.lockOverlay.visibility = android.view.View.GONE
        binding.mainContent.visibility = android.view.View.VISIBLE

        checkRootStatus()

        setupNavigation()
        setupNotificationBell()
        applyStatusBarInsets()
        // A help request this account left pending; another account's held here goes.
        runCatching { sosDispatcher.get().resumePending() }
        checkPermissions()
        observeViewModel()
        handleIntent(intent)

        // "Allow all the time" on app open, for someone who shares with a circle.
        // The circles arrive from the database a moment after launch, and every
        // return to the screen looks again; the per-opening flag keeps it to once.
        // flowWithLifecycle rather than repeatOnLifecycle: this runs from
        // initializeApp(), which the app-lock path can reach from onStart, and lint
        // (RepeatOnLifecycleWrongUsage) rejects repeatOnLifecycle there.
        lifecycleScope.launch {
            viewModel.circles
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { circles ->
                    allTheTime.maybeAsk(circles.isNotEmpty() && sessionManager.isTrackingEnabled())
                }
        }

        // Refresh the effective-premium entitlement in the background.
        // SessionManager seeds effectivePremium from prefs, which is false on a
        // fresh login — without a refresh here the premium gates (Share
        // Destination etc.) wall off a genuinely-premium user until some other
        // screen happens to fetch it.
        lifecycleScope.launch {
            try {
                premiumStatusRefresher.refresh()
            } catch (e: Exception) {
                android.util.Log.d("MainActivity", "Premium refresh failed: ${e.message}")
            }
        }

        // Validate session first, then initialize data loading.
        // This prevents API calls from firing with an invalid/expired token,
        // which causes requests without Authorization headers and wasted network calls.
        validateSessionAndInitialize()
    }

    /**
     * Check if the app was updated and force device re-registration if so.
     *
     * After an app update, the FCM push token may change. If a push notification
     * was sent to the old token while the app wasn't running, the backend may have
     * deactivated the device. Re-registering the device activates it again,
     * allowing location streaming to work.
     */
    private fun checkAppVersionAndReregisterDevice() {
        val currentVersionCode = com.pingolin.track.BuildConfig.VERSION_CODE.toLong()
        val wasUpdated = sessionManager.checkAndUpdateAppVersion(currentVersionCode)

        if (wasUpdated && sessionManager.isLoggedIn()) {
            android.util.Log.i("MainActivity", "App was updated, forcing device re-registration")
            lifecycleScope.launch {
                val success = locationRepository.forceDeviceReregistration()
                if (success) {
                    android.util.Log.i("MainActivity", "Device re-registered successfully after update")
                } else {
                    android.util.Log.w("MainActivity", "Device re-registration failed after update")
                }
            }
        }
    }

    /**
     * Self-heal the push-token registration when the device has a saved FCM
     * token that never reached the server.
     *
     * How it arises: on a reinstall, Firebase's onNewToken can fire BEFORE
     * login completes. The handler saves the token but defers the sync ("not
     * logged in"). The post-login device registration then runs, and if it
     * races ahead of the token being readable it creates the device row with a
     * NULL push token — the server then holds no push token for the device at
     * all. The local deviceRegistered flag is true by then, so nothing
     * re-registers and the user silently receives no push notifications. The
     * dedicated push-token PUT can't repair it either: with no token row it
     * 404s (it only UPDATES an existing one). Only a fresh device-registration
     * POST creates the row.
     *
     * So: logged in with a saved token that isn't marked synced ⇒ force a
     * re-registration. By this point the token is reliably readable (unlike the
     * immediate post-login race), so the re-POST carries it. Idempotent — bails
     * once synced, and the backend upsert tolerates a redundant POST if this
     * overlaps the app-update re-registration above.
     */
    private fun reconcilePushTokenRegistration() {
        if (!sessionManager.isLoggedIn()) return
        // Runs before the synced/empty guards below on purpose: BOTH states it
        // repairs are ones those guards treat as "nothing to do" — a device the
        // server has silenced still reads as synced, and an install that lost
        // its local copy reads as having nothing to sync.
        lifecycleScope.launch { reconcilePushRegistrationWithServer() }
        if (sessionManager.isPushTokenSynced()) return
        // Nothing to sync if neither an FCM/APNs token nor a UnifiedPush
        // endpoint has been saved yet (onNewToken hasn't fired).
        if (sessionManager.getPushToken() == null && sessionManager.getPushEndpoint() == null) return

        android.util.Log.i("MainActivity", "Push token saved but not synced — forcing device re-registration")
        lifecycleScope.launch {
            try {
                val ok = locationRepository.forceDeviceReregistration()
                android.util.Log.i("MainActivity", "Push-token reconciliation re-registration: success=$ok")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Push-token reconciliation failed: ${e.message}")
            }
        }
    }

    /**
     * Reconcile this install's push state against the server's, in BOTH
     * directions, on every cold start. One GET; a write only where the two
     * disagree.
     *
     * Each direction is its own way of being wedged, and neither is reachable
     * from the other repair paths:
     *
     *  - **Server has it, we lost it.** A local token/endpoint can go missing
     *    while delivery still works — the distributor keeps its subscription
     *    and the server keeps an active row. The other repair paths stop at
     *    the same "nothing to sync" guard ([reconcilePushTokenRegistration]
     *    and `PushTokenSyncWorker` both return early when token and endpoint
     *    are null), so nothing would refresh the registration and Settings
     *    would report a working channel as unregistered. So the server's copy
     *    is written back locally — only ever FILLING a gap, never overwriting:
     *    local state comes from the distributor or Firebase and is
     *    authoritative when present.
     *
     *  - **We have it, the server does not.** The server deactivates a push
     *    token after three consecutive delivery failures (timeouts count), and
     *    from that moment nothing can address this device. Local state is
     *    untouched and looks perfect, so no other path acts — the two guards
     *    above because `isPushTokenSynced()` is still true (it is set by the
     *    registration that succeeded, and cleared only by logout or an FCM
     *    token rotation), and the restore direction because there is no gap to
     *    fill. Not even a token rotation brings the device back: `PUT
     *    /devices/:id/push-token` UPDATES an existing row and 404s when there
     *    is none. Only a fresh registration POST creates one, so that is what
     *    this does — as the iOS app's `DeviceRegistrar` does, re-POSTing
     *    `/devices` with the token on every launch.
     */
    private suspend fun reconcilePushRegistrationWithServer() {
        val registrations = try {
            val resp = tracksApi.getPushRegistration()
            if (resp.isSuccessful) resp.body()?.data else null
        } catch (_: Exception) {
            // Offline, or a backend without the endpoint. No opinion to act on
            // — this is best-effort repair, and a null must never be read as
            // "the server has nothing", which would re-register on every
            // flaky start.
            null
        } ?: return

        // Same reconciliation the Settings screen displays, so what the user is
        // told and what the app repairs can never diverge.
        val status = com.pingolin.track.util.PushStatusResolver.resolve(
            pushType = sessionManager.getPushType(),
            pushToken = sessionManager.getPushToken(),
            pushEndpoint = sessionManager.getPushEndpoint(),
            serverRegistrations = registrations,
        )

        status.restore?.let { restore ->
            android.util.Log.i(
                "MainActivity",
                "Restoring ${restore.pushType} push registration the server still holds but this install lost"
            )
            sessionManager.restorePushRegistration(restore.pushType, restore.endpoint)
            return
        }

        if (status.needsReregistration) {
            android.util.Log.w(
                "MainActivity",
                "Server holds no push registration for this device — re-registering to recreate it"
            )
            try {
                val ok = locationRepository.forceDeviceReregistration()
                android.util.Log.i("MainActivity", "Push re-registration after server-side loss: success=$ok")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Push re-registration failed: ${e.message}")
            }
        }
    }

    /** Show the lock overlay. No network call or fragment init happens until unlock. */
    private fun showLockScreen() {
        binding.mainContent.visibility = android.view.View.GONE
        binding.lockOverlay.visibility = android.view.View.VISIBLE

        binding.btnUnlock.setOnClickListener {
            showAppLockPrompt()
        }

        binding.lockOverlay.post {
            showAppLockPrompt()
        }
    }

    /**
     * Validate the user session, then initialize data loading if valid.
     *
     * JWTs are stateless - they remain cryptographically valid until expiry even after
     * user deletion. This proactively calls auth/me to verify the user still exists.
     * Only after successful validation do we start API calls (circles, SOS, notifications, etc.)
     * to avoid firing requests with a token that gets cleared mid-flight.
     */
    private fun validateSessionAndInitialize() {
        lifecycleScope.launch {
            val isValid = authRepository.validateSession()
            if (!isValid) {
                android.util.Log.w("MainActivity", "Session validation failed - user may have been deleted")
                authRepository.clearLocalData()
                startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                finish()
                return@launch
            }
            // Waiting for an admin after all, or turned down, though the sign-in couldn't tell:
            // nothing below would work.
            if (sessionManager.isRegistrationUnapproved()) {
                com.pingolin.track.ui.start.UnderReviewActivity.show(this@MainActivity)
                return@launch
            }

            viewModel.initialize()

            // Auto-start location tracking — only if location permission is already granted.
            // On first launch, the permission dialog will fire first (via checkPermissions),
            // and tracking starts after the user grants it.
            val hasLocationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasLocationPermission && !LocationTrackingService.isRunning && !sessionManager.isTrackingPaused()) {
                viewModel.startTracking()
            }

            LocationSyncWorker.schedule(this@MainActivity)
            LocationHeartbeatWorker.schedule(this@MainActivity)
            // Notifications have their own second route. Every mobile push this
            // backend sends is a content-free tickle, so a push that never lands is
            // a notification that never existed — and on the libre flavour, with no
            // distributor installed, no push lands at all. See schedulePolling for
            // why it is armed on both flavours.
            com.pingolin.track.worker.NotificationTickleWorker.schedulePolling(this@MainActivity)
            // Plus the AlarmManager watchdog as a third-line defense
            // against WorkManager ticks being deferred multi-hour by
            // aggressive OEM Doze / battery-optimization customizations.
            com.pingolin.track.receiver.WatchdogAlarmReceiver.schedule(this@MainActivity)

            checkAppVersionAndReregisterDevice()
            reconcilePushTokenRegistration()
            pushNotificationManager.autoDetectAndRegister()
        }
    }

    /** Set the first time the root check is kicked off, so the non-cancelable
     *  warning cannot stack. */
    private var rootCheckStarted = false

    /**
     * Check if device is rooted and warn the user.
     *
     * On rooted devices:
     * - Hardware-backed Keystore may be unavailable
     * - App data can be extracted
     * - Biometric auth can be spoofed
     *
     * We warn rather than block to allow legitimate power users,
     * but ensure they understand the security implications.
     */
    private fun checkRootStatus() {
        // OFF THE MAIN THREAD.
        //
        // RootDetector stats a list of su/Superuser paths, queries the
        // PackageManager for root-management apps, reads system properties,
        // probes writable system paths and looks for Magisk: tens of
        // milliseconds on the main thread (about 36 ms on a mid-range phone),
        // and nothing on the first frame depends on the answer. The dialog is
        // advisory; its own buttons are "I Understand the Risks" and "Logout".
        // Showing it a few frames later costs nothing; blocking the first
        // frame on it would cost that on every launch, on every device, rooted
        // or not.
        //
        // Guarded because initializeApp() has TWO callers — onCreate and the
        // app-lock unlock path — so without it an async check could stack two
        // identical setCancelable(false) dialogs on a lock/unlock cycle.
        if (rootCheckStarted) return
        rootCheckStarted = true
        lifecycleScope.launch {
            val rootResult = withContext(Dispatchers.IO) {
                RootDetector.checkRoot(this@MainActivity)
            }
            // lifecycleScope already cancels on destroy; this also covers the
            // window where the activity is finishing but the job has not been
            // cancelled yet, where show() would throw a BadTokenException.
            if (isFinishing || isDestroyed) return@launch
            if (!rootResult.isRooted) return@launch

            val reasons = rootResult.reasons.joinToString("\n- ", prefix = "- ")
            // this@MainActivity, not `this`: inside launch{} the receiver is the
            // CoroutineScope, which is not a Context.
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Security Warning")
                .setMessage(
                    "This device appears to be rooted or modified:\n\n$reasons\n\n" +
                        "Rooted devices have reduced security protections. " +
                        "Your location data may be at risk.\n\n" +
                        "For maximum security, use an unmodified device."
                )
                .setPositiveButton("I Understand the Risks") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton("Logout") { _, _ ->
                    lifecycleScope.launch {
                        authRepository.clearLocalData()
                        startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                        finish()
                    }
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Only handle intent if app is initialized (not locked)
        if (::navController.isInitialized) {
            handleIntent(intent)
        }
    }

    private fun setupNavigation() {
        // Create NavHostFragment programmatically to prevent premature initialization (If
        // declared in XML with android:name, it would initialize before app lock auth).
        // A rotation, font or theme change brings the activity back with the one it had, its
        // screens and back stack restored: that one is kept. Replacing it on every create
        // would send the person back to the map, with the tab bar pointing elsewhere.
        val restored = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navHostFragment = restored ?: NavHostFragment.create(R.navigation.nav_graph)
        supportFragmentManager.beginTransaction()
            .apply { if (restored == null) replace(R.id.nav_host_fragment, navHostFragment) }
            .setPrimaryNavigationFragment(navHostFragment)
            .commitNow()
        navController = navHostFragment.navController

        // Use custom bottom nav handling to avoid multi-back-stack issues
        // The default setupWithNavController uses saveBackStack/restoreBackStack
        // which can cause crashes when fragments aren't properly isolated
        navBar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.mapFragment -> {
                    navController.navigate(R.id.mapFragment, null, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, inclusive = false, saveState = false)
                        .setLaunchSingleTop(true)
                        .setRestoreState(false)
                        .build())
                    true
                }
                R.id.circlesFragment -> {
                    navController.navigate(R.id.circlesFragment, null, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, inclusive = false, saveState = false)
                        .setLaunchSingleTop(true)
                        .setRestoreState(false)
                        .build())
                    true
                }
                R.id.sosFragment -> {
                    navController.navigate(R.id.sosFragment, null, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, inclusive = false, saveState = false)
                        .setLaunchSingleTop(true)
                        .setRestoreState(false)
                        .build())
                    true
                }
                R.id.placesFragment -> {
                    navController.navigate(R.id.placesFragment, null, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, inclusive = false, saveState = false)
                        .setLaunchSingleTop(true)
                        .setRestoreState(false)
                        .build())
                    true
                }
                R.id.settingsFragment -> {
                    navController.navigate(R.id.settingsFragment, null, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, inclusive = false, saveState = false)
                        .setLaunchSingleTop(true)
                        .setRestoreState(false)
                        .build())
                    true
                }
                else -> false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.mapFragment -> navBar.menu.findItem(R.id.mapFragment)?.isChecked = true
                R.id.circlesFragment -> navBar.menu.findItem(R.id.circlesFragment)?.isChecked = true
                R.id.sosFragment -> navBar.menu.findItem(R.id.sosFragment)?.isChecked = true
                R.id.placesFragment -> navBar.menu.findItem(R.id.placesFragment)?.isChecked = true
                R.id.settingsFragment -> navBar.menu.findItem(R.id.settingsFragment)?.isChecked = true
            }

            val isMainTab = destination.id in setOf(
                R.id.mapFragment,
                R.id.circlesFragment,
                R.id.sosFragment,
                R.id.placesFragment,
                R.id.settingsFragment
            )
            navBar.visibility = if (isMainTab) android.view.View.VISIBLE else android.view.View.GONE

            // Only show the notification bell on the Map tab — it overlays other screens
            val isMapTab = destination.id == R.id.mapFragment
            binding.notificationBellContainer.visibility = if (isMapTab) android.view.View.VISIBLE else android.view.View.GONE

            currentDestinationId = destination.id
            if (isMapTab) {
                updateBellPosition()
            }
        }
    }

    private fun checkPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocation || !coarseLocation) {
            // ASK BEFORE ANDROID ASKS. Signing in must not land straight on
            // the OS location prompt with nothing on screen saying why an
            // app the user has used for ten seconds wants it. The system
            // dialog has room for none of that, and once it is denied twice
            // Android stops showing it at all — so the explanation has to
            // come first or not at all.
            if (sessionManager.hasDeclinedLocationRationale()) {
                // They have already heard it and said no: not asked again at
                // every launch. The map's "Location is off" line asks when
                // tapped, and so does an action that shares location
                // (askForLocation).
                checkNotificationPermission()
                return
            }
            locationAskedAtStartup = true
            showLocationRationale(onNotNow = {
                locationAskedAtStartup = false
                sessionManager.setDeclinedLocationRationale(true)
                checkNotificationPermission()
            })
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundLocation) {
                if (sessionManager.hasDeclinedLocationRationale() || allTheTime.askedThisOpening) {
                    // Asked again when the app is opened, not here in the
                    // first-run chain (AllTheTimeAsker) — and never twice in
                    // one opening.
                    checkNotificationPermission()
                    return
                }
                allTheTime.show {
                    // Foreground location still works; only always-on sharing is
                    // off. Usable, so the app carries on; the question comes back
                    // at the next opening (AllTheTimeAsker).
                    sessionManager.setDeclinedLocationRationale(true)
                    checkNotificationPermission()
                }
            } else {
                checkNotificationPermission()
            }
        } else {
            checkNotificationPermission()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!notificationPermission) {
                // The callback will chain to battery optimization and push delivery
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // Notification permission already granted or not needed; continue chain
        continueStartupChecks()
    }

    /**
     * The tail of the startup permission chain.
     *
     * Motion detection reads TYPE_SIGNIFICANT_MOTION and the accelerometer,
     * which need no permission, so there is nothing to ask for between the
     * notification prompt and these, and the chain runs straight through.
     */
    private fun continueStartupChecks() {
        checkBatteryOptimization()
        checkPushDeliveryAvailable()
    }

    /**
     * Ask, once, to let Track run in the background: on Samsung, Xiaomi, Huawei, OnePlus, Oppo,
     * Vivo and the like, whose battery managers stop background apps, "Continue" here opens
     * Android's own one-tap question, so only where the phone has it (the words say Android asks
     * next). "Not now" (or a dismissal) is kept: never asked again unless sharing really stops
     * (the watchdog asks again then). Settings keeps the way back.
     */
    private fun checkBatteryOptimization() {
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) return
        if (sessionManager.isBatteryOptShown()) return

        // Only show on OEMs known to aggressively kill background apps
        // Stock Android (Google/Pixel), Motorola, Nokia, Sony don't need this
        if (!BatteryOptimizationHelper.isAggressiveOem()) return

        if (!BatteryOptimizationHelper.canAskForExemption(this)) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_exemption_title)
            .setMessage(R.string.background_exemption_body)
            .setPositiveButton(R.string.background_exemption_continue) { dialog, _ ->
                sessionManager.setBatteryOptShown(true)
                dialog.dismiss()
                BatteryOptimizationHelper.requestExemption(this)
            }
            .setNegativeButton(R.string.background_exemption_not_now) { dialog, _ ->
                sessionManager.setBatteryOptShown(true)
                dialog.dismiss()
            }
            .setOnCancelListener { sessionManager.setBatteryOptShown(true) }
            .show()
    }

    /**
     * Tell the user when nothing can deliver their alerts.
     *
     * A Google-free build has no Firebase, so notifications arrive over
     * UnifiedPush through a distributor app the user installs themselves — ntfy
     * and NextPush are the common ones. That is a deliberate design: the
     * distributor is the user's choice and we never see their notifications.
     * But with no distributor installed there is NO inbound path at all, and
     * that includes SOS and place alerts. Saying so only on a settings screen
     * most people never open is not good enough for a safety feature.
     *
     * Deliberately keyed on the resolved provider rather than on the build
     * flavour: a normal build on a device without Play services lands in
     * exactly the same state, and deserves the same warning.
     *
     * Location itself keeps working regardless — LocationHeartbeatWorker and
     * WatchdogAlarmReceiver do not depend on push — so the message is about
     * what you will not RECEIVE, not about tracking being broken.
     */
    private fun checkPushDeliveryAvailable() {
        val provider = pushNotificationManager.getPreferredProvider()
        if (provider != com.pingolin.track.util.PushNotificationManager.PushProvider.NONE) {
            // A delivery path exists (or has reappeared). Re-arm, so that losing
            // it again later is reported rather than silently swallowed.
            if (sessionManager.isPushUnavailableShown()) sessionManager.setPushUnavailableShown(false)
            return
        }
        if (sessionManager.isPushUnavailableShown()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Alerts can't reach this device")
            .setMessage(
                "This build sends notifications through a delivery app of your choice " +
                "instead of Google's. You don't have one installed, so circle " +
                "notifications and place alerts won't arrive until you add one.\n\n" +
                "ntfy is the usual choice and is free. Location sharing keeps working " +
                "either way — this only affects what you receive."
            )
            .setPositiveButton("Install ntfy") { dialog, _ ->
                sessionManager.setPushUnavailableShown(true)
                dialog.dismiss()
                openNtfyListing()
            }
            .setNeutralButton("Push settings") { dialog, _ ->
                sessionManager.setPushUnavailableShown(true)
                dialog.dismiss()
                navController.navigate(R.id.settingsFragment)
            }
            .setNegativeButton("Not now") { dialog, _ ->
                sessionManager.setPushUnavailableShown(true)
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Send the user somewhere they can actually get a distributor.
     *
     * F-Droid first: a Google-free build is very often a Google-free device, so
     * a Play link would dead-end. Falls through Play, then the website, so the
     * button always does something.
     */
    private fun openNtfyListing() {
        val targets = listOf(
            "fdroid://app/io.heckel.ntfy",
            "market://details?id=io.heckel.ntfy",
            "https://f-droid.org/packages/io.heckel.ntfy/",
        )
        for (uri in targets) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                return
            } catch (_: Exception) {
                // No handler for this scheme; try the next.
            }
        }
    }

    /** The note [sayWhenDatabaseUnavailable] put up, while it's up. */
    private var databaseUnavailableNote: androidx.appcompat.app.AlertDialog? = null

    /**
     * A database this phone couldn't open: the screens show nothing rather than the app
     * crashing, and this says why, once, in plain words. Taken down when the database opens:
     * the screens fill in by themselves then.
     */
    private fun sayWhenDatabaseUnavailable() {
        lifecycleScope.launch {
            DatabaseUnavailable.failure
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { failure ->
                    if (failure == null) {
                        databaseUnavailableNote?.dismiss()
                        databaseUnavailableNote = null
                    } else if (DatabaseUnavailable.takeUnsaid()) {
                        databaseUnavailableNote = MaterialAlertDialogBuilder(this@MainActivity)
                            .setMessage(R.string.database_unavailable_note)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
        }
    }

    private fun observeViewModel() {
        sayWhenDatabaseUnavailable()
        // A help request this phone sent reached nobody, or its checks never answered, while Track
        // was in front without the Safety tab's own dialog up: said once, here, over whatever
        // screen it is (as on iOS).
        // Shown again in an activity made again, and on each return, as long as it is untold; taken
        // down when nothing is left to tell (the request ended, or a sign-out).
        lifecycleScope.launch {
            sosDispatcher.get().deliveries.untold
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collectLatest { verdict -> deliveryVerdict.render(this@MainActivity, verdict, isAppLocked) }
        }
        lifecycleScope.launch {
            // STARTED-scoped: activeAlerts re-queries Room on a 60 s ticker
            // (SosRepository.observeActiveAlerts), and a collector bound to
            // the activity's whole life would keep that ticker running while
            // the app sits in the background — the foreground service keeps
            // the process, and so this activity, alive. A badge nobody can
            // see needs no refresh; it is re-read on return.
            //
            // flowWithLifecycle rather than repeatOnLifecycle: this runs from
            // initializeApp(), which the app-lock path can reach from
            // onStart, and lint (RepeatOnLifecycleWrongUsage) rejects
            // repeatOnLifecycle there.
            viewModel.activeAlerts
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collectLatest { alerts ->
                    if (alerts.isNotEmpty()) {
                        navBar.getOrCreateBadge(R.id.sosFragment).apply {
                            isVisible = true
                            number = alerts.size
                        }
                    } else {
                        navBar.removeBadge(R.id.sosFragment)
                    }
                }
        }

        // Observe cross-cutting UX events emitted by repos/services so the user sees
        // a snackbar for things that otherwise fail silently (removed from circle,
        // sharing went stale, invite key didn't arrive).
        lifecycleScope.launch {
            uxEventBus.events.collectLatest { event ->
                val message = when (event) {
                    // Not "You were removed": a deleted circle, or one left on another device, reads the
                    // same here.
                    is com.pingolin.track.util.UxEvent.CircleGone ->
                        getString(R.string.circle_gone, event.circleName)
                    is com.pingolin.track.util.UxEvent.CircleKeyPending ->
                        "You've joined the circle. It opens here as soon as another member's app is online."
                    is com.pingolin.track.util.UxEvent.SendingStale -> {
                        val minutes = (event.ageMillis / 60_000L).coerceAtLeast(1)
                        "Location hasn't been shared in ${minutes}m — check connection"
                    }
                    is com.pingolin.track.util.UxEvent.DecryptionKeyMissing ->
                        "Couldn't decrypt ${event.count} location(s) — try refreshing the circle"
                    is com.pingolin.track.util.UxEvent.HistoryClamped ->
                        "Showing ${event.allowedDays}-day history (your plan limit). Requested ${event.requestedDays} days."
                    is com.pingolin.track.util.UxEvent.HistoryTruncated ->
                        "That range had too much history to show at once — the oldest points were left out. Pick a shorter range."
                    is com.pingolin.track.util.UxEvent.PlaceAlertsTooCoarse ->
                        "Location is too approximate to check ${event.placeCount} of your place" +
                            "${if (event.placeCount == 1) "" else "s"} — turn off battery saver for alerts there"
                }
                com.google.android.material.snackbar.Snackbar.make(
                    binding.mainContent,
                    message,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }

        lifecycleScope.launch {
            viewModel.unreadNotificationCount.collectLatest { count ->
                if (count > 0) {
                    binding.notificationBadge.text = if (count > 99) "99+" else count.toString()
                    binding.notificationBadge.visibility = android.view.View.VISIBLE
                } else {
                    binding.notificationBadge.visibility = android.view.View.GONE
                }
            }
        }

        // forceLogout fires when a token refresh fails or the account is gone.
        lifecycleScope.launch {
            sessionManager.forceLogout.collectLatest {
                android.util.Log.w("MainActivity", "Force logout triggered, redirecting to auth")
                startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                finish()
            }
        }
    }

    private fun setupNotificationBell() {
        binding.fabNotifications.setOnClickListener {
            navController.navigate(R.id.notificationsFragment)
        }
    }

    private fun applyStatusBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.notificationBellContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            statusBarInsetPx = insets.top
            updateBellPosition()
            windowInsets
        }
    }

    private fun updateBellPosition() {
        val margin = resources.getDimensionPixelSize(R.dimen.card_margin_top)
        val extraTop = if (currentDestinationId == R.id.mapFragment || currentDestinationId == 0) {
            0
        } else {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
            TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        }
        placeBell(binding.notificationBellContainer, binding.labelNotifications, topPx = statusBarInsetPx + margin + extraTop, marginPx = margin)
    }

    private fun handleIntent(intent: Intent?) {
        // Tapped the "location sharing has stopped" notification. Opening this
        // activity is itself the fix — onResume re-fires ACTION_START from a
        // foreground context, which is the only start Android reliably grants
        // location capability to. All that is left here is to clear the
        // notification and reset the watchdog's failure count so the next
        // genuine failure gets its full grace again.
        if (intent?.getBooleanExtra(TrackingHealthNotifier.EXTRA_RESUME_TRACKING, false) == true) {
            TrackingHealthNotifier.clear(this)
            sessionManager.clearTrackingRecoveryState()
            intent.removeExtra(TrackingHealthNotifier.EXTRA_RESUME_TRACKING)
        }

        val navigateTo = intent?.getStringExtra("navigate_to")
        when (navigateTo) {
            "sos" -> {
                // Server metadata carries sos_id: with one, jump straight to that
                // alert's marker. It can be absent (older notification, or a
                // sender that omits it), so the Safety tab stays as the fallback.
                val sosId = intent?.getStringExtra("sos_id")
                if (sosId != null) {
                    // One with no position yet has nothing on the map: its Safety row says why.
                    lifecycleScope.launch {
                        if (viewModel.zoomToSos(sosId)) navBar.selectedItemId = R.id.mapFragment
                        else navController.navigate(R.id.sosFragment)
                    }
                } else {
                    navController.navigate(R.id.sosFragment)
                }
            }
            "circles" -> {
                navController.navigate(R.id.circlesFragment)
            }
            // An ownership claim tap has to land ON the circle, not near it. The push it
            // comes from says "Open Pingolin to refuse", and the Refuse control lives in
            // the circle's own screen — stopping at the list would leave the owner hunting
            // for it with a deadline running.
            "circle_ownership_claim" -> {
                val circleId = com.pingolin.track.util.AccountIds.circle(intent?.getStringExtra("circle_id"))
                navController.navigate(R.id.circlesFragment)
                if (!circleId.isNullOrBlank()) {
                    viewModel.requestOwnershipClaimScreen(circleId)
                }
            }
            // A question only this person can answer about who is in a circle:
            // the Circles tab asks it again, so it is on screen.
            com.pingolin.track.util.AndroidMembershipQuestionNotifier.NAVIGATE_TO -> {
                val circleId = com.pingolin.track.util.AccountIds.circle(intent?.getStringExtra("circle_id"))
                navController.navigate(R.id.circlesFragment)
                if (!circleId.isNullOrBlank()) viewModel.requestCircleQuestion(circleId)
            }
            // Destination notifications land on the in-app notifications list —
            // the tapped tray entry has a matching personalised record there.
            // Every navigate_to value AlertHandler emits needs a branch here: an
            // unmatched one falls through silently and just opens the map.
            "notifications" -> {
                navController.navigate(R.id.notificationsFragment)
            }
            "destination" -> {
                // Destination shared / member-arrival tap: jump to the map tab and zoom to
                // the destination's coordinates (captured when the notification was built).
                val lat = intent?.getDoubleExtra("dest_lat", Double.NaN) ?: Double.NaN
                val lng = intent?.getDoubleExtra("dest_lng", Double.NaN) ?: Double.NaN
                if (!lat.isNaN() && !lng.isNaN()) viewModel.requestMapZoom(lat, lng)
                navBar.selectedItemId = R.id.mapFragment
            }
            "destinations" -> {
                navController.navigate(R.id.destinationsFragment)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        // Not told: an activity made again shows it again.
        deliveryVerdict.dismiss()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Nothing of Track in front: a help request that reached nobody is told by notification.
        runCatching { sosDispatcher.get().deliveries.trackInFront = false }
        // Record background time when activity is truly not visible.
        // onStop fires when the user leaves the app (home, recents, another app).
        // It does NOT fire for dialogs (biometric, PIN, permission) — those only
        // trigger onPause/onResume, which is why we use onStop here.
        if (sessionManager.isAppLockEnabled()) {
            sessionManager.recordBackgroundTime()
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotProtection()

        // Stock the anonymous geocode credits before anyone searches. Minting is
        // the expensive half of that flow — a round trip plus 40 VOPRF blinds —
        // so it must not happen lazily inside the first search: that path is
        // cancelled on every keystroke and the user pays for the mint while
        // typing.
        //
        // It belongs on onResume, not Application.onCreate: at process start the
        // session isn't usable yet and the mint comes back 401, burning a request
        // on every cold start and warming nothing. Running it on each foreground
        // is fine — a no-op with no network unless the pool has actually run low.
        if (sessionManager.getUseServerGeocoder()) {
            lifecycleScope.launch {
                runCatching { geocodeTokenManager.get().prewarm() }
            }
        }

        // Re-sync active SOS alerts on every foreground. The FCM tickle path
        // already refreshes them on arrival, and UnifiedPush's through
        // NotificationTickleWorker, but a push can be dropped or arrive while
        // the process is dead. One cheap GET here is
        // what guarantees the map icon appears and — via the stale sweep in
        // refreshActiveAlerts — that a closed one disappears.
        if (sessionManager.isLoggedIn() && !isAppLocked) {
            viewModel.refreshSosAlerts()
            // A restore of pre-reset data opens here without a restart.
            viewModel.recheckPreviousIdentities()
        }
        // Changes waiting to sync look again when the app comes back.
        if (sessionManager.isLoggedIn()) com.pingolin.track.worker.PendingChangesWorker.kick(this)
        // The database's key moves off the old one tied to the screen lock: the open does it, and
        // this tries again should that have failed. A database made again because its key was
        // gone says so, once.
        if (sessionManager.isLoggedIn() && !isAppLocked) {
            lifecycleScope.launch {
                val rebuilt = withContext(Dispatchers.IO) {
                    databasePassphraseManager.moveKeyIfDue()
                    databasePassphraseManager.takeRebuiltNotice()
                }
                if (rebuilt) showDatabaseRebuiltNote()
            }
        }
        // The rules low-battery and inactivity alerts are raised from, fetched again on a
        // return to the app (as iOS does on foreground): at once after a sign-in, an install or
        // a database made again (nothing read yet), and for a rule made on another device. Not
        // on every resume: a turn of the screen or a dialog closing within a few minutes of the
        // last read fetches nothing.
        if (sessionManager.isLoggedIn() && !isAppLocked) {
            lifecycleScope.launch(Dispatchers.IO) {
                alertRulesReload.reloadIfStale(maxAgeMs = com.pingolin.track.domain.repository.AlertRulesReload.RETURN_MAX_AGE_MS)
            }
        }
        // An install that had an older app version's device-wide Pause Tracking switch on stays
        // stopped until every circle is paused on the server ([PauseTrackingMigration]), then
        // says so once; anything short of that tries again on the next return.
        if (sessionManager.isLoggedIn() && !isAppLocked) {
            lifecycleScope.launch {
                if (pauseTrackingMigration.runIfNeeded() == com.pingolin.track.domain.repository.PauseTrackingMigration.Outcome.MOVED) {
                    startTrackingIfPermitted()
                }
                sessionManager.takePauseTrackingMovedNote()?.let { hadCircles -> showPauseTrackingMovedNote(hadCircles) }
            }
        }
        // Re-fire ACTION_START every time the activity returns to the
        // foreground. If LocationTrackingService is already running but was
        // originally started in an FGS-restricted context (an FCM wake,
        // PUSH_MESSAGING reason on Android 14+), Android refuses to deliver
        // location updates to it for the rest of its lifetime. Re-issuing
        // ACTION_START from a foreground activity context gives the service
        // a chance to re-register the fused location callback with full
        // capability — see LocationTrackingService.reattachLocationUpdates.
        // Cheap to call: when not running it bootstraps the service; when
        // running normally it's an idempotent reattach.
        if (sessionManager.isLoggedIn() &&
            !sessionManager.isTrackingPaused() &&
            !isAppLocked &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startTracking()
        }
    }

    override fun onStart() {
        super.onStart()
        // Check app lock when activity becomes visible again.
        // onStart fires when returning from another app/home screen.
        // It does NOT fire when a dialog (biometric/PIN) dismisses — those
        // only trigger onPause/onResume, avoiding infinite re-lock loops.
        val requiresAuth = sessionManager.requiresAppLockAuth()
        if (requiresAuth && !isAppLocked) {
            isAppLocked = true
            binding.mainContent.visibility = android.view.View.GONE
            binding.lockOverlay.visibility = android.view.View.VISIBLE
            showAppLockPrompt()
        }
        // In front, unless behind the app lock, where a dialog would wait unseen: a
        // notification tells it there. And a verdict dialog never sits above the lock: it comes
        // back once unlocked.
        runCatching { sosDispatcher.get().deliveries.trackInFront = !isAppLocked }
        if (isAppLocked) deliveryVerdict.dismiss()
    }

    private fun showAppLockPrompt() {
        when (sessionManager.getAppLockMethod()) {
            SessionManager.APP_LOCK_BIOMETRICS -> showBiometricPrompt()
            SessionManager.APP_LOCK_PIN -> showPinPrompt()
            else -> {
                onUnlockSuccess()
            }
        }
    }

    /** Called when authentication succeeds (biometrics or PIN). */
    private fun onUnlockSuccess() {
        isAppLocked = false
        runCatching {
            val deliveries = sosDispatcher.get().deliveries
            deliveries.trackInFront = true
            deliveryVerdict.render(this, deliveries.untold.value, locked = false)
        }
        sessionManager.setForceAuthOnNextLaunch(false)
        sessionManager.clearBackgroundTime()

        // A hidden mainContent means this is the first unlock after launch and
        // nothing has been initialized yet.
        if (binding.mainContent.visibility == android.view.View.GONE) {
            initializeApp()
        } else {
            binding.lockOverlay.visibility = android.view.View.GONE
            binding.mainContent.visibility = android.view.View.VISIBLE
            // The opening's questions waited behind the lock.
            allTheTime.maybeAsk(viewModel.circles.value.isNotEmpty() && sessionManager.isTrackingEnabled())
            passwordInPlace.maybeAsk()
        }
    }

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            if (sessionManager.getAppLockPinHash() != null) {
                showPinPrompt()
            } else {
                showDeviceCredentialPrompt()
            }
            return
        }

        // SECURITY: Use CryptoObject binding to prevent biometric bypass attacks.
        // The cipher requires genuine biometric authentication before it can be used;
        // hooking the callback alone will not produce a valid CryptoObject.
        val cipher = try {
            keystoreEncryption.getCipherForBiometricAuth()
        } catch (e: Exception) {
            // Key may have been invalidated (e.g., new biometric enrolled).
            // Fall back to PIN if available, otherwise to the phone's screen lock.
            android.util.Log.w("MainActivity", "Failed to get biometric cipher, falling back", e)
            if (sessionManager.getAppLockPinHash() != null) {
                showPinPrompt()
            } else {
                showDeviceCredentialPrompt()
            }
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // Verify that the CryptoObject was actually authenticated
                    if (result.cryptoObject?.cipher != null) {
                        onUnlockSuccess()
                    } else {
                        // CryptoObject missing - possible bypass attempt
                        sessionManager.setForceAuthOnNextLaunch(true)
                        finishAffinity()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    val hasPinFallback = sessionManager.getAppLockPinHash() != null

                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && hasPinFallback) {
                        // "Use PIN" tapped — switch to PIN prompt
                        showPinPrompt()
                    } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        sessionManager.setForceAuthOnNextLaunch(true)
                        finishAffinity()
                    } else if (hasPinFallback) {
                        showPinPrompt()
                    } else {
                        sessionManager.setForceAuthOnNextLaunch(true)
                        finishAffinity()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Allow retry
                }
            })

        val hasPinFallback = sessionManager.getAppLockPinHash() != null
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Track")
            .setSubtitle("Verify your identity to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(if (hasPinFallback) "Use PIN" else "Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, cryptoObject)
    }

    /**
     * Biometrics can't run here (none enrolled any more, or a new fingerprint invalidated their
     * key) and there is no PIN to fall back on (an install whose biometrics were turned on under
     * an older app version has none). The phone's own screen lock stands in, never no check at
     * all. A phone with no screen lock has nothing to ask for, and opens (see askToMendTheLock).
     */
    private fun showDeviceCredentialPrompt() {
        if (getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure != true) {
            onUnlockSuccess()
            // Nothing here can check the person: no PIN, no biometrics that run, no screen lock. It
            // opens, and the lock isn't left broken in silence: a PIN for it, or off.
            askToMendTheLock()
            return
        }
        // The screen lock alone from Android 11; before that the platform offers it only beside a
        // biometric.
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onUnlockSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                sessionManager.setForceAuthOnNextLaunch(true)
                finishAffinity()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Track")
                .setSubtitle("Use your phone's screen lock")
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    /**
     * A biometric lock that can no longer check anyone: set a PIN for it in Settings' App Lock, or
     * turn it off. Asked until one is done; never dismissed.
     */
    private fun askToMendTheLock() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_lock_mend_title)
            .setMessage(R.string.app_lock_mend_body)
            .setCancelable(false)
            .setPositiveButton(R.string.app_lock_mend_set_pin) { _, _ ->
                navBar.selectedItemId = R.id.settingsFragment
            }
            .setNegativeButton(R.string.app_lock_mend_turn_off) { _, _ ->
                sessionManager.clearAppLockPinHash()
                sessionManager.setAppLockMethod(SessionManager.APP_LOCK_NONE)
                Toast.makeText(this, R.string.app_lock_biometrics_disabled, Toast.LENGTH_SHORT).show()
            }
            .show()
    }


    private fun showPinPrompt() {
        // The tries are counted together with Settings': one count, one lockout.
        com.pingolin.track.util.PinAttempts.lockedFor(sessionManager)?.let { remainingSeconds ->
            Toast.makeText(this, com.pingolin.track.util.PinAttempts.lockedOut(remainingSeconds), Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showPinPrompt()
            }, (remainingSeconds * 1000).coerceAtMost(com.pingolin.track.util.PinAttempts.LOCKOUT_MS))
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_input, null)
        val pinInput = dialogView.findViewById<TextInputEditText>(R.id.etPinInput)
        // The PIN reaches accessibility services as dots, as the screen shows it.
        com.pingolin.track.util.SecretFields.keepHidden(dialogView.findViewById(R.id.tilPinInput))

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter PIN")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Unlock") { dialog, _ ->
                val editable = pinInput.text
                val enteredPin = CharArray(editable?.length ?: 0)
                if (editable != null && editable.isNotEmpty()) {
                    editable.getChars(0, editable.length, enteredPin, 0)
                    // Wipe the EditText buffer so the plaintext PIN doesn't linger.
                    editable.clear()
                }
                val outcome = try {
                    com.pingolin.track.util.PinAttempts.check(sessionManager, enteredPin)
                } finally {
                    enteredPin.fill('\u0000')
                }
                if (outcome == com.pingolin.track.util.PinAttempts.Outcome.Right) {
                    dialog.dismiss()
                    onUnlockSuccess()
                } else {
                    Toast.makeText(
                        this,
                        com.pingolin.track.util.PinAttempts.refusal(outcome),
                        if (outcome == com.pingolin.track.util.PinAttempts.Outcome.LockedNow) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                    // Show prompt again (will check lockout)
                    showPinPrompt()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                sessionManager.setForceAuthOnNextLaunch(true)
                finishAffinity()
            }
            .show()
    }

    /**
     * Apply or remove FLAG_SECURE based on user preference.
     * When enabled, prevents screenshots and hides content in task switcher.
     */
    fun applyScreenshotProtection() {
        if (sessionManager.isBlockScreenshotsEnabled()) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

}

/**
 * The floating bell's [container], [topPx] from the top and [marginPx] from the end, and the "Notifications"
 * [label] the map's dot menu shows beside it (the map hides it when it would cover something: fitMenuLabels).
 * Out here so the map's layout test places the bell as the activity does.
 */
internal fun placeBell(container: android.view.View, label: android.view.View, topPx: Int, marginPx: Int) {
    container.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        topMargin = topPx
        marginEnd = marginPx
    }

    // Keep the "Notifications" label (shown when the map dot-menu expands)
    // vertically centered against the bell and to its LEFT, like every
    // other FAB label. The bell container is pushed down by the status-bar
    // inset here, but the label has a static top margin in XML — without
    // this it floats above the bell, half under the status bar.
    //
    // Center against the bell CONTAINER's measured height, not a bare 40dp:
    // the mini FAB's elevation/shadow padding inflates the container to
    // ~56dp, so a 40dp assumption leaves the label sitting ~half a chip
    // too high. measure() works even while both views are GONE.
    val unspec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
    val density = container.resources.displayMetrics.density
    container.measure(unspec, unspec)
    label.measure(unspec, unspec)
    val bellHeight = container.measuredHeight.takeIf { it > 0 }
        ?: (56 * density).toInt()
    val labelCenterOffset = ((bellHeight - label.measuredHeight) / 2).coerceAtLeast(0)
    label.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        topMargin = topPx + labelCenterOffset
        // Sit just left of the bell (container width + its end margin) with an 8dp gap.
        marginEnd = marginPx + container.measuredWidth + (8 * density).toInt()
    }
}
