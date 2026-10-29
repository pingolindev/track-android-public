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

package com.pingolin.track.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * No text a user reads talks about keys — nobody knows what that means — and nothing is
 * called SOS (it is "Notify my circle"). A tripwire over every string resource, so the
 * next piece of copy that says "circle key" fails here instead of in front of someone.
 *
 * Product nouns people hold are fine: "recovery key" (they saved it) and "passkey".
 */
class NoKeyTalkInCopyTest {

    private val banned = Regex(
        """\b(keys?|wrap\w*|pinned|pins?|fingerprints?|safety numbers?|re-?key\w*|rotat\w*)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Kept on purpose, each with its reason. */
    private val allowed = mapOf(
        // The app lock's own 4-digit code and the phone's biometrics: not encryption.
        "app_lock_pin" to "app lock", "app_lock_pin_description" to "app lock",
        "app_lock_biometrics_description" to "app lock", "app_lock_enter_pin" to "app lock",
        "app_lock_confirm_pin" to "app lock", "app_lock_pin_mismatch" to "app lock",
        "app_lock_pin_set" to "app lock", "app_lock_pin_removed" to "app lock",
        // The locked screen's PIN field (dialog_pin_input): the same 4-digit code.
        "app_lock_pin_hint" to "app lock",
        // App Lock's words for the phone's fingerprint or face: not encryption.
        "app_lock_no_hardware" to "app lock", "app_lock_none_enrolled" to "app lock",
        "app_lock_hardware_unavailable" to "app lock",
        // A lock nothing can check asks for a PIN for it.
        "app_lock_mend_title" to "app lock", "app_lock_mend_body" to "app lock", "app_lock_mend_set_pin" to "app lock",
        // The lock asked for before it is turned off or weakened.
        "app_lock_prove_use_pin" to "app lock", "app_lock_prove_pin_title" to "app lock",
        "app_lock_prove_off_pin_body" to "app lock", "app_lock_prove_change_pin_body" to "app lock",
        "app_lock_prove_add_biometrics_pin_body" to "app lock", "app_lock_prove_auto_lock_pin_body" to "app lock",
        // (The location notice's fallback is not a string: it is the served
        // legal text itself, byte for byte, in res/raw — the legal copy, not
        // the app's.)
    )

    private fun copy(): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/values/strings.xml"))
        val out = mutableListOf<Pair<String, String>>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> out += name to node.textContent
                "plurals", "string-array" -> {
                    val items = node.getElementsByTagName("item")
                    for (j in 0 until items.length) out += name to items.item(j).textContent
                }
            }
        }
        return out
    }

    @Test
    fun `no string resource talks about keys`() {
        val strings = copy()
        assertTrue("strings.xml not read", strings.size > 100)
        val offenders = strings
            .filter { (name, _) -> name !in allowed }
            .filter { (_, text) ->
                banned.containsMatchIn(text.replace(Regex("recovery key|passkeys?", RegexOption.IGNORE_CASE), ""))
            }
        assertTrue("key talk in: ${offenders.joinToString { "${it.first}: ${it.second.take(80)}" }}", offenders.isEmpty())
    }

    @Test
    fun `nothing is called SOS`() {
        val offenders = copy().filter { (_, text) -> Regex("""\bSOS\b""").containsMatchIn(text) }
        assertTrue("SOS in: ${offenders.joinToString { it.first }}", offenders.isEmpty())
    }
}
