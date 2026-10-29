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

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A secret typed in Settings (the deletion's password, the App Lock's PIN dialogs) is hidden as it is
 * typed: a field with no password type shows it in clear text and the keyboard learns it, and a PIN
 * field set single-line after its password type loses the masking, so each digit shows, on screen and
 * to accessibility services. In a Material text field, a hidden secret must not reach accessibility
 * services in plain text either. The fields here are the platform's and Material's own, under
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SecretFieldsTest {

    private val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get().also { it.setTheme(R.style.Theme_Track) }

    private fun assertHides(field: EditText, typed: String) {
        field.setText(typed)
        assertTrue("masked on screen", field.transformationMethod is PasswordTransformationMethod)
        val node = field.createAccessibilityNodeInfo()
        assertTrue("a password to accessibility services", node.isPassword)
        assertNotEquals("never the typed text", typed, node.text?.toString())
        assertEquals("one line", 1, field.maxLines)
    }

    @Test
    fun `a PIN field hides each digit as typed, and takes no more than its digits`() {
        val pin = SecretFields.pin(activity, 4)
        assertHides(pin, "1357")
        assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, pin.inputType)
        pin.setText("135791")
        assertEquals(4, pin.text!!.length)
    }

    @Test
    fun `a password field hides it as typed, and is a password to the keyboard`() {
        val password = SecretFields.password(activity)
        assertHides(password, "dummy-pass-1")
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password.inputType)
    }

    @Test
    fun `a numeric password field set up without these shows its text - why these exist`() {
        val withoutThem = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        withoutThem.setText("1357")
        assertFalse(withoutThem.transformationMethod is PasswordTransformationMethod)
        assertEquals("1357", withoutThem.createAccessibilityNodeInfo().text?.toString())
    }

    @Test
    fun `Settings makes every PIN field and the deletion's password field with them`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertEquals("the four App Lock PIN fields, and the PIN asked before the lock is turned off", 5, Regex("""SecretFields\.pin\(requireContext\(\), 4\)""").findAll(settings).count())
        assertTrue(settings.contains("val passwordEditText = com.pingolin.track.util.SecretFields.password(requireContext())"))
        // No field is set single-line after its type: only the display name's is single-line.
        assertEquals(1, Regex("""setSingleLine\(true\)""").findAll(settings).count())
        assertTrue(settings.substringAfter("private fun showEditDisplayNameDialog()").substringBefore("\n    private fun ").contains("setSingleLine(true)"))
    }

    @Test
    fun `nowhere in the app is a field set single-line after its type, and every secret field has a password type`() {
        val main = File("src/main/java").walk().filter { it.extension == "kt" }.associate { it.name to it.readText() }
        // The display name's field is the app's one setSingleLine (in code, not in a comment).
        fun code(text: String) = text.lines().filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }.joinToString("\n")
        assertEquals(listOf("SettingsFragment.kt"), main.filter { (_, text) -> code(text).contains("setSingleLine(true)") }.keys.toList())
        // The secret fields made in code, each with its password type (or, for the two-factor code, a
        // visible one on purpose: a one-time code the person reads off another screen).
        val restore = main.getValue("SettingsFragment.kt").substringAfter("private fun promptForBackupPassphrase(").substringBefore("\n    private fun ")
        assertTrue(restore.contains("android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD"))
        assertTrue(main.getValue("PasswordInPlacePrompt.kt").contains("inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD"))
        assertTrue(main.getValue("AuthActivity.kt").substringAfter("private fun show2FADialog()").contains("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD"))
        // And in the layouts: the sign-in password, the export passphrase and the unlock PIN.
        val layouts = File("src/main/res/layout")
        assertTrue(File(layouts, "activity_auth.xml").readText().contains("android:id=\"@+id/etPassword\"\n                    android:layout_width=\"match_parent\"\n                    android:layout_height=\"wrap_content\"\n                    android:inputType=\"textPassword\""))
        assertTrue(File(layouts, "dialog_export_data.xml").readText().contains("android:id=\"@+id/exportPassphrase\"\n                android:layout_width=\"match_parent\"\n                android:layout_height=\"wrap_content\"\n                android:inputType=\"textPassword\""))
        assertTrue(File(layouts, "dialog_pin_input.xml").readText().contains("android:inputType=\"numberPassword\""))
    }

    // ===== what Material's field tells accessibility services =====

    /** A sign-in password field as activity_auth.xml makes it: in a TextInputLayout with the eye. */
    private fun materialPassword(protect: Boolean): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(activity).apply {
            hint = "Password"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val field = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(field)
        if (protect) SecretFields.keepHidden(layout)
        return layout to field
    }

    @Test
    fun `Material's own field hands accessibility services the typed text - why keepHidden exists`() {
        val (_, field) = materialPassword(protect = false)
        field.setText("dummy-pass-1")
        assertEquals("dummy-pass-1", field.createAccessibilityNodeInfo().text?.toString())
    }

    @Test
    fun `kept hidden, it gives dots while hidden, the text once the eye shows it, and keeps the rest of its node`() {
        val (_, field) = materialPassword(protect = true)
        field.setText("dummy-pass-1")
        val hidden = field.createAccessibilityNodeInfo()
        assertEquals("\u2022".repeat(12), hidden.text?.toString())
        assertTrue(hidden.isPassword)
        assertEquals("Material's hint stays", "Password", hidden.hintText?.toString())

        // The eye: what the screen shows is what the node says.
        field.transformationMethod = null
        assertEquals("dummy-pass-1", field.createAccessibilityNodeInfo().text?.toString())

        // Empty: Material's hint.
        field.transformationMethod = PasswordTransformationMethod.getInstance()
        field.setText("")
        assertFalse(field.createAccessibilityNodeInfo().text?.toString().orEmpty().contains('\u2022'))
    }

    @Test
    fun `every Material field with an eye keeps its secret hidden, in layouts and in code`() {
        val main = File("src/main/java").walk().filter { it.extension == "kt" }.associateWith { it.readText() }
        val all = main.values.joinToString("\n")
        // Each layout's eye: its TextInputLayout's id is handed to keepHidden.
        val layouts = File("src/main/res/layout").listFiles()!!.filter { it.readText().contains("app:endIconMode=\"password_toggle\"") }
        assertEquals(setOf("activity_auth.xml", "dialog_export_data.xml", "dialog_pin_input.xml"), layouts.map { it.name }.toSet())
        for (layout in layouts) {
            val xml = layout.readText()
            val block = xml.substring(xml.lastIndexOf("<com.google.android.material.textfield.TextInputLayout", xml.indexOf("app:endIconMode=\"password_toggle\"")))
            val id = Regex("""android:id="@\+id/(\w+)"""").find(block)!!.groupValues[1]
            assertTrue("${layout.name}: $id", Regex("""SecretFields\.keepHidden\([^)]*\b$id\b""").containsMatchIn(all))
        }
        // Each eye made in code: keepHidden on that layout, once its field has joined it (the layout
        // sets its own delegate then, over any set before).
        val inCode = main.filter { (_, text) -> text.contains("END_ICON_PASSWORD_TOGGLE") }
        assertEquals(setOf("SettingsFragment.kt", "PasswordInPlacePrompt.kt"), inCode.keys.map { it.name }.toSet())
        for ((file, text) in inCode) {
            for (at in Regex("END_ICON_PASSWORD_TOGGLE").findAll(text)) {
                val after = text.substring(at.range.last).lines().take(6).joinToString("\n")
                val kept = after.indexOf("com.pingolin.track.util.SecretFields.keepHidden(this)")
                assertTrue(file.name, kept > 0)
                assertTrue("${file.name}: after addView", after.indexOf("addView(") in 0 until kept)
            }
        }
    }
}
