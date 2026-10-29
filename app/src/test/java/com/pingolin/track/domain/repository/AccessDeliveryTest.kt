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

import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.util.KeyAccessApprovals.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whom a device hands the circle's current copy to, under which key, and whom an owner/admin
 * device vouches for once: the pure rules, every branch.
 */
class AccessDeliveryTest {

    private val now = 1_800_000_000L

    private fun member(id: String, keyVersion: Int? = 5, sharing: String = "$id-x", signing: String = "$id-s") =
        MemberConfirmation.Member(id, sharing, signing, keyVersion)

    private fun statement(kind: Kind, atVersion: Int = 5, expiresAt: Long = now + 3600) =
        MembershipStatements.Statement(kind, "c-1", "s", "owner", atVersion = atVersion, expiresAt = expiresAt)

    private fun result(
        confirmed: Map<String, Origin>,
        holding: Map<String, List<MembershipStatements.Statement>> = emptyMap(),
        selfReceiveOnly: Boolean = false,
    ) = MemberConfirmation.Result(
        confirmed = confirmed, newlyConfirmed = emptyMap(), boundKeys = emptyMap(), invalid = emptySet(),
        signerUnconfirmed = emptySet(), signerChanged = emptySet(), askSigner = emptySet(),
        statementLess = emptySet(), subjectConflict = emptySet(), holding = holding,
        selfReceiveOnly = selfReceiveOnly,
    )

    private fun plan(
        roster: List<MemberConfirmation.Member>,
        result: MemberConfirmation.Result,
        accessRequested: Set<String> = emptySet(),
        createdHere: Boolean = false,
        pins: Map<String, String> = roster.associate { it.userId to it.sharingKeyHex!! },
        receiveOnlyKeys: Map<String, Pair<String, String>> = emptyMap(),
        eDeliver: Set<String>? = null,
    ) = AccessDelivery.plan(
        selfUserId = "me", result = result, roster = roster, accessRequested = accessRequested,
        activeVersion = 5, nowSecs = now, createdHere = createdHere,
        pinnedSharingKey = { pins[it] }, receiveOnlyKeys = receiveOnlyKeys, eDeliver = eDeliver,
    ).map { it.userId }

    @Test
    fun `on a circle with records E_deliver decides, not the confirmation cases or the creator's exemption`() {
        // dan holds an unexpired invitation (a first delivery), bob was chosen here (OWN), and
        // this device created the circle; on a circle with records only `eDeliver`, the members
        // its records allow a delivery to, counts.
        val roster = listOf(member("me"), member("dan", keyVersion = null), member("bob", keyVersion = null), member("fay", keyVersion = null))
        val r = result(
            mapOf("me" to Origin.OWN, "dan" to Origin.STATEMENT, "bob" to Origin.OWN, "fay" to Origin.STATEMENT),
            holding = mapOf("dan" to listOf(statement(Kind.INVITATION_TO_ACCOUNT))),
        )
        assertEquals(listOf("dan", "bob", "fay"), plan(roster, r, createdHere = true))

        assertEquals(listOf("FAY".lowercase()), plan(roster, r, createdHere = true, eDeliver = setOf("FAY")))
        // `eDeliver` never widens the rest: confirmed here, owed, under the trusted key.
        assertEquals(emptyList<String>(), plan(roster, r, eDeliver = setOf("zed")))
        assertEquals(emptyList<String>(), plan(roster, r, eDeliver = setOf("fay"), pins = mapOf("fay" to "fay-old-x")))
    }

    @Test
    fun `only confirmed members who are owed a copy get one`() {
        val roster = listOf(member("me"), member("dan", keyVersion = null), member("eve", keyVersion = null), member("fay"))
        val invited = listOf(statement(Kind.INVITATION_TO_ACCOUNT))
        val r = result(
            mapOf("me" to Origin.OWN, "dan" to Origin.STATEMENT, "fay" to Origin.STATEMENT),
            holding = mapOf("dan" to invited, "fay" to invited),
        )
        // eve is not confirmed; fay already holds the current version.
        assertEquals(listOf("dan"), plan(roster, r))
    }

    @Test
    fun `an app that asked for a fresh copy is owed one at the current version`() {
        val roster = listOf(member("me"), member("bob"))
        val r = result(mapOf("me" to Origin.OWN, "bob" to Origin.OWN))
        assertEquals(emptyList<String>(), plan(roster, r))
        assertEquals(listOf("bob"), plan(roster, r, accessRequested = setOf("bob")))
    }

    @Test
    fun `a copy goes only to the key this device trusts for them`() {
        val roster = listOf(member("me"), member("bob", keyVersion = null, sharing = "bob-new-x"))
        val r = result(mapOf("me" to Origin.OWN, "bob" to Origin.OWN))
        // The member list shows a key this device has not accepted: "It's them" first.
        assertEquals(emptyList<String>(), plan(roster, r, pins = mapOf("bob" to "bob-old-x")))
        assertEquals(emptyList<String>(), plan(roster, r, pins = emptyMap()))
        assertEquals(listOf("bob"), plan(roster, r, pins = mapOf("bob" to "BOB-NEW-X")))
    }

