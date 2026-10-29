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
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards a SQLite footgun that silently destroys place alerts.
 *
 * `place_alerts` has a foreign key on `places` with ON DELETE CASCADE, and Room
 * runs with `PRAGMA foreign_keys = ON`. SQLite implements INSERT OR REPLACE as
 * DELETE-then-INSERT, so REPLACE-writing an EXISTING place row deletes it first,
 * firing the cascade and wiping every alert on that place — even though the row
 * is re-inserted with the same id a moment later.
 *
 * It bites exactly when it hurts most: creating the first alert on a place flips
 * `alertsEnabled` server-side, so the very next refreshPlaces sees changed
 * content, rewrites the row, and destroys the alert that caused the change.
 *
 * These tests run against a REAL in-memory Room database rather than mocks: the
 * behaviour under test is SQLite's, so a mocked DAO would prove nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Plain Application on purpose: TrackApplication's static initialiser calls
// System.loadLibrary for SQLCipher and the Rust SDK, which cannot load on the JVM.
// This test needs Room's SQL semantics, not the app's native stack.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class PlaceAlertCascadeTest {

    private lateinit var db: TrackDatabase

    private val place = PlaceEntity(
        id = "place-home",
        circleId = "self",
        name = "PrivateHome",
        address = null,
        latitude = 37.7699,
        longitude = -122.4469,
        radius = 150,
        icon = null,
        color = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** A circle-less alert on a personal place — the case with no share at all. */
    private val alert = PlaceAlertEntity(
        id = "alert-1",
        placeId = place.id,
        circleId = null,
        userId = "user-me",
        targetUserId = "user-me",
        dayBucket = 0,
    )

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrackDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `foreign key enforcement is actually on`() = runTest {
        // If this were OFF the cascade tests below would stop meaning
        // anything, so assert the precondition rather than assuming it.
        db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { c ->
            c.moveToFirst()
            assertEquals("PRAGMA foreign_keys must be ON", 1, c.getInt(0))
        }
    }

    @Test
    fun `rewriting a place keeps its alerts`() = runTest {
        db.placeDao().upsert(place)
        db.placeAlertDao().insert(alert)
        assertEquals(1, db.placeAlertDao().getActive().size)

        // Exactly what happens after the first alert is created: the server flips
        // alertsEnabled, refreshPlaces sees changed content and rewrites the row.
        db.placeDao().upsertAll(listOf(place.copy(alertsEnabled = true)))

        assertEquals(
            "rewriting the parent place must not cascade-delete its alerts",
            1,
            db.placeAlertDao().getActive().size,
        )
        assertEquals(true, db.placeDao().getById(place.id)?.alertsEnabled)
    }

    @Test
    fun `a circle-less personal place alert survives repeated refreshes`() = runTest {
        db.placeDao().upsert(place)
        db.placeAlertDao().insert(alert)

        // The 2-minute boundary reload calls refreshPlaces over and over.
        repeat(5) { i ->
            db.placeDao().upsertAll(listOf(place.copy(alertsEnabled = true, name = "PrivateHome $i")))
            assertEquals("alert lost on refresh $i", 1, db.placeAlertDao().getActive().size)
        }

        val survivor = db.placeAlertDao().getActive().single()
        assertEquals("alert-1", survivor.id)
        assertEquals("the circle-less routing must survive too", null, survivor.circleId)
    }

    @Test
    fun `upsert still inserts a genuinely new place`() = runTest {
        db.placeDao().upsertAll(listOf(place))
        assertNotNull(db.placeDao().getById(place.id))
    }

    @Test
    fun `deleting a place still cascades to its alerts`() = runTest {
        // The cascade is wanted when the place is REALLY gone: the upsert stops
        // a rewrite from masquerading as a delete without turning the cascade off.
        db.placeDao().upsert(place)
        db.placeAlertDao().insert(alert)

        db.placeDao().deleteById(place.id)

        assertEquals(
            "a real place deletion must still remove its alerts",
            0,
            db.placeAlertDao().getActive().size,
        )
    }
}
