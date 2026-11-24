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

package com.pingolin.track.ui.alerts.history

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.AlertEventResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.AlertHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * A missed check-in in the History tab: one this phone raised says its notification's words; one
 * another device of the account reported is read from its sealed type ("missed_check_in") and
 * titled the same; a type that doesn't open, or that this build doesn't know, stays a plain alert.
 * And a past check-in of a watched person beside them: "{name} checked in" / "You checked in".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MissedCheckInHistoryTest {

    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Track)

    /** The row's title and message as shown. */
    private fun shown(item: AlertEventItem): Pair<String, String> {
        val holder = AlertEventAdapter {}.onCreateViewHolder(FrameLayout(context), 0)
        holder.bind(item)
        fun text(id: Int) = holder.itemView.findViewById<TextView>(id).text.toString()
        return text(R.id.tvTitle) to text(R.id.tvMessage)
    }

    private fun item(type: String, message: String = "", circle: String? = null, name: String? = null, hours: Int? = null, due: Int? = null) =
        AlertEventItem(
            id = "e-1", type = type, message = message, dayBucket = 20000, isAcknowledged = false, circleName = circle,
            timestampMillis = 1_800_000_000_000L, memberName = name, timeoutHours = hours, dueMinute = due,
        )

    @Test
    fun `one raised here says its notification's words, with its circle`() {
        assertEquals(
            "Missed check-in" to "Bo hasn't checked in for 4 hours. · Family",
            shown(item(AlertHandler.TYPE_MISSED_CHECK_IN, circle = "Family", name = "Bo", hours = 4)),
        )
    }

    @Test
    fun `a scheduled rule's says its time as set, not hours`() {
        val nine = com.pingolin.track.util.CheckInRuleText.time(context, 21 * 60)
        assertEquals(
            "Missed check-in" to "Bo hasn't checked in by $nine. · Family",
            shown(item(AlertHandler.TYPE_MISSED_CHECK_IN, circle = "Family", name = "Bo", hours = 1, due = 21 * 60)),
        )
        // The history's rows from this phone's lapses carry it (AlertEventsViewModel.localEventItems).
        val model = java.io.File("src/main/java/com/pingolin/track/ui/alerts/history/AlertEventsViewModel.kt").readText()
        assertEquals(true, model.contains("dueMinute = e.dueMinute,"))
    }

    @Test
    fun `one another device reported is titled the same, and says no words it doesn't have`() {
        assertEquals("Missed check-in" to "", shown(item(AlertHandler.TYPE_MISSED_CHECK_IN)))
        assertEquals("Missed check-in" to "Family", shown(item(AlertHandler.TYPE_MISSED_CHECK_IN, circle = "Family")))
    }

    @Test
    fun `a type that didn't open, or isn't known here, is a plain alert, and the others read as before`() {
        assertEquals("Alert" to "", shown(item("alert_rule")))
        assertEquals("Alert" to "", shown(item("geofence")))
        assertEquals("Inactivity Alert" to "Bo: no location for 4h · Family", shown(item("inactivity", "Bo: no location for 4h", "Family")))
        assertEquals("Low Battery Alert" to "", shown(item("low_battery")))
    }

    /** A past check-in row as shown: icon, title, message, status visibility. */
    private fun checkInRow(holder: AlertEventAdapter.AlertEventViewHolder, item: AlertEventItem): List<Any> {
        holder.bind(item)
        fun text(id: Int) = holder.itemView.findViewById<TextView>(id).text.toString()
        return listOf(text(R.id.tvIcon), text(R.id.tvTitle), text(R.id.tvMessage), holder.itemView.findViewById<TextView>(R.id.tvStatus).visibility)
    }

    private fun pastCheckIn(type: String, name: String?, own: Boolean = false, circle: String? = "Family") = AlertEventItem(
        id = "check-in:k-1", type = AlertHandler.TYPE_CHECK_IN, message = "", dayBucket = 20000, isAcknowledged = false,
        circleName = circle, timestampMillis = 1_800_000_000_000L, memberName = name, checkInType = type, ownCheckIn = own,
    )

    @Test
    fun `a past check-in says whose, its kind when it isn't the plain one, and where, with no status`() {
        val holder = AlertEventAdapter {}.onCreateViewHolder(FrameLayout(context), 0)
        assertEquals(listOf("💬", "Alice checked in", "Family", android.view.View.GONE), checkInRow(holder, pastCheckIn("general", "Alice")))
        assertEquals(listOf("🏠", "You checked in", "Arrived · Work", android.view.View.GONE), checkInRow(holder, pastCheckIn("arrived", "Me", own = true, circle = "Work")))
        assertEquals(listOf("✅", "Alice checked in", "I'm Safe · Family", android.view.View.GONE), checkInRow(holder, pastCheckIn("safe_arrival", "Alice")))
        assertEquals("a name not readable yet", "A member checked in", checkInRow(holder, pastCheckIn("general", null))[1])
        assertTrue("its time, as the history says times", holder.itemView.findViewById<TextView>(R.id.tvTime).text.isNotBlank())

        // The same row holding an alert again shows its status.
        holder.bind(item(AlertHandler.TYPE_MISSED_CHECK_IN, circle = "Family", name = "Bo", hours = 4))
        assertEquals(android.view.View.VISIBLE, holder.itemView.findViewById<TextView>(R.id.tvStatus).visibility)
    }

    @Test
    fun `the line for check-ins that couldn't be loaded`() {
        val line = android.view.LayoutInflater.from(context).inflate(R.layout.fragment_alert_events, null)
            .findViewById<TextView>(R.id.tvCheckInsFailed)
        assertEquals("Check-ins couldn't be loaded.", line.text.toString())
        assertEquals("only when they couldn't", android.view.View.GONE, line.visibility)
    }

    @Test
    fun `a reported firing's time opens with this account's own key, and one that doesn't is no time`() {
        val crypto = mockk<CryptoManager>()
        every { crypto.decryptData(LocationRepository.SELF_CIRCLE_UUID, EncryptedData("n", "at")) } returns "2026-11-23T10:15:30.123Z"
        every { crypto.decryptData(LocationRepository.SELF_CIRCLE_UUID, EncryptedData("n", "not a time")) } returns "yesterday"
        every { crypto.decryptData(LocationRepository.SELF_CIRCLE_UUID, EncryptedData("n", "other key")) } throws IllegalStateException("no key")
        val repository = AlertRuleRepository(mockk(), mockk(), mockk(), crypto, mockk(), mockk())
        fun event(sealedAt: String) = AlertEventResponse(
            id = "e-1", alertRuleId = "r-1", encryptedUserId = EncryptedPayload("n", "u"), encryptedTargetUserId = EncryptedPayload("n", "t"),
            alertTypeHash = "", encryptedAlertType = EncryptedPayload("n", "sealed"), message = null, metadata = null,
            dayBucket = 20000, encryptedTriggeredAt = EncryptedPayload("n", sealedAt), acknowledgedDayBucket = null,
            notifiedPush = false, notifiedEmail = false, notifiedSms = false,
        )
        assertEquals(java.time.Instant.parse("2026-11-23T10:15:30.123Z").toEpochMilli(), repository.triggeredAtOf(event("at")))
        assertNull(repository.triggeredAtOf(event("not a time")))
        assertNull(repository.triggeredAtOf(event("other key")))
    }

    @Test
    fun `the sealed type opens with this account's own key, and one that doesn't open is no type`() {
        val crypto = mockk<CryptoManager>()
        every { crypto.decryptData(LocationRepository.SELF_CIRCLE_UUID, EncryptedData("n", "sealed")) } returns "missed_check_in"
        every { crypto.decryptData(LocationRepository.SELF_CIRCLE_UUID, EncryptedData("n", "other key")) } throws IllegalStateException("no key")
        val repository = AlertRuleRepository(mockk(), mockk(), mockk(), crypto, mockk(), mockk())
        fun event(ciphertext: String) = AlertEventResponse(
            id = "e-1", alertRuleId = "r-1", encryptedUserId = EncryptedPayload("n", "u"), encryptedTargetUserId = EncryptedPayload("n", "t"),
            alertTypeHash = "", encryptedAlertType = EncryptedPayload("n", ciphertext), message = null, metadata = null,
            dayBucket = 20000, encryptedTriggeredAt = EncryptedPayload("n", "at"), acknowledgedDayBucket = null,
            notifiedPush = false, notifiedEmail = false, notifiedSms = false,
        )
        assertEquals(AlertHandler.TYPE_MISSED_CHECK_IN, repository.alertTypeOf(event("sealed")))
        assertNull(repository.alertTypeOf(event("other key")))
    }
}
