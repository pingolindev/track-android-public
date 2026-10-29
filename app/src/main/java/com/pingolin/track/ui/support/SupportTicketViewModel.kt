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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.dto.SupportTicketRequest
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SupportTicketViewModel @Inject constructor(
    private val authApi: AuthApi
) : ViewModel() {

    private val _uiState = MutableStateFlow<SupportTicketUiState>(SupportTicketUiState.Idle)
    val uiState: StateFlow<SupportTicketUiState> = _uiState.asStateFlow()

    /**
     * Submit a support ticket to the auth service.
     * Category is hardcoded to "track" for the Track Android app.
     *
     * @param subject Brief description of the issue
     * @param message Detailed description of the issue
     */
    fun submitTicket(subject: String, message: String) {
        if (subject.isBlank()) {
            _uiState.value = SupportTicketUiState.ValidationError("Please enter a subject")
            return
        }
        if (message.isBlank()) {
            _uiState.value = SupportTicketUiState.ValidationError("Please enter a message")
            return
        }

        viewModelScope.launch {
            _uiState.value = SupportTicketUiState.Loading
            try {
                val response = authApi.submitSupportTicket(
                    SupportTicketRequest(
                        subject = subject.trim(),
                        message = message.trim(),
                        category = "track"
                    )
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    val ticketId = response.body()?.data?.ticketId
                    _uiState.value = SupportTicketUiState.Success(ticketId)
                } else {
                    // In this app's words, never the
                    // server's.
                    _uiState.value = SupportTicketUiState.Error(
                        com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't send your message just now.")
                    )
                }
            } catch (e: Exception) {
                _uiState.value = SupportTicketUiState.Error(
                    e.plainText("Network error. Please check your connection.")
                )
            }
        }
    }

    fun resetState() {
        _uiState.value = SupportTicketUiState.Idle
    }
}

sealed class SupportTicketUiState {
    object Idle : SupportTicketUiState()
    object Loading : SupportTicketUiState()
    data class ValidationError(val message: String) : SupportTicketUiState()
    data class Success(val ticketId: String?) : SupportTicketUiState()
    data class Error(val message: String) : SupportTicketUiState()
}
