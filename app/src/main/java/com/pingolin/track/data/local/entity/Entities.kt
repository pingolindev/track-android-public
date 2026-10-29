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

package com.pingolin.track.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pingolin.track.util.AccountIds

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String?,
    val publicKey: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "circles")
data class CircleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val encryptedKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isOwner: Boolean = false,
    /** True if key rotation is needed (a member left) */
    val keyRotationNeeded: Boolean = false,
    /** Current key version for this circle */
    val keyVersion: Int = 1
)

/**
 * Per-user, device-local ordering of circles in the Circles tab. Kept in a
 * separate table (not a column on circles) so the server refresh of the
 * circles table — which has no notion of this personal ordering — never
 * clobbers it. circleId is unconstrained by a FK on purpose: orphan rows for
 * a deleted circle are harmless (the LEFT JOIN in observeAll just ignores
 * them) and decoupling avoids any interaction with the circle upsert path.
 */
@Entity(tableName = "circle_sort_order")
data class CircleSortOrderEntity(
    @PrimaryKey val circleId: String,
    val sortOrder: Int
)

@Entity(
    tableName = "circle_members",
    primaryKeys = ["circleId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = CircleEntity::class,
            parentColumns = ["id"],
            childColumns = ["circleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("circleId"), Index("userId")]
)
data class CircleMemberEntity(
    val circleId: String,
    val userId: String,
    val displayName: String,
    val role: String, // owner, admin, member
    val canSeeRealtime: Boolean = true,
    val canSeeHistory: Boolean = true,
    val joinedAt: Long,
    /**
     * [displayName] opened here with the circle's key, the name the member sealed: never the
     * server's words. False for the list's plain fallback name, and for rows kept from before Room
     * v42 until the next member refresh. An SOS alarm names its raiser only by such a name.
     */
    @ColumnInfo(defaultValue = "0") val nameOpened: Boolean = false,
    /**
     * This member's location sharing in the circle as the server reports it: "off", "paused",
     * or null for sharing as normal (also a row kept from before Room v43, until the next member
     * refresh). Read through [com.pingolin.track.ui.circles.MemberSharing].
     */
    val locationSharing: String? = null,
    /** When a timed pause ends (epoch ms) while [locationSharing] is "paused"; null otherwise. */
    val sharingPausedUntil: Long? = null,
    /**
     * "off" while this member keeps their location history from the circle, null when they show
     * it (also a row kept from before Room v48, until the next member refresh). No viewer shows a
     * past position of theirs meanwhile: see [com.pingolin.track.ui.circles.MemberSharing.historyOff].
     */
    val historySharing: String? = null,
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val platform: String, // android, ios
    val lastSeenAt: Long?,
    val createdAt: Long
)

@Entity(
    tableName = "places",
    // Note: No foreign key on circleId because personal places use "self" as circleId
    // which is not a real circle in the circles table
    indices = [Index("circleId")]
)
data class PlaceEntity(
    @PrimaryKey val id: String,
    val circleId: String,
    val name: String,
    /** Optional free-form user notes/description. Transmitted E2EE to the
     * server as `encrypted_notes`. */
    val notes: String? = null,
    val address: String?, // Geocoded address for display
    val latitude: Double,
    val longitude: Double,
    val radius: Int, // meters
    val icon: String?,
    val color: String?,
    /** Place type: "place" or "boundary" */
    val placeType: String = "place",
    /** Optional geofence boundary radius in meters (200-25000). Null means no boundary. */
    val boundaryRadius: Int? = null,
    /** Server-maintained: true iff an active alert subscription targets this place.
     * Geofence monitoring is gated on this ("an alert turns monitoring on") rather
     * than on placeType/boundaryRadius. defaultValue matches the v25→26 migration's
     * NOT NULL DEFAULT 0 so Room's open-time schema check passes. */
    @ColumnInfo(defaultValue = "0")
    val alertsEnabled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Int = 0, // Local-only field for user's preferred ordering (not synced to server)
    /** Comma-separated circle IDs this place is shared with (envelope encryption) */
    val sharedCircles: String? = null,
    /** Per-place symmetric key (hex), cached after the envelope is unwrapped so
     *  share/edit flows don't need an extra unwrap round-trip. NULL on legacy
     *  circle-owned places that encrypt directly under the circle key. */
    val placeKey: String? = null,
    /** Set when this user reads the place via a place_shares row (i.e.
     *  another circle member shared their place into a circle the user
     *  belongs to). Holds the circle ID the wrap came through, lets the
     *  UI render a "Shared via <circle>" tag and distinguish received
     *  shares from the user's own places. NULL for places the user
     *  owns. Populated from PlaceDto.wrapViaCircle. */
    val wrapViaCircle: String? = null,
    /** Pseudonymous owner user id, surfaced by the server only on
     *  share-recipient reads (alongside wrapViaCircle). Lets the UI
     *  render "Shared by <member>" attribution by resolving the id
     *  against the local circle-members list. NULL on owner-only
     *  reads — the server keeps owner identity off the wire there. */
    val ownerUserId: String? = null,
    /** Server-authoritative ownership: true iff this user created the place.
     * Gates the owner-only radius tuner in the alert bell — the
     * `wrapViaCircle == null` proxy alone would misidentify a non-creator
     * member of a legacy circle place as owner, and their updatePlace would
     * get a 422. Populated from PlaceResponse.is_owner; falls back to
     * `wrapViaCircle == null` when the server omits it. defaultValue "1"
     * matches the v29→30 migration's NOT NULL DEFAULT 1 (owner is the common
     * case) so Room's schema check passes. */
    @ColumnInfo(defaultValue = "1")
    val isOwner: Boolean = true
)

@Entity(
    tableName = "place_alerts",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["placeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("placeId"), Index("targetUserId"), Index("isActive")]
)
data class PlaceAlertEntity(
    @PrimaryKey val id: String,
    val placeId: String,
    /** The circle this alert routes/fires under (from PlaceAlertResponse.circle_id).
     * An alert on a personal place is homed to a REAL circle it's shared to — NOT
     * the device "self" key — so match-hash re-derivation must use THIS, not
     * place.circleId. NULL when the backend omits it; consumers then fall
     * back to place.circleId (guarding the self sentinel). */
    val circleId: String? = null,
    /** User ID of the alert creator */
    val userId: String,
    /** User ID whose location is being monitored (decrypted from encrypted_target_user_id) */
    val targetUserId: String,
    /** Whether to alert on arrival */
    val alertOnArrival: Boolean = true,
    /** Whether to alert on departure */
    val alertOnDeparture: Boolean = false,
    /** Active days (comma-separated: "monday,tuesday,wednesday") */
    val activeDays: String? = null,
    /** Start time for active window (HH:MM format) */
    val activeStartTime: String? = null,
    /** End time for active window (HH:MM format) */
    val activeEndTime: String? = null,
    /** True if time window spans overnight (e.g., 22:00 - 06:00) */
    val isOvernight: Boolean = false,
    /** Whether push notifications are enabled */
    val notifyPush: Boolean = true,
    /** Whether email notifications are enabled */
    val notifyEmail: Boolean = false,
    /** Whether SMS notifications are enabled */
    val notifySms: Boolean = false,
    /** Whether this is a recurring alert */
    val isRecurring: Boolean = false,
    /** Whether this alert is currently active */
    val isActive: Boolean = true,
    /** Expiration timestamp (null = never expires) */
    val expiresAt: Long? = null,
    /** Day bucket when created (days since Unix epoch) */
    val dayBucket: Int,
    /**
     * The TARGET has withdrawn `allow_alerts_on_me` in the circle this alert's
     * consent is anchored to (`PlaceAlertResponse.suppressed_by_target`, computed
     * server-side so the anchor — the place's circle, falling back to the
     * subscription's — is resolved in ONE place).
     *
     * The alert stays ACTIVE and fully configured: withdrawal SUSPENDS it, so
     * consent returning restores exactly what the subscriber set up. NULL means a
     * backend that does not report it, which is ALLOWED — absence permits, as at
     * every other consent gate in this system.
     */
    val suppressedByTarget: Boolean? = null
)

@Entity(
    tableName = "locations",
    indices = [
        Index("circleId"),
        Index("userId"),
        Index("timestamp"),
        Index("dayBucket"),
        Index(value = ["dayBucket", "circleId"]),
        // Unique per (circle, user, timestamp): collapses the live poll re-inserting the
        // same stationary fix every tick (it is what makes OnConflictStrategy.REPLACE
        // fire), and turns observeLatestByCircle's per-user MAX(timestamp) into an index
        // seek instead of an O(N²) correlated scan that pins the CPU as the table grows.
        Index(value = ["circleId", "userId", "timestamp"], unique = true)
    ]
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val circleId: String,
    val userId: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val speed: Double?,
    val heading: Double?,
    val accuracy: Double?,
    val batteryLevel: Int?,
    val timestamp: Long,
    val isSynced: Boolean = false,
    /** Day bucket for cache indexing (days since Unix epoch = timestamp / 86400000) */
    val dayBucket: Int = (timestamp / 86400000).toInt(),
    /**
     * `allow_alerts_on_me` for this member in this circle, as the server reported
     * it ALONGSIDE THIS FIX (`LocationResponse.allow_alerts_on_me`).
     *
     * Stored on the location rather than in a per-member table on purpose: it is
     * an instruction about this datum, so each fix keeps the posture it arrived
     * with, and a history page replayed into the evaluator carries the posture it
     * was fetched with. It can be staler than the server's word, though: turning
     * the switch off sends no fix, and the server serves the same fix again with
     * the new "no", which the replay guard drops as not newer. So a "no" from a
     * dropped row is handed to the member's newest row
     * ([com.pingolin.track.data.local.dao.LocationDao.lowerAlertsConsent]),
     * and a fix held with a "no" keeps it when the same fix is stored again. A
     * withdrawal governs the judgments made now, about any fix held; a "yes" never
     * comes back from a fix already held: only the member's next fix raises it.
     *
     * NULL = UNKNOWN = allowed. Our own fixes and self-circle rows leave it null;
     * there is no one to consent to when the viewer is the subject.
     */
    val allowAlertsOnMe: Boolean? = null,
    /**
     * Whether the circle key version that opened this row is the one this device seals the
     * circle under now (the SDK's isCurrentKeyVersion). False: HISTORY, a row only an older
     * (or not yet taken) key opened. Shown in history, never live: never a member's marker or
     * "last seen", never place-alert input, never the replay guard's. Every query feeding a
     * live view reads `keyCurrent = 1` (LocationQueriesTest). A row cached before the column
     * is history until a fetch decides it again (MIGRATION_40_41).
     */
    @ColumnInfo(defaultValue = "0")
    val keyCurrent: Boolean = true,
)

@Entity(
    tableName = "check_ins",
    // observeByCircle filters by circleId and sorts by timestamp DESC (CheckInDao).
    indices = [Index(value = ["circleId", "timestamp"])]
)
data class CheckInEntity(
    @PrimaryKey val id: String,
    val circleId: String,
    val userId: String,
    val type: String, // safe_arrival, arrived, general, emergency_ok
    val message: String?,
    val latitude: Double?,
    val longitude: Double?,
    val placeId: String?,
    val timestamp: Long,
    val isSynced: Boolean = false
)

@Entity(
    tableName = "sos_alerts",
    // observeActive filters on status = 'active' (SosAlertDao).
    indices = [Index("status")]
)
data class SosAlertEntity(
    @PrimaryKey val id: String,
    val circleId: String,
    val userId: String,
    val status: String, // active, acknowledged, resolved, false_alarm
    val latitude: Double?,
    val longitude: Double?,
    val acknowledgedBy: String?, // JSON array of user IDs
    val createdAt: Long,
    val resolvedAt: Long?,
    /**
     * Did THIS user acknowledge the alert? Server-side this is a per-member
     * acknowledgment, not a change to [status], so acknowledging leaves the
     * alert in every other member's view. Clears the map icon (it shows
     * "until acknowledged") while the Safety entry stays, so a responder
     * keeps the location.
     */
    val acknowledgedByMe: Boolean = false,
    /**
     * Did THIS user dismiss the alert? Purely local to the viewer — hides it
     * from the map AND the Safety list, tells no one, closes nothing.
     */
    val dismissedByMe: Boolean = false,
    /**
     * When THIS user dismissed it (epoch millis), or null. Drives the Safety
     * screen's grace period: a dismissed alert stays listed, greyed and
     * counting down, for [SosAlertEntity.DISMISS_GRACE_MS] so an accidental
     * dismissal can be undone — after which it drops off the list. The MAP
     * hides it immediately either way.
     */
    val dismissedAt: Long? = null,
    /** How many members have acknowledged. Count only; identities aren't disclosed. */
    val acknowledgmentCount: Int = 0,
    /**
     * When the sealed position was TAKEN (its `Location.timestamp`), not when the alert was sent
     * ([createdAt]): a last-known position is older, an update later. Null for a row kept from
     * before it, which reads as taken when sent.
     */
    val fixAt: Long? = null,
    /** The sealed position's accuracy in metres, when the raiser's phone gave one; past 100 m it is drawn as an area. */
    val accuracy: Double? = null,
    /**
     * No position sealed, as the raiser's phone said: [POSITION_PENDING] while it is still
     * getting one, [POSITION_UNAVAILABLE] when one can't be sent. Null when a position was
     * sealed, or for a row kept from before.
     */
    val positionState: String? = null,
) {
    companion object {
        /** [positionState]: the raiser's phone is still getting a location ("Sending current location"). */
        const val POSITION_PENDING = "pending"

        /** [positionState]: a location can't be sent ("Location unavailable"). */
        const val POSITION_UNAVAILABLE = "unavail"

        /**
         * How long a dismissed alert lingers on the Safety screen before it
         * drops off. The map hides it immediately; this window exists purely so
         * a mistaken dismissal of a live emergency can be undone.
         */
        const val DISMISS_GRACE_MS = 24L * 60 * 60 * 1000
    }
}

@Entity(tableName = "privacy_settings")
data class PrivacySettingEntity(
    @PrimaryKey val circleId: String,
    val shareRealtime: Boolean = true,
    val shareHistory: Boolean = true,
    val shareBattery: Boolean = true,
    /**
     * This account's own "Allow alerts on me" in the circle (`allow_alerts_on_me`), as the server last
     * said. Null on a row kept from before it was stored, until the next privacy refresh fills it: read
     * as allowed, the server's own reading of a missing consent field. See [sendsBatteryLevel].
     */
    val allowAlertsOnMe: Boolean? = null,
    val sharingMode: String = "exact",
    val approximateRadiusMiles: Int? = null,
    val pausedUntil: Long? = null,
    /** While paused: the mode a resume goes back to, "exact" or "approximate" (null = exact). See [sendsBubble]. */
    val modeBeforePause: String? = null,
    /** Primary device to show on map. If null, shows most recent device. */
    val primaryDeviceId: String? = null,
    /** Primary device pseudonym (required by backend when primaryDeviceId is set). */
    val primaryDevicePseudonym: String? = null,
    /** If true, show all devices on map. If false, show only primary device. */
    val showAllDevices: Boolean = false,
    val updatedAt: Long
) {
    companion object {
        /** `sharing_mode` value that means "stop sharing my location with this circle". */
        const val MODE_PAUSED = "paused"
        /** `sharing_mode` value that means "share a bubble, not a point". */
        const val MODE_APPROXIMATE = "approximate"
        /** `sharing_mode` value that means "share my real position". */
        const val MODE_EXACT = "exact"
    }
}

/**
 * True when this circle's sharing is paused *right now*.
 *
 * A pause is a MODE plus an OPTIONAL deadline, and the deadline alone does not
 * describe it. `pausedUntil == null` means "paused until I resume", so a
 * `pausedUntil != null && pausedUntil > now` check reads an indefinite pause
 * as un-paused and keeps uploading exact fixes.
 *
 * This mirrors the server's own visibility rule, which is stated the other way
 * round — a paused member is shown only once a deadline exists AND has passed.
 *
 * Every send-path gate must use this rather than re-deriving the predicate.
 */
fun PrivacySettingEntity.isSharingPausedAt(now: Long = System.currentTimeMillis()): Boolean {
    if (sharingMode != PrivacySettingEntity.MODE_PAUSED) return false
    val deadline = pausedUntil ?: return true // no deadline = paused until resumed
    return deadline > now
}

/**
 * Whether this circle gets this phone's battery level: only while its battery switch AND
 * "Allow alerts on me" are on. The level's one reader is the low-battery alert, which can't
 * be set on this person while alerts on them are off; sent anyway, it would only tell the circle
 * something no alert asked for. Every send path gates the level with this, not with [shareBattery]
 * alone.
 */
fun PrivacySettingEntity.sendsBatteryLevel(): Boolean = shareBattery && allowAlertsOnMe != false

/**
 * Whether positions for this circle go out as a bubble rather than the exact point: approximate
 * mode, or a pause whose mode before it was approximate (the mode held before the pause, the
 * server's `mode_before_pause`).
 *
 * Positions are coarsened only on this phone. When a timed pause ends, the phone sends again from
 * the deadline ([isSharingPausedAt]) while this row still says "paused" until the next privacy
 * re-sync, so reading only "approximate" would send the EXACT point of someone who had a bubble
 * before the pause. Nothing is sent while a pause runs, so the rule needs no clock.
 * `approximate_until` isn't kept here: in that window the bubble stays even where the server
 * would go back to exact, which errs private until the re-sync corrects it.
 *
 * Every send-path coarsening must use this rather than re-deriving it.
 */
fun PrivacySettingEntity.sendsBubble(): Boolean =
    sharingMode == PrivacySettingEntity.MODE_APPROXIMATE ||
        (sharingMode == PrivacySettingEntity.MODE_PAUSED && modeBeforePause == PrivacySettingEntity.MODE_APPROXIMATE)

@Entity(tableName = "pending_locations")
data class PendingLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val circleId: String,
    val encryptedPayload: String,
    val timestamp: Long,
    val retryCount: Int = 0,
    val createdAt: Long,
    /** Envelope-encryption wrap for this target (hex nonce/ciphertext).
     *  Set only for rows queued from the envelope path (sendEnvelopeLocation),
     *  where the payload is sealed under a RANDOM per-call msg_key recoverable
     *  only via this wrap. The drain MUST replay these via sendEnvelopeLocation
     *  or the payload is undecryptable forever. NULL for bare rows
     *  (self-tracking, storeLocalLocation, and session-key sends), which drain
     *  via the bare sendLocation path — see [keyId] for how those name a key. */
    val wrappedKey: String? = null,
    val wrappedKeyNonce: String? = null,
    /** Which key sealed [encryptedPayload], in wire format: "s:{uuid}" for a
     * session key, "c:{version}" for the circle-key fallback.
     *
     * Set for rows queued from the session-key send path. NULL for the plain
     * circle-key encrypts (self-tracking, storeLocalLocation) and for envelope
     * rows, which name their key via [wrappedKey] instead.
     *
     * This column exists because a session-key-sealed payload does NOT
     * "decrypt directly under the circle key". Without the id the drain would
     * replay such a row with an empty key_id and no receiver could ever
     * select its key again. */
    val keyId: String? = null
)

/**
 * A location fix kept on this phone for a circle it cannot share with yet: the circle's
 * key moved on without this device, or it cannot open the circle at all.
 * "Awaiting sync for circle settings from another member. Locations will be queued until
 * synced." — this is the queue that sentence promises.
 *
 * The circle's privacy is applied at CAPTURE: a paused or realtime-off circle queues
 * nothing, and an "approximate" circle stores the coarsened point, never the exact one.
 * Plaintext, like the device's own `locations` history (the database itself is
 * encrypted), because there is no current key to seal it under — that is the reason it
 * waits. Sent as history once the device holds the circle's current key; kept 48 hours
 * at most, oldest dropped first.
 */
@Entity(tableName = "queued_circle_locations", indices = [Index("circleId"), Index("timestamp")])
data class QueuedCircleLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val circleId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speed: Double? = null,
    val heading: Double? = null,
    val accuracy: Double? = null,
    /** Null when the circle does not get battery levels (shareBattery off at capture). */
    val batteryLevel: Int? = null,
    /** When the fix was taken — what the history row carries. */
    val timestamp: Long,
    val createdAt: Long,
)

/**
 * A boundary crossing that has not reached the server yet.
 *
 * Crossings need an outbox just as LOCATIONS have one ([PendingLocationEntity]):
 * a crossing that loses its request — radio down on a cold wake, a 5xx, the
 * process dying mid-flight — would otherwise be gone with no trace. That failure
 * is invisible by construction: the alert simply never fires, and nothing
 * distinguishes that from a crossing nobody subscribed to.
 *
 * ## Why this queues the INPUTS, not a ciphertext
 *
 * [PendingLocationEntity] stores an encrypted payload and therefore has to carry
 * `wrappedKey` / `keyId` so the drain can name the key that sealed it — a whole
 * class of "undecryptable forever" failures documented on those columns. A
 * crossing needs none of that: `BoundaryRepository.reportCrossingFromMonitor`
 * derives the circle set, the BLAKE3 hash and every ciphertext from these
 * plaintext inputs at SEND time, so replaying the inputs re-derives everything
 * under whatever key is current. A rotation between crossing and replay is then
 * a non-event.
 *
 * Two consequences of replaying through that function, both deliberate:
 *
 *  - The privacy posture is re-evaluated at REPLAY time, so a circle the user has
 *    paused since the crossing is skipped. Fail-closed is the right direction for
 *    a presence signal; the alternative is honouring consent that was withdrawn.
 *  - The circle set is re-resolved, so a place un-shared in the meantime emits
 *    nothing — which is the same answer the live path would now give.
 */
@Entity(tableName = "pending_crossings", indices = [Index("occurredAtMs")])
data class PendingCrossingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: String,
    /** The crossing user — us. Stored rather than read from the session at replay
     *  so a queued crossing cannot be re-attributed if the account changes. */
    val userId: String,
    val deviceId: String,
    /** [com.pingolin.track.domain.repository.BoundaryRepository.EVENT_TYPE_ENTER]
     *  or `EVENT_TYPE_EXIT`. Plaintext here and hashed at send time, exactly as
     *  the live path does — this row never leaves the device. */
    val crossingType: String,
    /** When the crossing HAPPENED, carried so the replay stamps the real time
     *  rather than the replay time. This is the same value the live path seals
     *  into `encrypted_occurred_at`. */
    val occurredAtMs: Long,
    val retryCount: Int = 0,
    val createdAt: Long
)

