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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.RenameSessionRequest
import com.pingolin.track.data.remote.dto.SessionInfo
import com.pingolin.track.data.remote.dto.TrackingDevice
import com.pingolin.track.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val trackApi: TrackApi,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    /** One-shot events the Fragment shows as toasts and dismisses. */
    private val _events = MutableStateFlow<DevicesEvent?>(null)
    val events: StateFlow<DevicesEvent?> = _events.asStateFlow()

    /**
     * Pauses before loading again after a failure. The list loads again by itself
     * while this screen is up, instead of offering a "Retry" button (people are never
     * asked to do what the app can do itself); tests shorten them.
     */
    internal var reloadPausesMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    private var failedLoads = 0
    private var reloadJob: Job? = null

    init {
        load()
    }

    fun load() {
        reloadJob?.cancel()
        reloadJob = null
        viewModelScope.launch { loadOnce() }
    }

    private fun failed() {
        _state.update { it.copy(loading = false, error = "Can't load your devices right now. Trying again…") }
        val pause = reloadPausesMs[minOf(failedLoads, reloadPausesMs.lastIndex)]
        failedLoads++
        reloadJob = viewModelScope.launch {
            delay(pause)
            loadOnce()
        }
    }

    private suspend fun loadOnce() {
        _state.update { it.copy(loading = true, error = null) }
        // Fire both lookups in parallel: auth.sessions for the
        // user-visible labels (device_name) and track's
        // /tracking-devices for the filter (only sessions that
        // actually push location count as "devices" in this UI).
        val outcome = runCatching {
            coroutineScope {
                val sessionsDeferred = async { authApi.listSessions() }
                val devicesDeferred = async { trackApi.listTrackingDevices() }
                sessionsDeferred.await() to devicesDeferred.await()
            }
        }
        // A load cancelled by a newer one is not a failure to report or retry.
        val pair = outcome.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e else null }
        if (pair == null) {
            failed()
            return
        }
        // Best-effort and deliberately outside the runCatching above: a
        // warning that breaks the screen it warns on is worse than no
        // warning, and an older backend has no such route.
        val unenforced = runCatching {
            val r = trackApi.getRevocationStatus()
            r.isSuccessful && r.body()?.data?.enforcing == false
        }.getOrDefault(false)

        val (sResp, dResp) = pair
        val sessions = sResp.body()?.data?.sessions
        if (!sResp.isSuccessful || sessions == null) {
            failed()
            return
        }
        // tracking-devices is best-effort: if it 404s on an older
        // backend or transiently errors, fall back to "no filter"
        // (show all sessions) rather than blocking the screen. The
        // log line is intentionally terse — this is the expected
        // failure mode during a rolling deploy.
        val devices = if (dResp.isSuccessful) {
            dResp.body()?.data.orEmpty()
        } else {
            android.util.Log.w("DevicesViewModel",
                "tracking-devices failed (${dResp.code()}); showing unfiltered session list")
            null
        }
        failedLoads = 0
        _state.update {
            it.copy(
                loading = false,
                error = null,
                items = buildItems(sessions, devices),
                signOutsUnenforced = unenforced,
            )
        }
    }

    fun rename(sessionId: String, newName: String?) {
        viewModelScope.launch {
            val result = runCatching {
                authApi.renameSession(sessionId, RenameSessionRequest(deviceName = newName))
            }
            val ok = result.getOrNull()?.isSuccessful == true
            _events.value = if (ok) DevicesEvent.Renamed else DevicesEvent.RenameFailed
            if (ok) load()
        }
    }

    fun revoke(sessionId: String) {
        viewModelScope.launch {
            val result = runCatching { authApi.revokeSession(sessionId) }
            val ok = result.getOrNull()?.isSuccessful == true
            _events.value = if (ok) DevicesEvent.Revoked else DevicesEvent.RevokeFailed
            if (ok) load()
        }
    }

    /**
     * Revoke every session except the caller's current one. The
     * server exposes no "revoke others" endpoint, so we iterate.
     * Errors per row are swallowed; we surface a single summary event
     * at the end.
     */
    fun revokeAllOthers() {
        viewModelScope.launch {
            val targets = _state.value.items
                .filterIsInstance<DeviceListItem.Other>()
                .map { it.session.id }
            if (targets.isEmpty()) {
                _events.value = DevicesEvent.NoOthersToRevoke
                return@launch
            }
            _state.update { it.copy(bulkInFlight = true) }
            var ok = 0
            var failed = 0
            for (id in targets) {
                val result = runCatching { authApi.revokeSession(id) }
                if (result.getOrNull()?.isSuccessful == true) ok++ else failed++
            }
            _state.update { it.copy(bulkInFlight = false) }
            _events.value = DevicesEvent.BulkRevoked(ok = ok, failed = failed)
            load()
        }
    }

    fun consumeEvent() { _events.value = null }

    /**
     * Group rows into three buckets — current device hero card, then "Active"
     * (≤7 days), then "Older" (>7 days). Sort by last activity descending within
     * each bucket. Failure to parse the timestamp just sinks the row to the
     * older bucket — better than crashing the screen.
     *
     * TWO SOURCES, not one. A row is either an auth session that a tracking
     * device points at, or a tracking device that points at no session — see
     * [DeviceListItem.Unlinked]. Dropping the second kind would filter on the
     * client what the server deliberately lists.
     *
     * @param trackingDevices Optional intersection set. When non-null, only
     *   sessions whose id appears as a session_id in tracking devices are kept,
     *   and the devices with a NULL session_id are rendered on their own. Passed
     *   as null when the /tracking-devices probe didn't come back (older backend,
     *   network blip); in that case we show the unfiltered session list, and no
     *   unlinked rows, so the screen doesn't go blank.
     */
    private fun buildItems(
        sessions: List<SessionInfo>,
        trackingDevices: List<TrackingDevice>?,
    ): List<DeviceListItem> {
        val nowMs = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000

        // Build sid → push-row map so we can prefer last_seen_day_bucket
        // (more accurate than the session's last_used_at because the
        // server updates it on every location upload, while last_used_at
        // only ticks on refresh).
        val pushBySid: Map<String, TrackingDevice> =
            trackingDevices.orEmpty()
                .filter { it.sessionId != null }
                .associateBy { it.sessionId!! }

        val filteredSessions = if (trackingDevices == null) sessions
        else sessions.filter { it.id in pushBySid.keys }

        // Devices with no session back-link at all. The server deliberately
        // returns these, and does not filter on whether a device is still active:
        // a phone that stops receiving notifications must not also stop appearing
        // here, or the list would look correct while the notifications simply did
        // not arrive. Joining every row through session_id would put that filter
        // straight back on the client.
        //
        // Only a NULL session_id qualifies, NOT "a session_id absent from
        // `sessions`". listSessions() is paginated (SessionsListResponse carries
        // limit/offset), so a device pointing at a session on a page we did not
        // fetch would otherwise be drawn twice — once as itself here and once as
        // its session when that page arrives.
        val unlinkedDevices = trackingDevices.orEmpty().filter { it.sessionId == null }

        val parsedSessions = filteredSessions.map { session ->
            val pushTs = pushBySid[session.id]?.lastSeenDayBucket
                ?.let { it * 86_400_000L }
            val authTs = parseIsoMs(session.lastUsedAt)
            // Prefer the higher of the two — the server's
            // last_seen_day_bucket is fresher when the device is actively
            // uploading; the auth session's last_used_at is fresher right
            // after a token refresh.
            val ts = listOfNotNull(pushTs, authTs).maxOrNull()
            session to ts
        }
        val current = parsedSessions.firstOrNull { it.first.isCurrent }

        // Session rows and session-less device rows share the Active/Older split rather
        // than getting a third section of their own: a phone that reported an hour ago
        // belongs beside the other devices that reported an hour ago, whatever its auth
        // row looks like.
        //
        // Neither kind of unlinked row can be the hero card: the server marks a device as
        // current only when its session id matches the caller's, so a NULL session_id is
        // never current.
        val ranked: List<RankedRow> = (
            parsedSessions.map { (s, ts) -> RankedRow.FromSession(s, ts) } +
                unlinkedDevices.map { d ->
                    RankedRow.FromDevice(d, d.lastSeenDayBucket?.let { it * 86_400_000L })
                }
            )
            .filterNot { it is RankedRow.FromSession && it.session.isCurrent }
            .sortedByDescending { it.lastActiveMs ?: 0L }

        val recent = ranked.filter { it.lastActiveMs.let { ts -> ts != null && nowMs - ts <= sevenDaysMs } }
        val older = ranked.filter { it.lastActiveMs.let { ts -> ts == null || nowMs - ts > sevenDaysMs } }

        fun render(row: RankedRow, dimmed: Boolean): DeviceListItem = when (row) {
            is RankedRow.FromSession ->
                DeviceListItem.Other(row.session, relativeTime(nowMs, row.lastActiveMs), dimmed)
            is RankedRow.FromDevice -> DeviceListItem.Unlinked(
                device = row.device,
                name = unlinkedDeviceName(row.device),
                // Say why the row has no menu, rather than leaving a card that
                // silently does nothing when tapped. It is also the honest
                // answer to what the screen is being asked: the device is
                // registered for push and is not attached to a sign-in.
                subtitle = relativeTime(nowMs, row.lastActiveMs) + " · No linked sign-in",
                dimmed = dimmed,
            )
        }

        val out = mutableListOf<DeviceListItem>()
        // The current device is THIS phone, so use its locally-recorded last
        // successful location send — the real "location received" time, in
        // minutes — instead of the day-bucketed/session proxy the server only
        // keeps for remote devices.
        current?.let {
            val localSendTs = sessionManager.getLastSuccessfulSendAt().takeIf { ts -> ts > 0L }
            out.add(DeviceListItem.Current(it.first, relativeTime(nowMs, localSendTs ?: it.second)))
        }
        if (recent.isNotEmpty()) {
            out.add(DeviceListItem.SectionHeader("Active", actionLabel = null))
            recent.forEach { out.add(render(it, dimmed = false)) }
        }
        if (older.isNotEmpty()) {
            // "Sign out all" only ever applies to the session rows —
            // revokeAllOthers iterates DeviceListItem.Other — and an Unlinked
            // row in the same section is simply not among its targets. The count
            // still includes them because it labels the section, not the button.
            //
            // SO THE ACTION IS OFFERED ONLY WHEN IT HAS A TARGET. Beside a
            // section of unlinked push registrations only, "Sign out all" would
            // show no confirmation dialog and no visible result (with nothing to
            // revoke, confirmBulkRevoke toasts and returns before any dialog),
            // and the devices would stay listed: an action that cannot act
            // should not be on screen offering to. The header still counts every
            // row; only the button is conditional.
            val revocable = older.count { it is RankedRow.FromSession }
            out.add(
                DeviceListItem.SectionHeader(
                    title = "Older (${older.size})",
                    actionLabel = "Sign out all".takeIf { revocable > 0 },
                )
            )
            older.forEach { out.add(render(it, dimmed = true)) }
        }
        return out
    }

    /**
     * A row and its last-activity stamp, before it is known which section it
     * lands in. Exists because the two sources — an auth session and a
     * session-less push registration — sort and bucket together but render
     * differently, and `dimmed` is decided by the bucket.
     */
    private sealed class RankedRow {
        abstract val lastActiveMs: Long?

        data class FromSession(
            val session: SessionInfo,
            override val lastActiveMs: Long?,
        ) : RankedRow()

        data class FromDevice(
            val device: TrackingDevice,
            override val lastActiveMs: Long?,
        ) : RankedRow()
    }

    /**
     * What to call a device with no session name behind it.
     *
     * There is nothing user-supplied to fall back on: the server's record of a
     * push registration holds `platform` and `push_type`, and the friendly name
     * lives on the auth session this row does not have. Naming the platform is
     * at least true and is enough to tell an Android phone from an iPad in a
     * list of two or three.
     *
     * `platform` is one of android / ios / web — the server rejects anything
     * else — so the else branch is for a server that has grown a fourth, not for
     * the values we know about.
     */
    private fun unlinkedDeviceName(device: TrackingDevice): String =
        when (device.platform.trim().lowercase()) {
            "android" -> "Android device"
            "ios" -> "iPhone or iPad"
            "web" -> "Web browser"
            else -> "Unnamed device"
        }

    private fun parseIsoMs(iso: String): Long? {
        // Auth emits ISO-8601 with fractional seconds and a "Z" suffix.
        // SimpleDateFormat's "S" pattern doesn't tolerate variable widths
        // across API levels, so strip the fractional part before parsing.
        val clean = iso.substringBefore('.').substringBefore('+').substringBefore('Z')
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            parser.parse(clean)?.time
        }.getOrNull()
    }

    /** "Active now" / "5 minutes ago" / "3 days ago" / absolute date for old rows. */
    private fun relativeTime(nowMs: Long, ts: Long?): String {
        if (ts == null) return "Unknown last activity"
        val deltaMs = (nowMs - ts).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000
        val hours = deltaMs / 3_600_000
        val days = deltaMs / (24L * 3_600_000)
        return when {
            minutes < 1 -> "Active now"
            minutes == 1L -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            hours == 1L -> "1 hour ago"
            hours < 24 -> "$hours hours ago"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            days < 30 -> "${days / 7} week${if (days / 7 == 1L) "" else "s"} ago"
            else -> {
                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                fmt.format(java.util.Date(ts))
            }
        }
    }
}

data class DevicesUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val bulkInFlight: Boolean = false,
    val items: List<DeviceListItem> = emptyList(),
    /** True when the server has recently been unable to confirm that sessions
     *  have ended, so a sign-out made here may not take effect immediately. */
    val signOutsUnenforced: Boolean = false,
)

sealed class DevicesEvent {
    object Renamed : DevicesEvent()
    object RenameFailed : DevicesEvent()
    object Revoked : DevicesEvent()
    object RevokeFailed : DevicesEvent()
    object NoOthersToRevoke : DevicesEvent()
    data class BulkRevoked(val ok: Int, val failed: Int) : DevicesEvent()
}
