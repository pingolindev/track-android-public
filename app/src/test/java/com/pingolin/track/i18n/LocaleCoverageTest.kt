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

package com.pingolin.track.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Track speaks seven languages besides English, as the web app does: de, es, fr, ja, ko, pt (Brazilian, as
 * Android's own values-pt is) and zh in Simplified script (values-b+zh+Hans).
 *
 * Every name in i18n/translated-strings.txt stays translated in each of them, with the English's format
 * arguments, markup and plural forms. An argument the code does not pass crashes String.format in that
 * language alone, so the arguments are checked for every translation, listed or not. A NEW English string is
 * not in the list and fails nothing here (lint reports MissingTranslation as a warning):
 * `tools/i18n-missing.py` lists what is owed, and `tools/i18n-missing.py --snapshot` adds a name once all
 * seven have it. Deleting or renaming an English string takes its translations with it
 * (`tools/i18n-missing.py --drop <name>`), or lint's ExtraTranslation fails the build.
 *
 * Each language keeps Track's register, as on the web: de du, es tú, fr vous, pt Brazilian, zh 你, ja サインイン.
 * The lists are curated forms only the other register uses, so a third person ("sie", "su", "le") never
 * trips them. And no language says more about keys than English may: "recovery key" and "passkey" only.
 */
class LocaleCoverageTest {

    private val folders = mapOf(
        "de" to "values-de", "es" to "values-es", "fr" to "values-fr", "ja" to "values-ja",
        "ko" to "values-ko", "pt" to "values-pt", "zh" to "values-b+zh+Hans",
    )

    /** The CLDR plural categories getQuantityString picks from in each language (lint: MissingQuantity). */
    private val quantities = mapOf(
        "de" to setOf("one", "other"),
        "es" to setOf("one", "many", "other"), "fr" to setOf("one", "many", "other"), "pt" to setOf("one", "many", "other"),
        "ja" to setOf("other"), "ko" to setOf("other"), "zh" to setOf("other"),
    )

    private fun letters(words: String) = Regex("""(?<!\p{L})(?:$words)(?!\p{L})""")

    /** What only the other register says. */
    private val otherRegister = mapOf(
        "es" to Regex(
            """(?<!\p{L})(?:ustedes|usted|Guárdela|Descárguela|Póngase|Inténtelo|Compruébelo|Asegúrese)(?!\p{L})""" +
                """|(?:^|[.!?:]\s+|[¿¡])(?:Toque|Pulse|Seleccione|Elija|Vuelva|Introduzca|Ingrese|Escriba|Abra|Active|Desactive|Permita)(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        ),
        "pt" to Regex(
            """palavras?-passe|telemóve|(?<!\p{L})(?:ficheiros?|ecrã|numa?|Introduza|enviámos|separador|Definições|registo|registou|registar|contacte|secção|para si)(?!\p{L})|encriptad|subscriç|Ser-lhe-á|(?<!\p{L})a funcionar(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        ),
        "fr" to Regex("""(?<!\p{L})(?:tu|toi|ton|ta|tes)(?!\p{L})""", RegexOption.IGNORE_CASE),
        "zh" to Regex("您"),
        "ja" to Regex("ログイン|ログアウト|リカバリーキー"),
        "ko" to Regex("당신"),
    )

    /** German "Sie"/"Ihr" addresses the reader formally except where a sentence starts with it ("Sie" = they). */
    private val germanFormal = letters("Sie|Ihnen|Ihr|Ihre|Ihren|Ihrem|Ihrer|Ihres")

    /** Key words, and the only ones a person may read: their language's "recovery key" and "passkey". */
    private val keyWords = mapOf(
        // "verschlüsselt"/"entschlüsselt" are the plain words for encrypted/decrypted, as on the web
        "de" to (Regex("""(?<!ver)(?<!ent)schlüssel""", RegexOption.IGNORE_CASE) to listOf("wiederherstellungsschlüssel")),
        "es" to (Regex("""(?<!\p{L})(?:claves?|llaves?)(?!\p{L})""", RegexOption.IGNORE_CASE) to
            listOf("claves de recuperación", "clave de recuperación", "llaves de acceso", "llave de acceso")),
        "fr" to (Regex("""(?<!\p{L})(?:clés?|clefs?)(?!\p{L})""", RegexOption.IGNORE_CASE) to
            listOf("clés de récupération", "clé de récupération", "clés d'accès", "clé d'accès")),
        "pt" to (Regex("""(?<!\p{L})chaves?(?!\p{L})""", RegexOption.IGNORE_CASE) to
            listOf("chaves de recuperação", "chave de recuperação", "chaves de acesso", "chave de acesso")),
        "ja" to (Regex("キー|鍵") to listOf("復旧キー", "パスキー")),
        "ko" to (Regex("키|열쇠") to listOf("복구 키", "패스키")),
        "zh" to (Regex("密钥|钥匙|秘钥") to listOf("恢复密钥", "通行密钥")),
    )

    /** The feature is "Notify my circle": an emergency word only where the English names real emergency services. */
    private val emergency = mapOf(
        "de" to Regex("Notfall|Notruf", RegexOption.IGNORE_CASE), "es" to Regex("emergencia", RegexOption.IGNORE_CASE),
        "fr" to Regex("urgence", RegexOption.IGNORE_CASE), "pt" to Regex("emergência", RegexOption.IGNORE_CASE),
        "ja" to Regex("緊急"), "ko" to Regex("긴급|비상"), "zh" to Regex("紧急"),
    )

    private val argument = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

    private class Entry(val items: Map<String, String>, val markup: List<String>, val plural: Boolean)

    /** name → its text (a string's under "", a plural's by quantity), Android's escapes undone. */
    private fun read(folder: String): Map<String, Entry> {
        val file = File("src/main/res/$folder/strings.xml")
        assertTrue("$file is missing", file.exists())
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = linkedMapOf<String, Entry>()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> out[name] = Entry(mapOf("" to unescape(node.textContent)), tags(node), plural = false)
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    val map = (0 until items.length).associate { j ->
                        val item = items.item(j) as org.w3c.dom.Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                    out[name] = Entry(map, tags(node).filter { it != "item" }, plural = true)
                }
            }
        }
        return out
    }

    private fun tags(node: org.w3c.dom.Element): List<String> {
        val all = node.getElementsByTagName("*")
        return (0 until all.length).map { (all.item(it) as org.w3c.dom.Element).tagName }
    }

    private fun unescape(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                append(if (raw[i + 1] == 'n') '\n' else raw[i + 1])
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }.trim()

    /**
     * The arguments a text uses: a numbered one (%1$s) may repeat or move, since String.format reuses it; an
     * unnumbered one (%d) is consumed in order, so its count has to match.
     */
    private fun arguments(text: String): Pair<Set<String>, List<String>> {
        val found = argument.findAll(text).map { it.value }.filter { it != "%%" }.toList()
        return found.filter { '$' in it }.toSet() to found.filter { '$' !in it }.sorted()
    }

    private val english by lazy { read("values") }

    private val snapshot by lazy {
        File("src/test/resources/i18n/translated-strings.txt").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun `the snapshot is read and names strings English still has`() {
        assertTrue("translated-strings.txt names only ${snapshot.size}", snapshot.size > 400)
        val gone = snapshot.filter { it !in english }
        assertTrue("no longer in values/strings.xml (tools/i18n-missing.py --drop): $gone", gone.isEmpty())
    }

    @Test
    fun `every language has every listed string`() {
        val missing = folders.flatMap { (lang, folder) ->
            val strings = read(folder)
            snapshot.filter { name -> strings[name]?.items?.values?.all { it.isNotBlank() } != true }.map { "$lang: $it" }
        }
        assertTrue("not translated: $missing", missing.isEmpty())
    }

    @Test
    fun `no language keeps a string English no longer has`() {
        val extra = folders.flatMap { (lang, folder) -> read(folder).keys.filter { it !in english }.map { "$lang: $it" } }
        assertTrue("delete these with tools/i18n-missing.py --drop: $extra", extra.isEmpty())
    }

    @Test
    fun `every translation has its English arguments and markup`() {
        val wrong = folders.flatMap { (lang, folder) ->
            read(folder).flatMap { (name, entry) ->
                val en = english[name] ?: return@flatMap emptyList()
                val enArgs = arguments(en.items[""] ?: en.items["other"].orEmpty())
                val kind = if (entry.plural != en.plural) listOf("$lang $name: string vs plurals") else emptyList()
                val args = entry.items.filter { (_, text) -> arguments(text) != enArgs }
                    .map { (q, text) -> "$lang $name${if (q.isEmpty()) "" else "[$q]"}: ${arguments(text)} != $enArgs" }
                val markup = if (entry.markup != en.markup) listOf("$lang $name: markup ${entry.markup} != ${en.markup}") else emptyList()
                kind + args + markup
            }
        }
        assertTrue("arguments or markup differ: $wrong", wrong.isEmpty())
    }

    @Test
    fun `every plural has its language's quantities`() {
        val wrong = folders.flatMap { (lang, folder) ->
            read(folder).filter { it.value.plural && it.value.items.keys != quantities.getValue(lang) }
                .map { (name, entry) -> "$lang $name: ${entry.items.keys} != ${quantities.getValue(lang)}" }
        }
        assertTrue("plural quantities: $wrong", wrong.isEmpty())
    }

    @Test
    fun `each language keeps Track's register`() {
        val wrong = folders.flatMap { (lang, folder) ->
            read(folder).flatMap { (name, entry) ->
                entry.items.values.flatMap { text ->
                    val other = otherRegister[lang]?.find(text)?.let { listOf("$lang $name: '${it.value}'") }.orEmpty()
                    val formal = if (lang != "de") emptyList() else germanFormal.findAll(text)
                        .filter { m -> text.substring(0, m.range.first).trimEnd().lastOrNull()?.let { it !in ".!?:\n„\"(" } == true }
                        .map { "$lang $name: '${it.value}' mid-sentence" }.toList()
                    other + formal
                }
            }
        }
        assertTrue("the other register: $wrong", wrong.isEmpty())
    }

    @Test
    fun `no language talks about keys, SOS or emergencies the English does not`() {
        val wrong = folders.flatMap { (lang, folder) ->
            val (word, allowed) = keyWords.getValue(lang)
            read(folder).flatMap { (name, entry) ->
                val en = english[name]?.items?.values?.joinToString(" ").orEmpty()
                entry.items.values.flatMap { text ->
                    val rest = allowed.fold(text.lowercase()) { acc, ok -> acc.replace(ok, " ") }
                    listOfNotNull(
                        word.find(rest)?.let { "$lang $name: key word '${it.value}'" },
                        if ("SOS" in text) "$lang $name: SOS" else null,
                        emergency.getValue(lang).find(text)?.takeIf { !en.contains("emergency", ignoreCase = true) }
                            ?.let { "$lang $name: '${it.value}'" },
                    )
                }
            }
        }
        assertTrue("words the English does not use: $wrong", wrong.isEmpty())
    }

    @Test
    fun `Track and Pingolin keep their names`() {
        val wrong = folders.flatMap { (lang, folder) ->
            read(folder).flatMap { (name, entry) ->
                val en = english[name]?.items?.values?.joinToString(" ").orEmpty()
                listOf("Track", "Pingolin").filter { brand ->
                    // not \b: kana, Hangul and hanzi count as word characters next to "Track" ("Trackは")
                    val own = Regex("""(?<![A-Za-z0-9])$brand(?![A-Za-z0-9])""")
                    own.containsMatchIn(en) && entry.items.values.any { !own.containsMatchIn(it) }
                }.map { "$lang $name: $it" }
            }
        }
        assertTrue("dropped: $wrong", wrong.isEmpty())
    }
}
