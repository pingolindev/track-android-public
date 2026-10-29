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

package com.pingolin.track.ui.sos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.databinding.ItemSosAlertBinding
import java.text.SimpleDateFormat
import java.util.*

class SosAlertsAdapter(
    private val onAcknowledgeClick: (SosAlertEntity) -> Unit,
    private val onResolveClick: (SosAlertEntity) -> Unit,
    private val onCancelClick: (SosAlertEntity) -> Unit,
    /** Row tap: open this alert's marker on the map. */
    private val onAlertClick: (SosAlertEntity) -> Unit,
    /** Side action: hide for this user only. Not acknowledge, not resolve. */
    private val onDismissClick: (SosAlertEntity) -> Unit,
    /** Undo a dismissal during the grace window — restores the map marker too. */
    private val onUndismissClick: (SosAlertEntity) -> Unit,
    private val isMyAlert: (SosAlertEntity) -> Boolean,
    /** My own alert went without a location for want of permission or Location Services: the way to turn it on. */
    private val onLocationSettingsClick: () -> Unit = {},
    /** Whether location is off on this phone now: no permission, or Location Services off. */
    private val locationOff: () -> Boolean = { false },
) : ListAdapter<SosAlertRow, SosAlertsAdapter.AlertViewHolder>(AlertDiffCallback()) {

    /** The user's distance unit, for "Within about {distance}". Set by SosFragment. */
    var useMiles: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /**
     * For my own alerts, by alert id: who is responding, by name ("Ana and 1 other are responding"), in place
     * of the count. Set by SosFragment from
     * [MyAlertResponders][com.pingolin.track.domain.repository.MyAlertResponders].
     */
    var responders: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val binding = ItemSosAlertBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AlertViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertViewHolder(
        private val binding: ItemSosAlertBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun bind(row: SosAlertRow) {
            val alert = row.alert
            val isMine = isMyAlert(alert)

            // Uses the shared string resources so this stays in step with the
            // rest of the screen.
            binding.tvAlertTitle.text = if (isMine) {
                itemView.context.getString(R.string.sos_from_you)
            } else {
                // Who asked, when this phone can tell by the receivers' rules.
                row.raiserName?.let { itemView.context.getString(R.string.sos_from_member, it) }
                    ?: itemView.context.getString(R.string.sos_from_member_unknown)
            }

            // Acknowledging must NOT flip the shared status: doing so
            // wipes a live emergency off every other member's list. An
            // alert this user has acknowledged is still "active" on the
            // wire and reads "acknowledged" only from where they are
            // standing. My OWN request reads "acknowledged" once anyone
            // has answered it, as iOS's card does, never "Waiting for
            // someone to respond" above who is responding.
            val answered = if (isMine) alert.acknowledgmentCount > 0 else alert.acknowledgedByMe
            val effectiveStatus =
                if (alert.status == "active" && answered) "acknowledged" else alert.status

            binding.tvStatus.text = when (effectiveStatus) {
                "active" -> "Waiting for someone to respond"
                "acknowledged" -> "Acknowledged"
                "resolved" -> "Resolved"
                "false_alarm" -> "Cancelled"
                // Local-only: the alert left /sos/active and the per-alert
                // re-fetch couldn't tell us why (SosRepository.STATUS_CLOSED).
                "closed" -> "Closed"
                else -> alert.status
            }

            binding.tvTime.text = dateFormat.format(Date(alert.createdAt))

            // Where the position stands, as the row was judged ([SosAlertRow.position]): a marker's
            // words, a last-known or updated time, an area's size; the raiser's own words for mine.
            val context = itemView.context
            val position = SosPositionText.lines(context, alert, row.position, isMine, useMiles)
            binding.tvPosition.visibility = if (position != null) View.VISIBLE else View.GONE
            binding.tvPosition.text = position
            // Mine went without a location, and location is still off here: the way to turn it on.
            val settings = isMine && alert.status == "active" &&
                row.position == com.pingolin.track.domain.sos.SosPosition.Unavailable && locationOff()
            binding.btnLocationSettings.visibility = if (settings) View.VISIBLE else View.GONE
            binding.btnLocationSettings.setOnClickListener { onLocationSettingsClick() }

            // WHERE it was raised. Resolved on device from the decrypted
            // coordinates — a responder reading this list should not have to
            // open the map to find out where to go. Absent when the alert has
            // no decryptable location, or when this device has no geocoding
            // data to resolve one with.
            val address = row.address
            if (address != null) {
                binding.tvAddress.visibility = View.VISIBLE
                binding.tvAddress.text = address
            } else {
                binding.tvAddress.visibility = View.GONE
            }

            // The count comes from the server as a bare number:
            // acknowledgedBy, a CSV of names, is not populated for end-to-end
            // encrypted alerts. Who they are reaches the raiser alone, by GET
            // /sos/{id} ([responders]).
            val acknowledgedCount = alert.acknowledgmentCount
            val named = responders[alert.id]?.takeIf { isMine }
            if (named != null) {
                binding.tvAcknowledged.visibility = View.VISIBLE
                binding.tvAcknowledged.text = named
            } else if (acknowledgedCount > 0) {
                binding.tvAcknowledged.visibility = View.VISIBLE
                binding.tvAcknowledged.text = "$acknowledgedCount member(s) acknowledged"
            } else {
                binding.tvAcknowledged.visibility = View.GONE
            }

            // Show appropriate actions based on status and ownership
            when {
                alert.status != "active" -> {
                    binding.btnAcknowledge.visibility = View.GONE
                    binding.btnResolve.visibility = View.GONE
                    binding.btnCancel.visibility = View.GONE
                }
                isMine -> {
                    binding.btnAcknowledge.visibility = View.GONE
                    binding.btnResolve.visibility = View.VISIBLE
                    binding.btnCancel.visibility = View.VISIBLE
                }
                else -> {
                    // Don't keep offering Acknowledge to someone who already
                    // acknowledged. The alert legitimately stays `active` after
                    // they do, so status alone cannot answer this.
                    binding.btnAcknowledge.visibility =
                        if (alert.acknowledgedByMe) View.GONE else View.VISIBLE
                    // Nothing closes someone else's request,
                    // an owner's or an admin's tap included:
                    // both sides of an alert are personal, so
                    // nobody can affect the other. Acknowledge
                    // and the private dismiss are all another
                    // member has; only the raiser resolves or
                    // cancels.
                    binding.btnResolve.visibility = View.GONE
                    binding.btnCancel.visibility = View.GONE
                }
            }

            binding.btnAcknowledge.setOnClickListener { onAcknowledgeClick(alert) }
            binding.btnResolve.setOnClickListener { onResolveClick(alert) }
            binding.btnCancel.setOnClickListener { onCancelClick(alert) }

            // Row tap → the alert's marker on the map. Only meaningful while the
            // alert is live and has coordinates that decrypted; a closed alert
            // has nothing to fly to.
            val navigable = alert.status == "active" && alert.latitude != null && alert.longitude != null
            binding.root.isClickable = navigable
            binding.root.setOnClickListener(if (navigable) View.OnClickListener { onAlertClick(alert) } else null)

            // Dismiss is a response to a live alert; once it's resolved or
            // cancelled there's nothing left to hide.
            binding.btnDismiss.visibility = if (alert.status == "active") View.VISIBLE else View.GONE
            binding.btnDismiss.setOnClickListener { onDismissClick(alert) }

            // A dismissed alert lingers here for 24h so a mistaken tap can be
            // undone. It is already off the map — this row is the only way back.
            // Greyed so it reads as "on its way out" rather than live.
            val dismissedAt = alert.dismissedAt
            if (alert.dismissedByMe && dismissedAt != null) {
                val remaining = (dismissedAt + SosAlertEntity.DISMISS_GRACE_MS) - System.currentTimeMillis()
                binding.tvDismissedCountdown.visibility = View.VISIBLE
                binding.tvDismissedCountdown.text = if (remaining > 0) {
                    binding.root.context.getString(
                        R.string.sos_dismissed_countdown, formatRemaining(remaining)
                    )
                } else {
                    binding.root.context.getString(R.string.sos_dismissed_expiring)
                }
                binding.btnUndismiss.visibility = View.VISIBLE
                binding.btnUndismiss.setOnClickListener { onUndismissClick(alert) }
                // Dim the whole card, and drop the actions that only make sense
                // for an alert you are still engaging with.
                binding.root.alpha = 0.55f
                binding.btnDismiss.visibility = View.GONE
                binding.btnAcknowledge.visibility = View.GONE
            } else {
                binding.tvDismissedCountdown.visibility = View.GONE
                binding.btnUndismiss.visibility = View.GONE
                binding.root.alpha = 1.0f
            }
        }
    }

    /** "23h 58m" / "45m" / "30s" — coarse enough to be readable, fine enough
     *  near the end that the row visibly counts down. */
    private fun formatRemaining(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            totalMinutes > 0 -> "${minutes}m"
            else -> "${(ms / 1000).coerceAtLeast(0)}s"
        }
    }

    class AlertDiffCallback : DiffUtil.ItemCallback<SosAlertRow>() {
        override fun areItemsTheSame(oldItem: SosAlertRow, newItem: SosAlertRow): Boolean {
            return oldItem.alert.id == newItem.alert.id
        }

        // Data class equality covers the address too, so a row re-binds when
        // its geocode lands rather than staying blank until something else
        // happens to change.
        override fun areContentsTheSame(oldItem: SosAlertRow, newItem: SosAlertRow): Boolean {
            return oldItem == newItem
        }
    }
}
