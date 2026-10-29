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

package com.pingolin.track.ui.alerts

import com.google.gson.JsonParser
import com.pingolin.track.data.local.dao.AlertRuleDao
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CurrentKeyGate
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * Low-battery and inactivity rules notify by Push only, and the editor must not
 * silence a rule stored with e-mail only. On Robolectric: the rule's
 * sealed conditions are built with org.json, a stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class RuleChannelsTest {

    @Test
    fun `an e-mail-only rule opens with Push on, so a save keeps it notifying`() {
        assertTrue(RuleChannels.pushShown(notifyPush = false, notifyEmail = true))
        assertTrue(RuleChannels.pushShown(notifyPush = true, notifyEmail = false))
        assertTrue(RuleChannels.pushShown(notifyPush = true, notifyEmail = true))
        assertFalse("a rule that notified nobody still notifies nobody", RuleChannels.pushShown(notifyPush = false, notifyEmail = false))
    }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "POST" && request.path == "/api/v1/alert-rules") {
                    MockResponse().setResponseCode(200).setBody("""{"success":true,"data":"rule-1"}""")
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"success":false,"error":"Not found"}""")
                }
        }
        startOnIpv4Loopback()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    /**
     * The server's create-rule request requires `notify_email`, a bool: a create without it
     * is a 422 against every server out there. So with no Email box, a create must still
     * send `"notify_email": false` on the wire.
     */
    @Test
    fun `a new battery rule still says notify_email false on the wire`() = runTest {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)
        val crypto = mockk<CryptoManager>(relaxed = true) {
            every { encryptData(any(), any()) } returns EncryptedData("bm9uY2U=", "Y2lwaGVy")
        }
        val gate = mockk<CurrentKeyGate> { coEvery { requireCurrentKey("c-1") } returns Unit }
        val session = mockk<SessionManager>(relaxed = true) { every { getUserId() } returns "me" }
        val rules = AlertRuleRepository(api, mockk<AlertRuleDao>(relaxed = true), session, crypto, gate, pendingChangesIn(InMemoryPendingChangeDao()))

        // What AlertRulesViewModel.createLowBatteryAlert sends for every new rule.
        assertTrue(rules.createLowBatteryAlert("c-1", "sam", 15, notifyPush = true, notifyEmail = false).isSuccess)

        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertTrue("notify_email must be on the wire: $body", body.has("notify_email"))
        assertEquals(false, body["notify_email"].asBoolean)
        assertEquals(true, body["notify_push"].asBoolean)
    }
}