@Entity(
    tableName = "destinations",
    indices = [Index("circleId"), Index("userId"), Index("status")]
)
data class DestinationEntity(
    @PrimaryKey val id: String,
    /** User ID who created the destination (decrypted from encrypted_user_id) */
    val userId: String,
    val circleId: String,
    /** Decrypted destination name */
    val destName: String?,
    /** Decrypted destination latitude */
    val destLatitude: Double,
    /** Decrypted destination longitude */
    val destLongitude: Double,
    /** Day bucket for estimated arrival (days since Unix epoch) */
    val etaDayBucket: Int?,
    /** Whether to notify circle on arrival */
    val notifyArrival: Boolean = true,
    /** Status: active, arrived, cancelled, expired */
    val status: String = "active",
    /** Day bucket when sharing was started */
    val startedDayBucket: Int,
    /** Day bucket when user arrived (null if not arrived) */
    val arrivedDayBucket: Int? = null,
    /** Day bucket when created */
    val createdDayBucket: Int,
    /** Day bucket when last updated */
    val updatedDayBucket: Int,
    /**
     * The creator as the server records it (DestinationResponse.userId, in its one spelling);
     * [userId] is the one the creator sealed. Null: the server didn't say, or the row was kept
     * from before this column (MIGRATION_39_40), until the next list refresh.
     */
    val creatorId: String? = null,
) {
    /**
     * Whether this is this account's own destination: the sealed id AND the server's creator both
     * name [me], in any spelling. Any member can seal any id, and the server alone can say
     * anything; neither half alone makes a destination this account's to arrive at by itself,
     * cancel or delete, or "Your destination".
     */
    fun isMine(me: String?): Boolean = AccountIds.same(userId, me) && AccountIds.same(creatorId, me)

    /** [isMine] with its no answer apart: a row sealed as [me] whose creator isn't known yet. */
    fun ownership(me: String?): Ownership = when {
        !AccountIds.same(userId, me) -> Ownership.NOT_MINE
        creatorId == null -> Ownership.UNKNOWN
        AccountIds.same(creatorId, me) -> Ownership.MINE
        else -> Ownership.NOT_MINE
    }

    enum class Ownership { MINE, NOT_MINE, UNKNOWN }
}

