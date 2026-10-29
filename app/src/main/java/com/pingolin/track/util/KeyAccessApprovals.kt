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

package com.pingolin.track.util

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Who this device trusts in each circle, and why.
 *
 * The server alone decides who is in a circle, so nothing here rests on what it says about
 * anyone's wraps or key versions. Every entry records its ORIGIN, because the trust rules
 * depend on it:
 *  - [Origin.PRE_ROUND2]: approvals from before signed invitations — seeded once, by trust
 *    on first use, from the first complete member list, plus this user's invites and "Give
 *    access". They stay and are never written again.
 *  - [Origin.OWN]: this user's own choice — an invitation sent from here, "Let them in",
 *    "It's them".
 *  - [Origin.STATEMENT]: vouched for by a signed invitation or admission that held up.
 *  - [Origin.KIND2] / [Origin.OWN_KIND2]: invited by email before they had an account — on
 *    the server's word that this account is that email. Receive-only: shown and given the
 *    circle, never trusted to sign for anyone. Their keys are kept here ([receiveOnlyKeys]),
 *    never as pins.
 *  - [Origin.ANCHOR]: where a device with no [Origin.PRE_ROUND2] seed starts: whoever signed
 *    this user's own invitation, a member matching an identity pin, or — when there was
 *    nothing else — whoever sent this device its first copy of the circle.
 *
 * Removed when the member leaves the complete member list, or this user removes them. A
 * departure leaves a TOMBSTONE: their user id with this device's version of the circle
 * then, and the statement ids they carried. A tombstoned statement never vouches again, and
 * a tombstoned user comes back only on a statement made at that version or later.
 *
 * An invitation this user signed is recorded with its statement id and expiry: its invitee
 * becomes this user's own approval only by appearing on the member list carrying THAT
 * invitation before it expires (+[INVITE_APPEARANCE_GRACE_MS]). The record goes when they
 * appear, when the invitation is withdrawn, or when it expires; until then it approves
 * nobody, and a pending invitee is never tombstoned.
 *
 * Kept per account, across that account's own sign-out, like the pins it complements;
 * [keepOnly] drops every other account's record when somebody else signs in on this device,
 * and [forget] drops a deleted account's.
 *
 * A record that can't be read is never "nobody" or "none": every read throws
 * [StoreUnreadable] then, and every write writes nothing (a durable one says false), so
 * nothing is written over what the file holds. What rests on these records waits:
 * confirmation and the deliveries after it, the backfill, and the one-time stray-choice
 * pass ([strayChoicesChecked]).
 */
interface KeyAccessApprovals : KeptPerAccount {

    enum class Origin { PRE_ROUND2, OWN, STATEMENT, KIND2, OWN_KIND2, ANCHOR }

    /** Members of [circleId] this device trusts, with why. Invitees yet to join are not in it. */
    fun origins(circleId: String): Map<String, Origin>

    /** Members of [circleId] this device trusts. */
    fun approved(circleId: String): Set<String> = origins(circleId).keys

    /** Production code never records [Origin.PRE_ROUND2]: those entries only ever go. */
    fun approve(circleId: String, userIds: Collection<String>, origin: Origin = Origin.OWN)

    /**
     * [approve], on disk before this returns and read back: false when it didn't land. A
     * waiting "Let them in" never goes on, or ends, on a choice that might not be on disk.
     */
    fun approveDurably(circleId: String, userIds: Collection<String>, origin: Origin = Origin.OWN): Boolean {
        approve(circleId, userIds, origin)
        val now = origins(circleId)
        return userIds.all { now[it] == origin }
    }

    /** This user removed [userIds] from [circleId]: their access goes with them. */
    fun revoke(circleId: String, userIds: Collection<String>)

    /**
     * This user invited [email] to [circleId] with the signed invitation [statementId], which
     * expires at [expiresAtSecs] (unix seconds). [userId] is the account a kind-1 invitation
     * names; without it the invitation is to the email (kind 2). An unsigned invitation
     * records nothing.
     */
    fun approveInvitee(circleId: String, userId: String?, email: String, statementId: String, expiresAtSecs: Long)

    /** This user withdrew the invitation [statementId]: its record goes with it. */
    fun withdrawInvitation(circleId: String, statementId: String)

    /** Invitations this user signed for [circleId] that nobody has joined with yet: invitee → statement id. */
    fun pendingInvitees(circleId: String): Map<String, String>

    /**
     * Approve (receive-only) the members of [circleId] whose email ([emailByUserId], from
     * the complete member list) this user invited, and return them. For invitees who had
     * no account when invited, and only those carrying the invitation this user signed
     * ([invitationIdByUser]). One invite approves one account: an email given to two at
     * once approves neither.
     */
    fun approveInvitedEmails(
        circleId: String,
        emailByUserId: Map<String, String>,
        invitationIdByUser: Map<String, String>,
    ): Set<String>

    /** Whether any email invitation from this user to [circleId] is still waiting for its account. */
    fun hasInvitedEmails(circleId: String): Boolean

    /** Whether this device had approvals for [circleId] from before signed invitations. */
    fun hasPreRound2Seed(circleId: String): Boolean

    /**
     * Members this account trusts, not receive-only, in any circle but [circleId]: what a
     * circle first seen now may start from, next to the keys pinned for them.
     */
    fun trustedInOtherCircles(circleId: String): Set<String>

