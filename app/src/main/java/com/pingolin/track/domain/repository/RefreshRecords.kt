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

import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.KeyChainLinkResponse
import com.pingolin.track.data.remote.dto.MemberResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.ServedCircleHead
import uniffi.pingolin_mobile.CircleHead
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.HeldStatement
import uniffi.pingolin_mobile.KeyChainLinkData
import uniffi.pingolin_mobile.MemberHead
import uniffi.pingolin_mobile.RecordMember
import uniffi.pingolin_mobile.RecordProblem
import uniffi.pingolin_mobile.RecordVersionState
import uniffi.pingolin_mobile.RecordVersionStatus
import uniffi.pingolin_mobile.RefreshRecordsReport
import uniffi.pingolin_mobile.SignedCircleHead
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * The app's side of the SDK's refresh-record checks: every circle's refresh records and heads
 * read from the server, and this device's judged member list turned into what the SDK checks
 * them against. The SDK decides (its record rules, forks); this only carries. Pure, so every
 * rule is unit-tested; CircleRepository fetches and imports.
 */
internal object RefreshRecords {

    /** At most this many pages per read (100 records each): far past any real chain. */
    const val MAX_PAGES = 50

    /**
     * Every circle's records above [since] and its members' heads, read to the last page. Null
     * when a page couldn't be had or the pages never end: a partial read decides nothing, and
     * versions stay as they were (a withheld record is a delay).
     */
    suspend fun fetchAll(
        since: Map<String, Int>,
        read: suspend (Map<String, Int>) -> RefreshRecordsResponse?,
    ): Map<String, CircleRefreshRecords>? {
        val asked = since.toMutableMap()
        val served = linkedMapOf<String, CircleRefreshRecords>()
        repeat(MAX_PAGES) {
            val page = read(asked.toMap()) ?: return null
            for (circle in page.circles) {
                val before = served[circle.circleId]
                served[circle.circleId] = CircleRefreshRecords(
                    circleId = circle.circleId,
                    // As served, in order: two records for one version are a fork the SDK must see.
                    records = before?.records.orEmpty() + circle.records,
                    heads = if (circle.complete) circle.heads else before?.heads.orEmpty(),
                    complete = circle.complete,
                )
                circle.records.lastOrNull()?.let { asked[circle.circleId] = it.version }
            }
            if (!page.more) return served
        }
        return null
    }

    /**
     * The members of [snapshot]'s complete list as the SDK takes them (the SDK's `RecordMember`).
     * Confirmation stays here: a member counts as confirmed only with the keys this device
     * confirms for them ([confirmedKeys]: the pin, or a receive-only member's record), and anyone
     * else is given with the keys the server lists, which the SDK uses only to see whether this
     * account's own head vouches for them. A listed member who has published no keys goes with
     * none (null): listed, and nothing more.
     */
    fun recordMembers(
        snapshot: CircleTrustSnapshot,
        /** DELIVER only: whether this device's person confirmed these keys ("It's them"). */
        personConfirmed: (userId: String, keys: Pair<String, String>) -> Boolean = { _, _ -> false },
        confirmedKeys: (userId: String) -> Pair<String, String>?,
    ): List<RecordMember> = snapshot.rows.values.map { row ->
        val keys = if (snapshot.result.confirmed.containsKey(row.userId.lowercase())) confirmedKeys(row.userId) else null
        RecordMember(
            userId = row.userId,
            sharingPublicKeyHex = keys?.first ?: row.sharingPublicKey?.takeIf { it.isNotBlank() },
            signingPublicKeyHex = keys?.second ?: row.signingPublicKey?.takeIf { it.isNotBlank() },
            confirmed = keys != null,
            listed = true,
            listedRole = role(row.role),
            statements = statements(row),
            personConfirmedKeys = keys != null && personConfirmed(row.userId, keys),
            serverSaysKeyless = row.keyVersion == null,
        )
    }

    /** The invitation and admissions the server serves with [row], as the SDK verifies them. */
    fun statements(row: MemberResponse): List<HeldStatement> =
        (listOfNotNull(row.invitation) + row.vouches.orEmpty()).map { HeldStatement(it.statement, it.signature) }

