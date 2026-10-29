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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.TrackDatabase
import com.pingolin.track.data.local.dao.AlertRuleDao
import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.api.CanonicalIdsTrackApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * What the alert lists' refreshes write (AlertRulesReload runs them hourly in the
 * background): only what changed; never a weaker copy over a row this phone has; from a whole
 * list, what the server no longer lists goes, and from a partial one nothing; the inactivity list
 * to its last page; and nothing at all into an account the refresh didn't begin for.
 *
 * The lists as the server writes them (`encrypted_conditions` left out when absent), through
 * MockWebServer, a real Retrofit and the app's CanonicalIdsTrackApi, into a real in-memory Room.
 * The crypto is a stand-in: a sealed field "open:<text>" opens to <text>; anything else is
 * sealed under a key this phone doesn't hold.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class AlertListsRefreshTest {

    private companion object {
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
        const val OTHER = "07e00000-0000-4000-8000-0000000000e2"
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val SAM = "5a300000-0000-4000-8000-0000000000a1"
        const val KIM = "c1300000-0000-4000-8000-0000000000a2"
    }

    private lateinit var server: MockWebServer
    private lateinit var api: TrackApi
    private lateinit var db: TrackDatabase
    private lateinit var rules: AlertRuleRepository
    private lateinit var inactivity: InactivityAlertRepository
    private val pending = InMemoryPendingChangeDao()

    @Volatile private var account: String? = ME
    private val session = mockk<SessionManager>(relaxed = true) { every { getUserId() } answers { account } }
    private val crypto = mockk<CryptoManager>(relaxed = true) {
        every { decryptData(any(), any()) } answers {
            val sealed = arg<EncryptedData>(1).ciphertext
            if (sealed.startsWith("open:")) sealed.removePrefix("open:") else throw IllegalStateException("no key")
        }
    }

    @Before
    fun setup() {
        server = MockWebServer()
        server.startOnIpv4Loopback()
        api = CanonicalIdsTrackApi(
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TrackApi::class.java),
        ) {}
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val changes = pendingChangesIn(pending, account = ME)
        rules = AlertRuleRepository(api, db.alertRuleDao(), session, crypto, mockk(relaxed = true), changes)
        inactivity = InactivityAlertRepository(
            api, db.inactivityAlertDao(), db.localInactivityEventDao(), db.circleMemberDao(), session, crypto,
            mockk(relaxed = true), changes,
        )
    }

    @After
    fun teardown() {
        server.shutdown()
        db.close()
    }

    // ── the wire ────────────────────────────────────────────────────────────

    private fun blob(sealed: String) = """{"nonce":"bm9uY2U=","ciphertext":"${sealed.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""

    /** A low-battery rule of mine about [target] as the server writes it; [conditions] null: none sealed. */
    private fun ruleJson(id: String, target: String = "open:$SAM", conditions: String? = "open:{\"threshold\":20}") =
        """{"id":"$id","user_id":"$ME","circle_id":"$CIRCLE","rule_type":"low_battery","conditions":{},""" +
            """"is_active":true,"notify_push":true,"notify_email":false,"notify_sms":false,"cooldown_minutes":60,""" +
            (conditions?.let { """"encrypted_conditions":${blob(it)},""" } ?: "") +
            """"encrypted_target_user_id":${blob(target)},"day_bucket":20000}"""

    private fun rulePage(vararg rules: String, next: String? = null) = MockResponse().setResponseCode(200).setBody(
        """{"success":true,"data":{"items":[${rules.joinToString(",")}],""" +
            (next?.let { """"next_cursor":"$it",""" } ?: "") + """"has_more":${next != null}}}"""
    )

    /** An inactivity alert of mine about [target] as the server writes it; [watching] null: none sealed. */
    private fun alertJson(id: String, dayBucket: Int = 20000, target: String = "open:$SAM", watching: String? = null) =
        """{"id":"$id","user_id":"$ME","circle_id":"$CIRCLE","encrypted_target_user_id":${blob(target)},""" +
            """"timeout_hours":1,"is_self_alert":false,"notify_push":true,"notify_email":false,"notify_on_return":false,""" +
            """"cooldown_minutes":60,"is_active":true,"last_triggered_day_bucket":null,"day_bucket":$dayBucket,""" +
            (watching?.let { """"encrypted_conditions":${blob(it)},""" } ?: "") + """"client_only":false}"""

    private fun alertPage(vararg alerts: String) = MockResponse().setResponseCode(200)
        .setBody("""{"success":true,"data":[${alerts.joinToString(",")}]}""")

    private val unavailable get() = MockResponse().setResponseCode(503).setBody("""{"success":false,"error":"busy"}""")

    // ── what is here ────────────────────────────────────────────────────────

    private fun ruleHere(id: String, threshold: Int? = 20) = AlertRuleEntity(
        id = id, userId = ME, circleId = CIRCLE, targetUserId = SAM, ruleType = "low_battery",
        cooldownMinutes = 60, batteryThreshold = threshold, dayBucket = 20000,
    )

    private fun alertHere(id: String, watched: String? = null) = InactivityAlertEntity(
        id = id, userId = ME, circleId = CIRCLE, targetUserId = SAM, timeoutHours = 1, isSelfAlert = false,
        notifyPush = true, notifyEmail = false, notifyOnReturn = false, cooldownMinutes = 60, isActive = true,
        lastTriggeredDayBucket = null, dayBucket = 20000, clientOnly = false, targetUserIds = watched,
    )

    private suspend fun rulesHere() = db.alertRuleDao().getAll().sortedBy { it.id }
    private suspend fun alertsHere() = db.inactivityAlertDao().getAll().sortedBy { it.id }

    // ── alert rules ─────────────────────────────────────────────────────────

    @Test
    fun `a whole list drops the rule the server no longer lists - one deleted on another device`() = runTest {
        db.alertRuleDao().insertAll(listOf(ruleHere("r-1"), ruleHere("r-gone")))
        server.enqueue(rulePage(ruleJson("r-1"), ruleJson("r-2")))

        val whole = rules.refreshAlertRulesWhole()!!

        assertEquals(setOf("r-1", "r-2"), whole.ids)
        assertTrue(whole.allRead)
        assertEquals(listOf("r-1", "r-2"), rulesHere().map { it.id })
    }

    @Test
    fun `a list cut short drops nothing`() = runTest {
        db.alertRuleDao().insertAll(listOf(ruleHere("r-1"), ruleHere("r-later")))
        server.enqueue(rulePage(ruleJson("r-1"), next = "page-2"))
        server.enqueue(unavailable)

        assertNull("not whole", rules.refreshAlertRulesWhole())
        assertEquals(listOf("r-1", "r-later"), rulesHere().map { it.id })
    }

    @Test
    fun `a rule whose change is waiting to sync stays until that change runs`() = runTest {
        db.alertRuleDao().insertAll(listOf(ruleHere("r-1"), ruleHere("r-edited")))
        pendingChangesIn(pending, account = ME).queue<Unit>(CIRCLE, PendingInputs.AlertChange("Family", "r-edited", 30))
        server.enqueue(rulePage(ruleJson("r-1")))

        rules.refreshAlertRulesWhole()!!

        assertEquals(listOf("r-1", "r-edited"), rulesHere().map { it.id })
    }

    @Test
    fun `a sealed threshold that won't open here keeps the rule as this phone has it`() = runTest {
        db.alertRuleDao().insertAll(listOf(ruleHere("r-1", threshold = 15)))
        server.enqueue(rulePage(ruleJson("r-1", conditions = "sealed-elsewhere")))

        val whole = rules.refreshAlertRulesWhole()!!

        assertFalse("listed, not read here", whole.allRead)
        assertEquals(15, rulesHere().single().batteryThreshold)
    }

    @Test
    fun `a target that won't open here keeps the rule as this phone has it, and isn't read`() = runTest {
        db.alertRuleDao().insertAll(listOf(ruleHere("r-1")))
        server.enqueue(rulePage(ruleJson("r-1", target = "sealed-elsewhere"), ruleJson("r-2")))

        val whole = rules.refreshAlertRulesWhole()!!

        assertEquals(listOf("r-2"), whole.rules.map { it.id })
        assertFalse(whole.allRead)
        assertEquals(listOf(ruleHere("r-1"), ruleHere("r-2")), rulesHere())
    }

    /** The real rule table, noting what each refresh asked it to write. */
    private class Recording(private val real: AlertRuleDao) : AlertRuleDao by real {
        val writes = mutableListOf<Pair<List<String>, List<String>>>()
        override suspend fun applyRefresh(changed: List<AlertRuleEntity>, gone: List<String>, stillFor: () -> Boolean): Boolean {
            writes += changed.map { it.id } to gone
            return real.applyRefresh(changed, gone, stillFor)
        }
    }

    /** As [Recording], for the inactivity table. */
    private class RecordingAlerts(private val real: InactivityAlertDao) : InactivityAlertDao by real {
        val writes = mutableListOf<Pair<List<String>, List<String>>>()
        override suspend fun applyRefresh(changed: List<InactivityAlertEntity>, gone: List<String>, stillFor: () -> Boolean): Boolean {
            writes += changed.map { it.id } to gone
            return real.applyRefresh(changed, gone, stillFor)
        }
    }

    @Test
    fun `an unchanged list writes nothing, so nothing watching the tables hears from it`() = runTest {
        val ruleTable = Recording(db.alertRuleDao())
        val alertTable = RecordingAlerts(db.inactivityAlertDao())
        val changes = pendingChangesIn(pending, account = ME)
        val rules = AlertRuleRepository(api, ruleTable, session, crypto, mockk(relaxed = true), changes)
        val inactivity = InactivityAlertRepository(
            api, alertTable, db.localInactivityEventDao(), db.circleMemberDao(), session, crypto, mockk(relaxed = true), changes,
        )
        repeat(2) {
            server.enqueue(rulePage(ruleJson("r-1")))
            rules.refreshAlertRulesWhole()!!
            server.enqueue(alertPage(alertJson("a-1")))
            server.enqueue(alertPage())
            inactivity.refreshInactivityAlertsWhole()!!
        }

        assertEquals(listOf(listOf("r-1") to emptyList<String>(), emptyList<String>() to emptyList()), ruleTable.writes)
        assertEquals(listOf(listOf("a-1") to emptyList<String>(), emptyList<String>() to emptyList()), alertTable.writes)
        // A changed rule is written again, and only it.
        server.enqueue(rulePage(ruleJson("r-1", conditions = "open:{\"threshold\":25}")))
        rules.refreshAlertRulesWhole()!!
        assertEquals(listOf("r-1") to emptyList<String>(), ruleTable.writes.last())
        assertEquals(25, rulesHere().single().batteryThreshold)
    }

    @Test
    fun `a refresh that ends after a sign-out and another sign-in writes nothing`() = runTest {
        db.alertRuleDao().insertAll(listOf(ruleHere("r-1")))
        // The answer arrives for the account that asked; by then another account is signed in.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                account = OTHER
                return rulePage(ruleJson("r-2"))
            }
        }

        assertNull(rules.refreshAlertRulesWhole())
        assertEquals("the tables are as they were", listOf(ruleHere("r-1")), rulesHere())
    }

    // ── inactivity alerts ───────────────────────────────────────────────────

    @Test
    fun `the inactivity list is read to its last page, each page after the one before`() = runTest {
        val first = (1..InactivityAlertRepository.ALERT_PAGE).map { alertJson("a-%03d".format(it), dayBucket = 20100 - it) }
        server.enqueue(alertPage(*first.toTypedArray()))
        server.enqueue(alertPage(alertJson("a-101", dayBucket = 19000)))
        server.enqueue(alertPage())

        val whole = inactivity.refreshInactivityAlertsWhole()!!

        assertEquals(101, whole.ids.size)
        assertEquals(101, alertsHere().size)
        val asked = (1..3).map { server.takeRequest().requestUrl!! }
        assertEquals(listOf(null, "20000", "19000"), asked.map { it.queryParameter("cursor_day_bucket") })
        assertEquals(listOf(null, "a-100", "a-101"), asked.map { it.queryParameter("cursor_id") })
        assertTrue(asked.all { it.queryParameter("limit") == "${InactivityAlertRepository.ALERT_PAGE}" })
    }

    @Test
    fun `a whole inactivity list drops what the server no longer lists, a partial one nothing`() = runTest {
        db.inactivityAlertDao().insertAll(listOf(alertHere("a-1"), alertHere("a-gone")))
        server.enqueue(alertPage(alertJson("a-1")))
        server.enqueue(unavailable)
        assertNull("the second page couldn't be had", inactivity.refreshInactivityAlertsWhole())
        assertEquals(listOf("a-1", "a-gone"), alertsHere().map { it.id })

        server.enqueue(alertPage(alertJson("a-1")))
        server.enqueue(alertPage())
        assertTrue(inactivity.refreshInactivityAlertsWhole()!!.allRead)
        assertEquals(listOf("a-1"), alertsHere().map { it.id })
    }

    @Test
    fun `a later page whose every entry names no circle isn't the end - nothing is dropped`() = runTest {
        db.inactivityAlertDao().insertAll(listOf(alertHere("a-1"), alertHere("a-on-a-later-page")))
        server.enqueue(alertPage(alertJson("a-1")))
        // A page the app refuses whole (its circle is no UUID): no answer, so the list isn't whole.
        server.enqueue(alertPage(alertJson("a-refused").replace("\"circle_id\":\"$CIRCLE\"", "\"circle_id\":\"circle-fam\"")))

        assertNull(inactivity.refreshInactivityAlertsWhole())
        assertEquals(listOf("a-1", "a-on-a-later-page"), alertsHere().map { it.id })
    }

    @Test
    fun `a sealed watch list that won't open here keeps the alert as this phone has it`() = runTest {
        db.inactivityAlertDao().insertAll(listOf(alertHere("a-1", watched = "$SAM,$KIM")))
        server.enqueue(alertPage(alertJson("a-1", watching = "sealed-elsewhere")))
        server.enqueue(alertPage())

        val whole = inactivity.refreshInactivityAlertsWhole()!!

        assertFalse(whole.allRead)
        assertEquals("$SAM,$KIM", alertsHere().single().targetUserIds)
    }

    @Test
    fun `an inactivity refresh that ends in another account writes nothing`() = runTest {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                account = null
                return if (request.requestUrl?.queryParameter("cursor_id") == null) alertPage(alertJson("a-1")) else alertPage()
            }
        }

        assertNull(inactivity.refreshInactivityAlertsWhole())
        assertTrue(alertsHere().isEmpty())
    }

    @Test
    fun `the Alerts screen's refresh reads the same lists`() = runTest {
        server.enqueue(rulePage(ruleJson("r-1")))
        assertEquals(listOf("r-1"), rules.refreshAlertRules().getOrThrow().map { it.id })
        server.enqueue(alertPage(alertJson("a-1")))
        server.enqueue(alertPage())
        assertEquals(listOf("a-1"), inactivity.refreshInactivityAlerts().getOrThrow().map { it.id })
        assertEquals(20, db.alertRuleDao().observeAll().first().single().batteryThreshold)
    }
}
