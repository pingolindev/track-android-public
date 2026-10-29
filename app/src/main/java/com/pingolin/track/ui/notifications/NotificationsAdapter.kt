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

package com.pingolin.track.ui.notifications

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.NotificationEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Relative day label ("Today" / "Yesterday" / "N days ago" / …) for a
 * notification timestamp, computed against LOCAL calendar days.
 *
 * Not a UTC day bucket: its boundary is UTC midnight — i.e.
 * mid-afternoon/evening for users west of UTC — so an event from earlier the
 * same local day would flip to "Yesterday" once the clock passed that
 * boundary.
 *
 * [zone] and [now] are parameters (not read straight from the system) so the
 * timezone behaviour is deterministically unit-testable.
 */
internal fun notificationRelativeLabel(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now()
): String {
    val then = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(then, today).toInt()
    return when {
        // <= 0 also covers a timestamp slightly in the future (server/device
        // clock skew) — show "Today" rather than a negative count.
        daysAgo <= 0 -> "Today"
        daysAgo == 1 -> "Yesterday"
        daysAgo < 7 -> "$daysAgo days ago"
        daysAgo < 30 -> "${daysAgo / 7} week${if (daysAgo / 7 == 1) "" else "s"} ago"
        else -> "${daysAgo / 30} month${if (daysAgo / 30 == 1) "" else "s"} ago"
    }
}

/**
 * Day label PLUS the clock time — "Today, 7:25 PM".
 *
 * The day alone is not enough context. Place-alert copy bakes a relative
 * phrase in at creation ("Alice left Home 3 minutes ago" —
 * PlaceAlertRules.describe computes `agoLabel(now - surfacing.at)` once and
 * stores the result), so hours later the row still claims "3 minutes ago"
 * while sitting under a bare "Today". Reading it, you cannot tell whether it
 * happened minutes or hours back. The clock time is the missing anchor: the
 * relative phrase describes the CROSSING, this describes when the
 * notification landed.
 *
 * [formatTime] is injected so the caller can pass Android's
 * DateFormat.getTimeFormat(context) — which honours the device's 24-hour
 * setting — while tests stay deterministic and JVM-only.
 */
internal fun notificationTimestampLabel(
    epochMillis: Long,
    formatTime: (Long) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now()
): String = "${notificationRelativeLabel(epochMillis, zone, now)}, ${formatTime(epochMillis)}"

class NotificationsAdapter(
    private val onNotificationClick: (NotificationEntity) -> Unit
) : ListAdapter<NotificationEntity, NotificationsAdapter.ViewHolder>(NotificationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvBody: TextView = itemView.findViewById(R.id.tvBody)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val unreadIndicator: View = itemView.findViewById(R.id.unreadIndicator)

        fun bind(notification: NotificationEntity) {
            tvTitle.text = notification.title
            tvBody.text = notification.body ?: ""
            tvBody.visibility = if (notification.body.isNullOrEmpty()) View.GONE else View.VISIBLE

            unreadIndicator.visibility = if (!notification.isRead) View.VISIBLE else View.GONE

            // Prefer the exact timestamp, rendered in the device's local timezone,
            // so "Today"/"Yesterday" match the user's calendar day. The day bucket
            // is UTC-based: west of UTC it rolls over mid-day local, and an event
            // from earlier today reads as "Yesterday". Fall back to it only when
            // createdAt is absent (an older cached row, or a backend that doesn't
            // send the field) — and day-only, since a UTC bucket carries no clock
            // time to show.
            val timeFormat = android.text.format.DateFormat.getTimeFormat(itemView.context)
            tvTime.text = notification.createdAt
                ?.let { millis ->
                    notificationTimestampLabel(millis, { timeFormat.format(java.util.Date(it)) })
                }
                ?: formatDayBucket(notification.createdDayBucket)

            itemView.setOnClickListener {
                onNotificationClick(notification)
            }
        }

        /** Legacy fallback: relative label from the UTC day bucket. */
        private fun formatDayBucket(dayBucket: Int): String {
            val currentDayBucket = (System.currentTimeMillis() / 86400000L).toInt()
            val daysAgo = currentDayBucket - dayBucket

            return when {
                daysAgo <= 0 -> "Today"
                daysAgo == 1 -> "Yesterday"
                daysAgo < 7 -> "$daysAgo days ago"
                daysAgo < 30 -> "${daysAgo / 7} week${if (daysAgo / 7 == 1) "" else "s"} ago"
                else -> "${daysAgo / 30} month${if (daysAgo / 30 == 1) "" else "s"} ago"
            }
        }
    }

    class NotificationDiffCallback : DiffUtil.ItemCallback<NotificationEntity>() {
        override fun areItemsTheSame(oldItem: NotificationEntity, newItem: NotificationEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NotificationEntity, newItem: NotificationEntity): Boolean {
            return oldItem == newItem
        }
    }
}
