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

package com.pingolin.track.domain.repository

import android.util.Log
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val notificationDao: NotificationDao,
    private val placeDao: PlaceDao,
    private val destinationRepository: DestinationRepository,
    // Names and places for the server's nameless rows, from this device's own data, as far as
    // the "Notification detail" setting allows.
    private val notificationNames: NotificationNames,
    private val sessionManager: com.pingolin.track.util.SessionManager,
) {
    companion object {
        /** Notifications recorded on-device for client-only alerts use this id prefix.
         *  They have no server row, so read/delete must skip the server round-trip. */
        const val LOCAL_ID_PREFIX = "local-"
        private const val TAG = "NotificationRepo"
    }

    /**
     * Observe all notifications.
     */
    fun observeAll(): Flow<List<NotificationEntity>> = notificationDao.observeAll().orWhenUnopenable(emptyList())

    /**
     * Observe unread notifications.
     */
    fun observeUnread(): Flow<List<NotificationEntity>> = notificationDao.observeUnread().orWhenUnopenable(emptyList())

    /**
     * Observe unread notification count for badge display.
     *
     * A database that can't be opened counts nothing, and says so, rather than crashing.
     */
    fun observeUnreadCount(): Flow<Int> = notificationDao.observeUnreadCount().orWhenUnopenable(0)

    /**
     * Get the unread notification count.
     */
    suspend fun getUnreadCount(): Int = notificationDao.getUnreadCount()

    /**
     * Refresh notifications from the server.
     *
     * Destination rows get their copy personalised before insert: the
     * server stores only generic E2EE-safe text ("A circle member shared
     * a new destination") plus circle-key ciphertexts in metadata; the
     * client decrypts those into "Alice created a destination for Home"
     * so the bell list reads like the tray notification did.
     */
    suspend fun refreshNotifications(limit: Int = 50, offset: Int = 0): Result<List<NotificationEntity>> {
        return try {
            val response = tracksApi.getNotifications(limit, offset)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val data = response.body()!!.data!!
                // Per-notification decrypt/personalisation is CPU-bound (UniFFI +
                // local place lookups); move it off the Main thread so a full page
                // of notifications can't jank the caller (viewModelScope).
                val detail = NamedNotificationCopy.detailOf(sessionManager.getNotificationDetailLevel())
                val pass = NotificationNames.Pass()
                val othersResponding = othersResponding(data.notifications)
                val notifications = withContext(Dispatchers.Default) {
                    data.notifications.map { dto ->
                    val (title, body) = when (dto.notificationType) {
                        // E2EE: server sends generic copy + place_id; render the cached place
                        // name here if we still have it, else keep the generic body.
                        "place_alert_removed" -> dto.title to placeAlertRemovedBody(dto.body, dto.metadata)
                        "destination_shared", "destination_arrival" -> personalizeDestinationCopy(
                            dto.notificationType,
                            dto.title,
                            dto.body,
                            dto.metadata
                        )
                        // Who and where, from the ids the row carries (NotificationNames).
                        else -> NamedNotificationCopy.rowCopy(
                            dto.notificationType,
                            dto.title,
                            dto.body,
                            if (detail == NamedNotificationCopy.Detail.GENERIC) NamedNotificationCopy.Names()
                            else notificationNames.namesFor(
                                dto.notificationType,
                                dto.circleId,
                                dto.metadata.orEmpty().mapValues { it.value.toString() },
                                pass,
                            ).copy(others = othersResponding[dto.id] ?: 0),
                            detail,
                        )
                    }
                    NotificationEntity(
                        id = dto.id,
                        circleId = dto.circleId,
                        notificationType = dto.notificationType,
                        title = title,
                        body = body,
                        metadata = dto.metadata?.toString(),
                        isRead = dto.isRead,
                        createdDayBucket = dto.createdDayBucket ?: 0,
                        createdAt = parseIsoToEpochMillis(dto.createdAt)
                    )
                }
                }
                notificationDao.insertAll(notifications)
                reconcileDeletions(notifications, limit, offset)
                Result.success(notifications)
            } else {
                Result.failure(Exception("Failed to fetch notifications"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove server-backed rows the server has stopped returning.
     *
     * insertAll+REPLACE inserts and updates but never deletes, so without this a
     * notification dismissed on another client sits in this list until the 90-day
     * prune. A client that rebuilds its list from every response has no such
     * problem; Android keeps a mirror, so it has to reconcile explicitly.
     *
     * THE PAGINATION TRAP: the refresh fetches ONE PAGE (default limit 50). The
     * naive "delete everything not in the response" would therefore delete every
     * notification past the first 50 on each refresh. So this only ever deletes
     * within the range the page actually covers:
     *
     *   - page not full (returned < limit)  -> the page IS the whole list, so any
     *     server-backed row missing from it is genuinely gone.
     *   - page full -> more rows may exist below it, so delete only rows STRICTLY
     *     newer than the oldest bucket returned. Rows sharing that boundary
     *     bucket are left alone: the page edge may have cut through a day.
     *   - empty first page -> the user has no server notifications at all.
     *
     * Only runs for `offset == 0`. A "load more" page is a window into the middle
     * of the list and carries no information about what was deleted above it.
     *
     * `local-` rows are never touched — they have no server counterpart, so their
     * absence from a response means nothing. The DAO queries enforce that too;
     * it is not left to the caller.
     *
     * NOTE: this is only valid for a FULL listing. If a caller ever fetches with
     * `unread_only=true`, every read row would look "missing" and be deleted —
     * `refreshNotifications` deliberately does not expose that parameter.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun reconcileDeletions(
        page: List<NotificationEntity>,
        limit: Int,
        offset: Int,
    ) {
        if (offset != 0) return
        try {
            val removed = when {
                page.isEmpty() -> notificationDao.deleteAllServerRows()
                page.size < limit -> notificationDao.deleteServerRowsNotIn(page.map { it.id })
                else -> notificationDao.deleteServerRowsNotInSince(
                    page.map { it.id },
                    page.minOf { it.createdDayBucket },
                )
            }
            if (removed > 0) {
                android.util.Log.i(TAG, "Reconciled $removed notification(s) deleted on another device")
            }
        } catch (e: Exception) {
            // Never fail a refresh over reconciliation: a stale extra row is a
            // far smaller problem than an empty notification list.
            android.util.Log.w(TAG, "Notification deletion reconciliation failed", e)
        }
    }

    /**
     * Each response to my request to be checked on is its own row (the server writes one per
     * member who acknowledges, naming them in `acknowledged_by`). For each such row of [rows], by
     * row id: how many OTHER members had acknowledged the same request by then, from this page's
     * rows, so the row reads "Ana and 1 other are responding". The server lists the newest first
     * (by creation time, then id, both descending).
     */
    internal fun othersResponding(rows: List<com.pingolin.track.data.remote.dto.NotificationResponse>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        val answered = HashMap<String, MutableSet<String>>()
        for (row in rows.asReversed()) {
            if (row.notificationType != "sos_acknowledged") continue
            val sos = row.metadata?.get("sos_id")?.toString()?.let { AccountIds.canonical(it) } ?: continue
            // A row of an older server names nobody: it keeps the server's words, and counts for none.
            val by = row.metadata?.get("acknowledged_by")?.toString()?.let { AccountIds.canonical(it) } ?: continue
            val sofar = answered.getOrPut(sos) { HashSet() }
            sofar += by
            counts[row.id] = sofar.count { it != by }
        }
        return counts
    }

    /**
     * Parse an RFC3339/ISO-8601 instant (e.g. "yyyy-MM-ddTHH:mm:ssZ", with or without
     * fractional seconds) into epoch millis. Returns null on any failure, so the UI
     * cleanly falls back to the UTC day-bucket label.
     */
    private fun parseIsoToEpochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * "A place alert on <name> was removed because the place was deleted" when we still have the
     * place cached locally (the server is E2EE-blind to the name, and the place is already gone),
     * otherwise the server's generic copy. Keyed by place_id in the notification metadata.
     */
    private suspend fun placeAlertRemovedBody(genericBody: String?, metadata: Map<String, Any?>?): String? {
        val placeId = metadata?.get("place_id")?.toString()?.takeIf { it.isNotBlank() } ?: return genericBody
        val name = try {
            placeDao.getById(placeId)?.name
        } catch (e: Exception) {
            null
        }
        return if (!name.isNullOrBlank()) {
            "A place alert on $name was removed because the place was deleted"
        } else {
            genericBody
        }
    }

    /**
     * Best-effort E2EE personalisation for destination notification rows. Any failure
     * (missing keys, unknown circle, departed member) keeps the server's generic
     * title/body.
     */
    private suspend fun personalizeDestinationCopy(
        type: String,
        title: String,
        body: String?,
        metadata: Map<String, Any?>?
    ): Pair<String, String?> {
        if (metadata == null ||
            (type != "destination_shared" && type != "destination_arrival")
        ) return title to body

        return try {
            val meta = HashMap<String, String>()
            metadata.forEach { (k, v) -> if (v != null) meta[k] = v.toString() }
            val copy = destinationRepository.resolveNotificationCopy(meta)
            val actor = copy.actorName
            val label = copy.destLabel
            val isSelf = meta["self"] == "true"

            when {
                type == "destination_shared" && actor != null && label != null ->
                    title to "$actor created a destination for $label"
                type == "destination_shared" && actor != null ->
                    title to "$actor shared a new destination"
                type == "destination_shared" && label != null ->
                    title to "New destination: $label"
                type == "destination_arrival" && isSelf && label != null ->
                    title to "You arrived at $label"
                type == "destination_arrival" && !isSelf && actor != null ->
                    title to (if (label != null) "$actor arrived at $label" else "$actor arrived at their destination")
                type == "destination_arrival" && !isSelf && label != null ->
                    title to "Arrived at $label"
                else -> title to body
            }
        } catch (e: Exception) {
            title to body
        }
    }

    /**
     * Mark a notification as read.
     */
    suspend fun markRead(notificationId: String): Result<Unit> {
        // Local-only rows have no server counterpart; mark read locally and skip the network.
        if (notificationId.startsWith(LOCAL_ID_PREFIX)) {
            notificationDao.markRead(notificationId)
            return Result.success(Unit)
        }
        return try {
            val response = tracksApi.markNotificationRead(notificationId)
            if (response.isSuccessful) {
                notificationDao.markRead(notificationId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark notification as read"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a single notification (swipe-to-dismiss). Optimistic: removes the
     * local row first so the list updates instantly via the Room flow, then
     * deletes server-side. A 404 counts as success (already gone). On any other
     * server failure the local row is restored so the swiped item reappears
     * rather than silently resurrecting on the next refresh.
     */
    suspend fun deleteNotification(notificationId: String): Result<Unit> {
        // Local-only rows have no server counterpart; delete locally and skip the network.
        if (notificationId.startsWith(LOCAL_ID_PREFIX)) {
            notificationDao.deleteById(notificationId)
            return Result.success(Unit)
        }
        val existing = notificationDao.getById(notificationId)
        notificationDao.deleteById(notificationId)
        return try {
            val response = tracksApi.deleteNotification(notificationId)
            if (response.isSuccessful || response.code() == 404) {
                Result.success(Unit)
            } else {
                existing?.let { notificationDao.insert(it) }
                Result.failure(Exception("Failed to delete notification: ${response.code()}"))
            }
        } catch (e: Exception) {
            existing?.let { notificationDao.insert(it) }
            Result.failure(e)
        }
    }

    /**
     * Mark all notifications as read.
     */
    suspend fun markAllRead(): Result<Unit> {
        return try {
            val response = tracksApi.markAllNotificationsRead()
            if (response.isSuccessful) {
                notificationDao.markAllRead()
                Result.success(Unit)
            } else {
                Log.w(TAG, "markAllRead: server returned ${response.code()}")
                Result.failure(Exception("Failed to mark all notifications as read"))
            }
        } catch (e: Exception) {
            // Log it: a swallowed exception here would hide a response-parsing
            // failure — the server marks everything read, the client throws on
            // the body, and the user sees an unread list with no error anywhere. A
            // swallowed exception in a path whose only feedback is "the UI changes"
            // is indistinguishable from a dead button.
            Log.w(TAG, "markAllRead failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch unread count from server and return it.
     */
    suspend fun refreshUnreadCount(): Result<Long> {
        return try {
            val response = tracksApi.getUnreadNotificationCount()
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                Result.success(response.body()!!.data!!.count)
            } else {
                Result.failure(Exception("Failed to fetch unread count"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete old notifications to prevent unbounded database growth.
     * Keeps notifications from the last 90 days.
     */
    suspend fun cleanupOldNotifications() {
        val currentDayBucket = (System.currentTimeMillis() / 86400000L).toInt()
        val minDayBucket = currentDayBucket - 90
        notificationDao.deleteOlderThan(minDayBucket)
    }
}
