package com.github.andreyasadchy.xtra.model.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewingCategoryPatchTest {

    @Test
    fun idOnlyPatchPreservesExistingCategoryIdentity() {
        val result = mergeViewingCategoryPatch(
            currentId = "game-1",
            currentName = "League of Legends",
            patchId = "game-2",
            patchName = null,
        )

        assertEquals(ViewingCategoryIdentity("game-1", "League of Legends"), result)
    }

    @Test
    fun nameOnlyPatchPreservesExistingCategoryIdentity() {
        val result = mergeViewingCategoryPatch(
            currentId = "game-1",
            currentName = "League of Legends",
            patchId = null,
            patchName = "Just Chatting",
        )

        assertEquals(ViewingCategoryIdentity("game-1", "League of Legends"), result)
    }

    @Test
    fun completePatchReplacesBothCategoryIdentityFields() {
        val result = mergeViewingCategoryPatch(
            currentId = "game-1",
            currentName = "League of Legends",
            patchId = "game-2",
            patchName = "Just Chatting",
        )

        assertEquals(ViewingCategoryIdentity("game-2", "Just Chatting"), result)
    }
}
