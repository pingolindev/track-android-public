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

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Formats dates and times according to user preferences.
 * Respects language, time format (12h/24h), and date format (mdy/dmy/ymd).
 */
@Singleton
class DateTimeFormatter @Inject constructor(
    private val sessionManager: SessionManager
) {
    companion object {
        // Time format options
        const val TIME_FORMAT_12H = "12h"
        const val TIME_FORMAT_24H = "24h"

        // Date format options
        const val DATE_FORMAT_MDY = "mdy"  // Month/Day/Year (US)
        const val DATE_FORMAT_DMY = "dmy"  // Day/Month/Year (Europe, most of world)
        const val DATE_FORMAT_YMD = "ymd"  // Year-Month-Day (ISO 8601)
    }

    /**
     * Format a timestamp as a time string according to user preference.
     */
    fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        return formatTime(date)
    }

    /**
     * Format a Date as a time string according to user preference.
     */
    fun formatTime(date: Date): String {
        val format = if (sessionManager.getTimeFormat() == TIME_FORMAT_24H) {
            SimpleDateFormat("HH:mm", getLocale())
        } else {
            SimpleDateFormat("h:mm a", getLocale())
        }
        return format.format(date)
    }

    /**
     * Format a timestamp as a date string according to user preference.
     */
    fun formatDate(timestamp: Long): String {
        val date = Date(timestamp)
        return formatDate(date)
    }

    /**
     * Format a Date as a date string according to user preference.
     */
    fun formatDate(date: Date): String {
        val pattern = when (sessionManager.getDateFormat()) {
            DATE_FORMAT_DMY -> "dd/MM/yyyy"
            DATE_FORMAT_YMD -> "yyyy-MM-dd"
            else -> "MM/dd/yyyy" // MDY default
        }
        val format = SimpleDateFormat(pattern, getLocale())
        return format.format(date)
    }

    /**
     * Format an epoch-day "day bucket" (days since 1970-01-01, as produced by
     * dividing epoch-millis by 86_400_000) as a calendar date in the user's
     * preferred format. Uses LocalDate so it stays timezone-independent — a day
     * bucket denotes a calendar day, not an instant, and must not shift by the
     * viewer's UTC offset (which Date + SimpleDateFormat would do).
     */
    fun formatDayBucket(dayBucket: Int): String {
        val date = LocalDate.ofEpochDay(dayBucket.toLong())
        val pattern = when (sessionManager.getDateFormat()) {
            DATE_FORMAT_DMY -> "dd/MM/yyyy"
            DATE_FORMAT_YMD -> "yyyy-MM-dd"
            else -> "MM/dd/yyyy" // MDY default
        }
        return date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, getLocale()))
    }

    /**
     * Format a timestamp as a short date (without year).
     */
    fun formatShortDate(timestamp: Long): String {
        val date = Date(timestamp)
        val pattern = when (sessionManager.getDateFormat()) {
            DATE_FORMAT_DMY -> "dd/MM"
            DATE_FORMAT_YMD -> "MM-dd"
            else -> "MM/dd" // MDY default
        }
        val format = SimpleDateFormat(pattern, getLocale())
        return format.format(date)
    }

    /**
     * Format a timestamp as date and time.
     */
    fun formatDateTime(timestamp: Long): String {
        return "${formatDate(timestamp)} ${formatTime(timestamp)}"
    }

    /**
     * Format a timestamp as date and time.
     */
    fun formatDateTime(date: Date): String {
        return "${formatDate(date)} ${formatTime(date)}"
    }

    /**
     * Format a timestamp as a relative time string (e.g., "2 minutes ago").
     */
    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "just now"
            diff < 3600_000 -> {
                val minutes = diff / 60_000
                if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
            }
            diff < 86400_000 -> {
                val hours = diff / 3600_000
                if (hours == 1L) "1 hour ago" else "$hours hours ago"
            }
            diff < 604800_000 -> {
                val days = diff / 86400_000
                if (days == 1L) "yesterday" else "$days days ago"
            }
            else -> formatDate(timestamp)
        }
    }

    /**
     * Format a timestamp for display with relative time if recent, otherwise date/time.
     */
    fun formatSmartDateTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return if (diff < 86400_000) {
            // Less than 24 hours - show relative time
            formatRelativeTime(timestamp)
        } else {
            // More than 24 hours - show date and time
            formatDateTime(timestamp)
        }
    }

    /**
     * Get the locale based on user's language preference.
     */
    fun getLocale(): Locale {
        return when (sessionManager.getLanguage()) {
            "es" -> Locale.forLanguageTag("es-ES")
            "fr" -> Locale.forLanguageTag("fr-FR")
            "de" -> Locale.forLanguageTag("de-DE")
            "pt" -> Locale.forLanguageTag("pt-BR")
            "ja" -> Locale.forLanguageTag("ja-JP")
            "zh" -> Locale.forLanguageTag("zh-CN")
            "ko" -> Locale.forLanguageTag("ko-KR")
            else -> Locale.US
        }
    }
}
