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

import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.dto.SosDeliveryResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.domain.sos.SosDispatcher
import com.pingolin.track.domain.sos.SosPositionChooser
import com.pingolin.track.domain.sos.SosSeal
import com.pingolin.track.util.SessionManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import uniffi.pingolin_mobile.GeocodingResult

/**
 * Unit tests for SosViewModel.
 *
 * Tests cover:
 * - 5-second SOS countdown
 * - Countdown cancellation
 * - Immediate trigger (bypass countdown)
 * - Trigger requires location
 * - Acknowledge/resolve/cancel state transitions
 * - Ownership check (isMyAlert), in the raiser id's one spelling
 * - Nobody but the raiser closes a request, the circle's owner and
 *   admins included (alerts are personal)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var sosRepository: SosRepository

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var geocodingRepository: GeocodingRepository

    @MockK
    private lateinit var privacySettingDao: PrivacySettingDao

    @MockK
    private lateinit var sessionManager: SessionManager

    private lateinit var viewModel: SosViewModel
    private lateinit var sosDispatcher: SosDispatcher
    private val positions = mockk<SosPositionChooser>()

    /** The exact position a fix at [lat], [lng] seals as here. */
    private fun at(lat: Double, lng: Double) = SosSeal.Point(lat, lng, 0L, null)

    private val me = "5b1f0d2e-7c44-4a7e-9d61-3f2a8c0e1b11"
    private val someoneElse = "c0ffee00-1d2b-4c3a-8e9f-0a1b2c3d4e5f"

    private val testAlert = SosAlertEntity(
        id = "alert-1",
        circleId = "circle-1",
        userId = me,
        status = "active",
        latitude = 37.0,
        longitude = -122.0,
        acknowledgedBy = null,
        createdAt = 0L,
        resolvedAt = null
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        // The roster this user's own ROLE comes from. Empty by default: the
        // circle list alone carries only `isOwner`, so with no roster nobody is
        // an admin of anything.
        every { circleRepository.observeMyMemberships() } returns flowOf(emptyList())
        every { sosRepository.observeActiveAlerts() } returns flowOf(emptyList())
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } returns null
        // Nobody signed in, and nobody to name for who asked, unless a test
        // says so.
        every { sessionManager.getUserId() } returns null
        coEvery { notificationNames.namesFor(any(), any(), any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names()

        // A successful trigger starts polling for the delivery outcome, so every
        // test that gets as far as Triggered drives this too. Default to a
        // terminal state: the poll loop stops on the first answer instead of
        // running its full 60s window through advanceUntilIdle().
        coEvery { sosRepository.getSosDelivery(any()) } returns SosDeliveryResponse(
            recipients = 1,
            addressable = 1,
            dispatched = 1,
            emailed = 0,
            confirmed = 1,
            state = "confirmed",
        )

        // Application-scoped in production; here it runs on the test scheduler
        // so advanceTimeBy drives the countdown. One instance shared by every
        // ViewModel this test builds, exactly as the @Singleton is in the app.
        // What the raise seals is SosPositionChooser's (its own tests): here, the press's fix as an
        // exact position when it came in, else the pending marker.
        coEvery { positions.forRaise(any(), any(), any()) } answers {
            thirdArg<Location?>()?.let { at(it.latitude, it.longitude) } ?: SosSeal.PENDING
        }
        every { positions.exact(any()) } returns null
        // After the create, the update window and the key check (SosDispatcherTest): nothing due here.
        // No fix, unless a test gives one: the press asks for one beside every countdown.
        coEvery { locationRepository.getCurrentLocation() } returns null
        coEvery { sosRepository.runOwnSos(any(), any(), any()) } returns SosRepository.OwnRunResult.NOTHING
        every { sosRepository.ownUpdateDue(any()) } returns false
        coEvery { sosRepository.isOpenHere(any()) } returns false
        sosDispatcher = SosDispatcher(
            sosRepository,
            locationRepository,
            positions,
            {},
            {},
            CoroutineScope(testDispatcher),
        )

        viewModel = newViewModel()
    }

    private val notificationNames = mockk<com.pingolin.track.domain.repository.NotificationNames>(relaxed = true)

    private fun newViewModel() = SosViewModel(
        sosRepository,
        circleRepository,
        locationRepository,
        geocodingRepository,
        privacySettingDao,
        sessionManager,
        sosDispatcher,
        notificationNames,
    )

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ---- requestSos: the privacy-override gate -------------------------------
    //
    // Notifying a circle overrides the raiser's privacy settings for it, because
    // the location is sealed with the CIRCLE key and every member decrypts the
    // same ciphertext. These pin the ASKING, which is the whole mitigation: if
    // the gate silently stops firing, someone who paused sharing goes back to
    // broadcasting their exact position with no warning at all.

    private fun privacyRow(
        circleId: String,
        mode: String,
        pausedUntil: Long? = null,
    ) = PrivacySettingEntity(
        circleId = circleId,
        sharingMode = mode,
        pausedUntil = pausedUntil,
        updatedAt = 0L,
    )

    @Test
    fun `requestSos on a paused circle asks first and does not start the countdown`() = runTest {
        coEvery { privacySettingDao.get("circle-1") } returns privacyRow("circle-1", "paused")

        viewModel.requestSos("circle-1")
        runCurrent()

        assertEquals("paused", viewModel.privacyOverride.value?.mode)
        assertEquals("circle-1", viewModel.privacyOverride.value?.circleId)
        assertNull(viewModel.countdown.value)
        assertEquals(SosUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `requestSos treats an expired pause as not paused`() = runTest {
        // pausedUntil in the past: the row still says paused but the pause is
        // over, so this must NOT prompt — a stale row should not stand between
        // someone and a safety feature.
        coEvery { privacySettingDao.get("circle-1") } returns
            privacyRow("circle-1", "exact", pausedUntil = System.currentTimeMillis() - 60_000)

        viewModel.requestSos("circle-1")
        runCurrent()

        assertNull(viewModel.privacyOverride.value)
        assertEquals(SosUiState.Countdown, viewModel.uiState.value)

        viewModel.cancelCountdown()
        runCurrent()
    }

    @Test
    fun `requestSos on an approximate circle asks first`() = runTest {
        coEvery { privacySettingDao.get("circle-1") } returns privacyRow("circle-1", "approximate")

        viewModel.requestSos("circle-1")
        runCurrent()

        assertEquals("approximate", viewModel.privacyOverride.value?.mode)
        assertNull(viewModel.countdown.value)
    }

    @Test
    fun `requestSos on an exact circle goes straight to the countdown`() = runTest {
        coEvery { privacySettingDao.get("circle-1") } returns privacyRow("circle-1", "exact")

        viewModel.requestSos("circle-1")
        runCurrent()

        assertNull(viewModel.privacyOverride.value)
        assertEquals(SosUiState.Countdown, viewModel.uiState.value)

        viewModel.cancelCountdown()
        runCurrent()
    }

    @Test
    fun `requestSos with no synced privacy row does not prompt`() = runTest {
        // An unprimed row must not raise a prompt nobody can answer. Failing
        // toward the send is deliberate: exact is the server default, and a
        // dialog blocking an emergency is the worse failure.
        coEvery { privacySettingDao.get("circle-1") } returns null

        viewModel.requestSos("circle-1")
        runCurrent()

        assertNull(viewModel.privacyOverride.value)
        assertEquals(SosUiState.Countdown, viewModel.uiState.value)

        viewModel.cancelCountdown()
        runCurrent()
    }

    @Test
    fun `confirming the override starts the countdown and clears the prompt`() = runTest {
        coEvery { privacySettingDao.get("circle-1") } returns privacyRow("circle-1", "paused")

        viewModel.requestSos("circle-1")
        runCurrent()
        viewModel.confirmPrivacyOverride()
        runCurrent()

        assertNull(viewModel.privacyOverride.value)
        assertEquals(SosUiState.Countdown, viewModel.uiState.value)

        viewModel.cancelCountdown()
        runCurrent()
    }

    @Test
    fun `dismissing the override sends nothing`() = runTest {
        coEvery { privacySettingDao.get("circle-1") } returns privacyRow("circle-1", "paused")

        viewModel.requestSos("circle-1")
        runCurrent()
        viewModel.dismissPrivacyOverride()
        runCurrent()

        assertNull(viewModel.privacyOverride.value)
        assertNull(viewModel.countdown.value)
        assertEquals(SosUiState.Idle, viewModel.uiState.value)
        coVerify(exactly = 0) { sosRepository.triggerSos(any(), any(), any(), any()) }
    }

    @Test
    fun `startSosCountdown sets Countdown state and initial value`() = runTest {
        viewModel.startSosCountdown("circle-1")
        runCurrent()

        assertEquals(SosUiState.Countdown, viewModel.uiState.value)
        assertEquals(SosViewModel.COUNTDOWN_SECONDS, viewModel.countdown.value)

        viewModel.cancelCountdown()
        runCurrent()
    }

    @Test
    fun `countdown decrements each second`() = runTest {
        viewModel.startSosCountdown("circle-1")
        runCurrent()
        assertEquals(5, viewModel.countdown.value)

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(4, viewModel.countdown.value)

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(3, viewModel.countdown.value)

        viewModel.cancelCountdown()
    }

    @Test
    fun `cancelCountdown clears state and countdown`() = runTest {
        viewModel.startSosCountdown("circle-1")
        runCurrent()

        viewModel.cancelCountdown()
        runCurrent()

        assertEquals(SosUiState.Idle, viewModel.uiState.value)
        assertNull(viewModel.countdown.value)
    }

    @Test
    fun `countdown completes and triggers SOS with location`() = runTest {
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 37.0
            every { longitude } returns -122.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { sosRepository.triggerSos("circle-1", at(37.0, -122.0), any(), any()) } returns Result.success(testAlert)

        viewModel.startSosCountdown("circle-1")
        // 5 second countdown, then trigger
        advanceTimeBy(6000)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Triggered, was $state", state is SosUiState.Triggered)
        assertEquals(testAlert, (state as SosUiState.Triggered).alert)
        coVerify { sosRepository.triggerSos("circle-1", at(37.0, -122.0), any(), any()) }
    }

    @Test
    fun `a raise with no location goes all the same - sealed pending, never failed for a location`() = runTest {
        // The raise is never abandoned for want of a location.
        coEvery { locationRepository.getCurrentLocation() } returns null
        coEvery { sosRepository.triggerSos("circle-1", SosSeal.PENDING, any(), any()) } returns Result.success(testAlert)

        viewModel.triggerSosImmediately("circle-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Triggered, was $state", state is SosUiState.Triggered)
        coVerify(exactly = 1) { sosRepository.triggerSos("circle-1", SosSeal.PENDING, any(), any()) }
    }

    @Test
    fun `triggerSosImmediately bypasses countdown`() = runTest {
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 1.0
            every { longitude } returns 2.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { sosRepository.triggerSos("circle-1", at(1.0, 2.0), any(), any()) } returns Result.success(testAlert)

        viewModel.triggerSosImmediately("circle-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SosUiState.Triggered)
        coVerify { sosRepository.triggerSos("circle-1", at(1.0, 2.0), any(), any()) }
    }

    @Test
    fun `a raise that didn't go stays on the card, never a toast of the failure's own words`() = runTest {
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 1.0
            every { longitude } returns 2.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { sosRepository.triggerSos(any(), any(), any(), any()) } returns Result.failure(Exception("Network error"))

        viewModel.triggerSosImmediately("circle-1")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value is SosUiState.Error)
        assertEquals("circle-1", viewModel.notSent.value?.circleId)
        assertEquals(com.pingolin.track.domain.sos.SosDispatcher.NotSent.Why.NOT_SENT, viewModel.notSent.value?.why)
        // Shown until the raiser closes it.
        viewModel.resetState()
        assertTrue(viewModel.notSent.value != null)
        viewModel.letGoNotSent()
        assertEquals(null, viewModel.notSent.value)
    }

    // ---- The countdown must outlive the screen -------------------------------
    //
    // SosFragment scopes this ViewModel with `by viewModels()`, so leaving
    // the Safety tab destroys it and cancels viewModelScope. If the
    // countdown and the send lived in that scope, five delay(1000)s deep,
    // pressing the button and then switching to the Map tab to watch what
    // happened would cancel the send mid-countdown: no alert raised, and
    // nothing to say so — no error, no toast, no row. The most natural
    // thing to do after pressing the button would be the thing that stopped
    // it working.

    /**
     * Puts the ViewModel in a real ViewModelStore so it can be cleared the
     * way the framework clears it on navigation — the only faithful way to
     * reproduce leaving the screen, since cancelling viewModelScope IS what
     * going away does.
     */
    private fun scopedViewModel(store: ViewModelStore): SosViewModel =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    newViewModel() as T
            },
        )[SosViewModel::class.java]

    @Test
    fun `a countdown outlives the screen that started it`() = runTest {
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 37.0
            every { longitude } returns -122.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { sosRepository.triggerSos("circle-1", at(37.0, -122.0), any(), any()) } returns Result.success(testAlert)

        val store = ViewModelStore()
        val screen = scopedViewModel(store)

        screen.startSosCountdown("circle-1")
        runCurrent()
        assertEquals(5, screen.countdown.value)

        advanceTimeBy(2000)
        runCurrent()
        assertEquals("still counting when the user navigates away", 3, screen.countdown.value)

        // Switch to the Map tab. This is exactly what the framework does.
        store.clear()

        advanceTimeBy(6000)
        advanceUntilIdle()

        coVerify(exactly = 1) { sosRepository.triggerSos("circle-1", at(37.0, -122.0), any(), any()) }
    }

    @Test
    fun `the outcome reaches the screen the user comes back to`() = runTest {
        // The send finishing with no UI attached is the point, so the
        // result cannot be a one-shot event — the raiser would never
        // learn what happened. It waits until a screen is there to take
        // it.
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 37.0
            every { longitude } returns -122.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { sosRepository.triggerSos(any(), any(), any(), any()) } returns Result.success(testAlert)

        val store = ViewModelStore()
        scopedViewModel(store).startSosCountdown("circle-1")
        runCurrent()
        store.clear()

        advanceTimeBy(6000)
        advanceUntilIdle()

        // Back to the Safety tab: a brand new ViewModel, and the news is waiting.
        val returning = newViewModel()
        advanceUntilIdle()

        val state = returning.uiState.value
        assertTrue("State should be Triggered, was $state", state is SosUiState.Triggered)
        assertEquals(testAlert, (state as SosUiState.Triggered).alert)
    }

    @Test
    fun `an outcome already shown is not shown again`() = runTest {
        // The other half of the same trade: an outcome that survives the screen
        // will re-announce the last send to every ViewModel built afterwards
        // unless something clears it. A stale "notification sent" toast on an
        // unrelated visit is its own kind of lie. resetState is the screen
        // saying it has been shown — the Fragment already calls it after the
        // toast.
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 37.0
            every { longitude } returns -122.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { sosRepository.triggerSos(any(), any(), any(), any()) } returns Result.success(testAlert)

        viewModel.triggerSosImmediately("circle-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SosUiState.Triggered)

        viewModel.resetState()
        advanceUntilIdle()

        val later = newViewModel()
        advanceUntilIdle()
        assertEquals(SosUiState.Idle, later.uiState.value)
    }

    @Test
    fun `cancelling stops the send, but only while there is still a countdown`() = runTest {
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 37.0
            every { longitude } returns -122.0
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        // A send that takes a moment, so "in flight" is a state the test can
        // actually stand in. With an instant mock the request is finished
        // before the countdown's last tick returns and there is nothing to
        // try to cancel.
        coEvery { sosRepository.triggerSos(any(), any(), any(), any()) } coAnswers {
            delay(2000)
            Result.success(testAlert)
        }

        // Deliberate abort: nothing goes out.
        viewModel.startSosCountdown("circle-1")
        advanceTimeBy(2000)
        viewModel.cancelCountdown()
        advanceTimeBy(10_000)
        advanceUntilIdle()
        coVerify(exactly = 0) { sosRepository.triggerSos(any(), any(), any(), any()) }
        assertEquals(SosUiState.Idle, viewModel.uiState.value)

        // Once the countdown is spent the request is on its way and
        // nothing recalls it: cancelling then must not kill the send
        // coroutine itself, which could abandon a half-issued alert.
        viewModel.startSosCountdown("circle-2")
        advanceTimeBy(5000)
        runCurrent()
        assertEquals("countdown spent, send in flight", 0, viewModel.countdown.value)

        viewModel.cancelCountdown()
        advanceUntilIdle()
        coVerify(exactly = 1) { sosRepository.triggerSos("circle-2", at(37.0, -122.0), any(), any()) }
    }

    @Test
    fun `startSosCountdown after existing cancels previous job`() = runTest {
        viewModel.startSosCountdown("circle-1")
        runCurrent()
        val firstCountdown = viewModel.countdown.value

        viewModel.startSosCountdown("circle-2")
        runCurrent()

        // countdown should reset to 5 for the new job
        assertEquals(5, viewModel.countdown.value)
        assertEquals(firstCountdown, 5)

        viewModel.cancelCountdown()
    }

    @Test
    fun `acknowledgeSos success transitions to Acknowledged`() = runTest {
        coEvery { sosRepository.acknowledgeSos("alert-1") } returns Result.success(testAlert)

        viewModel.acknowledgeSos("alert-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SosUiState.Acknowledged)
        assertEquals(testAlert, (state as SosUiState.Acknowledged).alert)
    }

    @Test
    fun `acknowledgeSos failure transitions to Error`() = runTest {
        coEvery { sosRepository.acknowledgeSos("alert-1") } returns Result.failure(Exception("Failed"))

        viewModel.acknowledgeSos("alert-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SosUiState.Error)
        assertEquals("Failed", (state as SosUiState.Error).message)
    }

    @Test
    fun `resolveSos success transitions to Resolved`() = runTest {
        every { sessionManager.getUserId() } returns me
        coEvery { sosRepository.resolveSos("alert-1") } returns Result.success(testAlert)

        viewModel.resolveSos(testAlert)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SosUiState.Resolved)
    }

    @Test
    fun `cancelSos success transitions to Cancelled`() = runTest {
        every { sessionManager.getUserId() } returns me
        coEvery { sosRepository.cancelSos("alert-1") } returns Result.success(testAlert)

        viewModel.cancelSos(testAlert)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SosUiState.Cancelled)
    }

    @Test
    fun `isMyAlert returns true when user id matches`() {
        every { sessionManager.getUserId() } returns me
        assertTrue(viewModel.isMyAlert(testAlert))
    }

    @Test
    fun `isMyAlert returns false when user id differs`() {
        every { sessionManager.getUserId() } returns someoneElse
        assertFalse(viewModel.isMyAlert(testAlert))
    }

    @Test
    fun `my own request is mine in any spelling the raiser's client sealed`() {
        // The raiser id is stored as the raising device sealed it (SosRepository.toEntity):
        // my request raised on another device of mine must still read as mine, with Resolve.
        every { sessionManager.getUserId() } returns me
        assertTrue(viewModel.isMyAlert(testAlert.copy(userId = me.uppercase())))
        // Not a UUID is nobody, never "me".
        every { sessionManager.getUserId() } returns "user-1"
        assertFalse(viewModel.isMyAlert(testAlert.copy(userId = "user-1")))
    }

    @Test
    fun `resetState returns to Idle`() = runTest {
        coEvery { sosRepository.acknowledgeSos(any()) } returns Result.failure(Exception("Err"))
        viewModel.acknowledgeSos("x")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SosUiState.Error)

        viewModel.resetState()
        assertEquals(SosUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `COUNTDOWN_SECONDS constant is 5`() {
        assertEquals(5, SosViewModel.COUNTDOWN_SECONDS)
    }

    // --- Address resolution -------------------------------------------------
    //
    // The Safety row shows WHERE an alert was raised. The server holds the SOS
    // location encrypted, so the address can only come from an on-device
    // reverse geocode of the decrypted coordinates.

    /** Rebuild the ViewModel with a specific set of active alerts. */
    private fun viewModelWith(alerts: List<SosAlertEntity>): SosViewModel {
        every { sosRepository.observeActiveAlerts() } returns flowOf(alerts)
        return newViewModel()
    }

    private fun geocoded(text: String) = GeocodingResult(
        text = text,
        lat = 37.0,
        lng = -122.0,
        resultType = "address",
        confidence = 0.9,
        name = null,
        city = "Modesto",
        state = "CA",
        postcode = "95350",
        // These cases pin street/city/region formatting. Country rendering has
        // its own locale-sensitive cases, so leave it out of this fixture.
        country = null,
        address = null,
    )

    @Test
    fun `alertRows pairs an alert with its reverse-geocoded address`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(37.0, -122.0) } returns
            geocoded("1500 McHenry Avenue")
        val vm = viewModelWith(listOf(testAlert))

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        val row = vm.alertRows.value.single()
        assertEquals("alert-1", row.alert.id)
        // City and state come off the structured fields, not out of `text` —
        // the server geocoder puts only the street there, and an SOS callout
        // that says "1500 McHenry Avenue" without saying which McHenry Avenue
        // is exactly the case this has to survive.
        assertEquals("1500 McHenry Avenue, Modesto, CA", row.address)
        job.cancel()
    }

    @Test
    fun `alertRows names who asked by the receivers' rules, once per alert`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } returns null
        coEvery { notificationNames.namesFor("sos_alert", testAlert.circleId, mapOf("sos_id" to testAlert.id), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Ana")
        val vm = viewModelWith(listOf(testAlert))

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        assertEquals("Ana", vm.alertRows.value.single().raiserName)
        coVerify(exactly = 1) { notificationNames.namesFor("sos_alert", any(), any(), any()) }
        job.cancel()
    }

    @Test
    fun `alertRows leaves the address null when the geocode finds nothing`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } returns null
        val vm = viewModelWith(listOf(testAlert))

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        assertNull(vm.alertRows.value.single().address)
        job.cancel()
    }

    @Test
    fun `alertRows survives a geocoder that throws`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } throws
            IllegalStateException("tile store corrupt")
        val vm = viewModelWith(listOf(testAlert))

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        val row = vm.alertRows.value.single()
        assertEquals("alert-1", row.alert.id)
        assertNull(row.address)
        job.cancel()
    }

    @Test
    fun `an alert whose location never decrypted is never geocoded`() = runTest {
        val noLocation = testAlert.copy(id = "alert-2", latitude = null, longitude = null)
        val vm = viewModelWith(listOf(noLocation))

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        assertNull(vm.alertRows.value.single().address)
        coVerify(exactly = 0) { geocodingRepository.reverseGeocode(any(), any()) }
        job.cancel()
    }

    @Test
    fun `an alert whose position moves is looked up again - addresses are keyed by alert AND position`() = runTest {
        // The raiser's phone replaces a last-known position with a better one: the
        // first address would be wrong for the new pin.
        coEvery { geocodingRepository.reverseGeocode(37.0, -122.0) } returns geocoded("1500 McHenry Avenue")
        coEvery { geocodingRepository.reverseGeocode(37.1, -122.1) } returns geocoded("2001 Oakdale Road")
        val alerts = kotlinx.coroutines.flow.MutableStateFlow(listOf(testAlert))
        every { sosRepository.observeActiveAlerts() } returns alerts
        val vm = newViewModel()

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()
        assertEquals("1500 McHenry Avenue, Modesto, CA", vm.alertRows.value.single().address)

        alerts.value = listOf(testAlert.copy(latitude = 37.1, longitude = -122.1, fixAt = 1_000L))
        advanceUntilIdle()
        assertEquals("2001 Oakdale Road, Modesto, CA", vm.alertRows.value.single().address)
        coVerify(exactly = 1) { geocodingRepository.reverseGeocode(37.1, -122.1) }
        job.cancel()
    }

    @Test
    fun `an area has no address - never looked up, and a point that becomes one drops its address`() = runTest {
        // An area's centre is nobody's street.
        coEvery { geocodingRepository.reverseGeocode(37.0, -122.0) } returns geocoded("1500 McHenry Avenue")
        val alerts = kotlinx.coroutines.flow.MutableStateFlow(listOf(testAlert))
        every { sosRepository.observeActiveAlerts() } returns alerts
        val vm = newViewModel()

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()
        assertEquals("1500 McHenry Avenue, Modesto, CA", vm.alertRows.value.single().address)

        alerts.value = listOf(testAlert.copy(latitude = 37.02, longitude = -122.03, accuracy = 1609.344, fixAt = 1_000L))
        advanceUntilIdle()
        assertNull(vm.alertRows.value.single().address)
        coVerify(exactly = 0) { geocodingRepository.reverseGeocode(37.02, -122.03) }
        job.cancel()
    }

    @Test
    fun `a resolved address is not looked up again on re-emission`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(37.0, -122.0) } returns
            geocoded("1500 McHenry Avenue")
        val vm = viewModelWith(listOf(testAlert))

        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        // The address landing re-emits alertRows, which re-runs the resolver.
        // Without the already-resolved guard that would loop forever.
        coVerify(exactly = 1) { geocodingRepository.reverseGeocode(37.0, -122.0) }
        job.cancel()
    }

    // ---- Nobody closes someone else's alert ------------------------------------
    //
    // Alerts are personal, on both the sent and the received side, so nobody
    // can affect the other's: they are more like notifications. Only the
    // raiser ends a request, from any screen, and the server agrees; the
    // circle's owner and admins can't resolve another member's alert.

    @Test
    fun `nobody closes someone else's request, whatever their role`() = runTest {
        // An owner, even: the row reads no role, and the view model refuses on its own.
        every { sessionManager.getUserId() } returns someoneElse
        every { circleRepository.observeCircles() } returns flowOf(
            listOf(CircleEntity(id = "circle-1", name = "circle", encryptedKey = null, createdAt = 0L, updatedAt = 0L, isOwner = true))
        )
        val vm = newViewModel()

        vm.resolveSos(testAlert)
        vm.cancelSos(testAlert)
        advanceUntilIdle()

        coVerify(exactly = 0) { sosRepository.resolveSos(any()) }
        coVerify(exactly = 0) { sosRepository.cancelSos(any()) }
        assertTrue("nothing happened, so nothing to show", vm.uiState.value is SosUiState.Idle)
    }

    @Test
    fun `the rows carry no role to unlock a close with`() = runTest {
        every { sosRepository.observeActiveAlerts() } returns flowOf(listOf(testAlert))
        val vm = newViewModel()
        val job = launch { vm.alertRows.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf(SosAlertRow(alert = testAlert, address = null, position = com.pingolin.track.domain.sos.SosPosition.AsSent)),
            vm.alertRows.value,
        )
        job.cancel()
    }

    // ---- A refused resolve must not read as a resolve that worked -----------

    @Test
    fun `resolveSos failure lands on Error, not Resolved`() = runTest {
        // What the raiser gets when the server refuses (an alert already closed
        // elsewhere: 400 "Alert not found or already resolved"): the repository
        // turns a non-2xx into a failure, and the screen shows THIS message in a
        // long toast — never the success toast, and the row stays as it was.
        every { sessionManager.getUserId() } returns me
        coEvery { sosRepository.resolveSos("alert-1") } returns
            Result.failure(Exception("Could not resolve this notification"))

        viewModel.resolveSos(testAlert)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("a refused resolve must not reach Resolved", state is SosUiState.Error)
        assertEquals("Could not resolve this notification", (state as SosUiState.Error).message)
    }
}
