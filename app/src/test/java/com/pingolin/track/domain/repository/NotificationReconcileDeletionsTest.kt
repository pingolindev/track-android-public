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

import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.remote.api.TrackApi
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Deletion reconciliation: remove server-backed rows the server stopped
 * returning, WITHOUT mistaking a paginated page for the whole list.
 *
 * The refresh fetches one page (default 50). The naive reconciliation — delete
 * anything absent from the response — would wipe every notification past the
 * first 50 on each refresh, so the branch chosen for each page shape is the
 * whole risk of this feature. These tests pin that choice; they deliberately
 * assert on WHICH dao call is made, because picking the unbounded delete when
 * the page was full is exactly the destructive mistake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationReconcileDeletionsTest {

    @MockK private lateinit var tracksApi: TrackApi
    @MockK private lateinit var notificationDao: NotificationDao
    @MockK private lateinit var placeDao: PlaceDao
    @MockK private lateinit var destinationRepository: DestinationRepository

    private lateinit var repo: NotificationRepository

    private fun row(id: String, bucket: Int) = NotificationEntity(
        id = id,
        circleId = null,
        notificationType = "destination_arrival",
        title = "Arrived",
        body = null,
        metadata = null,
        isRead = false,
        createdDayBucket = bucket,
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        coEvery { notificationDao.deleteServerRowsNotIn(any()) } returns 0
        coEvery { notificationDao.deleteServerRowsNotInSince(any(), any()) } returns 0
        coEvery { notificationDao.deleteAllServerRows() } returns 0
        repo = NotificationRepository(tracksApi, notificationDao, placeDao, destinationRepository, io.mockk.mockk(), io.mockk.mockk())
    }

    /** A partial page IS the complete list, so an unbounded delete is correct. */
    @Test
    fun partial_page_reconciles_the_whole_list() = runTest {
        val page = listOf(row("a", 20660), row("b", 20659))
        repo.reconcileDeletions(page, limit = 50, offset = 0)
        coVerify(exactly = 1) { notificationDao.deleteServerRowsNotIn(listOf("a", "b")) }
        coVerify(exactly = 0) { notificationDao.deleteServerRowsNotInSince(any(), any()) }
        coVerify(exactly = 0) { notificationDao.deleteAllServerRows() }
    }

    /**
     * THE ONE THAT MATTERS. A full page means more rows may exist below it, so
     * reconciliation must be bounded — and bounded STRICTLY above the oldest
     * bucket returned, since the page edge may have cut through a day.
     */
    @Test
    fun full_page_reconciles_only_above_the_page_boundary() = runTest {
        val page = (0 until 3).map { row("id$it", 20660 - it) } // oldest bucket = 20658
        repo.reconcileDeletions(page, limit = 3, offset = 0)
        coVerify(exactly = 1) {
            notificationDao.deleteServerRowsNotInSince(listOf("id0", "id1", "id2"), 20658)
        }
        coVerify(exactly = 0) { notificationDao.deleteServerRowsNotIn(any()) }
        coVerify(exactly = 0) { notificationDao.deleteAllServerRows() }
    }

    /** An empty first page means the user genuinely has no server notifications. */
    @Test
    fun empty_page_clears_all_server_rows() = runTest {
        repo.reconcileDeletions(emptyList(), limit = 50, offset = 0)
        coVerify(exactly = 1) { notificationDao.deleteAllServerRows() }
        coVerify(exactly = 0) { notificationDao.deleteServerRowsNotIn(any()) }
    }

    /**
     * A "load more" page is a window into the middle of the list and says nothing
     * about what was deleted above it — reconciling from it would delete rows the
     * page simply never covered.
     */
    @Test
    fun paged_fetch_never_reconciles() = runTest {
        repo.reconcileDeletions(listOf(row("x", 20600)), limit = 50, offset = 50)
        coVerify(exactly = 0) { notificationDao.deleteServerRowsNotIn(any()) }
        coVerify(exactly = 0) { notificationDao.deleteServerRowsNotInSince(any(), any()) }
        coVerify(exactly = 0) { notificationDao.deleteAllServerRows() }
    }

    /** A DAO failure must never fail the refresh — a stale row beats an empty list. */
    @Test
    fun dao_failure_is_swallowed() = runTest {
        coEvery { notificationDao.deleteServerRowsNotIn(any()) } throws RuntimeException("db gone")
        repo.reconcileDeletions(listOf(row("a", 20660)), limit = 50, offset = 0)
    }
}