@Entity(
    tableName = "inactivity_alerts",
    indices = [Index("circleId"), Index("targetUserId")]
)
data class InactivityAlertEntity(
    @PrimaryKey val id: String,
    /** User ID who created the alert */
    val userId: String,
    val circleId: String,
    /** User ID whose inactivity is being monitored (decrypted from encrypted_target_user_id) */
    val targetUserId: String,
    /** Timeout in hours (hour-level granularity, 1-24) */
    val timeoutHours: Int,
    /** If true, notify the circle when the creator is inactive */
    val isSelfAlert: Boolean = false,
    /** Whether push notifications are enabled */
    val notifyPush: Boolean = true,
    /** Whether email notifications are enabled */
    val notifyEmail: Boolean = false,
    /** Opt-in: also alert when the target is seen again ("back online") */
    val notifyOnReturn: Boolean = false,
    /** Minimum minutes between alerts (15-1440) */
    val cooldownMinutes: Int = 60,
    /** Whether this alert is currently active */
    val isActive: Boolean = true,
    /** Day bucket of last trigger (days since Unix epoch) */
    val lastTriggeredDayBucket: Int? = null,
    /** Day bucket when created */
    val dayBucket: Int,
    /** If true, this alert requires client-side evaluation */
    val clientOnly: Boolean = false,
    /** Whom this alert watches, for client-side evaluation. NULL = everyone in the circle;
     *  a comma-joined list of user IDs = those specific members. Decoded from encrypted_conditions. */
    val targetUserIds: String? = null
)

