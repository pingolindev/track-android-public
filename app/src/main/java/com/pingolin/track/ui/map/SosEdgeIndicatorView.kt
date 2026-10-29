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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Edge arrows for active SOS alerts that are outside the current viewport.
 *
 * Sits directly above the MapView and draws one chevron per off-screen alert,
 * pinned to the edge of the screen in the direction of the alert. Tapping one
 * reports its id so the map can fly to it.
 *
 * Why a custom View and not more map layers: an off-screen marker has, by
 * definition, no on-screen map coordinate to attach to. The position is a
 * screen-space construction — a ray from the viewport centre in the compass
 * direction of the alert, clipped to the viewport rectangle — so it belongs in
 * the view hierarchy, not the style.
 *
 * Direction comes from the geographic bearing between the map centre and the
 * alert, rotated by the camera's own bearing, rather than from projecting the
 * alert to screen space and normalising. Projection of a far-off point can be
 * degenerate (behind the camera when the map is tilted, or numerically wild
 * near the antipode); a great-circle bearing is well defined everywhere.
 */
class SosEdgeIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One off-screen alert: its id and the compass bearing to it, in SCREEN
     *  degrees (geographic bearing already corrected for camera rotation). */
    data class Indicator(val sosId: String, val screenBearingDegrees: Double)

    /** Invoked with the alert id when the user taps one of the arrows. */
    var onIndicatorClick: ((String) -> Unit)? = null

    private var indicators: List<Indicator> = emptyList()

    /** Where each indicator was last drawn, for hit testing. */
    private val hitTargets = mutableListOf<Pair<String, PointF>>()

    private val density = resources.displayMetrics.density
    private val edgeInset = 28f * density
    private val badgeRadius = 20f * density
    private val touchSlop = 28f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e53935")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    /**
     * The warning triangle inside the badge, rather than the word "SOS". This
     * badge is 40dp across at most and has to work in every locale; a glyph
     * needs no translation and no abbreviation.
     */
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(2f * density)
    }

    /**
     * The exclamation, painted in the badge fill colour rather than punched out
     * — see MapFragment.drawWarningTriangle for why clearing is wrong here.
     */
    private val glyphMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e53935")
        style = Paint.Style.FILL
    }
    private val glyph = Path()
    private val chevron = Path()

    fun setIndicators(next: List<Indicator>) {
        // Cheap equality guard: this is driven by every camera frame, and
        // invalidating on an unchanged list would repaint the overlay
        // continuously through a pan.
        if (next == indicators) return
        indicators = next
        // INVISIBLE rather than GONE: a GONE view stops being laid out, so its
        // width/height fall to 0 and the caller — which needs them to locate the
        // viewport edge — can never bring it back.
        visibility = if (next.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitTargets.clear()
        if (indicators.isEmpty() || width == 0 || height == 0) return

        for (indicator in indicators) {
            val at = edgePosition(indicator.screenBearingDegrees)
            hitTargets.add(indicator.sosId to at)

            canvas.drawCircle(at.x, at.y, badgeRadius, fillPaint)
            canvas.drawCircle(at.x, at.y, badgeRadius, strokePaint)

            drawWarningGlyph(canvas, at.x, at.y, badgeRadius * 1.05f)

            drawChevron(canvas, at, indicator.screenBearingDegrees)
        }
    }

    /**
     * Cast a ray from the centre of the view along [bearingDegrees] (0 = up the
     * screen, increasing clockwise) and return where it meets the inset
     * viewport rectangle.
     *
     * Solved per-axis rather than by walking the ray: for each axis, the
     * distance at which the ray would cross that boundary is a single division,
     * and the smaller of the two is the edge actually hit.
     */
    private fun edgePosition(bearingDegrees: Double): PointF {
        val cx = width / 2f
        val cy = height / 2f
        val halfW = cx - edgeInset
        val halfH = cy - edgeInset

        val radians = Math.toRadians(bearingDegrees)
        val dx = sin(radians).toFloat()
        // Screen y grows downward while north is up, hence the negation.
        val dy = (-cos(radians)).toFloat()

        // A perfectly axis-aligned direction has a zero component; guard the
        // division rather than special-casing the four cardinal bearings.
        val tx = if (abs(dx) < 1e-6f) Float.MAX_VALUE else halfW / abs(dx)
        val ty = if (abs(dy) < 1e-6f) Float.MAX_VALUE else halfH / abs(dy)
        val t = minOf(tx, ty)

        return PointF(cx + dx * t, cy + dy * t)
    }

    /**
     * A point-up warning triangle with an exclamation, centred in the badge.
     *
     * Point-up (ISO 7010 W001, "general warning") rather than the point-down
     * give-way sign, which specifically means yield to other traffic.
     */
    private fun drawWarningGlyph(canvas: Canvas, cx: Float, cy: Float, width: Float) {
        val h = width * 0.88f
        glyph.reset()
        glyph.moveTo(cx, cy - h / 2f)
        glyph.lineTo(cx + width / 2f, cy + h / 2f)
        glyph.lineTo(cx - width / 2f, cy + h / 2f)
        glyph.close()
        canvas.drawPath(glyph, glyphPaint)

        val barW = width * 0.11f
        canvas.drawRoundRect(
            cx - barW / 2f, cy - h * 0.10f, cx + barW / 2f, cy + h * 0.18f,
            barW / 2f, barW / 2f, glyphMarkPaint
        )
        canvas.drawCircle(cx, cy + h * 0.31f, barW * 0.62f, glyphMarkPaint)
    }

    /** A small filled triangle just outside the badge, pointing along the bearing. */
    private fun drawChevron(canvas: Canvas, at: PointF, bearingDegrees: Double) {
        val radians = Math.toRadians(bearingDegrees)
        val dx = sin(radians).toFloat()
        val dy = (-cos(radians)).toFloat()
        // Perpendicular, for the triangle's base.
        val px = -dy
        val py = dx

        val tipDistance = badgeRadius + 9f * density
        val baseDistance = badgeRadius + 1f * density
        val halfBase = 6f * density

        chevron.reset()
        chevron.moveTo(at.x + dx * tipDistance, at.y + dy * tipDistance)
        chevron.lineTo(at.x + dx * baseDistance + px * halfBase, at.y + dy * baseDistance + py * halfBase)
        chevron.lineTo(at.x + dx * baseDistance - px * halfBase, at.y + dy * baseDistance - py * halfBase)
        chevron.close()
        canvas.drawPath(chevron, fillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only claim taps that actually land on an arrow. Returning false
        // otherwise lets the gesture reach the MapView underneath, so this
        // overlay doesn't swallow panning across the whole screen.
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        val hit = hitTargets.firstOrNull { (_, at) ->
            hypot(event.x - at.x, event.y - at.y) <= touchSlop
        } ?: return false
        onIndicatorClick?.invoke(hit.first)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
