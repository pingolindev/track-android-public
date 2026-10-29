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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.databinding.FragmentNotificationsBinding
import com.pingolin.track.ui.start.UnreadableEmptyState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotificationsViewModel by viewModels()
    /** Activity-scoped: carries the map-focus request across the tab switch. */
    private val mainViewModel: com.pingolin.track.ui.main.MainViewModel by activityViewModels()
    private lateinit var adapter: NotificationsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupSwipeToDismiss()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbar.inflateMenu(R.menu.menu_notifications)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_mark_all_read -> {
                    viewModel.markAllAsRead()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = NotificationsAdapter { notification ->
            // Mark as read when clicked
            if (!notification.isRead) {
                viewModel.markAsRead(notification.id)
            }
            // Navigate to relevant screen based on notification type
            navigateForNotification(notification)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@NotificationsFragment.adapter
        }
    }

    /**
     * Navigate to the appropriate screen based on notification type.
     */
    private fun navigateForNotification(notification: com.pingolin.track.data.local.entity.NotificationEntity) {
        val navController = findNavController()

        when (notification.notificationType) {
            // SOS → the alert's own marker on the map when it can be identified,
            // Safety screen otherwise. The sos_id arrives in the row's metadata;
            // a row without it leaves no way back to the alert.
            "sos", "emergency", "sos_alert" -> {
                val sosId = notification.metadata?.let { json ->
                    runCatching {
                        com.google.gson.JsonParser.parseString(json)
                            .asJsonObject.get("sos_id")?.asString
                    }.getOrNull()
                }
                if (sosId != null) {
                    // One with no position yet has nothing on the map: its Safety row says why.
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (mainViewModel.zoomToSos(sosId)) navController.navigate(R.id.mapFragment)
                        else navController.navigate(R.id.sosFragment)
                    }
                } else {
                    navController.navigate(R.id.sosFragment)
                }
            }

            // Boundary and place alerts → Map (to show the location)
            "boundary_enter", "boundary_exit", "place_alert_enter", "place_alert_exit" -> {
                navController.navigate(R.id.mapFragment)
            }

            // Inactivity alerts → Alerts screen
            "inactivity", "inactivity_alert" -> {
                navController.navigate(R.id.alertsContainerFragment)
            }

            // Route arrival → Destinations screen
            "route_arrival", "arrival" -> {
                navController.navigate(R.id.destinationsFragment)
            }

            // Destination shared/arrived → the map, pointed at the
            // destination the notification is about: the destinations list
            // says nothing about WHERE the thing is. The server puts
            // destination_id in the row's metadata; without it (older row)
            // fall back to the list. Any type missing from this when()
            // silently lands on the map default below.
            "destination_shared", "destination_arrival" -> {
                val destinationId = notification.metadata?.let { json ->
                    runCatching {
                        com.google.gson.JsonParser.parseString(json)
                            .asJsonObject.get("destination_id")?.asString
                    }.getOrNull()
                }
                if (destinationId != null) {
                    mainViewModel.requestMapZoomToDestination(destinationId)
                    navController.navigate(R.id.mapFragment)
                } else {
                    navController.navigate(R.id.destinationsFragment)
                }
            }

            // Low battery alerts → Map (to see member status)
            "battery_low", "low_battery" -> {
                navController.navigate(R.id.mapFragment)
            }

            // Privacy/sharing notifications → Settings
            "privacy_resumed", "privacy_paused" -> {
                navController.navigate(R.id.settingsFragment)
            }

            // Key rotation needed → Circles screen
            "key_rotation_required", "key_rotation" -> {
                navController.navigate(R.id.circlesFragment)
            }

            // Check-ins → Check-in screen
            "check_in", "checkin" -> {
                navController.navigate(R.id.checkInFragment)
            }

            // Circle invites → Circles screen
            "invite", "circle_invite" -> {
                navController.navigate(R.id.circlesFragment)
            }

            // "Someone asked to take over your circle" → that circle's own screen,
            // which is where Refuse lives. This row is the surface that outlives the
            // tray notification: the push can be swiped away and the deadline is two
            // weeks out, so the inbox has to lead somewhere useful for as long as the
            // claim is live.
            //
            // The circle id is a COLUMN on the row, not metadata — the server stores
            // {claim_id, resolves_at} under metadata for this type and nothing else,
            // so parsing the JSON the way the destination branch does would find
            // nothing.
            //
            // The claimant's notice that the owner refused opens the same circle: its
            // row keeps the circle in the same column, and its sheet says where
            // things stand now.
            "circle_ownership_claim", "circle_ownership_refused" -> {
                val circleId = notification.circleId
                navController.navigate(R.id.circlesFragment)
                if (!circleId.isNullOrBlank()) {
                    mainViewModel.requestOwnershipClaimScreen(circleId)
                }
            }

            // The claim completed and the circle changed hands. Nothing to act on any
            // more, so this only needs to show them the circle it happened to.
            "circle_ownership_transferred" -> {
                navController.navigate(R.id.circlesFragment)
            }

            // Default: Navigate to map as the main screen
            else -> {
                navController.navigate(R.id.mapFragment)
            }
        }
    }

    /**
     * Swipe-to-dismiss for notification rows. Lets the user clear out stale /
     * expired notifications. Deletes via the ViewModel, which removes the row
     * server-side + locally (optimistic, reverts on failure). A red delete
     * background is drawn behind the row as it swipes, in either direction.
     */
    private fun setupSwipeToDismiss() {
        val background = android.graphics.drawable.ColorDrawable(
            android.graphics.Color.parseColor("#E53935")
        )
        val icon = androidx.core.content.ContextCompat.getDrawable(
            requireContext(), R.drawable.ic_delete
        )?.mutate()?.apply {
            androidx.core.graphics.drawable.DrawableCompat.setTint(this, android.graphics.Color.WHITE)
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val item = adapter.currentList.getOrNull(pos) ?: return
                viewModel.deleteNotification(item.id)
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    if (dX > 0) {
                        background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    } else {
                        background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    }
                    background.draw(c)

                    icon?.let {
                        val iconH = it.intrinsicHeight
                        val iconW = it.intrinsicWidth
                        val top = itemView.top + (itemView.height - iconH) / 2
                        val margin = (16 * resources.displayMetrics.density).toInt()
                        if (dX > 0) {
                            it.setBounds(itemView.left + margin, top, itemView.left + margin + iconW, top + iconH)
                        } else {
                            it.setBounds(itemView.right - margin - iconW, top, itemView.right - margin, top + iconH)
                        }
                        it.draw(c)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshNotifications()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.notifications.collectLatest { notifications ->
                adapter.submitList(notifications)
                UnreadableEmptyState.show(binding.emptyState, notifications.isEmpty())
                binding.recyclerView.visibility = if (notifications.isEmpty()) View.GONE else View.VISIBLE
                // "Mark all as read" over an empty list, or a list with
                // nothing unread, is a menu item that can only ever do
                // nothing. Disable rather than hide: a menu that changes
                // shape is harder to learn than one whose item is visibly
                // unavailable.
                binding.toolbar.menu.findItem(R.id.action_mark_all_read)?.isEnabled =
                    notifications.any { !it.isRead }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRefreshing.collectLatest { isRefreshing ->
                binding.swipeRefresh.isRefreshing = isRefreshing
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
