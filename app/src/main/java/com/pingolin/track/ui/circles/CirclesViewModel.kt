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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.PendingInviteEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.OwnershipClaimExistsException
import com.pingolin.track.data.remote.dto.OwnershipClaimResponse
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import com.pingolin.track.data.remote.dto.premiumErrorCode
import com.pingolin.track.data.remote.dto.quotaError
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.data.remote.dto.PauseSharingRequest
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.data.remote.dto.UpdatePrivacyRequest
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.CirclesPaginationState
import com.pingolin.track.domain.repository.MemberKeyAlert
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainText
import com.pingolin.track.util.plainTextOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CirclesViewModel @Inject constructor(
    private val circleRepository: CircleRepository,
    private val placeRepository: PlaceRepository,
    private val tracksApi: TrackApi,
    private val sessionManager: SessionManager,
    private val locationConsentRepository: com.pingolin.track.domain.repository.LocationConsentRepository,
    private val pendingChanges: com.pingolin.track.domain.pending.PendingChanges,
) : ViewModel() {

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every change waiting to sync on this phone, and the lines dropped ones left. */
    val pending = com.pingolin.track.ui.pending.PendingListModel(
        pendingChanges, viewModelScope, circleRepository.observeCircles(), kinds = null,
    )

    /**
     * Circle id -> the line its row shows: how many changes are waiting to go out from this
     * phone, else the latest line one of them left when it was dropped.
     */
    val pendingLines: StateFlow<Map<String, String>> = pending.items.map { items ->
        items.groupBy { it.circleId }.mapValues { (_, list) ->
            val waiting = list.count { it is com.pingolin.track.ui.pending.PendingItem.Waiting }
            if (waiting > 0) com.pingolin.track.domain.pending.PendingLines.count(waiting)
            else (list.last() as com.pingolin.track.ui.pending.PendingItem.Notice).line
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * What this account may do in [circle]: its role there being the owner's ([CircleEntity.isOwner]) or the
     * one [members] list for it. See [com.pingolin.track.domain.repository.CircleRights].
     */
    fun rightsIn(circle: CircleEntity, members: List<CircleMemberEntity>): com.pingolin.track.domain.repository.CircleRights.Rights =
        circleRepository.rightsIn(circle.id, if (circle.isOwner) com.pingolin.track.domain.repository.CircleRights.OWNER else currentUserRoleIn(members))

    /**
     * The circles whose card offers Invite: an owner's or admin's, where the invitation takes
     * effect. Looked again whenever the circles, this account's member rows or the records read here
     * change. Made on first use, as the Circles screen's.
     */
    val inviteOffered: StateFlow<Set<String>> by lazy {
        combine(
            circleRepository.observeCircles(),
            circleRepository.observeMyMemberships(),
            circleRepository.recordsNoted,
        ) { circles, mine, _ ->
            circles.filter { circle ->
                val role = if (circle.isOwner) com.pingolin.track.domain.repository.CircleRights.OWNER else mine.firstOrNull { it.circleId == circle.id }?.role
                circleRepository.rightsIn(circle.id, role).invite
            }.map { it.id }.toSet()
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    }

    /** Moves on after every read of the circles' records: what [rightsIn] says may have changed. */
    val recordsNoted: StateFlow<Long> get() = circleRepository.recordsNoted

    /** Whether [circle] is one this device cannot open; see [CircleRepository.isCircleLocked]. */
    fun isCircleLocked(circle: CircleEntity): Boolean = circleRepository.isCircleLocked(circle)

    /** Why [circle] is locked, as the dialog that explains it. */
    suspend fun lockedCircleExplanation(circle: CircleEntity): com.pingolin.track.domain.repository.LockedCircle.Explanation {
        val cause = circleRepository.lockedCircleCause(circle.id)
        val asked = if (cause == com.pingolin.track.domain.repository.LockedCircle.Cause.AWAITING_ACCESS && !circle.isOwner) {
            circleRepository.invitationRanOutAsked(circle.id)
        } else {
            null
        }
        return com.pingolin.track.domain.repository.LockedCircle.explanationFor(cause, circle.isOwner, asked)
    }

    /**
     * Whether the Circles tab should show its "No circles yet" block.
     *
     * Derived from BOTH the list and the pagination state, because the two do
     * not move together: on a fresh account the list emits `[]` while the
     * first page is still loading, and when that load finishes nothing
     * re-emits the (unchanged, still empty) list. Judged only inside the list
     * collector, gated on `!isLoading` read at that instant, a new user's
     * Circles tab would stay a blank screen with a FAB. Combining the flows
     * makes the end of loading a state change the screen actually sees.
     */
    val showEmptyState: StateFlow<Boolean> = combine(circles, circleRepository.paginationState) { list, page ->
        list.isEmpty() && !page.isLoading
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val paginationState: StateFlow<CirclesPaginationState> = circleRepository.paginationState

    private val _selectedCircle = MutableStateFlow<CircleEntity?>(null)
    val selectedCircle: StateFlow<CircleEntity?> = _selectedCircle.asStateFlow()

    val selectedCircleMembers: StateFlow<List<CircleMemberEntity>> = _selectedCircle
        .filterNotNull()
        .flatMapLatest { circle ->
            circleRepository.observeMembers(circle.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Only populated for circles the current user owns; empty for the rest. */
    val pendingInvites: StateFlow<List<PendingInviteEntity>> = _selectedCircle
        .filterNotNull()
        .flatMapLatest { circle ->
            circleRepository.observePendingInvites(circle.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _receivedInvites = MutableStateFlow<List<ReceivedInviteResponse>>(emptyList())
    val receivedInvites: StateFlow<List<ReceivedInviteResponse>> = _receivedInvites.asStateFlow()

    /** Invitations this phone joins by itself once they are renewed. */
    private val _waitingToJoin = MutableStateFlow<Set<String>>(emptySet())
    val waitingToJoin: StateFlow<Set<String>> = _waitingToJoin.asStateFlow()

    private val _uiState = MutableStateFlow<CirclesUiState>(CirclesUiState.Idle)
    val uiState: StateFlow<CirclesUiState> = _uiState.asStateFlow()

    private val _privacySettings = MutableStateFlow<PrivacySettingsResponse?>(null)
    val privacySettings: StateFlow<PrivacySettingsResponse?> = _privacySettings.asStateFlow()

    /**
     * The circle whose privacy read last failed, else null. Its form is not shown
     * ([privacyForm]) and nothing writes it: every write here round-trips the whole row, so a
     * form of defaults, saved, would lift a pause, end approximate sharing and re-allow
     * alerts on this person.
     */
    private val _privacyLoadFailed = MutableStateFlow<String?>(null)
    val privacyLoadFailed: StateFlow<String?> = _privacyLoadFailed.asStateFlow()

    /**
     * One privacy round trip at a time, in the order asked. Every write replaces the whole row,
     * so each is built from the row as read after the one before it. Two changes made within one
     * answer of each other would otherwise both be built from the same older copy, and the second
     * would undo the first: real-time sharing switched off, then history, and real-time sharing
     * back on.
     */
    private val privacyTrips = Mutex()

    /** The newest approximate radius asked for, by circle: the slider asks at every step it passes. */
    private val bubbleAsks = mutableMapOf<String, Int>()

    /**
     * TOFU pin-divergence alerts, emitted whenever a member's keys change.
     * The UI surfaces a confirm dialog
     * (CirclesFragment.showMemberKeyChangedDialog) to either approve or block
     * the new keys.
     */
    val memberKeyAlerts: StateFlow<List<MemberKeyAlert>> = circleRepository.memberKeyAlerts

    /** "Give <name> access to <circle>?" questions a rotation raised. */
    val keyAccessRequests: StateFlow<List<com.pingolin.track.domain.repository.KeyAccessRequest>> =
        circleRepository.keyAccessRequests

    /** "Is this really {email}?" about invitations this account made. */
    val inviteRenewalQuestions: StateFlow<List<com.pingolin.track.domain.repository.InviteRenewalQuestion>> =
        circleRepository.inviteRenewalQuestions

    /** "It's them": renew the invitation for the keys the question showed. */
    fun renewInvitation(question: com.pingolin.track.domain.repository.InviteRenewalQuestion) {
        viewModelScope.launch {
            // While this phone is behind, the answer waits and goes out by itself.
            circleRepository.renewInvitation(question, keepIfBehind = true).onFailure { e ->
                _uiState.value = if (e is com.pingolin.track.domain.pending.QueuedForSync) CirclesUiState.Kept(e.line)
                else CirclesUiState.Error(e.plainText("Couldn't renew the invitation just now."))
            }
        }
    }

    /** "No": the invitation is withdrawn for good. */
    fun declineRenewal(question: com.pingolin.track.domain.repository.InviteRenewalQuestion) {
        viewModelScope.launch {
            circleRepository.declineRenewal(question).onFailure { e ->
                _uiState.value = CirclesUiState.Error(e.plainText("Couldn't withdraw the invitation just now."))
            }
        }
    }

    /** "Joining…" and the owner/admin "kept out" line, per circle. */
    val memberStates: StateFlow<Map<String, com.pingolin.track.domain.repository.CircleMemberStates>> =
        circleRepository.memberStates

    /**
     * Circle id -> the warning to show on its row: for circles still waiting to be
     * re-keyed after somebody left or was removed — to this circle's owner and admins
     * only, the people whose phones re-key it — and, to anyone, for a circle whose key
     * moved on without this phone, so nothing is shared with it from here.
     */
    val rekeyWarnings: StateFlow<Map<String, Int>> = combine(
        circles,
        circleRepository.keyRotationCauses,
        circleRepository.observeMyMemberships(),
        circleRepository.behindCircles,
        circleRepository.recordsBehindCircles,
    ) { circles, causes, memberships, behindOnKey, recordsBehind ->
        val behind = behindOnKey + recordsBehind
        val adminOf = memberships
            .filter { it.role == "admin" || it.role == "owner" }
            .map { it.circleId }
            .toSet()
        circles.mapNotNull { circle ->
            if (circle.id in behind) return@mapNotNull circle.id to com.pingolin.track.R.string.key_behind_warning
            val warning = rekeyWarningFor(causes[circle.id]) ?: return@mapNotNull null
            if (!circle.keyRotationNeeded) return@mapNotNull null
            if (!circle.isOwner && circle.id !in adminOf) return@mapNotNull null
            circle.id to warning
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun grantKeyAccess(request: com.pingolin.track.domain.repository.KeyAccessRequest) {
        viewModelScope.launch {
            // "Let them in" is kept as a waiting change and goes out by itself: at once,
            // or, while this phone is behind, once it catches up, which it says. Nothing
            // kept: the question stays, and this says why.
            circleRepository.letIn(request).onFailure { e ->
                _uiState.value = if (e is com.pingolin.track.domain.pending.QueuedForSync) CirclesUiState.Kept(e.line)
                else CirclesUiState.Error(
                    e.plainText(
                        com.pingolin.track.domain.pending.PendingLines.notDone(
                            com.pingolin.track.domain.pending.PendingInputs.letInSubject(request.displayName)
                        )
                    )
                )
            }
        }
    }

    /**
     * What changes waiting to sync ask the person: each with its question, oldest first. The
     * screen asks them one at a time, in place of the live question.
     */
    val pendingQuestions: StateFlow<List<Pair<com.pingolin.track.data.local.entity.PendingChangeEntity, com.pingolin.track.domain.pending.PendingQuestions.Ask>>> =
        pendingChanges.observe()
            .map { changes ->
                changes.filter { it.state == com.pingolin.track.domain.pending.PendingChanges.NEEDS_PERSON }
                    .mapNotNull { change -> com.pingolin.track.domain.pending.PendingQuestions.readAsk(change.question)?.let { change to it } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The person answered a waiting change's question. A renewal's "Withdraw" withdraws
     * the invitation for good, as the live question's does.
     */
    fun answerPending(changeId: String, accept: Boolean, withdrawInvitation: Boolean = false) {
        viewModelScope.launch {
            val change = pendingChanges.answer(changeId, accept) ?: return@launch
            val ask = com.pingolin.track.domain.pending.PendingQuestions.readAsk(change.question)
            ask?.let {
                circleRepository.pendingQuestionAnswered(
                    change.circleId, it.userId,
                    deferLetIn = !accept && it.ask == com.pingolin.track.domain.pending.PendingQuestions.LET_IN,
                )
            }
            val renewal = com.pingolin.track.domain.pending.PendingInputs.read(change)
                as? com.pingolin.track.domain.pending.PendingInputs.RenewInvitation
            if (!accept && withdrawInvitation && renewal != null) {
                circleRepository.withdrawInvitation(change.circleId, renewal.inviteId, renewal.statementId).onFailure { e ->
                    _uiState.value = CirclesUiState.Error(e.plainText("Couldn't withdraw the invitation just now."))
                }
            }
        }
    }

    fun deferKeyAccess(request: com.pingolin.track.domain.repository.KeyAccessRequest) {
        circleRepository.deferKeyAccess(request)
    }

    init {
        refreshCircles()
        fetchReceivedInvites()
    }

    /** Persist the user's drag-reordered circle list (device-local). */
    fun updateCircleOrder(ordered: List<CircleEntity>) {
        viewModelScope.launch {
            circleRepository.updateCircleSortOrder(ordered)
        }
    }

    /**
     * Reload the Circles tab. [force] is for an explicit pull-to-refresh: it also
     * re-imports keys and re-sends what the cheap path skips when nothing changed
     * (see CircleRepository.refreshCircles).
     */
    fun refreshCircles(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            circleRepository.resetPagination()
            val result = circleRepository.loadInitialCircles(force)
            _uiState.value = result.fold(
                onSuccess = { CirclesUiState.Idle },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to load circles")) }
            )
            // Always check for pending invites when circles are refreshed
            fetchReceivedInvites()
        }
    }

    fun loadMoreCircles() {
        // Don't trigger loading state for pagination - it's handled by pagination state
        viewModelScope.launch {
            circleRepository.loadMoreCircles()
        }
    }

    /**
     * Judge [circleId]'s member list again, so a question this device has about one of
     * its members is raised on this screen (the tap on its notification).
     */
    fun askAboutMembersOf(circleId: String) {
        viewModelScope.launch {
            circleRepository.refreshMembers(circleId).onFailure { e ->
                android.util.Log.w("CirclesViewModel", "Could not re-check the members of $circleId: ${e.message}")
            }
        }
    }

    fun selectCircle(circle: CircleEntity) {
        _selectedCircle.value = circle
        viewModelScope.launch {
            val result = circleRepository.refreshMembers(circle.id)
            result.onFailure { e ->
                android.util.Log.e("CirclesViewModel", "Failed to refresh members for ${circle.id}: ${e.message}")
            }
        }
    }

    /**
     * Create [name], having agreed to the location notice [noticeVersion]: creating a
     * circle is agreeing to share with it. The consent travels with the creation, in the
     * same request.
     */
    fun createCircle(name: String, noticeVersion: String) {
        if (name.isBlank()) {
            _uiState.value = CirclesUiState.Error("Circle name cannot be empty")
            return
        }

        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.createCircle(name, noticeVersion)
            _uiState.value = result.fold(
                onSuccess = {
                    android.util.Log.d("CirclesViewModel", "Circle created: ${it.id} name=${it.name}")
                    CirclesUiState.CircleCreated(it)
                },
                onFailure = {
                    android.util.Log.e("CirclesViewModel", "Circle creation failed: ${it.message}", it)
                    CirclesUiState.Error(it.plainText("Failed to create circle"), it.quotaError, it.premiumErrorCode)
                }
            )
        }
    }

    fun leaveCircle(circleId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.leaveCircle(circleId)
            _uiState.value = result.fold(
                onSuccess = {
                    _selectedCircle.value = null
                    CirclesUiState.CircleLeft
                },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to leave circle")) }
            )
        }
    }

    fun deleteCircle(circleId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.deleteCircle(circleId)
            _uiState.value = result.fold(
                onSuccess = {
                    _selectedCircle.value = null
                    CirclesUiState.CircleDeleted
                },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to delete circle")) }
            )
        }
    }

    fun inviteMember(circleId: String, email: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.inviteToCircle(circleId, email, keepIfBehind = true)
            _uiState.value = inviteOutcome(result)
        }
    }

    /** "It's them" for an invitee whose account changed: accept their new details here, and invite. */
    fun confirmInvitee(change: com.pingolin.track.domain.repository.InviteeChangedException) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.confirmInviteeAndInvite(change, keepIfBehind = true)
            if (result.isSuccess) circleRepository.refreshPendingInvites(change.circleId)
            _uiState.value = inviteOutcome(result)
        }
    }

    private fun inviteOutcome(result: Result<*>, failed: String = "Failed to invite member"): CirclesUiState = result.fold(
        onSuccess = { CirclesUiState.MemberInvited },
        onFailure = {
            when (it) {
                is com.pingolin.track.domain.repository.InviteeChangedException -> CirclesUiState.InviteeNeedsConfirmation(it)
                // Kept while this phone is behind: it goes out by itself.
                is com.pingolin.track.domain.pending.QueuedForSync -> CirclesUiState.Kept(it.line)
                else -> CirclesUiState.Error(it.plainText(failed))
            }
        },
    )

    /**
     * The invite dialog's "Send Invite": create an invite and refresh pending invites so the code
     * is available. Kept while this phone is behind; an account that changed is asked about
     * first.
     */
    fun createInviteAndRefresh(circleId: String, email: String) {
        android.util.Log.d("CirclesViewModel", "createInviteAndRefresh: circle=$circleId email=$email")
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.inviteToCircle(circleId, email, keepIfBehind = true)
            result.onSuccess {
                android.util.Log.d("CirclesViewModel", "Invite sent successfully")
                circleRepository.refreshPendingInvites(circleId)
            }.onFailure {
                if (it !is com.pingolin.track.domain.pending.QueuedForSync) {
                    android.util.Log.e("CirclesViewModel", "Invite failed: ${it.message}", it)
                }
            }
            _uiState.value = inviteOutcome(result, failed = "Failed to create invite")
        }
    }

    fun removeMember(circleId: String, userId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            // Named before they go: the list row goes with them.
            val name = runCatching { circleRepository.observeMembers(circleId).first() }.getOrNull()
                ?.firstOrNull { it.userId == userId }?.displayName?.takeIf { it.isNotBlank() } ?: "They"
            val circleName = runCatching { circleRepository.getCircle(circleId)?.name }.getOrNull().orEmpty()
            val result = circleRepository.removeMember(circleId, userId)
            _uiState.value = result.fold(
                onSuccess = { rekeyed ->
                    // Behind, it went through at once without a new key.
                    if (rekeyed) CirclesUiState.MemberRemoved
                    else CirclesUiState.MemberRemovedWithoutRotation(null, com.pingolin.track.domain.pending.PendingLines.removed(name, circleName))
                },
                // A distinct state, not a plain Error: this is the one failure
                // the user can still do something about, and the something is
                // a weaker removal they must choose deliberately.
                onFailure = {
                    CirclesUiState.MemberRemovalNeedsFallback(
                        circleId = circleId,
                        userId = userId,
                        reason = it.plainText("Could not remove them."),
                    )
                }
            )
        }
    }

    /**
     * The second step, and only ever the second: remove somebody without
     * re-keying the circle. Reached from the dialog [removeMember]'s failure
     * raises, never offered up front.
     */
    fun revokeMemberAccess(circleId: String, userId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.revokeMemberAccess(circleId, userId)
            _uiState.value = result.fold(
                onSuccess = { CirclesUiState.MemberRemovedWithoutRotation(it) },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to remove member")) }
            )
        }
    }

    /** Transfer circle ownership to another member. */
    fun transferOwnership(circleId: String, newOwnerId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.transferOwnership(circleId, newOwnerId)
            _uiState.value = result.fold(
                onSuccess = {
                    _selectedCircle.value = null
                    CirclesUiState.OwnershipTransferred
                },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to transfer ownership")) }
            )
        }
    }

    // ===== Circle ownership claims =====

    private val _ownershipClaim = MutableStateFlow<OwnershipClaimState?>(null)

    /**
     * The claim state for the circle whose screen is open.
     *
     * Null means "not looked yet"; a non-null value with a null [OwnershipClaimState.claim]
     * means "looked, and there is none". Those are genuinely different — the endpoint
     * answers "no live claim" with a 200 and a null payload, so collapsing them into one
     * nullable claim would make a circle that has not been checked indistinguishable from
     * one that is safe, on the screen where that distinction is the entire point.
     */
    val ownershipClaim: StateFlow<OwnershipClaimState?> = _ownershipClaim.asStateFlow()

    /**
     * Read the live claim on [circleId]. Any active member may do this, so it runs for
     * every member's view of the circle and not just the owner's: a circle changing hands
     * is not a secret between the claimant and the owner.
     *
     * A failure leaves the previous state alone rather than writing "no claim" — the
     * silence of a network error must never render as reassurance.
     */
    fun loadOwnershipClaim(circleId: String) {
        viewModelScope.launch {
            circleRepository.getOwnershipClaim(circleId)
                .onSuccess { _ownershipClaim.value = OwnershipClaimState(circleId, it) }
        }
    }

    /** Drop any claim state so a previously-opened circle's claim cannot render on another. */
    fun clearOwnershipClaim() {
        _ownershipClaim.value = null
    }

    /**
     * Ask to take over [circleId]. Admin-only, enforced by the SERVER — this does not
     * pre-judge the caller's role, so a stale local roster cannot suppress a legitimate
     * request nor invent one the server will refuse.
     */
    fun claimOwnership(circleId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            _uiState.value = circleRepository.claimOwnership(circleId).fold(
                onSuccess = {
                    _ownershipClaim.value = OwnershipClaimState(circleId, it)
                    CirclesUiState.OwnershipClaimOpened(it)
                },
                onFailure = { e ->
                    // A 409 is not a failed tap: somebody else's claim is already live.
                    // Re-read it so the caller is shown what exists rather than an error
                    // about something that did happen, just not by them.
                    if (e is OwnershipClaimExistsException) {
                        loadOwnershipClaim(circleId)
                        CirclesUiState.OwnershipClaimAlreadyExists(
                            e.plainText("Somebody has already asked to take over this circle")
                        )
                    } else {
                        CirclesUiState.Error(e.plainText("Couldn't ask to take over this circle"))
                    }
                }
            )
        }
    }

    /**
     * Refuse the claim on [circleId]. The owner's veto, and the reason this whole surface
     * exists — without it the push telling them to "open Pingolin to refuse" is a dead end.
     */
    fun refuseOwnershipClaim(circleId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            _uiState.value = circleRepository.refuseOwnershipClaim(circleId).fold(
                onSuccess = {
                    // Refusing is permanent, so the claim is gone rather than
                    // pending. Written straight to state instead of
                    // re-fetching: the owner must see it succeed even if a
                    // later read fails.
                    _ownershipClaim.value = OwnershipClaimState(circleId, null)
                    CirclesUiState.OwnershipClaimRefused
                },
                onFailure = { CirclesUiState.Error(it.plainText("Couldn't refuse the claim")) }
            )
        }
    }

    /** Withdraw the claim this user opened on [circleId]. */
    fun withdrawOwnershipClaim(circleId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            _uiState.value = circleRepository.withdrawOwnershipClaim(circleId).fold(
                onSuccess = {
                    _ownershipClaim.value = OwnershipClaimState(circleId, null)
                    CirclesUiState.OwnershipClaimWithdrawn
                },
                onFailure = { CirclesUiState.Error(it.plainText("Couldn't withdraw the claim")) }
            )
        }
    }

    /**
     * This user's role in [members], or null if they are not on the roster.
     *
     * Used only to decide which CONTROLS to offer. Every one of them is authorised again
     * by the server, so a wrong answer here costs a misleading button and never an
     * unauthorised action.
     */
    fun currentUserRoleIn(members: List<CircleMemberEntity>): String? {
        val userId = getCurrentUserId() ?: return null
        return members.firstOrNull { it.userId == userId }?.role
    }

    // ===== Member Role Management =====

    /**
     * @param role either "admin" or "member".
     * @param decidedKeys for a promotion the person was shown a number for: the keys
     *   behind it.
     */
    fun updateMemberRole(circleId: String, userId: String, role: String, decidedKeys: Pair<String, String>? = null) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            // A promotion made while this phone is behind waits and goes out by itself.
            val result = circleRepository.updateMemberRole(circleId, userId, role, decidedKeys, keepIfBehind = role == "admin")
            _uiState.value = result.fold(
                onSuccess = { CirclesUiState.MemberRoleUpdated },
                onFailure = {
                    if (it is com.pingolin.track.domain.pending.QueuedForSync) CirclesUiState.Kept(it.line)
                    else CirclesUiState.Error(it.plainText("Failed to update member role"))
                }
            )
        }
    }

    // ===== Pending Invites Management =====

    /** The circle's owner and admins see its pending invites; for anyone else the server refuses. */
    fun refreshPendingInvites(circleId: String) {
        viewModelScope.launch {
            circleRepository.refreshPendingInvites(circleId)
        }
    }

    fun revokeInvite(circleId: String, inviteId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.revokeInvite(circleId, inviteId)
            _uiState.value = result.fold(
                onSuccess = { CirclesUiState.InviteRevoked },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to revoke invite")) }
            )
        }
    }

    // Key rotation is fully automatic — see CircleRepository.autoRotateIfNeeded.
    // The ViewModel exposes no rotation action because the user can't meaningfully
    // trigger one: rotation is triggered by member changes or the periodic 14-day
    // worker, and any active member's client rotates in the background.

    // ===== Received Invites =====

    fun fetchReceivedInvites() {
        viewModelScope.launch {
            val result = circleRepository.fetchReceivedInvites()
            result.onSuccess { invites ->
                android.util.Log.d("CirclesViewModel", "Fetched ${invites.size} received invites")
                _receivedInvites.value = invites
                // One the person agreed to join may have been renewed: join it now.
                if (circleRepository.retryPendingAccepts(invites) > 0) {
                    refreshCircles()
                    circleRepository.fetchReceivedInvites().onSuccess { _receivedInvites.value = it }
                }
                _waitingToJoin.value = circleRepository.waitingToJoin()
            }.onFailure { e ->
                android.util.Log.e("CirclesViewModel", "Failed to fetch received invites: ${e.message}", e)
            }
        }
    }

    /**
     * Accept [inviteId], having agreed to the location notice [noticeVersion]: joining IS
     * agreeing. One request joins and records the consent together, so there is no "joined,
     * answer not recorded" state for anyone to be left in: the server takes both or
     * neither. Declining never reaches here — the invitation stays pending
     * (CirclesFragment.gateInviteOnConsent).
     *
     * @param noticeVersion the version that was ON SCREEN, so the record names the text
     *   actually read rather than whatever is current by the time this runs.
     */
    fun acceptInviteAfterConsent(
        inviteId: String,
        circleId: String,
        circleName: String?,
        noticeVersion: String,
    ) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            // The circleId drives the post-accept key-arrival poll.
            val result = circleRepository.acceptInvite(inviteId, circleId, noticeVersion)
            if (result.isFailure) {
                // Being renewed: the yes is kept and this phone joins by itself.
                _waitingToJoin.value = circleRepository.waitingToJoin()
                _uiState.value = CirclesUiState.Error(
                    result.exceptionOrNull()?.plainTextOrNull() ?: "Failed to accept invite"
                )
                return@launch
            }
            fetchReceivedInvites()
            _uiState.value = CirclesUiState.InviteAccepted(circleId, circleName)
        }
    }

    /**
     * Current consent state for a circle, for the privacy dialog to render.
     *
     * Null means "not known yet", and the dialog hides the row rather than
     * guessing: telling somebody who IS sharing that they are not is worse than
     * showing nothing for a moment.
     */
    private val _locationConsent =
        MutableStateFlow<com.pingolin.track.data.remote.dto.LocationConsentResponse?>(null)
    val locationConsent: StateFlow<com.pingolin.track.data.remote.dto.LocationConsentResponse?> =
        _locationConsent.asStateFlow()

    fun loadLocationConsent(circleId: String) {
        viewModelScope.launch {
            _locationConsent.value = locationConsentRepository.state(circleId)
        }
    }

    /** The notice text, or null if it could not be fetched (use the fallback). */
    suspend fun locationNotice(): com.pingolin.track.data.remote.dto.LocationConsentNoticeResponse? =
        locationConsentRepository.notice()

    fun declineInvite(inviteId: String, circleId: String? = null) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = circleRepository.declineInvite(inviteId)
            _uiState.value = result.fold(
                onSuccess = {
                    fetchReceivedInvites()
                    CirclesUiState.InviteDeclined(circleId)
                },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to decline invite")) }
            )
        }
    }

    fun getCurrentUserId(): String? = sessionManager.getUserId()

    /**
     * Is there anyone in the selected circle that ownership could go to?
     *
     * Same rule [showTransferOwnershipDialog] applies when it builds its list —
     * every member except you — hoisted so the BUTTON can ask before it closes
     * the dialog it lives in: asking after would answer the tap, in a circle of
     * one, by shutting the settings screen and toasting behind it.
     */
    fun hasTransferCandidates(): Boolean {
        val me = getCurrentUserId()
        return selectedCircleMembers.value.any { it.userId != me }
    }

    fun getSessionManager(): SessionManager = sessionManager

    fun resetState() {
        _uiState.value = CirclesUiState.Idle
    }

    // ===== Member key change alerts =====

    /** Approve a pin-divergence alert. Forwards to the SDK and removes
     *  the alert from the queue. Subsequent member fetches won't re-alert. */
    fun approveMemberKeyChange(alert: MemberKeyAlert) {
        viewModelScope.launch { circleRepository.approveMemberKeyChange(alert) }
    }

    /** See [com.pingolin.track.domain.repository.CircleRepository.promotionQuestion]. */
    fun promotionQuestion(circleId: String, userId: String): com.pingolin.track.domain.repository.CircleRepository.PromotionQuestion? =
        circleRepository.promotionQuestion(circleId, userId)

    /** Block a pin-divergence alert. Leaves the SDK pin record unchanged (so
     * we keep treating the OLD keys as canonical) and dismisses the dialog. */
    fun blockMemberKeyChange(alert: MemberKeyAlert) {
        // Don't call the SDK — we want the old pin to stay canonical so wraps
        // under the new (unverified) keys still raise alerts. Asked again
        // tomorrow at the earliest.
        circleRepository.deferMemberKeyChange(alert)
    }

    // ===== Safety number =====

    fun computeSafetyNumber(
        peerUserId: String,
        peerSharingHex: String,
        peerSigningHex: String,
    ): String? = circleRepository.computeSafetyNumber(peerUserId, peerSharingHex, peerSigningHex)

    fun cachedMemberSigningPubkey(circleId: String, userId: String): String? =
        circleRepository.cachedMemberSigningPubkey(circleId, userId)

    fun cachedSharingPubkey(circleId: String, userId: String): String? =
        circleRepository.cachedMemberSharingPubkey(circleId, userId)

    // ===== Privacy Settings =====

    fun loadPrivacySettings(circleId: String) {
        // Another circle's settings are never this one's form: a write would copy them over.
        if (_privacySettings.value?.circleId != circleId) _privacySettings.value = null
        if (_privacyLoadFailed.value == circleId) _privacyLoadFailed.value = null
        privacyTrip { readPrivacy(circleId) }
    }

    /**
     * One privacy round trip, after every one asked for before it ([privacyTrips]): a read
     * waits for a write still out, so its answer is never older than that write.
     */
    private fun privacyTrip(trip: suspend () -> Unit) {
        viewModelScope.launch { privacyTrips.withLock { trip() } }
    }

    /** Reads [circleId]'s row on a privacy trip: held for its form, or the read failed. */
    private suspend fun readPrivacy(circleId: String) {
        try {
            val response = tracksApi.getPrivacySettings(circleId)
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()?.data
                if (data == null) {
                    privacyUnread(circleId, "no settings in the answer")
                    return
                }
                privacyRead(circleId, data)
                // Mirror the server posture into the local Room table so the
                // no-network send path (coarsening / pause / realtime-off) can
                // actually enforce it. Local-only; no outbound write. Delegated
                // to CircleRepository so the DTO→entity mapping lives in one place
                // (same writer used by the bulk sync on circle refresh).
                circleRepository.persistPrivacyLocally(data)
            } else if (CircleRepository.isNoPrivacyRow(response)) {
                // The server's own "no privacy row yet" (the server seeds one when a
                // circle is created, best-effort, and that can fail). Surface the server's
                // DEFAULT privacy posture so the toggles reflect reality — share on,
                // alerts-on-me on, exact location — instead of rendering every switch
                // unchecked. Mirrors the server's defaults ('exact', true, true, true, and
                // those of allow_alerts_on_me and show_all_devices). Any other 404 is a
                // read that failed, below: no form, no defaults.
                privacyRead(circleId, PrivacySettingsResponse(
                    circleId = circleId,
                    shareRealtime = true,
                    shareHistory = true,
                    shareBattery = true,
                    pausedUntil = null,
                    primaryDeviceId = null
                ))
            } else if (response.code() == 400 || response.code() == 403) {
                // User is no longer a member - clean up stale circle
                android.util.Log.w("CirclesViewModel", "Stale circle detected: $circleId, cleaning up")
                circleRepository.deleteLocalCircle(circleId)
                if (_selectedCircle.value?.id == circleId) {
                    _selectedCircle.value = null
                }
                privacyUnread(circleId, "HTTP ${response.code()}")
            } else {
                privacyUnread(circleId, "HTTP ${response.code()}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            privacyUnread(circleId, e.javaClass.simpleName)
        }
    }

    /**
     * A privacy read of [circleId] that failed: no settings are held for it, so its form isn't
     * shown and no write can round-trip defaults or an older copy, and the screen says so. A
     * refresh after a write that failed clears the copy from before that write, too.
     */
    private fun privacyUnread(circleId: String, why: String) {
        android.util.Log.w("CirclesViewModel", "Privacy settings for $circleId not read: $why")
        if (_privacySettings.value?.circleId == circleId) _privacySettings.value = null
        _privacyLoadFailed.value = circleId
    }

    /** [data] is [circleId]'s row as the server has it now: held for its form. */
    private fun privacyRead(circleId: String, data: PrivacySettingsResponse) {
        _privacySettings.value = data
        if (_privacyLoadFailed.value == circleId) _privacyLoadFailed.value = null
    }

    /**
     * A write of [circleId] whose outcome isn't known: no answer, or an error after the server
     * may have applied it. The copy from before it can't be trusted: the next whole-row write
     * would undo what this one did (a pause, a resume, approximate sharing). Drop it and read the
     * row again on the same trip, before any other write goes; until that answers the form isn't
     * shown, and a read that fails says so.
     */
    private suspend fun privacyWriteUnsure(circleId: String) {
        if (_privacySettings.value?.circleId == circleId) _privacySettings.value = null
        readPrivacy(circleId)
    }

    /**
     * [circleId]'s settings as this phone last read them, for a write to round-trip; null
     * when they aren't loaded here, and then nothing is written: the PUT replaces the whole
     * row, so a write from defaults would undo a pause, approximate sharing and alerts-on-me.
     */
    private fun loadedPrivacy(circleId: String): PrivacySettingsResponse? =
        _privacySettings.value?.takeIf { it.circleId == circleId } ?: run {
            _uiState.value = CirclesUiState.Error(PRIVACY_NOT_LOADED)
            null
        }

    fun updatePrivacySettings(
        circleId: String,
        shareRealtime: Boolean? = null,
        shareHistory: Boolean? = null,
        shareBattery: Boolean? = null,
        allowAlertsOnMe: Boolean? = null
    ) {
        loadedPrivacy(circleId) ?: return
        privacyTrip {
            // The row as read after every write before this one (see [privacyTrips]).
            val current = loadedPrivacy(circleId) ?: return@privacyTrip
            try {
                // The PUT REPLACES the whole row (see [UpdatePrivacyRequest]): a deadline
                // this request omits is written as NULL. Toggling any switch here would
                // blank `paused_until`, turning a 4-hour pause into a permanent one, so
                // both deadlines are round-tripped — this call owns neither.
                val request = UpdatePrivacyRequest(
                    // Gson leaves an omitted sharing_mode null whatever the declared default; the
                    // compiled elvis still checks it (the warning says otherwise).
                    sharingMode = current.sharingMode ?: "exact",
                    shareRealtime = shareRealtime ?: current.shareRealtime,
                    shareHistory = shareHistory ?: current.shareHistory,
                    shareBattery = shareBattery ?: current.shareBattery,
                    approximateRadiusMiles = current.approximateRadiusMiles,
                    pausedUntil = current.pausedUntil,
                    approximateUntil = current.approximateUntil,
                    showAllDevices = current.showAllDevices,
                    allowAlertsOnMe = allowAlertsOnMe ?: current.allowAlertsOnMe
                )
                val response = tracksApi.updatePrivacySettings(circleId, request)
                if (response.isSuccessful && response.body()?.success == true) {
                    // Real-time sharing on again, from here: the first point after it goes at once.
                    if (!current.shareRealtime && shareRealtime == true) circleRepository.resumedHere(circleId)
                    readPrivacy(circleId)
                } else {
                    _uiState.value = CirclesUiState.Error("Failed to update privacy settings")
                    privacyWriteUnsure(circleId)
                }
            } catch (e: Exception) {
                _uiState.value = CirclesUiState.Error("Failed to update privacy settings")
                privacyWriteUnsure(circleId)
            }
        }
    }

    /**
     * Enable bubble mode — approximate location sharing — for a circle.
     *
     * Goes through the settings PUT rather than POST /bubble, which is the
     * whole point of this function's shape. The bubble route REQUIRES a
     * duration: once it ran out the position would silently go back to exact,
     * and unlike a pause nothing is sent to say so — somebody using it to stay
     * out of one member's view would be un-hidden without being told.
     *
     * iOS and the website set the mode with NO expiry too, so the same control
     * protects people the same way on every phone. The PUT leaves
     * approximate_until NULL, which is the server's "until you turn it off".
     *
     * @param radiusMiles 1-25; the server rejects anything outside that range.
     */
    fun enableBubble(circleId: String, radiusMiles: Int) {
        loadedPrivacy(circleId) ?: return
        // The slider asks at every step it passes: of the asks still waiting, only the newest goes.
        val ask = (bubbleAsks[circleId] ?: 0) + 1
        bubbleAsks[circleId] = ask
        privacyTrip {
            if (bubbleAsks[circleId] != ask) return@privacyTrip
            val current = loadedPrivacy(circleId) ?: return@privacyTrip
            try {
                val response = tracksApi.updatePrivacySettings(
                    circleId,
                    UpdatePrivacyRequest(
                        sharingMode = "approximate",
                        shareRealtime = current.shareRealtime,
                        shareHistory = current.shareHistory,
                        shareBattery = current.shareBattery,
                        approximateRadiusMiles = radiusMiles,
                        // This call OWNS the approximate dimension, so
                        // it states the expiry rather than
                        // round-tripping it: null = "until you turn it
                        // off" (see the doc above). Carrying an older,
                        // possibly already-expired expiry forward
                        // would make the bubble the user just switched
                        // on invisible on arrival — the server hides a
                        // lapsed approximate member outright. The
                        // pause deadline is not ours to clear, so it
                        // round-trips.
                        approximateUntil = null,
                        pausedUntil = current.pausedUntil,
                        showAllDevices = current.showAllDevices,
                        allowAlertsOnMe = current.allowAlertsOnMe
                    )
                )
                if (response.isSuccessful) {
                    readPrivacy(circleId)
                } else {
                    _uiState.value = CirclesUiState.Error("Failed to enable bubble mode")
                    privacyWriteUnsure(circleId)
                }
            } catch (e: Exception) {
                _uiState.value = CirclesUiState.Error("Failed to enable bubble mode")
                privacyWriteUnsure(circleId)
            }
        }
    }

    /** Disable bubble mode, returning the circle to exact location sharing. */
    fun disableBubble(circleId: String) {
        loadedPrivacy(circleId) ?: return
        privacyTrip {
            val current = loadedPrivacy(circleId) ?: return@privacyTrip
            try {
                val response = tracksApi.updatePrivacySettings(
                    circleId,
                    UpdatePrivacyRequest(
                        sharingMode = "exact",
                        shareRealtime = current.shareRealtime,
                        shareHistory = current.shareHistory,
                        shareBattery = current.shareBattery,
                        // Ending the bubble is exactly what this call is for, so its
                        // expiry goes with it. The pause deadline is round-tripped: the
                        // PUT rewrites every column, and dropping it would convert a
                        // timed pause into a permanent one.
                        approximateUntil = null,
                        pausedUntil = current.pausedUntil,
                        showAllDevices = current.showAllDevices,
                        allowAlertsOnMe = current.allowAlertsOnMe
                    )
                )
                if (response.isSuccessful) {
                    readPrivacy(circleId)
                } else {
                    _uiState.value = CirclesUiState.Error("Failed to disable bubble mode")
                    privacyWriteUnsure(circleId)
                }
            } catch (e: Exception) {
                _uiState.value = CirclesUiState.Error("Failed to disable bubble mode")
                privacyWriteUnsure(circleId)
            }
        }
    }

    fun updateShowAllDevices(circleId: String, showAllDevices: Boolean) {
        loadedPrivacy(circleId) ?: return
        privacyTrip {
            val current = loadedPrivacy(circleId) ?: return@privacyTrip
            try {
                val response = tracksApi.updatePrivacySettings(
                    circleId,
                    UpdatePrivacyRequest(
                        // Gson leaves an omitted sharing_mode null whatever the declared default; the
                        // compiled elvis still checks it (the warning says otherwise).
                        sharingMode = current.sharingMode ?: "exact",
                        shareRealtime = current.shareRealtime,
                        shareHistory = current.shareHistory,
                        shareBattery = current.shareBattery,
                        // Owns no mode: round-trip both deadlines and the bubble radius,
                        // or this write blanks them (see [UpdatePrivacyRequest]). The
                        // radius matters as much as the deadlines — the server rejects
                        // sharing_mode='approximate' with no radius outright (400), so
                        // omitting it would make this toggle simply fail for anyone in a
                        // bubble.
                        approximateRadiusMiles = current.approximateRadiusMiles,
                        pausedUntil = current.pausedUntil,
                        approximateUntil = current.approximateUntil,
                        showAllDevices = showAllDevices,
                        allowAlertsOnMe = current.allowAlertsOnMe
                    )
                )
                if (response.isSuccessful) {
                    readPrivacy(circleId)
                } else {
                    _uiState.value = CirclesUiState.Error("Failed to update device visibility")
                    privacyWriteUnsure(circleId)
                }
            } catch (e: Exception) {
                _uiState.value = CirclesUiState.Error("Failed to update device visibility")
                privacyWriteUnsure(circleId)
            }
        }
    }

    fun pauseSharing(circleId: String, durationMinutes: Int) {
        privacyTrip {
            try {
                // Convert duration in minutes to an RFC3339 resume_at timestamp
                val resumeAt = java.time.Instant.now()
                    .plusSeconds(durationMinutes.toLong() * 60)
                    .toString()
                val request = PauseSharingRequest(resumeAt = resumeAt)
                val response = tracksApi.pauseSharing(circleId, request)
                if (response.isSuccessful && response.body()?.success == true) {
                    readPrivacy(circleId)
                } else {
                    _uiState.value = CirclesUiState.Error("Failed to pause sharing")
                    privacyWriteUnsure(circleId)
                }
            } catch (e: Exception) {
                _uiState.value = CirclesUiState.Error("Failed to pause sharing")
                privacyWriteUnsure(circleId)
            }
        }
    }

    /**
     * End a pause: sharing goes back to the mode held before it, approximate with its
     * radius or exact.
     *
     * POST …/resume, not the settings PUT. The PUT rewrites every column, so this call
     * would have to name a mode, and naming "exact" would bring an approximate circle
     * back exact after every pause. Only the server holds the mode from before the pause,
     * so it restores it, and its answer (the settings, as the GET gives them) is shown
     * and kept at once. Nothing else is sent, so the other switches (real-time sharing
     * above all) stay as they were.
     *
     * A server without the route answers 404 and the error shows. No fallback to the PUT:
     * it would quietly restore exact and hide a route that isn't there.
     */
    fun resumeSharing(circleId: String) {
        privacyTrip {
            try {
                val response = tracksApi.resumeSharing(circleId)
                if (response.isSuccessful && response.body()?.success == true) {
                    // Resumed from here: the first point after it goes at once.
                    circleRepository.resumedHere(circleId)
                    val data = response.body()?.data
                    if (data != null) {
                        privacyRead(circleId, data)
                        circleRepository.persistPrivacyLocally(data)
                    } else {
                        readPrivacy(circleId)
                    }
                } else {
                    _uiState.value = CirclesUiState.Error("Failed to resume sharing")
                    privacyWriteUnsure(circleId)
                }
            } catch (e: Exception) {
                _uiState.value = CirclesUiState.Error("Failed to resume sharing")
                privacyWriteUnsure(circleId)
            }
        }
    }

    // ===== Share All Places =====

    /** Each place is copied and re-encrypted for the target circle's key. */
    fun shareAllPlacesToCircle(sourceCircleId: String, targetCircleId: String) {
        viewModelScope.launch {
            _uiState.value = CirclesUiState.Loading
            val result = placeRepository.copyAllPlacesToCircle(sourceCircleId, targetCircleId, keepIfBehind = true)
            _uiState.value = result.fold(
                onSuccess = { count ->
                    if (count == 0) {
                        CirclesUiState.Error("No places to share")
                    } else {
                        CirclesUiState.PlacesShared(count)
                    }
                },
                onFailure = { CirclesUiState.Error(it.plainText("Failed to share places")) }
            )
        }
    }

    fun getOtherCircles(excludeCircleId: String): List<CircleEntity> {
        return circles.value.filter { it.id != excludeCircleId }
    }
}

