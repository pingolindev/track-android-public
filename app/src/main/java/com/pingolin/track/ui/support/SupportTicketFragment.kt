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

package com.pingolin.track.ui.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.pingolin.track.databinding.FragmentSupportTicketBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SupportTicketFragment : Fragment() {

    private var _binding: FragmentSupportTicketBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SupportTicketViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupportTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupSubmitButton()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener {
            val subject = binding.etSubject.text?.toString() ?: ""
            val message = binding.etMessage.text?.toString() ?: ""
            viewModel.submitTicket(subject, message)
        }

        binding.btnBackToSettings.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is SupportTicketUiState.Idle -> {
                        showForm()
                    }
                    is SupportTicketUiState.Loading -> {
                        showLoading()
                    }
                    is SupportTicketUiState.ValidationError -> {
                        showForm()
                        showError(state.message)
                        // Set error on the relevant field
                        if (state.message.contains("subject", ignoreCase = true)) {
                            binding.tilSubject.error = state.message
                        } else if (state.message.contains("message", ignoreCase = true)) {
                            binding.tilMessage.error = state.message
                        }
                    }
                    is SupportTicketUiState.Success -> {
                        showSuccess(state.ticketId)
                    }
                    is SupportTicketUiState.Error -> {
                        showForm()
                        showError(state.message)
                    }
                }
            }
        }
    }

    private fun showForm() {
        binding.layoutForm.visibility = View.VISIBLE
        binding.layoutSuccess.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.btnSubmit.isEnabled = true
        binding.cardError.visibility = View.GONE
        binding.tilSubject.error = null
        binding.tilMessage.error = null
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSubmit.isEnabled = false
        binding.cardError.visibility = View.GONE
        binding.tilSubject.error = null
        binding.tilMessage.error = null
    }

    private fun showSuccess(ticketId: String?) {
        binding.layoutForm.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE
        if (ticketId != null) {
            binding.tvSuccessMessage.text = getString(
                com.pingolin.track.R.string.support_ticket_sent_with_id,
                ticketId
            )
        }
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnSubmit.isEnabled = true
        binding.cardError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