@Entity(
    tableName = "alert_rules",
    indices = [Index("circleId"), Index("targetUserId"), Index("ruleType")]
)
data class AlertRuleEntity(
    @PrimaryKey val id: String,
    /** User ID who created the rule */
    val userId: String,
    val circleId: String?,
    /** User ID whose status is being monitored (decrypted from encrypted_target_user_id) */
    val targetUserId: String,
    /** Rule type: 'low_battery', 'offline' */
    val ruleType: String,
    /** Whether this rule is currently active */
    val isActive: Boolean = true,
    /** Whether push notifications are enabled */
    val notifyPush: Boolean = true,
    /** Whether email notifications are enabled */
    val notifyEmail: Boolean = false,
    /** Whether SMS notifications are enabled */
    val notifySms: Boolean = false,
    /** Minimum minutes between alerts */
    val cooldownMinutes: Int? = null,
    /**
     * Battery percentage this rule fires at, for `low_battery` rules.
     *
     * Held locally because the comparison is local: nothing on the server ever
     * reads a battery level, so the threshold has to be on the device that does
     * the comparing, and it has to be there when a silent wake-up arrives rather
     * than a round-trip away.
     */
    val batteryThreshold: Int? = null,
    /** Day bucket when created */
    val dayBucket: Int
)

