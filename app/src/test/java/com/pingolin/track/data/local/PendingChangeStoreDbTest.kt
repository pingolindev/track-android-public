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
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.DroppedNoticeEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changes waiting to sync live in the SQLCipher TrackDatabase, against the real Room schema:
 * the migration makes exactly the tables Room expects, and the sign-out wipe (clearAllTables)
 * takes them with every other table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class PendingChangeStoreDbTest {

    private lateinit var db: TrackDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun change(id: String, account: String = "me", circleId: String = "c-1", createdAt: Long = 1) = PendingChangeEntity(
        id = id, account = account, circleId = circleId, kind = "CHECK_IN", inputs = "{}",
        createdAt = createdAt, expiresAt = createdAt + 1000, state = "waiting",
    )

    @Test
    fun `changes are kept per account, oldest first, and a change ends leaving its line`() = runTest {
        val dao = db.pendingChangeDao()
        dao.insert(change("b", createdAt = 2))
        dao.insert(change("a", createdAt = 1))
        dao.insert(change("x", account = "someone-else"))

        assertEquals(listOf("a", "b"), dao.forAccount("me").map { it.id })
        assertEquals(2, dao.countForAccount("me"))
        assertEquals(1, dao.countInCircle("someone-else", "c-1"))

        dao.end("a", DroppedNoticeEntity("a", "me", "c-1", "CHECK_IN", null, "Your check-in wasn't done.", 5))

        assertNull(dao.get("a"))
        assertEquals(listOf("Your check-in wasn't done."), dao.notices("me").map { it.line })
    }

    @Test
    fun `the sign-out wipe takes them with every other table`() = runTest {
        val dao = db.pendingChangeDao()
        dao.insert(change("a"))
        dao.insertNotice(DroppedNoticeEntity("n", "me", "c-1", "CHECK_IN", null, "line", 5))

        db.clearAllTables()

        assertTrue(dao.forAccount("me").isEmpty())
        assertTrue(dao.notices("me").isEmpty())
    }

    @Test
    fun `migration 38 to 39 makes the tables and indices exactly as Room does`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(38) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val migrated = helper.writableDatabase
        TrackDatabase.MIGRATION_38_39.migrate(migrated)

        fun schema(read: (String) -> android.database.Cursor): List<String> {
            val rows = mutableListOf<String>()
            read(
                "SELECT type, name, sql FROM sqlite_master WHERE tbl_name IN ('pending_changes', 'dropped_notices') " +
                    "AND sql IS NOT NULL ORDER BY name"
            ).use { c -> while (c.moveToNext()) rows += "${c.getString(0)} ${c.getString(1)}: ${c.getString(2)}" }
            return rows
        }

        val expected = schema { db.openHelper.readableDatabase.query(it) }
        val actual = schema { migrated.query(it) }
        assertEquals(6, expected.size)
        assertEquals(expected, actual)
        helper.close()
    }
}
