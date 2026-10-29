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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pingolin.track.data.local.dao.*
import com.pingolin.track.data.local.entity.*

@Database(
    entities = [
        UserEntity::class,
        CircleEntity::class,
        CircleSortOrderEntity::class,
        CircleMemberEntity::class,
        DeviceEntity::class,
        PlaceEntity::class,
        PlaceAlertEntity::class,
        LocationEntity::class,
        CheckInEntity::class,
        SosAlertEntity::class,
        PrivacySettingEntity::class,
        PendingLocationEntity::class,
        DestinationEntity::class,
        InactivityAlertEntity::class,
        AlertRuleEntity::class,
        NotificationEntity::class,
        PendingInviteEntity::class,
        LocalInactivityEventEntity::class,
        LocalBatteryEventEntity::class,
        PlaceAlertMemberStateEntity::class,
        PlaceAlertEventEntity::class,
        PendingCrossingEntity::class,
        QueuedCircleLocationEntity::class,
        PendingChangeEntity::class,
        DroppedNoticeEntity::class,
        MemberNotSharingSeenEntity::class,
        PlaceAlertShownEntity::class
    ],
    version = 49,
    exportSchema = false
)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun circleDao(): CircleDao
    abstract fun circleSortOrderDao(): CircleSortOrderDao
    abstract fun circleMemberDao(): CircleMemberDao
    abstract fun deviceDao(): DeviceDao
    abstract fun placeDao(): PlaceDao
    abstract fun placeAlertDao(): PlaceAlertDao
    abstract fun locationDao(): LocationDao
    abstract fun checkInDao(): CheckInDao
    abstract fun sosAlertDao(): SosAlertDao
    abstract fun privacySettingDao(): PrivacySettingDao
    abstract fun pendingLocationDao(): PendingLocationDao
    abstract fun destinationDao(): DestinationDao
    abstract fun inactivityAlertDao(): InactivityAlertDao
    abstract fun alertRuleDao(): AlertRuleDao
    abstract fun notificationDao(): NotificationDao
    abstract fun pendingInviteDao(): PendingInviteDao
    abstract fun localInactivityEventDao(): LocalInactivityEventDao
    abstract fun memberNotSharingSeenDao(): MemberNotSharingSeenDao
    abstract fun localBatteryEventDao(): LocalBatteryEventDao
    abstract fun placeAlertMemberStateDao(): PlaceAlertMemberStateDao
    abstract fun placeAlertEventDao(): PlaceAlertEventDao
    abstract fun placeAlertShownDao(): PlaceAlertShownDao
    abstract fun pendingCrossingDao(): PendingCrossingDao
    abstract fun queuedCircleLocationDao(): QueuedCircleLocationDao
    abstract fun pendingChangeDao(): PendingChangeDao

    companion object {
        /**
         * Tracks when a circle's key needs rotating (e.g. after a member leaves).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE circles ADD COLUMN keyRotationNeeded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE circles ADD COLUMN keyVersion INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Day bucket = timestamp / 86400000 (ms per day). Lets the cache be
         * looked up a day at a time instead of pulling the whole history.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE locations ADD COLUMN dayBucket INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE locations SET dayBucket = timestamp / 86400000")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_dayBucket ON locations(dayBucket)")
            }
        }

        /**
         * Multi-device visibility. primaryDeviceId null means show every device.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE privacy_settings ADD COLUMN primaryDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE privacy_settings ADD COLUMN showAllDevices INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Drops the places → circles foreign key. Personal places carry the
         * sentinel circleId "self", which has no row in circles, so the
         * constraint would reject them. SQLite cannot drop a constraint in
         * place; the table has to be rebuilt.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS places_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        circleId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        radius INTEGER NOT NULL,
                        icon TEXT,
                        color TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO places_new (id, circleId, name, latitude, longitude, radius, icon, color, createdAt, updatedAt)
                    SELECT id, circleId, name, latitude, longitude, radius, icon, color, createdAt, updatedAt FROM places
                """)
                db.execSQL("DROP TABLE places")
                db.execSQL("ALTER TABLE places_new RENAME TO places")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_places_circleId ON places(circleId)")
            }
        }

        /**
         * Migration from version 5 to 6: Add address column to places table.
         * The address is geocoded when creating a place and displayed in the places list.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN address TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from version 6 to 7: Add sortOrder column to places table.
         * This is a local-only field for user's preferred ordering (not synced to server).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration from version 7 to 8: recompute dayBucket for locations
         * that were stored with dayBucket=0 instead of a value computed from
         * their timestamp.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rows stored without a computed bucket carry 0, so it is also the
                // "needs recomputing" marker.
                db.execSQL("UPDATE locations SET dayBucket = timestamp / 86400000 WHERE dayBucket = 0")
            }
        }

        /**
         * Migration from version 8 to 9: Add destinations, inactivity alerts, alert rules, and notifications.
         * These features enable travel intention sharing, inactivity monitoring, smart alerts, and
         * an in-app notification center.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create destinations table for travel intention sharing
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS destinations (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        circleId TEXT NOT NULL,
                        destName TEXT,
                        destLatitude REAL NOT NULL,
                        destLongitude REAL NOT NULL,
                        etaDayBucket INTEGER,
                        notifyArrival INTEGER NOT NULL DEFAULT 1,
                        status TEXT NOT NULL DEFAULT 'active',
                        startedDayBucket INTEGER NOT NULL,
                        arrivedDayBucket INTEGER,
                        createdDayBucket INTEGER NOT NULL,
                        updatedDayBucket INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_destinations_circleId ON destinations(circleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_destinations_userId ON destinations(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_destinations_status ON destinations(status)")

                // Create inactivity_alerts table for inactivity monitoring
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inactivity_alerts (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        circleId TEXT NOT NULL,
                        targetUserId TEXT NOT NULL,
                        timeoutHours INTEGER NOT NULL,
                        isSelfAlert INTEGER NOT NULL DEFAULT 0,
                        notifyPush INTEGER NOT NULL DEFAULT 1,
                        notifyEmail INTEGER NOT NULL DEFAULT 0,
                        cooldownMinutes INTEGER NOT NULL DEFAULT 60,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        lastTriggeredDayBucket INTEGER,
                        dayBucket INTEGER NOT NULL,
                        clientOnly INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inactivity_alerts_circleId ON inactivity_alerts(circleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inactivity_alerts_targetUserId ON inactivity_alerts(targetUserId)")

                // Create alert_rules table for smart alerts (low battery, offline)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS alert_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        circleId TEXT,
                        targetUserId TEXT NOT NULL,
                        ruleType TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        notifyPush INTEGER NOT NULL DEFAULT 1,
                        notifyEmail INTEGER NOT NULL DEFAULT 0,
                        notifySms INTEGER NOT NULL DEFAULT 0,
                        cooldownMinutes INTEGER,
                        dayBucket INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_rules_circleId ON alert_rules(circleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_rules_targetUserId ON alert_rules(targetUserId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_rules_ruleType ON alert_rules(ruleType)")

                // Create notifications table for in-app notification center
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id TEXT NOT NULL PRIMARY KEY,
                        circleId TEXT,
                        notificationType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT,
                        metadata TEXT,
                        isRead INTEGER NOT NULL DEFAULT 0,
                        createdDayBucket INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_circleId ON notifications(circleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_isRead ON notifications(isRead)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_createdDayBucket ON notifications(createdDayBucket)")
            }
        }

        /**
         * Migration from version 9 to 10: Add pending_invites table.
         * This enables viewing and revoking pending circle invitations.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_invites (
                        id TEXT NOT NULL PRIMARY KEY,
                        circleId TEXT NOT NULL,
                        inviteCode TEXT NOT NULL,
                        email TEXT,
                        createdDayBucket INTEGER NOT NULL,
                        expiresDayBucket INTEGER NOT NULL,
                        isExpired INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (circleId) REFERENCES circles(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_invites_circleId ON pending_invites(circleId)")
            }
        }

        /**
         * Migration from version 10 to 11: Add boundaryRadius column to places table.
         * Optional geofence boundary radius in meters (200-25000). Null means no boundary.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN boundaryRadius INTEGER DEFAULT NULL")
            }
        }

        /**
         * Deliberately empty: the step exists only to keep the migration chain
         * gapless — installs at version 11 would otherwise never reach 12.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        /**
         * Migration from version 12 to 13: Add primaryDevicePseudonym to privacy_settings.
         * Required by backend when primary_device_id is set in privacy update requests.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE privacy_settings ADD COLUMN primaryDevicePseudonym TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from version 13 to 14: Add sharedCircles column to places table.
         * Comma-separated circle IDs this place is shared with via envelope encryption.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN sharedCircles TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from version 14 to 15: Update pending_invites table schema.
         * Replace inviteCode with inviteType and status to match backend API response.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS pending_invites")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_invites (
                        id TEXT NOT NULL PRIMARY KEY,
                        circleId TEXT NOT NULL,
                        inviteType TEXT,
                        email TEXT,
                        status TEXT,
                        createdDayBucket INTEGER NOT NULL,
                        expiresDayBucket INTEGER NOT NULL,
                        isExpired INTEGER NOT NULL,
                        FOREIGN KEY (circleId) REFERENCES circles(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_invites_circleId ON pending_invites(circleId)")
            }
        }

        /**
         * Migration from version 15 to 16: Add composite index for location range queries
         * (history fetches scoped to dayBucket + circleId) and an index on place_alerts.isActive
         * for the active-alerts hot path inside LocationTrackingService.
         *
         * Index names match what Room generates from the corresponding @Entity(indices = ...)
         * annotations so that schema verification passes.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_dayBucket_circleId ON locations(dayBucket, circleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_alerts_isActive ON place_alerts(isActive)")
            }
        }

        /**
         * Migration from version 16 to 17: Add `notes` column to places.
         * Mirrors the web client, which stores per-place notes via the
         * backend's encrypted_notes blob. Default NULL so existing places
         * land with no notes and the user can fill them in.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN notes TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration 17→18: cache the per-place envelope key (`placeKey`)
         * on places so share/edit flows can re-wrap or re-encrypt
         * without a server round-trip. Local DB is SQLCipher-encrypted
         * at rest. NULL is the safe default for both legacy circle-
         * owned rows and any pre-existing personal places that haven't
         * been re-encrypted to the envelope format yet.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN placeKey TEXT DEFAULT NULL")
            }
        }
        /**
         * Migration 18 → 19: persist the wrap_via_circle hint on each
         * place row. Lets the UI render a "Shared via <circle>" tag for
         * places the user reads through a place_shares row instead of
         * owning directly. NULL for owned places.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN wrapViaCircle TEXT DEFAULT NULL")
            }
        }
        /**
         * Migration 19 → 20: persist the owner user id on share-
         * recipient place rows so the UI can render "Shared by
         * <member>" attribution. Server only ships this for shared
         * places; owner-only reads keep ownerUserId NULL.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN ownerUserId TEXT DEFAULT NULL")
            }
        }

        /** Add the device-local circle ordering table (Circles-tab drag reorder). */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS circle_sort_order (" +
                        "circleId TEXT NOT NULL PRIMARY KEY, " +
                        "sortOrder INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Add notifications.createdAt (epoch millis, nullable) so the bell list
         * can render "Today"/"Yesterday" in the device's local timezone instead
         * of from the UTC day bucket (which mislabels local days west of UTC).
         * Existing rows stay NULL and fall back to the day-bucket label until the
         * next refresh repopulates them from the server's created_at.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN createdAt INTEGER DEFAULT NULL")
            }
        }

        /**
         * Migration 22 → 23: stop the `locations` table growing without bound. The live poll
         * re-inserts the same (circleId, userId, timestamp) rows every tick, and REPLACE
         * never fires because the only unique key is the autoincrement id — so a stationary
         * member accumulates one duplicate row per poll, and observeLatestByCircle (a
         * correlated subquery) degrades to O(N²). De-dupe the table, then add a UNIQUE
         * (circleId, userId, timestamp) index so REPLACE collapses future re-inserts AND the
         * latest-per-user query becomes an index seek.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep the newest row (MAX id) per (circleId, userId, timestamp); drop the
                // accumulated duplicates so the UNIQUE index below can be created.
                db.execSQL(
                    "DELETE FROM locations WHERE id NOT IN " +
                        "(SELECT MAX(id) FROM locations GROUP BY circleId, userId, timestamp)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_locations_circleId_userId_timestamp " +
                        "ON locations (circleId, userId, timestamp)"
                )
            }
        }

        /**
         * Migration 23 → 24: add inactivity_alerts.targetUserIds for the multi-member /
         * "everyone" watcher set (client-side evaluated). NULL = everyone in the circle; a
         * comma-joined list of user IDs = those specific members. Backfill existing rows from
         * their single targetUserId so a pre-existing rule keeps watching that one member.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inactivity_alerts ADD COLUMN targetUserIds TEXT DEFAULT NULL")
                db.execSQL("UPDATE inactivity_alerts SET targetUserIds = targetUserId")
            }
        }

        /**
         * Migration 24 → 25: add local_inactivity_events. Client-side inactivity rules are
         * evaluated on-device and never reported to the server, so a firing has no server row
         * to surface in the bell or the alert-rules history. This device-local table records
         * each firing so both surfaces can show it (server stays blind).
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_inactivity_events (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "inactivityAlertId TEXT NOT NULL, " +
                        "circleId TEXT NOT NULL, " +
                        "targetUserId TEXT NOT NULL, " +
                        "targetDisplayName TEXT NOT NULL, " +
                        "timeoutHours INTEGER NOT NULL, " +
                        "inactiveSeconds INTEGER NOT NULL, " +
                        "triggeredAt INTEGER NOT NULL, " +
                        "triggeredDayBucket INTEGER NOT NULL, " +
                        "resolvedAt INTEGER)"
                )
            }
        }

        /**
         * v25→26: add places.alertsEnabled. NOT NULL DEFAULT 0 to match
         * @ColumnInfo(defaultValue = "0") on PlaceEntity; the value is refreshed from
         * the server's alerts_enabled on the next place sync.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN alertsEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inactivity_alerts ADD COLUMN notifyOnReturn INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 27 → 28: add the indices that back two hot read paths so they
         * become index seeks instead of full-table scans:
         *  - check_ins (circleId, timestamp): CheckInDao.observeByCircle filters by
         *    circleId and orders by timestamp DESC.
         *  - sos_alerts (status): SosAlertDao.observeActive filters status = 'active'.
         * Index names must match what Room derives from the @Entity(indices = ...)
         * annotations or the schema-identity check fails on open (see MIGRATION_15_16).
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_check_ins_circleId_timestamp ON check_ins(circleId, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sos_alerts_status ON sos_alerts(status)")
            }
        }

        /**
         * Migration 28 → 29: persist the envelope wrap on queued locations. A row
         * queued from the envelope path (sendEnvelopeLocation, offline) must keep the
         * per-target wrapped msg_key beside its ciphertext: replayed as a bare
         * sendLocation call with no wrap, its payload (sealed under a random per-call
         * msg_key) can never be decrypted. Add the nullable wrap columns; existing
         * rows land NULL and keep draining via the legacy bare path.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN wrappedKey TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN wrappedKeyNonce TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration 29 → 30:
         *  - places.isOwner: server-authoritative ownership flag. NOT NULL DEFAULT 1
         *    matches @ColumnInfo(defaultValue = "1"); the value refreshes from the
         *    server's is_owner on the next place sync. Backfill existing rows from the
         *    `wrapViaCircle IS NULL` proxy so a received-share (wrapViaCircle set) lands
         *    isOwner = 0 and an owned place lands 1 until the next refresh corrects it.
         *  - place_alerts.circleId: the alert's routing circle (from the server's
         *    circle_id), needed to re-derive match hashes under the right key for
         *    alerts on personal places. NULL default; populated on the next alert sync.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN isOwner INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE places SET isOwner = 0 WHERE wrapViaCircle IS NOT NULL")
                db.execSQL("ALTER TABLE place_alerts ADD COLUMN circleId TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration 30 → 31: SUBSCRIBER-SIDE place alerts. Both tables are
         * device-local and never reach the server — they hold exactly the
         * computation the blind server cannot do.
         *  - place_alert_member_state: per-(place, member) in/out state plus the
         *    watermark of the last location already accounted for, so a catch-up
         *    batch is reconciled as transitions instead of firing "arriving now"
         *    for an hour-old inside point.
         *  - place_alert_events: the de-duplication ledger. The primary key is the
         *    crossing's own identity (place:user:transition:crossedAt), so the same
         *    crossing can be claimed at most once ever — across restarts, across
         *    overlapping batches, and across the local vs server-push paths.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS place_alert_member_state (" +
                        "placeId TEXT NOT NULL, " +
                        "userId TEXT NOT NULL, " +
                        "isInside INTEGER NOT NULL, " +
                        "lastLocationAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(placeId, userId))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS place_alert_events (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "placeId TEXT NOT NULL, " +
                        "userId TEXT NOT NULL, " +
                        "transition TEXT NOT NULL, " +
                        "crossedAt INTEGER NOT NULL, " +
                        "surfacedAt INTEGER NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "silent INTEGER NOT NULL)"
                )
                // Name must match what Room derives from the @Entity(indices = ...)
                // annotation or the schema-identity check fails on open (cf. MIGRATION_15_16).
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_place_alert_events_placeId_userId_transition_crossedAt " +
                        "ON place_alert_events (placeId, userId, transition, crossedAt)"
                )
            }
        }

        /**
         * Migration 31 → 32:
         *  - pending_locations.keyId: names the key that sealed encryptedPayload
         *    ("s:{uuid}" session key, "c:{version}" circle-key fallback).
         *
         * A queued row from the session-key send path must carry its key_id:
         * replayed with an empty key_id, the reader takes its circle-key branch
         * and can never select the session key the payload was actually sealed
         * under. Existing rows stay NULL: they drain as before, which is correct
         * for the plain circle-key and envelope paths.
         *
         * Nullable with no default, matching the wrappedKey pair added in
         * MIGRATION_28_29.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN keyId TEXT DEFAULT NULL")
            }
        }

        /**
         * Per-viewer SOS state: whether *this* user acknowledged or dismissed an
         * alert, plus the acknowledgment count.
         *
         * These live on the client because they're per-(alert, viewer) facts,
         * and the server returns them as flags rather than by mutating the
         * shared alert.
         *
         * The DEFAULT is not optional: SQLite rejects ADD COLUMN ... NOT NULL
         * without one on a non-empty table. 0 is also the correct reading of
         * history — nobody could have dismissed an alert before dismissal
         * existed. The entity declares no @ColumnInfo(defaultValue), so Room's
         * identity check ignores the default and only compares type/nullability.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sos_alerts ADD COLUMN acknowledgedByMe INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sos_alerts ADD COLUMN dismissedByMe INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sos_alerts ADD COLUMN acknowledgmentCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * When THIS user dismissed an alert, so the Safety screen can show the
         * 24h undo countdown and drop the row once it expires.
         *
         * Nullable with no default: a row that was dismissed before this column
         * existed has no honest timestamp to claim. Those rows read as
         * dismissedByMe = 1 with dismissedAt = NULL, which the Safety query
         * treats as already past the grace window — it stays hidden, which is
         * what the user asked for when they dismissed it.
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sos_alerts ADD COLUMN dismissedAt INTEGER DEFAULT NULL")
            }
        }

        /**
         * Migration from version 34 to 35: low-battery alerts become a
         * client-side evaluation, which needs two things on the device.
         *
         * The threshold, because the comparison is local — the server never
         * reads a battery level, so it cannot make it — and the device has to be
         * able to make it the moment a silent wake-up lands, not a round-trip
         * later. And local_battery_events, because a firing decided here is
         * never reported to the server, so there is no server row for the bell
         * to fetch; an unresolved row is also the fire-once marker that stops a
         * phone sitting at 8% re-notifying on every wake-up.
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_rules ADD COLUMN batteryThreshold INTEGER DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_battery_events (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "alertRuleId TEXT NOT NULL, " +
                        "circleId TEXT NOT NULL, " +
                        "targetUserId TEXT NOT NULL, " +
                        "targetDisplayName TEXT NOT NULL, " +
                        "batteryLevel INTEGER NOT NULL, " +
                        "triggeredAt INTEGER NOT NULL, " +
                        "triggeredDayBucket INTEGER NOT NULL, " +
                        "resolvedAt INTEGER)"
                )
            }
        }

        /**
         * An outbox for boundary crossings, like the one locations have
         * (`pending_locations`): without it a report that loses its request is
         * lost outright and the alert silently never fires.
         *
         * Columns are the plaintext INPUTS to
         * `BoundaryRepository.reportCrossingFromMonitor`, not a ciphertext — see
         * `PendingCrossingEntity` for why that avoids the key-naming problem
         * `pending_locations.wrappedKey` / `keyId` exist to solve.
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Copied VERBATIM from Room's own `createAllTables` for this entity
                // (build/generated/ksp/.../TrackDatabase_Impl.kt), because Room
                // validates a migrated schema against the entity at open time and
                // reports a mismatch as an IllegalStateException on the first query
                // — not at build time, since `exportSchema = false` means there is
                // no schema file for the build to check.
                //
                // Two divergences are tempting and both are wrong: reordering the
                // `id` constraints, and adding `DEFAULT 0` to `retryCount`. Room
                // emits no default here (the Kotlin `= 0` is a constructor default,
                // and Room's generated INSERT always binds every column), and while
                // `TableInfo.Column.equals` happens to skip the default comparison
                // when the entity side is null, that is a detail of the comparator
                // rather than a contract. Matching the generator exactly means the
                // question never arises.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_crossings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`placeId` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, " +
                        "`crossingType` TEXT NOT NULL, " +
                        "`occurredAtMs` INTEGER NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                // Room compares INDICES as well as columns, so omitting this fails
                // validation just as a missing column would.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_crossings_occurredAtMs` " +
                        "ON `pending_crossings` (`occurredAtMs`)"
                )
            }
        }

        /**
         * Carry `allow_alerts_on_me` consent onto the two rows that decide whether
         * a subscriber-side alert may fire.
         *
         * Both columns are NULLABLE, and that is load-bearing twice over:
         *
         *  1. SEMANTICS. NULL means "this backend did not tell us", which every
         *     consent gate in this system reads as ALLOWED. Most members have no
         *     `privacy_settings` row at all, so a non-null column defaulting to
         *     "suppressed" would silently disable alerts across the estate — and a
         *     silently disabled alert is indistinguishable from a broken feature.
         *  2. SCHEMA. SQLite cannot ADD a NOT NULL column without a DEFAULT, while
         *     Room emits no default for a Kotlin constructor default (see the
         *     verbatim-DDL note on MIGRATION_35_36 — the same trap that made
         *     `retryCount` tempting to write as `DEFAULT 0`). Nullable columns need
         *     no default, so the migrated schema matches what Room generates.
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `locations` ADD COLUMN `allowAlertsOnMe` INTEGER")
                db.execSQL("ALTER TABLE `place_alerts` ADD COLUMN `suppressedByTarget` INTEGER")
            }
        }

        /**
         * The per-circle location queue for a device that cannot share with a circle yet;
         * see QueuedCircleLocationEntity. DDL copied VERBATIM from Room's generated
         * `createAllTables` for the entity, for the reason MIGRATION_35_36 gives.
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queued_circle_locations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`circleId` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`altitude` REAL, " +
                        "`speed` REAL, " +
                        "`heading` REAL, " +
                        "`accuracy` REAL, " +
                        "`batteryLevel` INTEGER, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_queued_circle_locations_circleId` " +
                        "ON `queued_circle_locations` (`circleId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_queued_circle_locations_timestamp` " +
                        "ON `queued_circle_locations` (`timestamp`)"
                )
            }
        }

        /**
         * Changes waiting to sync, and the lines dropped ones leave; see
         * PendingChangeEntity. DDL copied VERBATIM from Room's generated `createAllTables`
         * for the entities, for the reason MIGRATION_35_36 gives.
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_changes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`circleId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`inputs` TEXT NOT NULL, " +
                        "`seen` TEXT, " +
                        "`signedIds` TEXT NOT NULL, " +
                        "`target` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`attemptId` TEXT, " +
                        "`startedAt` INTEGER, " +
                        "`question` TEXT, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`nextAttemptAt` INTEGER NOT NULL, " +
                        "`lastOutcome` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_changes_account` ON `pending_changes` (`account`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_changes_circleId` ON `pending_changes` (`circleId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dropped_notices` (" +
                        "`id` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`circleId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`target` TEXT, " +
                        "`line` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`seenAt` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dropped_notices_account` ON `dropped_notices` (`account`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dropped_notices_circleId` ON `dropped_notices` (`circleId`)")
            }
        }

        /**
         * A destination's creator as the server records it: "mine" needs it AND the sealed id to
         * name this account. Nullable with no default, as Room makes the column (see
         * MIGRATION_36_37): a row kept from before has NULL, so it isn't this account's until the
         * next list refresh fills it in.
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `destinations` ADD COLUMN `creatorId` TEXT")
            }
        }

        /**
         * Whether a location row opened under the circle's CURRENT key version. NOT NULL DEFAULT 0:
         * every row cached before is HISTORY until a fetch decides it again, so a row an old key opened
         * before this update can never stay a member's marker. The fetch's fast path paints markers
         * meanwhile.
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `locations` ADD COLUMN `keyCurrent` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Whether a member's display name opened here with the circle's key (an SOS alarm names its
         * raiser only by such a name). NOT NULL DEFAULT 0: a row kept from before reads as the server's
         * words until the next member refresh decides it.
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `circle_members` ADD COLUMN `nameOpened` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Why a member's location may not be updating: "off", "paused" or NULL for sharing, and a
         * timed pause's end. Nullable with no default, as Room makes the columns (see
         * MIGRATION_39_40): a row kept from before reads as sharing until the next member refresh
         * fills it in.
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `circle_members` ADD COLUMN `locationSharing` TEXT")
                db.execSQL("ALTER TABLE `circle_members` ADD COLUMN `sharingPausedUntil` INTEGER")
            }
        }

        /**
         * While paused, the mode a resume goes back to (the mode held before the pause), so a
         * timed pause that ends on this phone sends as it did before
         * ([com.pingolin.track.data.local.entity.sendsBubble]). Nullable with no default; a row
         * kept from before reads as exact until the next privacy sync fills it in.
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `privacy_settings` ADD COLUMN `modeBeforePause` TEXT")
            }
        }

        /**
         * An SOS's position as sealed: when it was taken, its accuracy, and whether none could be
         * sealed yet. Nullable with no default; a row kept from before reads as a position taken
         * when it was sent, until the next refresh.
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sos_alerts` ADD COLUMN `fixAt` INTEGER")
                db.execSQL("ALTER TABLE `sos_alerts` ADD COLUMN `accuracy` REAL")
                db.execSQL("ALTER TABLE `sos_alerts` ADD COLUMN `positionState` TEXT")
            }
        }

        /**
         * This account's own "Allow alerts on me" per circle, beside the battery switch it gates
         * (the battery level goes out only while both are on). Nullable with no default: a row kept
         * from before reads as allowed until the next privacy refresh fills it.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `privacy_settings` ADD COLUMN `allowAlertsOnMe` INTEGER")
            }
        }

        /**
         * When this phone last saw each watched member not sharing in a circle; see
         * MemberNotSharingSeenEntity. DDL copied VERBATIM from Room's generated `createAllTables`
         * for the entity, for the reason MIGRATION_35_36 gives.
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `member_not_sharing_seen` (" +
                        "`circleId` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`seenUntil` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`circleId`, `userId`))"
                )
            }
        }

        /**
         * Whether each member shows the circle their location history. Nullable with no default, as
         * Room makes the column (see MIGRATION_36_37): a member kept from before shows it until the
         * next member refresh says otherwise.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `circle_members` ADD COLUMN `historySharing` TEXT")
            }
        }

        /**
         * The place-alert notifications in the shade, for the other path of the same crossing to pair with
         * (PlaceAlertShownEntity). New and empty: nothing from before pairs with anything.
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place_alert_shown` (" +
                        "`notificationId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`placeId` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`transition` TEXT, " +
                        "`eventAt` INTEGER, " +
                        "`shownAt` INTEGER NOT NULL, " +
                        "`consumed` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`notificationId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_place_alert_shown_placeId_userId` " +
                        "ON `place_alert_shown` (`placeId`, `userId`)"
                )
            }
        }
    }
}