    /** Whether this account's one-time seed from the pins this device already held was taken. */
    fun heldPinsSeeded(): Boolean

    /**
     * Once per account on this device: every circle in [pinnedByCircle] with no approval
     * record at all is seeded with the members this device had pinned there. The seed is
     * HELD until that circle's first complete member list ([retain]): only the users it
     * lists become [Origin.PRE_ROUND2] entries; anyone else it held — an invitee still
     * pending, or someone long gone whose pin was never retired — gets nothing, no entry
     * and no tombstone. Later calls do nothing. Returns how many circles were seeded.
     */
    fun seedFromHeldPins(pinnedByCircle: Map<String, Set<String>>): Int

    /**
     * [memberUserIds] is the circle's COMPLETE member list. A held seed is settled against it
     * first (see [seedFromHeldPins]). Anyone this device trusted or recorded statements for who
     * is missing from it has left: their records go, and each leaves a tombstone with
     * [activeVersion] (this device's version of the circle now) and their statement ids. An
     * invitee who now appears becomes an ordinary (own) approval only when their invitation
     * ([invitationIdByUser]: the statement id each member joined with) is the one this user
     * signed, before it expired; their invitation record goes either way.
     */
    fun retain(
        circleId: String,
        memberUserIds: Collection<String>,
        activeVersion: Int = 0,
        invitationIdByUser: Map<String, String> = emptyMap(),
    )

    /** The statement ids each member carries now, for their tombstone if they leave. */
    fun recordStatementIds(circleId: String, idsByUser: Map<String, Collection<String>>)

    /**
     * The statements of this account's own that a member's approval here rests on, by id, recorded while
     * they verify under this account's key. After a password reset they do not verify any more, and this
     * record is what still says they were this account's: the pointer the reset's "Let them in?" needs.
     */
    fun approvalBasis(circleId: String): Map<String, Set<String>> = emptyMap()

    /** Adds [idsByUser] to [approvalBasis]; a member's entries go with their approval ([revoke]) or their departure. */
    fun recordApprovalBasis(circleId: String, idsByUser: Map<String, Collection<String>>) {}

    /** Departed members: user id → this device's version of the circle when they left. */
    fun tombstones(circleId: String): Map<String, Int>

    fun tombstonedStatementIds(circleId: String): Set<String>

    /** [userIds] came back on statements made after they left. */
    fun clearTombstones(circleId: String, userIds: Collection<String>)

    /** Receive-only members' keys (sharing, signing): used to wrap TO them, and nothing else. */
    fun receiveOnlyKeys(circleId: String): Map<String, Pair<String, String>>

    fun recordReceiveOnlyKeys(circleId: String, userId: String, sharingKeyHex: String, signingKeyHex: String)

    /** Members this device already signed a backfill admission for: once each. */
    fun backfilled(circleId: String): Set<String>

    fun markBackfilled(circleId: String, userIds: Collection<String>)

    /** Ids of the admissions the backfill signed for [userId] in [circleId]. */
    fun backfillIds(circleId: String, userId: String): Set<String>

    /** [id] saved before its admission's request goes out; false when it didn't land. */
    fun addBackfillId(circleId: String, userId: String, id: String): Boolean

    /**
     * This device created [circleId]: its own record, never the role the server
     * reports, is what lets it hand the circle to any member it confirms.
     */
    fun markCreatedHere(circleId: String)

    fun createdHere(circleId: String): Boolean

    /** Whether the one-time look for stray "Let them in" choices ran for [circleId]. */
    fun strayChoicesChecked(circleId: String): Boolean

    /** [strayChoicesChecked] from now on, on disk before this returns: false when it didn't land. */
    fun markStrayChoicesChecked(circleId: String): Boolean

    /**
     * Drop this user's own choices ([Origin.OWN]) of [userIds] in [circleId], and nothing else about
     * them; on disk before this returns and read back: false when it didn't land.
     */
    fun dropChoicesDurably(circleId: String, userIds: Collection<String>): Boolean

    /** When this device last asked "Is this really {name}?" about [userId], or null. */
    fun lastAsked(circleId: String, userId: String): Long?

    fun markAsked(circleId: String, userId: String)

    /** Withdrawn or refused invitations, by statement id: never renewed. */
    fun markInvitationDead(circleId: String, statementId: String)

    /**
     * [markInvitationDead] for every one of [statementIds], written before this returns; false
     * when that write failed. A change waiting to sync ends only once the ids it signed and the
     * server doesn't hold are dead on this device (fail closed, else the change is parked).
     */
    fun markDeadDurably(circleId: String, statementIds: Collection<String>): Boolean {
        statementIds.forEach { markInvitationDead(circleId, it) }
        return true
    }

    fun invitationDead(circleId: String, statementId: String): Boolean

    /**
     * Every invitation withdrawn or refused here, by statement id: none of them confirms
     * anybody on this device, whatever the member list says.
     */
    fun deadInvitations(circleId: String): Set<String>

    /** "Is this really {email}?" was asked about this invitation (once per statement id). */
    fun markRenewalAsked(circleId: String, statementId: String)

    fun renewalAsked(circleId: String, statementId: String): Boolean

    companion object {
        /** An invitee may appear this long after their invitation's expiry. */
        const val INVITE_APPEARANCE_GRACE_MS = 5L * 60 * 1000
    }
}

