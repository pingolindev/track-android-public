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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.databinding.ItemAlertEventBinding
import com.pingolin.track.ui.checkin.CheckInChoices
import com.pingolin.track.ui.notifications.notificationRelativeLabel
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.missedCheckInByWords
import com.pingolin.track.util.missedCheckInWords
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class AlertEventItem(
    val id: String,
    val type: String, // "low_battery", "offline", "inactivity"
    val message: String,
    val dayBucket: Int,
    val isAcknowledged: Boolean,
    val circleName: String? = null,
    /** Optional status label override ("Resolved", …); falls back to Triggered/Acknowledged. */
    val statusOverride: String? = null,
    /** Exact firing time (epoch millis) when known (local events); null → use [dayBucket]. */
    val timestampMillis: Long? = null,
    /**
     * A missed check-in recorded here: whom it was about and the rule's limit, for its
     * notification's words (missedCheckInWords), said with this phone's language; a scheduled
     * rule's by its time as set ([dueMinute], missedCheckInByWords).
     */
    val memberName: String? = null,
    val timeoutHours: Int? = null,
    val dueMinute: Int? = null,
    /** A past check-in ([type] "check_in", HistoryCheckIns): its type, and whether it is this account's. */
    val checkInType: String? = null,
    val ownCheckIn: Boolean = false,
)

class AlertEventAdapter(
    private val onEventClick: (AlertEventItem) -> Unit
) : ListAdapter<AlertEventItem, AlertEventAdapter.AlertEventViewHolder>(AlertEventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertEventViewHolder {
        val binding = ItemAlertEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AlertEventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlertEventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertEventViewHolder(
        private val binding: ItemAlertEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(event: AlertEventItem) {
            if (event.type == AlertHandler.TYPE_CHECK_IN) return bindCheckIn(event)
            binding.tvStatus.visibility = View.VISIBLE
            val context = binding.root.context
            // Icon based on type
            val icon = when (event.type) {
                "low_battery" -> "🔋"
                "offline" -> "📡"
                "inactivity", AlertHandler.TYPE_MISSED_CHECK_IN -> "⏰"
                else -> "⚠️"
            }
            binding.tvIcon.text = icon

            // Title based on type
            val title = when (event.type) {
                "low_battery" -> "Low Battery Alert"
                "offline" -> "Offline Alert"
                "inactivity" -> "Inactivity Alert"
                AlertHandler.TYPE_MISSED_CHECK_IN -> context.getString(R.string.missed_check_in_title)
                else -> "Alert"
            }
            binding.tvTitle.text = title

            // A missed check-in recorded here says its notification's words.
            val message = when {
                event.memberName != null && event.dueMinute != null -> missedCheckInByWords(context, event.memberName, event.dueMinute)
                event.memberName != null && event.timeoutHours != null -> missedCheckInWords(context, event.memberName, event.timeoutHours)
                else -> event.message
            }
            // Message (append the circle name to disambiguate across circles); a firing whose
            // words are sealed has none, and shows its circle alone.
            binding.tvMessage.text = listOfNotNull(message.takeIf { it.isNotBlank() }, event.circleName).joinToString(" · ")

            // Status
            binding.tvStatus.text = event.statusOverride
                ?: if (event.isAcknowledged) "Acknowledged" else "Triggered"
            binding.tvStatus.alpha = if (event.isAcknowledged) 0.6f else 1.0f

            // When it fired: exact local time when we have it (local events), else the day label.
            binding.tvTime.text = formatWhen(event)

            binding.root.setOnClickListener { onEventClick(event) }
        }

        /**
         * A past check-in of someone a check-in rule is about (HistoryCheckIns): "{name} checked in"
         * or "You checked in", with the check-in screen's icon for its type, the type's name when it
         * isn't the plain one, its circle and its time. No status: nothing was triggered.
         */
        private fun bindCheckIn(event: AlertEventItem) {
            val context = binding.root.context
            val type = event.checkInType.orEmpty()
            val (icon, label) = CheckInChoices.label(type)
            binding.tvIcon.text = icon
            binding.tvTitle.text = if (event.ownCheckIn) {
                context.getString(R.string.history_you_checked_in)
            } else {
                context.getString(R.string.history_checked_in, event.memberName?.takeIf { it.isNotBlank() } ?: "A member")
            }
            binding.tvMessage.text = listOfNotNull(label.takeIf { type in HistoryCheckIns.NAMED_TYPES }, event.circleName).joinToString(" · ")
            binding.tvStatus.visibility = View.GONE
            binding.tvStatus.alpha = 1.0f
            binding.tvTime.text = formatWhen(event)
            binding.root.setOnClickListener { onEventClick(event) }
        }
    }

    companion object {
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        /** "Today · 2:14 PM" when an exact time is known (local events), else a day-bucket label. */
        private fun formatWhen(event: AlertEventItem): String {
            val millis = event.timestampMillis
            return if (millis != null) {
                val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)
                "${notificationRelativeLabel(millis)} · $time"
            } else {
                dayBucketLabel(event.dayBucket)
            }
        }

        /** Relative label from a UTC day bucket (matches the bell's day-bucket fallback). */
        private fun dayBucketLabel(dayBucket: Int): String {
            val today = (System.currentTimeMillis() / 86_400_000L).toInt()
            val daysAgo = today - dayBucket
            return when {
                daysAgo <= 0 -> "Today"
                daysAgo == 1 -> "Yesterday"
                daysAgo < 7 -> "$daysAgo days ago"
                daysAgo < 30 -> "${daysAgo / 7} week${if (daysAgo / 7 == 1) "" else "s"} ago"
                else -> "${daysAgo / 30} month${if (daysAgo / 30 == 1) "" else "s"} ago"
            }
        }
    }

    class AlertEventDiffCallback : DiffUtil.ItemCallback<AlertEventItem>() {
        override fun areItemsTheSame(oldItem: AlertEventItem, newItem: AlertEventItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AlertEventItem, newItem: AlertEventItem): Boolean {
            return oldItem == newItem
        }
    }
}