@Entity(
    tableName = "notifications",
    indices = [Index("circleId"), Index("isRead"), Index("createdDayBucket")]
)
data class NotificationEntity(
    @PrimaryKey val id: String,
    val circleId: String?,
    /** Notification type: sos, arrival, departure, low_battery, etc. */
    val notificationType: String,
    val title: String,
    val body: String?,
    /** JSON-encoded metadata */
    val metadata: String?,
    /** Whether this notification has been read */
    val isRead: Boolean = false,
    /** Day bucket when created (UTC days since epoch) — kept for ordering. */
    val createdDayBucket: Int,
    /** Exact creation time (epoch millis), when the server provides it. Drives
     *  local-timezone "Today"/"Yesterday" labels; null falls back to the
     *  UTC-based [createdDayBucket]. */
    val createdAt: Long? = null
)

/**
 * A client-side low-battery firing, recorded on-device for the same reason as
 * [LocalInactivityEventEntity]: the comparison happens here and is never
 * reported to the server, so there is no server row for the bell to fetch.
 *
 * An unresolved row is also the fire-once marker — it survives a process
 * restart, which an in-memory flag would not, and it is what stops a watched
 * phone sitting at 8% from re-notifying on every wake-up.
 */
@Entity(tableName = "local_battery_events")
data class LocalBatteryEventEntity(
    @PrimaryKey val id: String,
    /** The low-battery rule that fired. */
    val alertRuleId: String,
    val circleId: String,
    /** Watched member's user id — stable key for matching/resolving an open drain. */
    val targetUserId: String,
    /** Watched member's display name, captured at firing time. */
    val targetDisplayName: String,
    /** The level that tripped the rule, as a percentage. */
    val batteryLevel: Int,
    /** Exact firing time (epoch millis). */
    val triggeredAt: Long,
    /** UTC day bucket of [triggeredAt], for ordering alongside server events. */
    val triggeredDayBucket: Int,
    /** Set when the member charged back above the threshold; null = still open. */
    val resolvedAt: Long? = null
)