/**
 * [KeyAccessApprovals] in its own encrypted preferences file, keyed by "<owner>|<circle>"
 * plus a suffix per record: none ([KeyAccessApprovals.Origin.PRE_ROUND2] approvals),
 * "#own", "#stmt", "#kind2", "#ownkind2", "#anchor", "#invited" and "#invitedEmail"
 * ("<id or email>\t<statement id>\t<lapses at millis>"), "#heldSeed" (the seed held
 * until the circle's first complete member list), "#seeded" (the first-use seed's flag),
 * "#tomb" ("<user>\t<version>"), "#tombIds", "#ids" ("<user>\t<statement id>"), "#rokeys"
 * ("<user>\t<sharing>\t<signing>"), "#backfilled", "#asked" ("<user>\t<millis>"), "#created"
 * (this device created the circle); and one per account, "<owner>|#pinseed" (the one-time
 * seed from held pins). The circle and every member and statement id are kept in their one
 * spelling ([AccountIds.storeKey]); what an earlier version kept under another is folded
 * there at load ([RULES]).
 * A read that fails throws [StoreUnreadable]: a tombstone or a dead invitation read as none
 * would let a statement vouch again, and a write built on an empty read would drop what the
 * file holds. Every write reads first, inside its runCatching, so it writes nothing then.
 */
