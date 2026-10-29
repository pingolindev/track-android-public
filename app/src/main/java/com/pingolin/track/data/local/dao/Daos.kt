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

package com.pingolin.track.data.local.dao

import androidx.room.*
import com.pingolin.track.data.local.entity.*
import kotlinx.coroutines.flow.Flow

/**
 * SQLite caps a single prepared statement at 999 host variables
 * (SQLITE_MAX_VARIABLE_NUMBER) on API 26–30, and an IN()/NOT IN() list binds one
 * variable per element. Chunk id lists below this so a large set can't throw
 * "too many SQL variables". Kept a little under 999 for headroom.
 */
private const val SQLITE_MAX_VARS = 900

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    fun observeById(id: String): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Delete
    suspend fun delete(user: UserEntity)
}

@Dao
interface CircleDao {
    @Query("SELECT circles.* FROM circles LEFT JOIN circle_sort_order ON circles.id = circle_sort_order.circleId ORDER BY COALESCE(circle_sort_order.sortOrder, 2147483647) ASC, circles.name ASC")
    fun observeAll(): Flow<List<CircleEntity>>

    @Query("SELECT circles.* FROM circles LEFT JOIN circle_sort_order ON circles.id = circle_sort_order.circleId ORDER BY COALESCE(circle_sort_order.sortOrder, 2147483647) ASC, circles.name ASC")
    suspend fun getAll(): List<CircleEntity>

    @Query("SELECT * FROM circles WHERE id = :id")
    suspend fun getById(id: String): CircleEntity?

    @Query("SELECT * FROM circles WHERE id = :id")
    fun observeById(id: String): Flow<CircleEntity?>

    // @Upsert, not @Insert(REPLACE). REPLACE is a DELETE + INSERT, so
    // re-storing a circle on a routine refresh would fire the ON DELETE
    // CASCADE on its children — wiping that circle's circle_members AND
    // locations rows for an instant before they repopulate. On a cold-start
    // map open that shows as members flickering 2→0→2 and a member's
    // marker vanishing for several seconds (observeLatestByCircle GROUP BY
    // userId transiently returns one fewer user while the table refills).
    // @Upsert updates the existing row in place (no DELETE, no cascade); a
    // genuine circle removal still goes through delete()/deleteById() and
    // cascades correctly.
    @Upsert
    suspend fun insert(circle: CircleEntity)

    @Upsert
    suspend fun insertAll(circles: List<CircleEntity>)

    @Delete
    suspend fun delete(circle: CircleEntity)

    @Query("DELETE FROM circles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CircleSortOrderDao {
    /** Persist the device-local circle ordering. REPLACE so re-saving the
     *  full ordered list just overwrites each row's position. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setOrder(orders: List<CircleSortOrderEntity>)
}

@Dao
interface CircleMemberDao {
    @Query("SELECT * FROM circle_members WHERE circleId = :circleId")
    fun observeByCircle(circleId: String): Flow<List<CircleMemberEntity>>

    /** Every circle's member list as last refreshed: whose destinations say they aren't sharing. */
    @Query("SELECT * FROM circle_members")
    fun observeAll(): Flow<List<CircleMemberEntity>>

    @Query("SELECT * FROM circle_members WHERE circleId = :circleId")
    suspend fun getByCircle(circleId: String): List<CircleMemberEntity>

    @Query("SELECT * FROM circle_members WHERE circleId = :circleId AND userId = :userId")
    suspend fun get(circleId: String, userId: String): CircleMemberEntity?

    /** How many members we hold for a circle. Zero means the roster is UNKNOWN
     *  locally, not that the circle is empty — nothing refreshes circle_members in
     *  the background, so a fresh install has none until a UI screen fetches them.
     *  Callers must not read "no row for this user" as "not a member" without first
     *  checking that we know the roster at all. */
    @Query("SELECT COUNT(*) FROM circle_members WHERE circleId = :circleId")
    suspend fun countByCircle(circleId: String): Int

    /** Any stored display name for a member, from whichever circle holds one: which may be the server's
     *  plaintext fallback, where this device couldn't open the name ([CircleMemberEntity.nameOpened] false).
     *  Never for a notification: [anyOpenedDisplayName]. */
    @Query("SELECT displayName FROM circle_members WHERE userId = :userId LIMIT 1")
    suspend fun anyDisplayName(userId: String): String?

    /** A name this device OPENED with a circle's key ([CircleMemberEntity.nameOpened]), from whichever circle
     * holds one: the only kind a notification may say. Subscriber-side place alerts are evaluated per
     * PLACE, not per circle — a personal place has no circle at all — so the lookup can't be scoped to
     * one. */
    @Query("SELECT displayName FROM circle_members WHERE userId = :userId AND nameOpened = 1 LIMIT 1")
    suspend fun anyOpenedDisplayName(userId: String): String?

    /** This user's own membership row in every circle whose roster we hold —
     *  the only place their ROLE is recorded. The circles table carries a bare
     *  `isOwner` boolean and so cannot answer "am I an admin here?".
     *
     *  Same caveat as [countByCircle]: a circle ABSENT from this result means
     *  its roster is unknown locally, not that the user is a plain member of
     *  it. Use it to decide what to OFFER, never to take something away.
     *
     *  circle_members is indexed on userId, so this stays a lookup rather than
     *  a scan of every member of every circle. */
    @Query("SELECT * FROM circle_members WHERE userId = :userId")
    fun observeMemberships(userId: String): Flow<List<CircleMemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: CircleMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<CircleMemberEntity>)

    @Delete
    suspend fun delete(member: CircleMemberEntity)

