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
        assertEquals(listOf(ChangeItem("A note", ChangeKind.OTHER)), notes.items)
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

    @Test
    fun markdownFormattingAndNonSemanticHeadingsAreRemoved() {
        val notes = ReleaseNotes.structured(
            "## Notes\n- **Fixed** [chat rendering](https://example.test/chat)\n- Full Changelog: https://example.test/compare/old...new",
        )

        assertEquals(listOf("Fixed chat rendering"), notes.items.map(ChangeItem::text))
        assertEquals(listOf(ChangeKind.FIXED), notes.items.map(ChangeItem::kind))
    }

    @Test
    fun markdownEmphasisIsRemovedBeforeClassification() {
        val notes = ReleaseNotes.structured(
            "- _Fixed_ one\n- __Fixed__ two\n- *Fixed* three\n- **Fixed** four\n- ***Fixed*** five",
        )

        assertEquals(
            listOf("Fixed one", "Fixed two", "Fixed three", "Fixed four", "Fixed five"),
            notes.items.map(ChangeItem::text),
        )
        assertTrue(notes.items.all { it.kind == ChangeKind.FIXED })
    }

    @Test
    fun intrawordUnderscoresAndMismatchedEmphasisArePreserved() {
        val notes = ReleaseNotes.structured(
            "- Fixed player_buffer_size\n- *foo_",
        )

        assertEquals(
            listOf("Fixed player_buffer_size", "*foo_"),
            notes.items.map(ChangeItem::text),
        )
    }

    @Test
    fun markdownLinksCanContainParentheses() {
        val notes = ReleaseNotes.structured(
            "- Read [the docs](https://example.com/foo_(bar))",
        )

        assertEquals(listOf("Read the docs"), notes.items.map(ChangeItem::text))
    }
}
