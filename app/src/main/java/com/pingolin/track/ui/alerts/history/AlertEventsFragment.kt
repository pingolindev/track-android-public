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

package com.pingolin.track.ui.alerts.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pingolin.track.databinding.FragmentAlertEventsBinding
import com.pingolin.track.ui.start.UnreadableEmptyState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlertEventsFragment : Fragment() {

    private var _binding: FragmentAlertEventsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AlertEventsViewModel by viewModels()
    private lateinit var adapter: AlertEventAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = AlertEventAdapter { event ->
            Toast.makeText(requireContext(), "Event: ${event.type}", Toast.LENGTH_SHORT).show()
        }

        binding.rvAlertEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AlertEventsFragment.adapter
        }
    }

    private fun setupUI() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshEvents()
        }
    }

    private fun observeViewModel() {
        // Combine alert events and inactivity events into a single list
        viewLifecycleOwner.lifecycleScope.launch {
            val serverItems = combine(
                viewModel.alertEvents,
                viewModel.inactivityEvents,
                viewModel.alertRules,
                viewModel.inactivityAlerts,
                viewModel.circles
            ) { alertEvents, inactivityEvents, rules, inacts, circles ->
                val nameById = circles.associate { it.id to it.name }
                val ruleCircle = rules.associate { it.id to it.circleId }
                val inactCircle = inacts.associate { it.id to it.circleId }
                val items = mutableListOf<AlertEventItem>()

                // Map alert rule events: each as what it was, where it opened here
                // ("missed_check_in", "low_battery", …); a generic alert where it didn't.
                alertEvents.forEach { (event, alertType, triggeredAt) ->
                    // A battery rule's circle, or an inactivity or check-in rule's.
                    val circleName = event.alertRuleId?.let { ruleCircle[it] ?: inactCircle[it] }?.let { nameById[it] }
                    items.add(
                        AlertEventItem(
                            id = event.id,
                            type = alertType ?: "alert_rule",
                            // None on a firing a device reported: its words are sealed.
                            message = event.message.orEmpty(),
                            dayBucket = event.dayBucket,
                            isAcknowledged = event.acknowledgedDayBucket != null,
                            circleName = circleName,
                            // Its sealed time where it opened: the check-ins merge with it by time.
                            timestampMillis = triggeredAt,
                        )
                    )
                }

                // Map inactivity events
                inactivityEvents.forEach { event ->
                    val circleName = inactCircle[event.inactivityAlertId]?.let { nameById[it] }
                    items.add(
                        AlertEventItem(
                            id = event.id,
                            type = "inactivity",
                            message = "Inactivity detected: no location for ${event.timeoutHours} hours",
                            dayBucket = event.triggeredDayBucket,
                            isAcknowledged = event.resolvedDayBucket != null,
                            circleName = circleName
                        )
                    )
                }

                items.toList()
            }

            // Merge in device-local inactivity firings (client-only rules have no server
            // event) and the watched people's past check-ins, newest first by time.
            combine(serverItems, viewModel.localEventItems, viewModel.checkInItems) { server, local, checkIns ->
                HistoryCheckIns.merged(server + local + checkIns)
            }.collectLatest { events ->
                binding.swipeRefresh.isRefreshing = false
                adapter.submitList(events)

                UnreadableEmptyState.show(binding.emptyState, events.isEmpty())
                binding.rvAlertEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        // The alert entries stand without the check-ins; a line says those couldn't be loaded.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.checkInsFailed.collectLatest { failed ->
                binding.tvCheckInsFailed.visibility = if (failed) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AlertEventsUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is AlertEventsUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    is AlertEventsUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
