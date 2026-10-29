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

import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.domain.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A database this phone can't open (a Keystore refusal that may pass, a locked phone, its secure
 * storage) doesn't crash a cold start. Room throws the open's failure into every flow's collector,
 * and the main screen's flows are collected with no handler (eight such sites). Each of the eight
 * shared repository flows they read shows nothing instead and looks again by itself, and
 * MainActivity says why, once, in plain words. ColdStartUnopenableDatabaseTest has the whole
 * start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseUnavailableTest {

    @Before
    fun setup() = DatabaseUnavailable.reset()

    @After
    fun tearDown() = DatabaseUnavailable.reset()

    private val refused = DatabaseKeyRefusedException(java.security.KeyStoreException("busy"))

    @Test
    fun `a flow whose database can't be opened shows nothing, says why, and reads once it opens`() = runTest {
        var tries = 0
        val failures = mutableListOf<Throwable?>()
        val rows = flow { if (tries++ == 0) throw refused; emit(listOf("a")) }
            .orWhenUnopenable(emptyList())
        val seen = rows.take(2).toList().also { failures += DatabaseUnavailable.failure.value }
        assertEquals(listOf(emptyList(), listOf("a")), seen)
        assertEquals("it looked again after the first wait", DatabaseUnavailable.RETRY_FIRST_MS, currentTime)
        assertEquals("open: nothing to say", listOf<Throwable?>(null), failures)
    }

    /** [e] is [of], or a copy of it a coroutine's stack trace recovery made (which keeps it as its cause). */
    private fun isOrCopies(e: Throwable?, of: Throwable) = generateSequence(e) { it.cause }.any { it === of }

    @Test
    fun `the database not opening, bare or wrapped once, shows nothing and looks again - a bug surfaces, unsaid`() = runTest {
        val family = listOf(
            refused,
            DeviceLockedException(java.security.KeyStoreException("locked")),
            SecureStorageUnavailableException(java.security.ProviderException("no key")),
            // The passphrase's store, and the session's, refusing.
            SecurityException("Cannot initialize secure storage"),
            android.database.sqlite.SQLiteException("disk I/O error"),
            // A seal that opened the wrong database, and SQLCipher's own that isn't an SQLiteException.
            net.zetetic.database.sqlcipher.SQLiteNotADatabaseException("file is not a database"),
            net.zetetic.database.CursorWindowAllocationException("no window"),
        )
        for (cause in family) for (e in listOf(cause, RuntimeException("Room's", cause))) {
            DatabaseUnavailable.reset()
            var tries = 0
            var reason: Throwable? = null
            val rows = flow { if (tries++ == 0) throw e; emit(listOf("a")) }
                .orWhenUnopenable(emptyList())
                .onEach { if (it.isEmpty()) reason = DatabaseUnavailable.failure.value }
            assertEquals("$e", listOf(emptyList(), listOf("a")), rows.take(2).toList())
            assertTrue("$e: said why", isOrCopies(reason, e))
        }
        // A bug in a flow isn't the database not opening: it surfaces, unsaid.
        for (bug in listOf(IllegalStateException("a bug"), RuntimeException(IllegalArgumentException("a bug")))) {
            DatabaseUnavailable.reset()
            // first(): a guard that took it for the database would hand back the empty list, at once.
            val thrown = runCatching { flow<List<String>> { throw bug }.orWhenUnopenable(emptyList()).first() }.exceptionOrNull()
            assertTrue("$bug surfaces: $thrown", isOrCopies(thrown, bug))
            assertNull(DatabaseUnavailable.failure.value)
        }
    }

    @Test
    fun `one row only looks again while the database can't be opened - nothing in the meantime, never a null that reads as gone`() = runTest {
        var tries = 0
        var saidWhileShut: Throwable? = null
        val row = flow<String?> {
            if (tries++ < 2) throw refused
            saidWhileShut = DatabaseUnavailable.failure.value
            emit("the circle")
        }.retryWhenUnopenable()
        assertEquals("nothing until it read", listOf<String?>("the circle"), row.toList())
        assertSame("said why while it couldn't", refused, saidWhileShut)
        assertEquals("looked again twice: 2 s, then 4 s", DatabaseUnavailable.RETRY_FIRST_MS * 3, currentTime)
        assertNull("open: nothing to say", DatabaseUnavailable.failure.value)
        // A bug surfaces.
        val bug = IllegalStateException("a bug")
        assertTrue(isOrCopies(runCatching { flow<String?> { throw bug }.retryWhenUnopenable().first() }.exceptionOrNull(), bug))
        // The circle a screen shows is read that way.
        val circle = File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        assertTrue(circle.contains("fun observeCircle(circleId: String): Flow<CircleEntity?> = circleDao.observeById(circleId).retryWhenUnopenable()"))
    }

    @Test
    fun `the reason stays until the database opens`() = runTest {
        val shut = flow<List<String>> { throw refused }.orWhenUnopenable(emptyList())
        assertEquals(emptyList<String>(), shut.first())
        assertSame(refused, DatabaseUnavailable.failure.value)
        // Another failure, from another flow, doesn't replace the first.
        DatabaseUnavailable.report(IllegalStateException("another"))
        assertSame(refused, DatabaseUnavailable.failure.value)
        assertEquals(listOf("a"), flowOf(listOf("a")).orWhenUnopenable(emptyList()).first())
        assertNull(DatabaseUnavailable.failure.value)
    }

    @Test
    fun `said once - never again in this process, so one flow that keeps failing can't repeat it`() {
        assertFalse("nothing to say", DatabaseUnavailable.takeUnsaid())
        DatabaseUnavailable.report(refused)
        assertTrue(DatabaseUnavailable.takeUnsaid())
        assertFalse(DatabaseUnavailable.takeUnsaid())
        DatabaseUnavailable.opened()
        DatabaseUnavailable.report(refused)
        assertFalse(DatabaseUnavailable.takeUnsaid())
    }

    @Test
    fun `it looks again after 2 seconds, then twice as long each time, to a minute`() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            listOf(0L, 1L, 2L, 3L, 4L, 5L, 50L).map { DatabaseUnavailable.retryAfterMs(it) },
        )
    }

    @Test
    fun `a flow that opens is as it was`() = runTest {
        assertEquals(listOf(listOf("a")), flowOf(listOf("a")).orWhenUnopenable(emptyList()).toList())
        assertNull(DatabaseUnavailable.failure.value)
    }

    @Test
    fun `the unread count with a database that can't be opened is none - no crash`() = runTest {
        val dao = mockk<NotificationDao> { every { observeUnreadCount() } returns flow { throw refused } }
        val repository = NotificationRepository(mockk(relaxed = true), dao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        assertEquals(0, repository.observeUnreadCount().first())
        assertSame(refused, DatabaseUnavailable.failure.value)
    }

    @Test
    fun `the eight flows a cold start reads all show nothing rather than crashing`() {
        fun src(path: String) = File("src/main/java/com/pingolin/track/$path").readText()
        fun body(file: String, signature: String) = src(file).substringAfter(signature).substringBefore("\n\n")
        val sites = listOf(
            "domain/repository/CircleRepository.kt" to "fun observeCircles(): Flow<List<CircleEntity>>",
            "domain/repository/SosRepository.kt" to "fun observeActiveAlerts(): Flow<List<SosAlertEntity>>",
            "domain/repository/SosRepository.kt" to "fun observeMappableAlerts(): Flow<List<SosAlertEntity>>",
            "domain/repository/NotificationRepository.kt" to "fun observeUnreadCount(): Flow<Int>",
            "domain/repository/PlaceRepository.kt" to "fun observePersonalPlaces(): Flow<List<PlaceEntity>>",
            "domain/repository/PlaceRepository.kt" to "fun observePlacesByCircleIncludingShared(circleId: String): Flow<List<PlaceEntity>>",
            "domain/repository/PlaceRepository.kt" to "fun observeAlertedPlaceIds(): Flow<List<String>>",
            "domain/repository/LocationRepository.kt" to "fun observeLatestLocationsByCircle(circleId: String): Flow<List<LocationEntity>>",
        )
        for ((file, signature) in sites) {
            assertTrue("$file $signature", body(file, signature).contains(".orWhenUnopenable("))
        }
        // Said once, by the main screen, and taken down when it opens.
        val main = src("ui/main/MainActivity.kt")
        val say = main.substringAfter("private fun sayWhenDatabaseUnavailable()").substringBefore("\n    }\n")
        assertTrue(say.contains("DatabaseUnavailable.failure"))
        assertTrue(say.contains("} else if (DatabaseUnavailable.takeUnsaid()) {"))
        assertTrue("its own line: no \"try again\"", say.contains(".setMessage(R.string.database_unavailable_note)"))
        assertFalse(say.contains("DATABASE_KEY_REFUSED_TEXT"))
        assertTrue(say.contains("databaseUnavailableNote?.dismiss()"))
        assertTrue("started with the main screen", main.substringAfter("private fun observeViewModel()").substringBefore("\n    }\n").contains("sayWhenDatabaseUnavailable()"))
        // A tapped SOS notification at a cold start: no row, never a crash.
        val zoom = src("ui/main/MainViewModel.kt").substringAfter("suspend fun zoomToSos(").substringBefore("\n    }\n")
        assertEquals(2, Regex("""runCatching \{ sosRepository.getAlert\(sosId\) \}""").findAll(zoom).count())
        // Notify my circle with no circles to read: NoCircleToNotifyTest.
    }
}
