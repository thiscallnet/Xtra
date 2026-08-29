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

    @Test
    fun conventionalCommitPrefixesSurviveCleaningForClassification() {
        val notes = ReleaseNotes.structured(
            body = null,
            commits = listOf(
                "fix: player crash",
                "feat: add multiview",
                "perf: reduce startup latency",
                "improve: player controls",
                "refactor: update state handling",
            ),
        )

        assertEquals(
            listOf(
                ChangeKind.FIXED,
                ChangeKind.NEW,
                ChangeKind.IMPROVED,
                ChangeKind.IMPROVED,
                ChangeKind.IMPROVED,
            ),
            notes.items.map(ChangeItem::kind),
        )
    }

    @Test
    fun generatedGithubChangelogNoiseIsNotShown() {
        val notes = ReleaseNotes.structured(
            "## What's Changed\n- **Full Changelog**: https://github.com/example/compare/old...new\n- A useful change",
        )

        assertEquals(listOf("A useful change"), notes.items.map(ChangeItem::text))
    }
}
