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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.pingolin.track.data.local.entity.DroppedNoticeEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import kotlinx.coroutines.flow.Flow

/** Changes waiting to sync on this phone, and the lines dropped ones left. */
@Dao
interface PendingChangeDao {

    /** Oldest first: a circle's changes go out in the order they were made. */
    @Query("SELECT * FROM pending_changes WHERE account = :account ORDER BY createdAt ASC, id ASC")
    suspend fun forAccount(account: String): List<PendingChangeEntity>

    @Query("SELECT * FROM pending_changes WHERE account = :account ORDER BY createdAt ASC, id ASC")
    fun observeForAccount(account: String): Flow<List<PendingChangeEntity>>

    @Query("SELECT * FROM pending_changes WHERE id = :id")
    suspend fun get(id: String): PendingChangeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: PendingChangeEntity)

    @Update
    suspend fun update(change: PendingChangeEntity)

    @Query("DELETE FROM pending_changes WHERE id = :id")
    suspend fun delete(id: String)

    /** Changes that still count as waiting (a settling record no longer shows). */
    @Query("SELECT COUNT(*) FROM pending_changes WHERE account = :account AND circleId = :circleId AND state != 'settling'")
    suspend fun countInCircle(account: String, circleId: String): Int

    @Query("SELECT COUNT(*) FROM pending_changes WHERE account = :account AND state != 'settling'")
    suspend fun countForAccount(account: String): Int

    /** A circle this account left, or that is gone: its changes go with its other local state. */
    @Query("DELETE FROM pending_changes WHERE account = :account AND circleId = :circleId")
    suspend fun deleteCircle(account: String, circleId: String)

    @Query("DELETE FROM dropped_notices WHERE account = :account AND circleId = :circleId")
    suspend fun deleteNoticesOfCircle(account: String, circleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotice(notice: DroppedNoticeEntity)

    @Query("SELECT * FROM dropped_notices WHERE account = :account ORDER BY createdAt ASC, id ASC")
    fun observeNotices(account: String): Flow<List<DroppedNoticeEntity>>

    @Query("SELECT * FROM dropped_notices WHERE account = :account ORDER BY createdAt ASC, id ASC")
    suspend fun notices(account: String): List<DroppedNoticeEntity>

    @Query("UPDATE dropped_notices SET seenAt = :at WHERE id IN (:ids) AND seenAt IS NULL")
    suspend fun markNoticesSeen(ids: List<String>, at: Long)

    /** Seen before [seenBefore], or made before [createdBefore] (7 days at most). */
    @Query("DELETE FROM dropped_notices WHERE (seenAt IS NOT NULL AND seenAt < :seenBefore) OR createdAt < :createdBefore")
    suspend fun pruneNotices(seenBefore: Long, createdBefore: Long)

    /** Every account the tables hold changes or lines for (PendingChanges.account: another spelling is moved). */
    @Query("SELECT account FROM pending_changes UNION SELECT account FROM dropped_notices")
    suspend fun accounts(): List<String>

    /** [from]'s changes and lines, under [to]: the same account's canonical id (AccountIds). */
    @Transaction
    suspend fun reassign(from: String, to: String) {
        reassignChanges(from, to)
        reassignNotices(from, to)
    }

    @Query("UPDATE pending_changes SET account = :to WHERE account = :from")
    suspend fun reassignChanges(from: String, to: String)

    @Query("UPDATE dropped_notices SET account = :to WHERE account = :from")
    suspend fun reassignNotices(from: String, to: String)

    /** The change ends: it goes, and when it was dropped with a line, the line stays. */
    @Transaction
    suspend fun end(id: String, notice: DroppedNoticeEntity?) {
        delete(id)
        if (notice != null) insertNotice(notice)
    }
}
