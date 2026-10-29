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

package com.pingolin.track.data.remote.api

import com.pingolin.track.data.remote.dto.CreateCircleRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.RefreshRecordsRequest
import com.pingolin.track.testutil.startOnIpv4Loopback
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Account ids where they're decoded, on the wire: every spelling the SDK takes comes out
 * canonical, and an id that is no UUID refuses its entry, counted.
 */
class CanonicalIdsTrackApiTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val logged = mutableListOf<String>()
    private val api = CanonicalIdsTrackApi(
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java),
    ) { logged += it }

    private val ann = "a11ce000-0000-4000-8000-00000000000a"
    private val ben = "b0b00000-0000-4000-8000-00000000000b"

    /** [ann] in every spelling the SDK's parser takes, the canonical one included. */
    private val spellings = listOf(
        ann, ann.uppercase(), ann.replace("-", ""), ann.replace("-", "").uppercase(), "{$ann}", "urn:uuid:${ann.uppercase()}",
    )

    /** Circles, as the decoder takes them now: UUIDs, with hex letters so their upper case differs. */
    private val c1 = "c1c1c1c1-0000-4000-8000-0000000000c1"
    private val c2 = "c2c2c2c2-0000-4000-8000-0000000000c2"
    private val legacy = "ce1e0000-0000-4000-8000-0000000000ce"
    private val bad = "cbad0000-0000-4000-8000-0000000000cb"
    private val badSender = "cbad5e00-0000-4000-8000-0000000000cb"
    private val badRecipient = "cbadec00-0000-4000-8000-0000000000cb"
    private fun cid(i: Int) = "c000000$i-0000-4000-8000-0000000000c0"

    /** Ids that are no UUID in any spelling the SDK takes. */
    private val notIds = listOf("user-ann", " $ann", "{${ann.replace("-", "")}}", "URN:UUID:$ann", "")

    @After
    fun tearDown() = server.shutdown()

    private fun reply(data: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"data":$data,"error":null}""")

    private fun member(id: String, signer: String? = null) =
        """{"user_id":"$id","role":"member"${signer?.let { ""","wrap_signer_user_id":"$it"""" }.orEmpty()}}"""

    @Test
    fun `the member list - each spelling is canonical, a row whose id is no UUID is dropped and counted`() = runTest {
        val rows = spellings.map { member(it, signer = it) } + notIds.map { member(it) } + member(ben)
        server.enqueue(reply("""{"items":[${rows.joinToString(",")}],"has_more":true,"next_cursor":"next"}"""))

        val page = api.getCircleMembers("c-1", 50, null).body()!!.data!!

        assertEquals(List(spellings.size) { ann } + ben, page.items.map { it.userId })
        assertEquals("the copy's signer too", List(spellings.size) { ann } + null, page.items.map { it.wrapSignerUserId })
        assertTrue("paging as served", page.hasMore)
        assertEquals("next", page.cursor)
        assertEquals(listOf("member list: ${notIds.size} account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    @Test
    fun `the member list - a copy's signer that is no UUID is no signer on record, and the row stays`() = runTest {
        server.enqueue(reply("""{"items":[${member(ben, signer = "user-ann")}],"has_more":false}"""))

        val row = api.getCircleMembers("c-1", 50, null).body()!!.data!!.items.single()

        assertEquals(ben, row.userId)
        assertNull(row.wrapSignerUserId)
        assertEquals(1, logged.size)
    }

    @Test
    fun `the member keys - each spelling is canonical, an entry whose id is no UUID is dropped`() = runTest {
        val keys = (spellings + notIds + ben).map { """{"user_id":"$it","sharing_public_key":"x-$it","signing_public_key":"s-$it"}""" }
        server.enqueue(reply("[${keys.joinToString(",")}]"))

        val served = api.getMemberPublicKeys("c-1").body()!!.data!!

        assertEquals(List(spellings.size) { ann } + ben, served.map { it.userId })
        assertEquals("the keys as served", (spellings + ben).map { "x-$it" }, served.map { it.sharingPublicKey })
        assertEquals(listOf("member keys: ${notIds.size} account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    private fun bundle(circle: String, sender: String?, recipient: String?) =
        """{"circle_id":"$circle","encrypted_circle_key":{"nonce":"n","ciphertext":"c"},"ephemeral_public_key":"e","key_version":2""" +
            sender?.let { ""","sender_user_id":"$it"""" }.orEmpty() + recipient?.let { ""","recipient_user_id":"$it"""" }.orEmpty() +
            ""","wrap_signature":"sig"}"""

    @Test
    fun `the circle keys - sender and recipient canonical, a copy naming no UUID for either is dropped`() = runTest {
        val bundles = spellings.mapIndexed { i, id -> bundle(cid(i), sender = id, recipient = spellings[(i + 1) % spellings.size]) } +
            bundle(legacy, sender = null, recipient = null) +
            notIds.map { bundle(badSender, sender = it, recipient = ben) } +
            notIds.map { bundle(badRecipient, sender = ben, recipient = it) }
        server.enqueue(reply("[${bundles.joinToString(",")}]"))

        val served = api.getCircleKeys().body()!!.data!!

        assertEquals(spellings.indices.map { cid(it) } + legacy, served.map { it.circleId })
        assertEquals(List(spellings.size) { ann } + null, served.map { it.senderUserId })
        assertEquals("none named stays none", List(spellings.size) { ann } + null, served.map { it.recipientUserId })
        assertEquals(listOf("circle keys: ${2 * notIds.size} account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    @Test
    fun `the personal copy - canonical, and one naming no UUID is no answer, never none published`() = runTest {
        server.enqueue(reply(bundle("00000000-0000-0000-0000-000000000000", sender = "{$ann}", recipient = ann.uppercase())))
        val taken = api.getSelfKey().body()!!
        assertTrue(taken.success)
        assertEquals(ann, taken.data!!.senderUserId)
        assertEquals(ann, taken.data!!.recipientUserId)

        for (notId in notIds) {
            server.enqueue(reply(bundle("00000000-0000-0000-0000-000000000000", sender = ann, recipient = notId)))
            val refused = api.getSelfKey()
            assertTrue("a 2xx", refused.isSuccessful)
            assertFalse(notId, refused.body()!!.success)
            assertNull(refused.body()!!.data)
        }

        server.enqueue(reply("null"))
        val none = api.getSelfKey().body()!!
        assertTrue("the server's own none stays its answer", none.success)
        assertNull(none.data)
        assertEquals(List(notIds.size) { "personal copy: its account ids couldn't be read; not taken" }, logged)
    }

    private fun circle(id: String, sender: String?) =
        """{"id":"$id","encrypted_name":{"nonce":"nn","ciphertext":"nc"},"encrypted_key":{"nonce":"n","ciphertext":"c"},""" +
            """"ephemeral_public_key":"e","member_count":2,"is_owner":false,"day_bucket":1,"key_version":2""" +
            sender?.let { ""","sender_user_id":"$it"""" }.orEmpty() + ""","wrap_signature":"sig"}"""

    @Test
    fun `the circle list - the copy's sender canonical, a copy from no UUID dropped and its circle kept`() = runTest {
        val circles = spellings.mapIndexed { i, id -> circle(cid(i), id) } + circle(legacy, null) + circle(bad, "user-ann")
        val page = """{"items":[${circles.joinToString(",")}],"has_more":false}"""
        server.enqueue(reply(page))
        server.enqueue(reply(page))

        for (served in listOf(api.getCircles().body()!!.data!!.items, api.getCirclesPaginated(null, 20).body()!!.data!!.items)) {
            assertEquals(spellings.indices.map { cid(it) } + legacy + bad, served.map { it.id })
            assertEquals(List(spellings.size) { ann } + null + null, served.map { it.senderUserId })
            val kept = served.dropLast(1)
            assertTrue(kept.all { it.encryptedKey != null && it.ephemeralPublicKey != null && it.wrapSignature == "sig" })
            val refused = served.last()
            assertNull(refused.encryptedKey)
            assertNull(refused.ephemeralPublicKey)
            assertNull(refused.wrapSignature)
            assertEquals("its name as served", EncryptedPayload("nn", "nc"), refused.encryptedName)
        }
        assertEquals(List(2) { "circle list: 1 account id(s) that aren't UUIDs; their entries refused" }, logged)
    }

    @Test
    fun `one circle, and a new one - the copy's sender canonical, or the copy dropped`() = runTest {
        server.enqueue(reply(circle(c1, "urn:uuid:$ann")))
        server.enqueue(reply(circle(c1, ann.replace("-", ""))))
        server.enqueue(reply(circle(c2, " $ann")))

        assertEquals(ann, api.getCircle(c1).body()!!.data!!.senderUserId)
        assertEquals(ann, api.createCircle(CreateCircleRequest(EncryptedPayload("n", "c"), EncryptedPayload("k", "c"), "e")).body()!!.data!!.senderUserId)
        val refused = api.getCircle(c2).body()!!.data!!
        assertNull(refused.senderUserId)
        assertNull(refused.encryptedKey)
        assertEquals(listOf("circle: 1 account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    @Test
    fun `the served heads - canonical, and a head naming no UUID is dropped with the records untouched`() = runTest {
        val heads = (spellings + notIds + ben).map { """{"user_id":"$it","head":"h-$it","signature":"s"}""" }
        server.enqueue(reply(
            """{"circles":[{"circle_id":"$c1","records":[{"version":1,"record":"r1","signature":"rs"}],""" +
                """"heads":[${heads.joinToString(",")}],"complete":true}],"more":false}""",
        ))

        val circle = api.refreshRecords(RefreshRecordsRequest(mapOf(c1 to 0))).body()!!.data!!.circles.single()

        assertEquals(List(spellings.size) { ann } + ben, circle.heads.map { it.userId })
        assertEquals((spellings + ben).map { "h-$it" }, circle.heads.map { it.head })
        assertEquals(listOf("r1"), circle.records.map { it.record })
        assertTrue(circle.complete)
        assertEquals(listOf("refresh records: ${notIds.size} account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    @Test
    fun `a notification's account ids - canonical, one that is no UUID left out and its row kept`() = runTest {
        server.enqueue(reply(
            """{"notifications":[""" +
                """{"id":"n-1","circle_id":"$c1","notification_type":"place_alert","title":"t","body":null,""" +
                """"metadata":{"place_id":"p-1","target_user_id":"{$ann}","event_type_hash":"h"}},""" +
                """{"id":"n-2","circle_id":"$c1","notification_type":"ownership_claim","title":"t","body":null,""" +
                """"metadata":{"claim_id":"k-1","new_owner_id":"user-ann"}},""" +
                """{"id":"n-3","circle_id":"$c1","notification_type":"place_alert","title":"t","body":null,""" +
                """"metadata":{"target_user_id":7}},""" +
                """{"id":"n-4","circle_id":null,"notification_type":"sos_alert","title":"t","body":null,"metadata":null}""" +
                """],"unread_count":4,"has_more":true}""",
        ))

        val list = api.getNotifications(50, 0, false).body()!!.data!!

        assertEquals("every row kept", listOf("n-1", "n-2", "n-3", "n-4"), list.notifications.map { it.id })
        assertEquals(mapOf("place_id" to "p-1", "target_user_id" to ann, "event_type_hash" to "h"), list.notifications[0].metadata)
        assertEquals(mapOf("claim_id" to "k-1"), list.notifications[1].metadata)
        assertEquals(emptyMap<String, Any>(), list.notifications[2].metadata)
        assertNull(list.notifications[3].metadata)
        assertEquals(4L, list.unreadCount)
        assertTrue(list.hasMore)
        assertEquals(listOf("notifications: 2 account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    /** The personal key's slot in every spelling the SDK takes, and this app's own slot name, any case. */
    private val slots = listOf(
        "00000000-0000-0000-0000-000000000000", "00000000000000000000000000000000",
        "{00000000-0000-0000-0000-000000000000}", "urn:uuid:00000000-0000-0000-0000-000000000000", "self", "SELF",
    )

    @Test
    fun `a listed circle named by the personal key's slot, in any spelling, is refused and counted, and the rest of the list stands`() = runTest {
        // The personal key's slot is no circle.
        val page = """{"items":[${(slots.map { circle(it, ann) } + circle(c1, ann)).joinToString(",")}],"has_more":false}"""
        server.enqueue(reply(page))
        server.enqueue(reply(page))

        for (served in listOf(api.getCircles().body()!!.data!!.items, api.getCirclesPaginated(null, 20).body()!!.data!!.items)) {
            assertEquals(listOf(c1), served.map { it.id })
            assertEquals(ann, served.single().senderUserId)
        }
        assertEquals(List(2) { "circle list: ${slots.size} entr(ies) naming the personal key's slot as a circle; refused" }, logged)
    }

    @Test
    fun `one circle, or a new one, named by the personal key's slot is no answer`() = runTest {
        for (slot in slots) {
            server.enqueue(reply(circle(slot, ann)))
            val refused = api.getCircle(c1)
            assertTrue(slot, refused.isSuccessful)
            assertFalse(slot, refused.body()!!.success)
            assertNull(slot, refused.body()!!.data)
        }
        server.enqueue(reply(circle(slots[1], ann)))
        assertNull(api.createCircle(CreateCircleRequest(EncryptedPayload("n", "c"), EncryptedPayload("k", "c"), "e")).body()!!.data)

        assertEquals(
            List(slots.size) { "circle: it names the personal key's slot as a circle; not taken" } +
                "new circle: it names the personal key's slot as a circle; not taken",
            logged,
        )
    }

    private fun invitation(circle: String) =
        """{"id":"i-$circle","circle_id":"$circle","invited_by":"$ann","invite_type":"account","created_day_bucket":1,""" +
            """"expires_day_bucket":8,"has_encrypted_key":true}"""

    @Test
    fun `a key copy, a circle's records and an invitation naming the personal key's slot are refused and counted, and the personal copy's own route is untouched`() = runTest {
        server.enqueue(reply("[${(slots.map { bundle(it, sender = ann, recipient = ann) } + bundle(c1, sender = ann, recipient = ann)).joinToString(",")}]"))
        server.enqueue(reply(
            """{"circles":[${(slots + c1).joinToString(",") { """{"circle_id":"$it","records":[],"heads":[{"user_id":"$ann","head":"h","signature":"s"}],"complete":true}""" }}],"more":false}""",
        ))
        server.enqueue(reply("[${(slots + c1).joinToString(",") { invitation(it) }}]"))
        server.enqueue(reply(bundle(slots[0], sender = ann, recipient = ann)))

        assertEquals(listOf(c1), api.getCircleKeys().body()!!.data!!.map { it.circleId })
        assertEquals(listOf(c1), api.refreshRecords(RefreshRecordsRequest(mapOf(c1 to 0))).body()!!.data!!.circles.map { it.circleId })
        assertEquals(listOf(c1), api.getReceivedInvites().body()!!.data!!.map { it.circleId })
        assertEquals("the personal copy, on its own route", slots[0], api.getSelfKey().body()!!.data!!.circleId)

        assertEquals(
            listOf("circle keys", "refresh records", "received invitations").map {
                "$it: ${slots.size} entr(ies) naming the personal key's slot as a circle; refused"
            },
            logged,
        )
    }

    // ---- circle ids: one spelling at every entry, one that is no UUID refused ----

    /** A circle's id, with hex letters so its upper case differs, in every spelling the SDK takes. */
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"
    private val ben0 = "be0c1e00-0000-4000-8000-0000000000be"
    private val circleSpellings = listOf(
        fam, fam.uppercase(), fam.replace("-", ""), fam.replace("-", "").uppercase(), "{$fam}", "urn:uuid:${fam.uppercase()}",
    )

    /** Circle ids that are no UUID in any spelling the SDK takes. */
    private val notCircles = listOf("circle-fam", " $fam", "{${fam.replace("-", "")}}", "URN:UUID:$fam")

    @Test
    fun `the circle list, one circle and a new one - each spelling of a circle's id comes out in its one spelling, and one that is no UUID is refused`() = runTest {
        val listed = (circleSpellings + notCircles).map { circle(it, ann) } + circle(ben0, ann)
        val page = """{"items":[${listed.joinToString(",")}],"has_more":false}"""
        server.enqueue(reply(page))
        server.enqueue(reply(page))

        for (served in listOf(api.getCircles().body()!!.data!!.items, api.getCirclesPaginated(null, 20).body()!!.data!!.items)) {
            assertEquals(List(circleSpellings.size) { fam } + ben0, served.map { it.id })
            assertEquals("its copy stays with it", List(circleSpellings.size + 1) { ann }, served.map { it.senderUserId })
        }

        for (spelling in circleSpellings) {
            server.enqueue(reply(circle(spelling, ann)))
            assertEquals(spelling, fam, api.getCircle(fam).body()!!.data!!.id)
        }
        server.enqueue(reply(circle(fam.uppercase(), ann)))
        assertEquals(fam, api.createCircle(CreateCircleRequest(EncryptedPayload("n", "c"), EncryptedPayload("k", "c"), "e")).body()!!.data!!.id)
        for (notCircle in notCircles) {
            server.enqueue(reply(circle(notCircle, ann)))
            val refused = api.getCircle(fam).body()!!
            assertFalse(notCircle, refused.success)
            assertNull(notCircle, refused.data)
        }

        assertEquals(
            List(2) { "circle list: ${notCircles.size} circle id(s) that aren't UUIDs; their entries refused" } +
                List(notCircles.size) { "circle: its circle id isn't a UUID; not taken" },
            logged,
        )
    }

    @Test
    fun `the key copies, the records and the invitations - each circle in its one spelling, one that is no UUID dropped, and the personal copy untouched`() = runTest {
        server.enqueue(reply("[${(circleSpellings + notCircles).joinToString(",") { bundle(it, sender = ann, recipient = ben) }}]"))
        server.enqueue(reply(
            """{"circles":[${(circleSpellings + notCircles).joinToString(",") { """{"circle_id":"$it","records":[],"heads":[{"user_id":"$ann","head":"h","signature":"s"}],"complete":true}""" }}],"more":false}""",
        ))
        server.enqueue(reply("[${(circleSpellings + notCircles).joinToString(",") { invitation(it) }}]"))
        server.enqueue(reply(bundle("00000000-0000-0000-0000-000000000000", sender = ann, recipient = ann)))

        assertEquals(List(circleSpellings.size) { fam }, api.getCircleKeys().body()!!.data!!.map { it.circleId })
        assertEquals(List(circleSpellings.size) { fam }, api.refreshRecords(RefreshRecordsRequest(mapOf(fam to 0))).body()!!.data!!.circles.map { it.circleId })
        assertEquals(List(circleSpellings.size) { fam }, api.getReceivedInvites().body()!!.data!!.map { it.circleId })
        assertEquals("the personal copy, on its own route", "00000000-0000-0000-0000-000000000000", api.getSelfKey().body()!!.data!!.circleId)

        assertEquals(
            listOf("circle keys", "refresh records", "received invitations").map {
                "$it: ${notCircles.size} circle id(s) that aren't UUIDs; their entries refused"
            },
            logged,
        )
    }

    /**
     * The answer to an invitation as the server writes it (`invite_token`, `delivery`), with [circle]
     * a field a later server might add.
     */
    private fun invite(circle: String? = null) =
        """{"invite_token":"invitee-token-stand-in","delivery":"notified"${circle?.let { ""","circle_id":"$it"""" }.orEmpty()}}"""

    @Test
    fun `the invitation just sent - the server's answer names no circle and is the one it was sent to, one naming its circle is that circle in its one spelling, and one naming another circle or no UUID is no answer`() = runTest {
        // As the server sends it: 201, `error` and `error_code` left out.
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
            .setBody("""{"success":true,"data":${invite()}}"""))
        server.enqueue(reply(invite(fam.uppercase())))
        server.enqueue(reply(invite(ben0)))
        server.enqueue(reply(invite("circle-fam")))
        val request = com.pingolin.track.data.remote.dto.InviteRequest(email = "a@example.com")

        val sent = api.inviteToCircle(fam, request)
        assertEquals(201, sent.code())
        assertTrue(sent.body()!!.success)
        assertEquals("notified", sent.body()!!.data!!.delivery)
        assertNull(sent.body()!!.data!!.circleId)
        assertEquals(fam, api.inviteToCircle(fam, request).body()!!.data!!.circleId)
        for (what in listOf("another circle", "no UUID")) {
            val refused = api.inviteToCircle(fam, request).body()!!
            assertFalse(what, refused.success)
            assertNull(what, refused.data)
        }
        assertEquals(listOf("invitation: it names another circle than the one it was sent in; not taken", "invitation: its circle id isn't a UUID; not taken"), logged)
    }

    @Test
    fun `an ownership claim and a location consent - their circle in its one spelling, and one that is no UUID is no answer`() = runTest {
        server.enqueue(reply("""{"circle_id":"{$fam}","status":"pending"}"""))
        server.enqueue(reply("""{"status":"none"}"""))
        server.enqueue(reply("""{"circle_id":"circle-fam","status":"pending"}"""))
        server.enqueue(reply("""{"circle_id":"${fam.replace("-", "")}","status":"granted","left_circle":false}"""))
        server.enqueue(reply("""{"circle_id":"circle-fam","status":"withdrawn"}"""))

        assertEquals(fam, api.getOwnershipClaim(fam).body()!!.data!!.circleId)
        assertNull("none named stays none", api.claimOwnership(fam).body()!!.data!!.circleId)
        assertNull(api.getOwnershipClaim(fam).body()!!.data)
        assertEquals(fam, api.getLocationConsent(fam).body()!!.data!!.circleId)
        // The consent read is the one consent call (no withdrawal from this app).
        assertNull(api.getLocationConsent(fam).body()!!.data)
    }

    @Test
    fun `a notification's circle - in its one spelling on the row and in its metadata, and one that is none is left out with the row kept`() = runTest {
        server.enqueue(reply(
            """{"notifications":[""" +
                """{"id":"n-1","circle_id":"${fam.uppercase()}","notification_type":"member_joined","title":"t","body":null,"metadata":{"circle_id":"urn:uuid:$fam"}},""" +
                """{"id":"n-2","circle_id":"circle-fam","notification_type":"member_joined","title":"t","body":null,"metadata":{"circle_id":"circle-fam"}},""" +
                """{"id":"n-3","circle_id":"00000000000000000000000000000000","notification_type":"member_joined","title":"t","body":null,"metadata":{"circle_id":"SELF"}}""" +
                """],"unread_count":3,"has_more":false}""",
        ))

        val list = api.getNotifications(50, 0, false).body()!!.data!!

        assertEquals("every row kept", listOf("n-1", "n-2", "n-3"), list.notifications.map { it.id })
        assertEquals(listOf(fam, null, null), list.notifications.map { it.circleId })
        assertEquals(mapOf("circle_id" to fam), list.notifications[0].metadata)
        assertEquals(emptyMap<String, Any>(), list.notifications[1].metadata)
        assertEquals(emptyMap<String, Any>(), list.notifications[2].metadata)
        assertEquals(
            listOf(
                "notifications: 2 entr(ies) naming the personal key's slot as a circle; refused",
                "notifications: 2 circle id(s) that aren't UUIDs; their entries refused",
            ),
            logged,
        )
    }

    // ---- content: one namespace in Room ----

    private val enc = """{"nonce":"n","ciphertext":"c"}"""
    private val nil = "00000000-0000-0000-0000-000000000000"

    private fun sos(circle: String) =
        """{"id":"s-1","circle_id":"$circle","encrypted_location":$enc,"status":"active","encrypted_user_id":$enc,"encrypted_device_id":$enc,"day_bucket":1,"encrypted_timestamp":$enc}"""
    private fun privacy(circle: String) =
        """{"circle_id":"$circle","sharing_mode":"exact","share_realtime":true,"share_history":true,"share_battery":false}"""
    private fun destination(circle: String) =
        """{"id":"d-1","encrypted_user_id":$enc,"circle_id":"$circle","encrypted_destination":$enc,"notify_arrival":true,"status":"active",""" +
            """"started_day_bucket":1,"created_day_bucket":1,"updated_day_bucket":1}"""
    private fun boundaryEvent(circle: String) =
        """{"id":"b-1","place_id":"p-1","circle_id":"$circle","encrypted_user_id":$enc,"encrypted_reported_by_user_id":$enc,""" +
            """"encrypted_device_id":$enc,"event_type_hash":"h","encrypted_event_type":$enc,"day_bucket":1,"encrypted_occurred_at":$enc}"""
    private fun boundaryState(circle: String) =
        """{"id":"b-1","place_id":"p-1","circle_id":"$circle","encrypted_user_id":$enc,"is_inside":true,"last_seen_day_bucket":1}"""
    private fun inactivity(circle: String) =
        """{"id":"a-1","user_id":"$ann","circle_id":"$circle","encrypted_target_user_id":$enc,"timeout_hours":2,"is_self_alert":false,""" +
            """"notify_push":true,"notify_email":false,"cooldown_minutes":5,"is_active":true,"day_bucket":1,"client_only":false}"""
    private fun selfAlert(circle: String) = """{"id":"a-1","user_id":"$ann","circle_id":"$circle","timeout_hours":2}"""
    private fun named(circle: String?) = circle?.let { """"circle_id":"$it",""" }.orEmpty()
    private fun placeAlert(circle: String?) =
        """{"id":"a-1","place_id":"p-1","user_id":"$ann",${named(circle)}"encrypted_target_user_id":$enc,"alert_on_arrival":true,""" +
            """"alert_on_departure":false,"is_overnight":false,"notify_push":true,"notify_email":false,"notify_sms":false,"is_recurring":false,""" +
            """"is_active":true,"day_bucket":1}"""
    private fun alertRule(circle: String?) =
        """{"id":"r-1","user_id":"$ann",${named(circle)}"rule_type":"speed","is_active":true,"notify_push":true,"notify_email":false,""" +
            """"notify_sms":false,"encrypted_target_user_id":$enc,"day_bucket":1}"""
    private fun checkIn(circle: String?) =
        """{"id":"k-1",${named(circle)}"check_in_type":"general","encrypted_user_id":$enc,"encrypted_device_id":$enc,"day_bucket":1,"encrypted_timestamp":$enc}"""
    private fun place(circle: String?, via: String? = null, shared: List<String>? = null) =
        """{"id":"p-1",${named(circle)}${via?.let { """"wrap_via_circle":"$it",""" }.orEmpty()}""" +
            """${shared?.let { list -> """"shared_circles":[${list.joinToString(",") { "\"$it\"" }}],""" }.orEmpty()}""" +
            """"encrypted_name":$enc,"encrypted_location":$enc,"radius_meters":100,"created_at":"2026-09-29T00:00:00Z"}"""

    @Test
    fun `an inactivity page every entry of which is refused is no answer, not the end of the list`() = runTest {
        server.enqueue(reply("[${(notCircles + nil).joinToString(",") { inactivity(it) }}]"))
        val refused = api.getInactivityAlerts(100, 0)
        assertEquals(false, refused.body()!!.success)
        assertEquals(null, refused.body()!!.data)
        assertEquals("inactivity alerts: every entry refused; not taken", logged.last())
        // One entry taken: the page.
        server.enqueue(reply("[${(notCircles + fam).joinToString(",") { inactivity(it) }}]"))
        assertEquals(listOf(fam), api.getInactivityAlerts(100, 0).body()!!.data!!.map { it.circleId })
        // And a page the server sent empty is the end.
        server.enqueue(reply("[]"))
        assertEquals(emptyList<String>(), api.getInactivityAlerts(100, 0).body()!!.data!!.map { it.circleId })
    }

    /** One list endpoint: its log name, a row of it naming a circle, and what the app reads back. */
    private class ListCase(val what: String, val row: (String) -> String, val read: suspend () -> List<String?>)

    @Test
    fun `content lists - each circle in its one spelling, and a row naming no circle a circle may have is dropped and counted`() = runTest {
        val cases = listOf(
            ListCase("SOS alerts", ::sos) { api.getActiveSosAlerts().body()!!.data!!.map { it.circleId } },
            ListCase("destinations", ::destination) { api.getDestinations().body()!!.data!!.map { it.circleId } },
            ListCase("boundary events", ::boundaryEvent) { api.getBoundaryEvents(fam, null, 50).body()!!.data!!.map { it.circleId } },
            ListCase("boundary states", ::boundaryState) { api.getBoundaryStates(fam).body()!!.data!!.map { it.circleId } },
            ListCase("inactivity alerts", ::inactivity) { api.getInactivityAlerts(50, 0).body()!!.data!!.map { it.circleId } },
            ListCase("circle self alerts", ::selfAlert) { api.getCircleSelfAlerts().body()!!.data!!.map { it.circleId } },
            ListCase("place alerts", { placeAlert(it) }) { api.getPlaceAlerts(null).body()!!.data!!.map { it.circleId } },
            ListCase("alert rules", { alertRule(it) }) {
                server.enqueue(reply("""{"items":[${(circleSpellings + notCircles + nil).joinToString(",") { alertRule(it) }}],"has_more":false}"""))
                api.getAlertRules(null, 50).body()!!.data!!.items.map { it.circleId }
            },
        )
        for (case in cases) {
            logged.clear()
            if (case.what != "alert rules") server.enqueue(reply("[${(circleSpellings + notCircles + nil).joinToString(",") { case.row(it) }}]"))
            assertEquals(case.what, List(circleSpellings.size) { fam }, case.read())
            assertEquals(
                case.what,
                listOf(
                    "${case.what}: 1 entr(ies) naming the personal key's slot as a circle; refused",
                    "${case.what}: ${notCircles.size} circle id(s) that aren't UUIDs; their entries refused",
                ),
                logged,
            )
        }
    }

    @Test
    fun `content replies about one row - its circle in its one spelling, and one naming no circle is no answer`() = runTest {
        val reads: List<Pair<(String) -> String, suspend () -> String?>> = listOf(
            ::sos to suspend { api.getSosAlert("s-1").body()!!.data?.circleId },
            ::sos to suspend { api.acknowledgeSos("s-1").body()!!.data?.circleId },
            ::sos to suspend { api.dismissSos("s-1").body()!!.data?.circleId },
            ::sos to suspend { api.undismissSos("s-1").body()!!.data?.circleId },
            ::sos to suspend { api.resolveSos("s-1").body()!!.data?.circleId },
            ::sos to suspend { api.cancelSos("s-1").body()!!.data?.circleId },
            ::privacy to suspend { api.getPrivacySettings(fam).body()!!.data?.circleId },
            ::privacy to suspend { api.resumeSharing(fam).body()!!.data?.circleId },
            ::destination to suspend { api.getDestination("d-1").body()!!.data?.circleId },
            ::inactivity to suspend { api.getInactivityAlert("a-1").body()!!.data?.circleId },
            { c: String -> placeAlert(c) } to suspend { api.getPlaceAlert("a-1").body()!!.data?.circleId },
            { c: String -> alertRule(c) } to suspend { api.getAlertRule("r-1").body()!!.data?.circleId },
        )
        for ((row, read) in reads) {
            server.enqueue(reply(row("urn:uuid:${fam.uppercase()}")))
            assertEquals(fam, read())
            server.enqueue(reply(row("circle-fam")))
            assertNull(read())
        }
    }

    @Test
    fun `a place alert or an alert rule naming no circle keeps none, never refused`() = runTest {
        server.enqueue(reply("[${placeAlert(null)}]"))
        server.enqueue(reply(placeAlert(null)))
        server.enqueue(reply("""{"items":[${alertRule(null)}],"has_more":false}"""))
        server.enqueue(reply(alertRule(null)))

        assertEquals(listOf<String?>(null), api.getPlaceAlerts(null).body()!!.data!!.map { it.circleId })
        assertEquals("a-1", api.getPlaceAlert("a-1").body()!!.data?.id)
        assertEquals(listOf<String?>(null), api.getAlertRules(null, 50).body()!!.data!!.items.map { it.circleId })
        assertEquals("r-1", api.getAlertRule("r-1").body()!!.data?.id)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `a place - its circle and the circle it opens through in their one spelling, the personal key's nil as itself, and shares naming no circle left out`() = runTest {
        server.enqueue(reply("[" + listOf(
            place(fam.uppercase(), via = "{$fam}", shared = listOf(fam.replace("-", ""), "circle-fam", nil, ben0.uppercase())),
            place(null),
            place(nil.replace("-", ""), via = "urn:uuid:$nil"),
            place("self"),
            place("circle-fam"),
            place(fam, via = "circle-fam"),
        ).joinToString(",") + "]"))

        val places = api.getPlaces(null, 50, 0).body()!!.data!!

        assertEquals(listOf(fam, null, nil), places.map { it.circleId })
        assertEquals(listOf(fam, null, nil), places.map { it.wrapViaCircle })
        assertEquals(listOf(fam, ben0), places[0].sharedCircles)
        assertEquals(
            listOf(
                "places: 1 entr(ies) naming the personal key's slot as a circle; refused",
                "places: 4 circle id(s) that aren't UUIDs; their entries refused",
            ),
            logged,
        )
    }

    @Test
    fun `a check-in - its circle in its one spelling, none or the personal key's nil kept, and one naming no circle dropped`() = runTest {
        val rows = "[${listOf(checkIn(fam.uppercase()), checkIn(null), checkIn(nil.uppercase()), checkIn("circle-fam")).joinToString(",")}]"
        server.enqueue(reply(rows))
        server.enqueue(reply(rows))

        assertEquals(listOf(fam, null, nil), api.getMyCheckIns(50).body()!!.data!!.map { it.circleId })
        assertEquals(listOf(fam, null, nil), api.getCircleCheckIns(fam, 50).body()!!.data!!.map { it.circleId })
        assertEquals(List(2) { "check-ins: 1 circle id(s) that aren't UUIDs; their entries refused" }, logged)
    }

    @Test
    fun `an error passes as it is, and a reply whose ids can't be taken whole is no answer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"success":false,"data":null,"error":"busy"}"""))
        val error = api.getCircleMembers("c-1", 50, null)
        assertEquals(503, error.code())
        assertEquals("""{"success":false,"data":null,"error":"busy"}""", error.errorBody()!!.string())

        // A row in another spelling without the role every row carries: it can't be rewritten.
        server.enqueue(reply("""{"items":[{"user_id":"${ann.uppercase()}"}],"has_more":false}"""))
        val unreadable = api.getCircleMembers("c-1", 50, null)
        assertTrue(unreadable.isSuccessful)
        assertFalse(unreadable.body()!!.success)
        assertNull(unreadable.body()!!.data)
        assertNull("no words of the server's own", unreadable.body()!!.error)
        assertEquals(listOf("member list: its account ids couldn't be read; not taken"), logged)
    }

    @Test
    fun `the app's TrackApi decodes through it`() {
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(GsonConverterFactory.create()).build()

        assertTrue(com.pingolin.track.di.AppModule.provideTrackApi(retrofit) is CanonicalIdsTrackApi)
    }

    @Test
    fun `canonical ids are passed on untouched, with nothing logged`() = runTest {
        server.enqueue(reply("""{"items":[${member(ann, signer = ben)},${member(ben)}],"has_more":false}"""))

        val rows = api.getCircleMembers("c-1", 50, null).body()!!.data!!.items

        assertEquals(listOf(ann, ben), rows.map { it.userId })
        assertEquals(listOf(ben, null), rows.map { it.wrapSignerUserId })
        assertTrue(logged.isEmpty())
        assertEquals("/api/v1/circles/c-1/members?limit=50", server.takeRequest().path)
    }
}