/**
 * A client-side inactivity firing, recorded on-device so it shows in the in-app
 * notifications bell and the alert-rules history. Inactivity rules about other members
 * are evaluated on this device, every one of them and not only the client_only kind
 * (MonitoringManager.evaluateInactivity). The server is told of a firing only sealed under
 * this account's own key, for its other devices (MonitoringManager.reportAlertEvent), so
 * it holds nothing it can read — this device-local table is what the bell shows here.
 */
@Entity(tableName = "local_inactivity_events")
data class LocalInactivityEventEntity(
    @PrimaryKey val id: String,
    /** The inactivity rule that fired. */
    val inactivityAlertId: String,
    val circleId: String,
    /** Watched member's user id — stable key for matching/auto-resolving an open lapse. */
    val targetUserId: String,
    /** Watched member's display name, captured at firing time. */
    val targetDisplayName: String,
    /** Rule timeout in hours (drives the "no location for Nh" copy). */
    val timeoutHours: Int,
    /** How long the member had been silent when this fired (seconds). */
    val inactiveSeconds: Long,
    /** Exact firing time (epoch millis). */
    val triggeredAt: Long,
    /** UTC day bucket of [triggeredAt], for ordering alongside server events. */
    val triggeredDayBucket: Int,
    /** Set when the member shared location again (lapse auto-resolved); null = still open. */
    val resolvedAt: Long? = null
)

