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

package com.pingolin.track.ui.checkin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.pingolin.track.R
import com.pingolin.track.databinding.FragmentCheckInBinding
import com.pingolin.track.ui.start.UnreadableEmptyState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CheckInFragment : Fragment() {

    private var _binding: FragmentCheckInBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CheckInViewModel by viewModels()
    private lateinit var adapter: CheckInAdapter
    private lateinit var pendingAdapter: com.pingolin.track.ui.pending.PendingChangesAdapter
    private var shownCheckIns = 0
    private var shownPending = 0

    /** The circles the spinner shows, and where they start in it: after the placeholder while none is chosen. */
    private var shownCircles: List<com.pingolin.track.data.local.entity.CircleEntity> = emptyList()
    private var shownOffset = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCheckInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = CheckInAdapter(
            getUserDisplayName = { userId -> viewModel.getMemberName(userId) },
            getPlaceName = { placeId -> viewModel.getPlaceName(placeId) }
        )

        // Check-ins waiting to sync go at the top, where the sent one would appear.
        pendingAdapter = com.pingolin.track.ui.pending.PendingChangesAdapter { viewModel.pending.cancel(it) }
        binding.rvCheckIns.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = androidx.recyclerview.widget.ConcatAdapter(pendingAdapter, this@CheckInFragment.adapter)
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshCheckIns()
        }

        // Quick check-in buttons
        binding.btnSafeArrival.setOnClickListener {
            sendQuickCheckIn(CheckInChoices.QUICK[0].type)
        }

        binding.btnArrived.setOnClickListener {
            sendQuickCheckIn(CheckInChoices.QUICK[1].type)
        }

        // "Checking In": a plain check-in, never the all-clear.
        binding.btnEmergencyOk.setOnClickListener {
            sendQuickCheckIn(CheckInChoices.CHECKING_IN)
        }

        // Custom message send button
        binding.btnSendCustomMessage.setOnClickListener {
            sendCustomMessage()
        }

        // FAB for check-in with message
        binding.fabCheckInWithMessage.setOnClickListener {
            showCheckInWithMessageDialog()
        }

        // Circle spinner listener: a pick is the person's. While nothing is chosen the first entry
        // is a placeholder, which picks nothing.
        binding.spinnerCircle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val circle = shownCircles.getOrNull(position - shownOffset) ?: return
                if (circle.id != viewModel.selectedCircleId.value) viewModel.setSelectedCircle(circle.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * The spinner shows the circle chosen, and keeps it when the list changes: rebuilt at every
     * change, it would fall to the first circle, and the next tap would send there. With none chosen
     * it starts with a placeholder, the line says why, and the quick actions send nothing until a
     * circle is picked.
     */
    private fun showCircleChoice(
        circles: List<com.pingolin.track.data.local.entity.CircleEntity>,
        chosen: String?,
        gone: String?,
    ) {
        val index = circles.indexOfFirst { it.id == chosen }
        val none = index < 0
        val names = circles.map { it.name }
        shownCircles = circles
        shownOffset = if (none) 1 else 0
        binding.spinnerCircle.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            if (none) listOf(getString(R.string.check_in_pick_circle_entry)) + names else names,
        )
        binding.spinnerCircle.setSelection(if (none) 0 else index, false)
        binding.tvCircleChoice.visibility = if (none) View.VISIBLE else View.GONE
        binding.tvCircleChoice.text = when {
            !none -> null
            gone != null -> readableName(gone)?.let { getString(R.string.check_in_circle_gone, it) }
                ?: getString(R.string.check_in_circle_gone_unnamed)
            else -> getString(R.string.check_in_pick_circle)
        }
        for (send in listOf(binding.btnSafeArrival, binding.btnArrived, binding.btnEmergencyOk, binding.btnSendCustomMessage, binding.fabCheckInWithMessage)) {
            send.isEnabled = !none
        }
    }

    /**
     * A circle's readable name, or none: never the "Locked circle" list label, and no fallback name
     * put into a frame either, which some languages read as "your circle". Without one, the unnamed
     * line says it.
     */
    private fun readableName(name: String?): String? =
        name?.takeIf { it.isNotBlank() && !com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it) }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.circles, viewModel.selectedCircleId, viewModel.goneCircleName) { circles, chosen, gone ->
                Triple(circles, chosen, gone)
            }.collectLatest { (circles, chosen, gone) ->
                if (circles.isEmpty()) {
                    binding.cardQuickActions.visibility = View.GONE
                    // No circle, or none that can be read now: the line then, never "Select a
                    // circle".
                    UnreadableEmptyState.show(binding.noCircleState, true)
                    UnreadableEmptyState.show(binding.emptyState, false)
                    binding.rvCheckIns.visibility = View.GONE
                    binding.tvCircleChoice.visibility = View.GONE
                } else {
                    binding.cardQuickActions.visibility = View.VISIBLE
                    UnreadableEmptyState.show(binding.noCircleState, false)
                    showCircleChoice(circles, chosen, gone)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.checkIns.collectLatest { checkIns ->
                binding.swipeRefresh.isRefreshing = false
                adapter.submitList(checkIns)
                shownCheckIns = checkIns.size
                showListOrEmpty()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pending.items.collectLatest { items ->
                pendingAdapter.submitList(items)
                shownPending = items.size
                showListOrEmpty()
                viewModel.pending.seen(items)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.members.collectLatest {
                // Members data has changed; notify the adapter so it re-binds
                // items with the correct display names instead of "Unknown"
                adapter.notifyDataSetChanged()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is CheckInUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                        setQuickButtonsEnabled(true)
                    }
                    is CheckInUiState.Sending -> {
                        binding.progressBar.visibility = View.VISIBLE
                        setQuickButtonsEnabled(false)
                    }
                    is CheckInUiState.Sent -> {
                        binding.progressBar.visibility = View.GONE
                        setQuickButtonsEnabled(true)
                        Toast.makeText(requireContext(), "Check-in sent!", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                        // The new row is already inserted locally and surfaces via the
                        // observeByCircle Flow — no immediate network refresh needed
                        // (it would only re-fetch and re-churn the list).
                    }
                    is CheckInUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        setQuickButtonsEnabled(true)
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    private fun showListOrEmpty() {
        val empty = shownCheckIns == 0 && shownPending == 0 && viewModel.selectedCircleId.value != null
        UnreadableEmptyState.show(binding.emptyState, empty)
        binding.rvCheckIns.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun setQuickButtonsEnabled(enabled: Boolean) {
        binding.btnSafeArrival.isEnabled = enabled
        binding.btnArrived.isEnabled = enabled
        binding.btnEmergencyOk.isEnabled = enabled
        binding.btnSendCustomMessage.isEnabled = enabled
        binding.fabCheckInWithMessage.isEnabled = enabled
    }

    private fun sendCustomMessage() {
        if (viewModel.selectedCircleId.value == null) {
            Toast.makeText(requireContext(), "Please select a circle first", Toast.LENGTH_SHORT).show()
            return
        }

        val message = binding.etCustomMessage.text?.toString()?.trim() ?: ""
        if (message.isBlank()) {
            Toast.makeText(requireContext(), "Please type a message", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.checkInWithMessage(CheckInChoices.MESSAGE_TYPE, message)
        binding.etCustomMessage.text?.clear()
    }

    private fun sendQuickCheckIn(type: String) {
        if (viewModel.selectedCircleId.value == null) {
            Toast.makeText(requireContext(), "Please select a circle first", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.checkInWithLocation(type)
    }

    private fun showCheckInWithMessageDialog() {
        if (viewModel.selectedCircleId.value == null) {
            Toast.makeText(requireContext(), "Please select a circle first", Toast.LENGTH_SHORT).show()
            return
        }

        val types = CheckInChoices.QUICK.map { it.label }.toTypedArray()
        val typeValues = CheckInChoices.QUICK.map { it.type }.toTypedArray()
        var selectedType = 0

        val inputView = layoutInflater.inflate(R.layout.dialog_check_in_message, null)
        val messageInput = inputView.findViewById<TextInputEditText>(R.id.etMessage)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Check In")
            .setView(inputView)
            .setSingleChoiceItems(types, selectedType) { _, which ->
                selectedType = which
            }
            .setPositiveButton("Send") { _, _ ->
                val message = messageInput?.text?.toString()?.trim() ?: ""
                if (message.isNotBlank()) {
                    viewModel.checkInWithMessage(typeValues[selectedType], message)
                } else {
                    viewModel.checkInWithLocation(typeValues[selectedType])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
