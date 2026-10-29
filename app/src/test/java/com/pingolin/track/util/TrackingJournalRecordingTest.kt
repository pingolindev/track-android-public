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

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording switch at the top of the tracking diagnostics dialog.
 *
 * The gate sits inside [TrackingJournal.record] rather than at its four call
 * sites, so this is the one place that decides whether the switch means
 * anything at all. Reading is deliberately NOT gated — switching recording off
 * must not destroy the entries someone opened the dialog to read.
 */
class TrackingJournalRecordingTest {

    /** Minimal in-memory SharedPreferences; the same shape SessionManagerTest uses. */
    private class FakePrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = data.toMap()
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
            @Suppress("UNCHECKED_CAST")
            return data[key] as? Set<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(k: String?, v: String?) = apply { if (k != null) pending[k] = v }
            override fun putStringSet(k: String?, v: Set<String>?) = apply { if (k != null) pending[k] = v }
            override fun putInt(k: String?, v: Int) = apply { if (k != null) pending[k] = v }
            override fun putLong(k: String?, v: Long) = apply { if (k != null) pending[k] = v }
            override fun putFloat(k: String?, v: Float) = apply { if (k != null) pending[k] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) pending[k] = v }
            override fun remove(k: String?) = apply { if (k != null) removed.add(k) }
            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) prefs.data.clear()
                removed.forEach { prefs.data.remove(it) }
                pending.forEach { (k, v) -> prefs.data[k] = v }
            }
        }
    }

    private val prefs = FakePrefs()
    private val journal = TrackingJournal(prefs)

    /** Explicit clocks: record()'s defaults reach for SystemClock. */
    private fun record(event: TrackingEvent = TrackingEvent.STOPPED, wall: Long = 1_000L) {
        journal.record(
            event = event,
            reason = TrackingStopReason.USER_ACTION,
            lastFixAgeMs = 30_000L,
            wallMs = wall,
            elapsedMs = wall,
        )
    }

    @Test
    fun `recording is off on a fresh install`() {
        assertFalse(journal.isRecordingEnabled())
    }

    @Test
    fun `nothing is written while recording is off`() {
        record()
        record(TrackingEvent.STARTED)
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun `entries are written once it is turned on`() {
        journal.setRecordingEnabled(true)
        record()
        assertEquals(1, journal.entries().size)
        assertEquals(TrackingEvent.STOPPED, journal.entries().first().event)
    }

    @Test
    fun `turning it off stops new entries but keeps the old ones`() {
        journal.setRecordingEnabled(true)
        record(wall = 1_000L)

        journal.setRecordingEnabled(false)
        record(TrackingEvent.STARTED, wall = 2_000L)

        // The one someone opened the dialog to read is still there; the one
        // written after the switch went off is not.
        val entries = journal.entries()
        assertEquals(1, entries.size)
        assertEquals(1_000L, entries.first().wallMs)
    }

    @Test
    fun `the switch survives a round trip through prefs`() {
        journal.setRecordingEnabled(true)
        assertTrue(TrackingJournal(prefs).isRecordingEnabled())
    }
}