/**
 * When this phone last saw a member not sharing in a circle (paused, or real-time sharing off), or a
 * timed pause's planned end when that is later. The inactivity evaluators count the member's
 * silence from the later of their last-seen and this, never after now: the server's own rule, the
 * later of last-seen and the latest span end, as near as this phone can know the span's end. The
 * members list says whether someone is sharing now, not when they stopped or started again. Without
 * it, a timed pause that runs out on a still phone is "hasn't shared since …" about the pause itself.
 *
 * Keyed by the ids' one form ([com.pingolin.track.util.AccountIds.storeKey]). Kept 8 days
 * ([com.pingolin.track.util.MonitoringManager.NOT_SHARING_KEEP_MS]), longer than any timeout; a
 * sign-out empties it with every other table.
 */
@Entity(
    tableName = "member_not_sharing_seen",
    primaryKeys = ["circleId", "userId"]
)
data class MemberNotSharingSeenEntity(
    val circleId: String,
    val userId: String,
    /** Epoch ms: when this phone last saw them not sharing, or the timed pause's planned end when later. */
    val seenUntil: Long,
)

/**
 * Per-(place, member) reconciliation state for SUBSCRIBER-SIDE place alerts.
 *
 * A geofence is defined by state TRANSITIONS, not by "is this point in the zone",
 * so the evaluator has to remember where each member was last seen relative to
 * each place — otherwise a batch replayed point-by-point makes an hour-old inside
 * point look identical to a fresh arrival. Device-local only: the server never
 * sees any of this (it is exactly the computation the blind server cannot do).
 */
