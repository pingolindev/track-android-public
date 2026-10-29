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

package com.pingolin.track.ui.devices

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.SessionInfo
import com.pingolin.track.data.remote.dto.TrackingDevice
import com.pingolin.track.databinding.ItemDeviceBinding
import com.pingolin.track.databinding.ItemDeviceCurrentBinding
import com.pingolin.track.databinding.ItemDeviceSectionHeaderBinding

/**
 * One row per device-list line. Four view types to render the design
 * (filled hero card for "this device", inline section labels with an
 * optional bulk action, the standard per-device card, and the same card
 * without an overflow menu for a device that has no sign-in behind it).
 */
sealed class DeviceListItem {
    data class Current(val session: SessionInfo, val subtitle: String) : DeviceListItem()
    data class SectionHeader(val title: String, val actionLabel: String?) : DeviceListItem()
    data class Other(
        val session: SessionInfo,
        val subtitle: String,
        val dimmed: Boolean = false,
    ) : DeviceListItem()

    /**
     * A device the server holds a push registration for that is not linked to
     * any auth session.
     *
     * These are real, addressable devices: the server lists them with a device
     * id and will push to them. What it has no back-link to is a session, and
     * every other row on this screen is built around one — which is why this
     * carries the [TrackingDevice] itself and its own [name] instead.
     *
     * No session means nothing to rename and nothing to sign out, so this row
     * has no overflow menu. Showing it anyway is the point: the question people
     * bring to this screen is "why is that phone not getting notifications", and
     * a device that is absent from the list cannot be part of the answer.
     */
    data class Unlinked(
        val device: TrackingDevice,
        val name: String,
        val subtitle: String,
        val dimmed: Boolean = false,
    ) : DeviceListItem()
}

