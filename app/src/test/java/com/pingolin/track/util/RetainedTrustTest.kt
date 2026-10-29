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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.pingolin_mobile.CryptoManager

/**
 * "Verified" marks survive a sign-out of the same account and never vouch for anything
 * else.
 *
 * A mark stored as a bare (circle, member) flag would vouch for whatever key the next
 * login trusted on first sight, so it would have to be wiped at every sign-out. Bound
 * to the exact keys compared and namespaced by account, a mark can be kept: new keys
 * read as unverified, and another account never sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetainedTrustTest {

    private lateinit var context: Context
    private val original = MemberVerificationStore.prefsFor
    private val cryptoManager = mockk<CryptoManager>(relaxed = true).also {
        // A key store that loaded: its pins may be exported at a sign-out.
        every { it.keyStoreStatus() } returns uniffi.pingolin_mobile.KeyStoreStatus(uniffi.pingolin_mobile.KeyStoreLoad.LOADED, false, false)
    }

    private fun trust() = RetainedTrust(context, cryptoManager).also {
        it.pinBlobsFor = { ctx -> ctx.getSharedPreferences("pins-test", Context.MODE_PRIVATE) }
        it.emails = AccountEmails(context) { ctx -> ctx.getSharedPreferences("emails-test", Context.MODE_PRIVATE) }
    }

    private fun keptPins() = context.getSharedPreferences("pins-test", Context.MODE_PRIVATE)

    private val sharing = "a".repeat(64)
    private val signing = "b".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // EncryptedSharedPreferences needs the Android Keystore, which Robolectric lacks.
        MemberVerificationStore.prefsFor = { it.getSharedPreferences("verification-test", Context.MODE_PRIVATE) }
    }

    @After
    fun tearDown() {
        MemberVerificationStore.prefsFor = original
    }

    private fun verifiedAt(owner: String, sharingHex: String = sharing, signingHex: String = signing) =
        MemberVerificationStore.getVerifiedAt(context, owner, "circle-1", "bob", sharingHex, signingHex)

    @Test
    fun `a mark vouches for exactly the keys that were compared`() {
        MemberVerificationStore.setVerified(context, "alice", "circle-1", "bob", sharing, signing, 1_000L)

        assertEquals(1_000L, verifiedAt("alice"))
        assertEquals(1_000L, verifiedAt("alice", sharing.uppercase(), signing.uppercase()))
        // A key trusted later — after a sign-out, or substituted — is not verified.
        assertEquals(0L, verifiedAt("alice", sharingHex = "c".repeat(64)))
        assertEquals(0L, verifiedAt("alice", signingHex = "d".repeat(64)))
    }

    @Test
    fun `another account never sees the mark`() {
        MemberVerificationStore.setVerified(context, "alice", "circle-1", "bob", sharing, signing, 1_000L)

        assertEquals(0L, verifiedAt("mallory"))
    }

    @Test
    fun `the same account signing back in keeps its marks, another account signing in drops them`() {
        val trust = trust()
        MemberVerificationStore.setVerified(context, "alice", "circle-1", "bob", sharing, signing, 1_000L)

        trust.onSignIn("alice", "alice@x.test")
        assertEquals(1_000L, verifiedAt("alice"))

        trust.onSignIn("mallory", "mallory@x.test")
        assertEquals(0L, verifiedAt("alice"))
    }

    @Test
    fun `a deleted account's marks are forgotten, others kept`() {
        MemberVerificationStore.setVerified(context, "alice", "circle-1", "bob", sharing, signing, 1_000L)
        MemberVerificationStore.setVerified(context, "carol", "circle-1", "bob", sharing, signing, 2_000L)

        trust().forget("alice")

        assertEquals(0L, verifiedAt("alice"))
        assertEquals(2_000L, verifiedAt("carol"))
    }

    @Test
    fun `a mark cannot be set without both keys`() {
        MemberVerificationStore.setVerified(context, "alice", "circle-1", "bob", "", signing, 1_000L)
        assertTrue(verifiedAt("alice") == 0L)
    }

    // ---- member pins (SDK export/import) -----------------------------------------

    @Test
    fun `the same account gets its pins back at its next sign-in, once`() {
        every { cryptoManager.exportMemberPins("alice") } returns "alice-pins"
        val trust = trust()

        trust.onSignOut("alice")
        assertEquals("alice-pins", keptPins().getString("alice", null))

        trust.onSignIn("alice", "alice@x.test")
        verify { cryptoManager.importMemberPins("alice", "alice-pins") }
        // Imported into the SDK's store: the copy goes, so an older one can never
        // overwrite pins trusted after it.
        assertNull(keptPins().getString("alice", null))
    }

    @Test
    fun `another account's sign-in never imports and drops the kept pins`() {
        every { cryptoManager.exportMemberPins("alice") } returns "alice-pins"
        val trust = trust()
        trust.onSignIn("alice", "alice@x.test")
        trust.onSignOut("alice")

        trust.onSignIn("mallory", "mallory@x.test")

        verify(exactly = 0) { cryptoManager.importMemberPins(any(), any()) }
        assertNull(keptPins().getString("alice", null))
    }

    @Test
    fun `nothing is exported without a signed-in account`() {
        trust().onSignOut(null)
        trust().onSignOut("")
        verify(exactly = 0) { cryptoManager.exportMemberPins(any()) }
    }

    @Test
    fun `a deleted account's kept pins are forgotten`() {
        every { cryptoManager.exportMemberPins("alice") } returns "alice-pins"
        val trust = trust()
        trust.onSignOut("alice")

        trust.forget("alice")

        assertNull(keptPins().getString("alice", null))
    }

    // ---- key-access approvals ------------------------------------------------------

    private fun approvalsOf(owner: String): KeyAccessApprovals =
        SecurePrefsKeyAccessApprovals(context, { owner }, { it.getSharedPreferences("approvals-test", Context.MODE_PRIVATE) })

    private fun trustWithApprovals() = trust().also { trust -> trust.approvalsFor = { approvalsOf(it) } }

    @Test
    fun `the same account signing back in keeps its key-access approvals, another account signing in drops them`() {
        val trust = trustWithApprovals()
        approvalsOf("alice").approve("circle-1", listOf("alice", "bob"), KeyAccessApprovals.Origin.STATEMENT)
        approvalsOf("alice").recordStatementIds("circle-1", mapOf("bob" to listOf("s-1")))

        trust.onSignOut("alice")
        trust.onSignIn("alice", "alice@x.test")
        assertEquals(setOf("alice", "bob"), approvalsOf("alice").approved("circle-1"))
        // Tombstones are kept too: bob leaving after this is still recorded with his ids.
        approvalsOf("alice").retain("circle-1", listOf("alice"), activeVersion = 2)
        assertEquals(setOf("s-1"), approvalsOf("alice").tombstonedStatementIds("circle-1"))

        trust.onSignIn("mallory", "mallory@x.test")
        assertEquals(emptySet<String>(), approvalsOf("alice").approved("circle-1"))
        assertEquals(emptySet<String>(), approvalsOf("alice").tombstonedStatementIds("circle-1"))
    }

    @Test
    fun `a deleted account's key-access approvals are forgotten, and every other account's with them`() {
        // An erased account's data never stays on the phone, as on iOS.
        approvalsOf("alice").approve("circle-1", listOf("alice", "bob"), KeyAccessApprovals.Origin.OWN)
        approvalsOf("carol").approve("circle-1", listOf("carol", "bob"), KeyAccessApprovals.Origin.OWN)

        trustWithApprovals().forget("alice")

        assertEquals(emptySet<String>(), approvalsOf("alice").approved("circle-1"))
        assertEquals(emptySet<String>(), approvalsOf("carol").approved("circle-1"))
    }

    // ---- a key store that didn't load ----------------------------------------------

    @Test
    fun `pins kept at sign-out survive a sign-in that clears a key store that doesn't decode`() {
        // KeyStoreRecovery clears a key store that still doesn't decode when a sign-in has just
        // opened the account's keys. The pins exported at sign-out are in a file of their own, so
        // the clear doesn't reach them, and the sign-in restores them.
        every { cryptoManager.exportMemberPins("alice") } returns "alice-pins"
        val trust = trust()
        trust.onSignOut("alice")

        val keyPrefs = com.pingolin.track.testutil.InMemoryPrefs()
        val keyStorage = KeyStorageCallbackImpl(keyPrefs)
        keyStorage.storeKeys(uniffi.pingolin_mobile.StoredKeyData("", "{}", """{"c-1:bob":"pin"}""", "seed"))
        var load = uniffi.pingolin_mobile.KeyStoreLoad.DOES_NOT_DECODE
        every { cryptoManager.keyStoreStatus() } answers { uniffi.pingolin_mobile.KeyStoreStatus(load, false, false) }
        every { cryptoManager.clearAllKeys() } answers { keyStorage.deleteKeys(); load = uniffi.pingolin_mobile.KeyStoreLoad.LOADED }
        val owedWipe = OwedWipe(context).also { it.mark = { java.io.File(context.cacheDir, "owed-test") } }

        // The order finishSignIn keeps (TwoFactorSignInTest): recovery before the keys are
        // installed, RetainedTrust.onSignIn after.
        assertEquals(
            KeyStoreRecovery.BeforeImport.IMPORT_KEYS,
            KeyStoreRecovery(cryptoManager, owedWipe, context).beforeSignInImport(),
        )
        trust.onSignIn("alice", "alice@x.test")

        assertTrue("the key store that didn't decode was cleared", keyPrefs.data.isEmpty())
        verify { cryptoManager.importMemberPins("alice", "alice-pins") }
    }

    @Test
    fun `no pins are exported from a key store that didn't load - the ones kept at the last sign-out stay`() {
        every { cryptoManager.exportMemberPins("alice") } returns "alice-pins"
        val trust = trust()
        trust.onSignOut("alice")

        for (load in listOf(uniffi.pingolin_mobile.KeyStoreLoad.READ_FAILED, uniffi.pingolin_mobile.KeyStoreLoad.DOES_NOT_DECODE)) {
            every { cryptoManager.keyStoreStatus() } returns uniffi.pingolin_mobile.KeyStoreStatus(load, false, false)
            every { cryptoManager.exportMemberPins("alice") } returns "partial-pins"
            trust.onSignOut("alice")
            assertEquals(load.name, "alice-pins", keptPins().getString("alice", null))
        }
    }

    // ---- what holds trust across accounts (the typed-email guard) ---------------------------

    private val ann = "a11ce000-0000-4000-8000-00000000000a"
    private val ben = "b0b00000-0000-4000-8000-00000000000b"

    private fun clearAll() {
        for (name in listOf("pins-test", "emails-test", "approvals-test", "verification-test")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun `another account's approvals and kept pins go only when the email typed isn't theirs`() {
        every { cryptoManager.exportMemberPins(any()) } answers { "${firstArg<String>()}-pins" }
        for ((case, typed, gone) in listOf(
            Triple("another email typed", "ben@x.test", true),
            Triple("ann's email typed, the server naming another account", " ANN@x.test", false),
            Triple("no email typed", null, false),
        )) {
            clearAll()
            val trust = trustWithApprovals()
            trust.onSignIn(ann, "ann@x.test")
            approvalsOf(ann).approve("circle-1", listOf(ann, "bob"), KeyAccessApprovals.Origin.OWN)
            trust.onSignOut(ann)

            trust.onSignIn(ben, typed)

            assertEquals(case, if (gone) emptySet() else setOf(ann, "bob"), approvalsOf(ann).approved("circle-1"))
            assertEquals(case, if (gone) null else "$ann-pins", keptPins().getString(ann, null))
        }
    }

    @Test
    fun `an account with no email saved here keeps its approvals and kept pins at another account's sign-in`() {
        // An older store keeps ann's entries with no email saved.
        every { cryptoManager.exportMemberPins(ann) } returns "ann-pins"
        val trust = trustWithApprovals()
        approvalsOf(ann).approve("circle-1", listOf(ann, "bob"), KeyAccessApprovals.Origin.OWN)
        trust.onSignOut(ann)

        trust.onSignIn(ben, "ben@x.test")

        assertEquals(setOf(ann, "bob"), approvalsOf(ann).approved("circle-1"))
        assertEquals("ann-pins", keptPins().getString(ann, null))
    }

    private fun pinJson(sharing: String, signing: String, first: Long, verified: Long, approved: Long? = null) =
        """{"sharing_pubkey_hex":"${sharing.repeat(32)}","signing_pubkey_hex":"${signing.repeat(32)}","first_pinned_at":$first,"last_verified_at":$verified,"user_approved_change_at":${approved ?: "null"}}"""

    private fun blob(owner: String, pins: Map<String, String>, identity: Map<String, String> = emptyMap()) =
        """{"format":"f1","owner_user_id":"$owner","pins":{${pins.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},""" +
            """"identity_pins":{${identity.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""

    @Test
    fun `pins kept under two spellings of one account go in as one blob - equal keys one pin, different keys the pin that reads as changed`() {
        val upper = ann.uppercase()
        keptPins().edit()
            .putString(upper, blob(upper, mapOf("c-1:bob" to pinJson("aa", "bb", 100, 200, 150), "c-1:cy" to pinJson("cc", "dd", 100, 200)), mapOf("bob" to pinJson("aa", "bb", 90, 200))))
            .putString(ann, blob(ann, mapOf("c-1:bob" to pinJson("aa", "bb", 50, 300, 120), "c-1:cy" to pinJson("ee", "ff", 80, 400, 390))))
            .commit()
        val imported = io.mockk.slot<String>()
        every { cryptoManager.importMemberPins(ann, capture(imported)) } returns 3u

        trust().onSignIn(ann, "ann@x.test")

        verify(exactly = 1) { cryptoManager.importMemberPins(any(), any()) }
        val merged = com.google.gson.JsonParser.parseString(imported.captured).asJsonObject
        assertEquals(ann, merged.get("owner_user_id").asString)
        val bob = merged.getAsJsonObject("pins").getAsJsonObject("c-1:bob")
        assertEquals("aa".repeat(32), bob.get("sharing_pubkey_hex").asString)
        assertEquals(50L, bob.get("first_pinned_at").asLong)
        assertEquals(300L, bob.get("last_verified_at").asLong)
        assertEquals(150L, bob.get("user_approved_change_at").asLong)
        val cy = merged.getAsJsonObject("pins").getAsJsonObject("c-1:cy")
        assertEquals(MemberPinBlobs.CHANGED_UNTIL_APPROVED, cy.get("sharing_pubkey_hex").asString)
        assertEquals(MemberPinBlobs.CHANGED_UNTIL_APPROVED, cy.get("signing_pubkey_hex").asString)
        assertEquals(80L, cy.get("first_pinned_at").asLong)
        assertEquals(400L, cy.get("last_verified_at").asLong)
        assertTrue(cy.get("user_approved_change_at").isJsonNull)
        assertEquals("aa".repeat(32), merged.getAsJsonObject("identity_pins").getAsJsonObject("bob").get("sharing_pubkey_hex").asString)
        assertNull(keptPins().getString(upper, null))
        assertNull(keptPins().getString(ann, null))
    }

    @Test
    fun `pins kept under one other spelling of the account come back as they are`() {
        val upper = ann.uppercase()
        keptPins().edit().putString(upper, "ann-pins").commit()

        trust().onSignIn(ann, "ann@x.test")

        verify { cryptoManager.importMemberPins(upper, "ann-pins") }
        assertNull(keptPins().getString(upper, null))
    }

    @Test
    fun `an erasure leaves no account's kept pins or emails`() {
        every { cryptoManager.exportMemberPins(any()) } answers { "${firstArg<String>()}-pins" }
        val trust = trust()
        trust.onSignIn(ann, "ann@x.test")
        trust.onSignOut(ann)
        keptPins().edit().putString(ben, "ben-pins").commit()

        trust.forget(ann)

        assertTrue(keptPins().all.isEmpty())
        assertTrue(context.getSharedPreferences("emails-test", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun `another account's email goes with its guarded entries, and stays where they stay`() {
        for ((typed, gone) in listOf("ben@x.test" to true, " ANN@x.test" to false, null to false)) {
            clearAll()
            val trust = trust()
            trust.onSignIn(ann, "ann@x.test")
            trust.onSignOut(ann)

            trust.onSignIn(ben, typed)

            assertEquals("$typed", if (gone) null else "ann@x.test", trust.emails.of(ann))
            assertEquals("$typed", typed?.trim()?.lowercase(), trust.emails.of(ben))
        }
    }

    @Test
    fun `verified marks under another spelling stay at the account's sign-in before anything reads them, and go at its erasure`() {
        for (spelling in listOf(ann.uppercase(), ann.replace("-", ""))) {
            clearAll()
            MemberVerificationStore.prefsFor = { it.getSharedPreferences("verification-test", Context.MODE_PRIVATE) }
            MemberVerificationStore.setVerified(context, spelling, "circle-1", "bob", sharing, signing, 1_000L)

            trust().onSignIn(ann, "ann@x.test")

            assertEquals(spelling, 1_000L, verifiedAt(ann))

            MemberVerificationStore.prefsFor = { it.getSharedPreferences("verification-test", Context.MODE_PRIVATE) }
            MemberVerificationStore.setVerified(context, spelling, "circle-2", "bob", sharing, signing, 2_000L)
            trust().forget(ann)

            assertEquals(spelling, emptyMap<String, Any?>(), context.getSharedPreferences("verification-test", Context.MODE_PRIVATE).all)
        }
    }

    @Test
    fun `verified marks kept under another spelling of the account read under its canonical id, and stay at its sign-in`() {
        MemberVerificationStore.setVerified(context, ann.uppercase(), "circle-1", "bob", sharing, signing, 1_000L)

        assertEquals(1_000L, verifiedAt(ann))
        trust().onSignIn(ann, "ann@x.test")
        assertEquals(1_000L, verifiedAt(ann))
        assertTrue(context.getSharedPreferences("verification-test", Context.MODE_PRIVATE).all.keys.all { it.startsWith("$ann|") })
    }
}
