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

import android.content.SharedPreferences
import android.util.Log

/**
 * A per-account store's circles, each under its ONE spelling: entries are "{owner}|{circle}{rest}",
 * the circle part [AccountIds.storeKey] of the circle's id and [rest] empty or starting '#', '|'
 * or ':'. What an earlier version of the app kept under another spelling of a circle, or with
 * another spelling of an id in it, is FOLDED into that one spelling at load ([fold]), by the
 * store's own [Rules] (the same rules as the web app's):
 *  - every spelling's value is weighed, and a removal, a tombstone or a dead invitation stands;
 *  - a single value the spellings disagree on is never decided by the other spelling's word:
 *    nothing stands, or the circle reads as unreadable ([Outcome.Refuse]);
 *  - the fold and the removal of the other spellings are ONE commit, which SharedPreferences
 *    writes as one file (the existing file kept until the new one is whole), read back before the
 *    fold counts: no file has the other spellings gone without the fold, and a circle whose fold
 *    doesn't read back reads as unreadable until one does;
 *  - a file that doesn't read moves nothing and has nothing written over it;
 *  - a key whose circle part is no UUID is kept as it is, matched only by itself;
 *  - idempotent: a second fold finds nothing to do.
 */
internal object CircleEntries {

    private const val TAG = "CircleEntries"
    private const val URN = "urn:uuid:"

    /**
     * One key's value for one entry: the circle [spelling] it was kept under, whether it is the
     * entry's one key ([canonical]), and what the file holds.
     */
    data class Held(val spelling: String, val canonical: Boolean, val value: Any?)

    /** What stands for one entry after a fold. */
    sealed class Outcome {
        /** This value, under the one key. */
        data class Keep(val value: Any) : Outcome()

        /** Nothing, under any key: the question is asked again, the keys unconfirmed, the member unticked. */
        object Drop : Outcome()

        /** Undecidable: every key stays as stored, and the circle reads as unreadable. */
        object Refuse : Outcome()
    }

    /** A store's rules for its circles' entries. */
    interface Rules {
        /** [rest] with the ids in it in their one spelling; as it is by default. */
        fun rest(rest: String): String = rest

        /**
         * One circle's entries, each (by its rest) with every key's value, the one key's first, and
         * the [spellings] the circle is kept under, to what stands. An entry left out of the answer
         * stays as stored.
         */
        fun fold(entries: Map<String, List<Held>>, spellings: Set<String>): Map<String, Outcome>
    }

    /** What a fold did. */
    class Folded(
        /** Nothing is left to fold: the file read, and every fold it wrote read back. */
        val done: Boolean,
        /** "{owner}|{circle}" of each circle read as unreadable: a rule refused it, or its fold didn't land. */
        val unreadable: Set<String>,
    )

    /** The circle part of [key] and what follows it, when [key] is [owner]'s and names a circle; null otherwise. */
    fun split(key: String, owner: String): Pair<String, String>? {
        val prefix = "$owner|"
        if (!key.startsWith(prefix)) return null
        val tail = key.substring(prefix.length)
        // "urn:uuid:…" spells a UUID: its colons are no separator.
        val from = if (tail.startsWith(URN)) URN.length else 0
        val end = tail.indexOfAny(charArrayOf('#', '|', ':'), from).let { if (it < 0) tail.length else it }
        val circle = tail.substring(0, end)
        return if (circle.isEmpty()) null else circle to tail.substring(end)
    }

    /** The one spelling of the circle [part] names: its canonical id, or [part] as it is when it's no UUID. */
    private fun circleOf(part: String): String = AccountIds.canonical(part) ?: part

    /**
     * Fold [owner]'s entries in [prefs] kept under another spelling of a circle, or with another
     * spelling of an id in them, into each circle's one spelling, by [rules].
     */
    fun fold(store: String, prefs: () -> SharedPreferences, owner: String, rules: Rules): Folded {
        val all = try {
            StoreUnreadable.reading(store) { prefs().all }
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: its circles are folded under their one spelling later")
            return Folded(done = false, unreadable = emptySet())
        }
        // circle → rest (its ids in their one spelling) → the keys holding it, with the circle spelling of each
        val circles = linkedMapOf<String, MutableMap<String, MutableList<Pair<String, String>>>>()
        for (key in all.keys) {
            val (part, rest) = split(key, owner) ?: continue
            // A circle part that is no UUID is kept as it is, matched only by itself.
            val one = AccountIds.canonical(part) ?: continue
            circles.getOrPut(one) { linkedMapOf() }.getOrPut(rules.rest(rest)) { mutableListOf() } += key to part
        }
        val writes = linkedMapOf<String, Any?>()
        val refused = mutableSetOf<String>()
        val folding = mutableSetOf<String>()
        for ((one, entries) in circles) {
            val circleKey = "$owner|$one"
            val held = entries.mapValues { (rest, keys) ->
                keys.map { (key, part) -> Held(part, key == "$circleKey$rest", all[key]) }.sortedByDescending { it.canonical }
            }
            val outcome = rules.fold(held, entries.values.flatten().map { it.second }.toSet())
            if (outcome.values.any { it == Outcome.Refuse }) {
                refused += circleKey
                continue
            }
            val circleWrites = linkedMapOf<String, Any?>()
            for ((rest, keys) in entries) {
                val target = "$circleKey$rest"
                when (val o = outcome[rest] ?: continue) {
                    is Outcome.Keep -> if (!same(all[target], o.value)) circleWrites[target] = o.value
                    Outcome.Drop -> if (target in all) circleWrites[target] = null
                    Outcome.Refuse -> Unit
                }
                for ((key, _) in keys) if (key != target) circleWrites[key] = null
            }
            if (circleWrites.isNotEmpty()) {
                writes += circleWrites
                folding += circleKey
            }
        }
        if (refused.isNotEmpty()) {
            Log.w(TAG, "$store: ${refused.size} circle(s) whose entries under two spellings can't be folded; read as unreadable")
        }
        if (writes.isEmpty()) return Folded(done = true, unreadable = refused)
        // One commit: the fold and the other spellings' removal land together or not at all. A commit
        // the disk refused after the change was made in memory still reads back: this process reads
        // the fold, and the next one folds the same file again.
        runCatching {
            val editor = prefs().edit()
            for ((key, value) in writes) put(editor, key, value)
            editor.commit()
        }
        val back = runCatching { val now = prefs().all; writes.all { (key, value) -> same(now[key], value) } }.getOrDefault(false)
        if (!back) {
            Log.w(TAG, "$store: ${folding.size} circle(s) not folded under their one spelling yet; read as unreadable until they are")
            return Folded(done = false, unreadable = refused + folding)
        }
        Log.i(TAG, "$store: ${folding.size} circle(s) folded under their one spelling")
        return Folded(done = true, unreadable = refused)
    }

