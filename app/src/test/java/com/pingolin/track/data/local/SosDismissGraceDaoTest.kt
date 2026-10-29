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
import com.pingolin.track.data.local.dao.SosAlertDao
import com.pingolin.track.data.local.entity.SosAlertEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 24h dismiss grace, pinned against the real Room schema.
 *
 * Two queries carry the whole behaviour and they deliberately disagree: the MAP
 * drops a dismissed alert at once, while the SAFETY LIST keeps it for
 * [SosAlertEntity.DISMISS_GRACE_MS] so a mistaken dismissal of a live emergency
 * can be undone. Getting either half wrong is easy to miss — both halves still
 * "work", just for the wrong surface.
 *
 * Real in-memory Room rather than mocks: the behaviour under test is SQLite's.
 * A mocked DAO would not show `dismissedAt > :cutoff` quietly excluding NULL
 * rows, which is what decides the fate of alerts dismissed before migration
 * 33→34 added the column.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Plain Application on purpose: TrackApplication's static initialiser calls
// System.loadLibrary for SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class SosDismissGraceDaoTest {

    private lateinit var db: TrackDatabase
    private lateinit var dao: SosAlertDao

    private val now = 1_800_000_000_000L
    private fun cutoff(at: Long = now) = at - SosAlertEntity.DISMISS_GRACE_MS

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.sosAlertDao()
    }

    @After
    fun tearDown() = db.close()

    private fun alert(
        id: String = "sos-1",
        dismissedByMe: Boolean = false,
        dismissedAt: Long? = null,
        acknowledgedByMe: Boolean = false,
        status: String = "active",
        latitude: Double? = 51.5074,
        longitude: Double? = -0.1278
    ) = SosAlertEntity(
        id = id,
        circleId = "circle-1",
        userId = "user-1",
        status = status,
        latitude = latitude,
        longitude = longitude,
        acknowledgedBy = null,
        createdAt = now - 60_000,
        resolvedAt = null,
        acknowledgedByMe = acknowledgedByMe,
        dismissedByMe = dismissedByMe,
        dismissedAt = dismissedAt
    )

    @Test
    fun liveAlert_isOnTheMapAndInTheList() = runTest {
        dao.insert(alert())

        assertEquals(1, dao.observeActiveMappable().first().size)
        assertEquals(1, dao.observeActive(cutoff()).first().size)
    }

    @Test
    fun dismissed_leavesTheMapImmediately_butStaysInTheListForTheGraceWindow() = runTest {
        // Dismissed one second ago: the map must already be clear, the list must not.
        dao.insert(alert(dismissedByMe = true, dismissedAt = now - 1_000))

        assertTrue(
            "a dismissed alert must leave the map at once",
            dao.observeActiveMappable().first().isEmpty()
        )
        assertEquals(
            "a dismissed alert must stay listed so it can be undone",
            1, dao.observeActive(cutoff()).first().size
        )
    }

    @Test
    fun dismissed_dropsOffTheListOnceTheGraceWindowExpires() = runTest {
        // One millisecond past 24h — the row has outlived its undo window.
        dao.insert(alert(dismissedByMe = true, dismissedAt = now - SosAlertEntity.DISMISS_GRACE_MS - 1))

        assertTrue(dao.observeActive(cutoff()).first().isEmpty())
        assertTrue(dao.observeActiveMappable().first().isEmpty())
    }

    @Test
    fun dismissedBeforeTheColumnExisted_staysHidden() = runTest {
        // Migration 33→34 backfills nothing: rows dismissed before `dismissedAt`
        // existed read as dismissed with a NULL timestamp. `dismissedAt > cutoff`
        // is false for NULL in SQLite, so they stay hidden — which is what the
        // user asked for when they dismissed them.
        dao.insert(alert(dismissedByMe = true, dismissedAt = null))

        assertTrue(dao.observeActive(cutoff()).first().isEmpty())
        assertTrue(dao.observeActiveMappable().first().isEmpty())
    }

    @Test
    fun undismissing_bringsTheAlertBackToTheMap() = runTest {
        dao.insert(alert(dismissedByMe = true, dismissedAt = now - 1_000))
        assertTrue(dao.observeActiveMappable().first().isEmpty())

        dao.insert(alert(dismissedByMe = false, dismissedAt = null)) // same id: REPLACE

        assertEquals(1, dao.observeActiveMappable().first().size)
        assertEquals(1, dao.observeActive(cutoff()).first().size)
    }

    @Test
    fun dismissedRowsSortAfterLiveOnes() = runTest {
        dao.insert(alert(id = "dismissed", dismissedByMe = true, dismissedAt = now - 1_000))
        dao.insert(alert(id = "live"))

        assertEquals(listOf("live", "dismissed"), dao.observeActive(cutoff()).first().map { it.id })
    }

    @Test
    fun acknowledgingClearsTheMapButKeepsTheListEntry() = runTest {
        // Acknowledging takes the alert off the map but not off the list:
        // a responder who acknowledged still needs the way back.
        dao.insert(alert(acknowledgedByMe = true))

        assertTrue(dao.observeActiveMappable().first().isEmpty())
        assertEquals(1, dao.observeActive(cutoff()).first().size)
    }

    @Test
    fun alertWithNoCoordinatesIsNeverMapped() = runTest {
        dao.insert(alert(latitude = null, longitude = null))

        assertTrue(dao.observeActiveMappable().first().isEmpty())
        assertEquals(1, dao.observeActive(cutoff()).first().size)
    }
}
