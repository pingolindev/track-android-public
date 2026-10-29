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

package com.pingolin.track.ui.places

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.databinding.DialogCreatePlaceAlertBinding
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.plainTextOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * The place-alert editor, shared by every surface that offers one.
 *
 * It lives here rather than inside PlacesFragment because it has TWO entry points — the
 * bell in the Places list and the bell on the map's place sheet — and the expensive
 * failure mode is the same behaviour implemented twice and then diverging. One
 * implementation, two call sites.
 *
 * It is deliberately share-aware: a member who was SHARED a place can set their own
 * alert on it. They just cannot tune the alert AREA, because the radius is a single
 * per-place value the server only lets the owner change — so that control is hidden for
 * non-owners and the updatePlace call is skipped on save.
 *
 * One consistent dialog for both create (no existing alerts) and edit (pre-filled from
 * existing alerts): Arrives / Leaves chips, an "Alert about" choice of Everyone
 * (circle-wide) vs Specific people (per-member checkboxes), and a schedule plus
 * notification preferences that apply to either target mode.
 *
 * The separate "alert me about a specific person" subscription flow is not part of this
 * editor. Save uses replace semantics via [PlacesViewModel.savePlaceAlerts].
 */
fun Fragment.showPlaceAlertEditor(
    place: PlaceEntity,
    viewModel: PlacesViewModel,
    /** Settings -> Account -> Distance units. Required, not defaulted: a
     *  caller that forgets would silently show the wrong unit. */
    useMiles: Boolean,
) {
    viewLifecycleOwner.lifecycleScope.launch {
        // WAIT for the circle list before building the dialog.
        //
        // The editor reads the circles ONCE, to populate the "home" spinner. On the
        // Places screen its ViewModel has been alive since the screen opened, so
        // they are already there. Opened from the MAP, the ViewModel is created on
        // the spot and its Room flow has not emitted yet, so the snapshot is empty
        // and the spinner comes up with no circles in it. Worse: with no circles
        // the editor refuses to open at all, so reading too early tells a user who
        // IS in a circle that they aren't.
        //
        // Bounded, because "this user really has no circles" is a legitimate answer
        // and must not hang the dialog.
        val circles = viewModel.circles.value.ifEmpty {
            withTimeoutOrNull(CIRCLE_LOAD_TIMEOUT_MS) {
                viewModel.circles.first { it.isNotEmpty() }
            } ?: emptyList()
        }
        showPlaceAlertEditorWith(place, viewModel, circles, useMiles)
    }
}

/** How long to wait for the circle list before opening with what we have. */
private const val CIRCLE_LOAD_TIMEOUT_MS = 2_000L