    /**
     * Every key of [owner]'s in [all] kept for the circle [circleId] names, under any spelling of it,
     * and, with [rest], for that entry only (its ids in any spelling, by [rules]).
     */
    fun keysOf(all: Map<String, *>, owner: String, circleId: String, rules: Rules? = null, rest: String? = null): List<String> {
        val one = AccountIds.storeKey(circleId)
        val wanted = rest?.let { r -> rules?.rest(r) ?: r }
        return all.keys.filter { key ->
            val (part, keyRest) = split(key, owner) ?: return@filter false
            circleOf(part) == one && (wanted == null || (rules?.rest(keyRest) ?: keyRest) == wanted)
        }
    }

    private fun same(a: Any?, b: Any?): Boolean = if (a is Set<*> && b is Set<*>) a.toSet() == b.toSet() else a == b

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }

    // ---- the rules the stores are made of ----

    /** Each key's set, or null when a value is no set of strings (damaged: the circle is then refused). */
    private fun sets(held: List<Held>): List<Set<String>>? = held.map { h ->
        val set = h.value as? Set<*> ?: return null
        if (set.any { it !is String }) return null
        set.filterIsInstance<String>().toSet()
    }

    /** Every key's set, merged, each element by [id]: a removal, a tombstone, a dead invitation stands. */
    fun union(held: List<Held>, id: (String) -> String = { it }): Outcome =
        sets(held)?.let { Outcome.Keep(it.flatten().map(id).toSet()) } ?: Outcome.Refuse

    /** What every spelling of the circle holds, each element by [id]; a spelling without the entry holds none. */
    fun intersection(held: List<Held>, spellings: Set<String>, id: (String) -> String = { it }): Outcome {
        val sets = sets(held) ?: return Outcome.Refuse
        val bySpelling = held.map { it.spelling }.zip(sets).groupBy({ it.first }, { it.second.map(id).toSet() })
        if (bySpelling.keys != spellings) return Outcome.Drop
        return Outcome.Keep(bySpelling.values.map { it.flatten().toSet() }.reduce { a, b -> a intersect b })
    }

    /**
     * The value every spelling of the circle holds, by [norm] (null: damaged); nothing when one
     * holds another or none.
     */
    fun agreed(held: List<Held>, spellings: Set<String>, norm: (Any?) -> Any? = { it }): Outcome {
        val values = held.map { norm(it.value) ?: return Outcome.Refuse }
        if (held.map { it.spelling }.toSet() != spellings || values.distinct().size != 1) return Outcome.Drop
        return Outcome.Keep(values.first()!!)
    }

    /** The value every key holds, by [norm] (null: damaged); the circle refused when two differ. */
    fun agreedOrRefused(held: List<Held>, norm: (Any?) -> Any? = { it }): Outcome {
        val values = held.map { norm(it.value) ?: return Outcome.Refuse }
        return if (values.distinct().size == 1) Outcome.Keep(values.first()!!) else Outcome.Refuse
    }

    /** A flag kept only when every spelling of the circle holds it. */
    fun all(held: List<Held>, spellings: Set<String>): Outcome {
        if (held.any { it.value !is Boolean }) return Outcome.Refuse
        val holding = held.filter { it.value == true }.map { it.spelling }.toSet()
        return if (holding == spellings) Outcome.Keep(true) else Outcome.Drop
    }

    /** The latest of every key's time. */
    fun latest(held: List<Held>): Outcome {
        val times = held.map { it.value as? Long ?: return Outcome.Refuse }
        return Outcome.Keep(times.max())
    }

    /**
     * Sets of "{id}{separator}{value}" merged per id (by [id]), [pick] choosing among one id's
     * values, or refusing the circle (null).
     */
    fun perId(held: List<Held>, separator: String, id: (String) -> String, pick: (List<String>) -> String?): Outcome {
        val sets = sets(held) ?: return Outcome.Refuse
        val byId = sets.flatten().groupBy({ id(it.substringBefore(separator)) }, { it.substringAfter(separator, "") })
        val kept = byId.map { (who, values) ->
            val value = pick(values.distinct()) ?: return Outcome.Refuse
            if (value.isEmpty()) who else "$who$separator$value"
        }
        return Outcome.Keep(kept.toSet())
    }
}
