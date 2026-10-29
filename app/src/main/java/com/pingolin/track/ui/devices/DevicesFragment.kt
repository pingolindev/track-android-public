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

package com.pingolin.track.ui.devices

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.SessionInfo
import com.pingolin.track.databinding.FragmentDevicesBinding
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manage signed-in sessions ("devices"). Backed by
 * /api/v1/auth/sessions.
 *
 * Lays out one tonal hero card for the current device, then grouped
 * Active / Older sections with per-row overflow and a top-bar overflow
 * that signs out every other device.
 */
@AndroidEntryPoint
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DevicesViewModel by viewModels()
    private lateinit var adapter: DevicesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sign_out_others -> {
                    confirmBulkRevoke()
                    true
                }
                else -> false
            }
        }

        adapter = DevicesAdapter(
            onRenameCurrent = { session -> showRenameDialog(session) },
            onRowMenu = { session, anchor -> showRowMenu(session, anchor) },
            onSectionAction = { title ->
                // The only section with an action is "Older (N) — Sign out all".
                if (title.startsWith("Older")) confirmBulkRevoke()
            },
        )
        binding.recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDevices.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { render(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collectLatest { event ->
                if (event != null) {
                    showEvent(event)
                    viewModel.consumeEvent()
                }
            }
        }
    }

    private fun render(state: DevicesUiState) {
        binding.loadingContainer.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.errorContainer.visibility = if (!state.loading && state.error != null) View.VISIBLE else View.GONE
        binding.tvError.text = state.error ?: getString(android.R.string.unknownName)
        val showEmpty = !state.loading && state.error == null && state.items.isEmpty()
        binding.emptyContainer.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.recyclerDevices.visibility = if (!state.loading && state.error == null && !showEmpty)
            View.VISIBLE else View.GONE
        // Shown whenever the server has recently failed to confirm a session
        // ended, including while the list itself is loading or errored — it is
        // about what the Sign out action will do, not about this fetch.
        binding.tvSignOutsUnenforced.visibility =
            if (state.signOutsUnenforced) View.VISIBLE else View.GONE
        adapter.submitList(state.items)
        binding.toolbar.menu.findItem(R.id.action_sign_out_others)?.isEnabled = !state.bulkInFlight
    }

    private fun showEvent(event: DevicesEvent) {
        val msg = when (event) {
            DevicesEvent.Renamed -> "Renamed"
            DevicesEvent.RenameFailed -> "Rename failed"
            DevicesEvent.Revoked -> "Signed out"
            DevicesEvent.RevokeFailed -> "Sign-out failed"
            // Says WHY, because the section above it may well be showing rows.
            // Those are push registrations with no sign-in behind them: there is
            // no session to end, so signing out cannot reach them.
            DevicesEvent.NoOthersToRevoke ->
                "Nothing to sign out — those devices have no linked sign-in"
            is DevicesEvent.BulkRevoked -> when {
                event.failed == 0 -> "Signed out ${event.ok} device${if (event.ok == 1) "" else "s"}"
                event.ok == 0 -> "Sign-out failed for all ${event.failed}"
                else -> "Signed out ${event.ok}; ${event.failed} failed"
            }
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun showRowMenu(session: SessionInfo, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Rename")
        popup.menu.add(0, 2, 1, "Sign out")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showRenameDialog(session); true }
                2 -> { confirmRevoke(session); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(session: SessionInfo) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Pixel 7, Asha's iPhone, …"
            setText(session.deviceName ?: "")
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename device")
            .setMessage("Leave blank to reset to auto-detected.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                viewModel.rename(session.id, input.text.toString().trim().ifEmpty { null })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRevoke(session: SessionInfo) {
        val label = session.deviceName?.takeIf { it.isNotBlank() } ?: "this device"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sign out $label?")
            .setMessage("This signs the device out and stops push notifications to it.")
            .setPositiveButton("Sign out") { _, _ -> viewModel.revoke(session.id) }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    private fun confirmBulkRevoke() {
        val otherCount = viewModel.state.value.items.count { it is DeviceListItem.Other }
        if (otherCount == 0) {
            // Reachable only if the list changed under us — the header hides the
            // action when nothing in the section is revocable. Same wording as the
            // NoOthersToRevoke event so the explanation does not depend on which
            // path got here.
            Toast.makeText(
                requireContext(),
                "Nothing to sign out — those devices have no linked sign-in",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sign out other devices?")
            .setMessage("Signs out $otherCount device${if (otherCount == 1) "" else "s"}. " +
                "You'll stay signed in here.")
            .setPositiveButton("Sign out all") { _, _ -> viewModel.revokeAllOthers() }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
