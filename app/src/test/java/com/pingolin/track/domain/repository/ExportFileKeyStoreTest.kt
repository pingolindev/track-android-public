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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CheckInExport
import com.pingolin.track.data.remote.dto.CircleMembershipExport
import com.pingolin.track.data.remote.dto.DataExportResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.ExportMetadata
import com.pingolin.track.data.remote.dto.LocationExport
import com.pingolin.track.data.remote.dto.PlaceExport
import com.pingolin.track.util.KeyStorageCallbackImpl
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * The export never writes this device's key store.
 *
 * The export's circle rows carry this account's key wraps with no signer, so every one of them is
 * the server's word, and none may go into the LIVE store as a first contact. A circle held here
 * reads with this device's keys only, the wraps of the rest are opened in the file's own
 * throwaway store, and a row only that store opened is marked `"key_unverified": true`, which no
 * restore makes real.
 *
 * Both managers are STRICT mocks, and every test that makes the file's store closes with
 * confirmVerified on it: the store gets this account's key, the file's wraps and reads, and it is
 * closed. No storage, pins, identities or signing key, ever.
 */
class ExportFileKeyStoreTest {

    private val held = "a1111111-1111-4111-8111-111111111111"
    private val fileOnly = "b2222222-2222-4222-8222-222222222222"
    private val self = "00000000-0000-0000-0000-000000000000"

    private val live: CryptoManager = mockk()
    private val file: CryptoManager = mockk()
    private val keyStorage: KeyStorageCallbackImpl = mockk()
    private val liveHolds = mutableSetOf(held, self)
    private var fileStoresMade = 0

    private lateinit var page: DataExportResponse
    private lateinit var repo: ExportRepository

    /** A TrackApi that answers the export with [page], once, and nothing else. */
    private fun exportApi(): TrackApi =
        java.lang.reflect.Proxy.newProxyInstance(TrackApi::class.java.classLoader, arrayOf(TrackApi::class.java)) { proxy, method, args ->
            when (method.name) {
                "exportData" -> Response.success(ApiResponse(success = true, data = page, error = null))
                "toString" -> "exportApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw AssertionError("the export asked TrackApi.${method.name}")
            }
        } as TrackApi

