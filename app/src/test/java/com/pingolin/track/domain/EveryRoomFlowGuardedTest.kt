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

package com.pingolin.track.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No Room flow in the domain layer goes unguarded. Every function there that reads a DAO's flow
 * carries orWhenUnopenable (a list: nothing, and a look again) or retryWhenUnopenable (one row:
 * a look again only, never a null that reads as "gone"). The DAOs' flow methods are read from
 * the DAOs themselves, so a new one is covered as it comes.
 */
class EveryRoomFlowGuardedTest {

    private val declaration = Regex("""^\s*(?:(?:override|internal|private|protected|public|open|suspend)\s+)*fun\s""")

    @Test
    fun `every DAO flow the domain layer reads is guarded`() {
        val root = File("src/main/java/com/pingolin/track")
        val flowMethods = root.resolve("data/local/dao").walk().filter { it.extension == "kt" }
            .flatMap { Regex("""fun (\w+)\([^)]*\)\s*:\s*Flow<""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue("the DAOs' flows: $flowMethods", flowMethods.size >= 15)
        val read = Regex("""\b\w*[dD]ao\.(${flowMethods.joinToString("|")})\(""")
        val unguarded = mutableListOf<String>()
        var reads = 0
        for (file in root.resolve("domain").walk().filter { it.extension == "kt" }) {
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || !read.containsMatchIn(line)) return@forEachIndexed
                reads++
                // The function it is in: from its declaration to the next thing at its indent.
                val start = (i downTo 0).first { declaration.containsMatchIn(lines[it]) }
                val indent = lines[start].takeWhile { it == ' ' }.length
                val end = (start + 1 until lines.size).firstOrNull { j ->
                    val next = lines[j]
                    next.isNotBlank() && next.takeWhile { it == ' ' }.length <= indent && !next.trimStart().startsWith("}")
                } ?: lines.size
                val function = lines.subList(start, end).joinToString("\n")
                if (!function.contains(".orWhenUnopenable(") && !function.contains(".retryWhenUnopenable()")) {
                    unguarded += "${file.name}:${i + 1}: ${code.take(90)}"
                }
            }
        }
        assertTrue("the domain layer's reads: $reads", reads >= 30)
        assertEquals(emptyList<String>(), unguarded)
    }
}
