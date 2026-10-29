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
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiErrors
import com.pingolin.track.data.remote.dto.BlockPersonRequest
import com.pingolin.track.data.remote.dto.BlockedPersonResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * "Blocked people": the list, and adding to and taking from it.
 *
 * The server keeps the list (`api/v1/invites/blocks`). Nothing is kept on the phone.
 */
@HiltViewModel
class BlockedPeopleViewModel @Inject constructor(
    private val trackApi: TrackApi,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BlockedPeopleUiState())
    val state: StateFlow<BlockedPeopleUiState> = _state.asStateFlow()

    /** One-shot: a block the server took. The screen empties its field and takes it. */
    private val _events = MutableStateFlow<BlockedPeopleEvent?>(null)
    val events: StateFlow<BlockedPeopleEvent?> = _events.asStateFlow()

    /**
     * Pauses before a list that failed to load is read again, by itself: there is no "Retry"
     * to press (as on the Devices screen). Tests shorten them.
     */
    internal var reloadPausesMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    private var failedLoads = 0
    private var reloadJob: Job? = null

    init {
        load()
    }

    fun consumeEvent() {
        _events.value = null
    }

    fun load() {
        reloadJob?.cancel()
        reloadJob = null
        viewModelScope.launch { loadOnce() }
    }

    /** Read the list. A list that cannot be read is not an empty one: what is shown stays, and it is read again. */
    private suspend fun loadOnce() {
        if (_state.value.people == null) _state.update { it.copy(loading = true) }
        val (people, failure) = try {
            val response = trackApi.listBlockedPeople()
            val list = response.body()?.takeIf { response.isSuccessful && it.success }?.data
            if (list != null) list to null else null to ApiErrors.plainLine(response, ApiErrors.TROUBLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to e.plainText(ApiErrors.TROUBLE)
        }
        if (people != null) {
            failedLoads = 0
            _state.update { it.copy(loading = false, people = BlockedPeople.inListOrder(people), failure = null) }
            return
        }
        _state.update { it.copy(loading = false, failure = failure) }
        val pause = reloadPausesMs[minOf(failedLoads, reloadPausesMs.lastIndex)]
        failedLoads++
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(pause)
            loadOnce()
        }
    }

    /** The address [typed] would block, or null when the Block button has nothing to send (see [BlockedPeople.addressToBlock]). */
    fun addressToBlock(typed: String): String? =
        BlockedPeople.addressToBlock(typed, _state.value.people?.map { it.blockedEmail }, sessionManager.getEmail())

    fun block(typed: String) {
        val address = addressToBlock(typed) ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null, listFull = false) }
        viewModelScope.launch {
            var listFull = false
            val failure = try {
                val response = trackApi.blockPerson(BlockPersonRequest(address))
                if (response.isSuccessful && response.body()?.success == true) {
                    null
                } else {
                    // Read once: the error body can be read only once. Its code, never its words.
                    val code = ApiErrors.codeOf(response)
                    listFull = BlockedPeople.isListFull(code)
                    ApiErrors.plainLine(response.code(), code, ApiErrors.TROUBLE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.plainText(ApiErrors.TROUBLE)
            }
            if (failure != null) {
                // What was typed stays in the field: nothing has been taken. A full list is said
                // as that, by the screen (`listFull`), and not as trouble.
                _state.update { it.copy(busy = false, failure = if (listFull) null else failure, listFull = listFull) }
                return@launch
            }
            // On the list at once, as the server keeps it (lower case); then the server's own
            // list, which is the list.
            val row = BlockedPersonResponse(blockedEmail = address.lowercase(Locale.ROOT))
            _state.update { s ->
                val held = s.people.orEmpty()
                s.copy(busy = false, people = BlockedPeople.inListOrder(held.filterNot { it.blockedEmail == row.blockedEmail } + row))
            }
            _events.value = BlockedPeopleEvent.Blocked
            reloadJob?.cancel()
            loadOnce()
        }
    }

    /** Take [email] off the list: the address exactly as the list gave it. */
    fun unblock(email: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null, listFull = false) }
        viewModelScope.launch {
            val failure = try {
                val response = trackApi.unblockPerson(BlockPersonRequest(email))
                if (response.isSuccessful && response.body()?.success == true) null
                else ApiErrors.plainLine(response, ApiErrors.TROUBLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.plainText(ApiErrors.TROUBLE)
            }
            _state.update { s ->
                if (failure != null) s.copy(busy = false, failure = failure)
                else s.copy(busy = false, people = s.people?.filterNot { it.blockedEmail == email })
            }
        }
    }
}

data class BlockedPeopleUiState(
    /** The first read is under way. */
    val loading: Boolean = true,
    /** The list, in the order it is shown; null until it has been read. Never "empty" for "could not be read". */
    val people: List<BlockedPersonResponse>? = null,
    /** A block or an unblock is on its way: no second one is sent. */
    val busy: Boolean = false,
    /** What to say about the request that failed, in this app's words; null when none did. */
    val failure: String? = null,
    /**
     * The last block was refused because the list is full ([BlockedPeople.isListFull]): the
     * screen says so in the approved words (strings.xml `blocked_people_full`). Never together
     * with [failure].
     */
    val listFull: Boolean = false,
)

sealed class BlockedPeopleEvent {
    object Blocked : BlockedPeopleEvent()
}
