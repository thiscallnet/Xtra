package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySubsequenceMatcherTest {
    private val matcher = FuzzySubsequenceMatcher()

    @Test
    fun `matches ordered subsequences case insensitively`() {
        listOf("g", "ga", "gar", "gare", "gr", "ge", "gren", "aen", "gn").forEach { query ->
            assertNotNull(query, matcher.match("Garen", query))
        }
        assertNull(matcher.match("Garen", "rg"))
        assertNull(matcher.match("Garen", "xz"))
        assertNotNull(matcher.match("Garen", "GA"))
    }

    @Test
    fun `quality prefers exact prefix contiguous compact and loose matches`() {
        val exact = matcher.match("Garen", "Garen")!!.score
        val prefix = matcher.match("Garen", "Gar")!!.score
        val contiguous = matcher.match("Garen", "are")!!.score
        val compact = matcher.match("Garen", "gren")!!.score
        val loose = matcher.match("Garen", "gn")!!.score

        assertTrue(exact > prefix)
        assertTrue(prefix > contiguous)
        assertTrue(contiguous > compact)
        assertTrue(compact > loose)
    }
}
