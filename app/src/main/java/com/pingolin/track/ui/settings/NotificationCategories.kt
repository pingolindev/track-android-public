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

package com.pingolin.track.ui.settings

/**
 * The rows of the notification-categories dialog, and — the point of this file —
 * which of them are actually preferences.
 *
 * WHY THE EMERGENCY ROW IS NOT A SWITCH
 *   The server's preference gate treats the emergency family as undeniable. It
 *   bypasses the global switch, quiet hours and every per-category opt-out, and it
 *   is checked BEFORE the preferences are loaded, so it cannot even be silenced by
 *   a failed query. `sos_resolved` is undeniable for the same reason: an alarm
 *   nobody can silence, paired with an all-clear they CAN, would leave a permanent
 *   "someone needs attention" marker with nothing able to clear it.
 *
 *   So `sos_push` and `sos_email` govern nothing. A row bound to them would render
 *   a promise the system does not keep — a switch a worried user could turn off,
 *   believing they had, while the alerts kept arriving. That is worse than no
 *   switch, and it is worse in the direction that matters: it teaches someone that
 *   the emergency channel is theirs to configure.
 *
 *   The row is kept, and locked, rather than deleted. Someone who goes looking for
 *   that switch should learn WHY it is not there instead of finding a silent gap —
 *   and a bare gap invites a change that "restores" it by making emergencies
 *   deniable again, which is the exact outcome the lock exists to prevent.
 *
 * A locked category is modelled as one with no server keys at all, so it is not
 * that the dialog declines to write it — there is nothing there to write. See
 * [buildUpdate].
 *
 * TERMINOLOGY: this family is "Circle notifications" on the web and iOS, and the
 * app says "notify my circle", never "SOS". The server columns are named `sos_…`;
 * that is a wire detail and stays out of the UI.
 */
object NotificationCategories {

    data class Category(
        /** The row's name on its own, without any lock wording. */
        val label: String,
        /** Server column for the push opt-out. Null means there is nothing to opt out of. */
        val pushKey: String?,
        /** Server column for the email opt-out. Null means the category has no email channel. */
        val emailKey: String?,
    ) {
        /** A category with no push key is not a preference; it is a statement. */
        val isLocked: Boolean get() = pushKey == null

        /** What the push list shows. A locked row says so where the user is reading. */
        val pushRowLabel: String get() = if (isLocked) "$label — always on" else label
    }

    /**
     * The locked row's line in the dialog header.
     *
     * It is rendered THERE, beside a lock glyph, rather than as the fifth entry in
     * the multi-choice list. In the list it would draw a live, ticked checkbox, and
     * a locked item that looks toggleable makes exactly the promise the lock exists
     * to avoid: even if tapping it put the tick back, the control itself would
     * already have told the user that the choice was theirs.
     *
     * It is still listed, still explained, and still in the dialog the user opened
     * looking for it.
     */
    val lockedStatement: String get() = ALL.single { it.isLocked }.pushRowLabel

    /**
     * Shown above the push list, so the reason is on screen before the user goes
     * hunting for the missing switch rather than only after they tap it.
     *
     * NO SAFETY-SERVICE VOCABULARY. Track is not a safety app — the Terms of Service
     * open by saying so, no alert reaches police, fire or ambulance, and nobody at
     * Pingolin watches them — so a settings screen promising that help "gets
     * through" would contradict the product's own disclaimer, in the one place a
     * worried user goes looking for reassurance. The feature is "notify my circle"
     * and what arrives is a circle notification; the guarantee the app can make is
     * about DELIVERY, not about rescue. See [BANNED_COPY].
     */
    const val LOCKED_NOTE: String =
        "Circle notifications can't be turned off. They ignore quiet hours and every " +
            "switch here, so you always see when someone in your circle asks for attention."

    /**
     * The e-mail step's line beside the locked row. Circle notifications are e-mailed to every
     * member with an address whatever is set here: `sos_email` governs nothing, as `sos_push`
     * doesn't. Shown, as on the web, rather than left a silent gap.
     */
    const val EMAIL_LOCKED_NOTE: String =
        "When someone in your circle asks for attention, you are always emailed too, " +
            "whatever is set here."

    /** Said on the spot if someone taps the locked row anyway. */
    const val LOCKED_TOAST: String = "Circle notifications can't be turned off."

