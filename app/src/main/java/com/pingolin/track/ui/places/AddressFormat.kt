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

package com.pingolin.track.ui.places

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import uniffi.pingolin_mobile.GeocodingResult
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Display formatting for a picked geocoder result.
 *
 * `GeocodingResult.text` is a legacy single-line field whose meaning depends on
 * the row type: on an address row it is the street line, but on a POI row it is
 * **name + locality** ("Pink's Hot Dogs, North Hollywood, CA") with no street at
 * all. Handing that straight to a form's Address field would show a POI's city
 * and state where its street address belongs.
 *
 * The structured fields carry what's actually needed — `address` holds the POI's
 * street (derived from the nearest address in the same tile), `name` holds just
 * the POI name — so build the lines from those and keep `text` as the fallback.
 */

/**
 * True when [country] is the user's own, in either alpha-2 ("US") or alpha-3
 * ("USA") form. Resolved through Locale rather than prefix-matched, because
 * "CHN".startsWith("CH") is true and a Swiss user would otherwise read every
 * Chinese row as domestic.
 */
internal fun isHomeCountryCode(country: String?): Boolean {
    val cc = country?.takeIf { it.isNotBlank() } ?: return false
    val home = Locale.getDefault().country.takeIf { it.isNotBlank() } ?: return false
    val homeAlpha3 = runCatching { Locale.Builder().setRegion(home).build().isO3Country }.getOrNull()
    return cc.equals(home, ignoreCase = true) || cc.equals(homeAlpha3, ignoreCase = true)
}

/**
 * "Fairfax District, Los Angeles, CA 90036" — the parts below the street line.
 *
 * The user's OWN country is omitted: "USA" on every row of a domestic search is
 * noise, and it costs ~5 characters on a line that has to hold a neighbourhood,
 * a city, a region and possibly a distance. Foreign results keep it, because
 * there it is the most important word on the line.
 */
internal fun GeocodingResult.localityLine(): String {
    // US convention groups state and postcode on one line segment; elsewhere
    // the postcode simply trails the region, which reads the same way.
    val regionAndPostcode = listOfNotNull(
        state?.takeIf { it.isNotBlank() },
        postcode?.takeIf { it.isNotBlank() },
    ).joinToString(" ")
    return listOfNotNull(
        city?.takeIf { it.isNotBlank() },
        regionAndPostcode.takeIf { it.isNotBlank() },
        country?.takeIf { it.isNotBlank() && !isHomeCountryCode(it) },
    ).joinToString(", ")
}

/** Metres in one mile; also the km->mi divisor via [KM_PER_MILE]. */
private const val KM_PER_MILE = 1.609344

/**
 * Render a radius that is STORED in whole miles (the approximate-location
 * bubble's server field is `approximate_radius_miles`, 1-25).
 *
 * Kept separate from [formatDistance] so the miles case stays exact — the
 * stored value is already the number to show, and round-tripping it through
 * kilometres would turn "3 mi" into "3.0 mi". In kilometres it necessarily
 * lands on non-round values (1 mi = 1.6 km); that is honest, since the
 * underlying setting really does step in miles.
 */
internal fun formatMileRadius(miles: Int, useMiles: Boolean, locale: Locale): String =
    if (useMiles) measure(miles, MeasureUnit.MILE, decimals = 0, locale) else formatDistance(miles * KM_PER_MILE, useMiles = false, locale)

/**
 * Render a distance in the user's chosen unit (Settings -> Account ->
 * Distance units, which itself defaults to the device region).
 *
 * Sub-unit distances get the small unit (metres / feet) because "0.1 km" reads
 * worse than "120 m", and the precision step mirrors how people actually say
 * it: one decimal while the number is small, whole units once it is not.
 * The unit's words and the number are [locale]'s ([measure]): "8.4 km", fr
 * "264 pi", zh "8.4公里". Callers pass [com.pingolin.track.util.uiLocale], so a
 * line never carries another language's unit; which unit stays [useMiles].
 */
internal fun formatDistance(km: Double, useMiles: Boolean, locale: Locale): String {
    if (useMiles) {
        val miles = km / KM_PER_MILE
        return when {
            miles < 0.1 -> measure((miles * 5280).roundToInt(), MeasureUnit.FOOT, decimals = 0, locale)
            miles < 10.0 -> measure(miles, MeasureUnit.MILE, decimals = 1, locale)
            else -> measure(miles.roundToInt(), MeasureUnit.MILE, decimals = 0, locale)
        }
    }
    return when {
        km < 1.0 -> measure((km * 1000).roundToInt(), MeasureUnit.METER, decimals = 0, locale)
        km < 10.0 -> measure(km, MeasureUnit.KILOMETER, decimals = 1, locale)
        else -> measure(km.roundToInt(), MeasureUnit.KILOMETER, decimals = 0, locale)
    }
}

