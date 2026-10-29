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

package com.pingolin.track.ui.pending

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.data.local.entity.DroppedNoticeEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.databinding.ItemPendingChangeBinding
import com.pingolin.track.domain.pending.PendingChangeRunner
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingKind
import com.pingolin.track.domain.pending.PendingLines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of changes waiting to sync, as a list shows it. */
sealed interface PendingItem {
    val id: String
    val circleId: String

    /** Waiting, going out, or being cancelled; [cancellable] while it isn't going out. */
    data class Waiting(
        override val id: String,
        override val circleId: String,
        val tag: String,
        val line: String,
        val cancellable: Boolean,
    ) : PendingItem

    /** A dropped change's line, shown until it has been seen. */
    data class Notice(override val id: String, override val circleId: String, val line: String) : PendingItem

    companion object {
        /**
         * The items [kinds] (all when null) make for the screens, oldest first: waiting
         * changes with their tag and line, then the dropped lines not yet cleared. A change
         * still settling shows nothing.
         */
        fun of(
            changes: List<PendingChangeEntity>,
            notices: List<DroppedNoticeEntity>,
            circleNames: Map<String, String>,
            kinds: Set<PendingKind>? = null,
            now: Long = System.currentTimeMillis(),
        ): List<PendingItem> {
            fun wanted(kind: String) = kinds == null || PendingKind.of(kind) in kinds
            val waiting = changes
                .filter { wanted(it.kind) && it.state != PendingChanges.SETTLING }
                .mapNotNull { change ->
                    val inputs = PendingInputs.read(change) ?: return@mapNotNull null
                    Waiting(
                        id = change.id,
                        circleId = change.circleId,
                        tag = tagOf(change),
                        line = PendingInputs.waitingLine(inputs, circleNames[change.circleId] ?: inputs.circleName),
                        cancellable = change.state == PendingChanges.WAITING || change.state == PendingChanges.NEEDS_PERSON,
                    )
                }
            val lines = notices
                .filter { wanted(it.kind) && (it.seenAt == null || now - it.seenAt < PendingChangeRunner.NOTICE_SEEN_KEEP_MS) }
                .map { Notice(it.id, it.circleId, it.line) }
            return waiting + lines
        }

        /** The tag a waiting change shows: by its state,
         * sending, cancelling or waiting for the password;
         * otherwise the default tag.
         */
        fun tagOf(change: PendingChangeEntity): String = when {
            change.state == PendingChanges.RUNNING -> PendingLines.SENDING
            change.state == PendingChanges.CANCELLING -> PendingLines.CANCELLING
            change.state == PendingChanges.NEEDS_PERSON && change.question == PASSWORD -> PendingLines.WAITING_FOR_PASSWORD
            else -> PendingLines.TAG
        }

        /** [PendingChangeEntity.question] when the password is what's needed. */
        const val PASSWORD = com.pingolin.track.domain.pending.PendingQuestions.PASSWORD

        /** [of], live, from [pendingChanges] and the circles' names as they are now. */
        fun flow(
            pendingChanges: PendingChanges,
            circleNames: Flow<Map<String, String>>,
            kinds: Set<PendingKind>? = null,
        ): Flow<List<PendingItem>> = combine(
            pendingChanges.observe(),
            pendingChanges.observeNotices(),
            circleNames,
        ) { changes, notices, names -> of(changes, notices, names, kinds) }
    }
}

/**
 * Changes waiting to sync, in place: put in front of a list's own adapter with a
 * ConcatAdapter. Cancel takes a change away at once, with no confirmation.
 */
class PendingChangesAdapter(
    private val onCancel: (PendingItem.Waiting) -> Unit,
) : ListAdapter<PendingItem, PendingChangesAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPendingChangeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemPendingChangeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PendingItem) {
            binding.tvPendingLine.text = when (item) {
                is PendingItem.Waiting -> item.line
                is PendingItem.Notice -> item.line
            }
            when (item) {
                is PendingItem.Waiting -> {
                    binding.tvPendingTag.visibility = View.VISIBLE
                    binding.tvPendingTag.text = item.tag
                    binding.btnPendingCancel.visibility = if (item.cancellable) View.VISIBLE else View.GONE
                    binding.btnPendingCancel.setOnClickListener { onCancel(item) }
                }
                is PendingItem.Notice -> {
                    binding.tvPendingTag.visibility = View.GONE
                    binding.btnPendingCancel.visibility = View.GONE
                    binding.btnPendingCancel.setOnClickListener(null)
                }
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PendingItem>() {
            override fun areItemsTheSame(oldItem: PendingItem, newItem: PendingItem) =
                oldItem.id == newItem.id && oldItem.javaClass == newItem.javaClass
            override fun areContentsTheSame(oldItem: PendingItem, newItem: PendingItem) = oldItem == newItem
        }
    }
}

/**
 * A screen's changes waiting to sync, for its ViewModel: the [kinds] it shows in place, in
 * the circles [inScope] says (all when null), with Cancel and "seen".
 */
class PendingListModel(
    private val pendingChanges: PendingChanges,
    private val scope: kotlinx.coroutines.CoroutineScope,
    circles: Flow<List<com.pingolin.track.data.local.entity.CircleEntity>>,
    kinds: Set<PendingKind>?,
    inScope: Flow<String?>? = null,
) {
    val items: kotlinx.coroutines.flow.StateFlow<List<PendingItem>> = run {
        val all = PendingItem.flow(pendingChanges, kotlinx.coroutines.flow.flow {
            circles.collect { list -> emit(list.associate { it.id to it.name }) }
        }, kinds)
        val scoped = if (inScope == null) all else combine(all, inScope) { items, circleId ->
            items.filter { it.circleId == circleId }
        }
        scoped.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /** Cancel, with no confirmation. */
    fun cancel(item: PendingItem.Waiting) {
        scope.launch { pendingChanges.cancel(item.id) }
    }

    /** The screen showed these dropped lines: they clear next time. */
    fun seen(items: List<PendingItem>) {
        val ids = items.filterIsInstance<PendingItem.Notice>().map { it.id }
        if (ids.isNotEmpty()) scope.launch { pendingChanges.noticesSeen(ids) }
    }
}
