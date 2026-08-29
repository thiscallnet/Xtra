package com.github.andreyasadchy.xtra.util.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReleaseNotesTest {
    @Test
    fun headingsAssignKinds() {
        val notes = ReleaseNotes.structured(
            """
            ## Added
            - Multiview improvements
            ## Improved
            - Faster startup
            ## Fixed
            - Player crash
            ## Security
            - Safer session handling
            """.trimIndent(),
        )
        assertEquals(
            listOf(ChangeKind.NEW, ChangeKind.IMPROVED, ChangeKind.FIXED, ChangeKind.SECURITY),
            notes.items.map(ChangeItem::kind),
        )
    }

    @Test
    fun commitFallbackCleansHashesMergesAndDuplicates() {
        val notes = ReleaseNotes.structured(
            "## Changes",
            listOf(
                "b6fde121 Fix chat spacing",
                "Merge branch 'master'",
                "b6fde121 Fix chat spacing",
                "feat: add a compact player",
            ),
        )
        assertEquals(listOf("Fixed chat spacing", "Added a compact player"), notes.items.map(ChangeItem::text))
        assertTrue(notes.items.none { it.text.contains("b6fde121") })
        assertFalse(notes.items.any { it.text.startsWith("Merge") })
        assertEquals(ChangeKind.FIXED, notes.items.first().kind)
        assertEquals(ChangeKind.NEW, notes.items[1].kind)
    }

    @Test
    fun unknownHeadingsRemainAsOther() {
        val notes = ReleaseNotes.structured("## Notes\n- A note")
        assertEquals(ChangeKind.OTHER, notes.items.first().kind)
        assertEquals("Notes", notes.items.first().text)
        assertEquals("A note", notes.items[1].text)
    }
}