/** [value] [unit] in [locale]'s short form, with exactly [decimals] decimals (CLDR's words: no strings to keep). */
private fun measure(value: Number, unit: MeasureUnit, decimals: Int, locale: Locale): String {
    val number = android.icu.text.NumberFormat.getInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, number).format(Measure(value, unit))
}

/**
 * The fullest address this row can express: street line first, then locality.
 *
 * POI rows take their street from [GeocodingResult.address] — `text` would
 * repeat the name and locality instead. Address rows already hold the street
 * in `text`. Falls back to `text` when the tile carries no structured fields, so
 * this is never worse than the raw field.
 */
internal fun GeocodingResult.fullAddress(): String {
    val isPoi = resultType == "poi"
    val street = if (isPoi) address?.takeIf { it.isNotBlank() } else text.takeIf { it.isNotBlank() }
    val line = listOfNotNull(street, localityLine().takeIf { it.isNotBlank() })
        .joinToString(", ")
    return line.ifBlank { text }
}

/**
 * The address line for a map placard (the member sheet, the tapped-location
 * sheet, the SOS callout), or null when the row can't express one.
 *
 * Rendering [GeocodingResult.text] raw would make the city and state a
 * property of WHICH GEOCODER ANSWERED rather than of the address. The
 * on-device tile geocoder appends ", City, State" to `text` on a reverse
 * lookup; the server geocoder deliberately does not — `text` there is the
 * street line alone, so that consumers reading the structured fields don't
 * double-print the locality. Same sheet, same coordinates, two different
 * lines, and with the server geocoder turned on the locality would simply
 * vanish. Composing from the structured fields makes the sheet answer the
 * same way whichever geocoder replied.
 *
 * Differs from [fullAddress] in two ways, both forced by the placard:
 *  - no postcode. The sheet already carries the coordinates, and the tiles
 *    carry the zip float-formatted ("94102.0"), which reads as a bug.
 *  - it will not repeat a locality the head already ends with, so the tile
 *    geocoder's baked-in ", City, State" doesn't come out twice. Matched
 *    segment-wise rather than by substring: the tile geocoder's own guard is
 *    `text.contains(state)`, which silently drops "CA" from "Canal Street".
 *
 * [withPoiName] is for the one-line placards (member, SOS), where the POI's
 * name is the most useful thing on the row and has nowhere else to go. The
 * tapped-location sheet leaves it false — that sheet renders the name in its
 * own title row, so repeating it here would print it twice.
 */
internal fun GeocodingResult.placardAddress(withPoiName: Boolean = false): String? {
    val isPoi = resultType == "poi"
    // POI rows keep their street in `address`; on an address row `text` IS the
    // street (plus, from the tile geocoder, the locality the dedupe below eats).
    val street = (if (isPoi) address else text)?.takeIf { it.isNotBlank() }
    val head = listOfNotNull(
        name?.takeIf { withPoiName && isPoi && it.isNotBlank() },
        street,
    )
    val carried = head.flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
    val locality = listOfNotNull(
        city?.takeIf { it.isNotBlank() },
        state?.takeIf { it.isNotBlank() },
        country?.takeIf { it.isNotBlank() && !isHomeCountryCode(it) },
    ).filterNot { it.trim().lowercase() in carried }

    // Fall back to the raw field rather than to nothing, so a row from a tile
    // too old to carry structured columns still renders as the raw field does.
    // Withheld from a POI when the caller shows the name separately: `text` on
    // a POI row leads with that name, and the title row already has it.
    val fallback = text.takeIf { it.isNotBlank() && (withPoiName || !isPoi) }
    return (head + locality).joinToString(", ").takeIf { it.isNotBlank() } ?: fallback
}

/**
 * The name to pre-fill a place/destination label with, or null when the row
 * carries none — an address row has no name worth suggesting, and the user's
 * own typing must never be overwritten.
 */
internal fun GeocodingResult.suggestedLabel(): String? =
    if (resultType == "poi") name?.takeIf { it.isNotBlank() } else null
