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

package com.pingolin.track.ui.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.ui.places.placardAddress
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SosViewModel @Inject constructor(
    private val sosRepository: SosRepository,
    private val circleRepository: CircleRepository,
    private val locationRepository: LocationRepository,
    private val geocodingRepository: GeocodingRepository,
    private val privacySettingDao: PrivacySettingDao,
    private val sessionManager: SessionManager,
    private val sosDispatcher: com.pingolin.track.domain.sos.SosDispatcher,
    // Who asked, on someone else's card, by the receivers' rules.
    private val notificationNames: com.pingolin.track.domain.repository.NotificationNames,
) : ViewModel() {

    companion object {
        const val COUNTDOWN_SECONDS = com.pingolin.track.domain.sos.SosDispatcher.COUNTDOWN_SECONDS
    }

    /**
     * Delivery outcome for the alert this user just raised, or null when there
     * is nothing to report on.
     *
     * "The server accepted the alert" and "somebody's phone got it" are
     * different facts, and only the second says the alert arrived. Checked by
     * the dispatcher for the whole application, from the send, so the answer
     * outlives this screen. A screen made again reads the same checks; there are
     * no others.
     */
    val delivery: StateFlow<com.pingolin.track.domain.sos.SosDeliveryWatcher.Status?> = sosDispatcher.deliveries.status

    /** The delivery dialog's OK: it goes. Checks still running go on, and still say it if it reached nobody. */
    fun clearDelivery() = sosDispatcher.deliveries.putAway()

    /** This screen is on screen, or not: while it is, its delivery dialog says what the request reached. */
    fun safetyTabShown(shown: Boolean) {
        sosDispatcher.deliveries.safetyTabOpen = shown
    }

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAlerts: StateFlow<List<SosAlertEntity>> = sosRepository.observeActiveAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    /**
     * An address is for an alert's position, not the alert: the raiser's phone replaces a last-known
     * position with a better one, and the address has to follow.
     */
    private data class AddressKey(val alertId: String, val latitude: Double, val longitude: Double)

    /** A point's key; none for no position, or for an area, which has no address: its centre is nobody's
     * street.
     */
    private fun addressKey(alert: SosAlertEntity): AddressKey? {
        val lat = alert.latitude ?: return null
        val lng = alert.longitude ?: return null
        if (com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) != null) return null
        return AddressKey(alert.id, lat, lng)
    }

    /** (alert, position) → reverse-geocoded street address. Only holds resolved entries;
     *  a position that hasn't resolved (or can't) is simply absent. */
    private val _addresses = MutableStateFlow<Map<AddressKey, String>>(emptyMap())

    /** Positions with a geocode in flight, so re-emissions of [activeAlerts]
     *  don't fire a second lookup for the same one. Touched only from
     *  viewModelScope's main dispatcher. */
    private val addressLookupsInFlight = mutableSetOf<AddressKey>()

    /**
     * Who asked for attention, on someone else's card: named only by the receivers' rules, as the
     * notification rows name them ([com.pingolin.track.domain.repository.NotificationNames]): the
     * alert as served now opens under the circle's current key, or this phone saw it current, and
     * the name is one this phone opened. Never the stored alert's raiser. Alert id to name; null
     * when none can be told, and the card says "A circle member".
     */
    private val _raiserNames = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** Alerts with a name lookup in flight; touched only from viewModelScope's main dispatcher. */
    private val raiserLookupsInFlight = mutableSetOf<String>()

    /**
     * The list the Safety screen renders: each active alert paired with its
     * address once that resolves.
     *
     * The lookup is kicked off from here rather than from an `init` collector
     * so it only runs while the screen is actually subscribed — an `init`
     * collector would hold the underlying Room query open for the whole
     * ViewModel's life. [resolveAddresses] is idempotent, which is what makes
     * calling it from a transform safe: it re-runs on every emission (including
     * this flow's own address updates) and skips anything already resolved or
     * in flight.
     */
    val alertRows: StateFlow<List<SosAlertRow>> =
        combine(activeAlerts, _addresses, _raiserNames) { alerts, addresses, raisers ->
            resolveAddresses(alerts)
            resolveRaiserNames(alerts)
            // Judged at each emission (the list's minute tick among them), so a raise
            // still pending past its window reads "Location unavailable" without
            // anything else changing.
            val now = System.currentTimeMillis()
            alerts.map {
                SosAlertRow(
                    alert = it,
                    address = addressKey(it)?.let { key -> addresses[key] },
                    position = com.pingolin.track.domain.sos.SosPosition.of(it, now),
                    raiserName = raisers[it.id],
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Looks up each other member's alert's raiser once, as [resolveAddresses] does addresses. */
    private fun resolveRaiserNames(alerts: List<SosAlertEntity>) {
        val me = sessionManager.getUserId()
        for (alert in alerts) {
            if (alert.userId == me || com.pingolin.track.util.AccountIds.same(alert.userId, me)) continue
            if (alert.id in _raiserNames.value || !raiserLookupsInFlight.add(alert.id)) continue
            viewModelScope.launch {
                val name = runCatching {
                    notificationNames.namesFor(
                        "sos_alert", alert.circleId, mapOf("sos_id" to alert.id),
                        com.pingolin.track.domain.repository.NotificationNames.Pass(),
                    ).person?.takeIf { it.isNotBlank() }
                }.getOrNull()
                _raiserNames.value = _raiserNames.value + (alert.id to name)
                raiserLookupsInFlight.remove(alert.id)
            }
        }
    }

    /** The user's distance unit, for the size of an area. */
    fun useMiles(): Boolean = sessionManager.useMiles()

    /**
     * Reverse-geocode any alert that has decrypted coordinates and no address
     * yet.
     *
     * Runs on device: the server stores the SOS location encrypted and cannot
     * answer "where is this". A failure is not cached — the tile geocoder
     * downloads tiles on demand, so an alert that couldn't resolve on the first
     * pass may well resolve on the next.
     */
    private fun resolveAddresses(alerts: List<SosAlertEntity>) {
        for (alert in alerts) {
            val key = addressKey(alert) ?: continue
            val lat = key.latitude
            val lng = key.longitude
            if (_addresses.value.containsKey(key)) continue
            if (!addressLookupsInFlight.add(key)) continue

            viewModelScope.launch {
                // placardAddress, not `text`: the city and state have to come
                // from the structured fields, or whether they appear depends on
                // which geocoder answered. One line here, so the POI name rides
                // along with the street.
                val text = try {
                    geocodingRepository.reverseGeocode(lat, lng)
                        ?.placardAddress(withPoiName = true)
                } catch (_: Exception) {
                    null
                }
                if (text != null) {
                    // Only the alert's positions as they stand: one it left goes.
                    _addresses.value = _addresses.value.filterKeys { it.alertId != alert.id } + (key to text)
                }
                // Cleared either way: a resolved position is guarded by the map
                // above, an unresolved one is free to be retried.
                addressLookupsInFlight.remove(key)
            }
        }
    }

    private val _uiState = MutableStateFlow<SosUiState>(
        // A countdown already running belongs to the app, not to this screen —
        // a ViewModel created halfway through one has to pick it up where it is.
        if (sosDispatcher.countdown.value != null) SosUiState.Countdown else SosUiState.Idle
    )
    val uiState: StateFlow<SosUiState> = _uiState.asStateFlow()

    /**
     * Straight from [com.pingolin.track.domain.sos.SosDispatcher], which owns
     * it. This screen renders the countdown but does not hold it: held here,
     * navigating away would cancel the send.
     */
    val countdown: StateFlow<Int?> = sosDispatcher.countdown

    /**
     * A raise that hasn't reached the circle, straight from the dispatcher: the SOS card says so
     * for as long as it lasts, with Cancel while it waits for a connection and Try again
     * otherwise.
     */
    val notSent: StateFlow<com.pingolin.track.domain.sos.SosDispatcher.NotSent?> = sosDispatcher.notSent

    /** Cancel a raise waiting for a connection, or close one that didn't go. */
    fun letGoNotSent() = sosDispatcher.letGo()

    /** Ask again, now, in the circle a raise didn't reach. */
    fun tryAgainNotSent() {
        _uiState.value = SosUiState.Sending
        sosDispatcher.tryAgain()
    }

    init {
        // The send can finish with nobody watching — that is the whole point of
        // it being application-scoped — so the outcome waits in a StateFlow
        // until a screen has actually SHOWN it. Deliberately not consumed here:
        // reading it is not showing it, and a ViewModel that reads it while
        // being destroyed would swallow the only news of the alert. The screen
        // consumes it via [resetState], which is what it already calls once the
        // toast is up.
        viewModelScope.launch {
            sosDispatcher.outcome.collect { outcome ->
                if (outcome == null) return@collect
                _uiState.value = when (outcome) {
                    // Whether it actually landed is checked from the send itself (deliveries).
                    is com.pingolin.track.domain.sos.SosDispatcher.Outcome.Sent -> SosUiState.Triggered(outcome.alert)
                }
            }
        }
    }

    /**
     * The circle a send is waiting on confirmation for, because the raiser has
     * hidden their location from it, or null when nothing is pending.
     *
     * WHY THIS EXISTS
     *   Notifying a circle ignores your privacy settings for that circle: the
     *   location is sealed with the CIRCLE key and every member decrypts the
     *   same ciphertext, so there is no per-member withholding to do even if we
     *   wanted one. Someone who paused sharing with a circle — often for exactly
     *   the reason you would expect — would hand that circle their exact
     *   position the moment they pressed the button, with no hint that the
     *   control they were relying on did not apply here.
     *
     *   Stripping the location instead is not done: pausing is circle-WIDE, and
     *   you choose the circle when you raise an alert, so a silent downgrade
     *   would gut a safety feature at the moment it matters. Informed consent is
     *   the honest middle: say what is about to happen, then do exactly what the
     *   user asks.
     */
    private val _privacyOverride = MutableStateFlow<PrivacyOverride?>(null)
    val privacyOverride: StateFlow<PrivacyOverride?> = _privacyOverride.asStateFlow()

    /** A pending send, and what the raiser had hidden before it. */
    data class PrivacyOverride(val circleId: String, val mode: String)

    /**
     * Entry point for the SOS button. Reads the local privacy row — deliberately
     * LOCAL, never a network call, because this runs when someone needs help and
     * must work with no signal — and either asks first or goes straight to the
     * countdown.
     *
     * An unknown row (never synced) starts the countdown rather than prompting: a
     * prompt nobody can answer would be a worse failure here than an unnecessary
     * send, and "exact" is the server default anyway.
     */
    fun requestSos(circleId: String) {
        viewModelScope.launch {
            val privacy = runCatching { privacySettingDao.get(circleId) }.getOrNull()
            val pausedNow = privacy?.pausedUntil?.let { it > System.currentTimeMillis() } == true
            // shareRealtime is a SEPARATE flag from sharingMode, and it is the
            // control the Terms call the strongest on the screen. It has to be
            // checked here too: otherwise the posture a user chooses most
            // deliberately — "stop sharing with this circle" — would be the
            // only one that produced no warning before an SOS sent an exact
            // position to it.
            val mode = when {
                pausedNow || privacy?.sharingMode == "paused" -> "paused"
                privacy?.sharingMode == "approximate" -> "approximate"
                privacy?.shareRealtime == false -> "sharing_off"
                else -> null
            }
            if (mode == null) {
                startSosCountdown(circleId)
            } else {
                _privacyOverride.value = PrivacyOverride(circleId, mode)
            }
        }
    }

    /** The user read the warning and chose to send anyway. */
    fun confirmPrivacyOverride() {
        val pending = _privacyOverride.value ?: return
        _privacyOverride.value = null
        startSosCountdown(pending.circleId)
    }

    /** The user backed out at the warning. Nothing has been sent. */
    fun dismissPrivacyOverride() {
        _privacyOverride.value = null
    }

    /**
     * Hand the countdown to the application-scoped dispatcher. Nothing about
     * it lives in this ViewModel: leaving the Safety tab cancels
     * `viewModelScope`, and with it would go the send at the end of the five
     * delays — silently, with no alert raised.
     */
    fun startSosCountdown(circleId: String) {
        _uiState.value = SosUiState.Countdown
        sosDispatcher.startCountdown(circleId)
    }

    /** The deliberate abort. No-ops once the send is already on its way. */
    fun cancelCountdown() {
        if (sosDispatcher.cancelCountdown()) {
            _uiState.value = SosUiState.Idle
        }
    }

    fun triggerSosImmediately(circleId: String) {
        _uiState.value = SosUiState.Sending
        sosDispatcher.sendNow(circleId)
    }

    fun acknowledgeSos(alertId: String) {
        viewModelScope.launch {
            _uiState.value = SosUiState.Loading
            val result = sosRepository.acknowledgeSos(alertId)
            _uiState.value = result.fold(
                onSuccess = { SosUiState.Acknowledged(it) },
                onFailure = { SosUiState.Error(it.plainText("Failed to acknowledge")) }
            )
        }
    }

    /**
     * Hide this alert for this user only. Private: no other member is told and
     * the alert stays active for them. Distinct from [acknowledgeSos], which is
     * an outward "I'm responding", and from [resolveSos], which closes it for
     * the whole circle and is the raiser's alone.
     */
    fun dismissSos(alertId: String) {
        viewModelScope.launch {
            _uiState.value = SosUiState.Loading
            val result = sosRepository.dismissSos(alertId)
            _uiState.value = result.fold(
                onSuccess = { SosUiState.Dismissed(it) },
                onFailure = { SosUiState.Error(it.plainText("Failed to dismiss")) }
            )
        }
    }

    /**
     * Undo a dismissal inside the 24h grace window. Restores the Safety row to
     * normal and puts the marker back on the map — both derive from the same
     * dismissedByMe flag, so this restores both surfaces at once.
     */
    fun undismissSos(alertId: String) {
        viewModelScope.launch {
            _uiState.value = SosUiState.Loading
            val result = sosRepository.undismissSos(alertId)
            _uiState.value = result.fold(
                onSuccess = { SosUiState.Restored(it) },
                onFailure = { SosUiState.Error(it.plainText("Failed to restore")) }
            )
        }
    }

    /**
     * Close MY request for the whole circle. Never someone else's, whoever asks:
     * both sides of an alert are personal, so nobody can affect the other.
     */
    fun resolveSos(alert: SosAlertEntity) {
        if (!isMyAlert(alert)) return
        viewModelScope.launch {
            _uiState.value = SosUiState.Loading
            val result = sosRepository.resolveSos(alert.id)
            // Over: nothing more is said of whom it
            // reached.
            if (result.isSuccess) sosDispatcher.deliveries.stop(alert.id)
            _uiState.value = result.fold(
                onSuccess = { SosUiState.Resolved(it) },
                onFailure = { SosUiState.Error(it.plainText("Failed to resolve")) }
            )
        }
    }

    /** Call MY request a false alarm. Never someone else's, as [resolveSos]. */
    fun cancelSos(alert: SosAlertEntity) {
        if (!isMyAlert(alert)) return
        viewModelScope.launch {
            _uiState.value = SosUiState.Loading
            val result = sosRepository.cancelSos(alert.id)
            if (result.isSuccess) sosDispatcher.deliveries.stop(alert.id)
            _uiState.value = result.fold(
                onSuccess = { SosUiState.Cancelled(it) },
                onFailure = { SosUiState.Error(it.plainText("Failed to cancel")) }
            )
        }
    }

    /**
     * Whether I raised [alert], in the raiser id's one spelling: it is stored as the raiser's
     * client sealed it, and the asker keeps Resolve and Cancel on every device of theirs.
     */
    fun isMyAlert(alert: SosAlertEntity): Boolean =
        AccountIds.same(alert.userId, sessionManager.getUserId())

    /**
     * Called by the screen once it has shown the state — which is also what
     * marks a send's outcome as delivered. Until then the outcome outlives
     * every ViewModel, so the raiser is told what happened even if they were
     * on another tab when it landed.
     */
    fun resetState() {
        sosDispatcher.consumeOutcome()
        _uiState.value = SosUiState.Idle
    }
}

/**
 * One row on the Safety screen: the alert plus the address its coordinates
 * reverse-geocode to.
 *
 * [address] is null while the lookup is in flight and stays null when there is
 * nothing to look up (no decryptable coordinates) or no geocoding data on the
 * device — the row renders without the line rather than with a placeholder.
 */
data class SosAlertRow(
    val alert: SosAlertEntity,
    val address: String?,
    /**
     * Where [alert]'s position stood when the row was made: a row changes with it and rebinds, as a
     * raise still pending past its window comes to read unavailable (and with nothing else, no row
     * rebinds at the list's minute tick).
     */
    val position: com.pingolin.track.domain.sos.SosPosition = com.pingolin.track.domain.sos.SosPosition.AsSent,
    /** Who asked, on someone else's card, when this phone can tell; null: "A circle member". */
    val raiserName: String? = null,
)

sealed class SosUiState {
    object Idle : SosUiState()
    object Loading : SosUiState()
    object Countdown : SosUiState()
    object Sending : SosUiState()
    data class Triggered(val alert: SosAlertEntity) : SosUiState()
    data class Acknowledged(val alert: SosAlertEntity) : SosUiState()
    data class Dismissed(val alert: SosAlertEntity) : SosUiState()
    data class Restored(val alert: SosAlertEntity) : SosUiState()
    data class Resolved(val alert: SosAlertEntity) : SosUiState()
    data class Cancelled(val alert: SosAlertEntity) : SosUiState()
    data class Error(val message: String) : SosUiState()
}