sealed class CirclesUiState {
    object Idle : CirclesUiState()
    object Loading : CirclesUiState()
    /** [quota]/[errorCode] are non-null when the failure was a subscription
     *  gate (402) — e.g. the free circle-count limit — so the Fragment can
     *  show the premium prompt. */
    data class Error(
        val message: String,
        val quota: QuotaError? = null,
        val errorCode: String? = null,
    ) : CirclesUiState()
    data class CircleCreated(val circle: CircleEntity) : CirclesUiState()
    data class CircleJoined(val circle: CircleEntity) : CirclesUiState()
    object CircleLeft : CirclesUiState()
    object CircleDeleted : CirclesUiState()
    object MemberInvited : CirclesUiState()
    /** The invited account changed since this device saw it: "Is this really {email}?" */
    data class InviteeNeedsConfirmation(
        val change: com.pingolin.track.domain.repository.InviteeChangedException,
    ) : CirclesUiState()
    object MemberRemoved : CirclesUiState()
    /** eject-and-rotate was refused. Carries what is needed to offer the
     * no-rotation removal, plus the real reason, never a generic "please
     * update your app" whatever actually went wrong. */
    data class MemberRemovalNeedsFallback(
        val circleId: String,
        val userId: String,
        val reason: String,
    ) : CirclesUiState()
    /** [warning] is the server's sentence about what a removal without a
     *  rotation leaves behind. Null only if an older server omits it, in which
     *  case we say nothing rather than inventing reassurance. */
    data class MemberRemovedWithoutRotation(
        val warning: String?,
        /** Before the usual line, when it went through while this phone was behind. */
        val lead: String? = null,
    ) : CirclesUiState()
    object MemberRoleUpdated : CirclesUiState()
    /** Kept to go out once this phone catches up: [line] is its waiting line. */
    data class Kept(val line: String) : CirclesUiState()
    data class PlacesShared(val count: Int) : CirclesUiState()
    object OwnershipTransferred : CirclesUiState()
    object InviteRevoked : CirclesUiState()
    /** Carries the circle so the location notice can be shown against it —
     *  joining is when collection starts, so it is when the notice is owed. The
     *  name is nullable because a circle whose key has not arrived yet cannot be
     *  decrypted; the consent is still owed either way, so the Fragment falls
     *  back to a generic label rather than skipping it. */
    data class InviteAccepted(
        val circleId: String,
        val circleName: String?,
    ) : CirclesUiState()
    /** [circleId]: the invitation's circle, whose banner goes; null when not known. */
    data class InviteDeclined(val circleId: String?) : CirclesUiState()

