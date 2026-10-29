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

package com.pingolin.track.ui.circles

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.LocationConsentNoticeResponse

/**
 * The just-in-time location notice, shown before a circle is joined or created
 * — agreeing is what joins or creates it.
 *
 * The notice has to say what leaves the device, recipients (circle members AND
 * vendors), purposes, retention, controls. The text itself comes from the
 * server, because the server is what records which text a consent was given
 * against — a bundled paraphrase would file a record naming something the user
 * never read.
 *
 * Two properties this has to keep:
 *
 *  - NOT cancellable by tapping outside or pressing back. An opt-in statute
 *    wants an unambiguous affirmative act, and a dialog that dismisses on a
 *    stray tap cannot tell "yes" from "go away". "Not now" is a real answer and
 *    is always available.
 *  - The affirmative button says what it does: "OK" on a notice about
 *    continuous location would not tell the person what they are agreeing to.
 *
 * Views rather than Compose: this app is Fragments/Views throughout.
 */
object LocationConsentDialog {

    /** What agreeing does: joining the circle an invitation is for, or creating one. */
    enum class Purpose(val acceptLabel: Int) {
        JOIN(R.string.location_notice_join),
        CREATE(R.string.location_notice_create),
    }

    /**
     * Consent is membership: if you don't consent, you can't be in the circle. This
     * runs BEFORE the accept or the creation and is the only way to either: agreeing
     * joins (or creates) and shares, and "Not now" does neither — an invitation
     * stays pending, a circle is not created.
     *
     * @param onGrant invoked when the user agrees, with the notice version they were
     *   shown. The caller joins or creates with it; this dialog does no I/O.
     * @param onDecline invoked on "Not now".
     */
    fun show(
        context: Context,
        circleName: String,
        notice: LocationConsentNoticeResponse?,
        purpose: Purpose,
        onGrant: (noticeVersion: String) -> Unit,
        onDecline: () -> Unit,
    ) {
        val text = notice?.text ?: bundledNotice(context)
        val version = notice?.version ?: versionOf(text) ?: "unknown"

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.location_notice_title, circleName))
            .setView(buildBody(context, text))
            .setCancelable(false)
            .setPositiveButton(purpose.acceptLabel) { _, _ -> onGrant(version) }
            .setNegativeButton(R.string.location_notice_decline) { _, _ -> onDecline() }
            .show()
    }

    /**
     * The notice as the server serves it, for when fetching it failed: a
     * byte-for-byte copy of the server's current notice (`location_notice_v1_5.md`,
     * in res/raw), so what is shown is exactly the text a consent given against it is
     * digested as. The server still stamps its own version and digest. A new notice
     * version means a new copy here (LocationNoticeBundledTest pins it).
     */
    private fun bundledNotice(context: Context): String =
        context.resources.openRawResource(R.raw.location_notice_v1_5).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** The version the text itself declares (`version: 1.1`), as the server reads it. */
    internal fun versionOf(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("version:") }?.removePrefix("version:")?.trim()

    /**
     * Render the notice's small Markdown subset: `##` headings and paragraphs.
     *
     * Deliberately not a general Markdown renderer — that is all the notice text
     * uses, and anything unrecognised falls through to a paragraph, so an
     * unsupported construct degrades to readable text instead of vanishing.
     */
    private fun buildBody(context: Context, markdown: String): View {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad / 2, pad / 2, pad + pad / 2, 0)
        }

        for (block in parse(markdown)) {
            val view = TextView(context).apply {
                when (block) {
                    // `primary` and `on_surface` both carry values-night
                    // overrides, so the dialog follows the system theme without
                    // this file branching on it.
                    is Block.Heading -> {
                        text = block.text.uppercase()
                        setTypeface(typeface, Typeface.BOLD)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        letterSpacing = 0.06f
                        setTextColor(ContextCompat.getColor(context, R.color.primary))
                        gravity = Gravity.START
                    }
                    is Block.Paragraph -> {
                        text = block.text
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = if (block is Block.Heading) pad else pad / 3
            column.addView(view, lp)
        }

        // The notice is longer than a phone screen. Without a ScrollView the
        // last section — Stopping, which is the one telling people how to
        // withdraw — would be the part that gets clipped.
        //
        // CAPPED, because an uncapped one takes every pixel the dialog has and
        // the button bar is what pays for it: the negative button ends up
        // outside the dialog's own rounded background, with "Not now" half off
        // the panel and shorter than the 48dp minimum touch target — the control
        // someone declining has to hit. A fixed share of the screen (62 %) is
        // not enough on its own: at a font scale of 1.1 the title and the two
        // stacked buttons need more than the rest. So the cap is also what the
        // dialog has left once its buttons have all the room they want.
        return CappedScrollView(
            context,
            (context.resources.displayMetrics.heightPixels * 0.62f).toInt(),
        ).apply { addView(column) }
    }

    /**
     * A ScrollView that will not measure taller than [maxHeightPx], nor than the dialog can give it
     * with every button whole.
     *
     * AT_MOST rather than EXACTLY so short content still wraps to its own height
     * — a fixed height would leave a tall empty box under a two-line notice.
     */
    private class CappedScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maxHeightPx
            } else {
                minOf(maxHeightPx, MeasureSpec.getSize(heightMeasureSpec) - buttonsStillWant())
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(cap.coerceAtLeast(0), MeasureSpec.AT_MOST),
            )
        }

        /**
         * How much more height the dialog's button bar wants than the room it was promised. The
         * dialog's layout measures the bar first at its full height, promises it only its minimum
         * (its first button), and offers this panel the rest; taking all of that leaves the
         * second button, "Not now" when they stack, half off the dialog.
         */
        private fun buttonsStillWant(): Int {
            val bar = (rootView.findViewById<View>(android.R.id.button1)?.parent as? View)?.parent as? ScrollView ?: return 0
            return (bar.measuredHeight - minimumHeightOf(bar)).coerceAtLeast(0)
        }

        /** As the dialog's layout reads it: a view's minimum height, or its only child's. */
        private fun minimumHeightOf(view: View): Int {
            val own = view.minimumHeight
            if (own > 0) return own
            return if (view is android.view.ViewGroup && view.childCount == 1) minimumHeightOf(view.getChildAt(0)) else 0
        }
    }

    private sealed class Block {
        data class Heading(val text: String) : Block()
        data class Paragraph(val text: String) : Block()
    }

    /**
     * Split on blank lines.
     *
     * The `version:`/`effective:` front matter and the `#` title are dropped —
     * the version is recorded rather than read, and the title is the dialog's
     * own. Everything from `## The question` on is dropped too: its text is the
     * positive button.
     */
    private fun parse(markdown: String): List<Block> {
        val out = mutableListOf<Block>()
        for (raw in markdown.split("\n\n")) {
            val chunk = raw.trim()
            if (chunk.isEmpty()) continue
            if (chunk.startsWith("version:") || chunk.startsWith("effective:")) continue
            if (chunk.startsWith("## ")) {
                val title = chunk.removePrefix("## ").trim()
                if (title.equals("The question", ignoreCase = true)) break
                out.add(Block.Heading(title))
                continue
            }
            if (chunk.startsWith("# ")) continue
            out.add(Block.Paragraph(chunk.lines().joinToString(" ") { it.trim() }))
        }
        return out
    }
}
