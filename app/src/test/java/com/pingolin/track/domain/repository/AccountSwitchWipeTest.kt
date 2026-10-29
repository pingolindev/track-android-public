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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The account-switch wipe is load-bearing for forced-sign-out retention.
 *
 * SessionManager.triggerForceLogout deliberately KEEPS the stored user id, so
 * an account survives the server refusing one refresh and comes back on the
 * next sign-in. The price of keeping that id is that a DIFFERENT user signing
 * in on this device would otherwise land on the previous user's decrypted rows
 * and pinned keys. AuthRepository is what stops that: it compares the incoming
 * id against the stored one and runs clearLocalData() BEFORE importing the new
 * user's keys. That is the login path; registration happens in the account
 * portal, not in the app.
 *
 * Delete the check and nothing fails at runtime until two accounts share a
 * device, at which point the failure is silent and is a data leak. So this
 * asserts the check is present and correctly ordered.
 *
 * Source-level on purpose. Exercising the real branch means standing up SRP,
 * Argon2id, the native crypto manager and Room, and a test that heavy is one
 * nobody keeps working — whereas the invariant worth defending is small and
 * exactly expressible: a comparison, followed by a wipe, before the key
 * import. If this test ever needs relaxing, the retention in
 * SessionManager.clearCredentialsOnly has to be revisited in the same change.
 */
class AccountSwitchWipeTest {

    /**
     * Walks up from the working directory rather than assuming it: Gradle
     * running unit tests from the module dir is a default, not a guarantee, and
     * a test that silently scanned an empty tree would pass forever.
     */
    private fun findMainSourceSet(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main")
            if (File(candidate, "java/com/pingolin/track").isDirectory) return candidate
            val direct = File(dir, "src/main")
            if (File(direct, "java/com/pingolin/track").isDirectory) return direct
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate src/main from ${File("").absolutePath}. Fix this lookup " +
                "rather than deleting the test — an unfindable tree means the guard is " +
                "not guarding anything."
        )
    }

    private fun readSource(relative: String): String {
        val f = File(findMainSourceSet(), "java/com/pingolin/track/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private val source: String by lazy { readSource("domain/repository/AuthRepository.kt") }

    /**
     * Strip block and line comments. These files explain themselves at length,
     * and the explanations name the very calls being asserted about — a
     * comment reading "credentials only, NOT clearSession()" would otherwise
     * fail an assertion about which call the code makes.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Text between the first and second occurrence markers, or null. */
    private fun section(startMarker: String, endMarker: String): String? {
        val start = source.indexOf(startMarker)
        if (start < 0) return null
        val end = source.indexOf(endMarker, start)
        if (end < 0) return null
        return source.substring(start, end)
    }

    @Test
    fun `the sign-in path compares the incoming user id against the stored one`() {
        val comparisons = Regex("""priorUserId != null && priorUserId != [\w.]+""")
            .findAll(source).count()
        assertTrue(
            "expected the account-switch comparison on the login path, found " +
                "$comparisons. Forced-sign-out retention keeps the stored user id; " +
                "this comparison is the only thing that stops the next account " +
                "inheriting the previous user's data.",
            comparisons >= 1
        )
    }

    @Test
    fun `each comparison wipes rather than merely logging`() {
        // The branch guarded by the mismatch must call clearLocalData(). A
        // comparison that only logs is the failure mode this guards: it looks
        // handled on reading and leaks at runtime.
        val branches = Regex(
            """priorUserId != null && priorUserId != [\w.]+\) \{[^}]*clearLocalData\(\)""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(source).count()
        assertTrue(
            "expected the mismatch branch to call clearLocalData(), found $branches",
            branches >= 1
        )
    }

    @Test
    fun `the login path wipes BEFORE the new user's keys are imported`() {
        // Ordering matters: importPrivateKey installs the incoming user's keys
        // into the native crypto manager, and clearLocalData calls
        // cryptoManager.clearAllKeys(). Wiping after the import would erase the
        // key that was just installed and leave the new session unable to
        // decrypt anything.
        val loginBody = section("Account switch detected on login", "fun completeLogin")
        assertTrue("could not locate the login path in AuthRepository.kt", loginBody != null)

        val wipeAt = loginBody!!.indexOf("clearLocalData()")
        val importAt = loginBody.indexOf("importPrivateKey")
        assertTrue("no clearLocalData() on the login path", wipeAt >= 0)
        assertTrue("no importPrivateKey on the login path", importAt >= 0)
        assertTrue(
            "clearLocalData() must run BEFORE importPrivateKey — wiping afterwards " +
                "erases the key the new session just imported",
            wipeAt < importAt
        )
    }

    @Test
    fun `the forced sign-out keeps the account rather than wiping it`() {
        // The other half of the contract. If triggerForceLogout called
        // clearSession(), the stored user id would be nulled, the comparisons
        // above could never fire, and a device would be left dark against a
        // live session — so the two facts belong in one test file.
        val sm = readSource("util/SessionManager.kt")
        val body = codeOnly(
            sm.substringAfter("fun triggerForceLogout")
                .substringBefore("\n    fun clearCredentialsOnly")
        )
        assertTrue(
            "triggerForceLogout must call clearCredentialsOnly(), not clearSession()",
            body.contains("clearCredentialsOnly()")
        )
        assertTrue(
            "triggerForceLogout must NOT call clearSession() — that removes the user " +
                "id the account-switch comparison depends on",
            !body.contains("clearSession()")
        )
    }

    @Test
    fun `the wipe clears the keys through the owed wipe, and lowers the sync error's banner`() {
        // A delete the platform refuses leaves a wipe owed (OwedWipe), paid before the next load.
        // A bare clearAllKeys() loses that, and the banner outlives the account.
        val body = codeOnly(
            source.substringAfter("suspend fun clearLocalData()")
                .substringBefore("suspend fun getCurrentUser()")
        )
        assertTrue("clearLocalData() must clear through keyStoreRecovery.clearKeys()", body.contains("keyStoreRecovery.clearKeys()"))
        assertTrue("clearLocalData() must lower the banner", body.contains("keyStoreRecovery.signedOut()"))
        assertTrue("clearLocalData() must not clear the keys around the owed wipe", !body.contains("cryptoManager.clearAllKeys()"))
    }

    @Test
    fun `an erasure wipes every account's entries kept per account, after the local wipe`() {
        // As on iOS: an erased account's data, other people's user ids among it, never stays
        // on the phone. deleteAccount can't run on the JVM (clearLocalData).
        val body = codeOnly(source.substringAfter("suspend fun deleteAccount(").substringBefore("\n    suspend fun "))
        val wipe = body.indexOf("circleRepository.get().wipeAccountEntries()")
        assertTrue("deleteAccount must wipe the entries kept per account", wipe >= 0)
        assertTrue("after clearLocalData()", body.indexOf("clearLocalData()") in 0 until wipe)
    }
}
