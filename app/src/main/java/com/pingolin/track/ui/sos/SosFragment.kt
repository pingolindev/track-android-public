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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.databinding.FragmentSosBinding
import com.pingolin.track.domain.sos.SosDispatcher
import com.pingolin.track.ui.main.MainViewModel
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SosFragment : Fragment() {

    private var _binding: FragmentSosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SosViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    /** Who is responding to my own request, by name. */
    @javax.inject.Inject
    lateinit var myAlertResponders: com.pingolin.track.domain.repository.MyAlertResponders

    private lateinit var alertsAdapter: SosAlertsAdapter

    /**
     * Held so successive delivery polls can update the same dialog in place
     * instead of stacking a new one every three seconds. Cleared in
     * onDestroyView — a dialog outliving the fragment's window leaks it.
     */
    private var deliveryDialog: AlertDialog? = null
    private var privacyOverrideDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        alertsAdapter = SosAlertsAdapter(
            onAcknowledgeClick = { alert -> viewModel.acknowledgeSos(alert.id) },
            onResolveClick = { alert -> viewModel.resolveSos(alert) },
            onCancelClick = { alert -> viewModel.cancelSos(alert) },
            onAlertClick = { alert -> showAlertOnMap(alert) },
            onDismissClick = { alert -> viewModel.dismissSos(alert.id) },
            onUndismissClick = { alert -> viewModel.undismissSos(alert.id) },
            isMyAlert = { alert -> viewModel.isMyAlert(alert) },
            onLocationSettingsClick = { openLocationSettings() },
            locationOff = { com.pingolin.track.domain.sos.SosPositionChooser.locationOff(requireContext()) },
        )
        alertsAdapter.useMiles = viewModel.useMiles()

        binding.rvActiveAlerts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = alertsAdapter
        }
    }

    /**
     * Where location can be turned on: the app's own page while it has no location permission, else
     * the phone's Location setting (Location Services off). The app's page when that can't open.
     */
    private fun openLocationSettings() {
        val context = requireContext()
        val permitted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val appPage = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
        val intent = if (permitted) android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS) else appPage
        try {
            startActivity(intent)
        } catch (_: Exception) {
            runCatching { startActivity(appPage) }
        }
    }

    private fun setupUI() {
        binding.btnSos.setOnClickListener {
            showCircleSelectionDialog()
        }

        binding.btnCancelCountdown.setOnClickListener {
            viewModel.cancelCountdown()
        }

        binding.btnNotSentTryAgain.setOnClickListener { viewModel.tryAgainNotSent() }
        binding.btnNotSentLetGo.setOnClickListener { viewModel.letGoNotSent() }

        // Safety Rules card — direct access (the toolbar alert button was removed)
        binding.cardSafetyRules.setOnClickListener {
            findNavController().navigate(R.id.alertsContainerFragment)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.circles.collectLatest { circles ->
                // Update circle selection if needed
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // STARTED-scoped for the same reason as MainActivity's
            // badge: the rows ride a 60 s ticker, and a view left on
            // the Safety tab would keep it running in the background.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.alertRows.collectLatest { alerts ->
                    alertsAdapter.submitList(alerts)
                    // My own requests someone has answered: who, by name. Only those, so the list
                    // asks the server for nothing on others' alerts.
                    alertsAdapter.responders = alerts.map { it.alert }
                        .filter { viewModel.isMyAlert(it) && it.acknowledgmentCount > 0 }
                        .mapNotNull { alert -> myAlertResponders.copyFor(alert)?.let { alert.id to it } }
                        .toMap()

                    if (alerts.isEmpty()) {
                        binding.tvNoActiveAlerts.visibility = View.VISIBLE
                        binding.rvActiveAlerts.visibility = View.GONE
                    } else {
                        binding.tvNoActiveAlerts.visibility = View.GONE
                        binding.rvActiveAlerts.visibility = View.VISIBLE
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(viewModel.countdown, viewModel.notSent) { seconds, notSent -> seconds to notSent }
                .collectLatest { (seconds, notSent) -> renderRaise(seconds, notSent) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is SosUiState.Triggered -> {
                        // NOT "sent to your circle" — at this point the server
                        // has stored the alert and nothing more. The delivery
                        // dialog below reports what actually reached anyone.
                        Toast.makeText(requireContext(), R.string.sos_sent, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is SosUiState.Acknowledged -> {
                        Toast.makeText(requireContext(), R.string.sos_acknowledge, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is SosUiState.Restored -> {
                        Toast.makeText(requireContext(), R.string.sos_restored, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is SosUiState.Dismissed -> {
                        Toast.makeText(requireContext(), R.string.sos_dismissed, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is SosUiState.Resolved -> {
                        Toast.makeText(requireContext(), R.string.sos_resolve, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is SosUiState.Cancelled -> {
                        Toast.makeText(requireContext(), R.string.sos_false_alarm, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is SosUiState.Error -> {
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.delivery.collectLatest { status -> renderDelivery(status) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.privacyOverride.collectLatest { pending -> renderPrivacyOverride(pending) }
        }
    }

    /**
     * The SOS card: the button, the countdown, or a raise that hasn't reached the circle.
     * That one stays, in place of the button, until it goes or the raiser lets it go: never a
     * toast gone in three seconds, never the exception's own words.
     */
    private fun renderRaise(seconds: Int?, notSent: SosDispatcher.NotSent?) {
        val card = raiseCard(seconds, notSent)
        binding.btnSos.visibility = if (card.button) View.VISIBLE else View.GONE
        binding.countdownContainer.visibility = if (card.countdown) View.VISIBLE else View.GONE
        if (seconds != null) {
            binding.tvCountdown.text = seconds.toString()
            // Zero means the countdown is spent and the request is
            // already on its way, which nothing can call back. Take the
            // button away rather than leave one that does nothing.
            binding.btnCancelCountdown.visibility =
                if (seconds > 0) View.VISIBLE else View.GONE
        }
        binding.notSentContainer.visibility = if (card.notSent != null) View.VISIBLE else View.GONE
        card.notSent?.let { binding.tvNotSent.setText(it) }
        binding.btnNotSentTryAgain.visibility = if (card.tryAgain) View.VISIBLE else View.GONE
        binding.btnNotSentLetGo.setText(card.letGo)
    }

    /**
     * Asks before notifying a circle the user has hidden their location from.
     *
     * Nothing has been sent at this point — the countdown has not started — so
     * dismissing costs the user nothing but a tap. Cancel is the default action
     * (dismissing by back or scrim lands there too), because the person who
     * paused sharing with this circle is the one whose choice we are about to
     * override.
     */
    private fun renderPrivacyOverride(pending: SosViewModel.PrivacyOverride?) {
        privacyOverrideDialog?.dismiss()
        privacyOverrideDialog = null
        if (pending == null) return

        val circleName = viewModel.circles.value
            .firstOrNull { it.id == pending.circleId }
            ?.name
            ?: getString(R.string.sos_privacy_override_this_circle)

        val override = when (pending.mode) {
            "paused" -> getString(R.string.sos_privacy_override_paused, circleName)
            "sharing_off" -> getString(R.string.sos_privacy_override_sharing_off, circleName)
            else -> getString(R.string.sos_privacy_override_approximate, circleName)
        }
        // The reach explanation rides along on this dialog too. It is the other
        // pre-send surface, and a user who lands here never sees the countdown
        // copy unless they confirm — so leaving it off would mean the one path
        // with an explicit "send anyway" decision was the path that said least
        // about what sending does.
        val body = "$override\n\n" + getString(R.string.sos_reach_explanation)

        // Message on the BUILDER: AlertDialog inflates its message TextView only
        // when setMessage precedes create(), so setting it afterwards renders an
        // empty dialog.
        privacyOverrideDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sos_privacy_override_title)
            .setMessage(body)
            .setPositiveButton(R.string.sos_privacy_override_send) { _, _ ->
                viewModel.confirmPrivacyOverride()
            }
            .setNegativeButton(R.string.sos_privacy_override_cancel) { _, _ ->
                viewModel.dismissPrivacyOverride()
            }
            .setOnCancelListener { viewModel.dismissPrivacyOverride() }
            .show()
    }

    /**
     * The delivery dialog: what actually happened to the alert this user just
     * raised.
     *
     * Held open across polls and updated in place rather than dismissed and
     * re-shown, so the text can move from "checking" to a verdict without the
     * dialog flickering once every three seconds.
     *
     * Not cancellable by touching outside. Every terminal state here is
     * something the user needs to have read — "this reached nobody" dismissed by
     * a stray tap is the one outcome that must not be missable.
     */
    private fun renderDelivery(status: com.pingolin.track.domain.sos.SosDeliveryWatcher.Status?) {
        if (status == null) {
            deliveryDialog?.dismiss()
            deliveryDialog = null
            return
        }

        val message = deliveryMessage(requireContext(), status)

        val dialog = deliveryDialog ?: MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sos_delivery_title)
            // The message MUST be supplied to the BUILDER, not only
            // to the created dialog. AlertDialog inflates its
            // message TextView only when a message exists at
            // create() time; build it without one and every later
            // setMessage() updates a view that was never added, so
            // the dialog renders with a title, a button, and an
            // empty body.
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.clearDelivery() }
            .create()
            .also {
                deliveryDialog = it
                it.setOnDismissListener { deliveryDialog = null }
                it.show()
            }

        // Subsequent polls update in place; the TextView exists now.
        dialog.setMessage(message)
    }

    /**
     * Jump to this alert's marker on the map.
     *
     * The zoom request goes through the activity-scoped MainViewModel, which
     * MapFragment already drains on view creation — so it survives the tab
     * switch and applies whether or not the map is currently instantiated. No
     * circle switch is needed: SOS markers are drawn regardless of which circle
     * the map is filtered to.
     */
    private fun showAlertOnMap(alert: com.pingolin.track.data.local.entity.SosAlertEntity) {
        val lat = alert.latitude
        val lng = alert.longitude
        if (lat == null || lng == null) {
            // Location that never decrypted (a key we don't hold). There is
            // nowhere to send them — say so instead of opening an empty map.
            Toast.makeText(requireContext(), R.string.sos_no_location, Toast.LENGTH_SHORT).show()
            return
        }
        // An area is fitted whole, never zoomed to its centre.
        mainViewModel.requestMapZoom(lat, lng, radiusMeters = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert))
        findNavController().navigate(R.id.mapFragment)
    }

    private fun showCircleSelectionDialog() {
        val circles = viewModel.circles.value
        if (circles.isEmpty()) {
            // No circle to raise in: circles this phone can't read now, or someone in
            // none. Either way, help another way, in a dialog that stays until OK:
            // never a two-second toast.
            val unreadable = com.pingolin.track.util.DatabaseUnavailable.failure.value != null
            MaterialAlertDialogBuilder(requireContext())
                .apply { if (unreadable) setTitle(R.string.sos_cant_notify_title) }
                .setMessage(if (unreadable) R.string.sos_cant_notify_now else R.string.sos_no_circle_to_notify)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        if (circles.size == 1) {
            viewModel.requestSos(circles.first().id)
            return
        }

        val circleNames = circles.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Notify which circle?")
            .setItems(circleNames) { _, which ->
                viewModel.requestSos(circles[which].id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // While this tab is on screen, its delivery dialog says what a request reached; elsewhere,
    // MainActivity's dialog or a notification does. Started, not only resumed: a screen over it
    // that lets it show through (Android's permission question) pauses it, and its dialog is
    // still seen, so another would be one too many.
    override fun onStart() {
        super.onStart()
        viewModel.safetyTabShown(true)
    }

    override fun onStop() {
        viewModel.safetyTabShown(false)
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Dismiss before dropping the reference: a shown dialog holds the
        // fragment's window and leaks it if the view goes away underneath it.
        deliveryDialog?.dismiss()
        deliveryDialog = null
        privacyOverrideDialog?.dismiss()
        privacyOverrideDialog = null
        _binding = null
    }
}

/**
 * The delivery dialog's text: one sentence per state, plus the caveats that apply to it. Checks that
 * ended with no answer at all, or with one still on its way, say so (as iOS does), never
 * "Checking…" for good.
 */
internal fun deliveryMessage(context: android.content.Context, status: com.pingolin.track.domain.sos.SosDeliveryWatcher.Status): String {
    val unanswered = context.getString(
        if (status.stillChecking) R.string.sos_delivery_checking else R.string.sos_delivery_no_answer
    )
    val d = status.response ?: return unanswered

    val head = when (d.state) {
        "alone" -> context.getString(R.string.sos_delivery_alone)
        "confirmed" -> context.getString(R.string.sos_delivery_confirmed, d.recipients)
        "partial" -> context.getString(R.string.sos_delivery_partial, d.confirmed, d.recipients)
        "no_confirmation" -> context.getString(R.string.sos_delivery_no_confirmation)
        "undeliverable" -> context.getString(R.string.sos_delivery_undeliverable)
        else -> unanswered
    }

    return buildString {
        append(head)
        if (d.emailed > 0) {
            append("\n\n")
            append(context.getString(R.string.sos_delivery_emailed, d.emailed))
        }
        // The distinction the whole dialog rests on: a device that confirmed
        // is not a person who has looked.
        if (d.confirmed > 0) {
            append("\n\n")
            append(context.getString(R.string.sos_delivery_not_response))
        }
    }
}

/** What the SOS card shows: the button, the countdown, or a raise that hasn't reached the circle. */
internal data class RaiseCard(
    val button: Boolean,
    val countdown: Boolean,
    /** The not-sent line, or null when none shows. */
    @androidx.annotation.StringRes val notSent: Int?,
    val tryAgain: Boolean,
    @androidx.annotation.StringRes val letGo: Int,
)

/**
 * The SOS card for a countdown at [seconds] (null: none) and [notSent]. A raise that hasn't reached
 * the circle shows in place of the button: waiting for a connection, it goes by itself, so Cancel
 * only; otherwise the raiser decides, Try again or Close. A countdown, a new press, comes first.
 */
internal fun raiseCard(seconds: Int?, notSent: SosDispatcher.NotSent?): RaiseCard {
    val unsent = notSent.takeIf { seconds == null }
    val waiting = unsent?.why == SosDispatcher.NotSent.Why.WAITING
    return RaiseCard(
        button = seconds == null && unsent == null,
        countdown = seconds != null,
        notSent = when (unsent?.why) {
            null -> null
            // Waiting on this phone's session store, not a connection: its own words.
            SosDispatcher.NotSent.Why.WAITING ->
                if (unsent.storeRefused) R.string.sos_not_sent_waiting_store else R.string.sos_not_sent_waiting
            SosDispatcher.NotSent.Why.UNSURE -> R.string.sos_not_sent_unsure
            SosDispatcher.NotSent.Why.NOT_SENT -> R.string.sos_not_sent
        },
        tryAgain = unsent != null && !waiting,
        letGo = if (waiting) R.string.sos_cancel else R.string.sos_not_sent_close,
    )
}
