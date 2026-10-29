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

package com.pingolin.track.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.BuildConfig
import com.pingolin.track.R
import com.pingolin.track.TrackApplication
import com.pingolin.track.databinding.FragmentSettingsBinding
import com.pingolin.track.domain.repository.deletionNotice
import com.pingolin.track.ui.auth.AuthActivity
import com.pingolin.track.ui.main.MainViewModel
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.BatteryOptimizationHelper
import com.pingolin.track.util.BiometricAuthManager
import com.pingolin.track.util.BiometricStatus
import com.pingolin.track.util.CrashReporter
import com.pingolin.track.util.PushNotificationManager
import com.pingolin.track.util.PushStatusResolver
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.TrackingJournalFormatter
import com.pingolin.track.util.plainText
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private val geocodingViewModel: GeocodingViewModel by viewModels()

    @Inject
    lateinit var sessionManager: SessionManager

    /** The sync error, above Logout (KeyStoreRecovery): stored keys that need a sign-in. */
    @Inject
    lateinit var keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    /** The unlock's biometric key, which a proof of the lock is bound to as the unlock is. */
    @Inject
    lateinit var keystoreEncryption: com.pingolin.track.util.KeystoreEncryption

    @Inject
    lateinit var pushNotificationManager: PushNotificationManager

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var tracksApi: com.pingolin.track.data.remote.api.TrackApi

    @Inject
    lateinit var authApi: com.pingolin.track.data.remote.api.AuthApi

    @Inject
    lateinit var cryptoManager: uniffi.pingolin_mobile.CryptoManager

    @Inject
    lateinit var quotaManager: com.pingolin.track.domain.repository.QuotaManager

    @Inject
    lateinit var locationRepository: com.pingolin.track.domain.repository.LocationRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        observeViewModel()
        // The sync error, above Logout, while the stored keys need a sign-in (KeyStoreRecovery).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                keyStoreRecovery.syncBanner.collect { shown ->
                    _binding?.tvSyncError?.visibility = if (shown) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun setupUI() {
        setupPlanCard()

        binding.tvEmail.text = viewModel.userEmail ?: "Unknown"
        binding.tvDisplayName.text = getDisplayNameOrDefault()

        viewModel.fetchSubscriptionTier()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subscriptionTier.collect { tier ->
                binding.tvSubscriptionTier.text = tier
                val badge = binding.tvSubscriptionTier
                // Tint for any premium tier, including circle-derived ("Premium · Circle").
                if (tier != "Free") {
                    val tv = android.util.TypedValue()
                    requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
                    badge.background.setTint(tv.data)
                }
            }
        }

        // The environment badge says which backend this build talks to, which
        // matters on DEV and is noise in a shipping app — next to the Premium
        // chip it reads as a product label rather than a build diagnostic.
        // Hidden on PROD rather than deleted: on DEV, knowing which backend
        // you are signed in to stops an afternoon of debugging the wrong
        // environment.
        if (viewModel.environment == SessionManager.Environment.PROD) {
            binding.tvEnvironment.visibility = View.GONE
        } else {
            binding.tvEnvironment.visibility = View.VISIBLE
            binding.tvEnvironment.text = viewModel.environment.name
        }

        setupDisplayName()
        setupThemeSelection()
        setupAppLock()
        sessionManager.migrateTrackingModel()
        setupTimeBased()
        setupDistanceSending()
        setupBatterySaver()
        setupBackgroundExemption()

        setupRecoveryEmail()

        binding.btnMyKeys.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_my_keys)
        }

        binding.btnManageDevices.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_devices)
        }

        binding.btnBlockedPeople.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_blocked_people)
        }

        setupPushNotifications()
        setupEmailNotificationsToggle()

        binding.btnNotificationCategories.setOnClickListener { showNotificationCategoriesDialog() }
        binding.btnNotificationDetail.setOnClickListener { showNotificationDetailDialog() }

        setupGeocoding()
        setupCrashReporting()

        binding.btnLogout.setOnClickListener {
            showLogoutConfirmation()
        }

        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountConfirmation()
        }

        binding.btnExportData.setOnClickListener {
            showExportDataDialog()
        }

        binding.btnRestoreData.setOnClickListener {
            // Say what to pick, and what happens, BEFORE the
            // system picker takes over the screen: the picker
            // itself says nothing about which file is wanted
            // or what restoring does. Export, one button up,
            // explains itself in a dialog first too.
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.restore_data_title)
                .setMessage(R.string.restore_data_explainer)
                .setPositiveButton(R.string.restore_data_choose) { _, _ ->
                    // Some file pickers hide a .json behind a stricter filter, so
                    // accept any type and let the reader decide — it refuses a
                    // file that isn't a Track backup anyway.
                    openBackupDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.cardContactSupport.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_support)
        }

        binding.cardOpenSourceLicenses.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_licenses)
        }

        // The pages the iPhone's purchase sheet and policy link to.
        binding.cardPrivacyPolicy.setOnClickListener { openUrl(PRIVACY_POLICY_URL) }
        binding.cardTermsOfService.setOnClickListener { openUrl(TERMS_URL) }

        binding.cardTrackingDiagnostics.setOnClickListener {
            showTrackingDiagnostics()
        }
    }

    /**
     * The durable answer to "why did tracking stop?".
     *
     * Logcat's ring buffer rotates past the reason within the hour, so
     * TrackingJournal keeps the last entries in prefs; this is where a person
     * reads them, and copies them out to report a problem.
     *
     * Recording is off until the switch at the top of this dialog turns it on,
     * so the usual state of this screen is an empty journal and an off switch.
     */
    private fun showTrackingDiagnostics() {
        val journal = sessionManager.trackingJournal
        val dialogBinding = com.pingolin.track.databinding
            .DialogTrackingDiagnosticsBinding.inflate(layoutInflater)

        // Rendered from a function rather than once, because the switch changes
        // what an empty journal MEANS: "no tracking events recorded yet" under
        // an off switch reads as "tracking never stopped", which is the one
        // conclusion the journal must never invite.
        fun body(): String {
            val entries = journal.entries()
            val stamp = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
            return when {
                entries.isNotEmpty() ->
                    TrackingJournalFormatter.format(entries) { wallMs ->
                        stamp.format(java.util.Date(wallMs))
                    }
                journal.isRecordingEnabled() -> getString(R.string.tracking_diagnostics_empty)
                else -> getString(R.string.tracking_diagnostics_off)
            }
        }

        dialogBinding.textTrackingDiagnosticsBody.text = body()
        // State first, listener second: assigning isChecked fires the listener,
        // so the other order writes the pref back on every open.
        dialogBinding.switchTrackingDiagnosticsEnabled.isChecked = journal.isRecordingEnabled()
        dialogBinding.switchTrackingDiagnosticsEnabled.setOnCheckedChangeListener { _, checked ->
            journal.setRecordingEnabled(checked)
            dialogBinding.textTrackingDiagnosticsBody.text = body()
        }

        // A custom view, so the journal needs its own
        // ScrollView — see the layout. It goes on the
        // BUILDER: an AlertDialog only inflates the
        // panels that exist at create() time, so setting
        // one afterwards silently does nothing.
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tracking_diagnostics_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.tracking_diagnostics_copy) { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Track diagnostics",
                        dialogBinding.textTrackingDiagnosticsBody.text.toString(),
                    )
                )
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.tracking_diagnostics_copied,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    /**
     * The Account recovery row: say honestly whether a recovery address exists,
     * and hand off to the portal to change it.
     *
     * Deliberately read-only. Adding, verifying and removing a recovery address
     * all stay in the web account portal, which is the one place that
     * implements anything touching a password reset. Duplicating that here
     * would put another implementation of the same reset semantics on the
     * network — the failure mode is not a broken screen, it is an account that
     * cannot be recovered.
     */
    /// Status first, then what a recovery address is for.
    ///
    /// Status leads because it is the question someone opens this to answer —
    /// the explanation is the same every time, the state is not. The colours:
    /// red for none, amber for pending (the user did the right thing and has
    /// one step left), green for verified. Rendering pending as an error would
    /// make the two indistinguishable at a glance.
    private fun recoveryInfoMessage(): CharSequence {
        val status = viewModel.recoveryEmailStatus.value
        val (text, colour) = when (status) {
            is AccountRecovery.Status.Unknown ->
                getString(R.string.recovery_email_status_unknown) to
                    MaterialColors.getColor(
                        requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
            is AccountRecovery.Status.NotSet ->
                getString(R.string.recovery_email_status_none) to
                    // colorError lives in appcompat, not material.
                    MaterialColors.getColor(requireView(), androidx.appcompat.R.attr.colorError)
            is AccountRecovery.Status.Pending ->
                getString(R.string.recovery_email_status_pending, status.address) to
                    ContextCompat.getColor(requireContext(), R.color.warning)
            is AccountRecovery.Status.Verified ->
                getString(R.string.recovery_email_status_verified, status.address) to
                    ContextCompat.getColor(requireContext(), R.color.secondary)
        }

        val body = getString(R.string.recovery_email_desc)
        val out = android.text.SpannableStringBuilder()
            .append(text)
            .append("\n\n")
            .append(body)
        out.setSpan(
            android.text.style.ForegroundColorSpan(colour),
            0,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return out
    }

    private fun setupRecoveryEmail() {
        binding.btnManageRecoveryEmail.setOnClickListener {
            openUrl(AccountRecovery.recoverySettingsUrl(BuildConfig.ACCOUNT_BASE_URL))
        }

        // The explanation sits behind this button, and the STATUS with it. The
        // row is one action plus one question mark; the dialog answers both
        // "what is this" and "do I have one".
        binding.btnRecoveryEmailInfo.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.recovery_email_title)
                // Set on the BUILDER. Setting it on the created dialog renders
                // an empty body.
                .setMessage(recoveryInfoMessage())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Same hand-off the sign-up screen's "Request a reviewed account" link uses. */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportDataDialog() {
        val dialogBinding = com.pingolin.track.databinding.DialogExportDataBinding.inflate(layoutInflater)
        // The passphrase reaches accessibility services as dots, as the screen shows it.
        com.pingolin.track.util.SecretFields.keepHidden(dialogBinding.exportPassphraseLayout)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export My Data")
            .setView(dialogBinding.root)
            .setPositiveButton("Export") { _, _ ->
                // Determine date range
                val daysBack = when {
                    dialogBinding.chip7Days.isChecked -> 7
                    dialogBinding.chip30Days.isChecked -> 30
                    dialogBinding.chip90Days.isChecked -> 90
                    dialogBinding.chip6Months.isChecked -> 180
                    dialogBinding.chip1Year.isChecked -> 365
                    else -> 30
                }

                pendingExportPassphrase = dialogBinding.exportPassphrase.text?.toString()
                exportData(
                    daysBack = daysBack,
                    includeDevices = dialogBinding.checkboxDevices.isChecked,
                    includeCircles = dialogBinding.checkboxCircles.isChecked,
                    includePlaces = dialogBinding.checkboxPlaces.isChecked,
                    includeCheckIns = dialogBinding.checkboxCheckIns.isChecked,
                    includeLocations = dialogBinding.checkboxLocations.isChecked
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportData(
        daysBack: Int = 30,
        includeDevices: Boolean = true,
        includeCircles: Boolean = true,
        includePlaces: Boolean = true,
        includeCheckIns: Boolean = true,
        includeLocations: Boolean = true
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnExportData.isEnabled = false

                val result = viewModel.exportData(
                    daysBack = daysBack,
                    includeDevices = includeDevices,
                    includeCircles = includeCircles,
                    includePlaces = includePlaces,
                    includeCheckIns = includeCheckIns,
                    includeLocations = includeLocations
                )
                result.onSuccess { export ->
                    // Hold the body until the user has
                    // picked a destination. The export is
                    // only "successful" once the bytes are
                    // somewhere the user chose.
                    // Seal it if the user asked. The SDK
                    // owns the format so every platform
                    // writes the same container; a blank
                    // passphrase keeps the plaintext file
                    // that makes an export useful for
                    // portability.
                    val pass = pendingExportPassphrase
                    pendingExportBody = if (pass.isNullOrEmpty()) {
                        export.fileBody
                    } else {
                        try {
                            uniffi.pingolin_mobile.sealBackupFile(
                                export.fileBody,
                                pass,
                                "pingolin-track",
                                java.time.Instant.now().toString(),
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("SettingsFragment", "Export not sealed: ${e.javaClass.simpleName}: ${e.message}")
                            Toast.makeText(requireContext(),
                                "Couldn't prepare the export just now.", Toast.LENGTH_LONG).show()
                            return@onSuccess
                        }
                    }
                    pendingExportSummary = export
                    createBackupDocument.launch(
                        "track-backup-${java.time.LocalDate.now()}.json"
                    )
                }.onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        e.plainText("Couldn't export your data just now."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnExportData.isEnabled = true
            }
        }
    }

    /**
     * The file body, held between building the export and the user choosing
     * where it goes. It is every place and every point in plaintext, so it is
     * dropped as soon as it has been written or the picker is cancelled.
     */
    private var pendingExportBody: String? = null
    private var pendingExportSummary: ExportResult? = null

    private val createBackupDocument =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val body = pendingExportBody
            val summary = pendingExportSummary
            pendingExportBody = null
            pendingExportSummary = null
            if (uri == null || body == null) return@registerForActivityResult
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(body.toByteArray())
                } ?: throw java.io.IOException("Couldn't open that location for writing")
                Toast.makeText(
                    requireContext(),
                    "Backup saved (${summary?.totalRecords ?: 0} records)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.util.Log.w("SettingsFragment", "Export not saved: ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(requireContext(), "Couldn't save the file there. Please try another place.", Toast.LENGTH_LONG).show()
            }
        }

    private val openBackupDocument =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            restoreFrom(uri)
        }

    /**
     * Restore reads the file first and only then writes anything, so a file
     * that is not a Track backup is refused before it can half-restore an
     * account.
     */
    private fun restoreFrom(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnRestoreData.isEnabled = false

                val text = try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        requireContext().contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
                if (text == null) {
                    Toast.makeText(requireContext(), "Couldn't read that file", Toast.LENGTH_LONG).show()
                    return@launch
                }

                restoreWithPassphraseIfNeeded(text, passphrase = null)
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnRestoreData.isEnabled = true
            }
        }
    }

    /**
     * A dialog, not a Toast: the notes say what did NOT come back, and that is
     * the half a user has to read.
     */
    private fun showRestoreSummary(summary: com.pingolin.track.domain.backup.RestoreRepository.Summary) {
        val lines = mutableListOf<String>()
        if (summary.placesRestored > 0) lines.add("${summary.placesRestored} place(s) restored")
        if (summary.placesAlreadyPresent > 0) lines.add("${summary.placesAlreadyPresent} place(s) were already there")
        if (summary.privacyRestored > 0) lines.add("${summary.privacyRestored} circle privacy setting(s) applied")
        if (summary.preferencesRestored) lines.add("Notification preferences applied")
        if (summary.alertsRestored > 0) lines.add("${summary.alertsRestored} place alert(s) restored")
        if (lines.isEmpty()) lines.add("Nothing new to restore — everything in that backup is already here.")
        if (summary.notes.isNotEmpty()) {
            lines.add("")
            lines.addAll(summary.notes)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore complete")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    /** Plan/usage card at the top of Settings (mirrors the Note app). Shows
     * the current tier and, for free users, live usage bars (Places,
     * Circles, Place alerts) so limits are visible before a 402. The `(i)`
     * button opens the shared [TrackPremiumBottomSheet]. Refresh-on-open so
     * the numbers reflect changes since the last foreground. No upgrade
     * button — plan management is out-of-band. */
    private fun setupPlanCard() {
        binding.btnPlanInfo.setOnClickListener {
            com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet.show(
                parentFragmentManager, resource = "overview",
            )
        }
        quotaManager.refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            quotaManager.quota.collect { q -> renderPlanCard(q) }
        }
    }

    private fun renderPlanCard(q: com.pingolin.track.data.remote.dto.QuotaDto?) {
        if (q == null) {
            // First render before /quota lands. Show the neutral free framing
            // instead of flashing Premium — the safe default until the server
            // confirms a paid tier.
            binding.tvPlanTier.text = getString(R.string.plan_tier_free)
            binding.layoutPlanUsage.visibility = View.GONE
            binding.tvPlanPaidSummary.visibility = View.GONE
            binding.tvPlanHistory.visibility = View.GONE
            binding.tvPlanExpiryNote.visibility = View.GONE
            return
        }
        // The /quota endpoint sends tier "premium" | "free"; treat any non-"free"
        // tier as paid so a naming skew ("premium" vs "paid") can't mislabel a
        // premium user as Free on the plan card.
        val paid = q.tier != com.pingolin.track.domain.repository.QuotaManager.TIER_FREE
        // "premium" covers both a real subscription and one borrowed from a circle
        // member, so `source` is what separates them. Worth the extra label: a
        // borrowed plan can disappear because someone ELSE cancelled or left, and a
        // card that just said "Premium" would make that look like an error.
        val borrowed = paid && q.source == SOURCE_CIRCLE
        binding.tvPlanTier.text = getString(
            when {
                borrowed -> R.string.plan_tier_premium_circle
                paid -> R.string.plan_tier_premium
                else -> R.string.plan_tier_free
            }
        )

        // History on both tiers: it is the entitlement people actually feel when
        // premium arrives or ends, and the only one whose loss destroys data.
        binding.tvPlanHistory.visibility = View.VISIBLE
        binding.tvPlanHistory.text =
            getString(R.string.plan_history_days, q.historyDays.toInt())

        // Countdown only while the server says one is running, and worded for
        // whichever subject it is running against — a circle's history or this
        // user's own. Both decisions belong to PlanCardCopy: absent when premium
        // is active and when there was never premium to lose, so this never nags a
        // plain free user, and null there means hide the note.
        val expiryNote = PlanCardCopy.historyExpiring(resources, q)
        if (expiryNote != null) {
            binding.tvPlanExpiryNote.visibility = View.VISIBLE
            binding.tvPlanExpiryNote.text = expiryNote
        } else {
            binding.tvPlanExpiryNote.visibility = View.GONE
        }
        binding.layoutPlanUsage.visibility = if (paid) View.GONE else View.VISIBLE
        binding.tvPlanPaidSummary.visibility = if (paid) View.VISIBLE else View.GONE
        if (!paid) {
            bindUsageRow(binding.tvPlanPlacesUsage, binding.progressPlanPlaces, "Places", q.currentPlaces, q.maxPlaces)
            bindUsageRow(binding.tvPlanCirclesUsage, binding.progressPlanCircles, "Circles", q.currentCircles, q.maxCircles)
            bindUsageRow(binding.tvPlanAlertsUsage, binding.progressPlanAlerts, "Place alerts", q.currentPlaceAlerts, q.maxPlaceAlerts)
            bindUsageRow(binding.tvPlanDestinationsUsage, binding.progressPlanDestinations, "Destinations", q.currentDestinations, q.maxDestinations)
        }
    }

    private fun bindUsageRow(
        label: android.widget.TextView,
        bar: com.google.android.material.progressindicator.LinearProgressIndicator,
        name: String,
        current: Long,
        max: Long,
    ) {
        // max == -1 means unlimited (premium); guard it even though usage rows
        // are hidden for paid tiers.
        if (max < 0) {
            label.text = "$name  $current"
            bar.visibility = View.GONE
            return
        }
        val cap = max.toInt().coerceAtLeast(1)
        label.text = "$name  $current / $max"
        bar.visibility = View.VISIBLE
        bar.max = cap
        bar.setProgressCompat(current.toInt().coerceIn(0, cap), true)
    }

    private fun setupThemeSelection() {
        when (sessionManager.getThemeMode()) {
            SessionManager.THEME_LIGHT -> binding.chipThemeLight.isChecked = true
            SessionManager.THEME_DARK -> binding.chipThemeDark.isChecked = true
            else -> binding.chipThemeSystem.isChecked = true
        }

        // Distance units. Reflect the CURRENT value first — getDistanceUnit()
        // falls back to the device region when unset, so the chip must show
        // what the app is actually doing, not an arbitrary default.
        binding.chipGroupDistanceUnit.check(
            if (sessionManager.useMiles()) R.id.chipUnitMi else R.id.chipUnitKm
        )
        binding.chipGroupDistanceUnit.setOnCheckedStateChangeListener { _, checkedIds ->
            sessionManager.setDistanceUnit(
                if (checkedIds.contains(R.id.chipUnitMi)) {
                    SessionManager.UNIT_MI
                } else {
                    SessionManager.UNIT_KM
                }
            )
        }

        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds ->
            val themeMode = when {
                checkedIds.contains(R.id.chipThemeLight) -> SessionManager.THEME_LIGHT
                checkedIds.contains(R.id.chipThemeDark) -> SessionManager.THEME_DARK
                else -> SessionManager.THEME_SYSTEM
            }
            sessionManager.setThemeMode(themeMode)
            TrackApplication.applyThemeMode(themeMode)
        }
    }

    private fun setupDisplayName() {
        binding.layoutDisplayName.setOnClickListener {
            showEditDisplayNameDialog()
        }
    }

    /** The display name, falling back to the email prefix and then to a prompt. */
    private fun getDisplayNameOrDefault(): String {
        val displayName = viewModel.userDisplayName
        if (!displayName.isNullOrBlank()) {
            return displayName
        }

        val email = viewModel.userEmail
        if (!email.isNullOrBlank() && email.contains("@")) {
            return email.substringBefore("@")
        }

        return "Tap to set display name"
    }

    private fun showEditDisplayNameDialog() {
        val currentName = viewModel.userDisplayName ?: ""

        val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(currentName)
            hint = "Display name"
            setSingleLine(true)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit Display Name")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text?.toString()?.trim()
                if (!newName.isNullOrEmpty() && newName != currentName) {
                    viewModel.updateDisplayName(newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupAppLock() {
        val biometricStatus = biometricAuthManager.canAuthenticate()
        val isBiometricAvailable = biometricStatus == BiometricStatus.Available

        // Can be turned on only with a strong biometric enrolled; can always be turned off.
        fun showBiometricSwitchUsable() {
            binding.switchBiometric.isEnabled =
                isBiometricAvailable || sessionManager.getAppLockMethod() == SessionManager.APP_LOCK_BIOMETRICS
        }
        showBiometricSwitchUsable()

        if (!isBiometricAvailable) {
            binding.tvBiometricStatus.text = when (biometricStatus) {
                BiometricStatus.NoHardware -> getString(R.string.app_lock_no_hardware)
                BiometricStatus.NoneEnrolled -> getString(R.string.app_lock_none_enrolled)
                BiometricStatus.HardwareUnavailable -> getString(R.string.app_lock_hardware_unavailable)
                else -> getString(R.string.app_lock_biometrics_unavailable)
            }
        }

        updateAutoLockButtonText()

        // A PIN alone, or biometrics with the PIN to fall back on: a change waits
        // for its proof, and a cancel leaves the lock as it was.
        AppLockSwitches(
            pinSwitch = binding.switchPinLock,
            biometricSwitch = binding.switchBiometric,
            lock = object : AppLockSwitches.Lock {
                override val method get() = sessionManager.getAppLockMethod()
                override val pinHash get() = sessionManager.getAppLockPinHash()
                override fun set(method: String, pinHash: String?) {
                    if (pinHash == null) sessionManager.clearAppLockPinHash() else sessionManager.setAppLockPinHash(pinHash)
                    sessionManager.setAppLockMethod(method)
                }
            },
            askPin = ::showSetPinDialog,
            askBackupPin = ::showSetPinForBiometricDialog,
            proveBiometrics = { onProven, onRefused ->
                biometricAuthManager.authenticate(
                    activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                    title = "Enable Biometric Lock",
                    subtitle = "Verify your identity to enable biometric unlock",
                    // The biometric the unlock will ask for, not the screen lock.
                    allowDeviceCredential = false,
                    onSuccess = onProven,
                    onError = { errorCode, _ ->
                        onRefused()
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            context?.let { Toast.makeText(it, "Authentication failed", Toast.LENGTH_SHORT).show() }
                        }
                    }
                )
            },
            proveCurrent = ::proveCurrentLock,
            say = { id -> context?.let { Toast.makeText(it, getString(id), Toast.LENGTH_SHORT).show() } },
            shown = {
                updateAutoLockVisibility()
                showBiometricSwitchUsable()
            },
        ).bind()

        binding.btnAutoLockTimeout.setOnClickListener {
            showAutoLockTimeoutDialog()
        }

        // Screenshot protection toggle (default: enabled/blocked)
        binding.switchBlockScreenshots.isChecked = sessionManager.isBlockScreenshotsEnabled()
        binding.switchBlockScreenshots.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setBlockScreenshots(isChecked)
            // FLAG_SECURE is per-window, so the hosting activity has to re-apply it
            // now; the preference alone changes nothing until the next launch.
            (requireActivity() as? com.pingolin.track.ui.main.MainActivity)?.applyScreenshotProtection()
            val message = if (isChecked) "Screenshots blocked" else "Screenshots allowed"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoLockVisibility() {
        val isLockEnabled = sessionManager.isAppLockEnabled()
        binding.layoutAutoLock.visibility = if (isLockEnabled) View.VISIBLE else View.GONE
    }

    private fun updateAutoLockButtonText() {
        val timeout = sessionManager.getAutoLockTimeout()
        binding.btnAutoLockTimeout.text = when (timeout) {
            SessionManager.AUTO_LOCK_IMMEDIATELY -> getString(R.string.app_lock_immediately)
            SessionManager.AUTO_LOCK_1_MIN -> getString(R.string.app_lock_1_min)
            SessionManager.AUTO_LOCK_2_MIN -> getString(R.string.app_lock_2_min)
            SessionManager.AUTO_LOCK_3_MIN -> "After 3 minutes"
            SessionManager.AUTO_LOCK_5_MIN -> getString(R.string.app_lock_5_min)
            SessionManager.AUTO_LOCK_10_MIN -> getString(R.string.app_lock_10_min)
            SessionManager.AUTO_LOCK_15_MIN -> getString(R.string.app_lock_15_min)
            SessionManager.AUTO_LOCK_30_MIN -> getString(R.string.app_lock_30_min)
            SessionManager.AUTO_LOCK_60_MIN -> getString(R.string.app_lock_60_min)
            else -> "After 3 minutes"
        }
    }

    /** A new PIN and its confirmation: [onSet] with its hash, or [onCancel] (AppLockSwitches). */
    private fun showSetPinDialog(onSet: (String) -> Unit, onCancel: () -> Unit) {
        val pinEditText = com.pingolin.track.util.SecretFields.pin(requireContext(), 4).apply {
            hint = getString(R.string.app_lock_enter_pin)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(pinEditText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_enter_pin))
            .setView(container)
            .setPositiveButton("Next") { _, _ ->
                val pin = readAndClearPin(pinEditText)
                if (pin.size == 4) {
                    showConfirmPinDialog(pin, onSet, onCancel)
                } else {
                    pin.fill('\u0000')
                    onCancel()
                    Toast.makeText(requireContext(), "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(pinEditText).fill('\u0000')
                onCancel()
            }
            .setOnCancelListener {
                readAndClearPin(pinEditText).fill('\u0000')
                onCancel()
            }
            .show()
    }

    /** PIN setup as a prerequisite for biometric lock: [onSet] with its hash goes on to biometric
     *  verification, which keeps it only once proven (AppLockSwitches). */
    private fun showSetPinForBiometricDialog(onSet: (String) -> Unit, onCancel: () -> Unit) {
        val pinEditText = com.pingolin.track.util.SecretFields.pin(requireContext(), 4).apply {
            hint = getString(R.string.app_lock_enter_pin)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(pinEditText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Backup PIN")
            .setMessage("A 4-digit PIN is required as a backup in case biometrics aren't available (e.g., new fingerprint enrolled or sensor failure).")
            .setView(container)
            .setPositiveButton("Next") { _, _ ->
                val pin = readAndClearPin(pinEditText)
                if (pin.size == 4) {
                    showConfirmPinForBiometricDialog(pin, onSet, onCancel)
                } else {
                    pin.fill('\u0000')
                    onCancel()
                    Toast.makeText(requireContext(), "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(pinEditText).fill('\u0000')
                onCancel()
            }
            .setOnCancelListener {
                readAndClearPin(pinEditText).fill('\u0000')
                onCancel()
            }
            .show()
    }

    /**
     * Confirm the backup PIN; biometric verification follows, and only it keeps the PIN.
     */
    private fun showConfirmPinForBiometricDialog(originalPin: CharArray, onSet: (String) -> Unit, onCancel: () -> Unit) {
        val confirmPinEditText = com.pingolin.track.util.SecretFields.pin(requireContext(), 4).apply {
            hint = getString(R.string.app_lock_confirm_pin)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(confirmPinEditText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_confirm_pin))
            .setView(container)
            .setPositiveButton("Confirm") { _, _ ->
                val confirmPin = readAndClearPin(confirmPinEditText)
                try {
                    if (charArraysEqual(confirmPin, originalPin)) {
                        onSet(hashPin(originalPin))
                    } else {
                        onCancel()
                        Toast.makeText(requireContext(), getString(R.string.app_lock_pin_mismatch), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    confirmPin.fill('\u0000')
                    originalPin.fill('\u0000')
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
                onCancel()
            }
            .setOnCancelListener {
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
                onCancel()
            }
            .show()
    }

    private fun showConfirmPinDialog(originalPin: CharArray, onSet: (String) -> Unit, onCancel: () -> Unit) {
        val confirmPinEditText = com.pingolin.track.util.SecretFields.pin(requireContext(), 4).apply {
            hint = getString(R.string.app_lock_confirm_pin)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(confirmPinEditText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_confirm_pin))
            .setView(container)
            .setPositiveButton("Confirm") { _, _ ->
                val confirmPin = readAndClearPin(confirmPinEditText)
                try {
                    if (charArraysEqual(confirmPin, originalPin)) {
                        onSet(hashPin(originalPin))
                    } else {
                        onCancel()
                        Toast.makeText(requireContext(), getString(R.string.app_lock_pin_mismatch), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    confirmPin.fill('\u0000')
                    originalPin.fill('\u0000')
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
                onCancel()
            }
            .setOnCancelListener {
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
                onCancel()
            }
            .show()
    }

    private fun hashPin(pin: CharArray): String {
        // Use PBKDF2 with random salt (see PinHasher for details)
        return com.pingolin.track.util.PinHasher.hashPin(pin)
    }

    /**
     * The lock as it is now, satisfied before it is turned off or replaced, as the unlock checks it
     * ([AppLockSwitches.proofFor]): its biometrics, bound to the unlock's key; its PIN, the tries counted
     * with the unlock's (PinAttempts: one count, one lockout); the phone's screen lock; or, for a lock
     * none of them can check, nothing, as the unlock opens. Once this screen is gone, nothing changes.
     */
    private fun proveCurrentLock(change: AppLockSwitches.Change, onProven: () -> Unit, onRefused: () -> Unit) {
        val proven = { if (_binding != null) onProven() }
        val refused = { if (_binding != null) onRefused() }
        val method = sessionManager.getAppLockMethod()
        val hasPin = sessionManager.getAppLockPinHash() != null
        // The unlock's key: none where biometrics can't run, or once a fingerprint added since retired it.
        val cipher = if (method == SessionManager.APP_LOCK_BIOMETRICS && biometricAuthManager.canAuthenticate() == BiometricStatus.Available) {
            runCatching { keystoreEncryption.getCipherForBiometricAuth() }.getOrNull()
        } else {
            null
        }
        val screenLock = requireContext().getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        when (AppLockSwitches.proofFor(method, hasPin, biometricsRun = cipher != null, screenLock = screenLock)) {
            AppLockSwitches.Proof.BIOMETRICS -> biometricAuthManager.authenticate(
                activity = requireActivity(),
                title = getString(proveTitle(change)),
                subtitle = getString(R.string.app_lock_prove_subtitle),
                negativeButtonText = getString(if (hasPin) R.string.app_lock_prove_use_pin else android.R.string.cancel),
                allowDeviceCredential = false,
                cryptoObject = BiometricPrompt.CryptoObject(checkNotNull(cipher)),
                onSuccess = proven,
                // As at the unlock: a cancel is a no; any other end goes to the PIN, where there is one.
                onError = { errorCode, _ ->
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && hasPin && _binding != null) {
                        askCurrentPin(change, proven, refused)
                    } else {
                        refused()
                    }
                },
            )
            AppLockSwitches.Proof.PIN -> askCurrentPin(change, proven, refused)
            AppLockSwitches.Proof.SCREEN_LOCK -> proveScreenLock(change, proven, refused)
            AppLockSwitches.Proof.NOTHING -> proven()
        }
    }

    private fun proveTitle(change: AppLockSwitches.Change) =
        if (change == AppLockSwitches.Change.TURN_OFF) R.string.app_lock_prove_off_title else R.string.app_lock_prove_change_title

    /** The phone's screen lock, as the unlock asks for it (MainActivity.showDeviceCredentialPrompt). */
    private fun proveScreenLock(change: AppLockSwitches.Change, onProven: () -> Unit, onRefused: () -> Unit) {
        val allowed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(requireContext()), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onProven()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onRefused()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(proveTitle(change)))
                .setSubtitle(getString(R.string.app_lock_prove_screen_lock_subtitle))
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    /** The lock's PIN, counted with the unlock's tries; a lockout refuses at once. */
    private fun askCurrentPin(change: AppLockSwitches.Change, onProven: () -> Unit, onRefused: () -> Unit) {
        com.pingolin.track.util.PinAttempts.lockedFor(sessionManager)?.let { seconds ->
            context?.let { Toast.makeText(it, com.pingolin.track.util.PinAttempts.lockedOut(seconds), Toast.LENGTH_LONG).show() }
            onRefused()
            return
        }
        val pinEditText = com.pingolin.track.util.SecretFields.pin(requireContext(), 4).apply {
            hint = getString(R.string.app_lock_enter_pin)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(pinEditText)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_lock_prove_pin_title)
            .setMessage(
                when (change) {
                    AppLockSwitches.Change.TURN_OFF -> R.string.app_lock_prove_off_pin_body
                    AppLockSwitches.Change.REPLACE -> R.string.app_lock_prove_change_pin_body
                    AppLockSwitches.Change.ADD_BIOMETRICS -> R.string.app_lock_prove_add_biometrics_pin_body
                    AppLockSwitches.Change.LENGTHEN_AUTO_LOCK -> R.string.app_lock_prove_auto_lock_pin_body
                }
            )
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = readAndClearPin(pinEditText)
                val outcome = try {
                    com.pingolin.track.util.PinAttempts.check(sessionManager, pin)
                } finally {
                    pin.fill('\u0000')
                }
                if (outcome == com.pingolin.track.util.PinAttempts.Outcome.Right) {
                    onProven()
                } else {
                    val long = outcome == com.pingolin.track.util.PinAttempts.Outcome.LockedNow
                    context?.let { Toast.makeText(it, com.pingolin.track.util.PinAttempts.refusal(outcome), if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
                    onRefused()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                readAndClearPin(pinEditText).fill('\u0000')
                onRefused()
            }
            .setOnCancelListener {
                readAndClearPin(pinEditText).fill('\u0000')
                onRefused()
            }
            .show()
    }

    /**
     * Copies the EditText's current contents into a fresh CharArray and wipes
     * the underlying Editable so the plaintext PIN doesn't sit on the heap.
     * Callers MUST wipe the returned CharArray after use.
     */
    private fun readAndClearPin(editText: com.google.android.material.textfield.TextInputEditText): CharArray {
        val editable = editText.text ?: return CharArray(0)
        val length = editable.length
        if (length == 0) return CharArray(0)
        val out = CharArray(length)
        editable.getChars(0, length, out, 0)
        editable.clear()
        return out
    }

    private fun charArraysEqual(a: CharArray, b: CharArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun showAutoLockTimeoutDialog() {
        val options = arrayOf(
            getString(R.string.app_lock_immediately),
            getString(R.string.app_lock_1_min),
            getString(R.string.app_lock_2_min),
            "After 3 minutes",
            getString(R.string.app_lock_5_min),
            getString(R.string.app_lock_10_min),
            getString(R.string.app_lock_15_min),
            getString(R.string.app_lock_30_min),
            getString(R.string.app_lock_60_min)
        )
        val timeouts = arrayOf(
            SessionManager.AUTO_LOCK_IMMEDIATELY,
            SessionManager.AUTO_LOCK_1_MIN,
            SessionManager.AUTO_LOCK_2_MIN,
            SessionManager.AUTO_LOCK_3_MIN,
            SessionManager.AUTO_LOCK_5_MIN,
            SessionManager.AUTO_LOCK_10_MIN,
            SessionManager.AUTO_LOCK_15_MIN,
            SessionManager.AUTO_LOCK_30_MIN,
            SessionManager.AUTO_LOCK_60_MIN
        )

        val currentTimeout = sessionManager.getAutoLockTimeout()
        val currentIndex = timeouts.indexOf(currentTimeout).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_auto_lock))
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                dialog.dismiss()
                val chosen = timeouts[which]
                val set = {
                    sessionManager.setAutoLockTimeout(chosen)
                    updateAutoLockButtonText()
                }
                // A longer wait before the lock comes back weakens it: the lock is asked for first, and a
                // refusal keeps the wait it had.
                if (AppLockSwitches.autoLockNeedsProof(currentTimeout, chosen, sessionManager.isAppLockEnabled())) {
                    proveCurrentLock(AppLockSwitches.Change.LENGTHEN_AUTO_LOCK, set) {}
                } else {
                    set()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupGeocoding() {
        // SessionManager is the source of truth for the server-geocoder toggle:
        // ServerGeocoder reads the very same key, so this control must never keep
        // its own copy of the state.
        binding.switchServerGeocoderMain.isChecked = sessionManager.getUseServerGeocoder()
        updateGeocodingPrivacyNote(sessionManager.getUseServerGeocoder())
        binding.switchServerGeocoderMain.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setUseServerGeocoder(isChecked)
            updateGeocodingPrivacyNote(isChecked)
        }
        binding.layoutServerGeocoderMain.setOnClickListener {
            binding.switchServerGeocoderMain.toggle()
        }
        binding.btnServerGeocoderInfoMain.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.geocoding_server_title)
                .setMessage(R.string.geocoding_server_description)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // Gate the server-side geocoder on effective premium (own OR
        // circle-derived). Non-premium users can't enable it (the server rejects
        // with 402 anyway); disable + dim so the control reflects that.
        viewLifecycleOwner.lifecycleScope.launch {
            sessionManager.effectivePremium.collect { premium ->
                binding.switchServerGeocoderMain.isEnabled = premium
                binding.layoutServerGeocoderMain.isEnabled = premium
                binding.layoutServerGeocoderMain.alpha = if (premium) 1f else 0.5f
            }
        }

    }

    /**
     * Keep the privacy note honest — it MUST track the server-geocoder switch
     * directly above it.
     *
     * A fixed "All address lookups are performed locally on your device" states
     * the exact opposite of the truth once the server geocoder is on, one row
     * below the control that made it false.
     */
    private fun updateGeocodingPrivacyNote(serverEnabled: Boolean) {
        _binding?.tvGeocodingPrivacyNote?.setText(
            if (serverEnabled) R.string.geocoding_privacy_note_server
            else R.string.geocoding_privacy_note
        )
    }

    private fun setupCrashReporting() {
        val isEnabled = crashReporter.isEnabled()
        binding.switchCrashReporting.isChecked = isEnabled
        binding.layoutCrashLogs.visibility = if (isEnabled) View.VISIBLE else View.GONE

        updateCrashLogsStatus()

        binding.switchCrashReporting.setOnCheckedChangeListener { _, isChecked ->
            crashReporter.setEnabled(isChecked)
            binding.layoutCrashLogs.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                Toast.makeText(
                    requireContext(),
                    "Crash reporting enabled. Logs are stored locally only.",
                    Toast.LENGTH_LONG
                ).show()
            }

            updateCrashLogsStatus()
        }

        binding.btnViewCrashLogs.setOnClickListener {
            showCrashLogsDialog()
        }
    }

    private fun updateCrashLogsStatus() {
        val logs = crashReporter.getCrashLogs()
        binding.tvCrashLogsStatus.text = if (logs.isEmpty()) {
            getString(R.string.crash_logs_none)
        } else {
            getString(R.string.crash_logs_count, logs.size)
        }
    }

    private fun showCrashLogsDialog() {
        val logs = crashReporter.getCrashLogs()

        if (logs.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.crash_logs_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val items = logs.map { log ->
            val type = if (log.isCrash) "CRASH" else "Exception"
            "$type - ${log.getFormattedTimestamp()} (${log.getFormattedSize()})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.crash_logs_title))
            .setItems(items) { _, which ->
                showCrashLogDetailDialog(logs[which])
            }
            .setNeutralButton(getString(R.string.crash_logs_delete_all)) { _, _ ->
                crashReporter.deleteAllCrashLogs()
                updateCrashLogsStatus()
                Toast.makeText(requireContext(), getString(R.string.crash_logs_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCrashLogDetailDialog(logInfo: CrashReporter.CrashLogInfo) {
        val content = crashReporter.readCrashLog(logInfo.fileName)

        if (content == null) {
            Toast.makeText(requireContext(), "Failed to read log file", Toast.LENGTH_SHORT).show()
            return
        }

        // Create a scrollable text view for the log content
        val scrollView = android.widget.ScrollView(requireContext())
        val textView = android.widget.TextView(requireContext()).apply {
            text = content
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(logInfo.fileName)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.crash_log_share)) { _, _ ->
                shareCrashLog(logInfo.fileName, content)
            }
            .setNegativeButton(getString(R.string.crash_log_delete)) { _, _ ->
                crashReporter.deleteCrashLog(logInfo.fileName)
                updateCrashLogsStatus()
                Toast.makeText(requireContext(), "Log deleted", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.cancel), null)
            .show()
    }

    private fun shareCrashLog(fileName: String, content: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Track Crash Log: $fileName")
            putExtra(android.content.Intent.EXTRA_TEXT, content)
        }
        try {
            startActivity(android.content.Intent.createChooser(intent, "Share crash log"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "No app available to share crash log", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTimeBased() {
        val isEnabled = sessionManager.isTimeBasedTrackingEnabled()
        binding.switchTimeBased.isChecked = isEnabled
        binding.layoutTrackingInterval.visibility = if (isEnabled) View.VISIBLE else View.GONE

        when (sessionManager.getTrackingInterval()) {
            10 -> binding.chipTracking10.isChecked = true
            30 -> binding.chipTracking30.isChecked = true
            60 -> binding.chipTracking60.isChecked = true
            300 -> binding.chipTracking300.isChecked = true
            600 -> binding.chipTracking600.isChecked = true
            900 -> binding.chipTracking900.isChecked = true
            else -> binding.chipTracking60.isChecked = true // Default to 1 min
        }

        binding.switchTimeBased.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setTimeBasedTrackingEnabled(isChecked)
            binding.layoutTrackingInterval.visibility = if (isChecked) View.VISIBLE else View.GONE

            // Restart tracking if active to apply new settings
            if (mainViewModel.isTracking.value) {
                mainViewModel.stopTracking()
                mainViewModel.startTracking()
            }
        }

        binding.chipGroupTrackingInterval.setOnCheckedStateChangeListener { _, checkedIds ->
            val interval = when {
                checkedIds.contains(R.id.chipTracking10) -> 10
                checkedIds.contains(R.id.chipTracking30) -> 30
                checkedIds.contains(R.id.chipTracking60) -> 60
                checkedIds.contains(R.id.chipTracking300) -> 300
                checkedIds.contains(R.id.chipTracking600) -> 600
                checkedIds.contains(R.id.chipTracking900) -> 900
                else -> SessionManager.DEFAULT_TRACKING_INTERVAL
            }
            sessionManager.setTrackingInterval(interval)

            if (mainViewModel.isTracking.value) {
                mainViewModel.stopTracking()
                mainViewModel.startTracking()
            }
        }
    }

    private fun setupDistanceSending() {
        val isEnabled = sessionManager.isDistanceSendingEnabled()
        binding.switchAlsoSendOnDistance.isChecked = isEnabled
        binding.layoutAlsoSendDistanceThreshold.visibility = if (isEnabled) View.VISIBLE else View.GONE

        when (sessionManager.getDistanceSendingThreshold()) {
            50 -> binding.chipAlsoDistance50.isChecked = true
            100 -> binding.chipAlsoDistance100.isChecked = true
            250 -> binding.chipAlsoDistance250.isChecked = true
            500 -> binding.chipAlsoDistance500.isChecked = true
            else -> binding.chipAlsoDistance100.isChecked = true
        }

        binding.switchAlsoSendOnDistance.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setDistanceSendingEnabled(isChecked)
            binding.layoutAlsoSendDistanceThreshold.visibility = if (isChecked) View.VISIBLE else View.GONE

            // Restart tracking to apply
            if (mainViewModel.isTracking.value) {
                mainViewModel.stopTracking()
                mainViewModel.startTracking()
            }
        }

        binding.chipGroupAlsoSendDistance.setOnCheckedStateChangeListener { _, checkedIds ->
            val threshold = when {
                checkedIds.contains(R.id.chipAlsoDistance50) -> 50
                checkedIds.contains(R.id.chipAlsoDistance100) -> 100
                checkedIds.contains(R.id.chipAlsoDistance250) -> 250
                checkedIds.contains(R.id.chipAlsoDistance500) -> 500
                else -> 100
            }
            sessionManager.setDistanceSendingThreshold(threshold)

            if (mainViewModel.isTracking.value) {
                mainViewModel.stopTracking()
                mainViewModel.startTracking()
            }
        }
    }

    /**
     * The way back to Android's "let Track run in the background" (the once-only setup
     * question's), and this phone's own battery page, on phones whose battery manager stops
     * background apps only. Not allowed: a tap asks Android. Allowed: a tap opens Android's
     * list, where it changes back. The status is read again at every return
     * (refreshBackgroundExemption).
     */
    private fun setupBackgroundExemption() {
        val shown = BatteryOptimizationHelper.isAggressiveOem()
        binding.layoutBackgroundExemption.visibility = if (shown) View.VISIBLE else View.GONE
        binding.layoutOemBatterySettings.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) return
        binding.layoutBackgroundExemption.setOnClickListener {
            val activity = requireActivity()
            if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(activity)) {
                BatteryOptimizationHelper.openExemptionList(activity)
            } else {
                BatteryOptimizationHelper.requestExemption(activity)
            }
        }
        binding.layoutOemBatterySettings.setOnClickListener {
            BatteryOptimizationHelper.openBatterySettings(requireActivity())
        }
        refreshBackgroundExemption()
    }

    private fun refreshBackgroundExemption() {
        val b = _binding ?: return
        if (b.layoutBackgroundExemption.visibility != View.VISIBLE) return
        b.tvBackgroundExemptionStatus.setText(
            if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(requireContext())) {
                R.string.settings_background_exemption_allowed
            } else {
                R.string.settings_background_exemption_not_allowed
            }
        )
    }

    private fun setupBatterySaver() {
        // "Battery saver" in storage, "Batch uploads" in the UI.
        val isBatchEnabled = sessionManager.isBatterySaverEnabled()
        binding.switchBatterySaver.isChecked = isBatchEnabled
        binding.layoutBatterySaverInterval.visibility = if (isBatchEnabled) View.VISIBLE else View.GONE

        when (sessionManager.getBatterySaverInterval()) {
            5 -> binding.chipInterval5.isChecked = true
            15 -> binding.chipInterval15.isChecked = true
            30 -> binding.chipInterval30.isChecked = true
            60 -> binding.chipInterval60.isChecked = true
            else -> binding.chipInterval15.isChecked = true
        }

        binding.switchBatterySaver.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setBatterySaverEnabled(isChecked)
            binding.layoutBatterySaverInterval.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                Toast.makeText(requireContext(), "Batch uploads enabled - locations will be queued", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Real-time location uploads enabled", Toast.LENGTH_SHORT).show()
            }

            // Restart tracking if active to apply new settings
            if (mainViewModel.isTracking.value) {
                mainViewModel.stopTracking()
                mainViewModel.startTracking()
            }
        }

        binding.chipGroupInterval.setOnCheckedStateChangeListener { _, checkedIds ->
            val interval = when {
                checkedIds.contains(R.id.chipInterval5) -> 5
                checkedIds.contains(R.id.chipInterval15) -> 15
                checkedIds.contains(R.id.chipInterval30) -> 30
                checkedIds.contains(R.id.chipInterval60) -> 60
                else -> 15
            }
            sessionManager.setBatterySaverInterval(interval)

            if (sessionManager.isBatterySaverEnabled() && mainViewModel.isTracking.value) {
                mainViewModel.stopTracking()
                mainViewModel.startTracking()
            }
        }

    }

    /**
     * Signal-style notification display verbosity. Pure device-local
     * display preference (stored in SessionManager, applied by
     * AlertHandler at render time) — the push transport is always a
     * content-free tickle regardless, and the in-app notifications list
     * always shows full detail behind the app lock. This only controls
     * how much decrypted content lands in the Android tray / lock screen.
     */
    private fun showNotificationDetailDialog() {
        val labels = arrayOf(
            "Names and places — “Alice arrived at Home”",
            "Names only — “Alice arrived at a place”",
            "Generic — “Tap to view”",
        )
        val values = arrayOf(
            SessionManager.NOTIF_DETAIL_FULL,
            SessionManager.NOTIF_DETAIL_NAMES,
            SessionManager.NOTIF_DETAIL_GENERIC,
        )
        val current = values.indexOf(sessionManager.getNotificationDetailLevel()).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Notification detail")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                sessionManager.setNotificationDetailLevel(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Guards the email switch against firing its save listener during a
     *  programmatic state load / revert. */
    private var suppressEmailToggle = false

    /**
     * Global "Email notifications" default — the `email_enabled` field, mirroring
     * the web modal's Global Delivery → Email toggle. It's the fallback applied to
     * any notification category without an explicit per-category email override
     * (those live in [showNotificationCategoriesDialog]). Persisted via
     * read-modify-write of the full preferences map so flipping the global default
     * never clobbers the per-category overrides.
     */
    private fun setupEmailNotificationsToggle() {
        // Reflect the server's current value without tripping the save listener.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val enabled = tracksApi.getNotificationPreferences()
                    .body()?.data?.get("email_enabled") as? Boolean ?: false
                suppressEmailToggle = true
                binding.switchEmailNotifications.isChecked = enabled
                suppressEmailToggle = false
            } catch (_: Exception) {
                // Leave the switch at its default; the categories dialog still
                // surfaces the server's real per-category values on demand.
            }
        }

        binding.switchEmailNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (suppressEmailToggle) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Read-modify-write the whole map (same shape the categories
                    // dialog PUTs) so per-category overrides are preserved.
                    val current = tracksApi.getNotificationPreferences()
                        .body()?.data?.toMutableMap() ?: mutableMapOf()
                    current["email_enabled"] = isChecked
                    val put = tracksApi.updateNotificationPreferences(current)
                    if (!put.isSuccessful) throw IllegalStateException("HTTP ${put.code()}")
                    Toast.makeText(requireContext(), "Preferences saved", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Failed to save", Toast.LENGTH_SHORT).show()
                    // Revert the visual state since the change didn't stick.
                    suppressEmailToggle = true
                    binding.switchEmailNotifications.isChecked = !isChecked
                    suppressEmailToggle = false
                }
            }
        }
    }

    /**
     * Dialog title plus a secondary line, built in code because a list dialog has
     * nowhere else to put a sentence: `setMessage` and `setMultiChoiceItems` are
     * mutually exclusive in AlertController, and the message wins by dropping the
     * list entirely.
     *
     * The push dialog's title block: the title, the locked category rendered as a
     * STATEMENT, and the reason underneath.
     *
     * [lockedRow] is drawn here — a lock glyph and a line of text, nothing
     * tappable — instead of appearing as a ticked checkbox in the list below.
     * A checkbox is a control, and this one would govern nothing; it would read
     * as toggleable, which is the misreading the lock exists to prevent.
     * Passing null omits it (the email step has no locked row).
     */
    private fun notificationCategoriesHeader(
        title: String,
        note: String,
        lockedRow: String? = null,
    ): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val muted = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 3)
            addView(
                TextView(context).apply {
                    text = title
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
                    )
                }
            )
            if (lockedRow != null) {
                addView(
                    TextView(context).apply {
                        text = lockedRow
                        textSize = 15f
                        setTextColor(muted)
                        // The glyph is what separates "this is how it is" from
                        // "this is yours to set" at a glance, next to a list of
                        // real checkboxes.
                        setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_lock, 0, 0, 0
                        )
                        compoundDrawablePadding = (12 * density).toInt()
                        TextViewCompat.setCompoundDrawableTintList(
                            this,
                            android.content.res.ColorStateList.valueOf(muted)
                        )
                        setPadding(0, (12 * density).toInt(), 0, 0)
                        // Read out as one unit, and never focusable: there is
                        // nothing here to operate.
                        contentDescription = lockedRow
                    }
                )
            }
            addView(
                TextView(context).apply {
                    text = note
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, pad / 4, 0, 0)
                }
            )
        }
    }

    private fun showNotificationCategoriesDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = tracksApi.getNotificationPreferences()
                if (!response.isSuccessful || response.body()?.data == null) {
                    Toast.makeText(requireContext(), "Failed to load preferences", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val prefs = response.body()!!.data!!

                // The rows, and which of them are preferences at
                // all, live in [NotificationCategories]. The
                // emergency row is listed but LOCKED: it is
                // undeniable server-side, so `sos_push`/`sos_email`
                // govern nothing, and a switch here would promise
                // something the system will not do. It has no
                // server keys in that table, so there is nothing
                // for this dialog to write for it.
                // The LIST holds only rows the user can actually
                // change. The locked category is rendered in the
                // header instead (see notificationCategoriesHeader):
                // as a checkbox it would be a control that governs
                // nothing. buildUpdate still walks every category
                // on its own and skips the keyless one, so nothing
                // here has to remember to.
                val deniable = NotificationCategories.deniable
                val pushEnabled = prefs.getOrDefault("push_enabled", true) as Boolean
                val emailEnabled = prefs.getOrDefault("email_enabled", false) as Boolean

                // What the server ACTUALLY holds, kept apart from what the dialog
                // shows. A null here is not "off": it is "no preference stored", and
                // the server resolves that by inheriting the global. Sending the
                // displayed tick back would pin a value the user never chose — see
                // NotificationCategories.buildUpdate.
                val pushStored = Array(deniable.size) { i -> deniable[i].pushKey?.let { prefs[it] as? Boolean } }
                val pushTouched = BooleanArray(deniable.size)

                val pushLabels = deniable.map { it.pushRowLabel }.toTypedArray()
                // Inheriting an enabled global renders as ticked; that tick is a
                // rendering of the inherit, not a stored preference.
                val pushStates = BooleanArray(deniable.size) { i -> pushStored[i] ?: pushEnabled }

                // Push first, then email. The email step lists only
                // categories with an email channel server-side:
                // "Destinations", "Check-ins" and "Safety alerts"
                // are push-only, and the locked row has no column at
                // all. The locked row is still SHOWN in the email
                // step's header, as in the push step's: its e-mail
                // goes out whatever is set.
                val emailCategories = NotificationCategories.withEmail
                val emailStored = Array(emailCategories.size) { i ->
                    emailCategories[i].emailKey?.let { prefs[it] as? Boolean }
                }
                val emailTouched = BooleanArray(emailCategories.size)
                val emailStates = BooleanArray(emailCategories.size) { i ->
                    emailStored[i] ?: emailEnabled
                }

                MaterialAlertDialogBuilder(requireContext())
                    // Carries the title AND the reason the locked row cannot be
                    // changed, so the explanation is on screen before the user goes
                    // hunting for the missing switch rather than only after they tap
                    // it. It has to ride in the title: setMessage would silently
                    // replace the list, because an AlertDialog renders a message or
                    // a list, never both.
                    .setCustomTitle(
                        notificationCategoriesHeader(
                            title = "Push Notifications",
                            note = NotificationCategories.LOCKED_NOTE,
                            lockedRow = NotificationCategories.lockedStatement,
                        )
                    )
                    .setMultiChoiceItems(pushLabels, pushStates) { _, which, isChecked ->
                        // Every row here is deniable, so a tap is always a real
                        // preference. Rows the user never touched keep whatever the
                        // server had, including "nothing".
                        pushTouched[which] = true
                        pushStates[which] = isChecked
                    }
                    .setPositiveButton("Next: Email") { _, _ ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setCustomTitle(
                                notificationCategoriesHeader(
                                    title = "Email Notifications",
                                    note = NotificationCategories.EMAIL_LOCKED_NOTE,
                                    lockedRow = NotificationCategories.lockedStatement,
                                )
                            )
                            .setMultiChoiceItems(
                                emailCategories.map { it.label }.toTypedArray(),
                                emailStates
                            ) { _, which, isChecked ->
                                emailTouched[which] = true
                                emailStates[which] = isChecked
                            }
                            .setPositiveButton("Save") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    // Touched -> the user's answer. Untouched -> repeat
                                    // what the server had, which for a column they
                                    // have never set is null, i.e. send nothing and
                                    // leave the inherit intact.
                                    val update = NotificationCategories.buildUpdate(
                                        pushEnabled = pushEnabled,
                                        emailEnabled = emailEnabled,
                                        push = {
                                            // buildUpdate only asks about categories
                                            // that HAVE a push key, and every one of
                                            // those is in `deniable` — the locked row
                                            // never reaches here, so this index is
                                            // always found.
                                            val i = deniable.indexOf(it)
                                            NotificationCategories.answerFor(
                                                touched = pushTouched[i],
                                                shown = pushStates[i],
                                                stored = pushStored[i],
                                            )
                                        },
                                        email = {
                                            val i = emailCategories.indexOf(it)
                                            NotificationCategories.answerFor(
                                                touched = emailTouched[i],
                                                shown = emailStates[i],
                                                stored = emailStored[i],
                                            )
                                        },
                                    )
                                    try {
                                        // A refusal (4xx/5xx) is a
                                        // Response, not a throw:
                                        // checked, as the e-mail
                                        // switch does.
                                        val put = tracksApi.updateNotificationPreferences(update)
                                        if (!put.isSuccessful) throw IllegalStateException("HTTP ${put.code()}")
                                        Toast.makeText(requireContext(), "Preferences saved", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(requireContext(), "Failed to save", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.plainText("Couldn't load your notification settings just now."), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPushNotifications() {
        val availableProviders = pushNotificationManager.detectAvailableProviders()
        val currentProvider = pushNotificationManager.getCurrentProvider()

        binding.chipPushFcm.isEnabled = availableProviders.contains(PushNotificationManager.PushProvider.FCM)
        // UnifiedPush stays tappable even with no distributor installed: tapping it
        // is how the user gets the "install a distributor" hint.
        binding.chipPushUnifiedPush.isEnabled = true

        when (currentProvider) {
            PushNotificationManager.PushProvider.FCM -> binding.chipPushFcm.isChecked = true
            PushNotificationManager.PushProvider.UNIFIED_PUSH -> {
                binding.chipPushUnifiedPush.isChecked = true
                binding.layoutUnifiedPushDistributor.visibility = View.VISIBLE
            }
            else -> {
                // No explicitly configured provider — leave chips unchecked.
                binding.chipGroupPushProvider.clearCheck()
            }
        }

        updatePushStatus()
        updatePrimaryDeliverySubtitle()
        refreshServerPushRegistration()

        // Refresh when push settings change out-of-band — notably when the
        // UnifiedPush endpoint arrives asynchronously
        // (TrackUnifiedPushReceiver.onNewEndpoint) after the user picks a
        // distributor. Without this the status keeps showing the provider being
        // replaced ("Status: Google FCM") until Settings is reopened, even though
        // UnifiedPush is already registered and synced to the server.
        viewLifecycleOwner.lifecycleScope.launch {
            sessionManager.pushSettingsChanged.collectLatest {
                updatePushStatus()
                updateUnifiedPushDistributorStatus()
            }
        }

        binding.chipGroupPushProvider.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipPushFcm) -> {
                    binding.layoutUnifiedPushDistributor.visibility = View.GONE
                    updatePrimaryDeliverySubtitle()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = pushNotificationManager.registerWithProvider(
                            PushNotificationManager.PushProvider.FCM
                        )
                        handlePushRegistrationResult(result)
                    }
                }
                checkedIds.contains(R.id.chipPushUnifiedPush) -> {
                    binding.layoutUnifiedPushDistributor.visibility = View.VISIBLE
                    updatePrimaryDeliverySubtitle()
                    updateUnifiedPushDistributorStatus()
                }
            }
        }

        binding.btnSelectDistributor.setOnClickListener {
            showDistributorSelectionDialog()
        }
    }

    /**
     * What the server says it can deliver to this session. Null until the probe
     * answers, and left null if it fails — [PushStatusResolver] treats that as
     * "no opinion" and falls back to local state rather than inventing one.
     */
    private var serverPushRegistrations: List<com.pingolin.track.data.remote.dto.PushRegistration>? = null

    /**
     * Guards the automatic re-registration in [updatePushStatus] to one attempt
     * per view. That method re-renders on every push-pref change, and each
     * render would otherwise fire another device-registration POST.
     */
    private var pushRepairAttempted = false

    /**
     * Ask the server what it can actually deliver here, then re-render.
     *
     * Best-effort on purpose: an older backend 404s this endpoint and an offline
     * phone never reaches it. Neither is a reason to tell the user anything is
     * wrong, so both leave [serverPushRegistrations] null.
     */
    private fun refreshServerPushRegistration() {
        viewLifecycleOwner.lifecycleScope.launch {
            val registrations = try {
                val resp = tracksApi.getPushRegistration()
                if (resp.isSuccessful) resp.body()?.data else null
            } catch (_: Exception) {
                null
            }
            // The fragment can go away while the call is in flight.
            if (_binding == null) return@launch
            serverPushRegistrations = registrations
            updatePushStatus()
            updateUnifiedPushDistributorStatus()
        }
    }

    /**
     * Push status line and its icon: green check = actively receiving, red error =
     * configured but not working, no icon = not configured.
     */
    private fun updatePushStatus(pendingProvider: PushNotificationManager.PushProvider? = null) {
        // During an in-flight provider switch the persisted pushType still reflects the
        // OLD provider until the async endpoint/token lands. When a target provider is
        // supplied (e.g. from a Pending registration result), reflect it so the status
        // reads "<target> — registration pending" instead of the provider we're leaving.
        val pushType = when (pendingProvider) {
            PushNotificationManager.PushProvider.UNIFIED_PUSH -> SessionManager.PUSH_TYPE_UNIFIED_PUSH
            PushNotificationManager.PushProvider.FCM -> SessionManager.PUSH_TYPE_FCM
            else -> sessionManager.getPushType()
        }
        val pushToken = sessionManager.getPushToken()
        val pushEndpoint = sessionManager.getPushEndpoint()

        // Reconciled against the server rather than read off local
        // prefs alone: a missing local endpoint would otherwise read as
        // "registration pending" even when the server holds a live
        // registration and pushes are arriving.
        val status = PushStatusResolver.resolve(
            pushType = pushType,
            pushToken = pushToken,
            pushEndpoint = pushEndpoint,
            serverRegistrations = serverPushRegistrations,
        )

        // Fill the gap the server just proved we have. Only ever a fill, never
        // an overwrite — see PushStatusResolver.Restore. Skipped during an
        // in-flight provider switch, where the pending provider is the truth
        // and the server still describes the one we're leaving.
        status.restore?.let { restore ->
            if (pendingProvider == null) {
                sessionManager.restorePushRegistration(restore.pushType, restore.endpoint)
            }
        }

        // The other direction: the server has no way to address this
        // device (typically it deactivated the token after three failed
        // deliveries) while local state is intact. Colouring this red
        // and saying "not synced to server" is not enough on its own —
        // there is no control here that repairs it, and the ordinary
        // sync paths all bail because local state looks healthy.
        // Re-register, which is the only call that can recreate the
        // row. Once per visit: repeated renders must not mean repeated
        // POSTs.
        if (status.needsReregistration && pendingProvider == null && !pushRepairAttempted) {
            pushRepairAttempted = true
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = try {
                    locationRepository.forceDeviceReregistration()
                } catch (e: Exception) {
                    android.util.Log.w("SettingsFragment", "Push re-registration failed: ${e.message}")
                    false
                }
                android.util.Log.i("SettingsFragment", "Push re-registration from Settings: success=$ok")
                if (_binding == null) return@launch
                if (ok) refreshServerPushRegistration()
            }
        }

        val statusText = status.text
        val isError = status.isError

        binding.tvPushStatus.text = statusText

        val icon = when {
            !status.configured -> null
            isError -> androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_error)?.apply {
                setTint(requireContext().getColor(com.google.android.material.R.color.design_default_color_error))
            }
            else -> androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_check)?.apply {
                setTint(requireContext().getColor(com.google.android.material.R.color.design_default_color_primary))
            }
        }
        binding.tvPushStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)

        binding.tvPushStatus.setTextColor(
            if (isError) requireContext().getColor(com.google.android.material.R.color.design_default_color_error)
            else {
                val tv = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
                requireContext().getColor(tv.resourceId)
            }
        )
    }

    private fun updatePrimaryDeliverySubtitle() {
        val subtitle = if (binding.chipPushUnifiedPush.isChecked) {
            "Requires a distributor app like ntfy."
        } else {
            "Battery efficient. Requires Google Play Services."
        }
        binding.tvPrimaryDeliverySubtitle.text = subtitle
    }

    private fun updateUnifiedPushDistributorStatus() {
        val distributors = pushNotificationManager.getUnifiedPushDistributors()
        val currentEndpoint = sessionManager.getPushEndpoint()
        // The server's copy counts too: an install that lost its local
        // endpoint is still registered, and must not be offered "select
        // a distributor" for a channel that already works.
        val serverEndpoint = serverPushRegistrations
            ?.firstOrNull { it.pushType == PushStatusResolver.serverPushType(SessionManager.PUSH_TYPE_UNIFIED_PUSH) }
            ?.endpoint

        binding.tvDistributorStatus.text = PushStatusResolver.distributorStatus(
            distributorCount = distributors.size,
            localEndpoint = currentEndpoint,
            serverEndpoint = serverEndpoint,
        )
        binding.btnSelectDistributor.isEnabled = distributors.isNotEmpty()
    }

    private fun showDistributorSelectionDialog() {
        val distributors = pushNotificationManager.getUnifiedPushDistributors()
        if (distributors.isEmpty()) {
            Toast.makeText(requireContext(), "No UnifiedPush distributors installed", Toast.LENGTH_LONG).show()
            return
        }

        val displayNames = distributors.map { packageName ->
            when {
                packageName.contains("ntfy") -> "ntfy"
                packageName.contains("nextpush") -> "NextPush"
                packageName.contains("molly") -> "Molly"
                else -> packageName.substringAfterLast(".")
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Distributor")
            .setItems(displayNames) { _, which ->
                val selectedDistributor = distributors[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = pushNotificationManager.registerWithUnifiedPushDistributor(selectedDistributor)
                    handlePushRegistrationResult(result)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handlePushRegistrationResult(result: PushNotificationManager.RegistrationResult) {
        when (result) {
            is PushNotificationManager.RegistrationResult.Success -> {
                val providerName = when (result.provider) {
                    PushNotificationManager.PushProvider.FCM -> "Google FCM"
                    PushNotificationManager.PushProvider.UNIFIED_PUSH -> "UnifiedPush"
                    PushNotificationManager.PushProvider.NONE -> "None"
                }
                Toast.makeText(requireContext(), "Registered with $providerName", Toast.LENGTH_SHORT).show()
                updatePushStatus()
                updateUnifiedPushDistributorStatus()
            }
            is PushNotificationManager.RegistrationResult.Pending -> {
                Toast.makeText(requireContext(), "Registration pending: ${result.message}", Toast.LENGTH_SHORT).show()
                updatePushStatus(result.provider)
                updateUnifiedPushDistributorStatus()
            }
            is PushNotificationManager.RegistrationResult.Failure -> {
                Toast.makeText(requireContext(), "Registration failed: ${result.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is SettingsUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is SettingsUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    is SettingsUiState.LoggedOut -> {
                        navigateToAuth()
                    }
                    is SettingsUiState.DeletingAccount -> {
                        binding.progressBar.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), "Deleting account...", Toast.LENGTH_SHORT).show()
                    }
                    is SettingsUiState.UnspentCredit -> {
                        // Nothing has been deleted. The server refused because
                        // the balance is money the user paid for and it goes
                        // with the account. Message on the BUILDER: AlertDialog
                        // inflates its message TextView only when setMessage
                        // precedes create().
                        binding.progressBar.visibility = View.GONE
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("You still have credit")
                            .setMessage(state.message)
                            .setPositiveButton("Delete anyway") { _, _ ->
                                showDeleteAccountConfirmation(forfeitCredit = true)
                            }
                            .setNegativeButton("Keep my credit", null)
                            .showDestructive()
                        viewModel.resetState()
                    }
                    is SettingsUiState.AccountDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        val billing = state.billing
                        if (billing.needsPostDeletionNotice) {
                            // A toast here would be wrong twice over: it is
                            // dismissible by accident and navigateToAuth() would
                            // race it off the screen. The account is already gone,
                            // so this is the last chance to say it.
                            val message = billing.deletionNotice()
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Account deleted")
                                // On the BUILDER, not the created dialog — set on
                                // the dialog it renders empty.
                                .setMessage(message)
                                .setCancelable(false)
                                .setPositiveButton("OK") { _, _ -> navigateToAuth() }
                                .show()
                        } else {
                            Toast.makeText(requireContext(), "Account deleted successfully", Toast.LENGTH_LONG).show()
                            navigateToAuth()
                        }
                    }
                    is SettingsUiState.DisplayNameUpdated -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvDisplayName.text = state.newName
                        Toast.makeText(requireContext(), "Display name updated", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun showLogoutConfirmation() {
        viewLifecycleOwner.lifecycleScope.launch {
            val waiting = runCatching { viewModel.signOutLine() }.getOrNull()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Logout")
                .setMessage(listOfNotNull("Are you sure you want to logout?", waiting).joinToString("\n\n"))
                .setPositiveButton("Logout") { _, _ ->
                    viewModel.logout()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * @param forfeitCredit the user's answer to the server's refusal when the
     *   account still holds bought credit. The password CharArray is wiped after
     *   each attempt, so a retry has to ask for it again — which also makes
     *   destroying the balance take a second deliberate act rather than one tap.
     */
    private fun showDeleteAccountConfirmation(forfeitCredit: Boolean = false) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Account")
            .setMessage(
                "Are you sure you want to permanently delete your account? This action cannot be undone." +
                    // Not "circles": only the ones you own go (the line below), and
                    // those you merely belong to stay.
                    "\n\nAll your data, including your location history and settings, will be permanently deleted." +
                    // The circles the account owns end with it, for everyone in them
                    // (their members, invitations and keys go too). The people left in
                    // a family circle lose it with this button.
                    "\n\n" + getString(R.string.delete_account_owned_circles) +
                    // The account is the shared Pingolin account, not a per-app one,
                    // and deleting it cascades into every app that hangs off it (Track
                    // and Note). Someone deleting from Track has no way to know their
                    // notes go with it unless this says so, before the button.
                    "\n\nThis is your Pingolin account, which every Pingolin app shares. Deleting it deletes " +
                    "your data in all of them - Pingolin Track and Pingolin Note - not just this app." +
                    // Stated before the button, because afterwards there is no account
                    // left to read it in. We cancel what we can; an App Store
                    // subscription only Apple can stop, at the customer's request.
                    "\n\nThis also cancels your subscription. If you bought it through the App Store on an " +
                    "iPhone or iPad, only you can cancel that - in your Apple account settings."
            )
            .setPositiveButton("Delete") { _, _ ->
                showPasswordDialogForDeletion(forfeitCredit)
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    private fun showPasswordDialogForDeletion(forfeitCredit: Boolean = false) {
        // A password field: hidden as typed, and nothing of it learnt by the keyboard.
        val passwordEditText = com.pingolin.track.util.SecretFields.password(requireContext())

        val textInputLayout = com.google.android.material.textfield.TextInputLayout(
            requireContext(),
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "Enter your password"
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(passwordEditText)
            // To accessibility services as dots, as the screen shows it.
            com.pingolin.track.util.SecretFields.keepHidden(this)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(textInputLayout)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Deletion")
            .setMessage("Please enter your password to confirm account deletion.")
            .setView(container)
            .setPositiveButton("Delete Account") { _, _ ->
                val editable = passwordEditText.text
                if (editable != null && editable.isNotEmpty()) {
                    val password = CharArray(editable.length)
                    editable.getChars(0, editable.length, password, 0)
                    // Wipe the EditText buffer so the plaintext password doesn't linger.
                    editable.clear()
                    viewModel.deleteAccount(password, forfeitCredit)
                } else {
                    Toast.makeText(requireContext(), "Password is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    /** Held between the export dialog and the file-picker result. */
    private var pendingExportPassphrase: String? = null

    /**
     * Restore, prompting for a passphrase if the file turns out to be sealed.
     *
     * The prompt is raised from the FAILURE rather than guessed up front: the
     * file is read before we know what it is, and asking everyone for a
     * passphrase they mostly do not have would be worse than asking the few who
     * do.
     */
    private fun restoreWithPassphraseIfNeeded(text: String, passphrase: String?) {
        lifecycleScope.launch {
            viewModel.restoreBackup(text, passphrase)
                .onSuccess { showRestoreSummary(it) }
                .onFailure { e ->
                    if (e is SettingsViewModel.NeedsPassphraseException) {
                        promptForBackupPassphrase(text)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            e.plainText("Restore failed"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }

    private fun promptForBackupPassphrase(text: String) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(input)
        }
        // Title, message and view on the BUILDER — set on the created dialog
        // they render empty.
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypted backup")
            .setMessage("This backup is sealed. Enter the passphrase it was exported with.")
            .setView(container)
            .setPositiveButton("Restore") { _, _ ->
                restoreWithPassphraseIfNeeded(text, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToAuth() {
        startActivity(Intent(requireContext(), AuthActivity::class.java))
        requireActivity().finish()
    }

    override fun onResume() {
        super.onResume()
        // The display name can be changed on web, so refetch — but paint the value
        // we already have first, rather than leaving the row stale until it lands.
        viewModel.fetchDisplayName()
        binding.tvDisplayName.text = getDisplayNameOrDefault()
        // The recovery address is ONLY ever changed on web, so the common path
        // is coming back from the portal having just added one — and the info
        // dialog reads this value, so it has to be fresh before it is opened.
        viewModel.fetchRecoveryEmailStatus()
        // Back from Android's battery question or list: say what it is now.
        refreshBackgroundExemption()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** /quota `source` for premium inherited from a circle member. */
        private const val SOURCE_CIRCLE = "circle"

        /** The website's own pages, the same ones the iPhone links to (StoreKitManager.Legal). */
        internal const val PRIVACY_POLICY_URL = "https://pingolin.com/privacy"
        internal const val TERMS_URL = "https://pingolin.com/terms"
        // There is deliberately no free-tier history-days constant here: the
        // server sends that number as history_days on every quota response, and
        // a constant that duplicates a server value is only ever one config
        // change away from lying to the user.
    }

}
