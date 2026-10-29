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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Who this phone trusts in each circle (KeyAccessApprovals: approvals with their
 * origins, tombstones, the "created here" record) must never reach a new phone through a
 * backup or a device-to-device transfer. The keys and pins those records vouch for stay
 * in the Keystore; a record restored without them would sit next to empty pins and vouch
 * for whatever the server then lists.
 *
 * Nothing of this app's is backed up or transferred at all, and the approvals are in
 * Keystore-encrypted preferences besides. Both are pinned here.
 */
class BackupExclusionTest {

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path)).documentElement

    @Test
    fun `the app opts out of backup and names rules for Android 12 and later`() {
        val app = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("false", app.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
    }

    @Test
    fun `no preferences, database or file goes to a cloud backup or a new phone`() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val block = rules.getElementsByTagName(section).item(0) as Element
            assertEquals("$section includes nothing", 0, block.getElementsByTagName("include").length)
            val excluded = (0 until block.getElementsByTagName("exclude").length)
                .map { block.getElementsByTagName("exclude").item(it) as Element }
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            assertTrue("$section excludes $excluded", excluded.containsAll(setOf("sharedpref", "database", "file", "root")))
        }
    }

    @Test
    fun `the approval records are in Keystore-encrypted preferences`() {
        val src = File("src/main/java/com/pingolin/track/util/KeyAccessApprovals.kt").readText()
        assertTrue(src.contains("private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },"))
        val securePrefs = File("src/main/java/com/pingolin/track/util/SecurePrefs.kt").readText()
        assertTrue(securePrefs.contains("EncryptedSharedPreferences"))
    }
}