    @Before
    fun setUp() {
        every { live.hasCircleKey(any()) } answers { firstArg<String>() in liveHolds }
        // Stubbed so that a build calling it passes silently, and the verify below says so.
        every { live.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        every { keyStorage.sharingPrivateKey() } returns "account-sharing-key"
        every { file.importPrivateKey("account-sharing-key") } just Runs
        every { file.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        every { file.close() } just Runs
        repo = ExportRepository(exportApi(), live, keyStorage).also {
            it.newFileKeyStore = { fileStoresMade++; file }
        }
    }

    // ---- fixtures ----

    private fun sealed(tag: String) = EncryptedPayload(nonce = "n-$tag", ciphertext = "c-$tag")
    private fun io.mockk.MockKMatcherScope.sealedAs(tag: String) = match<EncryptedData> { it.nonce == "n-$tag" }
    private fun point(lat: Double, lng: Double) =
        Location(lat, lng, null, null, null, 5.0, 1_790_000_000_000, null, null)

    private fun circle(id: String, tag: String = id) = CircleMembershipExport(
        circleId = id, encryptedName = sealed("name-$tag"), role = "member",
        encryptedCircleKey = EncryptedPayload(nonce = "wn-$tag", ciphertext = "wc-$tag"),
        ephemeralPublicKey = "eph-$tag", keyVersion = 3, joinedDayBucket = 100
    )

    private fun place(id: String, circleId: String?, wrapped: Boolean = false) = PlaceExport(
        id = id, circleId = circleId, encryptedName = sealed("$id-name"), encryptedLocation = sealed("$id-loc"),
        radiusMeters = 150, createdDayBucket = 100,
        wrappedKey = if (wrapped) "wk-$id" else null, wrappedKeyNonce = if (wrapped) "wkn-$id" else null
    )

    private fun checkIn(id: String, circleId: String?, withLocation: Boolean = false) = CheckInExport(
        id = id, circleId = circleId, checkInType = "general", encryptedMessage = sealed("$id-msg"),
        encryptedLocation = if (withLocation) sealed("$id-loc") else null, createdDayBucket = 100
    )

    private fun location(tag: String, circleId: String) = LocationExport(
        deviceId = "d-$tag", circleId = circleId, dayBucket = 100, encryptedTimestamp = null,
        encryptedPayload = sealed("$tag-loc"), keyId = "k-$tag", insertedAtMs = 1_790_000_000_000, rowKey = "r-$tag"
    )

    private fun serve(
        circles: List<CircleMembershipExport>? = null,
        places: List<PlaceExport>? = null,
        checkIns: List<CheckInExport>? = null,
        locations: List<LocationExport>? = null,
    ) {
        page = DataExportResponse(
            metadata = ExportMetadata(
                userId = "u", exportedDayBucket = 100, startDayBucket = 70, endDayBucket = 100,
                formatVersion = "1.8", locationCount = locations?.size ?: 0, isComplete = true
            ),
            devices = null, circles = circles, places = places, checkIns = checkIns, locations = locations
        )
    }

    private suspend fun backup(): Pair<DecryptedDataExport, JsonObject> {
        val built = repo.exportAndBuildBackup(generatedAt = "2026-09-29T00:00:00Z").getOrThrow()
        return built.decrypted to JsonParser.parseString(built.fileBody).asJsonObject
    }

    private fun JsonObject.rows(section: String) = getAsJsonArray(section).map { it.asJsonObject }
    private fun JsonObject.markedTrue() =
        get("key_unverified")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true

    private fun verifyNothingWentLive() {
        verify(exactly = 0) { live.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ---- a circle with no key here ----

    @Test
    fun `a circle with no key here is opened in the file's own store only, and its row is marked`() = runTest {
        serve(circles = listOf(circle(fileOnly)))
        every { file.decryptDataV2(fileOnly, sealedAs("name-$fileOnly"), "name") } returns "Planted"

        val (decrypted, json) = backup()

        verifyNothingWentLive()
        assertEquals(1, fileStoresMade)
        verify(exactly = 1) { keyStorage.sharingPrivateKey() }
        confirmVerified(keyStorage)
        verify(exactly = 1) { file.importPrivateKey("account-sharing-key") }
        verify(exactly = 1) { file.storeCircleKey(fileOnly, "wn-${fileOnly}wc-$fileOnly", "eph-$fileOnly", 3, "", "", null, null) }
        verify(exactly = 1) { file.decryptDataV2(fileOnly, any(), "name") }
        verify(exactly = 1) { file.close() }
        confirmVerified(file)

        val row = decrypted.circles!!.single()
        assertEquals("Planted", row.name)
        assertTrue("read only with the file's key: marked", row.keyUnverified)
        assertTrue(json.rows("circles").single().markedTrue())
    }

    @Test
    fun `a wrap the file's store refuses leaves that circle unread, and the export goes on`() = runTest {
        serve(circles = listOf(circle(fileOnly), circle(held)), locations = listOf(location("l-file", fileOnly)))
        every { file.storeCircleKey(fileOnly, any(), any(), any(), any(), any(), any(), any()) } throws
            uniffi.pingolin_mobile.MobileException.DecryptionFailed("not this account's")
        // Were the circle read anyway, the file's store would answer.
        every { file.decryptDataV2(fileOnly, any(), "name") } returns "Planted"
        every { file.decryptLocation(fileOnly, any()) } returns point(1.0, 2.0)
        every { live.decryptDataV2(held, any(), "name") } returns "Family"

        val (decrypted, _) = backup()

        assertEquals(listOf(held), decrypted.circles!!.map { it.circleId })
        assertTrue(decrypted.locations!!.isEmpty())
        verify(exactly = 0) { file.decryptDataV2(any(), any(), any()) }
        verify(exactly = 0) { file.decryptLocation(any(), any()) }
        verify(exactly = 1) { file.close() }
        verifyNothingWentLive()
    }

    // ---- every circle held ----

    @Test
    fun `when every circle is held here, no file store is made and this account's key is never read`() = runTest {
        serve(circles = listOf(circle(held)), locations = listOf(location("l-held", held)))
        every { live.decryptDataV2(held, sealedAs("name-$held"), "name") } returns "Family"
        every { live.decryptLocation(held, sealedAs("l-held-loc")) } returns point(51.5, -0.12)

        val (decrypted, json) = backup()

        assertEquals(0, fileStoresMade)
        confirmVerified(keyStorage)
        verifyNothingWentLive()
        assertEquals("Family", decrypted.circles!!.single().name)
        assertFalse(decrypted.circles!!.single().keyUnverified)
        assertFalse(decrypted.locations!!.single().keyUnverified)
        assertFalse(json.rows("circles").single().has("key_unverified"))
        assertFalse(json.rows("locations").single().has("key_unverified"))
    }

    @Test
    fun `a held circle's rows read with this device's keys even when the file's store holds the circle too`() = runTest {
        // The file-only circle makes the store; the held circle's planted wrap still goes nowhere.
        serve(circles = listOf(circle(held), circle(fileOnly)), locations = listOf(location("l-held", held)))
        every { live.decryptDataV2(held, any(), "name") } returns "Family"
        every { file.decryptDataV2(fileOnly, any(), "name") } returns "Planted"
        every { live.decryptLocation(held, sealedAs("l-held-loc")) } returns point(51.5, -0.12)

        val (decrypted, _) = backup()

        verify(exactly = 0) { file.storeCircleKey(held, any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { file.decryptDataV2(held, any(), any()) }
        verify(exactly = 0) { file.decryptLocation(held, any()) }
        assertEquals(listOf("Family" to false, "Planted" to true), decrypted.circles!!.map { it.name to it.keyUnverified })
        assertFalse(decrypted.locations!!.single().keyUnverified)
    }

    // ---- spellings ----

    @Test
    fun `another spelling of a held circle reads with this device's keys, and its planted wrap is never opened`() = runTest {
        val spellings = listOf(
            held.uppercase(),
            held.replace("-", ""),
            "{$held}",
            "urn:uuid:$held",
        )
        serve(
            circles = spellings.mapIndexed { i, s -> circle(s, tag = "s$i") },
            places = listOf(place("p-upper", held.uppercase())),
            checkIns = listOf(checkIn("k-upper", held.uppercase())),
            locations = listOf(location("l-upper", held.uppercase()))
        )
        every { live.decryptDataV2(held, any(), "name") } returns "Family"
        every { live.decryptLocation(held, any()) } returns point(51.5, -0.12)
        every { live.decryptDataV2(held, sealedAs("k-upper-msg"), "message") } returns "home"

        val (decrypted, json) = backup()

        assertEquals("no file store for a circle held here", 0, fileStoresMade)
        confirmVerified(keyStorage)
        verifyNothingWentLive()
        assertEquals(List(4) { held }, decrypted.circles!!.map { it.circleId })
        assertEquals(List(4) { "Family" }, decrypted.circles!!.map { it.name })
        assertEquals("the file names it by its one spelling", List(4) { held }, json.rows("circles").map { it.get("circle_id").asString })
        assertEquals("Family", decrypted.places!!.single().name)
        assertEquals("home", decrypted.checkIns!!.single().message)
        assertEquals(51.5, decrypted.locations!!.single().latitude, 0.0)
        // The file names each row's circle in its one spelling too.
        for (section in listOf("places", "check_ins", "locations")) {
            assertEquals(section, held, json.rows(section).single().get("circle_id").asString)
        }
        assertFalse(decrypted.places!!.single().keyUnverified || decrypted.checkIns!!.single().keyUnverified ||
            decrypted.locations!!.single().keyUnverified || decrypted.circles!!.any { it.keyUnverified })
    }

    @Test
    fun `a place or check-in naming no circle a circle may have is left out of the file, and one naming none stays personal`() = runTest {
        serve(
            places = listOf(place("p-junk", "circle-a", wrapped = true), place("p-own", null, wrapped = true)),
            checkIns = listOf(checkIn("k-junk", "circle-a"), checkIn("k-own", null)),
        )
        every { live.unwrapPlaceKey(self, any(), any()) } returns "place-key"
        every { live.decryptWithPlaceKeyV2("place-key", any(), "name") } returns "Home"
        every { live.decryptWithPlaceKeyV2("place-key", any(), "location") } returns """{"latitude":3.0,"longitude":4.0}"""
        every { live.decryptDataV2(self, sealedAs("k-own-msg"), "message") } returns "fine"

        val (decrypted, json) = backup()

        assertEquals(listOf("p-own"), decrypted.places!!.map { it.id })
        assertEquals(listOf("k-own"), decrypted.checkIns!!.map { it.id })
        // No circle: the file body names none (Gson leaves a null out of the written text).
        assertTrue(json.rows("places").single().get("circle_id")?.isJsonNull ?: true)
        assertTrue(json.rows("check_ins").single().get("circle_id")?.isJsonNull ?: true)
    }

    // ---- rows that name no circle ----

    @Test
    fun `circle rows naming the personal key's slot or no UUID are left out, and their wraps opened nowhere`() = runTest {
        serve(circles = listOf(
            circle(self, tag = "nil"),
            circle(self.replace("-", ""), tag = "plain"),
            circle("{$self}", tag = "braced"),
            circle("urn:uuid:$self", tag = "urn"),
            circle("self", tag = "self"),
            circle("SELF", tag = "SELF"),
            circle("not-a-uuid", tag = "junk"),
        ))
        // Were a slot row kept, this device's own personal key would read it.
        every { live.decryptDataV2(self, any(), "name") } returns "Me"

        val (decrypted, json) = backup()

        assertTrue(decrypted.circles!!.isEmpty())
        assertEquals(0, json.rows("circles").size)
        assertEquals(0, fileStoresMade)
        confirmVerified(keyStorage)
        verifyNothingWentLive()
    }

    // ---- places ----

    @Test
    fun `a circle place of a circle with no key here is read by the file's store and marked, and the rest are not`() = runTest {
        serve(
            circles = listOf(circle(fileOnly)),
            places = listOf(
                place("p-file", fileOnly),
                place("p-wrapped", fileOnly, wrapped = true),
                place("p-held", held),
            )
        )
        every { file.decryptDataV2(fileOnly, any(), "name") } returns "Planted"
        every { file.decryptLocation(fileOnly, sealedAs("p-file-loc")) } returns point(1.0, 2.0)
        every { live.unwrapPlaceKey(self, "wk-p-wrapped", "wkn-p-wrapped") } returns "place-key"
        every { live.decryptWithPlaceKeyV2("place-key", sealedAs("p-wrapped-name"), "name") } returns "Home"
        every { live.decryptWithPlaceKeyV2("place-key", sealedAs("p-wrapped-loc"), "location") } returns """{"latitude":3.0,"longitude":4.0}"""
        every { live.decryptDataV2(held, sealedAs("p-held-name"), "name") } returns "School"
        every { live.decryptLocation(held, sealedAs("p-held-loc")) } returns point(5.0, 6.0)

        val (decrypted, json) = backup()

        assertEquals(
            listOf(Triple("p-file", "Planted", true), Triple("p-wrapped", "Home", false), Triple("p-held", "School", false)),
            decrypted.places!!.map { Triple(it.id, it.name, it.keyUnverified) }
        )
        assertEquals(listOf(true, false, false), json.rows("places").map { it.markedTrue() })
        assertEquals(listOf(false, true, true), json.rows("places").map { !it.has("key_unverified") })
        verifyNothingWentLive()
    }

    // ---- check-ins and locations ----

    @Test
    fun `check-ins and locations of a circle with no key here are read by the file's store and marked`() = runTest {
        serve(
            circles = listOf(circle(fileOnly)),
            checkIns = listOf(
                checkIn("k-file", fileOnly),
                checkIn("k-file-unread", fileOnly),
                checkIn("k-held", held, withLocation = true),
            ),
            locations = listOf(location("l-file", fileOnly), location("l-held", held))
        )
        every { file.decryptDataV2(fileOnly, sealedAs("name-$fileOnly"), "name") } returns "Planted"
        every { file.decryptDataV2(fileOnly, sealedAs("k-file-msg"), "message") } returns "planted message"
        every { file.decryptDataV2(fileOnly, sealedAs("k-file-unread-msg"), "message") } throws
            uniffi.pingolin_mobile.MobileException.DecryptionFailed("no key opens it")
        every { file.decryptLocation(fileOnly, sealedAs("l-file-loc")) } returns point(1.0, 2.0)
        every { live.decryptDataV2(held, sealedAs("k-held-msg"), "message") } returns "home"
        every { live.decryptLocation(held, sealedAs("k-held-loc")) } returns point(5.0, 6.0)
        every { live.decryptLocation(held, sealedAs("l-held-loc")) } returns point(7.0, 8.0)

        val (decrypted, json) = backup()

        assertEquals(
            listOf(Triple("k-file", "planted message", true), Triple("k-file-unread", null, false), Triple("k-held", "home", false)),
            decrypted.checkIns!!.map { Triple(it.id, it.message, it.keyUnverified) }
        )
        assertEquals(listOf(true, false), decrypted.locations!!.map { it.keyUnverified })
        assertEquals(listOf(true, false, false), json.rows("check_ins").map { it.markedTrue() })
        assertEquals(listOf(true, false), json.rows("locations").map { it.markedTrue() })
        assertFalse(json.rows("check_ins")[1].has("key_unverified"))
        assertFalse(json.rows("locations")[1].has("key_unverified"))
        verifyNothingWentLive()
    }

    // ---- the file's store is closed whatever happens ----

    @Test
    fun `the file's store is closed when a read throws past every catch`() = runTest {
        serve(circles = listOf(circle(fileOnly)), locations = listOf(location("l-file", fileOnly)))
        every { file.decryptDataV2(fileOnly, any(), "name") } returns "Planted"
        every { file.decryptLocation(fileOnly, any()) } throws AssertionError("not an Exception")

        val result = repo.exportAndBuildBackup(generatedAt = "2026-09-29T00:00:00Z")

        assertTrue(result.isFailure)
        verify(exactly = 1) { file.close() }
    }

    // ---- this account's key can't be read ----

    @Test
    fun `with no readable key the file's rows stay unread, the export still succeeds, and the store is closed`() = runTest {
        every { keyStorage.sharingPrivateKey() } returns null
        serve(circles = listOf(circle(held), circle(fileOnly)), locations = listOf(location("l-file", fileOnly)))
        every { live.decryptDataV2(held, any(), "name") } returns "Family"

        val (decrypted, _) = backup()

        assertEquals(listOf(held), decrypted.circles!!.map { it.circleId })
        assertTrue(decrypted.locations!!.isEmpty())
        verify(exactly = 1) { file.close() }
        confirmVerified(file)
        verifyNothingWentLive()
    }

    @Test
    fun `a key the file's store refuses leaves the file's rows unread, and the store is closed`() = runTest {
        every { file.importPrivateKey(any()) } throws uniffi.pingolin_mobile.MobileException.InvalidKey("not a key")
        serve(circles = listOf(circle(held), circle(fileOnly)))
        every { live.decryptDataV2(held, any(), "name") } returns "Family"

        val (decrypted, _) = backup()

        assertEquals(listOf(held), decrypted.circles!!.map { it.circleId })
        verify(exactly = 1) { file.importPrivateKey("account-sharing-key") }
        verify(exactly = 1) { file.close() }
        confirmVerified(file)
        verifyNothingWentLive()
    }

    // ---- the source ----

    @Test
    fun `the export's source never writes the live store and makes the file's store in one place, closed in a finally`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/ExportRepository.kt").readText()
        assertFalse(src.contains("cryptoManager.storeCircleKey("))
        for (call in listOf("registerSecureStorage", "loadPersistedKeys", "restoreState", "persistKeys", "importSigningPrivateKey", "importPreviousIdentities", "importMemberPins")) {
            assertFalse("the export calls $call", src.contains(call))
        }
        assertEquals("CryptoManager() is the seam's default only", 1, Regex("""CryptoManager\(\)""").findAll(src).count())
        assertEquals("the file's store is made in one place", 1, Regex("""newFileKeyStore\(\)""").findAll(src).count())
        val body = src.substringAfter("private fun decryptExport(").substringBefore("\n    }\n")
        assertTrue("made inside decryptExport", body.contains("newFileKeyStore()"))
        assertTrue("closed in its finally", body.substringAfter("} finally {").contains("fileStore?.close()"))
    }

    // ---- what the file is ----

    @Test
    fun `the file body is the export as this device read it, in the clear`() = runTest {
        // What Settings and the export dialog say: "written in JSON, decrypted on this
        // device. Anyone who opens it can read it". A passphrase seals THIS body (SettingsFragment;
        // ExportReadableCopyTest pins that); without one, it is the file. Here because the harness is.
        val applePark = point(37.3349, -122.0090)
        serve(
            circles = listOf(circle(held)),
            places = listOf(place("p-held", held)),
            checkIns = listOf(checkIn("k-held", held)),
            locations = listOf(location("l-held", held)),
        )
        every { live.decryptDataV2(held, sealedAs("name-$held"), "name") } returns "Family"
        every { live.decryptDataV2(held, sealedAs("p-held-name"), "name") } returns "School"
        every { live.decryptLocation(held, sealedAs("p-held-loc")) } returns applePark
        every { live.decryptDataV2(held, sealedAs("k-held-msg"), "message") } returns "Home safe"
        every { live.decryptLocation(held, sealedAs("l-held-loc")) } returns applePark

        val built = repo.exportAndBuildBackup(generatedAt = "2026-10-15T00:00:00Z").getOrThrow()
        val json = JsonParser.parseString(built.fileBody).asJsonObject

        assertEquals("Family", json.rows("circles").single().get("name").asString)
        val place = json.rows("places").single()
        assertEquals("School", place.get("name").asString)
        assertEquals(37.3349, place.getAsJsonObject("location").get("latitude").asDouble, 0.0)
        assertEquals("Home safe", json.rows("check_ins").single().get("message").asString)
        assertEquals(-122.0090, json.rows("locations").single().getAsJsonObject("location").get("longitude").asDouble, 0.0)
        for (tag in listOf("name-$held", "p-held-name", "p-held-loc", "k-held-msg", "l-held-loc")) {
            assertFalse("$tag reached the file sealed", built.fileBody.contains("c-$tag"))
        }
    }
}
