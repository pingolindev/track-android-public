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

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.data.local.entity.MemberNotSharingSeenEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.data.local.entity.PlaceAlertShownEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.local.entity.sendsBatteryLevel
import com.pingolin.track.data.local.entity.sendsBubble
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A phone with a version 38 database updates to this build: MIGRATION_38_39, MIGRATION_39_40,
 * MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45,
 * MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48 and MIGRATION_48_49 open the database it
 * already has, data and all, the way the app opens it (AppModule: the migrations, and a
 * destructive fallback that would wipe everything without a word if the path were missing).
 * After migrating, Room checks every table against this build's entities and refuses to open on
 * any difference, so a migration that doesn't make exactly Room's tables fails here, not on phones.
 *
 * The version 38 database is made from Room's own statements for that version
 * (test resource room/track-38-schema.sql).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class TrackDatabaseUpgradeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "upgrade-from-38.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun v38Statements(): List<String> =
        javaClass.classLoader!!.getResourceAsStream("room/track-38-schema.sql")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("--") }

    /** A version 38 database: Room's version 38 tables, with a circle and a location kept
     * for it.
     */
    private fun makeVersion38() {
        val statements = v38Statements()
        assertTrue("version 38's identity hash", statements.last().endsWith("VALUES(42, 'd0d02e95edc05ee70458dffa98010ebc')"))
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(38) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO circles (id, name, encryptedKey, createdAt, updatedAt, isOwner, keyRotationNeeded, keyVersion) " +
                    "VALUES ('c-1', 'Family', NULL, 1, 2, 1, 0, 3)"
            )
            execSQL(
                "INSERT INTO circle_members (circleId, userId, displayName, role, canSeeRealtime, canSeeHistory, joinedAt) " +
                    "VALUES ('c-1', '$ME', 'Sam', 'owner', 1, 1, 0)"
            )
            execSQL(
                "INSERT INTO queued_circle_locations (circleId, latitude, longitude, timestamp, createdAt) " +
                    "VALUES ('c-1', 51.5, -0.12, 1000, 1000)"
            )
            execSQL(
                "INSERT INTO locations (circleId, userId, deviceId, latitude, longitude, timestamp, isSynced, dayBucket) " +
                    "VALUES ('c-1', '$ME', 'dev-1', 51.5, -0.12, 5000, 1, 0)"
            )
            execSQL(
                "INSERT INTO privacy_settings (circleId, shareRealtime, shareHistory, shareBattery, sharingMode, " +
                    "approximateRadiusMiles, pausedUntil, primaryDeviceId, primaryDevicePseudonym, showAllDevices, updatedAt) " +
                    "VALUES ('c-1', 1, 1, 1, 'paused', 3, 9000, NULL, NULL, 0, 1)"
            )
            execSQL(
                "INSERT INTO destinations (id, userId, circleId, destName, destLatitude, destLongitude, etaDayBucket, " +
                    "notifyArrival, status, startedDayBucket, arrivedDayBucket, createdDayBucket, updatedDayBucket) " +
                    "VALUES ('d-1', '$ME', 'c-1', 'Gym', 51.5, -0.1, NULL, 1, 'active', 20000, NULL, 20000, 20000)"
            )
            execSQL(
                "INSERT INTO sos_alerts (id, circleId, userId, status, latitude, longitude, acknowledgedBy, createdAt, " +
                    "resolvedAt, acknowledgedByMe, dismissedByMe, dismissedAt, acknowledgmentCount) " +
                    "VALUES ('s-1', 'c-1', '$ME', 'active', 37.3349, -122.009, '', 7000, NULL, 0, 0, NULL, 1)"
            )
        }
        helper.close()
    }

    private fun identityHash(db: TrackDatabase): String =
        db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    @Test
    fun `a version 38 database opens after the update, keeps its data and takes changes waiting to sync`() = runTest {
        makeVersion38()

        val db = Room.databaseBuilder(context, TrackDatabase::class.java, name)
            .fallbackToDestructiveMigration(true)
            .addMigrations(
                TrackDatabase.MIGRATION_38_39, TrackDatabase.MIGRATION_39_40, TrackDatabase.MIGRATION_40_41,
                TrackDatabase.MIGRATION_41_42, TrackDatabase.MIGRATION_42_43, TrackDatabase.MIGRATION_43_44,
                TrackDatabase.MIGRATION_44_45, TrackDatabase.MIGRATION_45_46, TrackDatabase.MIGRATION_46_47,
                TrackDatabase.MIGRATION_47_48, TrackDatabase.MIGRATION_48_49,
            )
            .allowMainThreadQueries()
            .build()
        try {
            // Opening migrates and validates: a table Room doesn't expect throws here.
            assertEquals(49, db.openHelper.readableDatabase.version)
            assertNotEquals("the schema is this build's now", "d0d02e95edc05ee70458dffa98010ebc", identityHash(db))

            // What the version 38 database kept is still there (a destructive
            // fallback would wipe it).
            assertEquals("Family", db.circleDao().getById("c-1")?.name)
            assertEquals(3, db.circleDao().getById("c-1")?.keyVersion)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM queued_circle_locations").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }

            // And the new tables work.
            val change = PendingChangeEntity(
                id = "ch-1", account = "me", circleId = "c-1", kind = "CHECK_IN", inputs = "{}",
                createdAt = 1, expiresAt = 2, state = "waiting",
            )
            db.pendingChangeDao().insert(change)
            assertEquals(listOf(change), db.pendingChangeDao().forAccount("me"))

            // A destination kept from before v40 has no creator: it isn't this account's until a
            // list refresh fills it in, and then it is.
            val kept = db.destinationDao().getById("d-1")!!
            assertEquals("Gym", kept.destName)
            assertNull(kept.creatorId)
            assertEquals(DestinationEntity.Ownership.UNKNOWN, kept.ownership(ME))
            assertFalse(kept.isMine(ME))
            db.destinationDao().insert(kept.copy(creatorId = ME))
            assertTrue(db.destinationDao().getById("d-1")!!.isMine(ME))

            // A location kept from before v41 is history until a fetch decides it again: kept,
            // and never a marker.
            val cached = db.locationDao().getByTimeRange("c-1", 0, 10_000).single()
            assertFalse(cached.keyCurrent)
            assertEquals(emptyList<Any>(), db.locationDao().observeLatestByCircle("c-1").first())
            db.locationDao().insert(cached.copy(id = 0, timestamp = 6000, keyCurrent = true))
            assertEquals(listOf(6000L), db.locationDao().observeLatestByCircle("c-1").first().map { it.timestamp })

            // A member kept from before v42 is named by words that may be the server's until the next
            // member refresh decides it: no SOS alarm names them meanwhile.
            val member = db.circleMemberDao().get("c-1", ME)!!
            assertEquals("Sam", member.displayName)
            assertFalse(member.nameOpened)

            // A member kept from before v43 reads as sharing until the next member refresh says
            // otherwise, and a refresh's answer is kept.
            assertNull(member.locationSharing)
            assertNull(member.sharingPausedUntil)
            db.circleMemberDao().replaceForCircle("c-1", listOf(member.copy(locationSharing = "paused", sharingPausedUntil = 9_000L)))
            val paused = db.circleMemberDao().get("c-1", ME)!!
            assertEquals("paused", paused.locationSharing)
            assertEquals(9_000L, paused.sharingPausedUntil)

            // A member kept from before v48 shows their history until the next member refresh says
            // otherwise, and a refresh's answer is kept.
            assertNull(paused.historySharing)
            db.circleMemberDao().replaceForCircle("c-1", listOf(paused.copy(historySharing = "off")))
            assertEquals("off", db.circleMemberDao().get("c-1", ME)!!.historySharing)

            // A pause kept from before v44 goes back to exact until the next privacy sync says
            // which mode it held before, and a sync's answer is kept.
            val pause = db.privacySettingDao().get("c-1")!!
            assertEquals(3, pause.approximateRadiusMiles)
            assertNull(pause.modeBeforePause)
            assertFalse(pause.sendsBubble())
            db.privacySettingDao().insert(pause.copy(modeBeforePause = "approximate"))
            val synced = db.privacySettingDao().get("c-1")!!
            assertEquals("approximate", synced.modeBeforePause)
            assertTrue(synced.sendsBubble())

            // A privacy row kept from before v46 doesn't know "Allow alerts on me" yet: read as allowed,
            // so its battery switch alone decides, until a refresh says; and that is kept.
            assertNull(synced.allowAlertsOnMe)
            assertTrue(synced.sendsBatteryLevel())
            db.privacySettingDao().insert(synced.copy(allowAlertsOnMe = false))
            assertEquals(false, db.privacySettingDao().get("c-1")!!.allowAlertsOnMe)
            assertFalse(db.privacySettingDao().get("c-1")!!.sendsBatteryLevel())

            // An SOS kept from before v45 keeps its position, read as taken when it was sent, until a
            // refresh brings what was sealed; and that is kept.
            val sos = db.sosAlertDao().getById("s-1")!!
            assertEquals(37.3349, sos.latitude!!, 0.0)
            assertEquals(7000L, sos.createdAt)
            assertNull(sos.fixAt)
            assertNull(sos.accuracy)
            assertNull(sos.positionState)
            db.sosAlertDao().insert(sos.copy(fixAt = 6000L, accuracy = 12.0))
            assertEquals(6000L, db.sosAlertDao().getById("s-1")!!.fixAt)
            db.sosAlertDao().insert(sos.copy(latitude = null, longitude = null, positionState = SosAlertEntity.POSITION_PENDING))
            assertEquals(SosAlertEntity.POSITION_PENDING, db.sosAlertDao().getById("s-1")!!.positionState)

            // v47's notes of members not sharing start empty, and take one.
            assertNull(db.memberNotSharingSeenDao().get("c-1", ME))
            db.memberNotSharingSeenDao().put(MemberNotSharingSeenEntity("c-1", ME, 8000L))
            assertEquals(8000L, db.memberNotSharingSeenDao().get("c-1", ME))

            // v49's place-alert notifications in the shade start empty, take one, and pair it once.
            val shown = PlaceAlertShownEntity(
                notificationId = "n-1", kind = PlaceAlertShownEntity.KIND_SERVER, placeId = "p-1", userId = ME,
                transition = null, eventAt = 7000L, shownAt = 7100L, consumed = false,
            )
            assertEquals(emptyList<PlaceAlertShownEntity>(), db.placeAlertShownDao().unpaired(PlaceAlertShownEntity.KIND_SERVER, "p-1", ME))
            assertTrue(db.placeAlertShownDao().insert(shown) != -1L)
            assertEquals(-1L, db.placeAlertShownDao().insert(shown))
            assertEquals(listOf(shown), db.placeAlertShownDao().unpaired(PlaceAlertShownEntity.KIND_SERVER, "p-1", ME))
            db.placeAlertShownDao().consume("n-1")
            assertEquals(emptyList<PlaceAlertShownEntity>(), db.placeAlertShownDao().unpaired(PlaceAlertShownEntity.KIND_SERVER, "p-1", ME))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the app opens with every migration from version 38 to this build's`() {
        // AppModule's list, read from its source: a migration left out of it is the destructive
        // fallback on every phone that updates.
        val module = java.io.File("src/main/java/com/pingolin/track/di/AppModule.kt").readText()
        for (from in 38 until 49) {
            assertTrue("MIGRATION_${from}_${from + 1}", module.contains("TrackDatabase.MIGRATION_${from}_${from + 1}"))
        }
        val database = java.io.File("src/main/java/com/pingolin/track/data/local/TrackDatabase.kt").readText()
        assertTrue(database.contains("    version = 49,"))
    }

    private companion object {
        const val ME = "a1b2c3d4-0000-4000-8000-00000000000e"
    }
}