    /** This user opened a claim. Carries it so the deadline can be shown immediately. */
    data class OwnershipClaimOpened(val claim: OwnershipClaimResponse) : CirclesUiState()

    /**
     * The server refused to open one because a claim is already live (409). Carries the
     * server's own sentence — it says who the situation belongs to better than a generic
     * failure would, and this is not an error the user caused.
     */
    data class OwnershipClaimAlreadyExists(val message: String) : CirclesUiState()

    /** The owner said no. Permanent — the claimant must open a new claim to try again. */
    object OwnershipClaimRefused : CirclesUiState()

    /** The claimant thought better of it. */
    object OwnershipClaimWithdrawn : CirclesUiState()
}

/**
 * What is known about a circle's ownership claim, and whether it has been looked at.
 *
 * [claim] is null when the circle has been CHECKED and has none. The absence of the whole
 * [OwnershipClaimState] is what means "not checked yet". Keeping the two apart is not
 * fussiness: the screen has to be able to stay silent while it does not know, rather than
 * telling an owner their circle is safe because a request has not come back.
 *
 * [circleId] is this client's own record of what was asked about, deliberately not the
 * server's `circle_id` from the payload — that field is nullable on the wire, and a
 * guard that reads null would let one circle's claim render on another's screen.
 */
data class OwnershipClaimState(
    val circleId: String,
    val claim: OwnershipClaimResponse?,
)