    /**
     * The listed members of [listed] that the record a plan would be built on names, included or
     * pending, when that is a record this device rejected: the report's [tipNamedListed]
     * (RefreshRecordsReport.tipNamedListed). The builder refuses a plan that leaves one of them
     * out, so every recorded change carries them pending when it can't include them, or names them
     * removed. Empty when the plan builds on the head.
     */
    fun rejectedIncluded(tipNamedListed: Collection<String>, listed: Collection<String>): Set<String> =
        listed.filter { id -> tipNamedListed.any { it.equals(id, ignoreCase = true) } }.toSet()

    /** A role in this device's member list (the server's word), as the SDK names it. */
    fun role(role: String?): CircleRole? = when (role?.lowercase()) {
        "owner" -> CircleRole.OWNER
        "admin" -> CircleRole.ADMIN
        "member" -> CircleRole.MEMBER
        else -> null
    }

    fun signed(circle: CircleRefreshRecords): List<SignedRefreshRecord> =
        circle.records.map { SignedRefreshRecord(it.record, it.signature) }

    fun links(chain: List<KeyChainLinkResponse>): List<KeyChainLinkData> =
        chain.map { KeyChainLinkData(it.fromVersion, it.toVersion, it.encryptedKey, it.nonce) }

    /** This account's own head among [heads], as served. */
    fun ownServedHead(heads: List<ServedCircleHead>, self: String): ServedCircleHead? =
        heads.firstOrNull { it.userId.equals(self, ignoreCase = true) }

    /** This account's own head among [heads], for a fresh install. */
    fun ownHead(heads: List<ServedCircleHead>, self: String): SignedCircleHead? =
        ownServedHead(heads, self)?.let { SignedCircleHead(it.head, it.signature) }

    /**
     * The heads of the members this device confirms, to see a fork. This account's own is one of
     * them: one that verifies under the account's current key and contradicts this device's
     * chain means its own devices diverged.
     */
    fun memberHeads(heads: List<ServedCircleHead>, confirmed: Set<String>): List<MemberHead> =
        heads.filter { head -> confirmed.any { it.equals(head.userId, ignoreCase = true) } }
            .map { MemberHead(it.userId, SignedCircleHead(it.head, it.signature)) }

    /**
     * After an import, whether this account's head is published. Only when this device accepted a
     * head ([accepted] > 0; 0 while forked or before an import), and the server serves none for
     * it ([served] null: withheld, or it doesn't verify under the account's current key, as after
     * a password reset), one of another circle or account, or an older one.
     */
    fun headDue(accepted: Int, served: CircleHead?, circleId: String, self: String): Boolean =
        accepted > 0 && (
            served == null || !served.circleId.equals(circleId, ignoreCase = true) ||
                !served.userId.equals(self, ignoreCase = true) || served.version < accepted
            )

    /**
     * The listed members [ids] this account's own head vouched for (`headConfirmedMembers`),
     * each with the listed keys the vouch matched: those [snapshot]'s list showed when the
     * import ran. A member listed with no keys can't have been vouched for.
     */
    fun vouched(ids: List<String>, snapshot: CircleTrustSnapshot): Map<String, Pair<String, String>> =
        ids.mapNotNull { id ->
            val row = snapshot.rows[id] ?: snapshot.rows.values.firstOrNull { it.userId.equals(id, ignoreCase = true) }
            val sharing = row?.sharingPublicKey ?: return@mapNotNull null
            val signing = row.signingPublicKey ?: return@mapNotNull null
            id to (sharing to signing)
        }.toMap()

    /**
     * The problems that call for a repair: who a record includes
     * ([RecordProblem.R2_NOT_ALLOWED], [RecordProblem.R2_UNMATCHED_ENTRY]), roles and removals,
     * and a record signed under another member's name, passed over: only a repair brings writes
     * back after it. A signature no confirmed member's key verifies still waits.
     */
    private val REPAIRABLE = setOf(
        RecordProblem.R2_NOT_ALLOWED, RecordProblem.R2_UNMATCHED_ENTRY, RecordProblem.ROLE, RecordProblem.REMOVAL,
        RecordProblem.MISATTRIBUTED,
    )

