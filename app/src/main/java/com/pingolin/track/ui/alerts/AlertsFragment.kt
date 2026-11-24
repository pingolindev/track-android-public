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

package com.pingolin.track.ui.alerts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.databinding.DialogAlertEditorBinding
import com.pingolin.track.databinding.FragmentAlertRulesBinding
import com.pingolin.track.ui.alerts.inactivity.InactivityAlertsUiState
import com.pingolin.track.ui.alerts.inactivity.InactivityAlertsViewModel
import com.pingolin.track.ui.alerts.rules.AlertRulesUiState
import com.pingolin.track.ui.alerts.rules.AlertRulesViewModel
import com.pingolin.track.ui.start.UnreadableEmptyState
import com.pingolin.track.util.CheckInModes
import com.pingolin.track.util.CheckInRuleText
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.CheckInScheduleSetting
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.showDestructive
import com.pingolin.track.util.uiLocale
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.DayOfWeek
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single "Alerts" list that merges battery/offline rules and inactivity alerts. The two
 * backends/ViewModels stay separate; this fragment hosts both and routes each action by type.
 * The FAB opens a unified create dialog (Battery/Inactivity/Check-in chooser; a check-in rule is an
 * inactivity alert that watches check-ins, within a time or by a time of day); tapping a row opens
 * the same dialog in edit mode.
 */