    @Query("DELETE FROM circle_members WHERE circleId = :circleId")
    suspend fun deleteByCircle(circleId: String)

    /**
     * Atomically replace a circle's members. Calling deleteByCircle + insertAll
     * separately makes observeByCircle emit an empty list in between, which on
     * the map flashes peer markers as "U" (Unknown) for a frame before the real
     * initial — doing both in one transaction means the Flow only emits the
     * final state.
     */
    @Transaction
    suspend fun replaceForCircle(circleId: String, members: List<CircleMemberEntity>) {
        deleteByCircle(circleId)
        insertAll(members)
    }
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE userId = :userId")
    fun observeByUser(userId: String): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getById(id: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: DeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<DeviceEntity>)

    @Delete
    suspend fun delete(device: DeviceEntity)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE circleId = :circleId ORDER BY sortOrder ASC, name ASC")
    fun observeByCircle(circleId: String): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE circleId = :circleId ORDER BY sortOrder ASC, name ASC")
    suspend fun getByCircle(circleId: String): List<PlaceEntity>

    /**
     * Observe places for a circle including:
     *   1. Places owned by this circle
     *   2. Personal places (circleId = 'self') — always visible to the user
     *      regardless of which circle they're viewing. Without this, a
     *      personal place created via long-press on the map (which Android
     *      always files as personal) would be invisible the moment the user
     *      switches to any shared circle view.
     *   3. Places explicitly shared with this circle from another circle
     *      (LIKE-match on the comma-separated sharedCircles column).
     */
    @Query("""
        SELECT * FROM places
        WHERE circleId = :circleId
           OR circleId = 'self'
           OR sharedCircles LIKE '%' || :circleId || '%'
        ORDER BY sortOrder ASC, name ASC
    """)
    fun observeByCircleIncludingShared(circleId: String): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<PlaceEntity>>