    /**
     * What a member list read later may settle: a signer or newcomer not confirmed here yet, and
     * a role or removal judged by the roles the list shows. A rejection a repair answers waits
     * one roster pass too, before the repair.
     */
    private val LIST_MAY_SETTLE = REPAIRABLE + RecordProblem.SIGNER_UNKNOWN

    /** The newest version [report] rejected for a reason a repair fixes, or null. */
    fun repairDue(report: RefreshRecordsReport): Int? =
        report.versions.filter { it.state == RecordVersionState.REJECTED && it.problem in REPAIRABLE }.maxOfOrNull { it.version }

    /** At most one repair per circle in this long, per device. */
    const val REPAIR_INTERVAL_MS = 60 * 60 * 1000L

    /**
     * The rejected record this device repairs now, or null. Only a rejection judged against a
     * member list read after the records (a device whose list is still catching up waits one
     * pass), for a reason a repair fixes; at most one repair per rejected version ([repaired]) and
     * one per circle an hour ([lastAt]); and none when the record rejected now was rejected for a
     * member the record this device last repaired was rejected for too ([lastFor], [rejectedFor]):
     * its repair's successor rejected for the same member, it stops and waits, so two devices with
     * different views don't ping-pong.
     */
    fun repairNow(
        judged: Judged?,
        repaired: Set<Int>,
        lastAt: Long?,
        lastFor: Set<String>,
        nowMs: Long,
        uncounted: (String) -> Boolean = { false },
    ): RecordVersionStatus? {
        if (judged == null || !judged.againstFreshList) return null
        val row = judged.report.versions
            .filter { it.state == RecordVersionState.REJECTED && it.problem in REPAIRABLE }
            .maxByOrNull { it.version } ?: return null
        if (row.version in repaired) return null
        if (lastAt != null && nowMs - lastAt < REPAIR_INTERVAL_MS) return null
        if (rejectedFor(row, uncounted).any { it in lastFor }) return null
        return row
    }

    /**
     * What [row] was rejected for, as the repair's ping-pong stop compares it: the eligibility
     * refusals ([RecordVersionStatus.notAllowed]) and the listed members its record names whom
     * the chain doesn't count here ([uncounted]; the report has no id for the unmatched ones). A
     * repair records the same when it's taken.
     */
    fun rejectedFor(row: RecordVersionStatus, uncounted: (String) -> Boolean): Set<String> =
        (row.notAllowed + row.namedListed.filter(uncounted)).map { it.lowercase() }.toSet()

    /** Whether a version in [report] waits or was refused for a reason a later member list may settle. */
    fun listMaySettle(report: RefreshRecordsReport): Boolean =
        report.versions.any { it.state != RecordVersionState.ACCEPTED && it.state != RecordVersionState.REFERENCE && it.problem in LIST_MAY_SETTLE }

    /** The SDK's words for "not yet: this circle's records aren't imported far enough here": the text its
     * error message carries.
     */
    const val NOT_IMPORTED_YET = "import this circle's records first"

    /**
     * Whether [e] is the SDK's "not yet" from the record builder or signCircleHead: whatever the
     * error case (an InvalidInput, or KeyNotFound while the circle's secret isn't derived yet), its
     * message carries [NOT_IMPORTED_YET]. Not a failure: tried again after the next import, with no
     * back-off.
     */
    fun notImportedYet(e: Throwable): Boolean =
        e is uniffi.pingolin_mobile.MobileException && e.message.orEmpty().contains(NOT_IMPORTED_YET)

    /**
     * A circle's last import. [againstFreshList]: judged against a member list read after its
     * records, so a rejection in it holds after a complete roster pass and a repair may
     * follow (a device whose list is still catching up waits one pass).
     */
    data class Judged(val report: RefreshRecordsReport, val againstFreshList: Boolean)
}
