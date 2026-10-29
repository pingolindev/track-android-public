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

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import com.pingolin.track.domain.sos.SosDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A raise that hasn't reached the circle is said on the SOS card for as long as it lasts, in this
 * app's words: Cancel while it waits for a connection (it goes by itself), Try again or Close
 * otherwise. Never a three-second toast of the exception's own text ("Unable to resolve host …").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SosNotSentCardTest {

    private fun unsent(why: SosDispatcher.NotSent.Why) = SosDispatcher.NotSent("c-1", why, raisedAt = 0L)

    @Test
    fun `the card shows the button, the countdown, or the raise that didn't go`() {
        raiseCard(null, null).let {
            assertTrue(it.button)
            assertFalse(it.countdown)
            assertNull(it.notSent)
            assertFalse(it.tryAgain)
        }
        // A countdown, a new press, comes first.
        raiseCard(3, unsent(SosDispatcher.NotSent.Why.WAITING)).let {
            assertFalse(it.button)
            assertTrue(it.countdown)
            assertNull(it.notSent)
        }
        raiseCard(null, unsent(SosDispatcher.NotSent.Why.WAITING)).let {
            assertFalse("in place of the button", it.button)
            assertEquals(R.string.sos_not_sent_waiting, it.notSent)
            assertFalse("it goes by itself", it.tryAgain)
            assertEquals(R.string.sos_cancel, it.letGo)
        }
        // Waiting on this phone's session store, not a connection: its own words, Cancel only.
        raiseCard(null, SosDispatcher.NotSent("c-1", SosDispatcher.NotSent.Why.WAITING, raisedAt = 0L, storeRefused = true)).let {
            assertFalse(it.button)
            assertEquals(R.string.sos_not_sent_waiting_store, it.notSent)
            assertFalse("it goes by itself", it.tryAgain)
            assertEquals(R.string.sos_cancel, it.letGo)
        }
        raiseCard(null, unsent(SosDispatcher.NotSent.Why.UNSURE)).let {
            assertFalse(it.button)
            assertEquals(R.string.sos_not_sent_unsure, it.notSent)
            assertTrue(it.tryAgain)
            assertEquals(R.string.sos_not_sent_close, it.letGo)
        }
        raiseCard(null, unsent(SosDispatcher.NotSent.Why.NOT_SENT)).let {
            assertFalse(it.button)
            assertEquals(R.string.sos_not_sent, it.notSent)
            assertTrue(it.tryAgain)
            assertEquals(R.string.sos_not_sent_close, it.letGo)
        }
    }

    @Test
    fun `the words say what happened and what happens next`() {
        val context = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        assertEquals(
            "Not sent yet: no connection. Your circle will be notified as soon as this phone is back online. " +
                "If you need them now, call or text them.",
            context.getString(R.string.sos_not_sent_waiting),
        )
        // Online, waiting on Track: its own words, never "no connection".
        assertEquals(
            "Not sent yet: Track can't send right now. Your circle will be notified as soon as it can. " +
                "If you need them now, call or text them.",
            context.getString(R.string.sos_not_sent_waiting_store),
        )
        // Try again is offered here only with this said plainly: the create has no request
        // id.
        assertEquals(
            "Couldn't tell whether your circle was notified. Trying again may notify them twice. " +
                "If you need them now, call or text them.",
            context.getString(R.string.sos_not_sent_unsure),
        )
        assertEquals(
            "Your circle wasn't notified. Try again, or call or text them yourself.",
            context.getString(R.string.sos_not_sent),
        )
        assertEquals("Try again", context.getString(R.string.sos_not_sent_try_again))
        assertEquals("Close", context.getString(R.string.sos_not_sent_close))
        assertEquals("Cancel", context.getString(R.string.sos_cancel))
        // Outside the app, the same words.
        assertEquals("Not sent yet: no connection", context.getString(R.string.sos_notice_waiting_title))
        assertEquals(
            "Your circle will be notified as soon as this phone is back online. If you need them now, call or text them.",
            context.getString(R.string.sos_notice_waiting_text),
        )
        assertEquals("Not sent yet", context.getString(R.string.sos_notice_waiting_store_title))
        assertEquals(
            "Your circle will be notified as soon as Track can send it. If you need them now, call or text them.",
            context.getString(R.string.sos_notice_waiting_store_text),
        )
        // State first, then the time: a title cut short drops the time, not the state.
        assertEquals("Your circle notification may not have gone · 10:00", context.getString(R.string.sos_notice_unsure_title, "10:00"))
        assertEquals(
            "Couldn't tell whether your circle was notified. Trying again may notify them twice. If you need them now, call or text them.",
            context.getString(R.string.sos_notice_unsure_text),
        )
        assertEquals("Your circle notification wasn't sent · 10:00", context.getString(R.string.sos_notice_not_sent_title, "10:00"))
        assertEquals(
            "Your circle wasn't notified. Try again, or call or text them yourself.",
            context.getString(R.string.sos_notice_not_sent_text),
        )
    }

    @Test
    fun `the notice outside the app says when it was asked, with the date when that wasn't today`() {
        val context = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        val now = System.currentTimeMillis()
        // The framework's clock (DateUtils.isToday) is Robolectric's, which starts in 1970: today is today.
        assertTrue(android.os.SystemClock.setCurrentTimeMillis(now))
        val yesterday = now - android.text.format.DateUtils.DAY_IN_MILLIS
        fun at(time: Long, flags: Int) = android.text.format.DateUtils.formatDateTime(context, time, flags)
        val withDate = android.text.format.DateUtils.FORMAT_SHOW_TIME or
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH
        fun words(why: SosDispatcher.NotSent.Why, raisedAt: Long) =
            com.pingolin.track.domain.sos.noticeWords(context, SosDispatcher.NotSent("c-1", why, raisedAt))

        // Asked yesterday, said today (the 24 h cap, or a restart): a bare "14:05" would read as minutes ago.
        assertEquals(
            context.getString(R.string.sos_notice_not_sent_title, at(yesterday, withDate)),
            words(SosDispatcher.NotSent.Why.NOT_SENT, yesterday).first,
        )
        assertEquals(
            context.getString(R.string.sos_notice_unsure_title, at(yesterday, withDate)),
            words(SosDispatcher.NotSent.Why.UNSURE, yesterday).first,
        )
        assertTrue(
            "the date is in it",
            at(yesterday, withDate).length > at(yesterday, android.text.format.DateUtils.FORMAT_SHOW_TIME).length,
        )
        // Today, the clock time alone.
        assertEquals(
            context.getString(R.string.sos_notice_not_sent_title, at(now, android.text.format.DateUtils.FORMAT_SHOW_TIME)),
            words(SosDispatcher.NotSent.Why.NOT_SENT, now).first,
        )
        assertEquals(context.getString(R.string.sos_notice_not_sent_text), words(SosDispatcher.NotSent.Why.NOT_SENT, now).second)
        // Waiting: no time, it is going now.
        assertEquals(
            context.getString(R.string.sos_notice_waiting_title) to context.getString(R.string.sos_notice_waiting_text),
            words(SosDispatcher.NotSent.Why.WAITING, yesterday),
        )
        // On the session store, not a connection: its own words.
        assertEquals(
            context.getString(R.string.sos_notice_waiting_store_title) to context.getString(R.string.sos_notice_waiting_store_text),
            com.pingolin.track.domain.sos.noticeWords(context, SosDispatcher.NotSent("c-1", SosDispatcher.NotSent.Why.WAITING, yesterday, storeRefused = true)),
        )
    }

    @Test
    fun `the card sits beside the button, hidden until needed, and the screen draws and wires it`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        val root = android.view.LayoutInflater.from(activity).inflate(R.layout.fragment_sos, null)
        val card = root.findViewById<android.view.ViewGroup>(R.id.notSentContainer)
        assertEquals(View.GONE, card.visibility)
        assertSame("in the SOS card, where the button is", root.findViewById<View>(R.id.btnSos).parent, card.parent)
        for (id in listOf(R.id.tvNotSent, R.id.btnNotSentTryAgain, R.id.btnNotSentLetGo)) {
            assertSame(card, root.findViewById<View>(id).parent)
        }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, root.findViewById<View>(R.id.tvNotSent).accessibilityLiveRegion)

        val fragment = File("src/main/java/com/pingolin/track/ui/sos/SosFragment.kt").readText()
        assertTrue(fragment.contains("kotlinx.coroutines.flow.combine(viewModel.countdown, viewModel.notSent)"))
        assertTrue(fragment.contains("val card = raiseCard(seconds, notSent)"))
        assertTrue(fragment.contains("binding.btnSos.visibility = if (card.button) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("binding.notSentContainer.visibility = if (card.notSent != null) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("binding.btnNotSentTryAgain.visibility = if (card.tryAgain) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("binding.btnNotSentTryAgain.setOnClickListener { viewModel.tryAgainNotSent() }"))
        assertTrue(fragment.contains("binding.btnNotSentLetGo.setOnClickListener { viewModel.letGoNotSent() }"))
    }
}
