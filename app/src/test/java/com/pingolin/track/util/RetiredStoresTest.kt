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
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The retired kept-name store's files go at every launch and sign-in, and nothing else does. */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's native lib.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetiredStoresTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(name: String) = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$name.xml")

    @Test
    fun `both kept-name files go, and the stores still kept stay`() {
        assertEquals(listOf("circle_names_read_here_2", "circle_names_read_here"), RetiredStores.FILES)
        for (name in RetiredStores.FILES + "own_removals") {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("me|c-1", "Family").commit()
            assertTrue(name, file(name).exists())
        }

        RetiredStores.purge(context)

        for (name in RetiredStores.FILES) assertFalse(name, file(name).exists())
        assertTrue("a store still kept stays", file("own_removals").exists())
    }

    @Test
    fun `the purge runs at every launch and every sign-in`() {
        fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()
        assertTrue(source("TrackApplication.kt").contains("Thread({ com.pingolin.track.util.RetiredStores.purge(this) }, \"retired-stores\").start()"))
        assertTrue(source("domain/repository/AuthRepository.kt").contains("com.pingolin.track.util.RetiredStores.purge(context)"))
    }

    @Test
    fun `a purge that fails fails nothing`() {
        val refusing = mockk<Context>()
        every { refusing.deleteSharedPreferences(any()) } throws SecurityException("refused")

        RetiredStores.purge(refusing)
    }
}
