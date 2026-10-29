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

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.entity.CircleEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resumed notice names its circle by the name this device keeps for it, and the fetch asks for
 * that name, as it does for a check-in: the row carries only the circle's id.
 */
class PrivacyResumedNameTest {

    private val circle = "fa0c1e00-0000-4000-8000-0000000000fa"

    private fun names(): NotificationNames {
        val circleDao = mockk<CircleDao>()
        coEvery { circleDao.getById(any()) } returns null
        coEvery { circleDao.getById(circle) } returns mockk<CircleEntity> { every { name } returns "Family" }
        return NotificationNames(
            circleMemberDao = mockk(relaxed = true),
            circleDao = circleDao,
            placeDao = mockk(relaxed = true),
            tracksApi = mockk(relaxed = true),
            cryptoManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            circleRepository = dagger.Lazy { mockk(relaxed = true) },
            sosRepository = dagger.Lazy { mockk(relaxed = true) },
        )
    }

    @Test
    fun `a resumed notice is named by its circle, and a circle this device doesn't know names nothing`() = runTest {
        assertEquals("Family", names().namesFor("privacy_resumed", circle, emptyMap(), NotificationNames.Pass()).circle)
        assertNull(names().namesFor("privacy_resumed", "c0000000-0000-4000-8000-0000000000c0", emptyMap(), NotificationNames.Pass()).circle)
        assertNull(names().namesFor("privacy_resumed", null, emptyMap(), NotificationNames.Pass()).circle)
    }

    @Test
    fun `a refusal is named by its circle, and never by a person`() = runTest {
        val names = names().namesFor("circle_ownership_refused", circle, mapOf("claim_id" to "k-1"), NotificationNames.Pass())
        assertEquals("Family", names.circle)
        assertNull(names.person)
    }

    @Test
    fun `the fetch names a resumed row before showing it`() {
        val fetcher = java.io.File("src/main/java/com/pingolin/track/service/NotificationTickleFetcher.kt").readText()
        val named = fetcher.substring(fetcher.indexOf("if (type == AlertHandler.TYPE_CHECK_IN || type == AlertHandler.TYPE_SOS_ACKNOWLEDGED ||"))
            .let { it.substring(0, it.indexOf("nameForAlertHandler(notif, alertData, namesPass)")) }
        assertTrue(named.contains("type == AlertHandler.TYPE_PRIVACY_RESUMED"))
    }
}
