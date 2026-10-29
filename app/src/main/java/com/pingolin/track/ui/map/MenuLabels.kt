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

package com.pingolin.track.ui.map

import android.view.View
import com.pingolin.track.R

/** A view's place in its window, in px. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** At least [gap] from [other] on one side or another: not over it, not touching it. */
    fun clearOf(other: Box, gap: Int): Boolean =
        right + gap <= other.left || other.right + gap <= left || bottom + gap <= other.top || other.bottom + gap <= top
}

/**
 * Which of the dot menu's labels show, each in turn in [labels]' order: one [gapPx] clear of all of [chrome]
 * and of every label shown before it. One that isn't is hidden whole, never cut short or left lying over
 * something: the button it names keeps its own name for TalkBack.
 *
 * At a large font the labels can meet: the notification bell's lies over the circle selector, which names
 * the circle the menu's sends go to, and the controls' run into the stack's on the other side once a
 * guardian-managed account's line moves the controls down into the stack's rows.
 */
internal fun menuLabelsShown(labels: List<Box>, chrome: List<Box>, gapPx: Int): List<Boolean> {
    val shown = mutableListOf<Box>()
    return labels.map { label ->
        val clear = chrome.all { label.clearOf(it, gapPx) } && shown.all { label.clearOf(it, gapPx) }
        if (clear) shown += label
        clear
    }
}

/**
 * The dot menu's labels, in the order they claim room: the right-hand stack's, which name its actions (a send
 * among them), then the notification bell's (the activity's), then the left-hand controls'.
 */
internal val MENU_LABELS = listOf(
    R.id.labelDestinations, R.id.labelHistory, R.id.labelSearch, R.id.labelQuickCheckIn, R.id.labelSendLocation,
    R.id.labelNotifications,
    R.id.labelCenterOnAll, R.id.labelRecenterOnMe, R.id.labelLayers, R.id.labelCompass,
)

/** What a label leaves clear: the map's own chrome that may show beside the open menu, and the bell. */
internal val MENU_CHROME = listOf(
    R.id.cardCircleSelector, R.id.tvGuardianManaged, R.id.tvSyncError, R.id.tvLocationOff,
    R.id.cardRefreshStatus, R.id.cardMapLayers, R.id.cardTimeRange,
    R.id.btnCenterOnAll, R.id.btnRecenterOnMe, R.id.btnLayers, R.id.btnCompass,
    R.id.fabDestinations, R.id.fabHistory, R.id.fabExpand, R.id.fabSearch, R.id.fabTracking, R.id.fabQuickCheckIn,
    R.id.fabSendLocation,
    R.id.notificationBellContainer,
)

/**
 * Shows each label the open menu has out in [window] (the activity's views and the map's: not gone, in a row
 * that shows) that fits ([menuLabelsShown]), and hides the rest. Hidden is INVISIBLE: a label keeps its room
 * in its row, so hiding one moves nothing.
 */
internal fun fitMenuLabels(window: View, gapPx: Int) {
    val out = MENU_LABELS.mapNotNull { window.findViewById<View>(it) }
        .filter { it.visibility != View.GONE && (it.parent as? View)?.isShown == true && it.width > 0 }
    val fixed = MENU_CHROME.mapNotNull { window.findViewById<View>(it) }
        .filter { it.isShown && it.width > 0 && it.height > 0 }
        .map { it.layoutBoxInWindow() }
    menuLabelsShown(out.map { it.layoutBoxInWindow() }, fixed, gapPx).forEachIndexed { index, show ->
        val wanted = if (show) View.VISIBLE else View.INVISIBLE
        if (out[index].visibility != wanted) out[index].visibility = wanted
    }
}

/** Where [this] view's layout puts it in its window, in px: a row's entry animation (its translationY) left out. */
internal fun View.layoutBoxInWindow(): Box {
    var x = 0
    var y = 0
    var view: View = this
    while (true) {
        x += view.left
        y += view.top
        val parent = view.parent as? View ?: break
        x -= parent.scrollX
        y -= parent.scrollY
        view = parent
    }
    return Box(x, y, x + width, y + height)
}
