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

package com.pingolin.track.ui.main

import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.NotificationRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.ui.map.MapViewModel
import com.pingolin.track.ui.places.PlacesViewModel
import com.pingolin.track.ui.sos.SosViewModel
import com.pingolin.track.util.DatabaseKeyRefusedException
import com.pingolin.track.util.DatabaseUnavailable
import com.pingolin.track.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.WildcardType

/**
 * A cold start while the database can't be opened doesn't crash. Room throws the open's failure
 * into every flow's collector and from every other call; here every DAO does that, under the real
 * repositories and the real view models of the main screen, the map, Places and the Safety tab,
 * started and subscribed as those screens do at a start. Nothing escapes: runTest fails on any
 * uncaught exception, viewModelScope's among them. Every list is empty, the line is said once,
 * and when the database opens every screen fills in by itself, the Places circles too, which are
 * shared for the screen's whole life.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartUnopenableDatabaseTest {

    private companion object {
        const val ME = "0e2c0c55-6b55-4c33-9a0b-9f3c5c1c0a11"
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
    }

    private val refused = DatabaseKeyRefusedException(java.security.KeyStoreException("busy"))
    private val circle = CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)

    /** The refusal passed: flows read (the circle list has [circle], the rest nothing); other calls still throw. */
    @Volatile private var opens = false

    /** A DAO as Room's is while the database can't be opened: a flow throws when collected, any other call at once. */
    private fun <T : Any> dao(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> "unopenable ${type.simpleName}"
                }
                Flow::class.java.isAssignableFrom(method.returnType) -> flow<Any?> {
                    if (!opens) throw refused
                    emit(readOf(type, method))
                    awaitCancellation()
                }
                else -> throw refused
            }
        }
    )

    private inline fun <reified T : Any> dao(): T = dao(T::class.java)

    private fun readOf(type: Class<*>, method: Method): Any? {
        if (type == CircleDao::class.java && method.name == "observeAll") return listOf(circle)
        val of = (method.genericReturnType as ParameterizedType).actualTypeArguments[0]
        val raw = when (of) {
            is ParameterizedType -> of.rawType
            is WildcardType -> (of.upperBounds[0] as? ParameterizedType)?.rawType ?: of.upperBounds[0]
            else -> of
        }
        return when (raw) {
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            Int::class.javaObjectType -> 0
            Long::class.javaObjectType -> 0L
            Boolean::class.javaObjectType -> false
            else -> null
        }
    }

    private val session = mockk<SessionManager>(relaxed = true).also {
        every { it.isLoggedIn() } returns true
        every { it.getUserId() } returns ME
        every { it.getDefaultCircleId() } returns CIRCLE
    }
    private val api = mockk<TrackApi>(relaxed = true)
    private val crypto = mockk<CryptoManager>(relaxed = true)

    private lateinit var circles: CircleRepository
    private lateinit var locations: LocationRepository
    private lateinit var sos: SosRepository
    private lateinit var places: PlaceRepository
    private lateinit var notifications: NotificationRepository
    private lateinit var pending: com.pingolin.track.domain.pending.PendingChanges
    private lateinit var alertRules: com.pingolin.track.domain.repository.AlertRuleRepository
    private lateinit var inactivity: com.pingolin.track.domain.repository.InactivityAlertRepository

    @Before
    fun setup() {
        DatabaseUnavailable.reset()
        locations = LocationRepository(
            locationProvider = mockk(relaxed = true), locationDao = dao(), pendingLocationDao = dao(), circleDao = dao(),
            privacySettingDao = dao(), tracksApi = api, authApi = mockk(relaxed = true), sessionManager = session,
            tokenRefresher = mockk(relaxed = true), cryptoManager = crypto, uxEventBus = mockk(relaxed = true),
            queueMonitor = mockk(relaxed = true), circleRepository = dagger.Lazy { circles },
            boundaryRepository = dagger.Lazy { mockk(relaxed = true) }, placeAlertEvaluator = mockk(relaxed = true),
            currentKeyGate = mockk(relaxed = true), queuedCircleLocationDao = dao(), approximateMembers = mockk(relaxed = true),
            newestOwnFix = mockk(relaxed = true), pauseIntervals = mockk(relaxed = true), approximateIntervals = mockk(relaxed = true),
        )
        circles = CircleRepository(
            previousIdentityLoader = mockk(relaxed = true), context = mockk(relaxed = true), tracksApi = api,
            circleDao = dao(), circleSortOrderDao = dao(), circleMemberDao = dao(), pendingInviteDao = dao(),
            privacySettingDao = dao(), sessionManager = session, cryptoManager = crypto,
            locationRepository = dagger.Lazy { locations }, uxEventBus = mockk(relaxed = true),
            currentKeyGate = mockk(relaxed = true), keyStoreRecovery = mockk(relaxed = true),
            pendingChanges = mockk(relaxed = true), sosRepository = dagger.Lazy { sos },
            pauseIntervals = mockk(relaxed = true), approximateIntervals = mockk(relaxed = true),
        )
        sos = SosRepository(
            tracksApi = api, sosAlertDao = dao(), sessionManager = session, cryptoManager = crypto,
            circleRepository = dagger.Lazy { circles }, currentKeyGate = mockk(relaxed = true), owedWipe = mockk(relaxed = true),
            alertHandler = dagger.Lazy { mockk(relaxed = true) }, circleDao = dao(), circleMemberDao = dao(),
            positions = mockk(relaxed = true),
        )
        places = PlaceRepository(
            tracksApi = api, placeDao = dao(), placeAlertDao = dao(), cryptoManager = crypto, locationRepository = locations,
            sessionManager = session, circleRepository = dagger.Lazy { circles }, pendingChanges = mockk(relaxed = true),
        )
        notifications = NotificationRepository(
            tracksApi = api, notificationDao = dao(), placeDao = dao(), destinationRepository = mockk(relaxed = true),
            notificationNames = mockk(relaxed = true), sessionManager = session,
        )
        pending = com.pingolin.track.domain.pending.PendingChanges(
            dao = dao(), sessionManager = session, scheduler = mockk(relaxed = true), circleDao = dao(), circleMemberDao = dao(),
        )
        alertRules = com.pingolin.track.domain.repository.AlertRuleRepository(
            tracksApi = api, alertRuleDao = dao(), sessionManager = session, cryptoManager = crypto,
            currentKeyGate = mockk(relaxed = true), pendingChanges = pending,
        )
        inactivity = com.pingolin.track.domain.repository.InactivityAlertRepository(
            tracksApi = api, inactivityAlertDao = dao(), localInactivityEventDao = dao(), circleMemberDao = dao(),
            sessionManager = session, cryptoManager = crypto, currentKeyGate = mockk(relaxed = true), pendingChanges = pending,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        DatabaseUnavailable.reset()
    }

    @Test
    fun `a cold start with every DAO throwing doesn't crash - empty screens, said once, filled in once it opens`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val main = MainViewModel(
            context = mockk(relaxed = true), circleRepository = circles, locationRepository = locations, sosRepository = sos,
            configRepository = mockk(relaxed = true), geocodingRepository = mockk(relaxed = true),
            notificationRepository = notifications, tracksApi = api, sessionManager = session,
            destinationRepository = mockk(relaxed = true), hiddenDestinations = mockk(relaxed = true),
        )
        val placesModel = PlacesViewModel(
            placeRepository = places, circleRepository = circles, geocodingRepository = mockk(relaxed = true),
            sessionManager = session, pendingChanges = mockk(relaxed = true),
        )
        val map = MapViewModel(
            locationRepository = locations, circleRepository = circles, placeRepository = places,
            destinationRepository = mockk(relaxed = true), geocodingRepository = mockk(relaxed = true),
            sessionManager = session, privacySettingDao = dao(), hiddenDestinations = mockk(relaxed = true),
            battery = mockk(relaxed = true),
        )
        val safety = SosViewModel(
            sosRepository = sos, circleRepository = circles, locationRepository = locations,
            geocodingRepository = mockk(relaxed = true), privacySettingDao = dao(), sessionManager = session,
            // Nothing pressed: its outcome, as the real one's, never ends.
            sosDispatcher = mockk(relaxed = true) { every { outcome } returns MutableStateFlow(null) },
            notificationNames = mockk(relaxed = true),
        )
        // Their scopes end with the test, a failed check too: a look again that never ends would
        // otherwise keep the test scheduler busy for good.
        try {
            // As MainActivity starts the main screen, and as the screens subscribe.
            main.initialize()
            main.refreshSosAlerts()
            main.recheckPreviousIdentities()
            val lists = listOf(
                main.circles, main.activeAlerts, main.mappableSosAlerts, placesModel.circles,
                map.places, map.memberLocations, safety.circles, safety.alertRows,
            )
            (lists + listOf(main.unreadNotificationCount, main.selectedCircle)).forEach { shown ->
                backgroundScope.launch { shown.collect {} }
            }
            // MapFragment's own collector of the alerted places, with no view model between.
            val alerted = mutableListOf<List<String>>()
            backgroundScope.launch { places.observeAlertedPlaceIds().collect { alerted += it } }
            advanceTimeBy(1_000)
            runCurrent()

            lists.forEach { assertEquals(emptyList<Any>(), it.value) }
            assertEquals(0, main.unreadNotificationCount.value)
            assertNull(main.selectedCircle.value)
            assertEquals(listOf(emptyList<String>()), alerted)
            assertSame(refused, DatabaseUnavailable.failure.value)
            assertTrue("said once", DatabaseUnavailable.takeUnsaid())
            assertFalse(DatabaseUnavailable.takeUnsaid())

            // The refusal passes: every screen fills in by itself, with no restart.
            opens = true
            advanceTimeBy(DatabaseUnavailable.RETRY_MAX_MS)
            runCurrent()
            assertEquals(listOf(circle), main.circles.value)
            assertEquals("shared for the screen's whole life", listOf(circle), placesModel.circles.value)
            assertEquals("Notify my circle has its circle again", listOf(circle), safety.circles.value)
            assertEquals("the default circle", circle, main.selectedCircle.value)
            assertNull(DatabaseUnavailable.failure.value)
            assertFalse("never said again", DatabaseUnavailable.takeUnsaid())
        } finally {
            listOf(main, placesModel, map, safety).forEach { it.viewModelScope.cancel() }
        }
    }

    /**
     * Each tab a tap away from the main screen, opened while every DAO throws. Places (its list
     * and its waiting changes), Circles, the bell and the three Alerts lists show nothing and
     * never crash, and fill in once the database opens. None of these flows may reach its view
     * model's scope unguarded.
     */
    @Test
    fun `each tab with every DAO throwing doesn't crash - Places, Circles, the bell and the Alerts lists show nothing, then fill in`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val placesTab = PlacesViewModel(
            placeRepository = places, circleRepository = circles, geocodingRepository = mockk(relaxed = true),
            sessionManager = session, pendingChanges = pending,
        )
        val circlesTab = com.pingolin.track.ui.circles.CirclesViewModel(
            circleRepository = circles, placeRepository = places, tracksApi = api, sessionManager = session,
            locationConsentRepository = mockk(relaxed = true), pendingChanges = pending,
        )
        val bell = com.pingolin.track.ui.notifications.NotificationsViewModel(notifications)
        val rules = com.pingolin.track.ui.alerts.rules.AlertRulesViewModel(
            alertRuleRepository = alertRules, circleRepository = circles, pendingChanges = pending,
        )
        val inactive = com.pingolin.track.ui.alerts.inactivity.InactivityAlertsViewModel(
            inactivityAlertRepository = inactivity, circleRepository = circles,
        )
        val history = com.pingolin.track.ui.alerts.history.AlertEventsViewModel(
            alertRuleRepository = alertRules, inactivityAlertRepository = inactivity, circleRepository = circles,
        )
        try {
            val lists = listOf(
                placesTab.places, placesTab.pending.items,
                circlesTab.circles, circlesTab.pending.items, circlesTab.pendingQuestions,
                bell.notifications,
                rules.alertRules, rules.pending.items, rules.circles,
                inactive.inactivityAlerts, inactive.circles,
                history.alertRules, history.inactivityAlerts, history.localEventItems, history.circles,
            )
            (lists + listOf(circlesTab.rekeyWarnings, circlesTab.pendingLines, circlesTab.showEmptyState)).forEach { shown ->
                backgroundScope.launch { shown.collect {} }
            }
            advanceTimeBy(1_000)
            runCurrent()

            lists.forEach { assertEquals(emptyList<Any>(), it.value) }
            assertSame(refused, DatabaseUnavailable.failure.value)

            // The refusal passes: each tab fills in by itself.
            opens = true
            advanceTimeBy(DatabaseUnavailable.RETRY_MAX_MS)
            runCurrent()
            for (tab in listOf(circlesTab.circles, rules.circles, inactive.circles, history.circles)) {
                assertEquals(listOf(circle), tab.value)
            }
            assertNull(DatabaseUnavailable.failure.value)
        } finally {
            listOf(placesTab, circlesTab, bell, rules, inactive, history).forEach { it.viewModelScope.cancel() }
        }
    }
}
