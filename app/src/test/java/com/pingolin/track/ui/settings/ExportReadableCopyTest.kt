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

package com.pingolin.track.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a data export is, as the Settings screen and the export dialog say it: a JSON file
 * decrypted on this device, which anyone who opens it can read, unless a passphrase seals it —
 * never "All exported data remains encrypted", with a lock, over a file that is plaintext without
 * a passphrase (ExportRepository's fileBody is built from the decrypted export). The words are
 * the iPhone app's, from its export footer.
 */
class ExportReadableCopyTest {

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val APP = "http://schemas.android.com/apk/res-auto"

        /** The iPhone's sentence, word for word, where a passphrase field follows it. */
        const val BESIDE_THE_FIELD =
            "The file is written in JSON, decrypted on this device. Anyone who opens it can read it, so keep it " +
                "somewhere safe — or seal it below."

        /** The same, where no file exists yet and no passphrase field follows: Settings, before the export starts. */
        const val IN_SETTINGS =
            "An export file is written in JSON, decrypted on this device. Anyone who opens it can read it, so keep it " +
                "somewhere safe — or seal it with a passphrase when you export."
    }

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path)).documentElement

    private fun layout(name: String): Element = parse("src/main/res/layout/$name.xml")

    /** strings.xml's values, by name, as the app reads them (`\'` unescaped). */
    private val strings: Map<String, String> by lazy {
        val all = parse("src/main/res/values/strings.xml").getElementsByTagName("string")
        (0 until all.length).associate { i ->
            val e = all.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'")
        }
    }

    /** An attribute's value, a `@string/` reference resolved. */
    private fun Element.attr(name: String): String? = getAttributeNS(ANDROID, name).takeIf { it.isNotEmpty() }
        ?.let { value -> value.removePrefix("@string/").takeIf { it != value }?.let { strings.getValue(it) } ?: value }

    /** Every element under this one, in document order. */
    private fun Element.all(): List<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `nothing about an export says it stays encrypted`() {
        for (path in listOf("layout/dialog_export_data", "layout/fragment_settings", "values/strings")) {
            val text = File("src/main/res/$path.xml").readText()
            assertFalse(path, text.contains("remains encrypted"))
            assertFalse(path, text.contains("decryption happens on your device"))
        }
    }

    @Test
    fun `the export dialog says the file is readable, above the passphrase that seals it, with no lock`() {
        val all = layout("dialog_export_data").all()
        val notice = all.single { it.attr("text") == BESIDE_THE_FIELD }
        val field = all.single { it.attr("id") == "@+id/exportPassphraseLayout" }
        assertTrue("\"seal it below\" points at the passphrase", all.indexOf(notice) < all.indexOf(field))
        assertNull("no lock: the file is not locked unless a passphrase seals it", notice.attr("drawableStart"))
        // The web's words for the field, word for word, so the two read alike.
        assertEquals("Seal with a passphrase (optional)", field.attr("hint"))
        assertEquals("Leave blank for a readable file", field.getAttributeNS(APP, "placeholderText").let { strings.getValue(it.removePrefix("@string/")) })
        val help = all.single { it.attr("text")?.startsWith("With a passphrase the file is sealed") == true }
        assertEquals(
            "With a passphrase the file is sealed and only that passphrase opens it. Without one it stays readable, " +
                "which is what you want if another app needs to import it. There is no way to recover a lost passphrase.",
            help.attr("text"),
        )
        assertTrue("the help sits under the field", all.indexOf(field) < all.indexOf(help))
    }

    @Test
    fun `the export dialog scrolls, so a small phone or a large font still reaches the passphrase`() {
        // AlertDialog measures its custom view in the height left between its title and its
        // buttons; a root that can't scroll is cut off there (on a Galaxy A50 at font scale
        // 1.1, to 7 px of the field and none of the help). The root is what SettingsFragment
        // hands setView.
        val root = layout("dialog_export_data")
        assertTrue(root.tagName, root.tagName in setOf("androidx.core.widget.NestedScrollView", "ScrollView"))
    }

    @Test
    fun `the field's label always sits on the outline, where all of it fits`() {
        // Inside the box, beside the eye icon, "Seal with a passphrase (optional)" is cut short, and
        // "(optional)" goes first. Collapsed, it fits, and "Leave blank for a readable file" shows
        // before the field is tapped (Material shows the placeholder whenever the hint is collapsed).
        val field = layout("dialog_export_data").all().single { it.attr("id") == "@+id/exportPassphraseLayout" }
        assertEquals("false", field.getAttributeNS(APP, "expandedHintEnabled"))
    }

    @Test
    fun `the words live in string resources - no new hardcoded text for lint`() {
        val dialog = layout("dialog_export_data").all()
        assertEquals("@string/export_readable_notice",
            dialog.single { it.getAttributeNS(ANDROID, "text") == "@string/export_readable_notice" }.getAttributeNS(ANDROID, "text"))
        assertEquals("@string/export_passphrase_hint",
            dialog.single { it.getAttributeNS(ANDROID, "id") == "@+id/exportPassphraseLayout" }.getAttributeNS(ANDROID, "hint"))
        assertEquals(1, dialog.count { it.getAttributeNS(ANDROID, "text") == "@string/export_passphrase_help" })
        assertEquals(1, layout("fragment_settings").all().count { it.getAttributeNS(ANDROID, "text") == "@string/export_readable_notice_settings" })
    }

    @Test
    fun `the Settings screen says the same, and how to seal it`() {
        val notes = layout("fragment_settings").all().filter { it.attr("text") == IN_SETTINGS }
        assertEquals(1, notes.size)
        assertNull(notes.single().attr("drawableStart"))
    }

    // ---- The code behind the words. Without a passphrase the file is the decrypted export:
    // ---- ExportFileKeyStoreTest's `the file body is the export as this device read it, in the clear`.

    /** SettingsFragment's source: line comments out, whitespace runs one space, none inside parentheses. */
    private val fragment: String by lazy {
        File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readLines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString(" ")
            .replace(Regex("""\s+"""), " ").replace("( ", "(").replace(Regex(""",? \)"""), ")")
    }

    @Test
    fun `with a passphrase the file written is the sealed one, and a seal that fails writes nothing`() {
        // The help under the field: "With a passphrase the file is sealed". The seal is the SDK's,
        // called inline where the export lands, so this reads the source.
        val onSuccess = fragment.substringAfter("result.onSuccess { export ->").substringBefore("}.onFailure")
        assertTrue(onSuccess, onSuccess.contains(
            "val pass = pendingExportPassphrase pendingExportBody = if (pass.isNullOrEmpty()) { export.fileBody } else { " +
                "try { uniffi.pingolin_mobile.sealBackupFile(export.fileBody, pass, \"pingolin-track\", " +
                "java.time.Instant.now().toString()) } catch (e: Exception) {"
        ))
        assertEquals("the body is set once, sealed or readable", 1, onSuccess.split("pendingExportBody =").size - 1)
        // A seal that throws says so and returns before the picker opens, so no file is written.
        assertTrue(onSuccess, onSuccess.substringAfter("} catch (e: Exception) {").contains(
            "\"Couldn't prepare the export just now.\", Toast.LENGTH_LONG).show() return@onSuccess } } " +
                "pendingExportSummary = export createBackupDocument.launch("
        ))
        // The picker opens from there only, and writes that body.
        assertEquals(1, fragment.split("createBackupDocument.launch(").size - 1)
        val writer = fragment.substringAfter("private val createBackupDocument =").substringBefore("private val openBackupDocument")
        assertTrue(writer, writer.contains("val body = pendingExportBody"))
        assertTrue(writer, writer.contains("it.write(body.toByteArray())"))
    }
}
