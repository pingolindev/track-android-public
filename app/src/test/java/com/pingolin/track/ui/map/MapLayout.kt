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

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import com.pingolin.track.R
import com.pingolin.track.ui.main.placeBell
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The map as the app lays it out, for the layout tests: the activity's layout (the notification bell, its
 * label, the bottom navigation) with the map's own in its nav host, at [fontScale] on the test's screen. The
 * column is placed by TopLeftStack and the bell by placeBell, as the fragment and the activity do. A 24dp
 * status bar and a 24dp navigation bar stand in for the insets, as on a 360dp phone; MapLibre's view needs
 * its native library, so a plain view stands in for it.
 */
internal class MapLayout(fontScale: Float) {
    val activity: AppCompatActivity
    val root: View
    val bell: View
    val bellLabel: TextView
    val stack: TopLeftStack

    /** The selector's top: the status bar, then the card margin. The bell's too (placeBell). */
    val base: Int

    val density get() = activity.resources.displayMetrics.density
    val Int.dp get() = (this * density).toInt()

    init {
        RuntimeEnvironment.setFontScale(fontScale)
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Track)
        val inflater = LayoutInflater.from(activity).cloneInContext(activity)
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? = when (name) {
                "org.maplibre.android.maps.MapView" -> View(context, attrs)
                // The nav host, as a plain frame: the map's layout goes in it as the fragment's view does.
                "androidx.fragment.app.FragmentContainerView" -> FrameLayout(context, attrs)
                else -> null
            }

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                onCreateView(null, name, context, attrs)
        }
        val window = inflater.inflate(R.layout.activity_main, null)
        window.findViewById<View>(R.id.mainContent).visibility = View.VISIBLE
        window.findViewById<View>(R.id.bottom_nav).visibility = View.VISIBLE
        val navHost = window.findViewById<ViewGroup>(R.id.nav_host_fragment)
        root = inflater.inflate(R.layout.fragment_map, navHost, false)
        navHost.addView(root)
        activity.setContentView(window)
        window.setPadding(0, 0, 0, 24.dp)
        bell = window.findViewById(R.id.notificationBellContainer)
        bellLabel = window.findViewById(R.id.labelNotifications)

        val margin = activity.resources.getDimensionPixelSize(R.dimen.card_margin_top)
        base = 24.dp + margin
        placeBell(bell, bellLabel, topPx = base, marginPx = margin)
        val guardian = view<TextView>(R.id.tvGuardianManaged)
        val syncError = view<TextView>(R.id.tvSyncError)
        stack = TopLeftStack(
            selector = view(R.id.cardCircleSelector),
            guardianLine = guardian,
            syncError = syncError,
            controls = view(R.id.mapControlsLeft),
            refreshPill = view(R.id.cardRefreshStatus),
            layersCard = view(R.id.cardMapLayers),
            baseTopPx = { base },
            // The location line, as MapFragment.placeLocationOff places it.
            onPlaced = {
                val above = listOf(guardian, syncError)
                    .filter { it.visibility == View.VISIBLE && it.height > 0 }
                    .map { (it.layoutParams as FrameLayout.LayoutParams).topMargin to it.height }
                val top = locationOffTop(base + 56.dp + 64.dp, above, gapPx = 8.dp)
                view<View>(R.id.tvLocationOff).updateLayoutParams<FrameLayout.LayoutParams> { topMargin = top }
            },
        )
        stack.attach()
    }

    fun <T : View> view(id: Int): T = root.findViewById(id)

    /** What the open menu's labels leave clear, as the map lists it (MENU_CHROME). */
    fun menuChrome(): List<View> = MENU_CHROME.map { root.rootView.findViewById(it) }

    /**
     * Every label in the window, the activity's and the map's, by their ids ("label…"): found in the layouts,
     * not taken from the map's own list, so a label the map leaves out of its fitting is still measured.
     */
    fun labels(): List<View> {
        val found = mutableListOf<View>()
        fun walk(v: View) {
            // A generated id has no entry name.
            val entry = if (v.id == View.NO_ID) null else runCatching { activity.resources.getResourceEntryName(v.id) }.getOrNull()
            if (entry?.startsWith("label") == true) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root.rootView)
        return found
    }

    fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    fun rectOf(v: View): Rect {
        val xy = IntArray(2)
        v.getLocationInWindow(xy)
        return Rect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
    }

    fun name(v: View): String = activity.resources.getResourceEntryName(v.id)
}
