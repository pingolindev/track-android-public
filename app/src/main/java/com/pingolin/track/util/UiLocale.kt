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

import android.content.Context
import android.icu.util.ULocale
import androidx.core.os.ConfigurationCompat
import com.pingolin.track.R
import java.util.Locale

/**
 * The locale for the formats that carry words (a distance's unit, a weekday's name, a compact number's "M" or "万"),
 * and the number format that goes with them: the phone's own when it speaks the language the app's words are in,
 * else that language, which each values folder states in [R.string.ui_language]. In a build that ships English
 * alone, a Chinese phone's English line reads "300 m", not "300米"; in one that ships Chinese, "300米". Which unit
 * (km or mi) is the user's setting, never this ([SessionManager.useMiles]).
 */
fun uiLocale(context: Context): Locale =
    uiLocaleFor(
        context.getString(R.string.ui_language),
        ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault(),
    )

/**
 * [uiLocale]'s rule: [phone] when its language AND script are those of [wordsTag] (likely subtags, so "zh-CN" is
 * zh-Hans and "zh-TW" zh-Hant), keeping the phone's region and choices (es-MX writes 8.4, en-GB its own forms);
 * else the words' language alone.
 */
internal fun uiLocaleFor(wordsTag: String, phone: Locale): Locale {
    val words = Locale.forLanguageTag(wordsTag)
    val p = ULocale.addLikelySubtags(ULocale.forLocale(phone))
    val w = ULocale.addLikelySubtags(ULocale.forLocale(words))
    return if (p.language == w.language && p.script == w.script) phone else words
}
