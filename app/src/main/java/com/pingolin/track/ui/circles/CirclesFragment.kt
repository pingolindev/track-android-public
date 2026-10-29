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

package com.pingolin.track.ui.circles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.PendingInviteEntity
import com.pingolin.track.databinding.FragmentCirclesBinding
import com.pingolin.track.databinding.DialogJoinCircleBinding
import com.pingolin.track.databinding.DialogCirclePrivacyBinding
import com.pingolin.track.databinding.DialogManageMembersBinding
import com.pingolin.track.databinding.DialogPendingInvitesBinding
import com.pingolin.track.databinding.DialogReceivedInvitesBinding
import com.pingolin.track.databinding.DialogSharePlaceBinding
import com.pingolin.track.ui.auth.AuthField
import com.pingolin.track.ui.auth.AuthFormValidation
import com.pingolin.track.ui.places.CircleSelectionAdapter
import com.pingolin.track.domain.repository.CirclesPaginationState
import com.pingolin.track.ui.start.UnreadableEmptyState
import com.pingolin.track.util.OwnershipClaimFormat
import androidx.recyclerview.widget.LinearLayoutManager as ShareLinearLayoutManager
import androidx.navigation.fragment.findNavController
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

@AndroidEntryPoint
class CirclesFragment : Fragment() {

    @javax.inject.Inject
    lateinit var sessionManager: com.pingolin.track.util.SessionManager

    /** An answered invitation's banner leaves the shade. */
    @javax.inject.Inject
    lateinit var alertHandler: com.pingolin.track.util.AlertHandler

    private var _binding: FragmentCirclesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CirclesViewModel by viewModels()

    /** Activity-scoped: circle selection and map-focus requests are shared
     *  with MapFragment, which reads them after we navigate away. */
    private val mainViewModel: com.pingolin.track.ui.main.MainViewModel by activityViewModels()

    private lateinit var circlesAdapter: CirclesAdapter

    /** Tracks the (circleId:userId) of the member key alert dialog on
     * screen, so we don't pop the same one twice while it's still open. */
    private var currentMemberKeyAlertDialogTarget: String? = null

    /** The (circleId:userId) whose "Let them in?" question is showing, if any. */
    private var currentKeyAccessDialogTarget: String? = null

    /** The statement id of the "Is this really {email}?" renewal question on screen. */
    private var currentRenewalDialogTarget: String? = null

