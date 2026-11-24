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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.databinding.DialogTimeZonePickerBinding
import com.pingolin.track.util.CheckInRuleText
import com.pingolin.track.util.uiLocale
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The time zone list of a scheduled check-in rule's editor: every named place zone this phone
 * knows, and UTC, by city ("New York", in the language of the app's words), with its offset now,
 * and a search box. This phone's zone comes first; for a rule about yourself, "Phone's time zone"
 * before it: the zone that follows this phone (a null pick), the editor's default there.
 */
object TimeZonePicker {

    /** One line of the list: a zone ([id] null: the phone's, following it), its name and its offset now. */
    data class Entry(val id: String?, val name: String, val offset: String)

    /** The areas of the IANA names that are places (no "Etc/…", "US/…" or other old aliases). */
    private val AREAS = listOf(
        "Africa/", "America/", "Antarctica/", "Arctic/", "Asia/", "Atlantic/", "Australia/", "Europe/", "Indian/", "Pacific/",
    )

    /** The zones offered: the place zones this phone knows, and UTC. */
    fun zoneIds(): List<String> =
        ZoneId.getAvailableZoneIds().filter { id -> AREAS.any { id.startsWith(it) } } + "UTC"

    /** [id]'s offset at [now], as "UTC−05:00" ("UTC" when none). */
    fun offset(id: String, now: Instant): String {
        val offset = runCatching { ZoneId.of(id).rules.getOffset(now) }.getOrNull() ?: return "UTC"
        return if (offset == ZoneOffset.UTC) "UTC" else "UTC" + offset.id.replace('-', '−')
    }

    /**
     * The list's lines: "Phone's time zone" first when [includePhone] (following [phoneZone], with
     * its offset), then this phone's zone by its city, then every other zone ([zoneIds]) by name.
     */
    fun entries(context: Context, locale: Locale, phoneZone: String, includePhone: Boolean, now: Instant): List<Entry> {
        val here = Entry(phoneZone, CheckInRuleText.zoneName(phoneZone, locale), offset(phoneZone, now))
        val others = (zoneIds() - phoneZone).map { Entry(it, CheckInRuleText.zoneName(it, locale), offset(it, now)) }
            .sortedWith(compareBy({ it.name.lowercase(locale) }, { it.id }))
        val following = Entry(null, context.getString(R.string.check_in_phone_time_zone), offset(phoneZone, now))
        return (if (includePhone) listOf(following) else emptyList()) + here + others
    }

    /** The lines of [entries] a search for [query] finds: by name or IANA name, any case; all for a blank one. */
    fun search(entries: List<Entry>, query: String, locale: Locale): List<Entry> {
        // "new_york" as an IANA name spells it finds New York too.
        val q = query.trim().lowercase(locale).replace('_', ' ')
        if (q.isEmpty()) return entries
        return entries.filter { e ->
            e.name.lowercase(locale).contains(q) || (e.id?.lowercase(Locale.ROOT)?.replace('_', ' ')?.contains(q) == true)
        }
    }

    /**
     * Show the list; [onPicked] gets the zone picked, or null for "Phone's time zone" (offered when
     * [includePhone]).
     */
    fun show(context: Context, inflater: LayoutInflater, phoneZone: String, includePhone: Boolean, onPicked: (String?) -> Unit) {
        val binding = DialogTimeZonePickerBinding.inflate(inflater)
        val locale = uiLocale(context)
        val all = entries(context, locale, phoneZone, includePhone, Instant.now())
        val adapter = object : ArrayAdapter<Entry>(context, android.R.layout.simple_list_item_2, android.R.id.text1, all.toMutableList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val entry = getItem(position) ?: return view
                view.findViewById<TextView>(android.R.id.text1).text = entry.name
                view.findViewById<TextView>(android.R.id.text2).text = entry.offset
                return view
            }
        }
        binding.lvZones.adapter = adapter
        binding.etZoneSearch.doAfterTextChanged { text ->
            adapter.clear()
            adapter.addAll(search(all, text?.toString().orEmpty(), locale))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.check_in_time_zone)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        binding.lvZones.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position) ?: return@setOnItemClickListener
            onPicked(entry.id)
            dialog.dismiss()
        }
        dialog.show()
    }
}
