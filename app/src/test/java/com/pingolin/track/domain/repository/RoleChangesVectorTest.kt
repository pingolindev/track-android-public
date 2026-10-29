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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.util.SecurePrefsOwnerRoleChanges
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.RecordMemberState
import java.io.File
import java.security.MessageDigest

/**
 * The shared role-change vector (src/test/resources/role-changes-vector.json, the same file every client
 * tests against), whole: for each case, whether a record is due ([RecordedRotation.rolesDue]), what it names
 * ([RecordedRotation.rotation]), and what it settles once the server takes it
 * ([RecordedRotation.rolesSettle] and the owed changes' own store, by member and role, never by member
 * alone).
 */
class RoleChangesVectorTest {

    private val file = File("src/test/resources/role-changes-vector.json")
    private val vector = JsonParser.parseString(file.readText()).asJsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.flag(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
    private fun JsonObject.roles(key: String): Map<String, String>? =
        getAsJsonObject(key)?.entrySet()?.associate { (id, role) -> id to role.asString }

    private fun role(name: String?) = RefreshRecords.role(name)
    private fun name(role: CircleRole?) = role?.name?.lowercase()

    private data class Member(val userId: String, val listed: Boolean, val hasKeys: Boolean, val eligible: Boolean, val chainRole: CircleRole?)

    @Test
    fun `the copy is the shared file, byte for byte`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("d7ac0420bcb83c2f60e5292ad3c81f559e2ca5788fb212a8b4bee5d2fd31cf93", sha)
        assertEquals("the whole vector", 12, vector.getAsJsonArray("cases").size())
    }

    @Test
    fun `every case - due, what the record names, what it settles and what stays owed`() {
        val wrong = mutableListOf<String>()
        for (c in vector.getAsJsonArray("cases").map { it.asJsonObject }) {
            val case = c.str("name")!!
            val input = c.getAsJsonObject("input")
            val expect = c.getAsJsonObject("expect")
            val me = input.str("me")!!
            val myRole = role(input.str("myChainRole"))
            val owed = input.roles("owed")!!
            val members = input.getAsJsonArray("members").map { it.asJsonObject }.map { m ->
                Member(m.str("userId")!!, m.flag("listed"), m.flag("hasKeys"), m.flag("eligible"), role(m.str("chainRole")))
            }
            // Looked up exactly as the vector spells them (canonical): spelling an owed id otherwise is the
            // rules' to mend.
            val byId = members.associateBy { it.userId }
            fun chainRole(id: String) = if (id == me) myRole else byId[id]?.chainRole
            fun includable(id: String) = byId[id]?.let { it.listed && it.hasKeys && it.eligible } == true

            val due = RecordedRotation.rolesDue(myRole, owed, ::chainRole, ::includable)

            val listed = listOf(me) + members.filter { it.listed }.map { it.userId }
            val plan = RecordedRotation.rotation(
                self = me,
                listed = listed,
                wrap = setOf(me) + members.filter { it.hasKeys }.map { it.userId },
                eligible = setOf(me) + members.filter { it.eligible }.map { it.userId },
                chainRole = ::chainRole,
                ownerRoleChanges = owed.mapNotNull { (id, r) -> role(r)?.let { id to it } }.toMap(),
            )
            val planned = members.associate { m ->
                val entry = plan.entries.firstOrNull { it.userId.equals(m.userId, ignoreCase = true) }
                val state = when (entry?.state) {
                    RecordMemberState.INCLUDED -> "included"
                    RecordMemberState.PENDING -> "pending"
                    RecordMemberState.REMOVED -> "removed"
                    RecordMemberState.DEPARTED -> "departed"
                    null -> "absent"
                }
                m.userId to (state to name(entry?.role))
            }

            // What the server takes it with: the owed changes then, a change made meanwhile included.
            val atTake = input.roles("ledgerAtTake") ?: owed
            val prefs = InMemoryPrefs()
            val ledger = SecurePrefsOwnerRoleChanges(mockk(), { me }, { prefs })
            for ((id, r) in atTake) assertTrue(ledger.put("c-1", id, r))
            val before = ledger.of("c-1")
            ledger.settled("c-1", RecordedRotation.rolesSettle(myRole == CircleRole.OWNER, owed, listed, plan))
            val owedAfter = ledger.of("c-1")
            val settles = before.filter { (id, r) -> owedAfter[id] != r }

            val wantPlan = expect.getAsJsonObject("plan").entrySet().associate { (id, p) ->
                id to (p.asJsonObject.str("state")!! to p.asJsonObject.str("role"))
            }
            val got = listOf(due, planned, settles, owedAfter)
            val want = listOf(expect.flag("due"), wantPlan, expect.roles("settles"), expect.roles("owedAfter"))
            if (got != want) wrong += "$case:\n   got  (due, plan, settles, owedAfter) $got\n   want $want"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}
