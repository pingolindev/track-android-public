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

package com.pingolin.track.ui.alerts.inactivity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.databinding.FragmentInactivityAlertsBinding
import com.pingolin.track.databinding.DialogCreateInactivityAlertBinding
import com.pingolin.track.ui.start.UnreadableEmptyState
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InactivityAlertsFragment : Fragment() {

    private var _binding: FragmentInactivityAlertsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InactivityAlertsViewModel by viewModels()
    private lateinit var adapter: InactivityAlertAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInactivityAlertsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = InactivityAlertAdapter(
            onAlertClick = { /* Show details */ },
            onToggleClick = { alert, isActive -> viewModel.toggleAlert(alert.id, isActive) },
            onDeleteClick = { alert -> showDeleteConfirmation(alert) }
        )

        binding.rvInactivityAlerts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@InactivityAlertsFragment.adapter
        }
    }

    private fun setupUI() {
        binding.fabCreateAlert.setOnClickListener {
            showCreateDialog()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshInactivityAlerts()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.inactivityAlerts.collectLatest { alerts ->
                binding.swipeRefresh.isRefreshing = false
                adapter.submitList(alerts)

                UnreadableEmptyState.show(binding.emptyState, alerts.isEmpty(), binding.fabCreateAlert)
                binding.rvInactivityAlerts.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is InactivityAlertsUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is InactivityAlertsUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    is InactivityAlertsUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    is InactivityAlertsUiState.AlertCreated -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Inactivity rule created!", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is InactivityAlertsUiState.AlertUpdated -> {
                        binding.progressBar.visibility = View.GONE
                        viewModel.resetState()
                    }
                    is InactivityAlertsUiState.AlertDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Inactivity rule deleted", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    private fun showCreateDialog() {
        val circles = viewModel.circles.value

        if (circles.isEmpty()) {
            Toast.makeText(requireContext(), "Join or create a circle first", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogCreateInactivityAlertBinding.inflate(layoutInflater)

        val circleNames = circles.map { it.name }.toTypedArray()
        val circleAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, circleNames)
        dialogBinding.spinnerCircle.adapter = circleAdapter

        dialogBinding.seekbarTimeout.max = 23 // 0-23 = 1-24 hours
        dialogBinding.seekbarTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val hours = progress + 1
                dialogBinding.tvTimeoutValue.text = if (hours == 1) "1 hour" else "$hours hours"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        dialogBinding.seekbarTimeout.progress = 3 // Default 4 hours

        dialogBinding.spinnerCircle.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < circles.size) {
                    viewModel.loadCircleMembers(circles[position].id)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // Initially load first circle's members
        if (circles.isNotEmpty()) {
            viewModel.loadCircleMembers(circles[0].id)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create Inactivity Rule")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val selectedCircleIndex = dialogBinding.spinnerCircle.selectedItemPosition

                if (selectedCircleIndex >= 0 && selectedCircleIndex < circles.size) {
                    val selectedCircle = circles[selectedCircleIndex]
                    val members = viewModel.circleMembers.value

                    if (members.isEmpty()) {
                        Toast.makeText(requireContext(), "No members in circle", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val isSelfAlert = dialogBinding.checkboxSelfAlert.isChecked
                    val targetUserId = if (isSelfAlert) {
                        // For self-alert, use current user
                        members.find { it.role == "owner" }?.userId ?: members.first().userId
                    } else {
                        members.first().userId
                    }

                    val timeoutHours = dialogBinding.seekbarTimeout.progress + 1
                    val timeoutMinutes = timeoutHours * 60
                    val notifyPush = dialogBinding.checkboxPush.isChecked
                    val notifyOnReturn = dialogBinding.checkboxNotifyOnReturn.isChecked

                    viewModel.createInactivityAlert(
                        circleId = selectedCircle.id,
                        targetUserId = targetUserId,
                        timeoutMinutes = timeoutMinutes,
                        isSelfAlert = isSelfAlert,
                        notifyPush = notifyPush,
                        notifyOnReturn = notifyOnReturn
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(alert: InactivityAlertEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Inactivity Rule")
            .setMessage("Are you sure you want to delete this inactivity rule?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteAlert(alert.id)
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