    /**
     * Words this screen must never use, checked by
     * `NotificationCategoriesTest.no copy claims a safety service`.
     *
     * "SOS" is one; the rest are the same promise in different words. Kept as data
     * rather than a prose rule so that new copy here trips a test.
     */
    val BANNED_COPY: List<String> =
        listOf("SOS", "emergency", "911", "rescue", "first responder", "gets through")

    /**
     * Every row, in display order. "Destinations" covers both `destination_shared` and
     * `destination_arrival` — they share one server-side opt-out — and has no email
     * column, so it drops out of the email step while staying in the push one.
     * "Check-ins" is push-only for a plainer reason: nothing sends a check-in by
     * email, so there is deliberately no `check_in_email` column to offer. "Safety
     * alerts" is push-only too: nothing can send those by e-mail, so there is
     * deliberately no `safety_alert_email` to write either.
     *
     * Check-ins sits immediately after the locked row on purpose. The contrast is the
     * useful part: one category nobody can mute, marked as such, beside one the user
     * has just been given control of.
     */
    val ALL: List<Category> = listOf(
        Category("Circle invites", "circle_invite_push", "circle_invite_email"),
        Category("Member joined", "member_joined_push", "member_joined_email"),
        Category("Place alerts", "place_alert_push", "place_alert_email"),
        // Push only: low battery and inactivity are judged on the watcher's own phone, so the
        // server never learns who watches whom, and an e-mail would hand it every firing. Nothing
        // sends them by e-mail, so there is no e-mail switch for them.
        Category("Safety alerts", "safety_alert_push", null),
        Category("Circle notifications", null, null),
        Category("Check-ins", "check_in_push", null),
        Category("Destinations", "destination_push", null),
    )

    /** Rows the user can actually change. */
    val deniable: List<Category> get() = ALL.filter { !it.isLocked }

    /** Rows the email step lists — the ones with an email channel to control. */
    val withEmail: List<Category> get() = ALL.filter { it.emailKey != null }

    /**
     * What to report for one row, given what the dialog is showing.
     *
     * Named and tested because [buildUpdate] honouring a null is only half the rule —
     * something has to actually PRODUCE the null. A caller that hands over the
     * displayed tick directly type-checks, passes every test of [buildUpdate], and
     * still pins every row the user never touched. This is the rule that must not be
     * inlined by hand:
     *
     *  - [touched] — the user tapped this row, so [shown] is their answer, `false`
     *    included. That is the point of offering the control.
     *  - otherwise — repeat [stored], the value the server already held. When that is
     *    null the user has never expressed a preference and there is nothing to
     *    report, so the key is omitted and the server's inherit survives.
     *
     * [shown] is deliberately ignored when the row was not touched: for an unset row
     * it is the rendered inherit, not a preference.
     */
    fun answerFor(touched: Boolean, shown: Boolean, stored: Boolean?): Boolean? =
        if (touched) shown else stored

    /**
     * The body of the preferences PUT.
     *
     * A locked category contributes nothing, because it has no keys to contribute.
     * That is deliberate: the guarantee is structural, not a filter someone can
     * drop while refactoring the loop. Writing `sos_push: false` would persist a
     * user's belief that they had turned emergencies off, which the server would
     * then ignore — the same broken promise, recorded.
     *
     * ABSENT IS A THIRD ANSWER, distinct from true and from false. These columns
     * are nullable, and NULL does not mean "off" — it means "inherit
     * `push_enabled`", which the server resolves itself. A brand-new column is
     * NULL for every existing user, so a row the user has never touched has no
     * value to report: [push] and [email] return null for it and the key is left
     * out entirely.
     *
     * That matters most when a new column ships: it is NULL everywhere, and the
     * dialog renders such a row as ticked, because inheriting an enabled global IS
     * on. If saving then wrote that rendered tick back as a concrete value, merely
     * opening the dialog and pressing Save would convert every user from "inherit"
     * to "pinned" — silently, and for a preference they never expressed — and
     * turning the global off afterwards would not carry the category with it.
     */
    fun buildUpdate(
        pushEnabled: Boolean,
        emailEnabled: Boolean,
        push: (Category) -> Boolean?,
        email: (Category) -> Boolean?,
    ): MutableMap<String, Any> {
        val update = mutableMapOf<String, Any>(
            "push_enabled" to pushEnabled,
            "email_enabled" to emailEnabled,
        )
        for (category in ALL) {
            category.pushKey?.let { key -> push(category)?.let { update[key] = it } }
            category.emailKey?.let { key -> email(category)?.let { update[key] = it } }
        }
        return update
    }
}
