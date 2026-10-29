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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.repository.AccountDeletionOutcome
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.domain.backup.RestoreRepository
import com.pingolin.track.domain.backup.TrackBackupFile
import com.pingolin.track.domain.repository.ExportRepository
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.PremiumStatusRefresher
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val exportRepository: ExportRepository,
    private val restoreRepository: RestoreRepository,
    private val tracksApi: TrackApi,
    private val sessionManager: SessionManager,
    private val premiumStatusRefresher: PremiumStatusRefresher,
    private val pendingChanges: com.pingolin.track.domain.pending.PendingChanges,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _subscriptionTier = MutableStateFlow(sessionManager.getSubscriptionTier())
    val subscriptionTier: StateFlow<String> = _subscriptionTier.asStateFlow()

    /** Recovery-address state for the Account recovery row. Starts Unknown and
     *  stays Unknown unless the server actually answers — see
     *  [AuthRepository.getRecoveryEmailStatus]. */
    private val _recoveryEmailStatus =
        MutableStateFlow<AccountRecovery.Status>(AccountRecovery.Status.Unknown)
    val recoveryEmailStatus: StateFlow<AccountRecovery.Status> = _recoveryEmailStatus.asStateFlow()

    val userEmail: String? get() = sessionManager.getEmail()
    val userDisplayName: String? get() = sessionManager.getDisplayName()
    /** What this build talks to, from BuildConfig — never a stored value. */
    val environment: SessionManager.Environment get() = sessionManager.getBuildEnvironment()

    /** Goes to the Track profile API, not auth: display names are Track-specific. */
    fun updateDisplayName(newName: String) {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            try {
                val response = tracksApi.updateProfile(
                    UpdateTracksProfileRequest(displayName = newName)
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    sessionManager.setDisplayName(newName)
                    _uiState.value = SettingsUiState.DisplayNameUpdated(newName)
                } else {
                    // In this app's words, never the server's.
                    _uiState.value = SettingsUiState.Error(
                        com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't change your name just now.")
                    )
                }
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error(e.plainText("Failed to update display name"))
            }
        }
    }

    /**
     * Sync the display name from the Track profile after login. A null from the
     * server is ignored rather than written through, so a locally-set name isn't
     * wiped before it has synced up.
     */
    fun fetchDisplayName() {
        viewModelScope.launch {
            try {
                val response = tracksApi.getProfile()
                if (response.isSuccessful && response.body()?.data != null) {
                    val displayName = response.body()!!.data!!.displayName
                    if (displayName != null) {
                        sessionManager.setDisplayName(displayName)
                    }
                }
            } catch (e: Exception) {
                // Non-critical - use existing display name
            }
        }
    }

    /** Refresh the effective premium entitlement and update the tier badge.
     *  The fetch + fallback + SessionManager caching live in
     *  [PremiumStatusRefresher] (shared with MainActivity's startup refresh
     *  and the destination premium gates); this just mirrors the resulting
     *  tier into the screen state. A null return means nothing could be
     *  determined (offline) — keep the cached label. */
    fun fetchSubscriptionTier() {
        viewModelScope.launch {
            premiumStatusRefresher.refresh()?.let { _subscriptionTier.value = it }
        }
    }

    /** Refresh the recovery-address row. Cheap and idempotent, so the screen
     *  can call it on every resume — a user who has just gone to the portal to
     *  add an address comes back to a row that says so. */
    fun fetchRecoveryEmailStatus() {
        viewModelScope.launch {
            // Only write an actual answer. An Unknown here means the read
            // failed, and clobbering a value the server already gave us with
            // "Checking…" would leave the row claiming to be mid-check forever.
            val status = authRepository.getRecoveryEmailStatus()
            if (status != AccountRecovery.Status.Unknown) {
                _recoveryEmailStatus.value = status
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = SettingsUiState.LoggedOut
        }
    }

    /**
     * The sign-out confirmation's extra line when changes are waiting to sync on this phone:
     * they won't be made. Information, not a question; null when none.
     */
    suspend fun signOutLine(): String? =
        pendingChanges.waitingCount().takeIf { it > 0 }?.let { com.pingolin.track.domain.pending.PendingLines.signOut(it) }

    /**
     * Delete the user's account (GDPR compliant), collecting the pseudonyms the
     * server needs to erase the location rows it can't otherwise attribute.
     *
     * SECURITY: [password] is a CharArray, not a String, so it can be wiped;
     * AuthRepository clears it after use and callers must not reuse it.
     */
    fun deleteAccount(password: CharArray, forfeitCreditBalance: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.DeletingAccount
            try {
                val result = authRepository.deleteAccount(password, forfeitCreditBalance)
                if (result.isSuccess) {
                    _uiState.value = SettingsUiState.AccountDeleted(
                        result.getOrNull() ?: AccountDeletionOutcome()
                    )
                } else {
                    when (val e = result.exceptionOrNull()) {
                        // A question, not a failure. Nothing has been deleted.
                        is com.pingolin.track.domain.repository.UnspentCreditException ->
                            _uiState.value = SettingsUiState.UnspentCredit(
                                e.plainText("You still have unspent credit.")
                            )
                        else ->
                            _uiState.value = SettingsUiState.Error(
                                (e?.plainText("Failed to delete account") ?: "Failed to delete account")
                            )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error(e.plainText("Failed to delete account"))
            }
        }
    }

    fun resetState() {
        _uiState.value = SettingsUiState.Idle
    }

    /**
     * Export user data (GDPR Article 20, right to data portability), and build
     * the FILE.
     *
     * Returns the file body for the caller to hand to the document picker, so
     * the export is a backup the user can actually keep and restore.
     *
     * @param daysBack one of 7, 30, 90 or 365.
     */
    suspend fun exportData(
        daysBack: Int = 30,
        includeDevices: Boolean = true,
        includeCircles: Boolean = true,
        includePlaces: Boolean = true,
        includeCheckIns: Boolean = true,
        includeLocations: Boolean = true
    ): Result<ExportResult> {
        return try {
            val endDay = (System.currentTimeMillis() / (1000 * 60 * 60 * 24)).toInt()
            val startDay = endDay - daysBack
            val result = exportRepository.exportAndBuildBackup(
                startDayBucket = startDay,
                endDayBucket = endDay,
                includeDevices = includeDevices,
                includeCircles = includeCircles,
                includePlaces = includePlaces,
                includeCheckIns = includeCheckIns,
                includeLocations = includeLocations
            )
            result.map { built ->
                ExportResult(
                    totalRecords = (built.decrypted.devices?.size ?: 0) +
                        (built.decrypted.circles?.size ?: 0) +
                        (built.decrypted.places?.size ?: 0) +
                        (built.decrypted.checkIns?.size ?: 0) +
                        (built.decrypted.locations?.size ?: 0),
                    deviceCount = built.decrypted.devices?.size ?: 0,
                    circleCount = built.decrypted.circles?.size ?: 0,
                    placeCount = built.decrypted.places?.size ?: 0,
                    checkInCount = built.decrypted.checkIns?.size ?: 0,
                    locationCount = built.decrypted.locations?.size ?: 0,
                    fileBody = built.fileBody
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read a backup file and put back what this client can.
     *
     * The parse is separate from the restore so a file that is not a Track
     * backup is refused before anything is written — silently finding zero
     * places in someone's note backup is worse than saying so.
     */
    suspend fun restoreBackup(
        text: String,
        passphrase: String? = null,
    ): Result<RestoreRepository.Summary> {
        // Unseal first when the file is a container. Branch on CONTENT rather
        // than the filename — a sealed backup comes from the same picker as a
        // plain one — and let the SDK do it, so every platform agrees on a
        // format whose header is bound in as AEAD associated data.
        val body = if (com.pingolin.track.domain.backup.looksLikeSealedBackup(text)) {
            if (passphrase.isNullOrEmpty()) return Result.failure(NeedsPassphraseException())
            try {
                uniffi.pingolin_mobile.openBackupFile(text, passphrase)
            } catch (e: Exception) {
                return Result.failure(Exception(e.plainText("That backup could not be opened.")))
            }
        } else {
            text
        }

        return when (val read = TrackBackupFile.read(body)) {
            is TrackBackupFile.ReadResult.NeedsPassphrase -> Result.failure(NeedsPassphraseException())
            is TrackBackupFile.ReadResult.Failed -> Result.failure(Exception(read.message))
            is TrackBackupFile.ReadResult.Ok -> try {
                Result.success(restoreRepository.restore(read.file))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Raised when the picked file is sealed and no passphrase was supplied, so
     *  the Fragment can prompt rather than showing an error. */
    class NeedsPassphraseException : Exception("That backup is encrypted. Enter its passphrase to restore it.")

}

sealed class SettingsUiState {
    object Idle : SettingsUiState()
    object Loading : SettingsUiState()
    object LoggedOut : SettingsUiState()
    object DeletingAccount : SettingsUiState()
    /**
     * Carries the billing outcome, because the fragment has to show it before it
     * navigates away — there is no account left to come back to.
     */
    data class AccountDeleted(val billing: AccountDeletionOutcome) : SettingsUiState()
    /**
     * The server refused the deletion because the account still holds credit it
     * paid for. Nothing has been deleted; the user answers and we retry.
     */
    data class UnspentCredit(val message: String) : SettingsUiState()
    data class DisplayNameUpdated(val newName: String) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

/** Result of a data export operation. */
data class ExportResult(
    val totalRecords: Int,
    val deviceCount: Int,
    val circleCount: Int,
    val placeCount: Int,
    val checkInCount: Int,
    val locationCount: Int,
    /** The JSON to write. This is the export — the counts above only describe it. */
    val fileBody: String
)

