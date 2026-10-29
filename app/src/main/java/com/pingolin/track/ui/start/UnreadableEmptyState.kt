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

package com.pingolin.track.ui.start

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.pingolin.track.R
import com.pingolin.track.util.DatabaseUnavailable

/**
 * An empty list's words while this phone's database can't be opened: the screen shows nothing
 * because nothing could be read, not because there is nothing. So its empty state says that, in
 * one shared line, in place of its own words ("No circles yet"), and its create button hides:
 * "Create a circle" then could make a second one. Both are as they were once the database
 * opens, and the list fills in by itself.
 */
internal object UnreadableEmptyState {

    private const val LINE = "database_unavailable_line"

    /** [emptyState] shown when [empty], with the line in place of its words while the database can't be opened; [create] hidden then. */
    fun show(emptyState: ViewGroup, empty: Boolean, vararg create: View) {
        val unreadable = DatabaseUnavailable.failure.value != null
        val line = emptyState.findViewWithTag<TextView>(LINE)
        if (unreadable && line == null) {
            emptyState.children.forEach { child ->
                child.setTag(R.id.unreadable_hidden, child.visibility)
                child.visibility = View.GONE
            }
            emptyState.addView(
                TextView(emptyState.context).apply {
                    tag = LINE
                    gravity = Gravity.CENTER
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setText(R.string.database_unavailable_note)
                },
            )
        } else if (!unreadable && line != null) {
            emptyState.removeView(line)
            emptyState.children.forEach { child ->
                (child.getTag(R.id.unreadable_hidden) as? Int)?.let { child.visibility = it }
                child.setTag(R.id.unreadable_hidden, null)
            }
        }
        emptyState.isVisible = empty
        for (button in create) {
            if (unreadable && button.isVisible) {
                button.setTag(R.id.unreadable_hidden, View.VISIBLE)
                button.visibility = View.GONE
            } else if (!unreadable && button.getTag(R.id.unreadable_hidden) == View.VISIBLE) {
                button.setTag(R.id.unreadable_hidden, null)
                button.visibility = View.VISIBLE
            }
        }
    }
}