@Entity(
    tableName = "place_alert_member_state",
    primaryKeys = ["placeId", "userId"]
)
data class PlaceAlertMemberStateEntity(
    val placeId: String,
    /** The watched member (may be this user, for the "alert me about myself" case). */
    val userId: String,
    /** Whether the last accounted location was inside the place's alert radius. */
    val isInside: Boolean,
    /** Watermark: timestamp of the newest location already folded into [isInside].
     *  Points at or before it are skipped, so an overlapping batch or a replayed
     *  history page can never re-drive a transition. */
    val lastLocationAt: Long
)

/**
 * The de-duplication ledger for place-alert crossings (a given crossing
 * surfaces AT MOST ONCE, EVER).
 *
 * Two independent paths can produce the same crossing — this device's local
 * evaluator, and a server-routed push for a place that also happens to be shared
 * to a circle. Both claim through this table before showing anything, so whichever
 * arrives second is dropped, in either order.
 *
 * Doubles as the record of record for the in-app bell: a locally-evaluated
 * crossing has no server notification row to fetch.
 */
@Entity(
    tableName = "place_alert_events",
    // The cross-path lookup: "did the other path already surface this
    // (place, member, transition) recently?"
    indices = [Index(value = ["placeId", "userId", "transition", "crossedAt"])]
)
data class PlaceAlertEventEntity(
    /** Deterministic crossing identity — "<placeId>:<userId>:<enter|exit>:<crossedAt>".
     *  Keyed on the crossing itself, never on a row id, so it is stable across
     *  process restarts and an INSERT-OR-IGNORE becomes an atomic "have I already
     *  surfaced this?" test. */
    @PrimaryKey val id: String,
    val placeId: String,
    val userId: String,
    /** "enter" or "exit" (BoundaryRepository.EVENT_TYPE_ENTER / _EXIT). */
    val transition: String,
    /** When the crossing ACTUALLY happened — the member location's own
     * timestamp, never "now". Drives the past-framed copy. */
    val crossedAt: Long,
    /** When this device decided the crossing. */
    val surfacedAt: Long,
    /** "local" = this device's evaluator, "server" = a routed push. */
    val source: String,
    /** True when the crossing only updated state and folded into history (older
     *  than the staleness window) rather than raising a notification. A silent
     *  row still blocks re-surfacing, but never suppresses a genuine live push. */
    val silent: Boolean
)

/**
 * A place-alert notification in the shade, and what it said happened, so the OTHER path of the same crossing
 * pairs with it instead of stacking beside it:
 * - a server push, posted at once under its event id, which the named local copy of that crossing then
 *   replaces in place;
 * - a local copy already shown, which the push for that crossing then doesn't repeat.
 *
 * Never a claim in [PlaceAlertEventEntity]: a row here can only ever stop a server push's own post, never a
 * local copy, so nothing a push writes can swallow a crossing this device saw (the tripwire in
 * PlaceAlertEvaluator). Single use: [consumed] once paired. Kept, not in memory, so a process death between
 * the push and the next evaluation pass doesn't lose the pairing.
 */
@Entity(
    tableName = "place_alert_shown",
    indices = [Index(value = ["placeId", "userId"])]
)
data class PlaceAlertShownEntity(
    /** What the notification was posted under: a push's event id, or the local copy's own id (one per
     *  crossing it covers). */
    @PrimaryKey val notificationId: String,
    /** [KIND_SERVER] or [KIND_LOCAL]. */
    val kind: String,
    val placeId: String,
    /** The member, canonical. */
    val userId: String,
    /** "enter" or "exit"; null for a push whose direction this device couldn't read. */
    val transition: String?,
    /** When the crossing happened: a local copy's crossedAt; a push's created_at (the reporter's sealed
     *  event time where there is one); null when a push carried none. */
    val eventAt: Long?,
    /** When this device posted it. */
    val shownAt: Long,
    /** Paired with the other path's copy already: it pairs with nothing else. */
    val consumed: Boolean,
) {
    companion object {
        const val KIND_SERVER = "server"
        const val KIND_LOCAL = "local"
    }
}

@Entity(
    tableName = "pending_invites",
    foreignKeys = [
        ForeignKey(
            entity = CircleEntity::class,
            parentColumns = ["id"],
            childColumns = ["circleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("circleId")]
)
data class PendingInviteEntity(
    @PrimaryKey val id: String,
    val circleId: String,
    /** Invite type (e.g. "email", "link") */
    val inviteType: String? = null,
    /** Email address the invite was sent to */
    val email: String?,
    /** Invite status */
    val status: String? = null,
    /** Day bucket when invite was created */
    val createdDayBucket: Int,
    /** Day bucket when invite expires */
    val expiresDayBucket: Int,
    /** Whether the invite has expired (computed client-side) */
    val isExpired: Boolean = false
)
