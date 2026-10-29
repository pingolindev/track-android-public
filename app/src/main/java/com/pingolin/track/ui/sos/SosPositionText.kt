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

import android.content.Context
import android.text.format.DateUtils
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.domain.sos.SosPosition
import com.pingolin.track.ui.places.formatDistance
import com.pingolin.track.util.uiLocale

/**
 * The words an SOS's position gets: one place, so the Safety row, the map's label and its sheet
 * say the same thing.
 */
internal object SosPositionText {

    /**
     * Where an SOS's [position] stands ([SosPosition.of]), or null when it was taken as it was sent
     * (the usual words say that already). [mine]: the raiser's own words.
     */
    fun status(context: Context, position: SosPosition, mine: Boolean): String? =
        when (position) {
            SosPosition.Pending -> context.getString(
                if (mine) R.string.sos_own_position_pending else R.string.sos_position_pending
            )
            SosPosition.Unavailable -> context.getString(
                if (mine) R.string.sos_own_position_unavailable else R.string.sos_position_unavailable
            )
            is SosPosition.LastKnown -> context.getString(
                if (mine) R.string.sos_own_position_from else R.string.sos_position_last_known_at,
                time(context, position.at),
            )
            is SosPosition.Updated -> context.getString(
                if (mine) R.string.sos_own_position_added else R.string.sos_position_updated_at,
                time(context, position.at),
            )
            SosPosition.AsSent -> null
        }

    /** "Within about {distance}" for a position drawn as an area, in the user's units; else null. */
    fun within(context: Context, alert: SosAlertEntity, useMiles: Boolean): String? =
        SosPosition.areaMeters(alert)?.let {
            context.getString(R.string.sos_position_within, formatDistance(it / 1000.0, useMiles, uiLocale(context)))
        }

    /** [status] and [within], a line each; null when neither has anything to say. */
    fun lines(context: Context, alert: SosAlertEntity, position: SosPosition, mine: Boolean, useMiles: Boolean): String? =
        listOfNotNull(status(context, position, mine), within(context, alert, useMiles))
            .takeIf { it.isNotEmpty() }?.joinToString("\n")

    /**
     * The label under an SOS's marker: "Last known location", "Location updated · {time}", "Within
     * about {distance}" for an area taken as it was sent, else the usual "Sent from here · {age}"
     * ([sentFromHere]).
     */
    fun mapLabel(context: Context, alert: SosAlertEntity, useMiles: Boolean, now: Long, sentFromHere: () -> String): String =
        when (val position = SosPosition.of(alert, now)) {
            is SosPosition.LastKnown -> context.getString(R.string.sos_position_last_known)
            is SosPosition.Updated -> context.getString(R.string.sos_position_updated_at, time(context, position.at))
            else -> within(context, alert, useMiles) ?: sentFromHere()
        }

    /**
     * The label an AREA carries at its centre in place of the alert's disc (the area and the alert's
     * label, nothing that reads as an exact pin): "Last known location" or "Location updated · {time}"
     * when it says so, then "Within about {distance}".
     */
    fun areaLabel(context: Context, alert: SosAlertEntity, useMiles: Boolean, now: Long): String =
        listOfNotNull(
            when (val position = SosPosition.of(alert, now)) {
                is SosPosition.LastKnown -> context.getString(R.string.sos_position_last_known)
                is SosPosition.Updated -> context.getString(R.string.sos_position_updated_at, time(context, position.at))
                else -> null
            },
            within(context, alert, useMiles),
        ).joinToString("\n")

    /** {time}: the clock time, with the date when it isn't today, in the phone's own format. */
    fun time(context: Context, at: Long): String {
        val date = if (DateUtils.isToday(at)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME or date)
    }
}