@AndroidEntryPoint
class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertRulesBinding? = null
    private val binding get() = _binding!!

    private val rulesVm: AlertRulesViewModel by viewModels()
    private val inactivityVm: InactivityAlertsViewModel by viewModels()
    private lateinit var adapter: UnifiedAlertAdapter
    private lateinit var pendingAdapter: com.pingolin.track.ui.pending.PendingChangesAdapter
    private var shownAlerts = 0
    private var shownPending = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupUI()
        observe()
    }

    private fun setupRecyclerView() {
        adapter = UnifiedAlertAdapter(
            onClick = { showEditor(it) },
            onToggle = { item, active ->
                when (item) {
                    is UnifiedAlert.Battery -> rulesVm.toggleRule(item.id, active)
                    is UnifiedAlert.Inactivity -> inactivityVm.toggleAlert(item.id, active)
                }
            },
            onDelete = { showDeleteConfirmation(it) }
        )
        // Alerts waiting to sync go first.
        pendingAdapter = com.pingolin.track.ui.pending.PendingChangesAdapter { rulesVm.pending.cancel(it) }
        binding.rvAlertRules.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = androidx.recyclerview.widget.ConcatAdapter(pendingAdapter, this@AlertsFragment.adapter)
        }
    }

    private fun setupUI() {
        binding.fabCreateRule.setOnClickListener { showEditor(null) }
        binding.swipeRefresh.setOnRefreshListener {
            rulesVm.refreshAlertRules()
            inactivityVm.refreshInactivityAlerts()
        }
    }

    private fun showListOrEmpty() {
        val empty = shownAlerts == 0 && shownPending == 0
        UnreadableEmptyState.show(binding.emptyState, empty, binding.fabCreateRule)
        binding.rvAlertRules.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                rulesVm.alertRules, inactivityVm.inactivityAlerts, rulesVm.circles, inactivityVm.selfCheckInCountdowns,
            ) { rules, inact, circles, countdowns ->
                val nameById = circles.associate { it.id to it.name }
                rules.map { UnifiedAlert.Battery(it, it.circleId?.let { id -> nameById[id] }) } +
                    inact.map { UnifiedAlert.Inactivity(it, nameById[it.circleId], countdowns[it.id]) }
            }.collect { items ->
                binding.swipeRefresh.isRefreshing = false
                adapter.submitList(items)
                shownAlerts = items.size
                showListOrEmpty()
            }
        }

        // The countdown on a check-in rule about yourself: your check-ins read from the server each
        // time the screen opens, and the line redrawn as its minutes go by while it shows.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                inactivityVm.readSelfCheckIns()
                while (true) delay(adapter.tickCountdowns() ?: COUNTDOWN_IDLE_MS)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            rulesVm.pending.items.collect { items ->
                pendingAdapter.submitList(items)
                shownPending = items.size
                showListOrEmpty()
                rulesVm.pending.seen(items)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            rulesVm.uiState.collect { state ->
                when (state) {
                    is AlertRulesUiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                    is AlertRulesUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        rulesVm.resetState()
                    }
                    is AlertRulesUiState.RuleCreated -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Alert created", Toast.LENGTH_SHORT).show()
                        rulesVm.resetState()
                    }
                    is AlertRulesUiState.RuleDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Alert deleted", Toast.LENGTH_SHORT).show()
                        rulesVm.resetState()
                    }
                    is AlertRulesUiState.RuleUpdated -> {
                        binding.progressBar.visibility = View.GONE
                        rulesVm.resetState()
                    }
                    is AlertRulesUiState.Idle -> binding.progressBar.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            inactivityVm.uiState.collect { state ->
                when (state) {
                    is InactivityAlertsUiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                    is InactivityAlertsUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        inactivityVm.resetState()
                    }
                    is InactivityAlertsUiState.AlertCreated -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Alert created", Toast.LENGTH_SHORT).show()
                        inactivityVm.resetState()
                    }
                    is InactivityAlertsUiState.AlertDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Alert deleted", Toast.LENGTH_SHORT).show()
                        inactivityVm.resetState()
                    }
                    is InactivityAlertsUiState.AlertUpdated -> {
                        binding.progressBar.visibility = View.GONE
                        inactivityVm.resetState()
                    }
                    is InactivityAlertsUiState.Idle -> binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showEditor(existing: UnifiedAlert?) {
        val b = DialogAlertEditorBinding.inflate(layoutInflater)
        val isEdit = existing != null

        b.seekbarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                b.tvThresholdValue.text = "${progress + 5}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        b.seekbarThreshold.progress = 15

        b.seekbarTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val h = progress + 1
                b.tvTimeoutValue.text = if (h == 1) "1 hour" else "$h hours"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        b.seekbarTimeout.progress = 3

        // A scheduled check-in rule's schedule as the editor holds it: a stored rule's, or by
        // 9:00 PM every day, counting from the start of the day, in this phone's zone.
        val context = requireContext()
        val phoneZone = CheckInSchedule.phoneZoneId() ?: "UTC"
        val loaded = (existing as? UnifiedAlert.Inactivity)?.alert?.let { CheckInScheduleSetting.of(it) }
        var dueMinute = loaded?.dueMinute ?: CheckInScheduleEditor.DEFAULT_DUE
        // The "Counting from" time, kept while "Start of day" is chosen.
        var fromMinute = loaded?.fromMinute ?: CheckInScheduleEditor.DEFAULT_FROM
        // A zone picked; null: the phone's (followed, on a rule about yourself).
        var zone: String? = loaded?.let { CheckInScheduleEditor.zoneOf(it) }
        // The day chips say the short weekday names of the language the editor's words are in; their
        // order stays Monday first, as the place alerts'.
        val weekdayNames = java.text.DateFormatSymbols.getInstance(uiLocale(context)).shortWeekdays
        val dayChips = listOf(
            DayOfWeek.MONDAY to b.chipMonday, DayOfWeek.TUESDAY to b.chipTuesday, DayOfWeek.WEDNESDAY to b.chipWednesday,
            DayOfWeek.THURSDAY to b.chipThursday, DayOfWeek.FRIDAY to b.chipFriday, DayOfWeek.SATURDAY to b.chipSaturday,
            DayOfWeek.SUNDAY to b.chipSunday,
        )
        dayChips.forEach { (day, chip) ->
            chip.text = weekdayNames[day.value % 7 + 1]
            chip.isChecked = loaded == null || loaded.days and CheckInSchedule.bit(day) != 0
        }
        if (loaded?.fromMinute != null) b.rbFromTime.isChecked = true
        fun showScheduleTimes() {
            b.btnDueTime.text = CheckInRuleText.time(context, dueMinute)
            b.rbFromTime.text = CheckInRuleText.time(context, fromMinute)
        }
        fun showZone() {
            b.btnTimeZone.text = CheckInScheduleEditor.zoneLabel(context, zone, phoneZone, b.checkboxSelfAlert.isChecked)
        }
        showScheduleTimes()
        b.btnDueTime.setOnClickListener {
            pickTime(R.string.check_in_by, dueMinute) { dueMinute = it; showScheduleTimes() }
        }
        // A tap on the time choice opens its picker, whether or not it was chosen.
        b.rbFromTime.setOnClickListener {
            pickTime(R.string.check_in_counting_from, fromMinute) { fromMinute = it; b.rbFromTime.isChecked = true; showScheduleTimes() }
        }
        b.btnTimeZone.setOnClickListener {
            // "Phone's time zone" is offered for a rule about yourself only: it follows your phone.
            TimeZonePicker.show(context, layoutInflater, phoneZone, includePhone = b.checkboxSelfAlert.isChecked) { picked ->
                zone = picked
                showZone()
            }
        }
        // What Save makes of the schedule; it says what is wrong and saves nothing when it can't.
        val scheduleChoice: () -> CheckInScheduleEditor.Choice = {
            CheckInScheduleEditor.choice(
                days = dayChips.filter { (_, chip) -> chip.isChecked }.sumOf { (day, _) -> CheckInSchedule.bit(day) },
                dueMinute = dueMinute,
                fromMinute = fromMinute.takeIf { b.rbFromTime.isChecked },
                zone = zone,
                phoneZone = phoneZone,
                aboutMe = b.checkboxSelfAlert.isChecked,
            )
        }

        // The inactivity section serves both kinds of inactivity rule: location updates, in the
        // layout's words, and check-ins, in their own, within a time or by a time of day.
        val locationTimeoutLabel = b.labelTimeout.text
        val locationSelfLabel = b.checkboxSelfAlert.text
        fun applyKind(checkIn: Boolean, self: Boolean) {
            val scheduled = checkIn && b.rbCheckInScheduled.isChecked
            // The two kinds are chosen when the rule is made; an edit keeps its kind.
            b.rgCheckInKind.visibility = if (checkIn && !isEdit) View.VISIBLE else View.GONE
            b.layoutTimeout.visibility = if (scheduled) View.GONE else View.VISIBLE
            b.layoutSchedule.visibility = if (scheduled) View.VISIBLE else View.GONE
            b.labelTimeout.text = if (checkIn) getString(R.string.check_in_within) else locationTimeoutLabel
            b.checkboxSelfAlert.text = if (checkIn) getString(R.string.check_in_rule_self) else locationSelfLabel
            // A check-in rule, either kind, says what it does, about someone else or about yourself.
            b.tvCheckInAbout.visibility = if (checkIn) View.VISIBLE else View.GONE
            b.tvCheckInAbout.setText(if (self) R.string.check_in_rule_help_self else R.string.check_in_rule_help)
            // A location rule about others never goes off while they aren't sharing, and says so. A
            // one-time check-in rule says it ends at the check-in; neither kind offers "back online".
            b.tvInactivityNotWhileOff.visibility = if (self || checkIn) View.GONE else View.VISIBLE
            b.tvCheckInHelp.visibility = if (checkIn && !scheduled) View.VISIBLE else View.GONE
            b.tvCheckInHelp.setText(if (self) R.string.check_in_once_help_self else R.string.check_in_once_help)
            // About yourself, the zone follows your phone; about someone else, it is a zone by name.
            showZone()
        }

        if (isEdit) {
            // Type and circle are fixed when editing: hide the type chooser, and show the
            // circle read-only. Changing the circle would re-target a different member and
            // require re-encryption; to move a rule, delete and recreate it.
            b.labelType.visibility = View.GONE
            b.rgType.visibility = View.GONE
            b.spinnerCircle.visibility = View.GONE
            val editCircleId = when (existing) {
                is UnifiedAlert.Battery -> existing.rule.circleId
                is UnifiedAlert.Inactivity -> existing.alert.circleId
            }
            b.tvCircleValue.text = rulesVm.circles.value.find { it.id == editCircleId }?.name ?: "—"
            b.tvCircleValue.visibility = View.VISIBLE
            when (existing) {
                is UnifiedAlert.Battery -> {
                    b.layoutBattery.visibility = View.VISIBLE
                    b.layoutInactivity.visibility = View.GONE
                    // The target is fixed on edit for the same reason the circle is:
                    // it is stored encrypted to that circle, so re-targeting means
                    // re-encrypting. Delete and recreate to move it.
                    b.labelMembers.visibility = View.GONE
                    b.layoutMembers.visibility = View.GONE
                    // An e-mail-only rule opens as Push, so a save keeps it notifying.
                    b.checkboxPush.isChecked = RuleChannels.pushShown(existing.rule.notifyPush, existing.rule.notifyEmail)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val t = rulesVm.fetchBatteryThreshold(existing.id) ?: 20
                        b.seekbarThreshold.progress = (t - 5).coerceIn(0, 45)
                    }
                }
                is UnifiedAlert.Inactivity -> {
                    b.layoutBattery.visibility = View.GONE
                    b.layoutInactivity.visibility = View.VISIBLE
                    b.checkboxSelfAlert.isChecked = existing.alert.isSelfAlert
                    b.checkboxSelfAlert.isEnabled = false // self-alert is structural; can't flip on edit
                    // Its kind's words; a location rule about others says it never goes off while
                    // they aren't sharing. The kind is structural too: fixed when it was made, a
                    // check-in rule's within a time or by a time of day as well.
                    b.rbCheckInScheduled.isChecked = CheckInModes.isScheduled(existing.alert.checkInMode)
                    applyKind(checkIn = InactivityWatches.isCheckIn(existing.alert.watches), self = existing.alert.isSelfAlert)
                    b.checkboxPush.isChecked = RuleChannels.pushShown(existing.alert.notifyPush, existing.alert.notifyEmail)
                    b.seekbarTimeout.progress = (existing.alert.timeoutHours - 1).coerceIn(0, 23)
                    // No watcher picker on edit. Like the self-alert checkbox above, who a
                    // rule watches is structural: creation writes one row per target, each
                    // carrying its own encrypted target. The picker cannot re-target this row:
                    // saving it would only write a watcher blob, and any such blob latches
                    // `client_only` on the server, which stops the inactivity worker
                    // evaluating the rule for good. To watch different members, delete and
                    // re-create.
                    b.labelMembers.visibility = View.GONE
                    b.layoutMembers.visibility = View.GONE
                }
            }
        } else {
            val circles = rulesVm.circles.value
            if (circles.isEmpty()) {
                Toast.makeText(requireContext(), "Join or create a circle first", Toast.LENGTH_SHORT).show()
                return
            }
            val circleNames = circles.map { it.name }.toTypedArray()
            b.spinnerCircle.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, circleNames
            )
            b.spinnerCircle.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos in circles.indices) {
                        rulesVm.selectCircle(circles[pos])
                        inactivityVm.loadCircleMembers(circles[pos].id)
                    }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            })
            rulesVm.selectCircle(circles[0])
            inactivityVm.loadCircleMembers(circles[0].id)

            // The picker is shared by both types, but the two read it
            // differently, so the label has to say which rule it is describing:
            // an inactivity rule with nothing checked watches everyone, whereas a
            // battery rule needs a target; it holds exactly one.
            fun applyMemberPickerLabel(battery: Boolean) {
                b.labelMembers.text =
                    if (battery) "Whose battery to watch" else "Who to watch (none checked = everyone)"
            }

            b.rgType.setOnCheckedChangeListener { _, checkedId ->
                val battery = checkedId == b.rbBattery.id
                b.layoutBattery.visibility = if (battery) View.VISIBLE else View.GONE
                b.layoutInactivity.visibility = if (battery) View.GONE else View.VISIBLE
                applyMemberPickerLabel(battery)
                applyKind(checkIn = checkedId == b.rbCheckIn.id, self = b.checkboxSelfAlert.isChecked)
                // A self-alert watches the creator, so the picker only reappears
                // for battery or for a non-self inactivity rule.
                val showPicker = battery || !b.checkboxSelfAlert.isChecked
                b.labelMembers.visibility = if (showPicker) View.VISIBLE else View.GONE
                b.layoutMembers.visibility = if (showPicker) View.VISIBLE else View.GONE
            }
            b.layoutBattery.visibility = View.VISIBLE
            b.layoutInactivity.visibility = View.GONE
            applyMemberPickerLabel(true)
            b.rgCheckInKind.setOnCheckedChangeListener { _, _ ->
                b.tvScheduleError.visibility = View.GONE
                applyKind(checkIn = b.rbCheckIn.isChecked, self = b.checkboxSelfAlert.isChecked)
            }

            // Self-alert watches the creator, so the member picker doesn't apply then; nor
            // does the line about others' sharing, and a check-in rule says whom it tells.
            b.checkboxSelfAlert.setOnCheckedChangeListener { _, checked ->
                val showPicker = !checked || b.rbBattery.isChecked
                b.labelMembers.visibility = if (showPicker) View.VISIBLE else View.GONE
                b.layoutMembers.visibility = if (showPicker) View.VISIBLE else View.GONE
                applyKind(checkIn = b.rbCheckIn.isChecked, self = checked)
            }
        }

        // Populate the member picker (shared by both rule types) reactively from the selected
        // circle's members. Create-path only: the picker is hidden when editing (the target is
        // structural), so there is nothing to pre-check here.
        val memberJob = viewLifecycleOwner.lifecycleScope.launch {
            inactivityVm.circleMembers.collect { members ->
                if (_binding == null) return@collect
                b.layoutMembers.removeAllViews()
                members.forEach { m ->
                    b.layoutMembers.addView(android.widget.CheckBox(requireContext()).apply {
                        text = m.displayName
                        tag = m.userId
                    })
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEdit) "Edit Alert" else "Create Alert")
            .setView(b.root)
            // Wired after show(): a schedule that can't be saved keeps the dialog open, saying why.
            .setPositiveButton(if (isEdit) "Save" else "Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { memberJob.cancel() }
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (onEditorSave(b, existing, scheduleChoice)) dialog.dismiss()
        }
    }

    /** A time of day for the schedule, in this phone's 12- or 24-hour format; [onPicked] gets its minute of the day. */
    private fun pickTime(title: Int, minute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(minute / 60)
            .setMinute(minute % 60)
            .setTitleText(getString(title))
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(parentFragmentManager, "check_in_time_picker")
    }

    /**
     * A scheduled check-in rule's schedule from the editor ([scheduleChoice]), or null after saying
     * in the editor what keeps it from being saved.
     */
    private fun scheduleOrSay(b: DialogAlertEditorBinding, scheduleChoice: () -> CheckInScheduleEditor.Choice): CheckInScheduleSetting? {
        val problem = when (val choice = scheduleChoice()) {
            is CheckInScheduleEditor.Choice.Ok -> {
                b.tvScheduleError.visibility = View.GONE
                return choice.setting
            }
            CheckInScheduleEditor.Choice.NoDay -> getString(R.string.check_in_pick_a_day)
            // The check-in time as its button says it.
            CheckInScheduleEditor.Choice.FromNotBeforeDue -> getString(R.string.check_in_from_before_due, b.btnDueTime.text)
        }
        b.tvScheduleError.text = problem
        b.tvScheduleError.visibility = View.VISIBLE
        return null
    }

    /** Save the editor's rule. False: nothing was saved, and the editor says why (it stays open). */
    private fun onEditorSave(
        b: DialogAlertEditorBinding,
        existing: UnifiedAlert?,
        scheduleChoice: () -> CheckInScheduleEditor.Choice,
    ): Boolean {
        // Push only. There is no e-mail for these rules: they are judged on this phone so the server
        // never learns who watches whom, and an e-mail would hand it each firing.
        val notifyPush = b.checkboxPush.isChecked

        if (existing != null) {
            when (existing) {
                is UnifiedAlert.Battery ->
                    rulesVm.updateBatteryRule(existing.id, b.seekbarThreshold.progress + 5, notifyPush)
                is UnifiedAlert.Inactivity -> {
                    // A scheduled rule sends its schedule whole; a one-time or location rule its hours.
                    val schedule = if (CheckInModes.isScheduled(existing.alert.checkInMode) && InactivityWatches.isCheckIn(existing.alert.watches)) {
                        scheduleOrSay(b, scheduleChoice) ?: return false
                    } else null
                    // No watcher set. Who a rule watches is structural: creation writes one
                    // row per target, and sending a watcher blob on update latches
                    // `client_only` on the server, after which its worker skips the rule
                    // permanently. Editing a timeout would silently disable the alert.
                    inactivityVm.updateInactivityRule(
                        existing.id, if (schedule != null) null else (b.seekbarTimeout.progress + 1) * 60, notifyPush, schedule,
                    )
                }
            }
            return true
        }

        val circles = rulesVm.circles.value
        val pos = b.spinnerCircle.selectedItemPosition
        if (pos !in circles.indices) return true
        val circle = circles[pos]

        if (b.rbBattery.isChecked) {
            val members = rulesVm.circleMembers.value
            if (members.isEmpty()) {
                Toast.makeText(requireContext(), "No members in circle", Toast.LENGTH_SHORT).show()
                return true
            }
            // One rule per checked member: a battery rule carries exactly
            // one encrypted target, and each target's threshold is
            // compared separately. Taking `members.first()` regardless of
            // the picker would watch whoever happens to be first in the
            // list, invisible while nothing fires, wrong the moment it
            // does.
            val watched = collectWatchedMembers(b)
            if (watched.isEmpty()) {
                Toast.makeText(requireContext(), "Choose whose battery to watch", Toast.LENGTH_SHORT).show()
                return true
            }
            watched.forEach { userId ->
                rulesVm.createLowBatteryAlert(
                    circleId = circle.id,
                    targetUserId = userId,
                    threshold = b.seekbarThreshold.progress + 5,
                    notifyPush = notifyPush,
                )
            }
        } else {
            val members = inactivityVm.circleMembers.value
            if (members.isEmpty()) {
                Toast.makeText(requireContext(), "No members in circle", Toast.LENGTH_SHORT).show()
                return true
            }
            val checkIn = b.rbCheckIn.isChecked
            // By a time of day: its schedule, or nothing saved until it can be.
            val schedule = if (checkIn && b.rbCheckInScheduled.isChecked) scheduleOrSay(b, scheduleChoice) ?: return false else null
            val isSelfAlert = b.checkboxSelfAlert.isChecked
            // Self-alert watches the creator; otherwise the picker decides (empty = everyone).
            val watched = if (isSelfAlert) {
                listOf(members.find { it.role == "owner" }?.userId ?: members.first().userId)
            } else {
                collectWatchedMembers(b)
            }
            val targetUserId = watched.firstOrNull() ?: members.first().userId
            inactivityVm.createInactivityAlert(
                circleId = circle.id,
                targetUserId = targetUserId,
                // A scheduled rule's hours mean nothing; the server takes 60 minutes and ignores them.
                timeoutMinutes = if (schedule != null) 60 else (b.seekbarTimeout.progress + 1) * 60,
                isSelfAlert = isSelfAlert,
                notifyPush = notifyPush,
                watchedUserIds = watched,
                // Check-in or location updates: the one choice that tells the two apart.
                watches = if (checkIn) InactivityWatches.CHECK_IN else InactivityWatches.LOCATION,
                schedule = schedule,
            )
        }
        return true
    }

    /** Read the checked members from the inactivity picker (empty = everyone). */
    private fun collectWatchedMembers(b: DialogAlertEditorBinding): List<String> =
        (0 until b.layoutMembers.childCount).mapNotNull { i ->
            (b.layoutMembers.getChildAt(i) as? android.widget.CheckBox)?.takeIf { it.isChecked }?.tag as? String
        }

    private fun showDeleteConfirmation(item: UnifiedAlert) {
        val what = when (item) {
            is UnifiedAlert.Battery -> "battery"
            is UnifiedAlert.Inactivity -> if (InactivityWatches.isCheckIn(item.alert.watches)) "check-in" else "inactivity"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Alert")
            .setMessage("Delete this $what alert?")
            .setPositiveButton("Delete") { _, _ ->
                when (item) {
                    is UnifiedAlert.Battery -> rulesVm.deleteRule(item.id)
                    is UnifiedAlert.Inactivity -> inactivityVm.deleteAlert(item.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** How often a list with no countdown on it looks again. */
        const val COUNTDOWN_IDLE_MS = 60_000L
    }
}
