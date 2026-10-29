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

package com.pingolin.track.testutil

import com.pingolin.track.domain.pending.PendingChanges
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * A [PendingChanges] with nothing waiting, for tests about something else: its lists are empty
 * and everything else does nothing.
 */
fun quietPendingChanges(): PendingChanges = mockk(relaxed = true) {
    every { observe() } returns flowOf(emptyList())
    every { observeNotices() } returns flowOf(emptyList())
}

/**
 * A real [PendingChanges] over [dao] for [account], every circle named [circleName] and every
 * member "Sam", and nothing scheduled.
 */
fun pendingChangesIn(
    dao: InMemoryPendingChangeDao,
    account: String = "me",
    circleName: String = "Family",
    now: () -> Long = { 1_700_000_000_000L },
): PendingChanges = PendingChanges(
    dao,
    mockk { every { getUserId() } returns account },
    mockk(relaxed = true),
    mockk {
        io.mockk.coEvery { getById(any()) } answers {
            com.pingolin.track.data.local.entity.CircleEntity(arg(0), circleName, null, 0, 0)
        }
    },
    mockk {
        io.mockk.coEvery { get(any(), any()) } answers {
            com.pingolin.track.data.local.entity.CircleMemberEntity(arg(0), arg(1), "Sam", "member", joinedAt = 0)
        }
    },
).also { it.now = now }