class SecurePrefsKeyAccessApprovals(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
    private val now: () -> Long = System::currentTimeMillis,
) : KeyAccessApprovals {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    private fun key(circleId: String): String? = account.key(circleId)

    private fun <T> read(what: () -> T): T = StoreUnreadable.reading(STORE, what)

    private fun set(key: String): Set<String> = read {
        account.check(key)
        prefs.getStringSet(key, null)?.toSet()
    }.orEmpty()

    private fun flag(key: String): Boolean = read {
        account.check(key)
        prefs.getBoolean(key, false)
    }

    /** [ids] in their one spelling. */
    private fun ids(ids: Collection<String>): Set<String> = ids.map(AccountIds::storeKey).toSet()

    /** An invitation this user signed: to whom (account id or email), which statement, and when it lapses. */
    private data class OwnInvite(val who: String, val statementId: String, val lapsesAtMs: Long)

    /**
     * Unexpired invitation records under [slot]. A record without a statement id (written by an
     * earlier version) binds nothing and reads as gone.
     */
    private fun ownInvites(slot: String): List<OwnInvite> {
        val at = now()
        return set(slot).mapNotNull { entry ->
            val parts = entry.split(SEPARATOR)
            if (parts.size != 3 || parts[1].isBlank()) return@mapNotNull null
            val lapses = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (lapses <= at) null else OwnInvite(parts[0], parts[1], lapses)
        }
    }

    private fun entryOf(invite: OwnInvite) =
        "${invite.who}$SEPARATOR${invite.statementId}$SEPARATOR${invite.lapsesAtMs}"

    private fun suffix(origin: KeyAccessApprovals.Origin) = when (origin) {
        KeyAccessApprovals.Origin.PRE_ROUND2 -> ""
        KeyAccessApprovals.Origin.OWN -> OWN
        KeyAccessApprovals.Origin.STATEMENT -> STMT
        KeyAccessApprovals.Origin.KIND2 -> KIND2
        KeyAccessApprovals.Origin.OWN_KIND2 -> OWN_KIND2
        KeyAccessApprovals.Origin.ANCHOR -> ANCHOR
    }

    /** Entries of "<user>\t…" records under [key], by user. */
    private fun byUser(key: String): Map<String, List<String>> =
        set(key).groupBy({ it.substringBefore(SEPARATOR) }, { it.substringAfter(SEPARATOR, "") })

    override fun origins(circleId: String): Map<String, KeyAccessApprovals.Origin> {
        val key = key(circleId) ?: return emptyMap()
        val out = linkedMapOf<String, KeyAccessApprovals.Origin>()
        // Weakest first, so a stronger record of the same member wins.
        for (id in set("$key$KIND2")) out[id] = KeyAccessApprovals.Origin.KIND2
        for (id in set("$key$OWN_KIND2")) out[id] = KeyAccessApprovals.Origin.OWN_KIND2
        for (id in set("$key$ANCHOR")) out[id] = KeyAccessApprovals.Origin.ANCHOR
        for (id in set(key)) out[id] = KeyAccessApprovals.Origin.PRE_ROUND2
        for (id in set("$key$STMT")) out[id] = KeyAccessApprovals.Origin.STATEMENT
        for (id in set("$key$OWN")) out[id] = KeyAccessApprovals.Origin.OWN
        return out
    }

    override fun approve(circleId: String, userIds: Collection<String>, origin: KeyAccessApprovals.Origin) {
        if (userIds.isEmpty()) return
        val key = key(circleId) ?: return
        val slot = "$key${suffix(origin)}"
        // A copy: the set getStringSet returns must not be modified in place.
        runCatching { prefs.edit().putStringSet(slot, set(slot) + ids(userIds)).apply() }
    }

    override fun approveDurably(circleId: String, userIds: Collection<String>, origin: KeyAccessApprovals.Origin): Boolean {
        if (userIds.isEmpty()) return true
        val key = key(circleId) ?: return false
        val slot = "$key${suffix(origin)}"
        val ids = ids(userIds)
        // commit(), not apply(), then read back: the caller goes on only once it is on disk.
        return runCatching {
            prefs.edit().putStringSet(slot, set(slot) + ids).commit() && set(slot).containsAll(ids)
        }.getOrDefault(false)
    }

    override fun revoke(circleId: String, userIds: Collection<String>) {
        if (userIds.isEmpty()) return
        val key = key(circleId) ?: return
        runCatching {
            val gone = ids(userIds)
            val editor = prefs.edit()
            for (s in ORIGIN_SUFFIXES) editor.putStringSet("$key$s", set("$key$s") - gone)
            editor.putStringSet("$key$INVITED", ownInvites("$key$INVITED").filter { it.who !in gone }.map(::entryOf).toSet())
            for (s in listOf(RO_KEYS, BASIS)) editor.putStringSet("$key$s", set("$key$s").filterNot { it.substringBefore(SEPARATOR) in gone }.toSet())
            editor.apply()
        }
    }

    override fun approveInvitee(circleId: String, userId: String?, email: String, statementId: String, expiresAtSecs: Long) {
        val key = key(circleId) ?: return
        if (statementId.isBlank() || expiresAtSecs <= 0) return
        val (slot, who) = userId?.takeIf { it.isNotBlank() }?.let { "$key$INVITED" to AccountIds.storeKey(it) }
            ?: normalized(email)?.let { "$key$INVITED_EMAIL" to it }
            ?: return
        val invite = OwnInvite(
            who,
            AccountIds.storeKey(statementId),
            expiresAtSecs * 1000 + KeyAccessApprovals.INVITE_APPEARANCE_GRACE_MS,
        )
        // Re-inviting replaces the invitee's record with the new invitation's.
        runCatching {
            prefs.edit().putStringSet(slot, ownInvites(slot).filter { it.who != who }.map(::entryOf).toSet() + entryOf(invite)).apply()
        }
    }

    override fun withdrawInvitation(circleId: String, statementId: String) {
        val key = key(circleId) ?: return
        val id = AccountIds.storeKey(statementId)
        runCatching {
            val editor = prefs.edit()
            for (slot in listOf("$key$INVITED", "$key$INVITED_EMAIL")) {
                editor.putStringSet(slot, ownInvites(slot).filter { it.statementId != id }.map(::entryOf).toSet())
            }
            editor.apply()
        }
    }

    override fun pendingInvitees(circleId: String): Map<String, String> {
        val key = key(circleId) ?: return emptyMap()
        return (ownInvites("$key$INVITED") + ownInvites("$key$INVITED_EMAIL")).associate { it.who to it.statementId }
    }

    override fun approveInvitedEmails(
        circleId: String,
        emailByUserId: Map<String, String>,
        invitationIdByUser: Map<String, String>,
    ): Set<String> {
        val key = key(circleId) ?: return emptySet()
        return runCatching {
            val invited = ownInvites("$key$INVITED_EMAIL")
            if (invited.isEmpty()) return@runCatching emptySet<String>()
            val accountsByEmail = emailByUserId.entries
                .mapNotNull { (userId, email) -> normalized(email)?.let { it to AccountIds.storeKey(userId) } }
                .groupBy({ it.first }, { it.second })
            val invitations = invitationIdByUser.entries.associate { (user, id) -> AccountIds.storeKey(user) to AccountIds.storeKey(id) }
            // Only the one account at the address, carrying the invitation this user signed.
            val matched = invited.mapNotNull { invite ->
                accountsByEmail[invite.who]?.singleOrNull()
                    ?.takeIf { invitations[it] == invite.statementId }
                    ?.let { invite to it }
            }
            if (matched.isEmpty()) return@runCatching emptySet<String>()
            val userIds = matched.map { it.second }.toSet()
            val used = matched.map { it.first }.toSet()
            prefs.edit()
                .putStringSet("$key$OWN_KIND2", set("$key$OWN_KIND2") + userIds)
                .putStringSet("$key$INVITED_EMAIL", invited.filter { it !in used }.map(::entryOf).toSet())
                .apply()
            userIds
        }.getOrDefault(emptySet())
    }

    override fun hasInvitedEmails(circleId: String): Boolean {
        val key = key(circleId) ?: return false
        return ownInvites("$key$INVITED_EMAIL").isNotEmpty()
    }

    override fun trustedInOtherCircles(circleId: String): Set<String> {
        val owner = account.get() ?: return emptySet()
        val prefix = "$owner|"
        val here = key(circleId) ?: return emptySet()
        val out = mutableSetOf<String>()
        for (k in read { prefs.all.keys }) {
            if (!k.startsWith(prefix)) continue
            val circleKey = k.substringBefore('#')
            if (circleKey == here) continue
            // A circle whose records can't be read now (two spellings that couldn't be folded, or a fold
            // not landed yet) vouches for nobody elsewhere, and never stops the other circles' reads.
            if (runCatching { account.check(k) }.isFailure) continue
            // A pending invitation trusts nobody yet.
            when (k.removePrefix(circleKey)) {
                "", OWN, STMT, ANCHOR -> out += set(k)
            }
        }
        return out - owner
    }

    override fun heldPinsSeeded(): Boolean {
        val owner = account.get() ?: return false
        return flag("$owner|$PIN_SEED")
    }

    override fun seedFromHeldPins(pinnedByCircle: Map<String, Set<String>>): Int {
        val owner = account.get() ?: return 0
        if (heldPinsSeeded()) return 0
        // Read first, outside the write: a seed decided on records that can't be read would be
        // written over them, and marked done.
        val due = pinnedByCircle.filter { (circleId, members) ->
            members.isNotEmpty() && origins(circleId).isEmpty() && !hasPreRound2Seed(circleId)
        }
        return runCatching {
            val editor = prefs.edit()
            for ((circleId, members) in due) {
                val key = "$owner|${AccountIds.storeKey(circleId)}"
                editor.putStringSet("$key$HELD_SEED", ids(members)).putBoolean("$key$SEEDED", true)
            }
            editor.putBoolean("$owner|$PIN_SEED", true).apply()
            due.size
        }.getOrDefault(0)
    }

    override fun hasPreRound2Seed(circleId: String): Boolean {
        val key = key(circleId) ?: return false
        return set(key).isNotEmpty() || flag("$key$SEEDED")
    }

    override fun retain(
        circleId: String,
        memberUserIds: Collection<String>,
        activeVersion: Int,
        invitationIdByUser: Map<String, String>,
    ) {
        val key = key(circleId) ?: return
        runCatching {
            val members = ids(memberUserIds)
            val invitations = invitationIdByUser.entries.associate { (user, id) -> AccountIds.storeKey(user) to AccountIds.storeKey(id) }
            // The held seed, settled against this first complete list: those it
            // lists become PRE_ROUND2 entries; anyone else it held gets nothing.
            if (read { prefs.contains("$key$HELD_SEED") }) {
                val listed = set("$key$HELD_SEED").intersect(members)
                prefs.edit().putStringSet(key, set(key) + listed).remove("$key$HELD_SEED").apply()
            }
            val invited = ownInvites("$key$INVITED")
            // Their record goes when they appear; it makes them this user's own only when
            // they carry the invitation this user signed. Expired ones have already gone.
            val appeared = invited.filter { it.who in members }
            val arrived = appeared
                .filter { invitations[it.who] == it.statementId }
                .map { it.who }
                .toSet()
            val ids = set("$key$IDS")
            val departed = ids.map { it.substringBefore(SEPARATOR) }.filter { it !in members }.toMutableSet()
            val editor = prefs.edit()
            var changed = false
            for (s in ORIGIN_SUFFIXES) {
                val before = set("$key$s")
                val kept = before.intersect(members)
                departed += before - kept
                if (kept != before) { editor.putStringSet("$key$s", kept); changed = true }
            }
            if (arrived.isNotEmpty()) {
                editor.putStringSet("$key$OWN", set("$key$OWN") + arrived)
                changed = true
            }
            val waiting = (invited - appeared.toSet()).map(::entryOf).toSet()
            if (waiting != set("$key$INVITED")) { editor.putStringSet("$key$INVITED", waiting); changed = true }
            val emails = ownInvites("$key$INVITED_EMAIL").map(::entryOf).toSet()
            if (emails != set("$key$INVITED_EMAIL")) { editor.putStringSet("$key$INVITED_EMAIL", emails); changed = true }
            if (departed.isNotEmpty()) {
                fun ofDeparted(entry: String) = entry.substringBefore(SEPARATOR) in departed
                val tombs = set("$key$TOMB").filterNot(::ofDeparted).toSet() +
                    departed.map { "$it$SEPARATOR$activeVersion" }
                val theirs = ids.filter(::ofDeparted)
                editor.putStringSet("$key$TOMB", tombs)
                    .putStringSet("$key$TOMB_IDS", set("$key$TOMB_IDS") + theirs.map { it.substringAfter(SEPARATOR) })
                    .putStringSet("$key$IDS", ids - theirs.toSet())
                for (s in listOf(RO_KEYS, ASKED, BASIS)) editor.putStringSet("$key$s", set("$key$s").filterNot(::ofDeparted).toSet())
                editor.putStringSet("$key$BACKFILLED", set("$key$BACKFILLED") - departed)
                changed = true
            }
            // Written only on a change: this runs on every member-list refresh.
            if (changed) editor.apply()
        }
    }

    override fun recordStatementIds(circleId: String, idsByUser: Map<String, Collection<String>>) {
        if (idsByUser.isEmpty()) return
        val key = key(circleId) ?: return
        runCatching {
            val ids = set("$key$IDS")
            val byUser = idsByUser.entries.associate { (user, list) -> AccountIds.storeKey(user) to list }
            val next = ids.filterNot { it.substringBefore(SEPARATOR) in byUser.keys }.toSet() +
                byUser.flatMap { (user, list) -> list.map { "$user$SEPARATOR${AccountIds.storeKey(it)}" } }
            if (next != ids) prefs.edit().putStringSet("$key$IDS", next).apply()
        }
    }

    override fun approvalBasis(circleId: String): Map<String, Set<String>> {
        val key = key(circleId) ?: return emptyMap()
        return set("$key$BASIS").mapNotNull { entry ->
            entry.split(SEPARATOR).takeIf { it.size == 2 }?.let { (user, statement) -> user to statement }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    override fun recordApprovalBasis(circleId: String, idsByUser: Map<String, Collection<String>>) {
        if (idsByUser.values.all { it.isEmpty() }) return
        val key = key(circleId) ?: return
        runCatching {
            val held = set("$key$BASIS")
            val next = held + idsByUser.flatMap { (user, ids) -> ids.map { "${id(user)}$SEPARATOR${id(it)}" } }
            if (next != held) prefs.edit().putStringSet("$key$BASIS", next).apply()
        }
    }

    override fun tombstones(circleId: String): Map<String, Int> {
        val key = key(circleId) ?: return emptyMap()
        return set("$key$TOMB").mapNotNull { entry ->
            val version = entry.substringAfterLast(SEPARATOR, "").toIntOrNull() ?: return@mapNotNull null
            entry.substringBeforeLast(SEPARATOR) to version
        }.toMap()
    }

    override fun tombstonedStatementIds(circleId: String): Set<String> {
        val key = key(circleId) ?: return emptySet()
        return set("$key$TOMB_IDS")
    }

    override fun clearTombstones(circleId: String, userIds: Collection<String>) {
        if (userIds.isEmpty()) return
        val key = key(circleId) ?: return
        runCatching {
            val tombs = set("$key$TOMB")
            val back = ids(userIds)
            val next = tombs.filterNot { it.substringBefore(SEPARATOR) in back }.toSet()
            if (next != tombs) prefs.edit().putStringSet("$key$TOMB", next).apply()
        }
    }

    override fun receiveOnlyKeys(circleId: String): Map<String, Pair<String, String>> {
        val key = key(circleId) ?: return emptyMap()
        return set("$key$RO_KEYS").mapNotNull { entry ->
            val parts = entry.split(SEPARATOR)
            if (parts.size != 3) null else parts[0] to (parts[1] to parts[2])
        }.toMap()
    }

    override fun recordReceiveOnlyKeys(circleId: String, userId: String, sharingKeyHex: String, signingKeyHex: String) {
        val key = key(circleId) ?: return
        val id = AccountIds.storeKey(userId)
        runCatching {
            val before = set("$key$RO_KEYS")
            val next = before.filterNot { it.substringBefore(SEPARATOR) == id }.toSet() +
                "$id$SEPARATOR${sharingKeyHex.lowercase()}$SEPARATOR${signingKeyHex.lowercase()}"
            if (next != before) prefs.edit().putStringSet("$key$RO_KEYS", next).apply()
        }
    }

    override fun backfilled(circleId: String): Set<String> {
        val key = key(circleId) ?: return emptySet()
        return set("$key$BACKFILLED")
    }

    override fun markBackfilled(circleId: String, userIds: Collection<String>) {
        if (userIds.isEmpty()) return
        val key = key(circleId) ?: return
        runCatching { prefs.edit().putStringSet("$key$BACKFILLED", set("$key$BACKFILLED") + ids(userIds)).apply() }
    }

    override fun backfillIds(circleId: String, userId: String): Set<String> {
        val key = key(circleId) ?: return emptySet()
        return set("$key$BACKFILL_IDS${AccountIds.storeKey(userId)}")
    }

    override fun addBackfillId(circleId: String, userId: String, id: String): Boolean {
        val key = key(circleId) ?: return false
        val slot = "$key$BACKFILL_IDS${AccountIds.storeKey(userId)}"
        val kept = AccountIds.storeKey(id)
        // commit(), not apply(), then read back: nothing is sent unless the id is on disk.
        return runCatching { prefs.edit().putStringSet(slot, set(slot) + kept).commit() && kept in set(slot) }.getOrDefault(false)
    }

    override fun markCreatedHere(circleId: String) {
        val key = key(circleId) ?: return
        runCatching { prefs.edit().putBoolean("$key$CREATED", true).apply() }
    }

    override fun createdHere(circleId: String): Boolean {
        val key = key(circleId) ?: return false
        return flag("$key$CREATED")
    }

    override fun strayChoicesChecked(circleId: String): Boolean {
        val key = key(circleId) ?: return false
        return flag("$key$STRAY_CHECKED")
    }

    override fun markStrayChoicesChecked(circleId: String): Boolean {
        val key = key(circleId) ?: return false
        // Read first: the pass is never marked done over records that can't be read.
        return runCatching {
            flag("$key$STRAY_CHECKED")
            prefs.edit().putBoolean("$key$STRAY_CHECKED", true).commit() && flag("$key$STRAY_CHECKED")
        }.getOrDefault(false)
    }

    override fun dropChoicesDurably(circleId: String, userIds: Collection<String>): Boolean {
        if (userIds.isEmpty()) return true
        val key = key(circleId) ?: return false
        val slot = "$key$OWN"
        val gone = ids(userIds)
        // commit(), not apply(), then read back: the one-time mark follows only a drop on disk.
        return runCatching {
            prefs.edit().putStringSet(slot, set(slot) - gone).commit() && set(slot).none { it in gone }
        }.getOrDefault(false)
    }

    override fun lastAsked(circleId: String, userId: String): Long? {
        val key = key(circleId) ?: return null
        return byUser("$key$ASKED")[AccountIds.storeKey(userId)]?.mapNotNull { it.toLongOrNull() }?.maxOrNull()
    }

    override fun markAsked(circleId: String, userId: String) {
        val key = key(circleId) ?: return
        val id = AccountIds.storeKey(userId)
        runCatching {
            val next = set("$key$ASKED").filterNot { it.substringBefore(SEPARATOR) == id }.toSet() +
                "$id$SEPARATOR${now()}"
            prefs.edit().putStringSet("$key$ASKED", next).apply()
        }
    }

    private fun addTo(circleId: String, suffix: String, value: String) {
        val key = key(circleId) ?: return
        runCatching { prefs.edit().putStringSet("$key$suffix", set("$key$suffix") + value).apply() }
    }

    private fun contains(circleId: String, suffix: String, value: String): Boolean {
        val key = key(circleId) ?: return false
        return value in set("$key$suffix")
    }

    override fun markInvitationDead(circleId: String, statementId: String) =
        addTo(circleId, DEAD_INVITES, AccountIds.storeKey(statementId))

    override fun markDeadDurably(circleId: String, statementIds: Collection<String>): Boolean {
        if (statementIds.isEmpty()) return true
        val key = key(circleId) ?: return false
        // commit(), not apply(): the caller deletes the record only once this is on disk. Read
        // back, too: an id that didn't land is never taken as dead.
        return runCatching {
            prefs.edit().putStringSet("$key$DEAD_INVITES", set("$key$DEAD_INVITES") + ids(statementIds)).commit() &&
                statementIds.all { contains(circleId, DEAD_INVITES, AccountIds.storeKey(it)) }
        }.getOrDefault(false)
    }

    override fun invitationDead(circleId: String, statementId: String): Boolean =
        contains(circleId, DEAD_INVITES, AccountIds.storeKey(statementId))

    override fun deadInvitations(circleId: String): Set<String> {
        val key = key(circleId) ?: return emptySet()
        return set("$key$DEAD_INVITES")
    }

    override fun markRenewalAsked(circleId: String, statementId: String) =
        addTo(circleId, RENEWAL_ASKED, AccountIds.storeKey(statementId))

    override fun renewalAsked(circleId: String, statementId: String): Boolean =
        contains(circleId, RENEWAL_ASKED, AccountIds.storeKey(statementId))

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    /** As the server stores an invite's email: trimmed and lower-cased. */
    private fun normalized(email: String): String? =
        email.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

    private companion object {
        const val STORE = "key access approvals"
        const val FILE_NAME = "key_access_approvals"
        const val OWN = "#own"
        const val STMT = "#stmt"
        const val KIND2 = "#kind2"
        const val OWN_KIND2 = "#ownkind2"
        const val ANCHOR = "#anchor"
        const val INVITED = "#invited"
        const val INVITED_EMAIL = "#invitedEmail"
        /** The seed held until the circle's first complete member list. */
        const val HELD_SEED = "#heldSeed"
        const val SEEDED = "#seeded"
        const val TOMB = "#tomb"
        const val TOMB_IDS = "#tombIds"
        const val IDS = "#ids"
        /** "<member>\t<statement id>": this account's own statements an approval here rests on. */
        const val BASIS = "#basis"
        const val RO_KEYS = "#rokeys"
        const val BACKFILLED = "#backfilled"
        const val ASKED = "#asked"
        /** Set once, when this device created the circle. */
        const val CREATED = "#created"
        /** "<owner>|<circle>#bfids|<member>": the backfill's statement ids for a member. */
        const val BACKFILL_IDS = "#bfids|"
        /** Set once, when the one-time look for stray "Let them in" choices ran. */
        const val STRAY_CHECKED = "#strayChecked"
        /** Statement ids of invitations withdrawn or refused here: never renewed. */
        const val DEAD_INVITES = "#deadInvites"
        /** Statement ids "Is this really {email}?" was asked about (once each). */
        const val RENEWAL_ASKED = "#renewalAsked"
        /** Per account, not per circle: "<owner>|#pinseed" (set once the seed
         * from held pins was taken).
         */
        const val PIN_SEED = "#pinseed"
        const val SEPARATOR = "\t"
        /** Every approval record, the PRE_ROUND2 one ("") included. */
        val ORIGIN_SUFFIXES = listOf("", OWN, STMT, KIND2, OWN_KIND2, ANCHOR)

        private fun id(raw: String): String = AccountIds.storeKey(raw)

        /**
         * A circle's records kept under two spellings, folded by the same rules as the web app's.
         * With one spelling, only the ids in them are written in their one spelling.
         *  - Tombstones: per member, the higher version; their statement ids, the statement ids
         *    members carry, dead invitations, renewals asked about, and backfills: every one, the
         *    backfill's admission ids per member capped at [CircleRepository.MAX_BACKFILL_IDS] (the
         *    only cap: the look takes no more).
         *  - Approvals and receive-only keys, per member: where the spellings holding them agree,
         *    that; where they don't, the circle's one spelling's; with neither, none (asked again).
         *    A member the fold holds a tombstone for keeps none: the removal wins.
         *  - Invitations: every one, by statement id, the earlier lapse on a clash; none where two
         *    name different people.
         *  - The held seed, seeded, created here and the stray-choice look: only where every
         *    spelling holds them (the seed, the members every one holds).
         *  - When a member was last asked: the later.
         */
        val RULES = object : CircleEntries.Rules {
            override fun rest(rest: String): String =
                if (rest.startsWith(BACKFILL_IDS)) BACKFILL_IDS + id(rest.removePrefix(BACKFILL_IDS)) else rest

            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) = buildMap<String, CircleEntries.Outcome> {
                entries[TOMB]?.let { held ->
                    put(TOMB, CircleEntries.perId(held, SEPARATOR, ::id) { v -> v.mapNotNull { it.toIntOrNull() }.maxOrNull()?.toString() ?: v.first() })
                }
                for (s in listOf(TOMB_IDS, BACKFILLED, DEAD_INVITES, RENEWAL_ASKED)) entries[s]?.let { put(s, CircleEntries.union(it, ::id)) }
                for (s in listOf(IDS, BASIS)) entries[s]?.let { held ->
                    put(s, CircleEntries.union(held) { e -> e.split(SEPARATOR).takeIf { it.size == 2 }?.let { (u, st) -> "${id(u)}$SEPARATOR${id(st)}" } ?: e })
                }
                entries[ASKED]?.let { held ->
                    put(ASKED, CircleEntries.perId(held, SEPARATOR, ::id) { v -> v.mapNotNull { it.toLongOrNull() }.maxOrNull()?.toString() ?: v.first() })
                }
                for (s in listOf(INVITED, INVITED_EMAIL)) entries[s]?.let { put(s, invitations(it)) }
                entries[HELD_SEED]?.let { put(HELD_SEED, CircleEntries.intersection(it, spellings, ::id)) }
                for (s in listOf(SEEDED, CREATED, STRAY_CHECKED)) entries[s]?.let { put(s, CircleEntries.all(it, spellings)) }
                for ((rest, held) in entries) if (rest.startsWith(BACKFILL_IDS)) put(rest, backfillIds(held))
                putAll(approvals(entries, spellings))
            }
        }

        /** Each key's strings, or null when a value is no set of them. */
        private fun strings(held: List<CircleEntries.Held>): List<List<String>>? =
            held.map { h -> (h.value as? Set<*>)?.takeIf { set -> set.all { it is String } }?.map { it as String } ?: return null }

        /**
         * Invitation records, every one by statement id; on a clash, the one lapsing first, and
         * none where two name different people.
         */
        private fun invitations(held: List<CircleEntries.Held>): CircleEntries.Outcome {
            val entries = strings(held)?.flatten() ?: return CircleEntries.Outcome.Refuse
            val byStatement = linkedMapOf<String, MutableList<Pair<String, Long?>>>()
            val kept = mutableSetOf<String>()
            for (entry in entries) {
                val f = entry.split(SEPARATOR)
                if (f.size != 3) {
                    kept += entry
                    continue
                }
                byStatement.getOrPut(id(f[1])) { mutableListOf() } += id(f[0]) to f[2].toLongOrNull()
            }
            for ((statement, invites) in byStatement) {
                val who = invites.map { it.first }.distinct().singleOrNull() ?: continue
                kept += "$who$SEPARATOR$statement$SEPARATOR${invites.mapNotNull { it.second }.minOrNull() ?: ""}"
            }
            return CircleEntries.Outcome.Keep(kept)
        }

        /** A member's backfill admission ids, every one, the one spelling's first, at most the look's. */
        private fun backfillIds(held: List<CircleEntries.Held>): CircleEntries.Outcome {
            val ids = strings(held) ?: return CircleEntries.Outcome.Refuse
            // held lists the one key first
            return CircleEntries.Outcome.Keep(
                ids.flatMap { set -> set.map(::id).sorted() }.distinct().take(com.pingolin.track.domain.repository.CircleRepository.MAX_BACKFILL_IDS).toSet()
            )
        }

        /**
         * The approval records ([ORIGIN_SUFFIXES]) and receive-only keys ([RO_KEYS]), per member:
         * what the spellings holding them agree on; else the circle's one spelling's; else none.
         * Folding two spellings, a member the fold holds a tombstone for keeps none.
         */
        private fun approvals(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>): Map<String, CircleEntries.Outcome> {
            val slots = (ORIGIN_SUFFIXES + RO_KEYS).filter { it in entries }
            if (slots.isEmpty()) return emptyMap()
            val one = spellings.firstOrNull { AccountIds.canonical(it) == it }
            val tombstoned = if (spellings.size < 2) emptySet() else entries[TOMB].orEmpty()
                .flatMap { (it.value as? Set<*>).orEmpty() }.filterIsInstance<String>().map { id(it.substringBefore(SEPARATOR)) }.toSet()
            // member → spelling → what that spelling holds for them: the slots approving them, and their receive-only keys
            val held = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
            for (slot in slots) for (h in entries.getValue(slot)) {
                val set = (h.value as? Set<*>)?.takeIf { s -> s.all { it is String } } ?: return slots.associateWith { CircleEntries.Outcome.Refuse }
                for (entry in set.map { it as String }) {
                    val member = id(entry.substringBefore(SEPARATOR))
                    val what = if (slot == RO_KEYS) "$RO_KEYS${entry.substringAfter(SEPARATOR, "").lowercase()}" else slot
                    held.getOrPut(member) { linkedMapOf() }.getOrPut(h.spelling) { mutableSetOf() } += what
                }
            }
            val kept = slots.associateWith { mutableSetOf<String>() }
            for ((member, bySpelling) in held) {
                if (member in tombstoned) continue
                val stands = bySpelling.values.distinct().singleOrNull() ?: one?.let { bySpelling[it] } ?: continue
                for (what in stands) {
                    if (what.startsWith(RO_KEYS)) kept.getValue(RO_KEYS) += "$member$SEPARATOR${what.removePrefix(RO_KEYS)}"
                    else kept.getValue(what) += member
                }
            }
            return kept.mapValues { (_, members) -> CircleEntries.Outcome.Keep(members.toSet()) }
        }
    }
}