    @Test
    fun `an email invitee is served under the keys recorded for them, never a pin`() {
        val roster = listOf(member("me"), member("erin", keyVersion = null))
        val r = result(mapOf("me" to Origin.OWN, "erin" to Origin.OWN_KIND2))
        assertEquals(emptyList<String>(), plan(roster, r, pins = mapOf("erin" to "erin-x")))
        assertEquals(listOf("erin"), plan(roster, r, pins = emptyMap(), receiveOnlyKeys = mapOf("erin" to ("erin-x" to "erin-s"))))
        assertEquals(emptyList<String>(), plan(roster, r, pins = emptyMap(), receiveOnlyKeys = mapOf("erin" to ("erin-x" to "other-s"))))
    }

    @Test
    fun `a plain member serves only what the statements allow, the circle's creator any confirmed member`() {
        val roster = listOf(member("me"), member("alice", keyVersion = 4))
        val r = result(mapOf("me" to Origin.OWN, "alice" to Origin.PRE_ROUND2))
        assertEquals(emptyList<String>(), plan(roster, r))
        assertEquals(listOf("alice"), plan(roster, r, createdHere = true))
    }

    @Test
    fun `this device is never served by itself`() {
        val roster = listOf(member("me", keyVersion = null))
        assertEquals(emptyList<String>(), plan(roster, result(mapOf("me" to Origin.OWN)), createdHere = true))
    }

    // ---- backfill ------------------------------------------------------------------------

    private fun backfill(
        roster: List<MemberConfirmation.Member>,
        result: MemberConfirmation.Result,
        preRound2Seed: Boolean = true,
        ownerOrAdmin: Boolean = true,
        already: Set<String> = emptySet(),
        pins: Map<String, Pair<String, String>> = roster.associate { it.userId to (it.sharingKeyHex!! to it.signingKeyHex!!) },
        /** Members whose current copy a member confirmed here handed over. */
        handedOver: Set<String> = emptySet(),
    ) = AdmissionBackfill.candidates(
        selfUserId = "me", result = result, roster = roster, preRound2Seed = preRound2Seed,
        ownerOrAdmin = ownerOrAdmin, alreadyBackfilled = already,
        pinnedSharingKey = { pins[it]?.first }, pinnedSigningKey = { pins[it]?.second },
        handedOverByConfirmed = { it.userId in handedOver },
    ).map { it.userId }

    @Test
    fun `an owner device vouches only for members of its seed from before signed invitations that nothing else vouches for`() {
        val roster = listOf(member("me"), member("old"), member("signedSeed"), member("orphan"), member("erin"), member("own"), member("anchor"))
        val r = result(
            mapOf(
                "me" to Origin.OWN, "old" to Origin.PRE_ROUND2, "signedSeed" to Origin.PRE_ROUND2,
                "orphan" to Origin.STATEMENT, "erin" to Origin.KIND2, "own" to Origin.OWN, "anchor" to Origin.ANCHOR,
            ),
            holding = mapOf("signedSeed" to listOf(statement(Kind.INVITATION_TO_ACCOUNT))),
        )
        // signedSeed: its statement already vouches. orphan: no evidence (below). erin, own,
        // anchor: never — only a seed, or a copy handed over by a confirmed member, is evidence.
        assertEquals(listOf("old"), backfill(roster, r))
        assertEquals(listOf("old"), backfill(roster, r, handedOver = setOf("erin", "own", "anchor")))
    }

    @Test
    fun `a member whose statement no longer holds is vouched for only with a copy a confirmed member handed over`() {
        val roster = listOf(member("me"), member("orphan"))
        val r = result(mapOf("me" to Origin.OWN, "orphan" to Origin.STATEMENT))
        assertTrue(backfill(roster, r).isEmpty())
        assertEquals(listOf("orphan"), backfill(roster, r, handedOver = setOf("orphan")))
        // That evidence is enough without a seed, and only from an owner/admin device.
        assertEquals(listOf("orphan"), backfill(roster, r, preRound2Seed = false, handedOver = setOf("orphan")))
        assertTrue(backfill(roster, r, ownerOrAdmin = false, handedOver = setOf("orphan")).isEmpty())
        // A statement that still holds already vouches; a key change nobody accepted gets nothing.
        assertTrue(backfill(roster, result(mapOf("me" to Origin.OWN, "orphan" to Origin.STATEMENT),
            holding = mapOf("orphan" to listOf(statement(Kind.INVITATION_TO_ACCOUNT)))), handedOver = setOf("orphan")).isEmpty())
        assertTrue(backfill(roster, r, pins = mapOf("orphan" to ("orphan-x" to "orphan-older-s")), handedOver = setOf("orphan")).isEmpty())
    }

    @Test
    fun `no backfill without its conditions`() {
        val roster = listOf(member("me"), member("old"))
        val r = result(mapOf("me" to Origin.OWN, "old" to Origin.PRE_ROUND2))
        assertTrue(backfill(roster, r, preRound2Seed = false).isEmpty())
        assertTrue(backfill(roster, r, ownerOrAdmin = false).isEmpty())
        assertTrue(backfill(roster, r, already = setOf("old")).isEmpty())
        // Their key changed and nobody here said "It's them": nothing is signed for new keys.
        assertTrue(backfill(roster, r, pins = mapOf("old" to ("old-x" to "old-older-s"))).isEmpty())
        assertTrue(backfill(roster, result(mapOf("me" to Origin.OWN, "old" to Origin.PRE_ROUND2), selfReceiveOnly = true)).isEmpty())
    }
}
