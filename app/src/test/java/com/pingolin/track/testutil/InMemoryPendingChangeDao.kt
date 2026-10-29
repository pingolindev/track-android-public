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

package com.pingolin.track.testutil

import com.pingolin.track.data.local.dao.PendingChangeDao
import com.pingolin.track.data.local.entity.DroppedNoticeEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** [PendingChangeDao] in memory, with the queries' order and filters, for plain JVM tests. */
class InMemoryPendingChangeDao : PendingChangeDao {
    val changes = MutableStateFlow<List<PendingChangeEntity>>(emptyList())
    val notices = MutableStateFlow<List<DroppedNoticeEntity>>(emptyList())

    private fun ordered(list: List<PendingChangeEntity>) = list.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun forAccount(account: String) = ordered(changes.value.filter { it.account == account })

    override fun observeForAccount(account: String): Flow<List<PendingChangeEntity>> =
        changes.map { list -> ordered(list.filter { it.account == account }) }

    override suspend fun get(id: String) = changes.value.firstOrNull { it.id == id }

    override suspend fun accounts() = (changes.value.map { it.account } + notices.value.map { it.account }).distinct()

    override suspend fun reassignChanges(from: String, to: String) {
        changes.value = changes.value.map { if (it.account == from) it.copy(account = to) else it }
    }

    override suspend fun reassignNotices(from: String, to: String) {
        notices.value = notices.value.map { if (it.account == from) it.copy(account = to) else it }
    }

    override suspend fun insert(change: PendingChangeEntity) {
        check(changes.value.none { it.id == change.id }) { "duplicate id ${change.id}" }
        changes.value = changes.value + change
    }

    override suspend fun update(change: PendingChangeEntity) {
        changes.value = changes.value.map { if (it.id == change.id) change else it }
    }

    override suspend fun delete(id: String) {
        changes.value = changes.value.filterNot { it.id == id }
    }

    override suspend fun countInCircle(account: String, circleId: String) =
        changes.value.count { it.account == account && it.circleId == circleId && it.state != "settling" }

    override suspend fun countForAccount(account: String) =
        changes.value.count { it.account == account && it.state != "settling" }

    override suspend fun deleteCircle(account: String, circleId: String) {
        changes.value = changes.value.filterNot { it.account == account && it.circleId == circleId }
    }

    override suspend fun deleteNoticesOfCircle(account: String, circleId: String) {
        notices.value = notices.value.filterNot { it.account == account && it.circleId == circleId }
    }

    override suspend fun insertNotice(notice: DroppedNoticeEntity) {
        notices.value = notices.value.filterNot { it.id == notice.id } + notice
    }

    override fun observeNotices(account: String): Flow<List<DroppedNoticeEntity>> =
        notices.map { list -> list.filter { it.account == account }.sortedWith(compareBy({ it.createdAt }, { it.id })) }

    override suspend fun notices(account: String) =
        notices.value.filter { it.account == account }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun markNoticesSeen(ids: List<String>, at: Long) {
        notices.value = notices.value.map { if (it.id in ids && it.seenAt == null) it.copy(seenAt = at) else it }
    }

    override suspend fun pruneNotices(seenBefore: Long, createdBefore: Long) {
        notices.value = notices.value.filterNot { (it.seenAt != null && it.seenAt < seenBefore) || it.createdAt < createdBefore }
    }
}
