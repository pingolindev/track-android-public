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

package com.pingolin.track.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.remote.dto.GeocodingPreferences
import com.pingolin.track.domain.repository.DownloadProgress
import com.pingolin.track.domain.repository.DownloadState
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GeocodingViewModel @Inject constructor(
    private val geocodingRepository: GeocodingRepository
) : ViewModel() {

    val preferences: StateFlow<GeocodingPreferences?> = geocodingRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val downloadProgress: StateFlow<DownloadProgress?> = geocodingRepository.downloadProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiState = MutableStateFlow<GeocodingUiState>(GeocodingUiState.Idle)
    val uiState: StateFlow<GeocodingUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = GeocodingUiState.Loading
            try {
                geocodingRepository.loadPreferences()
                _uiState.value = GeocodingUiState.Idle
            } catch (e: Exception) {
                _uiState.value = GeocodingUiState.Error(e.plainText("Failed to load geocoding data"))
            }
        }
    }




    fun markPrompted() {
        viewModelScope.launch {
            geocodingRepository.markPrompted()
        }
    }

    fun needsPrompt(): Boolean {
        return geocodingRepository.needsPrompt()
    }

    fun resetState() {
        _uiState.value = GeocodingUiState.Idle
    }

    /**
     * Check if geocoding data is available (has downloaded regions).
     */
    fun hasGeocodingData(): Boolean {
        return geocodingRepository.hasGeocodingData()
    }


    /**
     * Format file size for display (e.g., "45 MB").
     */
    fun formatSize(bytes: ULong): String {
        val kb = bytes.toDouble() / 1024
        val mb = kb / 1024
        val gb = mb / 1024

        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}

sealed class GeocodingUiState {
    object Idle : GeocodingUiState()
    object Loading : GeocodingUiState()
    data class Error(val message: String) : GeocodingUiState()
}
