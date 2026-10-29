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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.track.R
import com.pingolin.track.domain.repository.LateJoiners.Case
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.MemberEligibility
import java.io.File

/**
 * The shared late-joiner vector (the same file for web, iOS and Android), whole: its every case,
 * its batch and its closing, against Android's own rules (LateJoiners) and its own words. Among
 * them: the cases in their set order (V19); the questions already raised first, then the oldest
 * (B1); and no inviter line for an inviter this device doesn't know (V18).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LateJoinerVectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vector = JsonParser.parseString(File("src/test/resources/late-joiner-vector.json").readText()).asJsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.flag(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

    private fun role(name: String?) = when (name) {
        "owner" -> CircleRole.OWNER
        "admin" -> CircleRole.ADMIN
        "member" -> CircleRole.MEMBER
        else -> null
    }

    private fun invitation(o: JsonObject) = LateJoiners.Invitation(o.str("id")!!, o.str("signer")!!, o.str("person")!!, o.get("issuedAt").asLong)

    /** The SDK's eligibility answer on [userId], as the vector's
     * `standing` gives it.
     */
    private fun standing(userId: String, s: JsonObject) = MemberEligibility(
        userId = userId,
        eligible = s.flag("eligible"),
        basis = null,
        invitationExpired = s.flag("invitationExpired"),
        invitationSignerNotChainAdmin = s.flag("signerNotChainAdmin"),
        removedByOwnerOrAdmin = s.flag("removedByOwnerOrAdmin"),
        removedAtVersion = if (s.flag("removedByOwnerOrAdmin")) 3 else null,
        emailInvitationWithoutSignedId = s.flag("emailInvitationWithoutSignedId"),
    )

    private val caseNames = mapOf(
        "ranOut" to Case.EXPIRED,
        "inviterNotChainAdmin" to Case.SIGNER_NOT_CHAIN_ADMIN,
        "emailInvitation" to Case.EMAIL_WITHOUT_ID,
        "ownInvitationBeforeReset" to Case.INVITED_BEFORE_RESET,
    )

    private fun lines(case: Case?, inviter: String?) =
        LateJoiners.lines(case, inviter, context.getString(R.string.late_joiner_invitation_ran_out)) { context.getString(R.string.late_joiner_invited_by, it) }

    @Test
    fun `the vector's words are Android's`() {
        val copy = vector.getAsJsonObject("copy")
        assertEquals(
            copy.str("title")!!.replace("{name}", "Quinn").replace("{circle}", "Family"),
            context.getString(R.string.let_in_title, "Quinn", "Family"),
        )
        assertEquals(copy.str("ranOut"), context.getString(R.string.late_joiner_invitation_ran_out))
        assertEquals(copy.str("invitedBy")!!.replace("{inviter}", "Sam"), context.getString(R.string.late_joiner_invited_by, "Sam"))
    }

    @Test
    fun `every case - who is asked, why, whether it counts in the cap, whom it names, and its lines`() {
        val cases = vector.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals("the whole vector", 22, cases.size)
        val wrong = mutableListOf<String>()
        for (c in cases) {
            val name = c.str("name")!!
            val input = c.getAsJsonObject("input")
            val expect = c.getAsJsonObject("expect")
            val member = input.str("member")!!
            val invitation = input.getAsJsonObject("invitation")
            val signerRole = role(input.str("signerChainRole"))
            val standing = input.getAsJsonObject("standing")
            val ours = LateJoiners.Candidate(
                member, standing(member, standing), invitation(invitation), signerRole,
                ownPreResetBasis = invitation.flag("signedBeforeReset"),
                bindsListedKeys = invitation.get("boundKeys") != null && invitation.get("boundKeys") == input.get("listedKeys"),
                chainUnforked = true,
            )
            // Questions already pending in the circle: about another member with an invitation, and
            // the capped ones (their inviters no chain owner or admin).
            val pending = input.getAsJsonArray("pending").mapIndexed { i, p ->
                LateJoiners.Candidate("pending-$i", standing("pending-$i", standing), invitation(p.asJsonObject), signerRole)
            }
            val capped = (0 until input.get("pendingCapped").asInt).map { i ->
                val e = MemberEligibility("capped-$i", false, null, false, true, false, null, false)
                LateJoiners.Candidate("capped-$i", e, LateJoiners.Invitation("capped-inv-$i", "capped-signer-$i", "capped-$i", 1_790_000_000L + i), CircleRole.MEMBER)
            }
            val asked = LateJoiners.questions(
                input.str("me")!!,
                role(input.str("myChainRole")),
                listOf(ours) + pending + capped,
                input.getAsJsonArray("answered").map { invitation(it.asJsonObject) },
                (pending + capped).map { it.userId }.toSet(),
            )
            val q = asked.firstOrNull { it.userId == member }
            val shouldAsk = expect.get("ask").asBoolean
            if ((q != null) != shouldAsk) {
                wrong += "$name: asked ${q != null}, the vector says ${shouldAsk}"
                continue
            }
            if (q == null) continue
            val signerName = input.str("signerName")
            val inviter = LateJoiners.inviterName(q) { signerName }
            val got = listOf(q.case, !q.inviterIsChainAdmin, inviter, lines(q.case, inviter))
            val want = listOf(
                caseNames.getValue(expect.str("case")!!),
                expect.get("capped").asBoolean,
                expect.str("inviterLine"),
                expect.getAsJsonArray("lines").map { it.asString },
            )
            if (got != want) wrong += "$name: (case, capped, inviter, lines) $got, the vector says $want"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `the batch - of the capped, the questions already raised first, then the oldest, and a chain owner's own uncapped`() {
        val batches = vector.getAsJsonArray("batch").map { it.asJsonObject }
        assertEquals(1, batches.size)
        for (b in batches) {
            val input = b.getAsJsonObject("input")
            val all = input.getAsJsonObject("standingAll")
            val each = input.getAsJsonObject("standingFor")
            val candidates = input.getAsJsonArray("candidates").map { it.asJsonObject }
            val asked = LateJoiners.questions(
                input.str("me")!!,
                role(input.str("myChainRole")),
                candidates.map { c ->
                    val member = c.str("member")!!
                    LateJoiners.Candidate(member, standing(member, each.getAsJsonObject(member) ?: all), invitation(c.getAsJsonObject("invitation")), role(c.str("signerChainRole")))
                },
                emptyList(),
                candidates.filter { it.flag("raised") }.map { it.str("member")!! }.toSet(),
            )
            val expect = b.getAsJsonObject("expect")
            assertEquals(b.str("name"), false, expect.flag("orderSignificant"))
            assertEquals(b.str("name"), expect.getAsJsonArray("asked").map { it.asString }.toSet(), asked.map { it.userId }.toSet())
        }
    }

    @Test
    fun `the closing - a raised question closes when E lets its member in or they leave the list`() {
        val closings = vector.getAsJsonArray("closing").map { it.asJsonObject }
        assertEquals(1, closings.size)
        for (c in closings) {
            val input = c.getAsJsonObject("input")
            val raised = input.getAsJsonArray("raised").map { pair ->
                val (member, invitation) = pair.asJsonArray.let { it[0].asString to it[1].asJsonObject }
                member to invitation(invitation)
            }
            val eligibility = input.getAsJsonArray("eligibility").map { it.asJsonObject }.map { e ->
                MemberEligibility(e.str("userId")!!, e.flag("eligible"), null, false, false, false, null, false)
            }
            val closed = LateJoiners.closed(raised, eligibility).map { it.id }
            assertEquals(c.str("name"), c.getAsJsonObject("expect").getAsJsonArray("closed").map { it.asString }, closed)
        }
    }

    @Test
    fun `the question names its inviter only as LateJoiners does, and its lines are LateJoiners'`() {
        val repository = File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        val raise = repository.substringAfter("private suspend fun raiseLateJoinerQuestions(").substringBefore("\n    /**")
        assertTrue(
            raise.contains(
                "inviterName = LateJoiners.inviterName(q) { signer ->\n" +
                    "                    names[signer]?.takeUnless { it.isBlank() || it == UNNAMED_MEMBER }\n" +
                    "                        ?: circleMemberDao.anyDisplayName(signer)?.takeUnless { it == UNNAMED_MEMBER }\n" +
                    "                },"
            )
        )
        assertTrue("no stand-in for an inviter nobody here knows", !raise.contains("\"A member\""))
        val screen = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        val dialog = screen.substringAfter("private fun showKeyAccessDialog(").substringBefore("\n    private fun ")
        assertTrue(
            dialog.contains(
                "com.pingolin.track.domain.repository.LateJoiners.lines(\n" +
                    "                request.lateCase, request.inviterName, getString(R.string.late_joiner_invitation_ran_out),\n" +
                    "            ) { getString(R.string.late_joiner_invited_by, it) }.joinToString(\"\\n\\n\").ifEmpty { null }"
            )
        )
    }
}