    /** Snapshot of ALL locally-cached places (suspend, non-observing). The
     *  boundary reload builds its geofence union from these RETAINED rows so a
     *  place whose circle key was transiently missing on the current refresh
     *  tick — but decrypted+stored on an earlier tick — stays monitored. */
    @Query("SELECT * FROM places ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getById(id: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PlaceEntity>

    /**
     * Chunked [getByIds]. A single IN(:ids) binds one host variable per id and
     * SQLite caps a statement at 999 (API 26–30), so an unchunked lookup over
     * >999 ids throws "too many SQL variables". Chunk to stay under the cap and
     * concatenate the pages. Empty in ⇒ empty out (no IN () emitted).
     */
    @Transaction
    suspend fun getByIdsChunked(ids: List<String>): List<PlaceEntity> =
        ids.chunked(SQLITE_MAX_VARS).flatMap { getByIds(it) }

    /** All locally-cached place ids (cheap, id-only) — used to compute the prune
     *  delete set as (all local ids − server-returned ids). */
    @Query("SELECT id FROM places")
    suspend fun getAllIds(): List<String>

    /**
     * MUST be @Upsert, never @Insert(REPLACE).
     *
     * `places` is the parent of `place_alerts`, whose foreign key is
     * ON DELETE CASCADE (Entities.kt), and Room runs with
     * `PRAGMA foreign_keys = ON`. SQLite implements INSERT OR REPLACE as
     * DELETE-then-INSERT, so REPLACE-writing an EXISTING place row deletes it
     * first — firing the cascade and silently wiping every alert on that place —
     * even though the row is immediately re-inserted with the same id.
     *
     * That is not hypothetical: creating the first alert on a place flips
     * `alertsEnabled` server-side, so the very next refreshPlaces sees changed
     * content, rewrites the row, and destroys the alert that caused the change.
     *
     * @Upsert issues INSERT-or-UPDATE and never deletes the parent row, so
     * children survive. Room 2.8.4.
     */
    @Upsert
    suspend fun upsert(place: PlaceEntity)

    @Upsert
    suspend fun upsertAll(places: List<PlaceEntity>)

    @Update
    suspend fun update(place: PlaceEntity)

    @Update
    suspend fun updateAll(places: List<PlaceEntity>)

    @Query("UPDATE places SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /**
     * Update sort order for multiple places in a single transaction.
     * This prevents intermediate Flow emissions during batch updates.
     */
    @Transaction
    suspend fun updateSortOrderBatch(idToSortOrder: Map<String, Int>) {
        idToSortOrder.forEach { (id, sortOrder) ->
            updateSortOrder(id, sortOrder)
        }
    }

    @Delete
    suspend fun delete(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM places WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /**
     * Chunked [deleteByIds]. The prune computes the delete set explicitly as
     * (all local ids − server-returned ids) and deletes it in ≤999-variable
     * chunks — instead of a single unchunked NOT IN(:keepIds), which both
     * overflows SQLite's variable cap above ~999 places (API 26–30) and emits an
     * invalid NOT IN () when the server returns zero places. Empty in ⇒ no-op.
     */
    @Transaction
    suspend fun deleteByIdsChunked(ids: List<String>): Int {
        var deleted = 0
        ids.chunked(SQLITE_MAX_VARS).forEach { deleted += deleteByIds(it) }
        return deleted
    }
}

@Dao
interface PlaceAlertDao {
    @Query("SELECT * FROM place_alerts WHERE placeId = :placeId")
    fun observeByPlace(placeId: String): Flow<List<PlaceAlertEntity>>

    @Query("SELECT * FROM place_alerts ORDER BY dayBucket DESC")
    fun observeAll(): Flow<List<PlaceAlertEntity>>

    /** Distinct place ids the CURRENT user has an ACTIVE alert on. place_alerts holds
     *  only this user's own subscriptions, so this is a per-user signal — used to draw
     *  the map geofence ring per user, not per place. */
    @Query("SELECT DISTINCT placeId FROM place_alerts WHERE isActive = 1")
    fun observeActiveAlertPlaceIds(): Flow<List<String>>

    @Query("SELECT * FROM place_alerts WHERE isActive = 1")
    suspend fun getActive(): List<PlaceAlertEntity>

    /** Active alerts on a specific place — used to route a personal-place crossing
     * under the circle its alert actually lives in (PlaceAlertEntity.circleId),
     * which fires the alert WITHOUT the place being shared. See
     * reportCrossingFromMonitor. */
    @Query("SELECT * FROM place_alerts WHERE placeId = :placeId AND isActive = 1")
    suspend fun getActiveByPlace(placeId: String): List<PlaceAlertEntity>

    @Query("SELECT * FROM place_alerts WHERE id = :id")
    suspend fun getById(id: String): PlaceAlertEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: PlaceAlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alerts: List<PlaceAlertEntity>)

    @Update
    suspend fun update(alert: PlaceAlertEntity)

    @Delete
    suspend fun delete(alert: PlaceAlertEntity)

    @Query("DELETE FROM place_alerts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT id FROM place_alerts")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM place_alerts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Chunked [deleteByIds] — one host variable per id, and SQLite caps a statement
     * at 999 on API 26–30. Mirrors PlaceDao.deleteByIdsChunked.
     */
    @Transaction
    suspend fun deleteByIdsChunked(ids: List<String>) {
        ids.chunked(SQLITE_MAX_VARS).forEach { deleteByIds(it) }
    }
}

/** A fix held with alerts on its member refused: [LocationDao.refusedAt]. */
data class RefusedFix(val userId: String, val timestamp: Long)

@Dao
interface LocationDao {
    companion object {
        const val MS_PER_DAY = 86400000L
        /** Maximum age in days for cached locations (90 days) */
        const val MAX_LOCATION_AGE_DAYS = 90
    }

    @Query("SELECT * FROM locations WHERE circleId = :circleId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getByTimeRange(circleId: String, startTime: Long, endTime: Long): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getPendingSync(): List<LocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(locations: List<LocationEntity>)

    @Query("UPDATE locations SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /**
     * Latest live location per user in a circle.
     *
     * Two-stage selection:
     *   1. Pick the largest `timestamp` per user (the actual event
     *      time, which is the right freshness signal).
     *   2. Among rows tied on that max timestamp, pick the largest
     *      `id` (autoincrement) — the most recently inserted row.
     *
     * Why both: the history-fetch path inserts decrypted historical
     * rows into the same table with fresh autoincrement ids, so keying
     * on MAX(id) alone would let history scrubs poison the live-marker
     * query — the marker would snap to where the user was 2h / 24h / 7d
     * ago (often off-screen, presenting as "no markers visible").
     * Keying on MAX(timestamp) alone could return more than one row per
     * user if rows ever tie on the timestamp, which confuses the map
     * clustering layer and produces an empty "blank spot" zoom target.
     * MAX(id) within MAX(timestamp) picks exactly one canonical row per
     * user.
     */
    @Query("""
        SELECT l.* FROM locations l
        WHERE l.circleId = :circleId
          AND l.id IN (
            SELECT MAX(l2.id) FROM locations l2
            WHERE l2.circleId = :circleId AND l2.keyCurrent = 1
              AND l2.timestamp = (
                SELECT MAX(timestamp) FROM locations
                WHERE circleId = :circleId AND userId = l2.userId AND keyCurrent = 1
              )
            GROUP BY l2.userId
          )
    """)
    fun observeLatestByCircle(circleId: String): Flow<List<LocationEntity>>

    /**
     * A member's served "no" to alerts on them (`allow_alerts_on_me` false), taken from a row the replay
     * guard dropped: their newest current row in the circle, the one [observeLatestByCircle] gives the
     * alert passes, takes it. Lowers only, and only the consent: the fix, its time and its battery stay.
     * A member with no row held gets none. Returns the rows lowered.
     */
    @Query("""
        UPDATE locations SET allowAlertsOnMe = 0
        WHERE circleId = :circleId AND userId = :userId AND keyCurrent = 1
          AND (allowAlertsOnMe IS NULL OR allowAlertsOnMe != 0)
          AND timestamp = (
            SELECT MAX(timestamp) FROM locations
            WHERE circleId = :circleId AND userId = :userId AND keyCurrent = 1
          )
    """)
    suspend fun lowerAlertsConsent(circleId: String, userId: String): Int

    /**
     * Which of these fix times in the circle are held with alerts on the member refused
     * (`allowAlertsOnMe` false): a "no" held stays when the same fix is stored again.
     */
    @Query("SELECT userId, timestamp FROM locations WHERE circleId = :circleId AND timestamp IN (:timestamps) AND allowAlertsOnMe = 0")
    suspend fun refusedAt(circleId: String, timestamps: List<Long>): List<RefusedFix>

    // ============================================================================
    // Maintenance operations
    // ============================================================================

    /**
     * Get total count of cached locations.
     */
    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getCount(): Int

    /**
     * Cleanup old cached locations to prevent unbounded database growth.
     * Deletes locations older than MAX_LOCATION_AGE_DAYS (90 days).
     *
     * @return Number of deleted rows
     */
    @Query("DELETE FROM locations WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int

    /**
     * Remove cached locations for users no longer in the circle's member list.
     * Called after a circle_members refresh so deleted members' last uploads
     * stop rendering as "U" (Unknown) on the map.
     */
    @Query("DELETE FROM locations WHERE circleId = :circleId AND userId NOT IN (:keepUserIds)")
    suspend fun deleteForMissingMembers(circleId: String, keepUserIds: List<String>): Int

    /** Each spelling of a member id the circle's cached rows carry (the history-off purge matches them as one). */
    @Query("SELECT DISTINCT userId FROM locations WHERE circleId = :circleId")
    suspend fun userIdsIn(circleId: String): List<String>

    /**
     * A member's cached positions in a circle, but for the one the map shows: their newest CURRENT
     * row, as [observeLatestByCircle] picks it (history off).
     */
    @Query("""
        DELETE FROM locations
        WHERE circleId = :circleId AND userId = :userId
          AND id NOT IN (
            SELECT id FROM locations
            WHERE circleId = :circleId AND userId = :userId AND keyCurrent = 1
            ORDER BY timestamp DESC, id DESC
            LIMIT 1
          )
    """)
    suspend fun deleteAllButNewest(circleId: String, userId: String): Int

    /** All of a member's cached positions in a circle (history and real-time sharing both off). */
    @Query("DELETE FROM locations WHERE circleId = :circleId AND userId = :userId")
    suspend fun deleteMember(circleId: String, userId: String): Int

    /**
     * Perform periodic cleanup of old locations.
     * Should be called periodically (e.g., daily) to prevent database bloat.
     *
     * @return Number of locations cleaned up
     */
    suspend fun cleanupOldLocations(): Int {
        val cutoffTime = System.currentTimeMillis() - (MAX_LOCATION_AGE_DAYS * MS_PER_DAY)
        return deleteOlderThan(cutoffTime)
    }
}

@Dao
interface CheckInDao {
    @Query("SELECT * FROM check_ins WHERE circleId = :circleId ORDER BY timestamp DESC LIMIT :limit")
    fun observeByCircle(circleId: String, limit: Int = 50): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE isSynced = 0")
    suspend fun getPendingSync(): List<CheckInEntity>

    // @Upsert (update-in-place) rather than @Insert(REPLACE) (delete+insert) so a
    // routine refresh doesn't momentarily drop the row from observeByCircle and
    // flicker the list. (No FK cascade on check_ins, so this is purely about
    // stream stability — same reasoning as CircleDao.)
    @Upsert
    suspend fun insert(checkIn: CheckInEntity)

    @Query("UPDATE check_ins SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}

@Dao
interface SosAlertDao {
    /**
     * The Safety screen's list.
     *
     * A dismissed alert is NOT dropped immediately — it stays, greyed and
     * counting down, until [graceCutoff] (now minus
     * SosAlertEntity.DISMISS_GRACE_MS) so an accidental dismissal of a live
     * emergency can be undone. The map has no such grace: it hides a dismissed
     * alert at once, which is the whole point of the gesture.
     *
     * Acknowledging never removes a row — a responder who acknowledged still
     * needs the way back to the location; the row shows that state instead.
     *
     * Dismissed rows sort last so the live ones stay at the top.
     */
    @Query(
        "SELECT * FROM sos_alerts WHERE status = 'active' " +
        "AND (dismissedByMe = 0 OR dismissedAt > :graceCutoff) " +
        "ORDER BY dismissedByMe ASC, createdAt DESC"
    )
    fun observeActive(graceCutoff: Long): Flow<List<SosAlertEntity>>

    /**
     * What the map draws (marker + off-screen pointer). Stricter than
     * [observeActive]: the icon shows until acknowledged, either on the
     * Safety screen or on the map marker, so either gesture clears it.
     * Alerts with no decryptable coordinates are excluded — there is
     * nowhere to put them, and a marker at (0, 0) would be a lie.
     */
    @Query(
        "SELECT * FROM sos_alerts " +
        "WHERE status = 'active' AND dismissedByMe = 0 AND acknowledgedByMe = 0 " +
        "AND latitude IS NOT NULL AND longitude IS NOT NULL " +
        "ORDER BY createdAt DESC"
    )
    fun observeActiveMappable(): Flow<List<SosAlertEntity>>

    @Query("SELECT * FROM sos_alerts WHERE circleId = :circleId ORDER BY createdAt DESC LIMIT :limit")
    fun observeByCircle(circleId: String, limit: Int = 20): Flow<List<SosAlertEntity>>

    /** How many SOS alerts [userId] raised in [circleId] are still active. */
    @Query("SELECT COUNT(*) FROM sos_alerts WHERE userId = :userId AND circleId = :circleId AND status = 'active'")
    suspend fun countActiveRaisedBy(userId: String, circleId: String): Int

    @Query("SELECT * FROM sos_alerts WHERE id = :id")
    suspend fun getById(id: String): SosAlertEntity?

    /** Ids of every locally-active alert. Snapshotted before a refresh so the
     *  stale sweep can tell "the server closed it" from "it didn't exist yet
     *  when the request went out". */
    @Query("SELECT id FROM sos_alerts WHERE status = 'active'")
    suspend fun getActiveIds(): List<String>

    /** Every locally-active alert: the raiser's own are resealed after a rotation. */
    @Query("SELECT * FROM sos_alerts WHERE status = 'active'")
    suspend fun getActive(): List<SosAlertEntity>

    /** An alert the receivers' rules hide here: not listed, not on the map. */
    @Query("DELETE FROM sos_alerts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sos_alerts SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: SosAlertEntity)

    @Update
    suspend fun update(alert: SosAlertEntity)
}

@Dao
interface PrivacySettingDao {
    @Query("SELECT * FROM privacy_settings WHERE circleId = :circleId")
    fun observe(circleId: String): Flow<PrivacySettingEntity?>

    @Query("SELECT * FROM privacy_settings WHERE circleId = :circleId")
    suspend fun get(circleId: String): PrivacySettingEntity?

    /**
     * Circles whose sharing is paused as of [currentTime] — the SQL twin of
     * [com.pingolin.track.data.local.entity.isSharingPausedAt]. `pausedUntil IS NULL`
     * is a pause with no deadline ("until I resume"), NOT the absence of a pause, so
     * it must be matched, and a row can only be paused in `paused` mode.
     */
    @Query(
        "SELECT * FROM privacy_settings WHERE sharingMode = 'paused' " +
            "AND (pausedUntil IS NULL OR pausedUntil > :currentTime)"
    )
    suspend fun getActivePauses(currentTime: Long): List<PrivacySettingEntity>

    /**
     * Batch query to get all privacy settings for multiple circles in a single query.
     * This avoids N+1 query problem when iterating over circles.
     */
    @Query("SELECT * FROM privacy_settings WHERE circleId IN (:circleIds)")
    suspend fun getAllForCircles(circleIds: List<String>): List<PrivacySettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: PrivacySettingEntity)

    @Update
    suspend fun update(setting: PrivacySettingEntity)

    @Query("DELETE FROM privacy_settings WHERE circleId = :circleId")
    suspend fun delete(circleId: String)
}

@Dao
interface PendingLocationDao {
    companion object {
        /** Maximum number of pending locations to store. Oldest entries are deleted when exceeded. */
        const val MAX_QUEUE_SIZE = 1000
        /** Maximum age in milliseconds for pending locations (24 hours) */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    @Query("SELECT * FROM pending_locations ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 100): List<PendingLocationEntity>

    @Query("SELECT COUNT(*) FROM pending_locations")
    suspend fun getCount(): Int

    @Insert
    suspend fun insert(location: PendingLocationEntity)

    @Delete
    suspend fun delete(location: PendingLocationEntity)

    @Query("DELETE FROM pending_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_locations SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    // NOTE: deliberately no retry-count eviction. With 15-min periodic sync +
    // exponential backoff, a user offline for ~1 hour would exhaust a small
    // retry budget and lose every queued location for the rest of a longer
    // outage. Age (MAX_AGE_MS = 24h) and size (MAX_QUEUE_SIZE = 1000) bounds
    // cap the queue. The retryCount column is incremented for diagnostics and
    // backoff decisions, but does not dictate eviction.

    /**
     * Delete oldest entries when queue exceeds max size.
     * Keeps only the most recent MAX_QUEUE_SIZE entries.
     */
    @Query("""
        DELETE FROM pending_locations
        WHERE id NOT IN (
            SELECT id FROM pending_locations
            ORDER BY timestamp DESC
            LIMIT :maxSize
        )
    """)
    suspend fun trimToSize(maxSize: Int = MAX_QUEUE_SIZE)

    /**
     * Delete entries older than the specified age.
     */
    @Query("DELETE FROM pending_locations WHERE createdAt < :minCreatedAt")
    suspend fun deleteOlderThan(minCreatedAt: Long)

    /**
     * Cleanup: Remove old entries and trim to max size.
     * Should be called periodically to prevent unbounded growth.
     */
    @Transaction
    suspend fun cleanup() {
        val cutoffTime = System.currentTimeMillis() - MAX_AGE_MS
        deleteOlderThan(cutoffTime)
        trimToSize(MAX_QUEUE_SIZE)
    }
}

@Dao
interface QueuedCircleLocationDao {
    companion object {
        /** The same cap on all three clients. */
        const val MAX_AGE_MS = 48 * 60 * 60 * 1000L

        /** And at most this many per circle, oldest dropped first (as on iPhone). */
        const val MAX_PER_CIRCLE = 5_000
    }

    @Insert
    suspend fun insert(row: QueuedCircleLocationEntity)

    /** Oldest first, so history arrives in the order it happened. */
    @Query("SELECT * FROM queued_circle_locations WHERE circleId = :circleId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun oldest(circleId: String, limit: Int = 100): List<QueuedCircleLocationEntity>

    @Query("SELECT DISTINCT circleId FROM queued_circle_locations")
    suspend fun circleIds(): List<String>

    @Query("SELECT COUNT(*) FROM queued_circle_locations WHERE circleId = :circleId")
    suspend fun count(circleId: String): Int

    @Query("DELETE FROM queued_circle_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** The 48-hour cap, by when each fix was taken. */
    @Query("DELETE FROM queued_circle_locations WHERE timestamp < :cutoff")
    suspend fun dropOlderThan(cutoff: Long): Int

    @Query("DELETE FROM queued_circle_locations WHERE circleId = :circleId")
    suspend fun deleteCircle(circleId: String)

    /** Drop [count] of [circleId]'s oldest kept points. */
    @Query(
        "DELETE FROM queued_circle_locations WHERE id IN " +
            "(SELECT id FROM queued_circle_locations WHERE circleId = :circleId ORDER BY timestamp ASC LIMIT :count)"
    )
    suspend fun dropOldest(circleId: String, count: Int)
}

@Dao
interface PendingCrossingDao {
    companion object {
        /** Size bound, as [PendingLocationDao.MAX_QUEUE_SIZE]. Far smaller because
         *  a crossing is a transition, not a sample: a device producing hundreds of
         *  queued crossings is malfunctioning, not offline. */
        const val MAX_QUEUE_SIZE = 200

        /**
         * Age bound: 2 hours, NOT the 24 used for locations, and the difference is
         * the point.
         *
         * A queued location replayed a day later is still true — it carries its own
         * timestamp and lands in history. A queued CROSSING replayed a day later
         * produces a notification, and the value of "Sam arrived at Home" decays to
         * nothing long before a day is up. This is the same bound and the same
         * reasoning as the subscriber-side detector's
         * `PlaceAlertRules.STALENESS_WINDOW_MS`: long enough to cover the outages
         * that actually produce catch-up batches, short enough that a crossing
         * surfaced as "2h ago" still reads as something worth knowing rather than
         * archaeology.
         *
         * Deliberately NOT a retry-count bound — see the note in
         * [PendingLocationDao] about a retry budget being exhausted within an hour.
         */
        const val MAX_AGE_MS = 2 * 60 * 60 * 1000L
    }

    /** Oldest first: a crossing's order is its meaning, and an enter replayed after
     *  its own exit reads as a member still being somewhere they left. */
    @Query("SELECT * FROM pending_crossings ORDER BY occurredAtMs ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 50): List<PendingCrossingEntity>

    @Query("SELECT COUNT(*) FROM pending_crossings")
    suspend fun getCount(): Int

    @Insert
    suspend fun insert(crossing: PendingCrossingEntity)

    @Query("DELETE FROM pending_crossings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_crossings SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("DELETE FROM pending_crossings WHERE occurredAtMs < :minOccurredAtMs")
    suspend fun deleteOlderThan(minOccurredAtMs: Long)

    @Query(
        """
        DELETE FROM pending_crossings
        WHERE id NOT IN (
            SELECT id FROM pending_crossings
            ORDER BY occurredAtMs DESC
            LIMIT :maxSize
        )
    """
    )
    suspend fun trimToSize(maxSize: Int = MAX_QUEUE_SIZE)

    /** Bounded on the CROSSING time, not `createdAt`: the two differ by however
     *  long the device sat offline, and it is the crossing's age that decides
     *  whether replaying it still tells anyone something useful. */
    @Transaction
    suspend fun cleanup() {
        deleteOlderThan(System.currentTimeMillis() - MAX_AGE_MS)
        trimToSize(MAX_QUEUE_SIZE)
    }
}

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destinations WHERE circleId = :circleId ORDER BY createdDayBucket DESC")
    fun observeByCircle(circleId: String): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations ORDER BY createdDayBucket DESC")
    fun observeAll(): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations WHERE status = 'active' ORDER BY createdDayBucket DESC")
    fun observeActive(): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations WHERE id = :id")
    suspend fun getById(id: String): DestinationEntity?

    @Query("SELECT * FROM destinations WHERE status = :status ORDER BY createdDayBucket DESC")
    suspend fun getByStatus(status: String): List<DestinationEntity>

    @Upsert
    suspend fun insert(destination: DestinationEntity)

    @Upsert
    suspend fun insertAll(destinations: List<DestinationEntity>)

    @Update
    suspend fun update(destination: DestinationEntity)

    @Delete
    suspend fun delete(destination: DestinationEntity)

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun deleteById(id: String)

    /** All locally-cached destination ids (cheap, id-only) — used to compute the
     *  prune delete set as (all local ids − server-returned ids). */
    @Query("SELECT id FROM destinations")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM destinations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /**
     * Chunked [deleteByIds]. The prune computes the delete set explicitly as
     * (all local ids − server-returned ids) and deletes it in ≤999-variable
     * chunks — instead of a single unchunked NOT IN(:keepIds), which both
     * overflows SQLite's variable cap above ~999 rows (API 26–30) and emits an
     * invalid NOT IN () when the server returns zero destinations. Empty in ⇒
     * no-op. Mirrors PlaceDao.deleteByIdsChunked.
     */
    @Transaction
    suspend fun deleteByIdsChunked(ids: List<String>): Int {
        var deleted = 0
        ids.chunked(SQLITE_MAX_VARS).forEach { deleted += deleteByIds(it) }
        return deleted
    }
}

@Dao
interface InactivityAlertDao {
    @Query("SELECT * FROM inactivity_alerts ORDER BY dayBucket DESC")
    fun observeAll(): Flow<List<InactivityAlertEntity>>

    @Query("SELECT * FROM inactivity_alerts WHERE circleId = :circleId ORDER BY dayBucket DESC")
    fun observeByCircle(circleId: String): Flow<List<InactivityAlertEntity>>

    @Query("SELECT * FROM inactivity_alerts WHERE isActive = 1 ORDER BY dayBucket DESC")
    fun observeActive(): Flow<List<InactivityAlertEntity>>

    @Query("SELECT * FROM inactivity_alerts WHERE id = :id")
    suspend fun getById(id: String): InactivityAlertEntity?

    @Query("SELECT * FROM inactivity_alerts WHERE isActive = 1")
    suspend fun getActive(): List<InactivityAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: InactivityAlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alerts: List<InactivityAlertEntity>)

    @Update
    suspend fun update(alert: InactivityAlertEntity)

    @Delete
    suspend fun delete(alert: InactivityAlertEntity)

    @Query("DELETE FROM inactivity_alerts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM inactivity_alerts")
    suspend fun getAll(): List<InactivityAlertEntity>

    /**
     * A refresh's writes as one: nothing at all when [stillFor] says the account isn't the one the
     * refresh read for any more. Sign-out ends the session before it empties the tables, and the
     * emptying can't run inside this, so another account's alerts never land in the next one's
     * tables. False when nothing was written for that reason.
     */
    @Transaction
    suspend fun applyRefresh(changed: List<InactivityAlertEntity>, gone: List<String>, stillFor: () -> Boolean): Boolean {
        if (!stillFor()) return false
        if (changed.isNotEmpty()) insertAll(changed)
        gone.forEach { deleteById(it) }
        return true
    }
}

@Dao
interface AlertRuleDao {
    @Query("SELECT * FROM alert_rules ORDER BY dayBucket DESC")
    fun observeAll(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE circleId = :circleId ORDER BY dayBucket DESC")
    fun observeByCircle(circleId: String): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE ruleType = :ruleType ORDER BY dayBucket DESC")
    fun observeByType(ruleType: String): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE isActive = 1 ORDER BY dayBucket DESC")
    fun observeActive(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE id = :id")
    suspend fun getById(id: String): AlertRuleEntity?

    @Query("SELECT * FROM alert_rules WHERE isActive = 1")
    suspend fun getActive(): List<AlertRuleEntity>

    /** Active rules of one type — what the on-device evaluator reads when a wake-up lands. */
    @Query("SELECT * FROM alert_rules WHERE isActive = 1 AND ruleType = :ruleType")
    suspend fun getActiveByType(ruleType: String): List<AlertRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AlertRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<AlertRuleEntity>)

    @Update
    suspend fun update(rule: AlertRuleEntity)

    @Delete
    suspend fun delete(rule: AlertRuleEntity)

    @Query("DELETE FROM alert_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM alert_rules")
    suspend fun getAll(): List<AlertRuleEntity>

    /** As [InactivityAlertDao.applyRefresh]: a refresh's writes as one, for the account it read for only. */
    @Transaction
    suspend fun applyRefresh(changed: List<AlertRuleEntity>, gone: List<String>, stillFor: () -> Boolean): Boolean {
        if (!stillFor()) return false
        if (changed.isNotEmpty()) insertAll(changed)
        gone.forEach { deleteById(it) }
        return true
    }
}

@Dao
interface NotificationDao {
    // Newest first: by day, then by the exact time within the day. Ordered by day alone, one day's rows
    // would come back in no set order, an earlier one over a later one. A row without a time (an older
    // cached one) goes after its day's timed rows: SQLite sorts NULL below every value.
    @Query("SELECT * FROM notifications ORDER BY createdDayBucket DESC, createdAt DESC LIMIT 200")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE isRead = 0 ORDER BY createdDayBucket DESC, createdAt DESC")
    fun observeUnread(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE circleId = :circleId ORDER BY createdDayBucket DESC, createdAt DESC")
    fun observeByCircle(circleId: String): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: String): NotificationEntity?

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    suspend fun getUnreadCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<NotificationEntity>)

    @Update
    suspend fun update(notification: NotificationEntity)

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllRead()

    @Delete
    suspend fun delete(notification: NotificationEntity)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete notifications older than a certain day bucket.
     * Used for periodic cleanup to prevent unbounded growth.
     */
    @Query("DELETE FROM notifications WHERE createdDayBucket < :minDayBucket")
    suspend fun deleteOlderThan(minDayBucket: Int)

    // ---- Deletion reconciliation (server is authoritative) ------------------
    //
    // The refresh is insertAll + REPLACE, which inserts and updates but never
    // removes a local row the server has stopped returning, so without these
    // queries a notification deleted on another platform would linger here
    // forever.
    //
    // All three queries EXCLUDE `local-%` ids. Those are client-evaluated
    // place-alert rows with no server counterpart (see
    // NotificationRepository.LOCAL_ID_PREFIX / PlaceAlertEvaluator) — the server
    // has never heard of them, so "absent from the response" must not mean
    // "deleted" for those.

    /** Server-backed rows absent from [keepIds] — use only when the page is the COMPLETE list. */
    @Query("DELETE FROM notifications WHERE id NOT IN (:keepIds) AND id NOT LIKE 'local-%'")
    suspend fun deleteServerRowsNotIn(keepIds: List<String>): Int

    /**
     * Server-backed rows absent from [keepIds] and STRICTLY newer than
     * [afterDayBucket] — the bounded form, for when more pages may exist.
     * Strictly-greater, not >=, because the boundary bucket may be split across
     * the page edge: rows sharing the oldest returned bucket could be real rows
     * the page simply did not reach.
     */
    @Query("DELETE FROM notifications WHERE id NOT IN (:keepIds) AND id NOT LIKE 'local-%' AND createdDayBucket > :afterDayBucket")
    suspend fun deleteServerRowsNotInSince(keepIds: List<String>, afterDayBucket: Int): Int

    /** Every server-backed row — for when the server returns an empty first page. */
    @Query("DELETE FROM notifications WHERE id NOT LIKE 'local-%'")
    suspend fun deleteAllServerRows(): Int
}

@Dao
interface LocalBatteryEventDao {
    @Query("SELECT * FROM local_battery_events ORDER BY triggeredAt DESC LIMIT 200")
    fun observeAll(): Flow<List<LocalBatteryEventEntity>>

    /** The still-open (unresolved) firing for a (rule, member), if any — the fire-once marker. */
    @Query("SELECT * FROM local_battery_events WHERE alertRuleId = :ruleId AND targetUserId = :userId AND resolvedAt IS NULL LIMIT 1")
    suspend fun getOpen(ruleId: String, userId: String): LocalBatteryEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: LocalBatteryEventEntity)

    @Query("UPDATE local_battery_events SET resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun resolve(id: String, resolvedAt: Long)

    @Query("DELETE FROM local_battery_events WHERE triggeredAt < :minEpochMillis")
    suspend fun deleteOlderThan(minEpochMillis: Long)
}

@Dao
interface LocalInactivityEventDao {
    @Query("SELECT * FROM local_inactivity_events ORDER BY triggeredAt DESC LIMIT 200")
    fun observeAll(): Flow<List<LocalInactivityEventEntity>>

    /** The still-open (unresolved) firing for a (rule, member), if any — the fire-once marker. */
    @Query("SELECT * FROM local_inactivity_events WHERE inactivityAlertId = :ruleId AND targetUserId = :userId AND resolvedAt IS NULL LIMIT 1")
    suspend fun getOpen(ruleId: String, userId: String): LocalInactivityEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: LocalInactivityEventEntity)

    @Query("UPDATE local_inactivity_events SET resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun resolve(id: String, resolvedAt: Long)

    @Query("DELETE FROM local_inactivity_events WHERE triggeredAt < :minEpochMillis")
    suspend fun deleteOlderThan(minEpochMillis: Long)
}

/** When this phone last saw each watched member not sharing in a circle; see [MemberNotSharingSeenEntity]. */
@Dao
interface MemberNotSharingSeenDao {
    @Query("SELECT seenUntil FROM member_not_sharing_seen WHERE circleId = :circleId AND userId = :userId")
    suspend fun get(circleId: String, userId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MemberNotSharingSeenEntity)

    @Query("DELETE FROM member_not_sharing_seen WHERE seenUntil < :minEpochMillis")
    suspend fun deleteOlderThan(minEpochMillis: Long)
}

@Dao
interface PlaceAlertMemberStateDao {
    @Query("SELECT * FROM place_alert_member_state WHERE placeId = :placeId AND userId = :userId")
    suspend fun get(placeId: String, userId: String): PlaceAlertMemberStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PlaceAlertMemberStateEntity)

    @Query("DELETE FROM place_alert_member_state WHERE placeId = :placeId")
    suspend fun deleteByPlace(placeId: String)

    @Query("DELETE FROM place_alert_member_state")
    suspend fun deleteAll()
}

@Dao
interface PlaceAlertEventDao {
    /**
     * Atomic claim: returns the new row id, or -1 when a row with this crossing
     * identity already exists. The IGNORE conflict strategy makes this a
     * test-and-set rather than a racy check-then-insert, which is what gives
     * the "at most once, EVER" guarantee its teeth across restarts and
     * overlapping batches.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(event: PlaceAlertEventEntity): Long

    /**
     * Has EITHER path already surfaced (not silently folded) this
     * (place, member, transition) since [since]?
     *
     * This is the CROSS-path double-notify guard: the server-routed push carries no
     * crossing timestamp, so it cannot match a local row by exact id and has to
     * match within a window instead.
     *
     * [fromSource] MUST be the OTHER path. Matching a path against its own rows is
     * an error, not extra safety: this window says "some crossing of this shape
     * happened recently", which is exactly what a genuine re-crossing looks like.
     * Without the source filter, leaving home and returning within the window would
     * get no arrival alert at all — the second, real enter would match the first
     * enter's own row. Each path already de-duplicates ITSELF exactly: the local path
     * via the ledger primary key (which includes crossedAt), the server path via the
     * notification dedupe key.
     *
     * Silent rows are excluded — a crossing that only folded into history must
     * never suppress a genuine live one.
     */
    @Query(
        "SELECT COUNT(*) FROM place_alert_events " +
            "WHERE placeId = :placeId AND userId = :userId AND transition = :transition " +
            "AND source = :fromSource AND silent = 0 AND crossedAt >= :since"
    )
    suspend fun countSurfacedSince(
        placeId: String,
        userId: String,
        transition: String,
        fromSource: String,
        since: Long
    ): Int

    @Query("SELECT * FROM place_alert_events ORDER BY crossedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<PlaceAlertEventEntity>>

    @Query("DELETE FROM place_alert_events WHERE crossedAt < :minEpochMillis")
    suspend fun deleteOlderThan(minEpochMillis: Long)

    @Query("DELETE FROM place_alert_events")
    suspend fun deleteAll()
}

/** The place-alert notifications in the shade that the other path may pair with (PlaceAlertShownEntity). */
@Dao
interface PlaceAlertShownDao {
    @Query("SELECT * FROM place_alert_shown WHERE notificationId = :notificationId")
    suspend fun get(notificationId: String): PlaceAlertShownEntity?

    /** -1 when a row under this id is there already: a push delivered again is not posted again. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PlaceAlertShownEntity): Long

    /** [kind]'s rows about this member at this place that haven't paired yet, oldest first. */
    @Query(
        "SELECT * FROM place_alert_shown WHERE kind = :kind AND placeId = :placeId AND userId = :userId " +
            "AND consumed = 0 ORDER BY shownAt ASC"
    )
    suspend fun unpaired(kind: String, placeId: String, userId: String): List<PlaceAlertShownEntity>

    @Query("UPDATE place_alert_shown SET consumed = 1 WHERE notificationId = :notificationId")
    suspend fun consume(notificationId: String)

    @Query("DELETE FROM place_alert_shown WHERE shownAt < :before")
    suspend fun deleteShownBefore(before: Long)
}

@Dao
interface PendingInviteDao {
    @Query("SELECT * FROM pending_invites WHERE circleId = :circleId ORDER BY createdDayBucket DESC")
    fun observeByCircle(circleId: String): Flow<List<PendingInviteEntity>>

    @Query("SELECT * FROM pending_invites WHERE circleId = :circleId ORDER BY createdDayBucket DESC")
    suspend fun getByCircle(circleId: String): List<PendingInviteEntity>

    @Query("SELECT * FROM pending_invites WHERE id = :id")
    suspend fun getById(id: String): PendingInviteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(invite: PendingInviteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(invites: List<PendingInviteEntity>)

    @Delete
    suspend fun delete(invite: PendingInviteEntity)

    @Query("DELETE FROM pending_invites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_invites WHERE circleId = :circleId")
    suspend fun deleteByCircle(circleId: String)
}
