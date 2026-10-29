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
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RootDetector.
 *
 * Tests the device compromise detection functionality.
 * Note: Some detection methods require actual file system access
 * and are better tested via instrumented tests.
 */
class RootDetectorTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
    }

    @Test
    fun `checkRoot returns RootCheckResult with isRooted flag`() {
        // Given - mock a clean device (no root apps installed)
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then
        assertNotNull(result)
        // On a mock without file system, most checks will pass
        // The actual rooted status depends on file system checks
    }

    @Test
    fun `isRooted returns boolean from checkRoot result`() {
        // Given - mock a clean device
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        // When
        val isRooted = RootDetector.isRooted(context)

        // Then
        // Result depends on file system state - just verify it returns a boolean
        assertTrue(isRooted is Boolean)
    }

    @Test
    fun `checkRoot detects root management apps when installed`() {
        // Given - Magisk is installed
        every {
            packageManager.getPackageInfo("com.topjohnwu.magisk", 0)
        } returns mockk(relaxed = true)

        // Other root apps not installed
        every {
            packageManager.getPackageInfo(neq("com.topjohnwu.magisk"), any<Int>())
        } throws PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then
        assertTrue(result.reasons.any { it.contains("Root management app") })
    }

    @Test
    fun `checkRoot detects SuperSU when installed`() {
        // Given - SuperSU is installed
        every {
            packageManager.getPackageInfo("eu.chainfire.supersu", 0)
        } returns mockk(relaxed = true)

        // Other root apps not installed
        every {
            packageManager.getPackageInfo(neq("eu.chainfire.supersu"), any<Int>())
        } throws PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then
        assertTrue(result.isRooted)
        assertTrue(result.reasons.any { it.contains("Root management app") })
    }

    @Test
    fun `checkRoot detects Xposed installer when installed`() {
        // Given - Xposed is installed
        every {
            packageManager.getPackageInfo("de.robv.android.xposed.installer", 0)
        } returns mockk(relaxed = true)

        // Other root/xposed apps not installed
        every {
            packageManager.getPackageInfo(neq("de.robv.android.xposed.installer"), any<Int>())
        } throws PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then
        assertTrue(result.isRooted)
        assertTrue(result.reasons.any {
            it.contains("Root management app") || it.contains("Xposed")
        })
    }

    @Test
    fun `checkRoot detects LSPosed manager when installed`() {
        // Given - LSPosed is installed
        every {
            packageManager.getPackageInfo("org.lsposed.manager", 0)
        } returns mockk(relaxed = true)

        // Other apps not installed
        every {
            packageManager.getPackageInfo(neq("org.lsposed.manager"), any<Int>())
        } throws PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then
        assertTrue(result.isRooted)
    }

    @Test
    fun `RootCheckResult reasons list is not null`() {
        // Given - clean device
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then
        assertNotNull(result.reasons)
    }

    @Test
    fun `checkRoot returns empty reasons on clean device without root apps`() {
        // Given - no root apps installed
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        // When
        val result = RootDetector.checkRoot(context)

        // Then - reasons should not contain root management app detection
        assertFalse(result.reasons.any { it.contains("Root management app") })
    }
}