class DevicesAdapter(
    private val onRenameCurrent: (SessionInfo) -> Unit,
    private val onRowMenu: (SessionInfo, View) -> Unit,
    private val onSectionAction: (String) -> Unit,
) : ListAdapter<DeviceListItem, RecyclerView.ViewHolder>(Diff()) {

    companion object {
        private const val TYPE_CURRENT = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_OTHER = 2
        private const val TYPE_UNLINKED = 3
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DeviceListItem.Current -> TYPE_CURRENT
        is DeviceListItem.SectionHeader -> TYPE_HEADER
        is DeviceListItem.Other -> TYPE_OTHER
        is DeviceListItem.Unlinked -> TYPE_UNLINKED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CURRENT -> CurrentVH(ItemDeviceCurrentBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderVH(ItemDeviceSectionHeaderBinding.inflate(inflater, parent, false))
            // Same card as TYPE_OTHER; the difference is entirely in what the
            // holder binds, so there is no second layout to keep in step.
            TYPE_UNLINKED -> UnlinkedVH(ItemDeviceBinding.inflate(inflater, parent, false))
            else -> OtherVH(ItemDeviceBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DeviceListItem.Current -> (holder as CurrentVH).bind(item)
            is DeviceListItem.SectionHeader -> (holder as HeaderVH).bind(item)
            is DeviceListItem.Other -> (holder as OtherVH).bind(item)
            is DeviceListItem.Unlinked -> (holder as UnlinkedVH).bind(item)
        }
    }

    inner class CurrentVH(private val binding: ItemDeviceCurrentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DeviceListItem.Current) {
            binding.tvName.text = displayName(item.session)
            binding.tvSubtitle.text = item.subtitle
            binding.ivIcon.setImageResource(iconFor(item.session))
            binding.btnRename.setOnClickListener { onRenameCurrent(item.session) }
        }
    }

    inner class HeaderVH(private val binding: ItemDeviceSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DeviceListItem.SectionHeader) {
            binding.tvSectionTitle.text = item.title
            if (item.actionLabel != null) {
                binding.btnSectionAction.visibility = View.VISIBLE
                binding.btnSectionAction.text = item.actionLabel
                binding.btnSectionAction.setOnClickListener { onSectionAction(item.title) }
            } else {
                binding.btnSectionAction.visibility = View.GONE
                binding.btnSectionAction.setOnClickListener(null)
            }
        }
    }

    inner class OtherVH(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DeviceListItem.Other) {
            binding.tvName.text = displayName(item.session)
            binding.tvSubtitle.text = item.subtitle
            binding.ivIcon.setImageResource(iconFor(item.session))
            binding.card.alpha = if (item.dimmed) 0.6f else 1.0f
            // Explicit, even though RecyclerView pools by view type and so never
            // hands this holder a view UnlinkedVH hid: the two share a layout, and
            // a shared layout with one holder hiding a view the other assumes is
            // showing is the kind of coupling that survives until someone merges
            // the view types.
            binding.btnOverflow.visibility = View.VISIBLE
            binding.btnOverflow.setOnClickListener { v -> onRowMenu(item.session, v) }
            binding.card.setOnClickListener { v -> onRowMenu(item.session, v) }
        }
    }

    inner class UnlinkedVH(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DeviceListItem.Unlinked) {
            binding.tvName.text = item.name
            binding.tvSubtitle.text = item.subtitle
            binding.ivIcon.setImageResource(iconForPlatform(item.device.platform))
            binding.card.alpha = if (item.dimmed) 0.6f else 1.0f
            // No session behind this row, so neither of the two actions the menu
            // offers has anything to act on: rename writes to a session, and sign
            // out revokes one. Clear BOTH listeners rather than leaving whatever
            // the recycled OtherVH row last set on them.
            binding.btnOverflow.visibility = View.GONE
            binding.btnOverflow.setOnClickListener(null)
            binding.card.setOnClickListener(null)
            binding.card.isClickable = false
        }
    }

    private fun displayName(session: SessionInfo): String {
        val raw = session.deviceName?.trim().orEmpty()
        if (raw.isNotEmpty()) return raw
        // Server-side row with no user-agent parse (typically an
        // older session or a service-account login). Fall back to a
        // friendly placeholder rather than a literal "null" /
        // "Unknown Device".
        return "Unnamed device"
    }

    /**
     * Best-effort icon picker. The server gives us a free-form device_name
     * with a User-Agent-parsed prefix like "Firefox on Windows 10" or
     * "on Android"; we sniff the string for the platform marker. No exact
     * match? Fall back to a generic devices icon.
     */
    private fun iconFor(session: SessionInfo): Int {
        val name = session.deviceName.orEmpty().lowercase()
        return when {
            name.contains("iphone") || name.contains("ipad") || name.contains("ios") ->
                R.drawable.ic_phone_iphone
            name.contains("android") -> R.drawable.ic_phone_android
            name.contains("windows") || name.contains("macos") || name.contains("linux") ||
                name.contains("chrome") || name.contains("firefox") || name.contains("edge") ||
                name.contains("safari") -> R.drawable.ic_laptop
            else -> R.drawable.ic_devices_other
        }
    }

    /**
     * Icon for a row that has no session name to sniff. `platform` is one of
     * `android` / `ios` / `web` — the server rejects anything else — so this
     * is an exhaustive match with a defensive fallback rather than the
     * substring guesswork [iconFor] has to do.
     */
    private fun iconForPlatform(platform: String): Int = when (platform.trim().lowercase()) {
        "android" -> R.drawable.ic_phone_android
        "ios" -> R.drawable.ic_phone_iphone
        "web" -> R.drawable.ic_laptop
        else -> R.drawable.ic_devices_other
    }

    private class Diff : DiffUtil.ItemCallback<DeviceListItem>() {
        override fun areItemsTheSame(a: DeviceListItem, b: DeviceListItem): Boolean = when {
            a is DeviceListItem.Current && b is DeviceListItem.Current -> a.session.id == b.session.id
            a is DeviceListItem.Other && b is DeviceListItem.Other -> a.session.id == b.session.id
            a is DeviceListItem.Unlinked && b is DeviceListItem.Unlinked -> a.device.id == b.device.id
            a is DeviceListItem.SectionHeader && b is DeviceListItem.SectionHeader -> a.title == b.title
            else -> false
        }

        override fun areContentsTheSame(a: DeviceListItem, b: DeviceListItem): Boolean = a == b
    }
}
