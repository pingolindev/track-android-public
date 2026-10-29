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

package com.pingolin.track.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.entity.NotificationEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The notification list, newest first, against the real Room schema. Ordered by day alone, one day's
 * rows would come back in no set order: an earlier one over a later one (09:25 over 12:27). By day,
 * then by the exact time within it; a row without a time (an older cached one) after its day's
 * timed rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Plain Application on purpose: TrackApplication's static initialiser loads native libraries the JVM can't.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class NotificationOrderDaoTest {

    private lateinit var db: TrackDatabase
    private lateinit var dao: NotificationDao

    // 2026-10-25 and the day before, as UTC day buckets, and times within them.
    private val day = 20751
    private fun at(bucket: Int, hhmm: String): Long {
        val (h, m) = hhmm.split(":").map { it.toLong() }
        return bucket * 86_400_000L + (h * 60 + m) * 60_000L
    }

    private fun row(id: String, bucket: Int, createdAt: Long?, circleId: String? = "c1", isRead: Boolean = false) =
        NotificationEntity(
            id = id, circleId = circleId, notificationType = "place_alert", title = "Arrived", body = null,
            metadata = null, isRead = isRead, createdDayBucket = bucket, createdAt = createdAt,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.notificationDao()
        // Inserted oldest-time first within each day, so an order that ignores the time shows it.
        dao.insertAllBlocking(
            row("morning", day, at(day, "09:25")),
            row("untimed", day, null),
            row("noon", day, at(day, "12:27")),
            row("evening", day, at(day, "18:02")),
            row("yesterday-late", day - 1, at(day - 1, "23:59")),
            row("yesterday-early", day - 1, at(day - 1, "07:00")),
            row("other-circle", day, at(day, "15:00"), circleId = "c2", isRead = true),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the whole list is by day, then by time, untimed after its day's timed rows`() = runTest {
        assertEquals(
            listOf("evening", "other-circle", "noon", "morning", "untimed", "yesterday-late", "yesterday-early"),
            dao.observeAll().first().map { it.id },
        )
    }

    @Test
    fun `the unread list and a circle's list keep the same order`() = runTest {
        assertEquals(
            listOf("evening", "noon", "morning", "untimed", "yesterday-late", "yesterday-early"),
            dao.observeUnread().first().map { it.id },
        )
        assertEquals(
            listOf("evening", "noon", "morning", "untimed", "yesterday-late", "yesterday-early"),
            dao.observeByCircle("c1").first().map { it.id },
        )
    }

    private fun NotificationDao.insertAllBlocking(vararg rows: NotificationEntity) =
        kotlinx.coroutines.runBlocking { rows.forEach { insert(it) } }
}
