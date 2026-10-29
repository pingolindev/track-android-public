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

import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.testutil.UnopenablePrefs
import com.pingolin.track.util.StoreUnreadable
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asked once: the invitations answered here (LateJoinerAnswers), never read as "none answered". */
class LateJoinerAnswersTest {

    private val bob = LateJoiners.Invitation("inv-bob", "me", "bob", 1_000L)
    private val cy = LateJoiners.Invitation("inv-cy", "me", "cy", 2_000L)

    /** What answers that can't be read must do: never "none", and nothing written over them. */
    private fun unreadable(answers: LateJoinerAnswers) {
        assertThrows(StoreUnreadable::class.java) { answers.answered("c-1") }
        assertFalse("not kept", answers.answer("c-1", cy))
    }

    @Test
    fun `a damaged read is never none - nothing is written over it, and it's all there once it reads`() {
        val prefs = DamagedPrefs()
        val answers = SecurePrefsLateJoinerAnswers(mockk(), { "me" }, { prefs })
        assertTrue(answers.answer("c-1", bob))
        val stored = prefs.data.toMap()

        prefs.damaged = true
        unreadable(answers)
        assertEquals("nothing written over it", stored, prefs.data)

        prefs.damaged = false
        assertEquals(listOf(bob), answers.answered("c-1"))
    }

    @Test
    fun `a file that can't be opened - the same, and it's opened again at the next use`() {
        val inner = InMemoryPrefs()
        assertTrue(SecurePrefsLateJoinerAnswers(mockk(), { "me" }, { inner }).answer("c-1", bob))
        val stored = inner.data.toMap()
        val file = UnopenablePrefs(inner)
        val answers = SecurePrefsLateJoinerAnswers(mockk(), { "me" }, file.provider)

        file.unopenable = true
        unreadable(answers)
        assertEquals("nothing written over it", stored, inner.data)

        file.unopenable = false
        assertEquals(listOf(bob), answers.answered("c-1"))
    }
}
