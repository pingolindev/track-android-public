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

package com.pingolin.track.ui.sos

import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.SosDeliveryResponse
import com.pingolin.track.domain.sos.SosDeliveryWatcher.Status
import com.pingolin.track.domain.sos.SosDeliveryWatcher.Verdict
import com.pingolin.track.domain.sos.deliveryWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * As on iOS, a help request that went and reached nobody, or whose checks never answered, is told
 * once outside the Safety tab, with OK only; and the tab's own dialog never reads "Checking…" for
 * good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SosDeliveryNoticeTest {

    private val context = Robolectric.buildActivity(AppCompatActivity::class.java).get()

    private fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    /** [name]'s body in [file], up to its closing brace at [indent]. */
    private fun body(file: String, name: String, indent: String = "    "): String =
        file.substring(file.indexOf(name)).let { it.substring(0, it.indexOf("\n$indent}\n")) }

    private fun words(verdict: Verdict) = deliveryWords(verdict).let { (t, b) -> context.getString(t) to context.getString(b) }

    @Test
    fun `the words - reached nobody, per state, and may not have arrived`() {
        assertEquals(
            "Your circle notification didn't reach anyone" to
                "Nobody else is in this circle, so there was no one to notify. If you need help now, contact someone directly, or notify a different circle.",
            words(Verdict.ALONE),
        )
        assertEquals(
            "Your circle notification didn't reach anyone" to
                "Nobody in this circle has a device we can notify. Contact them another way.",
            words(Verdict.UNDELIVERABLE),
        )
        assertEquals(
            "Your circle notification may not have arrived" to
                "We couldn't find out whether it reached anyone. If you need help now, contact someone directly.",
            words(Verdict.NO_ANSWER),
        )
        // Never "try again" (the same circle gives the same answer), never "confirm" (on these cards it
        // means a device reported arrival), and no SOS.
        for (verdict in Verdict.values()) {
            val (title, body) = words(verdict)
            for (said in listOf(title, body)) {
                assertFalse(said, said.contains("try again", ignoreCase = true))
                assertFalse(said, said.contains("confirm", ignoreCase = true))
                assertFalse(said, said.contains("SOS"))
            }
        }
    }

    @Test
    fun `the tab's dialog says when the checks ended with no answer, never Checking for good`() {
        fun response(state: String) =
            SosDeliveryResponse(recipients = 2, addressable = 2, dispatched = 2, emailed = 0, confirmed = 0, state = state)
        val checking = "Checking whether your circle received it…"
        val noAnswer = "We couldn't find out whether it arrived. If this matters right now, reach someone another way."
        assertEquals(checking, deliveryMessage(context, Status("a", null, stillChecking = true)))
        assertEquals(noAnswer, deliveryMessage(context, Status("a", null, stillChecking = false)))
        // A state still on its way when the checks ended is no answer either (iOS's verdict line).
        assertEquals(checking, deliveryMessage(context, Status("a", response("pending"), stillChecking = true)))
        assertEquals(noAnswer, deliveryMessage(context, Status("a", response("pending"), stillChecking = false)))
        assertEquals(context.getString(R.string.sos_delivery_alone), deliveryMessage(context, Status("a", response("alone"), false)))
        assertEquals(
            context.getString(R.string.sos_delivery_undeliverable),
            deliveryMessage(context, Status("a", response("undeliverable"), false)),
        )
    }

    @Test
    fun `told over any screen of Track until OK, never above the lock, and by notification when Track isn't in front`() {
        val main = source("ui/main/MainActivity.kt")
        val observe = body(main, "private fun observeViewModel()")
        // Every value, on each return and in an activity made again: the dialog shows what's untold,
        // and goes when nothing is (DeliveryVerdictDialogTest has the dialog itself).
        assertTrue(observe.contains(
            "sosDispatcher.get().deliveries.untold\n" +
                "                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)\n" +
                "                .collectLatest { verdict -> deliveryVerdict.render(this@MainActivity, verdict, isAppLocked) }\n"
        ))
        // Told on OK only, not when shown.
        assertTrue(main.contains("DeliveryVerdictDialog { sosDispatcher.get().deliveries.told() }"))
        assertFalse(observe.contains("told()"))
        // In front from start to stop, and not behind the app lock, where it comes back once unlocked.
        assertTrue(body(main, "override fun onStop()").contains("sosDispatcher.get().deliveries.trackInFront = false"))
        val start = body(main, "override fun onStart()")
        assertTrue(start.contains("sosDispatcher.get().deliveries.trackInFront = !isAppLocked"))
        assertTrue(start.contains("if (isAppLocked) deliveryVerdict.dismiss()"))
        val unlock = body(main, "private fun onUnlockSuccess()")
        assertTrue(unlock.contains("deliveries.trackInFront = true"))
        assertTrue(unlock.contains("deliveryVerdict.render(this, deliveries.untold.value, locked = false)"))
        assertTrue(body(main, "override fun onDestroy()").contains("deliveryVerdict.dismiss()"))

        // The Safety tab's own dialog, while it is on screen (started: a screen over it can pause it).
        val fragment = source("ui/sos/SosFragment.kt")
        assertTrue(body(fragment, "override fun onStart()").contains("viewModel.safetyTabShown(true)"))
        assertTrue(body(fragment, "override fun onStop()").contains("viewModel.safetyTabShown(false)"))
        assertFalse(fragment.contains("override fun onResume()"))
        assertTrue(fragment.contains(".setPositiveButton(android.R.string.ok) { _, _ -> viewModel.clearDelivery() }"))
        val model = source("ui/sos/SosViewModel.kt")
        assertTrue(model.contains("fun clearDelivery() = sosDispatcher.deliveries.putAway()"))
        assertTrue(model.contains("val delivery: StateFlow<com.pingolin.track.domain.sos.SosDeliveryWatcher.Status?> = sosDispatcher.deliveries.status"))
        assertFalse("one set of checks, the dispatcher's", model.contains("getSosDelivery"))
        // A request the raiser closed: nothing more is said of it.
        assertEquals(2, Regex("""if \(result\.isSuccess\) sosDispatcher\.deliveries\.stop\(alert\.id\)""").findAll(model).count())

        // The notification: the general alerts channel, with its sound and never through
        // Do Not Disturb (the SOS channel is for others' emergencies); gone once tapped,
        // opening the Safety tab; taken down when its request is over.
        val outside = source("domain/sos/SosRaiseOutside.kt")
        val notice = body(outside, "override fun deliveryNotice(")
        assertTrue(notice.contains("NotificationCompat.Builder(context, TrackApplication.ALERTS_CHANNEL_ID)"))
        assertFalse(notice.contains("SOS_CHANNEL_ID"))
        assertTrue(notice.contains("deliveryWords(verdict)"))
        assertFalse(notice.contains("setSilent"))
        assertTrue(notice.contains(".setAutoCancel(true)"))
        assertTrue(notice.contains("putExtra(\"navigate_to\", \"sos\")"))
        assertTrue(body(outside, "override fun cancelDeliveryNotice()").contains(".cancel(DELIVERY_NOTICE_ID)"))
        assertTrue(source("domain/sos/SosDispatcher.kt").contains("{ outside.deliveryNotice(it) }, { outside.cancelDeliveryNotice() },"))
    }
}
