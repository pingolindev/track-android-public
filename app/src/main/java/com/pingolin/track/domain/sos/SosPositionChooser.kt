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

package com.pingolin.track.domain.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.location.NewestOwnFix
import com.pingolin.track.util.ApproximateIntervals
import com.pingolin.track.util.PauseIntervals
import com.pingolin.track.util.StoreUnreadable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which position an SOS of this phone's carries: chosen when sealing, never waited for.
 *  - (a) a fix taken after the press, if one is in hand: EXACT, whatever the circle's pause or
 *    bubble (the raiser's consent covers the current exact location: SosViewModel asks first);
 *  - (b) otherwise the newest fix this phone holds, by when it was taken, if the circle allowed it
 *    then: not inside a pause or real-time-off span this phone knows of, and placed by a privacy
 *    read since; under a bubble only as that area;
 *  - (c) otherwise a marker: "unavail" when a definite cause keeps this phone from any (no location
 *    permission, or Location Services off), "pending" while it is still getting one.
 *
 * A fix's capture time is now − its age on the elapsed clock every source shares (NewestOwnFix's);
 * one that clock can't date isn't used.
 */
@Singleton
class SosPositionChooser internal constructor(
    private val newestOwnFix: NewestOwnFix,
    private val locationProvider: LocationProvider,
    private val pauseIntervals: PauseIntervals,
    private val approximateIntervals: ApproximateIntervals,
    /** Whether a definite cause keeps this phone from a fix now: no location permission, or Location Services off. */
    private val definiteCause: () -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        newestOwnFix: NewestOwnFix,
        locationProvider: LocationProvider,
        pauseIntervals: PauseIntervals,
        approximateIntervals: ApproximateIntervals,
    ) : this(newestOwnFix, locationProvider, pauseIntervals, approximateIntervals, { locationOff(context) })

    /** The wall clock capture times are dated on; tests set it. */
    internal var wallNow: () -> Long = System::currentTimeMillis

    /** The clock fixes are compared on (NewestOwnFix's); tests set it. */
    internal var elapsedNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }

    /** Whether a definite cause keeps this phone from a fix now. */
    fun cause(): Boolean = runCatching { definiteCause() }.getOrDefault(false)

    /**
     * [fix] as sealed exact: taken at now − its age, with its accuracy when it has one. Null for one
     * the shared clock can't date (none, or one from the future: a reboot's).
     */
    fun exact(fix: Location): SosSeal.Point? {
        val at = runCatching { fix.elapsedRealtimeNanos }.getOrDefault(0L)
        val now = elapsedNanos()
        if (at <= 0L || at > now) return null
        return SosSeal.Point(
            latitude = fix.latitude,
            longitude = fix.longitude,
            takenAt = wallNow() - (now - at) / 1_000_000L,
            accuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
        )
    }

    /** The newest fix [NewestOwnFix] holds, when it was taken after the press at [pressAt]: one the update
     * rule may seal.
     */
    fun newestAfterPress(pressAt: Long): SosSeal.Point? =
        newestOwnFix.newest()?.let(::exact)?.takeIf { SosUpdateRule.afterPress(it, pressAt) }

    /**
     * What the raise in [circleId] pressed at [pressAt] seals, at once: [inHand] is the press's own fix
     * when it already came in. The other sources are what this phone holds now, asked nothing new. A
     * raise that waited for a connection carries what the rules give as of the window, never later: a
     * fix from after press + 10 minutes is never sealed, and a marker past the window is "unavail", the
     * update rule's (e) ([SosUpdateRule.next]), sealed in the create since no update can come any more.
     */
    suspend fun forRaise(circleId: String, pressAt: Long, inHand: Location?): SosSeal {
        val lastKnown = try {
            locationProvider.lastLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val windowEnd = pressAt + SosUpdateRule.WINDOW_MS
        val fixes = listOfNotNull(inHand, newestOwnFix.newest(), lastKnown).mapNotNull(::exact)
            .filter { it.takenAt < windowEnd }
        // (a) Taken after the press: exact.
        fixes.filter { SosUpdateRule.afterPress(it, pressAt) }.maxByOrNull { it.takenAt }?.let { return it }
        // (b) The newest held one decides; no older one is searched for.
        fixes.maxByOrNull { it.takenAt }?.let { newest -> heldFix(circleId, newest)?.let { return it } }
        // (c)
        return if (cause() || wallNow() >= windowEnd) SosSeal.UNAVAILABLE else SosSeal.PENDING
    }

    /**
     * Case (b) of the class's choice: [fix], held from before the press, as [circleId] allowed it
     * when it was taken, or null when it may not go: the record can't place it (no privacy read since
     * it was taken, with the margin, or a record that doesn't read), or a not-sharing span this phone
     * knows of covers it. Under a bubble, coarsened as the live path does to the widest in force when
     * it was taken (the widest of all where that isn't known), its accuracy at least the bubble's
     * radius, so every reader draws an area. The bubble NOW doesn't apply: only fixes after the press
     * go exact, and a held one only as it went then.
     */
    internal fun heldFix(circleId: String, fix: SosSeal.Point): SosSeal.Point? {
        val allowed = try {
            pauseIntervals.syncedSince(circleId, fix.takenAt) && !pauseIntervals.covers(circleId, fix.takenAt)
        } catch (e: StoreUnreadable) {
            false
        }
        if (!allowed) return null
        val miles = try {
            if (!approximateIntervals.knowsSince(circleId, fix.takenAt)) LocationRepository.MAX_BUBBLE_RADIUS_MILES
            else approximateIntervals.radiusAt(circleId, fix.takenAt)
        } catch (e: StoreUnreadable) {
            LocationRepository.MAX_BUBBLE_RADIUS_MILES
        } ?: return fix
        val radius = miles.coerceIn(1, LocationRepository.MAX_BUBBLE_RADIUS_MILES) * LocationRepository.METERS_PER_MILE
        val (lat, lng) = LocationRepository.coarsenLocation(fix.latitude, fix.longitude, radius)
        return SosSeal.Point(lat, lng, fix.takenAt, maxOf(fix.accuracy ?: 0.0, radius))
    }

    companion object {
        /** No location permission at all, or Location Services off. */
        fun locationOff(context: Context): Boolean {
            val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (!granted) return true
            val manager = context.getSystemService(LocationManager::class.java) ?: return false
            return !LocationManagerCompat.isLocationEnabled(manager)
        }
    }
}
