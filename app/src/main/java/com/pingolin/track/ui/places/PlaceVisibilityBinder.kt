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

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.chip.Chip
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.databinding.DialogCreatePlaceBinding
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.plainTextOrNull
import kotlinx.coroutines.launch

/**
 * Wire up the "Place Visibility" section of [DialogCreatePlaceBinding]
 * so users can add/remove circle-sharing inline on both create and edit.
 *
 * Mirrors the web app's Place Visibility section.
 *
 * Edit flow: calls `placeRepository.sharePlaceWithCircle` /
 * `unsharePlaceFromCircle` directly as the user clicks.
 *
 * Create flow (editingPlace == null): shares are queued in a local list
 * and the caller should drain it via [PendingShares.drain] right after
 * the place is successfully created on the server.
 */
class PendingShares {
    private val _circleIds = mutableListOf<String>()
    val circleIds: List<String> get() = _circleIds.toList()

    fun add(circleId: String) {
        if (!_circleIds.contains(circleId)) _circleIds.add(circleId)
    }
    fun remove(circleId: String) { _circleIds.remove(circleId) }

    /**
     * Fire [onShare] for each queued circle, then clear the queue.
     * Errors on individual shares are logged but don't abort the batch —
     * one failed share shouldn't leave the others undone.
     */
    suspend fun drain(onShare: suspend (String) -> Result<Unit>) {
        val ids = _circleIds.toList()
        _circleIds.clear()
        for (circleId in ids) {
            val r = onShare(circleId)
            if (r.isFailure) {
                android.util.Log.w("PlaceVisibilityBinder", "Failed to share pending: $circleId", r.exceptionOrNull())
            }
        }
    }
}

fun bindPlaceVisibility(
    context: Context,
    binding: DialogCreatePlaceBinding,
    editingPlace: PlaceEntity?,
    availableCircles: List<CircleEntity>,
    placeRepository: PlaceRepository,
    scope: LifecycleCoroutineScope,
): PendingShares {
    val pending = PendingShares()
    // Sharing a place to the circle it already lives in is meaningless
    // — copyPlaceToCircle would either create a duplicate or fail
    // unhelpfully. Drop the place's own circle from the candidate list
    // up-front so the spinner / "Place Visibility" section never lists
    // it as an option.
    val shareCandidates = if (editingPlace != null) {
        availableCircles.filter { it.id != editingPlace.circleId }
    } else {
        availableCircles
    }
    // Hide section entirely when the user has no circles to share with,
    // as the web app does.
    if (shareCandidates.isEmpty()) {
        binding.layoutPlaceVisibility.visibility = android.view.View.GONE
        return pending
    }
    binding.layoutPlaceVisibility.visibility = android.view.View.VISIBLE

    // Current shared IDs: from the edit target's sharedCircles comma
    // string, or empty (create). We maintain a MUTABLE snapshot here
    // so adds/removes reflect instantly in the chip group without
    // having to refetch the place.
    val currentShared = mutableListOf<String>().apply {
        if (editingPlace != null) {
            editingPlace.sharedCircles
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.let { addAll(it) }
        }
    }

    fun circleName(id: String): String =
        availableCircles.find { it.id == id }?.name ?: id.take(8)

    fun refreshSpinner() {
        // Adapter backed by unshared circles. Hide the whole add-row
        // once every shareable circle is already shared. shareCandidates
        // already excludes the place's own circle.
        val unshared = shareCandidates.filter { c ->
            c.id !in currentShared && c.id !in pending.circleIds
        }
        if (unshared.isEmpty()) {
            binding.layoutAddSharedCircle.visibility = android.view.View.GONE
            return
        }
        binding.layoutAddSharedCircle.visibility = android.view.View.VISIBLE
        val names = unshared.map { it.name }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerShareCircle.adapter = adapter
        binding.spinnerShareCircle.tag = unshared // stash for button click
    }

    fun refreshChips() {
        binding.chipGroupSharedCircles.removeAllViews()
        val sharedOrPending = (currentShared + pending.circleIds).distinct()
        binding.tvNoSharedCircles.visibility =
            if (sharedOrPending.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (id in sharedOrPending) {
            val chip = Chip(context).apply {
                text = circleName(id)
                isCloseIconVisible = true
                isCheckable = false
                isClickable = false
                setOnCloseIconClickListener {
                    if (editingPlace == null) {
                        // Create flow: drop from pending queue only.
                        pending.remove(id)
                        refreshChips()
                        refreshSpinner()
                    } else {
                        // Edit flow: fire the API. Optimistically drop
                        // the chip first, re-add on failure.
                        currentShared.remove(id)
                        refreshChips()
                        refreshSpinner()
                        scope.launch {
                            val r = placeRepository.unsharePlaceFromCircle(editingPlace.id, id)
                            if (r.isFailure) {
                                currentShared.add(id)
                                refreshChips()
                                refreshSpinner()
                                Toast.makeText(
                                    context,
                                    r.exceptionOrNull()?.plainTextOrNull()
                                        ?.let { context.getString(com.pingolin.track.R.string.places_unshare_failed, it) }
                                        ?: context.getString(com.pingolin.track.R.string.places_unshare_failed_no_reason),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }
            binding.chipGroupSharedCircles.addView(chip)
        }
    }

    binding.btnShareAddCircle.setOnClickListener {
        @Suppress("UNCHECKED_CAST")
        val unshared = binding.spinnerShareCircle.tag as? List<CircleEntity> ?: return@setOnClickListener
        val pos = binding.spinnerShareCircle.selectedItemPosition
        val target = unshared.getOrNull(pos) ?: return@setOnClickListener
        if (editingPlace == null) {
            pending.add(target.id)
            refreshChips()
            refreshSpinner()
        } else {
            // Fire immediately. Optimistic add; roll back on failure.
            currentShared.add(target.id)
            refreshChips()
            refreshSpinner()
            scope.launch {
                val r = placeRepository.sharePlaceWithCircle(editingPlace.id, target.id, keepIfBehind = true)
                val kept = r.exceptionOrNull() as? com.pingolin.track.domain.pending.QueuedForSync
                if (kept != null) {
                    // Waiting to sync: it stays on, and goes out by itself.
                    Toast.makeText(context, kept.line, Toast.LENGTH_LONG).show()
                } else if (r.isFailure) {
                    currentShared.remove(target.id)
                    refreshChips()
                    refreshSpinner()
                    Toast.makeText(
                        context,
                        r.exceptionOrNull()?.plainTextOrNull()
                            ?.let { context.getString(com.pingolin.track.R.string.places_share_failed, it) }
                            ?: context.getString(com.pingolin.track.R.string.places_share_failed_no_reason),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    refreshChips()
    refreshSpinner()
    return pending
}
