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

package com.pingolin.track.ui.alerts

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.databinding.ItemAlertRuleBinding
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.util.CheckInCountdown
import com.pingolin.track.util.CheckInRuleText
import com.pingolin.track.util.InactivityWatches

/**
 * A battery/offline alert rule or an inactivity alert, unified so both kinds render
 * in a single list. The two backends stay separate; this only unifies the UI.
 */
sealed class UnifiedAlert {
    abstract val id: String
    abstract val isActive: Boolean
    /** Name of the circle this alert applies to, resolved for display (null if unknown). */
    abstract val circleName: String?

    data class Battery(val rule: AlertRuleEntity, override val circleName: String?) : UnifiedAlert() {
        override val id: String get() = rule.id
        override val isActive: Boolean get() = rule.isActive
    }

    /**
     * An inactivity alert: one that watches location updates, or a check-in rule (its `watches`).
     * [countdown]: for this account's own active check-in rule about itself, what its countdown line
     * is about (CheckInCountdown), once read; null shows no countdown.
     */
    data class Inactivity(
        val alert: InactivityAlertEntity,
        override val circleName: String?,
        val countdown: CheckInCountdown.Countdown? = null,
    ) : UnifiedAlert() {
        override val id: String get() = alert.id
        override val isActive: Boolean get() = alert.isActive
    }
}

class UnifiedAlertAdapter(
    private val onClick: (UnifiedAlert) -> Unit,
    private val onToggle: (UnifiedAlert, Boolean) -> Unit,
    private val onDelete: (UnifiedAlert) -> Unit
) : ListAdapter<UnifiedAlert, UnifiedAlertAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAlertRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    /** A [COUNTDOWN] tick redraws the countdown line alone: the row and its focus stay as they are. */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == COUNTDOWN }) holder.bindCountdown(getItem(position))
        else onBindViewHolder(holder, position)
    }

    /**
     * Redraw every countdown line for the time now. Returns how long until one reads differently
     * (CheckInCountdown.nextChangeIn), or null when no row has one.
     */
    fun tickCountdowns(now: Long = System.currentTimeMillis()): Long? {
        val countdowns = currentList.mapNotNull { (it as? UnifiedAlert.Inactivity)?.countdown }
        if (countdowns.isEmpty()) return null
        notifyItemRangeChanged(0, itemCount, COUNTDOWN)
        return countdowns.minOf { CheckInCountdown.nextChangeIn(it, now) }
    }

    inner class VH(private val binding: ItemAlertRuleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UnifiedAlert) {
            val title: String = when (item) {
                is UnifiedAlert.Battery -> {
                    val (icon, t, desc) = when (item.rule.ruleType) {
                        AlertRuleRepository.RULE_TYPE_LOW_BATTERY ->
                            Triple("🔋", "Low Battery", "Alert when battery is low")
                        else -> Triple("⚠️", "Alert", "Custom alert")
                    }
                    binding.tvIcon.text = icon
                    binding.tvDescription.text = desc
                    t
                }
                is UnifiedAlert.Inactivity -> if (InactivityWatches.isCheckIn(item.alert.watches)) {
                    // A check-in rule: "Check in within 6 hours", or "Check in by 9:00 PM, every day"
                    // with its time zone.
                    val context = binding.root.context
                    binding.tvIcon.text = if (item.alert.isSelfAlert) "🔔" else "✅"
                    binding.tvDescription.text = CheckInRuleText.summary(context, item.alert)
                    context.getString(R.string.check_in_rule_type)
                } else {
                    val hrs = item.alert.timeoutHours
                    val timeDesc = if (hrs == 1) "1 hour" else "$hrs hours"
                    binding.tvIcon.text = if (item.alert.isSelfAlert) "🔔" else "⏰"
                    binding.tvDescription.text = if (item.alert.isSelfAlert) {
                        "Alert me if I'm inactive for $timeDesc"
                    } else {
                        val who = item.alert.targetUserIds
                            ?.split(",")?.filter { it.isNotBlank() }?.size
                            ?.let { if (it == 1) "1 member" else "$it members" }
                            ?: "everyone"
                        "Watch $who · inactive $timeDesc"
                    }
                    if (item.alert.isSelfAlert) "Self Inactivity" else "Inactivity"
                }
            }

            // Circle name rides on the title line (de-emphasized): it's the primary
            // disambiguator across rules, so it sits where the eye lands first instead
            // of trailing the description, where it would truncate first.
            binding.tvTitle.text = titleWithCircle(title, item.circleName)

            // Clear the listener before setting state so recycled rows don't fire a toggle.
            binding.switchActive.setOnCheckedChangeListener(null)
            binding.switchActive.isChecked = item.isActive
            binding.switchActive.setOnCheckedChangeListener { _, checked ->
                if (checked != item.isActive) onToggle(item, checked)
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
            bindCountdown(item)
        }

        /**
         * "Your circle is alerted in 3 h 12 min" (or a scheduled rule's line, "Your circle is alerted
         * at 9:00 PM unless you check in.") under this account's own check-in rule about itself, once
         * its circle has been read; nothing on any other row. Read with the rest of the row, and no
         * live region: a minute's change is not an announcement.
         */
        fun bindCountdown(item: UnifiedAlert) {
            val text = (item as? UnifiedAlert.Inactivity)?.countdown?.let {
                CheckInCountdown.text(binding.root.context, it, System.currentTimeMillis())
            }
            binding.tvCountdown.visibility = if (text != null) View.VISIBLE else View.GONE
            binding.tvCountdown.text = text
        }

        /**
         * Bold rule title, with the circle name appended in the muted color the
         * description uses, so the title stays scannable while still naming the circle.
         */
        private fun titleWithCircle(title: String, circleName: String?): CharSequence {
            val sb = SpannableStringBuilder(title)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!circleName.isNullOrBlank()) {
                val start = sb.length
                sb.append(" · ").append(circleName)
                sb.setSpan(
                    ForegroundColorSpan(binding.tvDescription.currentTextColor),
                    start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return sb
        }
    }

    companion object {
        /** The payload of a countdown tick ([tickCountdowns]). */
        const val COUNTDOWN = "countdown"
    }

    class Diff : DiffUtil.ItemCallback<UnifiedAlert>() {
        override fun areItemsTheSame(o: UnifiedAlert, n: UnifiedAlert): Boolean =
            o.id == n.id && o::class == n::class
        override fun areContentsTheSame(o: UnifiedAlert, n: UnifiedAlert): Boolean = o == n
    }
}