/**
 * The re-key warning for a pending rotation's [cause], or null when the cause carries no
 * exposure: a keyless join or the periodic cycle leaves nobody holding a key they
 * should no longer have.
 */
internal fun rekeyWarningFor(cause: String?): Int? = when (cause) {
    com.pingolin.track.domain.repository.RotationPlanner.CAUSE_MEMBER_REMOVED ->
        com.pingolin.track.R.string.rekey_warning_member_removed
    com.pingolin.track.domain.repository.RotationPlanner.CAUSE_MEMBER_LEFT ->
        com.pingolin.track.R.string.rekey_warning_member_left
    else -> null
}

/** What a write says when the circle's settings never loaded here, so nothing was sent. */
internal const val PRIVACY_NOT_LOADED = "Your settings for this circle haven't loaded, so nothing was changed. Try again."

/**
 * The battery switch's row: the level goes to the circle only while "Allow alerts on me" is on too,
 * since its one reader is the low-battery alert. So the switch is usable only then, and its line says
 * why otherwise: whether it can be used, and the line.
 */
internal fun batteryRow(allowAlertsOnMe: Boolean): Pair<Boolean, Int> = if (allowAlertsOnMe) {
    true to com.pingolin.track.R.string.privacy_low_battery_alerts_body
} else {
    false to com.pingolin.track.R.string.privacy_low_battery_needs_alerts
}

/** The circle privacy dialog's form: shown only once that circle's own settings are here. */
internal enum class PrivacyForm { LOADING, FAILED, READY }

/**
 * [circleId]'s form, from the held settings and the circle whose read last failed: READY only
 * with this circle's own settings, never another circle's; FAILED when its read failed (the
 * dialog says so, with Try again); else LOADING. Only READY shows the controls.
 */
internal fun privacyForm(circleId: String, settings: PrivacySettingsResponse?, failedFor: String?): PrivacyForm = when {
    settings?.circleId == circleId -> PrivacyForm.READY
    failedFor == circleId -> PrivacyForm.FAILED
    else -> PrivacyForm.LOADING
}