    /** The waiting change whose question is on screen, if any. */
    private var currentPendingQuestionTarget: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCirclesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
        pollInvitesWhileResumed()
        markShowingWhileResumed()
    }

    /**
     * While this tab is on screen its questions are on it too, so none of them is
     * posted as a notification. RESUMED-scoped like [pollInvitesWhileResumed].
     */
    private fun markShowingWhileResumed() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                com.pingolin.track.util.MembershipQuestionNotifier.circlesScreenShowing = true
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    com.pingolin.track.util.MembershipQuestionNotifier.circlesScreenShowing = false
                }
            }
        }
    }

    /**
     * Poll for received invites while this tab is actually on screen — the
     * fallback for when their push does not arrive (e.g. after `pm clear` wiped
     * the FCM token).
     *
     * RESUMED-scoped on purpose. Launched from onResume() on the VIEW's scope,
     * which lives until onDestroyView, the loop would keep running after the user
     * left the app (the foreground service keeps the process alive), and every
     * return to the tab would launch another one on top. repeatOnLifecycle
     * cancels on pause and restarts on resume, and each start fetches once
     * immediately. 60 s, not 15: invites also arrive by push.
     */
    private fun pollInvitesWhileResumed() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    viewModel.fetchReceivedInvites()
                    delay(INVITE_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        circlesAdapter = CirclesAdapter(
            onCircleClick = { circle -> showPrivacySettingsDialog(circle) },
            onLeaveClick = { circle -> showLeaveConfirmation(circle) },
            onDeleteClick = { circle -> showDeleteConfirmation(circle) },
            onInviteClick = { circle -> showInviteDialog(circle) },
            onOrderChanged = { ordered -> viewModel.updateCircleOrder(ordered) },
            isLocked = { circle -> viewModel.isCircleLocked(circle) },
            onLockedInfoClick = { circle -> showLockedCircleExplanation(circle) },
            onPendingClick = { circle -> showPendingChanges(circle) },
        )

        // Drag-to-reorder via long-press, mirroring the Places tab.
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0 // no swipe
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // The pagination "loading" row is not draggable.
                if (viewHolder is CirclesAdapter.LoadingViewHolder) return 0
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (target is CirclesAdapter.LoadingViewHolder) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                circlesAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { /* swipe disabled */ }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    circlesAdapter.onDragStart()
                    viewHolder?.itemView?.alpha = 0.7f
                    viewHolder?.itemView?.scaleX = 1.02f
                    viewHolder?.itemView?.scaleY = 1.02f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                circlesAdapter.onDragComplete()
            }
        }
        val itemTouchHelper = ItemTouchHelper(touchCallback)
        circlesAdapter.setItemTouchHelper(itemTouchHelper)

        val linearLayoutManager = LinearLayoutManager(requireContext())
        binding.rvCircles.apply {
            layoutManager = linearLayoutManager
            adapter = circlesAdapter

            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dy <= 0) return

                    val totalItemCount = linearLayoutManager.itemCount
                    val lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition()

                    // Load more when within 5 items of the end
                    if (lastVisibleItem >= totalItemCount - 5) {
                        viewModel.loadMoreCircles()
                    }
                }
            })
        }
        itemTouchHelper.attachToRecyclerView(binding.rvCircles)
    }

    private fun setupUI() {
        binding.fabCreateCircle.setOnClickListener {
            showCreateDialog()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshCircles(force = true)
            viewModel.fetchReceivedInvites()
        }

        binding.cardReceivedInvites.setOnClickListener {
            showReceivedInvitesDialog()
        }

    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.circles.collectLatest { circles ->
                binding.swipeRefresh.isRefreshing = false
                circlesAdapter.submitList(circles)
            }
        }

        // The empty state follows a flow that combines the list with the
        // loading state (see CirclesViewModel.showEmptyState). Deciding it here
        // from the list alone would leave a new account's tab blank: the list
        // is empty while the first page loads, and the end of loading never
        // re-emits the list.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showEmptyState.collectLatest { show ->
                UnreadableEmptyState.show(binding.emptyState, show, binding.fabCreateCircle)
                binding.rvCircles.visibility = if (show) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.paginationState.collectLatest { state ->
                circlesAdapter.setLoadingMore(state.isLoading && viewModel.circles.value.isNotEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.receivedInvites.collectLatest { invites ->
                if (invites.isNotEmpty()) {
                    binding.cardReceivedInvites.visibility = View.VISIBLE
                    val count = invites.size
                    binding.tvReceivedInvitesCount.text =
                        resources.getQuantityString(R.plurals.received_invites_count, count, count)
                } else {
                    binding.cardReceivedInvites.visibility = View.GONE
                }
            }
        }

        // Re-key warnings for circles somebody left or was removed from.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rekeyWarnings.collectLatest { circlesAdapter.setRekeyWarnings(it) }
        }

        // Changes waiting to go out from this phone, counted on their circle.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingLines.collectLatest { circlesAdapter.setPendingLines(it) }
        }

        // Invite on an owner's and an admin's card, where it takes effect.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.inviteOffered.collectLatest { circlesAdapter.setInviteOffered(it) }
        }

        // "Let <name> into <circle>?": one at a time, like the reset
        // confirmations below.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keyAccessRequests.collectLatest { requests ->
                val head = requests.firstOrNull() ?: return@collectLatest
                if (currentKeyAccessDialogTarget == "${head.circleId}:${head.userId}") return@collectLatest
                showKeyAccessDialog(head)
            }
        }

        // "Is this really {email}?" about an invitation this account made.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.inviteRenewalQuestions.collectLatest { questions ->
                val head = questions.firstOrNull() ?: return@collectLatest
                if (currentRenewalDialogTarget == head.statementId) return@collectLatest
                showInviteRenewalDialog(head)
            }
        }

        // What changes waiting to sync ask again: one at a time, in place of the live
        // question.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingQuestions.collectLatest { questions ->
                val (change, ask) = questions.firstOrNull() ?: return@collectLatest
                if (currentPendingQuestionTarget == change.id) return@collectLatest
                showPendingQuestion(change, ask)
            }
        }

        // TOFU divergence alerts. Pop one dialog at a time — additional
        // alerts queue and surface as the user dismisses each. Don't fire if
        // a dialog is already showing.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.memberKeyAlerts.collectLatest { alerts ->
                val head = alerts.firstOrNull() ?: return@collectLatest
                if (currentMemberKeyAlertDialogTarget == "${head.circleId}:${head.userId}") return@collectLatest
                showMemberKeyChangedDialog(head)
            }
        }

        // A tapped circle question lands here: ask it again, and the two collectors
        // above show it.
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.circleQuestionRequests.collectLatest { request ->
                val req = request ?: return@collectLatest
                mainViewModel.consumeCircleQuestionRequest(req.token)
                viewModel.askAboutMembersOf(req.circleId)
            }
        }

        // A tapped "someone asked to take over your circle" notification lands here.
        // Opens that circle's own screen, which is where Refuse is.
        //
        // Combined with the circle list rather than read once: a cold-launched tap can
        // arrive before the first /circles fetch has landed, and a request that found no
        // circle would otherwise be dropped silently — on the one notification whose
        // entire purpose is to reach a control before a deadline.
        viewLifecycleOwner.lifecycleScope.launch {
            combine(mainViewModel.ownershipClaimRequests, viewModel.circles) { req, circles ->
                req to circles
            }.collectLatest { (req, circles) ->
                val request = req ?: return@collectLatest
                val circle = circles.firstOrNull { it.id == request.circleId }
                    ?: return@collectLatest
                mainViewModel.consumeOwnershipClaimRequest(request.token)
                showPrivacySettingsDialog(circle)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is CirclesUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is CirclesUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    is CirclesUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        // A subscription gate (402, e.g. the free circle limit)
                        // carries quota/error_code — show the premium prompt.
                        if (state.quota != null || state.errorCode != null) {
                            com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet.showForQuota(
                                parentFragmentManager, state.quota, state.errorCode,
                            )
                        } else {
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        }
                        viewModel.resetState()
                    }
                    is CirclesUiState.CircleCreated -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Circle created!", Toast.LENGTH_SHORT).show()
                        viewModel.refreshCircles()
                        viewModel.resetState()
                    }
                    is CirclesUiState.CircleJoined -> {
                        binding.progressBar.visibility = View.GONE
                        viewModel.refreshCircles()
                        viewModel.resetState()
                    }
                    is CirclesUiState.CircleLeft -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Left circle", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.CircleDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Circle deleted", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.PlacesShared -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            "${state.count} place${if (state.count > 1) "s" else ""} shared",
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.OwnershipTransferred -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Ownership transferred successfully", Toast.LENGTH_SHORT).show()
                        viewModel.refreshCircles()
                        viewModel.resetState()
                    }
                    is CirclesUiState.MemberRoleUpdated -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Member role updated", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.Kept -> {
                        // Waiting to sync: it goes out by itself, and shows on the circle's
                        // row.
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.line, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.InviteRevoked -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Invite revoked", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.MemberInvited -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Invite sent", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.InviteeNeedsConfirmation -> {
                        binding.progressBar.visibility = View.GONE
                        val change = state.change
                        // Their number to compare; the answer counts on this
                        // phone only.
                        val body = getString(R.string.invitee_changed_body, change.email) +
                            (change.number?.let { "\n\n" + getString(R.string.their_number, it) } ?: "")
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.invitee_changed_title, change.email))
                            .setMessage(body)
                            .setPositiveButton(R.string.member_reset_confirm) { _, _ -> viewModel.confirmInvitee(change) }
                            .setNegativeButton(R.string.member_reset_not_now, null)
                            .show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.MemberRemoved -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Member removed", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.MemberRemovalNeedsFallback -> {
                        binding.progressBar.visibility = View.GONE
                        showRemoveWithoutRotationDialog(state)
                        viewModel.resetState()
                    }
                    is CirclesUiState.MemberRemovedWithoutRotation -> {
                        binding.progressBar.visibility = View.GONE
                        // A toast is too small for this: it is the difference
                        // between "they are gone" and "they are gone from the
                        // circle but can still read what they took". Dialog,
                        // and the server's words.
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Removed")
                            .setMessage(
                                listOfNotNull(
                                    state.lead,
                                    "They're out of the circle now. Finishing the removal completes " +
                                        "as soon as a member's app is online.",
                                ).joinToString(" ")
                            )
                            .setPositiveButton("Got it", null)
                            .show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.InviteAccepted -> {
                        binding.progressBar.visibility = View.GONE
                        alertHandler.cancelInvite(state.circleId)
                        Toast.makeText(requireContext(), "Invite accepted!", Toast.LENGTH_SHORT).show()
                        viewModel.refreshCircles()
                        viewModel.resetState()
                        // No location notice here: it ran BEFORE
                        // the accept and is what allowed it (see
                        // gateInviteOnConsent). Being ADDED to a
                        // circle is the case the notice matters
                        // most for — the person whose location it
                        // is did not pick the recipients — and
                        // showing it again would ask a question
                        // already answered.
                    }
                    is CirclesUiState.InviteDeclined -> {
                        binding.progressBar.visibility = View.GONE
                        state.circleId?.let { alertHandler.cancelInvite(it) }
                        Toast.makeText(requireContext(), "Invite declined", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.OwnershipClaimOpened -> {
                        binding.progressBar.visibility = View.GONE
                        // A dialog, not a toast: the person has just started a clock that
                        // ends with somebody else's circle becoming theirs, and they
                        // should leave this screen knowing the owner was told and can stop
                        // it.
                        val deadline = OwnershipClaimFormat.deadline(state.claim)
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("The owner has been asked")
                            .setMessage(
                                buildString {
                                    append("They have been notified, and they can refuse at any time — ")
                                    append("which ends it for good.\n\n")
                                    if (deadline != null) {
                                        append("If they do nothing, the circle becomes yours on ")
                                        append(OwnershipClaimFormat.deadlineText(deadline))
                                        append(".")
                                    } else {
                                        // No invented duration. The window is the server's
                                        // to decide and it is stored per claim, so a
                                        // number this client guessed could be wrong.
                                        append("If they do nothing, the circle becomes yours when the notice period ends.")
                                    }
                                }
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.OwnershipClaimAlreadyExists -> {
                        binding.progressBar.visibility = View.GONE
                        // Not an error the user made — somebody else's request is already
                        // running. The banner behind this dialog has just been refreshed
                        // with it, so closing this shows them what exists.
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Already asked")
                            .setMessage(state.message)
                            .setPositiveButton("OK", null)
                            .show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.OwnershipClaimRefused -> {
                        binding.progressBar.visibility = View.GONE
                        // The one confirmation in this feature that must not be missable.
                        // An owner who taps Refuse and sees a toast vanish cannot tell
                        // whether it worked, and the cost of guessing wrong is the circle.
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Refused — the circle stays yours")
                            .setMessage(
                                "The request has been turned down for good. Nothing about the circle " +
                                    "has changed and everyone in it keeps the access they had.\n\n" +
                                    "An admin can ask again, and you will be told if they do."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        viewModel.resetState()
                    }
                    is CirclesUiState.OwnershipClaimWithdrawn -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Request withdrawn", Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    // ===== Location consent (consent = membership) =====

    /**
     * Show the location notice BEFORE accepting an invite, and accept only if it is
     * agreed to: "Join and share my location" joins, with the agreement in the same
     * request. "Not now" joins nothing, and the invitation stays pending.
     *
     * The circle name comes from the invite itself — a PENDING invite carries it in
     * plaintext precisely because the invitee holds no key to decrypt one — so the
     * notice can say which circle it is about before any join happens.
     *
     * Accepting first and undoing the join on a decline would look the same and is
     * not: the person would briefly BE a member, in the sender's roster and on their
     * map, and if the undo failed they would stay one.
     */
    private fun gateInviteOnConsent(invite: com.pingolin.track.data.remote.dto.ReceivedInviteResponse) {
        viewLifecycleOwner.lifecycleScope.launch {
            val label = invite.circleName ?: getString(R.string.location_notice_this_circle)
            // Null only means the text could not be fetched; the dialog renders
            // its bundled copy and still gates. It is never a reason to skip.
            val notice = viewModel.locationNotice()
            if (!isAdded) return@launch
            LocationConsentDialog.show(
                context = requireContext(),
                circleName = label,
                notice = notice,
                purpose = LocationConsentDialog.Purpose.JOIN,
                onGrant = { version ->
                    viewModel.acceptInviteAfterConsent(invite.id, invite.circleId, invite.circleName, version)
                    askForLocationAfterAgreeing()
                },
                onDecline = {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.location_notice_invite_not_joined, label),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    /**
     * "Join and share my location" or "Create and share my location" just agreed to share it:
     * where Track can't have it, ask now, or the circle is joined and nothing is shared, with
     * nothing to say so.
     */
    private fun askForLocationAfterAgreeing() {
        val access = com.pingolin.track.util.LocationAccess.of(context ?: return)
        if (access != com.pingolin.track.util.LocationAccess.ON) {
            (activity as? com.pingolin.track.ui.main.MainActivity)?.askForLocation()
        }
    }

    // ===== Received Invites =====

    private fun showReceivedInvitesDialog() {
        val dialogBinding = DialogReceivedInvitesBinding.inflate(layoutInflater)
        val adapter = ReceivedInvitesAdapter(
            onAcceptClick = { invite ->
                // THE NOTICE IS THE GATE. Nothing touches the circle until it is
                // answered: no accept, no membership, no row. Decline and it is
                // as if Accept was never pressed — the invite is untouched and
                // still pending.
                gateInviteOnConsent(invite)
            },
            onDeclineClick = { invite ->
                viewModel.declineInvite(invite.id, invite.circleId)
            }
        )

        dialogBinding.rvReceivedInvites.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvReceivedInvites.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.received_invites_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .create()

        // Paint what we already have, then keep it live for as long as the dialog
        // is up — a new invite can land while the user is looking at the list.
        val currentInvites = viewModel.receivedInvites.value
        if (currentInvites.isEmpty()) {
            dialogBinding.tvEmptyState.visibility = View.VISIBLE
            dialogBinding.rvReceivedInvites.visibility = View.GONE
        } else {
            dialogBinding.tvEmptyState.visibility = View.GONE
            dialogBinding.rvReceivedInvites.visibility = View.VISIBLE
            adapter.submitList(currentInvites)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.receivedInvites.collectLatest { invites ->
                if (invites.isEmpty()) {
                    dialogBinding.tvEmptyState.visibility = View.VISIBLE
                    dialogBinding.rvReceivedInvites.visibility = View.GONE
                } else {
                    dialogBinding.tvEmptyState.visibility = View.GONE
                    dialogBinding.rvReceivedInvites.visibility = View.VISIBLE
                    adapter.submitList(invites)
                }
            }
        }
        // "Joining as soon as {inviter} confirms it's you."
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.waitingToJoin.collectLatest { adapter.setWaitingToJoin(it) }
        }

        dialog.show()
    }

    private fun showCreateDialog() {
        CreateCircleSheet.show(requireContext()) { name, answered -> gateCreationOnConsent(name, answered) }
    }

    /**
     * Creating a circle is agreeing to share with it: the notice comes first, and
     * "Create and share my location" is what creates it. "Not now" creates nothing
     * and says nothing; the create sheet stays open with the name
     * (CreateCircleSheet).
     */
    private fun gateCreationOnConsent(name: String, answered: (agreed: Boolean) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val notice = viewModel.locationNotice()
            if (!isAdded) {
                answered(false)
                return@launch
            }
            LocationConsentDialog.show(
                context = requireContext(),
                circleName = name,
                notice = notice,
                purpose = LocationConsentDialog.Purpose.CREATE,
                onGrant = { version ->
                    answered(true)
                    viewModel.createCircle(name, version)
                    askForLocationAfterAgreeing()
                },
                onDecline = { answered(false) },
            )
        }
    }

    /**
     * [circle]'s changes waiting to sync, each with Cancel, and the lines dropped ones
     * left. Live while open: a change that goes out leaves the list.
     */
    private fun showPendingChanges(circle: CircleEntity) {
        val adapter = com.pingolin.track.ui.pending.PendingChangesAdapter { viewModel.pending.cancel(it) }
        val list = RecyclerView(requireContext()).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            this.adapter = adapter
            val side = (16 * resources.displayMetrics.density).toInt()
            setPadding(side, side / 2, side, 0)
        }
        val watch = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pending.items.collectLatest { items ->
                val mine = items.filter { it.circleId == circle.id }
                adapter.submitList(mine)
                viewModel.pending.seen(mine)
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(com.pingolin.track.domain.pending.PendingLines.TAG)
            .setView(list)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { watch.cancel() }
            .show()
    }

    private fun showLeaveConfirmation(circle: CircleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Leave Circle")
            .setMessage("Are you sure you want to leave ${circle.name}?")
            .setPositiveButton("Leave") { _, _ ->
                viewModel.leaveCircle(circle.id)
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    /**
     * Why this circle is locked on this phone. The cause decides the words: waiting
     * for access, a refused key, the user's own email reset (with the way back to
     * that data), or unknown — and an owner is never told to wait for an owner.
     */
    private fun showLockedCircleExplanation(circle: CircleEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val explanation = viewModel.lockedCircleExplanation(circle)
            val ctx = context ?: return@launch
            // Body set on the BUILDER: setMessage() after create() renders an
            // empty dialog.
            val name = circle.name.takeUnless { com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it) }
                ?: getString(R.string.locked_circle_this_circle)
            val builder = MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(explanation.title, name))
                .setMessage(getString(explanation.body, explanation.bodyName ?: name))
                .setPositiveButton(android.R.string.ok, null)
            if (explanation.offersRestore) {
                builder.setNeutralButton(R.string.locked_circle_restore) { _, _ ->
                    val url = com.pingolin.track.util.AccountRecovery.preResetRestoreUrl(
                        com.pingolin.track.BuildConfig.ACCOUNT_BASE_URL
                    )
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            builder.show()
        }
    }

    private fun showDeleteConfirmation(circle: CircleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Circle")
            .setMessage("Are you sure you want to delete ${circle.name}? This will remove all members and cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteCircle(circle.id)
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    private fun showPrivacySettingsDialog(circle: CircleEntity) {
        viewModel.selectCircle(circle)
        viewModel.loadPrivacySettings(circle.id)
        viewModel.loadOwnershipClaim(circle.id)
        viewModel.loadLocationConsent(circle.id)

        val dialogBinding = DialogCirclePrivacyBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            // Just the circle. The dialog's own headings say what each
            // section is, so repeating "Privacy" in the title would say it
            // twice and leave no room for the name on a narrow screen.
            .setTitle(circle.name)
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .create()

        // Every switch listener below is gated on this. Setting `isChecked`
        // programmatically fires the listener, which would PUT the value straight
        // back to the server as if the user had toggled it.
        var isPopulating = false

        // Your own location sharing here, and what the circle sees. Said in ONE place: the
        // consent row when it shows, else its own line, so nothing says "You are sharing your
        // location with this circle" whatever the real-time switch says. Both observers below
        // call it, so it follows the switch and the pause. Neither shows until this circle's
        // settings have loaded: while they are unknown, a pause included, it would claim
        // sharing.
        var consentGranted = false

        // The battery switch, usable only while "Allow alerts on me" is on.
        fun showBatteryRow(allowAlertsOnMe: Boolean) {
            val (usable, line) = batteryRow(allowAlertsOnMe)
            dialogBinding.switchShareBattery.isEnabled = usable
            dialogBinding.tvShareBatteryBody.setText(line)
        }

        fun renderOwnSharing() {
            val settings = viewModel.privacySettings.value?.takeIf { it.circleId == circle.id }
            dialogBinding.layoutLocationConsent.visibility = if (consentGranted && settings != null) View.VISIBLE else View.GONE
            if (settings == null) {
                dialogBinding.tvOwnSharingStatus.visibility = View.GONE
                return
            }
            val now = System.currentTimeMillis()
            val status = MemberSharing.own(settings.shareRealtime, settings.sharingMode, MemberSharing.epochMs(settings.pausedUntil), now)
            val sentence = MemberSharing.ownSentence(requireContext(), status, settings.shareHistory, now)
            val consentShown = consentGranted
            if (consentShown) {
                dialogBinding.tvConsentStatus.text = sentence ?: getString(R.string.consent_status_sharing)
            }
            dialogBinding.tvOwnSharingStatus.text = sentence
            dialogBinding.tvOwnSharingStatus.visibility = if (sentence != null && !consentShown) View.VISIBLE else View.GONE
        }

        // Location consent: consent = membership. A member shares, and the row says so.
        // Pausing is below; stopping for good is leaving, by the circle's own Leave. There
        // is no separate "Stop sharing my location": no "member, sharing nothing" state
        // exists to show, and a membership without a recorded yes is left alone, with
        // nothing to prompt for.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.locationConsent.collectLatest { consent ->
                consentGranted = consent != null && consent.circleId == circle.id &&
                    consent.status == "granted" && consent.mode == "presented"
                if (consentGranted) {
                    dialogBinding.tvConsentDetail.text = getString(R.string.consent_detail_sharing)
                }
                renderOwnSharing()
            }
        }

        // The controls show only once THIS circle's settings have loaded. Until then the form
        // isn't there to change, so nothing can be saved from defaults; a read that failed
        // says so, with Try again.
        fun renderPrivacyForm() {
            val form = privacyForm(circle.id, viewModel.privacySettings.value, viewModel.privacyLoadFailed.value)
            val ready = form == PrivacyForm.READY
            dialogBinding.layoutPrivacyControls.visibility = if (ready) View.VISIBLE else View.GONE
            dialogBinding.layoutPauseControls.visibility = if (ready) View.VISIBLE else View.GONE
            dialogBinding.layoutPrivacyLoadState.visibility = if (ready) View.GONE else View.VISIBLE
            dialogBinding.tvPrivacyLoadState.setText(
                if (form == PrivacyForm.FAILED) R.string.privacy_settings_load_failed else R.string.privacy_settings_loading
            )
            dialogBinding.btnPrivacyRetry.visibility = if (form == PrivacyForm.FAILED) View.VISIBLE else View.GONE
            renderOwnSharing()
        }
        // Both reads the dialog opened with: the consent row's too, which a read that failed with
        // the settings' would otherwise leave hidden until the dialog is opened again.
        dialogBinding.btnPrivacyRetry.setOnClickListener {
            viewModel.loadPrivacySettings(circle.id)
            viewModel.loadLocationConsent(circle.id)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(viewModel.privacySettings, viewModel.privacyLoadFailed) { _, _ -> }
                .collectLatest { renderPrivacyForm() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.privacySettings.collectLatest { settings ->
                if (settings != null && settings.circleId == circle.id) {
                    isPopulating = true

                    dialogBinding.switchShareRealtime.isChecked = settings.shareRealtime
                    dialogBinding.switchShareHistory.isChecked = settings.shareHistory
                    dialogBinding.switchShareBattery.isChecked = settings.shareBattery

                    // While paused: the mode Resume goes back to, read-only (MemberSharing.bubbleEditable).
                    val isBubble = MemberSharing.bubbleOn(settings.sharingMode, settings.modeBeforePause)
                    val bubbleEditable = MemberSharing.bubbleEditable(settings.sharingMode)
                    dialogBinding.switchBubbleMode.isChecked = isBubble
                    dialogBinding.switchBubbleMode.isEnabled = bubbleEditable
                    dialogBinding.sliderBubbleRadius.isEnabled = bubbleEditable
                    dialogBinding.layoutBubbleRadius.visibility = if (isBubble) View.VISIBLE else View.GONE
                    if (isBubble) {
                        val radiusMiles = (settings.approximateRadiusMiles ?: 1).toFloat()
                        dialogBinding.sliderBubbleRadius.value = radiusMiles.coerceIn(1f, 25f)
                        dialogBinding.tvBubbleRadiusValue.text = com.pingolin.track.ui.places.formatMileRadius(settings.approximateRadiusMiles ?: 1, sessionManager.useMiles(), com.pingolin.track.util.uiLocale(requireContext()))
                        // Nothing is shared during a pause, so not "Sharing …" then.
                        dialogBinding.tvBubbleStatus.text = if (bubbleEditable) "Sharing approximate location" else "Share approximate location"
                    } else {
                        dialogBinding.tvBubbleStatus.text = "Share approximate location"
                    }

                    dialogBinding.switchShowAllDevices.isChecked = settings.showAllDevices
                    dialogBinding.switchAllowAlertsOnMe.isChecked = settings.allowAlertsOnMe
                    showBatteryRow(settings.allowAlertsOnMe)

                    // Gate on the MODE, not on the deadline. `pausedUntil
                    // == null` is a pause with no end ("until I resume"),
                    // so a deadline-only test would tell a user who had
                    // stopped sharing indefinitely that they were not
                    // paused, and offer them the Pause button again.
                    if (settings.sharingMode == "paused") {
                        dialogBinding.layoutPauseStatus.visibility = View.VISIBLE
                        dialogBinding.btnPauseSharing.visibility = View.GONE
                        // Said once: the sentence at the top (renderOwnSharing)
                        // is the pause and its end; the line beside Resume only
                        // when real-time sharing is off as well.
                        // MemberSharing.epochMs, not SimpleDateFormat: the
                        // server sends RFC3339 with a zone, and a pattern with
                        // no zone field lets a lenient parse read the UTC clock
                        // time as LOCAL and show the wrong resume time.
                        val line = MemberSharing.pauseLine(
                            requireContext(), settings.shareRealtime,
                            MemberSharing.epochMs(settings.pausedUntil), System.currentTimeMillis()
                        )
                        dialogBinding.tvPauseStatus.text = line
                        dialogBinding.tvPauseStatus.visibility = if (line != null) View.VISIBLE else View.GONE
                    } else {
                        dialogBinding.layoutPauseStatus.visibility = View.GONE
                        dialogBinding.btnPauseSharing.visibility = View.VISIBLE
                    }

                    isPopulating = false
                    renderOwnSharing()
                }
            }
        }

        dialogBinding.switchShareRealtime.setOnCheckedChangeListener { _, isChecked ->
            if (!isPopulating) viewModel.updatePrivacySettings(circle.id, shareRealtime = isChecked)
        }

        dialogBinding.switchShareHistory.setOnCheckedChangeListener { _, isChecked ->
            if (!isPopulating) viewModel.updatePrivacySettings(circle.id, shareHistory = isChecked)
        }

        dialogBinding.switchShareBattery.setOnCheckedChangeListener { _, isChecked ->
            if (!isPopulating) viewModel.updatePrivacySettings(circle.id, shareBattery = isChecked)
        }

        dialogBinding.switchBubbleMode.setOnCheckedChangeListener { _, isChecked ->
            if (isPopulating) return@setOnCheckedChangeListener
            if (isChecked) {
                dialogBinding.layoutBubbleRadius.visibility = View.VISIBLE
                val radiusMiles = dialogBinding.sliderBubbleRadius.value.toInt().coerceIn(1, 25)
                viewModel.enableBubble(circle.id, radiusMiles)
            } else {
                dialogBinding.layoutBubbleRadius.visibility = View.GONE
                viewModel.disableBubble(circle.id)
            }
        }

        dialogBinding.sliderBubbleRadius.addOnChangeListener { _, value, fromUser ->
            val radiusMiles = value.toInt()
            dialogBinding.tvBubbleRadiusValue.text = com.pingolin.track.ui.places.formatMileRadius(radiusMiles, sessionManager.useMiles(), com.pingolin.track.util.uiLocale(requireContext()))
            if (fromUser && dialogBinding.switchBubbleMode.isChecked) {
                viewModel.enableBubble(circle.id, radiusMiles.coerceIn(1, 25))
            }
        }

        dialogBinding.switchShowAllDevices.setOnCheckedChangeListener { _, isChecked ->
            if (!isPopulating) viewModel.updateShowAllDevices(circle.id, isChecked)
        }

        // Allow-alerts-on-me toggle (consent for person-targeted place alerts).
        // Off => members cannot create new arrival/departure alerts on you here, and
        // existing ones stop firing.
        dialogBinding.switchAllowAlertsOnMe.setOnCheckedChangeListener { _, isChecked ->
            showBatteryRow(isChecked)
            if (!isPopulating) viewModel.updatePrivacySettings(circle.id, allowAlertsOnMe = isChecked)
        }

        val sessionManager = viewModel.getSessionManager()
        dialogBinding.switchDefaultCircle.isChecked = sessionManager.getDefaultCircleId() == circle.id
        dialogBinding.switchDefaultCircle.setOnCheckedChangeListener { _, isChecked ->
            if (!isPopulating) {
                if (isChecked) {
                    sessionManager.setDefaultCircleId(circle.id)
                    val mainViewModel = androidx.lifecycle.ViewModelProvider(requireActivity())[com.pingolin.track.ui.main.MainViewModel::class.java]
                    mainViewModel.selectCircle(circle.id)
                } else {
                    sessionManager.setDefaultCircleId(null)
                }
            }
        }

        dialogBinding.btnPauseSharing.setOnClickListener {
            showPauseDurationDialog(circle)
        }

        dialogBinding.btnResume.setOnClickListener {
            viewModel.resumeSharing(circle.id)
        }

        val currentUserId = viewModel.getCurrentUserId() ?: ""
        val membersAdapter = CircleMembersAdapter(
            currentUserId = currentUserId,
            onChangeRole = { member ->
                dialog.dismiss()
                showMemberManagementDialog(circle, member)
            },
            onRemove = { member ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove Member")
                    .setMessage("Remove ${member.displayName} from ${circle.name}? They will lose access to this circle's data.")
                    .setPositiveButton("Remove") { _, _ ->
                        viewModel.removeMember(circle.id, member.userId)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .showDestructive()
            },
            onSecurityDetails = { member ->
                // Safety number / verify toggle / raw key material.
                dialog.dismiss()
                val args = Bundle().apply {
                    putString(MemberDetailFragment.ARG_CIRCLE_ID, circle.id)
                    putString(MemberDetailFragment.ARG_USER_ID, member.userId)
                }
                findNavController().navigate(R.id.memberDetailFragment, args)
            },
            onMemberTap = { member ->
                // Show the member on the map. The camera work and the callout
                // are MapFragment's to do — it owns the map's state and is the
                // only place the member's position exists in the clear — so
                // this just raises the request and switches tabs.
                dialog.dismiss()
                mainViewModel.requestMemberFocus(circle.id, member.userId)
                findNavController().navigate(R.id.mapFragment)
            }
        )
        dialogBinding.rvMembers.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvMembers.adapter = membersAdapter

        // What this account may do here: admins invite, manage invitations and remove; roles,
        // hand-over and delete stay the owner's. Looked again as the member list (where an
        // admin's role is) and the records read here change.
        fun showRights() {
            val rights = viewModel.rightsIn(circle, viewModel.selectedCircleMembers.value)
            membersAdapter.setRights(rights)
            dialogBinding.btnManageInvites.visibility = if (rights.manageInvites) View.VISIBLE else View.GONE
        }
        showRights()
        val rightsWatch = viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.selectedCircleMembers, viewModel.recordsNoted) { _, _ -> }.collectLatest { showRights() }
        }
        dialogBinding.btnManageInvites.setOnClickListener {
            dialog.dismiss()
            showPendingInvitesDialog(circle)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCircleMembers.collectLatest { members ->
                membersAdapter.submitList(members.sortedWith(
                    compareBy<CircleMemberEntity> { it.role != "owner" }
                        .thenBy { it.role != "admin" }
                        .thenBy { it.displayName }
                ))
            }
        }
        // "Joining…", and on owner/admin devices the people Pingolin kept out.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.memberStates.collectLatest { states ->
                val state = states[circle.id]
                membersAdapter.setJoining(state?.joining.orEmpty())
                membersAdapter.setAskedToUpdate(state?.askedToUpdate.orEmpty())
                val keptOut = state?.keptOut ?: 0
                val circleName = circle.name.takeUnless { com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it) }
                    ?: getString(R.string.locked_circle_this_circle)
                dialogBinding.tvMembersKeptOut.visibility = if (keptOut > 0) View.VISIBLE else View.GONE
                dialogBinding.tvMembersKeptOut.text =
                    resources.getQuantityString(R.plurals.members_kept_out, keptOut, keptOut, circleName)
            }
        }

        if (circle.isOwner) {
            // Transfer Ownership - only for owners
            dialogBinding.btnTransferOwnership.visibility = View.VISIBLE
            dialogBinding.btnTransferOwnership.setOnClickListener {
                // Ask BEFORE closing. Dismissing first would answer the
                // tap, in a circle with no one else in it, by closing the
                // settings dialog and toasting behind it — from the
                // user's side, the button shuts the screen. Nothing moves
                // unless there is somebody to transfer to.
                if (viewModel.hasTransferCandidates()) {
                    dialog.dismiss()
                    showTransferOwnershipDialog(circle)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No one else is in this circle yet — invite someone first",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        } else {
            dialogBinding.btnTransferOwnership.visibility = View.GONE
        }

        // ===== Ownership claim =====
        //
        // Built from TWO flows, because the copy needs both halves: the claim says who
        // asked and when it settles, the roster says what this particular reader is able
        // to do about it. Either can arrive first, so both collectors call the same
        // render instead of each trying to be the one that has everything.
        fun renderOwnershipClaim() {
            val state = viewModel.ownershipClaim.value
            val members = viewModel.selectedCircleMembers.value
            val myId = viewModel.getCurrentUserId()
            val myRole = viewModel.currentUserRoleIn(members)

            // Not looked yet, or the answer belongs to a circle whose dialog this is not.
            // Stay silent rather than guess: telling an owner their circle is fine because
            // a request has not come back yet is the exact failure this feature exists to
            // prevent.
            if (state == null || state.circleId != circle.id) {
                dialogBinding.cardOwnershipClaim.visibility = View.GONE
                dialogBinding.btnClaimOwnership.visibility = View.GONE
                return
            }

            val claim = state.claim
            if (claim == null) {
                dialogBinding.cardOwnershipClaim.visibility = View.GONE
                // Checked, and nothing is live — so an admin who does not already own the
                // circle is the one person who can start a claim.
                dialogBinding.btnClaimOwnership.visibility =
                    if (!circle.isOwner && myRole == "admin") View.VISIBLE else View.GONE
                return
            }

            dialogBinding.btnClaimOwnership.visibility = View.GONE
            dialogBinding.cardOwnershipClaim.visibility = View.VISIBLE

            // The server sends an id, not a name. Resolving it through the roster is what
            // turns "an admin" into a person the owner recognises — and the fallback has
            // to stay truthful when the roster has not loaded or they have since left.
            val claimantName = members
                .firstOrNull { it.userId == claim.claimantUserId }
                ?.displayName
                ?.takeIf { it.isNotBlank() }
                ?: "An admin"
            val isClaimant = claim.claimantUserId != null && claim.claimantUserId == myId

            dialogBinding.tvOwnershipClaimTitle.text = when {
                isClaimant -> "You asked to take over this circle"
                circle.isOwner -> "Someone has asked to take over this circle"
                else -> "This circle may be changing hands"
            }

            dialogBinding.tvOwnershipClaimBody.text = when {
                isClaimant ->
                    "The owner has been told, and can refuse at any time — which ends it for good."
                // What actually happens to them is decided by the server: the outgoing
                // owner is set to role 'admin' with can_rotate_keys, NOT demoted to plain
                // member. Saying "you would become an ordinary member" would be a lie on
                // the one screen where they are deciding whether to act.
                circle.isOwner ->
                    "$claimantName has asked to become the owner of this circle. If you do " +
                        "nothing, they become the owner and you become an admin — you would " +
                        "keep the ability to invite and remove members, but not to delete the " +
                        "circle or hand it on."
                else ->
                    "$claimantName has asked to become the owner of this circle. Only the " +
                        "current owner can refuse."
            }

            val deadline = OwnershipClaimFormat.deadline(claim)
            dialogBinding.tvOwnershipClaimDeadline.text = if (deadline != null) {
                "Transfers on ${OwnershipClaimFormat.deadlineText(deadline)} · " +
                    OwnershipClaimFormat.daysRemainingText(deadline, Instant.now())
            } else {
                // Say the date is missing rather than invent one. The refuse button below
                // is unaffected, which is the half that has to survive.
                "The date this transfers could not be read."
            }

            dialogBinding.btnRefuseOwnershipClaim.visibility =
                if (circle.isOwner) View.VISIBLE else View.GONE
            dialogBinding.btnWithdrawOwnershipClaim.visibility =
                if (isClaimant) View.VISIBLE else View.GONE
        }

        dialogBinding.btnRefuseOwnershipClaim.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Refuse this request?")
                .setMessage(
                    "The circle stays yours and nothing about it changes. This cannot be " +
                        "undone — but an admin can ask again, and you will be told if they do."
                )
                .setPositiveButton("Refuse") { _, _ -> viewModel.refuseOwnershipClaim(circle.id) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialogBinding.btnWithdrawOwnershipClaim.setOnClickListener {
            viewModel.withdrawOwnershipClaim(circle.id)
        }

        dialogBinding.btnClaimOwnership.setOnClickListener {
            dialog.dismiss()
            showClaimOwnershipConfirmation(circle)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.ownershipClaim.collectLatest { renderOwnershipClaim() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCircleMembers.collectLatest { renderOwnershipClaim() }
        }

        // The claim belongs to the circle whose dialog this was. Leaving it in the
        // ViewModel would let it render for a moment on the next circle opened.
        dialog.setOnDismissListener {
            viewModel.clearOwnershipClaim()
            rightsWatch.cancel()
        }
        dialog.show()
    }

    /**
     * Confirmation before an admin asks to take over a circle.
     *
     * Worth a dialog rather than a bare tap because of what it starts: the owner is
     * contacted immediately, and if they never respond the circle changes hands. Every
     * sentence here is a fact the server actually implements — the notification and the
     * email both go out when the claim is made, refusing is one action and permanent, and
     * the claim is readable by every active member, so "everyone can see" is true.
     *
     * No duration is quoted. The window is the server's, stored per claim so that
     * changing the default cannot move a countdown somebody is already watching, and a
     * number this client made up could be wrong.
     */
    private fun showClaimOwnershipConfirmation(circle: CircleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Ask to take over this circle?")
            .setMessage(
                "The owner of \"${circle.name}\" is told straight away — by notification and " +
                    "by email — and can refuse with one tap, which ends it for good.\n\n" +
                    "Only if they never respond does the circle become yours, once the notice " +
                    "period ends. Everyone in the circle can see that you asked."
            )
            .setPositiveButton("Ask") { _, _ -> viewModel.claimOwnership(circle.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTransferOwnershipDialog(circle: CircleEntity) {
        val members = viewModel.selectedCircleMembers.value
        val currentUserId = viewModel.getCurrentUserId()

        // Filter out the current owner - can only transfer to other members
        val eligibleMembers = members.filter { it.userId != currentUserId }

        if (eligibleMembers.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No other members to transfer ownership to",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val memberNames = eligibleMembers.map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Transfer Ownership")
            .setItems(memberNames) { _, which ->
                val selectedMember = eligibleMembers[which]
                showTransferOwnershipConfirmation(circle, selectedMember.userId, selectedMember.displayName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTransferOwnershipConfirmation(circle: CircleEntity, newOwnerId: String, newOwnerName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Transfer")
            .setMessage("Are you sure you want to transfer ownership of \"${circle.name}\" to $newOwnerName?\n\nThis action cannot be undone. You will become a regular member of the circle.")
            .setPositiveButton("Transfer") { _, _ ->
                viewModel.transferOwnership(circle.id, newOwnerId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShareAllPlacesDialog(sourceCircle: CircleEntity) {
        val otherCircles = viewModel.getOtherCircles(sourceCircle.id)

        if (otherCircles.isEmpty()) {
            Toast.makeText(requireContext(), "No other circles available to share with", Toast.LENGTH_SHORT).show()
            return
        }

        val circleNames = otherCircles.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Share All Places from \"${sourceCircle.name}\"")
            .setItems(circleNames) { _, which ->
                val targetCircle = otherCircles[which]
                showShareAllPlacesConfirmation(sourceCircle, targetCircle)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShareAllPlacesConfirmation(sourceCircle: CircleEntity, targetCircle: CircleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Share")
            .setMessage("Share all places from \"${sourceCircle.name}\" to \"${targetCircle.name}\"?\n\nPlaces will be copied to the target circle.")
            .setPositiveButton("Share") { _, _ ->
                viewModel.shareAllPlacesToCircle(sourceCircle.id, targetCircle.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===== Member Role Management =====

    /**
     * Shown only when eject-and-rotate has actually been refused.
     *
     * The strong removal re-keys the circle, and it needs a fresh key for every
     * remaining member — a survivor whose key changed without being verified,
     * or whose identity key cannot be resolved, makes that impossible. Without
     * this fallback the member could not be removed at all.
     *
     * The weaker removal is deliberately behind this failure rather than beside
     * the normal button. Somebody who can re-key should always re-key.
     */
    private fun showRemoveWithoutRotationDialog(
        state: CirclesUiState.MemberRemovalNeedsFallback,
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Could not remove them")
            .setMessage(
                "${state.reason}\n\n" +
                    "You can still remove them now. They're out of the circle straight away, " +
                    "and the removal finishes as soon as a member's app is online. What they " +
                    "already saw stays with them."
            )
            .setPositiveButton("Remove now") { _, _ ->
                viewModel.revokeMemberAccess(state.circleId, state.userId)
            }
            .setNegativeButton("Cancel", null)
            .showDestructive()
    }

    private fun showMemberManagementDialog(circle: CircleEntity, member: CircleMemberEntity) {
        // Cannot change owner's role
        if (member.role == "owner") {
            Toast.makeText(
                requireContext(),
                "Cannot change owner's role. Use Transfer Ownership instead.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dialogBinding = DialogManageMembersBinding.inflate(layoutInflater)
        dialogBinding.tvMemberName.text = member.displayName
        dialogBinding.tvCurrentRole.text = "Current role: ${member.role.replaceFirstChar { it.uppercase() }}"

        when (member.role) {
            "admin" -> dialogBinding.rbAdmin.isChecked = true
            else -> dialogBinding.rbMember.isChecked = true
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Change Role")
            .setView(dialogBinding.root)
            .setPositiveButton("Update") { _, _ ->
                val newRole = when {
                    dialogBinding.rbAdmin.isChecked -> "admin"
                    else -> "member"
                }
                if (newRole != member.role) {
                    // An email invitee made admin is trusted by every
                    // member's app from then on: compare their number first.
                    // The keys it was worked out from are the only ones
                    // signed.
                    val question = if (newRole == "admin") viewModel.promotionQuestion(circle.id, member.userId) else null
                    if (question == null) {
                        viewModel.updateMemberRole(circle.id, member.userId, newRole)
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.promote_email_invitee_title, member.displayName))
                            .setMessage(getString(R.string.promote_email_invitee_body, member.displayName, question.number ?: "—"))
                            .setPositiveButton(R.string.promote_email_invitee_confirm) { _, _ ->
                                viewModel.updateMemberRole(circle.id, member.userId, newRole, question.keys)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===== Pending Invites Management =====

    private fun showPendingInvitesDialog(circle: CircleEntity) {
        viewModel.refreshPendingInvites(circle.id)

        val dialogBinding = DialogPendingInvitesBinding.inflate(layoutInflater)
        val adapter = PendingInvitesAdapter { invite ->
            showRevokeConfirmation(circle.id, invite)
        }

        dialogBinding.rvInvites.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvInvites.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pending Invites")
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .create()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingInvites.collectLatest { invites ->
                if (invites.isEmpty()) {
                    dialogBinding.tvEmptyState.visibility = View.VISIBLE
                    dialogBinding.rvInvites.visibility = View.GONE
                } else {
                    dialogBinding.tvEmptyState.visibility = View.GONE
                    dialogBinding.rvInvites.visibility = View.VISIBLE
                    adapter.submitList(invites)
                }
            }
        }

        dialog.show()
    }

    private fun showRevokeConfirmation(circleId: String, invite: PendingInviteEntity) {
        val emailInfo = if (!invite.email.isNullOrBlank()) {
            "\n\nThis invite was sent to: ${invite.email}"
        } else {
            ""
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Revoke Invite")
            .setMessage("Are you sure you want to revoke this invite? The recipient will no longer be able to join.$emailInfo")
            .setPositiveButton("Revoke") { _, _ ->
                viewModel.revokeInvite(circleId, invite.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===== Invite Dialog =====

    private fun showInviteDialog(circle: CircleEntity) {
        viewModel.selectCircle(circle)

        // Reuses the join-circle layout, so the hint and helper text have to be
        // rewritten here to talk about inviting rather than joining.
        val dialogBinding = DialogJoinCircleBinding.inflate(layoutInflater)
        (dialogBinding.etInviteCode.parent?.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = "Recipient email"
        dialogBinding.etInviteCode.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val helperText = (dialogBinding.root as? android.view.ViewGroup)?.getChildAt(1)
        if (helperText is android.widget.TextView) {
            helperText.text = "Enter the email address of the person you want to invite."
        }
        // Say what adding somebody actually hands them, before it is handed
        // over. Invite only — joining a circle discloses nothing about
        // anyone else, so the same layout keeps this hidden on that path.
        dialogBinding.tvHistoryWarning.visibility = View.VISIBLE

        val inviteField = dialogBinding.etInviteCode.parent?.parent
            as? com.google.android.material.textfield.TextInputLayout

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Invite to ${circle.name}")
            .setView(dialogBinding.root)
            // Listener set below, after show(), so an empty address does not
            // dismiss. A positive button dismisses the dialog before its
            // listener runs, which would toast "Please enter a valid email"
            // over a closed dialog and throw away whatever else the user had
            // typed.
            .setPositiveButton("Send Invite", null)
            .setNegativeButton("Cancel", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val email = dialogBinding.etInviteCode.text?.toString()?.trim() ?: ""
            // Same rules, and the same wording, as the sign-in form: one place
            // decides what a usable address looks like.
            val errors = AuthFormValidation.forLogin(email, passwordLength = 1)
            val emailError = errors.firstOrNull { it.field == AuthField.EMAIL }
            if (emailError != null) {
                inviteField?.error = emailError.message
                dialogBinding.etInviteCode.requestFocus()
                return@setOnClickListener
            }
            inviteField?.error = null
            viewModel.createInviteAndRefresh(circle.id, email)
            dialog.dismiss()
        }
    }

    // ===== Pause Duration Selection =====

    private fun showPauseDurationDialog(circle: CircleEntity) {
        val options = arrayOf(
            "15 minutes",
            "30 minutes",
            "1 hour",
            "2 hours",
            "4 hours",
            "8 hours",
            "Until tomorrow"
        )
        val durations = arrayOf(15, 30, 60, 120, 240, 480, 1440)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pause Duration")
            .setItems(options) { _, which ->
                viewModel.pauseSharing(circle.id, durations[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * "Is this really {email}?" about an invitation this account made that no longer names
     * its account as it is now: their number to compare. "It's them" renews it for the
     * details shown; "No" withdraws it for good; "Not now" leaves the question for later.
     */
    private fun showInviteRenewalDialog(question: com.pingolin.track.domain.repository.InviteRenewalQuestion) {
        currentRenewalDialogTarget = question.statementId
        val body = getString(R.string.invitee_changed_body, question.email) +
            (question.number?.let { "\n\n" + getString(R.string.their_number, it) } ?: "") +
            "\n\n" + getString(R.string.invite_renewal_explainer, question.circleName)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.invitee_changed_title, question.email))
            .setMessage(body)
            .setPositiveButton(R.string.member_reset_confirm) { _, _ -> viewModel.renewInvitation(question) }
            .setNegativeButton(R.string.invite_renewal_withdraw) { _, _ -> viewModel.declineRenewal(question) }
            .setNeutralButton(R.string.member_reset_not_now, null)
            .setOnDismissListener { currentRenewalDialogTarget = null }
            .show()
    }

    /**
     * Surface a [MaterialAlertDialogBuilder]-style modal when the SDK
     * detects a TOFU pin divergence on a member's keys. The user can either
     * Approve (and continue sharing under the new keys) or Block (which
     * leaves the OLD pin canonical so subsequent fetches keep raising the
     * alert until manually cleared). One dialog at a time — additional
     * alerts queue.
     */
    private fun showMemberKeyChangedDialog(alert: com.pingolin.track.domain.repository.MemberKeyAlert) {
        currentMemberKeyAlertDialogTarget = "${alert.circleId}:${alert.userId}"

        // A member whose app is too old to confirm (no signing key) never reaches this
        // question: the server tells them to update, and the member list says they
        // have been asked (CircleRepository.requestMemberUpdate).
        val circleName = viewModel.circles.value.firstOrNull { it.id == alert.circleId }?.name
            ?.takeUnless { com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it) }
            ?: getString(R.string.locked_circle_this_circle)

        // A routine question, in plain words, with their number to compare. The
        // answer counts on this phone only.
        val body = getString(R.string.member_reset_body, alert.displayName, circleName) +
            (alert.number?.let { "\n\n" + getString(R.string.member_reset_check, alert.displayName, it) } ?: "")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.member_reset_title, alert.displayName))
            .setMessage(body)
            .setPositiveButton(R.string.member_reset_confirm) { _, _ ->
                viewModel.approveMemberKeyChange(alert)
                currentMemberKeyAlertDialogTarget = null
            }
            .setNegativeButton(R.string.member_reset_not_now) { _, _ ->
                viewModel.blockMemberKeyChange(alert)
                currentMemberKeyAlertDialogTarget = null
            }
            .setOnDismissListener {
                currentMemberKeyAlertDialogTarget = null
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Owner/admin devices only: a member invited by someone this phone cannot confirm.
     * Letting them in hands them everything the circle has shared, history included, so it
     * is asked. Nobody is asked about a member invited from an app older than signed
     * invitations.
     */
    private fun showKeyAccessDialog(request: com.pingolin.track.domain.repository.KeyAccessRequest) {
        currentKeyAccessDialogTarget = "${request.circleId}:${request.userId}"
        val circleName = request.circleName.takeUnless { com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it) }
            ?: getString(R.string.locked_circle_this_circle)
        // The late joiner: the approved lines only, by case; the inviter named when they
        // aren't an owner or admin in the chain.
        val body = if (request.reason == com.pingolin.track.domain.repository.KeyAccessRequest.Reason.LATE_JOINER) {
            com.pingolin.track.domain.repository.LateJoiners.lines(
                request.lateCase, request.inviterName, getString(R.string.late_joiner_invitation_ran_out),
            ) { getString(R.string.late_joiner_invited_by, it) }.joinToString("\n\n").ifEmpty { null }
        } else if (!request.email.isNullOrBlank()) {
        // Invited by someone this phone cannot confirm: their number, to check.
            getString(R.string.unconfirmed_invite_body_with_email, request.displayName, request.email, request.number ?: "—")
        } else {
            getString(R.string.unconfirmed_invite_body, request.displayName, request.number ?: "—")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.let_in_title, request.displayName, circleName))
            .setMessage(body)
            .setPositiveButton(R.string.let_in_confirm) { _, _ ->
                viewModel.grantKeyAccess(request)
                currentKeyAccessDialogTarget = null
            }
            .setNegativeButton(R.string.let_in_not_now) { _, _ ->
                viewModel.deferKeyAccess(request)
                currentKeyAccessDialogTarget = null
            }
            .setOnDismissListener { currentKeyAccessDialogTarget = null }
            .setCancelable(false)
            .show()
    }

    /**
     * A change waiting to sync asks again about someone whose details aren't the ones the
     * person saw, with the live question's words and their number. The answer continues it with
     * exactly these details, or ends it with no line; a renewal's "Withdraw" withdraws the
     * invitation, as live.
     */
    private fun showPendingQuestion(
        change: com.pingolin.track.data.local.entity.PendingChangeEntity,
        ask: com.pingolin.track.domain.pending.PendingQuestions.Ask,
    ) {
        val inputs = com.pingolin.track.domain.pending.PendingInputs.read(change) ?: return
        val questions = com.pingolin.track.domain.pending.PendingQuestions
        val circleName = inputs.circleName.takeUnless { com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it) }
            ?: getString(R.string.locked_circle_this_circle)
        val builder = MaterialAlertDialogBuilder(requireContext())
        when (ask.ask) {
            questions.INVITEE, questions.RENEWAL -> {
                val email = (inputs as? com.pingolin.track.domain.pending.PendingInputs.Invite)?.email
                    ?: (inputs as? com.pingolin.track.domain.pending.PendingInputs.RenewInvitation)?.email ?: return
                val body = getString(R.string.invitee_changed_body, email) +
                    (ask.number?.let { "\n\n" + getString(R.string.their_number, it) } ?: "") +
                    (if (ask.ask == questions.RENEWAL) "\n\n" + getString(R.string.invite_renewal_explainer, circleName) else "")
                builder.setTitle(getString(R.string.invitee_changed_title, email)).setMessage(body)
                    .setPositiveButton(R.string.member_reset_confirm) { _, _ -> viewModel.answerPending(change.id, accept = true) }
                if (ask.ask == questions.RENEWAL) {
                    builder.setNegativeButton(R.string.invite_renewal_withdraw) { _, _ ->
                        viewModel.answerPending(change.id, accept = false, withdrawInvitation = true)
                    }
                } else {
                    builder.setNegativeButton(R.string.member_reset_not_now) { _, _ -> viewModel.answerPending(change.id, accept = false) }
                }
            }
            questions.MEMBER -> {
                val name = (inputs as? com.pingolin.track.domain.pending.PendingInputs.MakeAdmin)?.memberName ?: return
                val body = getString(R.string.member_reset_body, name, circleName) +
                    (ask.number?.let { "\n\n" + getString(R.string.member_reset_check, name, it) } ?: "")
                builder.setTitle(getString(R.string.member_reset_title, name)).setMessage(body)
                    .setPositiveButton(R.string.member_reset_confirm) { _, _ -> viewModel.answerPending(change.id, accept = true) }
                    .setNegativeButton(R.string.member_reset_not_now) { _, _ -> viewModel.answerPending(change.id, accept = false) }
            }
            questions.LET_IN -> {
                val name = (inputs as? com.pingolin.track.domain.pending.PendingInputs.LetIn)?.memberName ?: return
                builder.setTitle(getString(R.string.let_in_title, name, circleName))
                    .setMessage(getString(R.string.unconfirmed_invite_body, name, ask.number ?: "—"))
                    .setPositiveButton(R.string.let_in_confirm) { _, _ -> viewModel.answerPending(change.id, accept = true) }
                    .setNegativeButton(R.string.let_in_not_now) { _, _ -> viewModel.answerPending(change.id, accept = false) }
            }
            else -> return
        }
        currentPendingQuestionTarget = change.id
        builder.setOnDismissListener { currentPendingQuestionTarget = null }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** How often the Circles tab re-checks received invites while it is on screen. */
private const val INVITE_POLL_INTERVAL_MS = 60_000L