private fun Fragment.showPlaceAlertEditorWith(
    place: PlaceEntity,
    viewModel: PlacesViewModel,
    circles: List<CircleEntity>,
    useMiles: Boolean,
) {
    val existingAlerts = viewModel.placeAlertsMap.value[place.id] ?: emptyList()
    val isEditing = existingAlerts.isNotEmpty()
    val first = existingAlerts.firstOrNull()

    // A place alert exists to tell a CIRCLE that one of its members crossed a
    // boundary. With no circle there is nobody to watch and nobody to tell, so
    // the feature is unavailable — for a personal place too. See the circle
    // picker below for why there is no circle-less "Just me" mode.
    //
    // It still opens when alerts already exist, so a stored circle-less alert can
    // be removed. Save is disabled in that state by noEligibleHome.
    if (circles.isEmpty() && !isEditing) {
        Toast.makeText(
            requireContext(),
            getString(R.string.place_alert_no_circle),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }

    val dialogBinding = DialogCreatePlaceAlertBinding.inflate(layoutInflater)
    // The day chips say the short weekday names of the language the editor's words are in (uiLocale: no
    // German "Mo" in an English editor); their order stays Monday first.
    val weekdays = java.text.DateFormatSymbols.getInstance(com.pingolin.track.util.uiLocale(requireContext())).shortWeekdays
    listOf(
        dialogBinding.chipMonday to java.util.Calendar.MONDAY,
        dialogBinding.chipTuesday to java.util.Calendar.TUESDAY,
        dialogBinding.chipWednesday to java.util.Calendar.WEDNESDAY,
        dialogBinding.chipThursday to java.util.Calendar.THURSDAY,
        dialogBinding.chipFriday to java.util.Calendar.FRIDAY,
        dialogBinding.chipSaturday to java.util.Calendar.SATURDAY,
        dialogBinding.chipSunday to java.util.Calendar.SUNDAY,
    ).forEach { (chip, day) -> chip.text = weekdays[day] }

    // --- Circle picker (single select) ---
    // The picker offers the valid homes for this alert, for BOTH create and edit:
    //  - CIRCLE place: exactly its own circle. A circle-place alert still routes +
    //    hashes under that circle's key via the server path, so seed AND lock the
    //    spinner to it. Defaulting a CREATE to circles[0] would silently home the
    //    alert to the wrong circle, whose key yields match hashes the mover's
    //    crossings never match — the alert would never fire.
    //  - PERSONAL place (circleId "self"): every circle the user belongs to.
    //    Watching anyone requires a circle you share with them — that is where
    //    their locations come from at all.
    //
    // There is deliberately no "Just me" entry. A place alert is a notification to
    // a CIRCLE about a member's crossing, so a circle-less mode offers a choice
    // the feature isn't for: on a personal place it yields an alert whose only
    // target is the user themselves. Homing to a circle still needs NO share — it
    // does not make the place's name or coordinates readable by that circle's
    // members; only sharing does.
    val eligibleHomes: List<CircleEntity> = eligibleAlertHomes(place, circles)
    // Only a circle place whose circle is missing locally has no home left.
    val noEligibleHome = eligibleHomes.isEmpty()

    // No share-state gate here, and never prompt the user to share. The backend's
    // homing rule is "the TARGET must be an active member of the chosen circle",
    // which the member picker below already guarantees — targets are drawn from
    // the chosen circle's own membership. A private place needs no share record
    // to be watched. Nudging the user to "share this place first" would tell them
    // to do the one thing this privacy model exists to prevent: sharing wraps the
    // place's content key for the circle, making its name AND coordinates
    // readable by every member.
    val circleNames = if (noEligibleHome) {
        listOf(getString(if (circles.isEmpty()) R.string.place_alert_spinner_no_circle else R.string.place_alert_spinner_unavailable))
    } else {
        eligibleHomes.map { it.name }
    }
    val circleAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, circleNames)
    circleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    dialogBinding.spinnerCircle.adapter = circleAdapter

    // Seed within eligibleHomes. When EDITING, prefer the existing alert's stored
    // routing circle (PlaceAlertEntity.circleId), then the place's own circle; a NEW
    // alert seeds to the first entry. An alert stored with NO routing circle has no
    // entry to select, so it falls back to the first circle — which is what saving
    // it will re-home it to.
    val initialCircleIndex = initialAlertHomeIndex(
        eligibleHomes = eligibleHomes,
        place = place,
        storedCircleId = if (isEditing) first?.circleId else null,
    )
    var selectedCircle: CircleEntity? = eligibleHomes.getOrNull(initialCircleIndex)
    // Lock the spinner when there's only one valid home (a circle place). Apply the
    // seed BEFORE wiring the listener so its deferred notifier fires with the seeded
    // index (keeping loadMembers consistent) rather than a spurious position 0.
    dialogBinding.spinnerCircle.isEnabled = eligibleHomes.size > 1
    if (initialCircleIndex in circleNames.indices) {
        dialogBinding.spinnerCircle.setSelection(initialCircleIndex)
    }

    // --- Trigger chips (pre-fill from existing alerts) ---
    if (isEditing) {
        dialogBinding.chipArrives.isChecked = existingAlerts.any { it.alertOnArrival }
        dialogBinding.chipLeaves.isChecked = existingAlerts.any { it.alertOnDeparture }
    }

    // --- Alert area (the per-place geofence ring these alerts fire on) ---
    // ONLY the place OWNER may tune the radius: it's a single per-place geofence
    // (radius_meters) and the server rejects updatePlace from a non-owner. A share
    // RECIPIENT (wrapViaCircle != null; owners get null) can still set THEIR own
    // per-user alert here — they just can't change the area — so hide the tuner and
    // skip the updatePlace on save, otherwise creating an alert 422s with the
    // misleading "failed to update place" and the alert-list never refreshes.
    // Gate on the server-authoritative place.isOwner, never on `wrapViaCircle == null`:
    // that proxy is TRUE for a non-creator member of a legacy circle place (they own
    // no wrap yet still can't updatePlace → 422). place.isOwner already folds in the
    // wrapViaCircle==null fallback for a backend that doesn't report it (see PlaceRepository).
    val isPlaceOwner = place.isOwner
    dialogBinding.layoutAlertArea.visibility = if (isPlaceOwner) View.VISIBLE else View.GONE
    // Seed from the place's effective geofence (explicit ring if set, else its
    // radius), snapped to a valid slider STEP. A Material Slider throws
    // (IllegalStateException → dialog crash) if its value isn't valueFrom + n*stepSize,
    // and a place's radius — e.g. the 150 m default — need not land on a step. Derive
    // the step from the slider so this stays correct if the XML step ever changes.
    val areaSlider = dialogBinding.sliderBoundaryRadius
    val rawArea = (place.boundaryRadius ?: place.radius).toFloat()
        .coerceIn(areaSlider.valueFrom, areaSlider.valueTo)
    val initialArea = (areaSlider.valueFrom +
        Math.round((rawArea - areaSlider.valueFrom) / areaSlider.stepSize) * areaSlider.stepSize)
        .coerceIn(areaSlider.valueFrom, areaSlider.valueTo)
    var alertAreaRadius = initialArea.toInt()
    areaSlider.value = initialArea
    dialogBinding.tvBoundaryRadius.text = formatRadius(initialArea, useMiles)
    dialogBinding.tvBoundaryRadiusMin.text = formatRadius(areaSlider.valueFrom, useMiles)
    dialogBinding.tvBoundaryRadiusMax.text = formatRadius(areaSlider.valueTo, useMiles)
    areaSlider.addOnChangeListener { _, value, _ ->
        dialogBinding.tvBoundaryRadius.text = formatRadius(value, useMiles)
        alertAreaRadius = value.toInt()
    }

    // --- "Alert about": which members are already targeted (circle-wide decrypts to "all") ---
    val existingTargetedUserIds = existingAlerts
        .map { it.targetUserId }
        .filter { it != "all" }
        .toSet()
    if (existingTargetedUserIds.isNotEmpty()) {
        dialogBinding.rbSpecific.isChecked = true
    }

    // --- Advanced: per-member checkboxes, (re)loaded for the selected circle ---
    val memberCheckboxMap = mutableMapOf<String, CheckBox>()
    // Who is ticked RIGHT NOW, seeded from the existing alerts. Held separately
    // from the checkboxes because the list is rebuilt whenever the members
    // re-emit — reading `isChecked` off the old views, or re-seeding from
    // `existingTargetedUserIds` alone, would silently discard ticks the user
    // had already made.
    val checkedUserIds = existingTargetedUserIds.toMutableSet()
    var memberLoadJob: Job? = null
    var loadedCircleId: String? = null
    // Has the current circle's member flow produced anything yet? NOT derivable
    // from the job: getCircleMembers returns a Room flow, so the collector stays
    // active for the dialog's whole life and `isActive` would say "still loading"
    // forever.
    var membersEmitted = false

    // The member list's visibility is derived, never set directly. It depends on
    // BOTH the radio ("Specific people") and there being a circle to draw members
    // from, and those are set from three different places — the pre-fill, the
    // circle spinner and the radio itself. Setting it at each site would let the
    // dialog open with "Specific people" ticked and an empty box below it.
    fun syncMemberPicker() {
        val showPicker = selectedCircle != null && dialogBinding.rbSpecific.isChecked
        dialogBinding.layoutMemberCheckboxes.visibility = if (showPicker) View.VISIBLE else View.GONE
        // "Still loading" and "this circle's roster is empty" are different
        // answers and a blank box gives neither. getCircleMembers kicks off a
        // network refresh and returns a Room flow, so on a cold cache there is a
        // real window with nothing to show — which is exactly the moment the user
        // is looking at it.
        dialogBinding.tvMembersEmpty.visibility =
            if (showPicker && memberCheckboxMap.isEmpty()) View.VISIBLE else View.GONE
        dialogBinding.tvMembersEmpty.text =
            getString(if (membersEmitted) R.string.place_alert_no_members else R.string.place_alert_loading_members)
    }

    fun loadMembers(circleId: String) {
        // Already showing this circle: leave the list (and the user's ticks)
        // alone. The spinner fires its selection notifier deferred, so the seeded
        // circle arrives here twice on open — reloading tears the checkboxes down
        // and rebuilds them, blanking the list until the second emission lands.
        if (loadedCircleId == circleId && memberLoadJob?.isCancelled == false) return
        // Cancel the previous circle's member collector. Without this, switching
        // circles leaves the old collector alive (and able to re-emit), racing to
        // repopulate the checkbox list with the previous circle's members.
        memberLoadJob?.cancel()
        loadedCircleId = circleId
        membersEmitted = false
        memberLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getCircleMembers(circleId).collectLatest { members ->
                membersEmitted = true
                dialogBinding.layoutMemberCheckboxes.removeAllViews()
                memberCheckboxMap.clear()
                for (member in members) {
                    val cb = CheckBox(requireContext()).apply {
                        text = member.displayName
                        tag = member.userId
                        isChecked = checkedUserIds.contains(member.userId)
                        setPadding(0, 4, 0, 4)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) checkedUserIds.add(member.userId)
                            else checkedUserIds.remove(member.userId)
                        }
                    }
                    memberCheckboxMap[member.userId] = cb
                    dialogBinding.layoutMemberCheckboxes.addView(cb)
                }
                syncMemberPicker()
            }
        }
        syncMemberPicker()
    }

    fun applyHomeSelection(home: CircleEntity?) {
        // Only unreachable-circle states have no home; the who-picker has nothing
        // to offer then.
        dialogBinding.radioWho.visibility = if (home != null) View.VISIBLE else View.GONE
        if (home != null) {
            loadMembers(home.id)
        } else {
            memberLoadJob?.cancel()
            memberLoadJob = null
            loadedCircleId = null
            dialogBinding.layoutMemberCheckboxes.removeAllViews()
            memberCheckboxMap.clear()
        }
        syncMemberPicker()
    }

    dialogBinding.spinnerCircle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            selectedCircle = eligibleHomes.getOrNull(position)
            applyHomeSelection(selectedCircle)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
    applyHomeSelection(selectedCircle)

    // "Specific people" reveals the member list; "Everyone" hides it.
    dialogBinding.radioWho.setOnCheckedChangeListener { _, _ -> syncMemberPicker() }

    // --- Schedule (pre-fill from existing alerts) ---
    var startTime = first?.activeStartTime ?: "08:00"
    var endTime = first?.activeEndTime ?: "18:00"
    val firstDays = first?.activeDays
    if (firstDays != null) {
        dialogBinding.checkboxUseSchedule.isChecked = true
        dialogBinding.scheduleContainer.visibility = View.VISIBLE
        val days = firstDays.split(",").map { it.trim() }.toSet()
        dialogBinding.chipMonday.isChecked = days.contains("monday")
        dialogBinding.chipTuesday.isChecked = days.contains("tuesday")
        dialogBinding.chipWednesday.isChecked = days.contains("wednesday")
        dialogBinding.chipThursday.isChecked = days.contains("thursday")
        dialogBinding.chipFriday.isChecked = days.contains("friday")
        dialogBinding.chipSaturday.isChecked = days.contains("saturday")
        dialogBinding.chipSunday.isChecked = days.contains("sunday")
    }

    dialogBinding.checkboxUseSchedule.setOnCheckedChangeListener { _, isChecked ->
        dialogBinding.scheduleContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
    }

    dialogBinding.btnStartTime.text = startTime
    dialogBinding.btnStartTime.setOnClickListener {
        val parts = startTime.split(":")
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(parts[0].toIntOrNull() ?: 8)
            .setMinute(parts.getOrNull(1)?.toIntOrNull() ?: 0)
            .setTitleText(getString(R.string.place_alert_pick_start))
            .build()
        picker.addOnPositiveButtonClickListener {
            startTime = String.format("%02d:%02d", picker.hour, picker.minute)
            dialogBinding.btnStartTime.text = startTime
        }
        picker.show(parentFragmentManager, "start_time_picker")
    }

    dialogBinding.btnEndTime.text = endTime
    dialogBinding.btnEndTime.setOnClickListener {
        val parts = endTime.split(":")
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(parts[0].toIntOrNull() ?: 18)
            .setMinute(parts.getOrNull(1)?.toIntOrNull() ?: 0)
            .setTitleText(getString(R.string.place_alert_pick_end))
            .build()
        picker.addOnPositiveButtonClickListener {
            endTime = String.format("%02d:%02d", picker.hour, picker.minute)
            dialogBinding.btnEndTime.text = endTime
        }
        picker.show(parentFragmentManager, "end_time_picker")
    }

    // --- Notification preferences (pre-fill from existing alerts) ---
    if (first != null) {
        dialogBinding.checkboxNotifyPush.isChecked = first.notifyPush
        dialogBinding.checkboxNotifyEmail.isChecked = first.notifyEmail
    }

    val builder = MaterialAlertDialogBuilder(requireContext())
        .setTitle(
            place.name.takeIf { it.isNotBlank() }
                ?.let { getString(if (isEditing) R.string.place_alert_title_edit else R.string.place_alert_title_new, it) }
                ?: getString(if (isEditing) R.string.place_alert_title_edit_unnamed else R.string.place_alert_title_new_unnamed)
        )
        .setView(dialogBinding.root)
        // Save is deliberately NOT wired here: setPositiveButton dismisses the
        // dialog before the coroutine can report a result, so a rejected save
        // would discard the user's whole edit. The listener is attached after
        // show() instead — see onSave below.
        .setPositiveButton(getString(R.string.save), null)
    // Returns the in-flight save to await, or null when a local validation
    // refused it (nothing was sent, so the dialog just stays open).
    val onSave: () -> kotlinx.coroutines.Deferred<Result<Unit>>? = onSave@{
            dialogBinding.tvAlertError.visibility = View.GONE
            val chosenCircle = selectedCircle
            val alertOnArrival = dialogBinding.chipArrives.isChecked
            val alertOnDeparture = dialogBinding.chipLeaves.isChecked
            if (!alertOnArrival && !alertOnDeparture) {
                Toast.makeText(requireContext(), getString(R.string.place_alert_pick_trigger), Toast.LENGTH_SHORT).show()
                return@onSave null
            }
            val alertType = when {
                alertOnArrival && alertOnDeparture -> "both"
                alertOnArrival -> "arrival"
                else -> "departure"
            }

            val useSchedule = dialogBinding.checkboxUseSchedule.isChecked
            val activeDays: List<String>?
            val activeStartTime: String?
            val activeEndTime: String?
            val isRecurring: Boolean
            if (useSchedule) {
                activeDays = buildList {
                    if (dialogBinding.chipMonday.isChecked) add("monday")
                    if (dialogBinding.chipTuesday.isChecked) add("tuesday")
                    if (dialogBinding.chipWednesday.isChecked) add("wednesday")
                    if (dialogBinding.chipThursday.isChecked) add("thursday")
                    if (dialogBinding.chipFriday.isChecked) add("friday")
                    if (dialogBinding.chipSaturday.isChecked) add("saturday")
                    if (dialogBinding.chipSunday.isChecked) add("sunday")
                }
                activeStartTime = startTime
                activeEndTime = endTime
                // A schedule implies recurring; there is no separate toggle.
                isRecurring = true
            } else {
                activeDays = null
                activeStartTime = null
                activeEndTime = null
                isRecurring = true
            }

            val notifyPush = dialogBinding.checkboxNotifyPush.isChecked
            val notifyEmail = dialogBinding.checkboxNotifyEmail.isChecked
            // No SMS toggle in this dialog. On EDIT, round-trip the existing alert's
            // opt-in so an Android save doesn't clear an SMS opt-in set from web/iOS
            // (the backend UPDATE is a full replace, same reasoning as the
            // expiry/recurring/active fields below); on CREATE (first == null) keep false.
            val notifySms = first?.notifySms ?: false

            // Every alert is homed to a circle. The only way to reach Save without
            // one is the unreachable-circle state, where Save is already disabled
            // — guard rather than assume, so a path that opens the dialog
            // differently can't post a circle-less alert.
            if (chosenCircle == null) {
                Toast.makeText(requireContext(), getString(R.string.place_alert_pick_circle), Toast.LENGTH_SHORT).show()
                return@onSave null
            }

            // Who: Everyone (circle-wide) vs Specific people (per-member). Read from
            // `checkedUserIds`, not from the checkbox views: the list is rebuilt on
            // every member re-emission, so a tick made before the last emission does
            // not survive on the view it was made on.
            val specific = dialogBinding.rbSpecific.isChecked
            val targetUserIds = if (specific) {
                checkedUserIds.filter { memberCheckboxMap.containsKey(it) }
            } else {
                emptyList()
            }
            if (specific && targetUserIds.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.place_alert_pick_people), Toast.LENGTH_SHORT).show()
                return@onSave null
            }

            // Persist the alert area (the per-place geofence radius) when the OWNER
            // moved the slider. Recipients never reach here (tuner hidden + gated) —
            // updatePlace from a non-owner 422s ("failed to update place"). Skip an
            // unchanged radius so opening the bell just to tweak recipients doesn't
            // churn the row through an E2EE re-encrypt. updatePlace re-encrypts
            // name+location from the existing row, so a radius-only write is safe.
            if (isPlaceOwner && alertAreaRadius != initialArea.toInt()) {
                viewModel.updatePlace(
                    placeId = place.id,
                    radius = alertAreaRadius,
                    boundaryRadius = alertAreaRadius,
                )
            }

            viewModel.savePlaceAlerts(
                placeId = place.id,
                circleId = chosenCircle?.id,
                targetUserIds = targetUserIds,
                alertType = alertType,
                activeDays = activeDays,
                activeStartTime = activeStartTime,
                activeEndTime = activeEndTime,
                // On EDIT, round-trip the existing alert's expiry / recurring / active
                // state — the Android editor has no control for them, and the backend
                // UPDATE is a full replace (not a partial merge), so passing the
                // create-time literals here would wipe an expiry (or recurring/active
                // flag) set from web or iOS. On CREATE (first == null) keep the create
                // defaults. The stored expiry is epoch-millis; the request wants RFC3339
                // (the inverse of ServerTime.epochMs in
                // PlaceRepository.refreshPlaceAlerts).
                isRecurring = first?.isRecurring ?: isRecurring,
                expiresAt = first?.expiresAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
                isActive = first?.isActive ?: true,
                notifyPush = notifyPush,
                notifyEmail = notifyEmail,
                notifySms = notifySms
            )
    }

    builder.setNegativeButton(getString(R.string.cancel), null)

    if (isEditing) {
        builder.setNeutralButton(getString(R.string.remove)) { _, _ ->
            viewModel.removePlaceAlerts(place.id)
        }
    }

    val dialog = builder.show()
    // Dismiss only on success. On failure keep the editor open with the user's
    // edit intact and show the SERVER's own reason in place. A rejected save that
    // closes the dialog and reverts is indistinguishable, to the user, from "it
    // saved and then silently un-saved itself".
    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
        val deferred = onSave() ?: return@setOnClickListener
        viewLifecycleOwner.lifecycleScope.launch {
            val result = deferred.await()
            if (result.isSuccess) {
                dialog.dismiss()
            } else {
                dialogBinding.tvAlertError.text =
                    result.exceptionOrNull()?.plainTextOrNull() ?: getString(R.string.place_alert_save_failed)
                dialogBinding.tvAlertError.visibility = View.VISIBLE
            }
        }
    }
    // No home to save to: either a circle place whose circle isn't on this device,
    // or the user has no circles at all and is only here to remove a stored
    // circle-less alert. Save off, Remove still on.
    if (noEligibleHome) {
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
        Toast.makeText(
            requireContext(),
            getString(if (circles.isEmpty()) R.string.place_alert_no_circle else R.string.place_alert_circle_not_here),
            Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * The circles an alert on [place] may be homed to.
 *
 *  - A CIRCLE place: exactly its own circle, and only if this device holds it.
 *    A circle-place alert routes and hashes under that circle's key, so any
 *    other circle produces match hashes the mover's crossings never match.
 *  - A PERSONAL place: every circle the user belongs to. Watching anyone needs
 *    a circle you share with them — that is where their locations come from.
 *
 * Empty means the alert has nowhere to live: the user is in no circles at all,
 * or a circle place's circle hasn't synced to this device. Never contains a
 * "no circle" entry — see the caller for why that mode does not exist.
 */
internal fun eligibleAlertHomes(
    place: PlaceEntity,
    circles: List<CircleEntity>,
): List<CircleEntity> {
    val personalIds = setOf(PlaceRepository.PERSONAL_CIRCLE_ID, PlaceRepository.PERSONAL_CIRCLE_UUID)
    return if (place.circleId in personalIds) {
        circles
    } else {
        circles.filter { it.id == place.circleId }
    }
}

/**
 * Which entry of [eligibleHomes] the picker opens on.
 *
 * Prefers the editing alert's own routing circle, then the place's circle,
 * then the circle a place shared with you came through, then the first circle
 * your own place is shared into, then the first eligible one — so a place
 * shared into X opens on X, not on whichever circle comes first.
 * [storedCircleId] being null covers both a fresh create and a stored
 * circle-less alert.
 */
internal fun initialAlertHomeIndex(
    eligibleHomes: List<CircleEntity>,
    place: PlaceEntity,
    storedCircleId: String?,
): Int {
    fun eligible(cid: String?): String? = cid?.takeIf { c -> eligibleHomes.any { it.id == c } }
    val seedCircleId = eligible(storedCircleId)
        ?: eligible(place.circleId)
        ?: eligible(place.wrapViaCircle)
        ?: place.sharedCircles?.split(",")?.map { it.trim() }?.firstNotNullOfOrNull { eligible(it) }
    return eligibleHomes.indexOfFirst { it.id == seedCircleId }.coerceAtLeast(0)
}

private fun Fragment.formatRadius(meters: Float, useMiles: Boolean): String =
    formatDistance(meters / 1000.0, useMiles, com.pingolin.track.util.uiLocale(requireContext()))
