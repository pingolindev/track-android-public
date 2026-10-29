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

import com.pingolin.track.util.KeptPerAccount
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Test

/** An account's sign-in and an erasure reach every store CircleRepository keeps per account (KeptPerAccount). */
class KeptPerAccountWiringTest {

    @After
    fun tearDown() = clearAllMocks()

    private val h = TrustHarness(selfRole = "owner")

    private val stores: List<KeptPerAccount> = listOf(
        mockk<com.pingolin.track.util.OwnRemovals>(relaxed = true).also { h.repository.ownRemovals = it },
        mockk<com.pingolin.track.util.OwnerRoleChanges>(relaxed = true).also { h.repository.ownerRoleChanges = it },
        mockk<com.pingolin.track.util.RepairBudget>(relaxed = true).also { h.repository.repairBudget = it },
        mockk<LateJoinerAnswers>(relaxed = true).also { h.repository.lateJoinerAnswers = it },
        mockk<com.pingolin.track.util.HandOvers>(relaxed = true).also { h.repository.handOvers = it },
        mockk<com.pingolin.track.util.PendingInviteAccepts>(relaxed = true).also { h.repository.pendingInviteAccepts = it },
        mockk<com.pingolin.track.util.PersonConfirmedKeys>(relaxed = true).also { h.repository.personConfirmedKeys = it },
        mockk<com.pingolin.track.util.PendingRenewalQuestions>(relaxed = true).also { h.repository.pendingRenewalQuestions = it },
        mockk<com.pingolin.track.util.SosMarks>(relaxed = true).also { h.repository.sosMarks = it },
    )

    @Test
    fun `an account's sign-in keeps only its entries in every store kept per account, one failing or not`() {
        every { stores.first().keepOnly(any(), any()) } throws IllegalStateException("can't")

        h.repository.keepOnlyAccount("me") { true }

        stores.forEach { verify(exactly = 1) { it.keepOnly("me", any()) } }
    }

    @Test
    fun `the typed-email guard reaches the owed removals, role changes and SOS marks, and only them`() {
        // What holds trust goes for another account only on the guard; every other store keeps its
        // keepOnly. The SOS marks hold an obligation ("ended", never alarm again) and go with the
        // guarded ones: a server that swaps the account id can't wipe them.
        val guard: (String) -> Boolean = { false }

        h.repository.keepOnlyAccount("me", guard)

        val (holdingTrust, others) = stores.partition {
            it is com.pingolin.track.util.OwnRemovals || it is com.pingolin.track.util.OwnerRoleChanges ||
                it is com.pingolin.track.util.SosMarks
        }
        org.junit.Assert.assertEquals(3, holdingTrust.size)
        holdingTrust.forEach { verify(exactly = 1) { it.keepOnly("me", guard) } }
        others.forEach { verify(exactly = 0) { it.keepOnly("me", guard) } }
    }

    @Test
    fun `an erasure wipes every store kept per account, one failing or not`() {
        every { stores.first().wipeAll() } throws IllegalStateException("can't")

        h.repository.wipeAccountEntries()

        stores.forEach { verify(exactly = 1) { it.wipeAll() } }
    }
}
