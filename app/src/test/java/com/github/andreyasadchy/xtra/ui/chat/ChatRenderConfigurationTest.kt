package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderConfigurationTest {

    private val catalogA = ChatAdapterUtils.ChatCatalogIndexes.create(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(),
    )
    private val catalogB = ChatAdapterUtils.ChatCatalogIndexes.create(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(),
    )

    @Test
    fun pendingCatalogIsPreservedWhenTranslationChanges() {
        val active = ChatRenderConfiguration(0, catalogA, false)
        val pendingCatalog = composeChatRenderConfiguration(active, null, 1, indexes = catalogB)

        val pendingBoth = composeChatRenderConfiguration(active, pendingCatalog, 2, translateAllMessages = true)

        assertSame(catalogB, pendingBoth.indexes)
        assertTrue(pendingBoth.translateAllMessages)
    }

    @Test
    fun pendingBroadCatalogChangeSurvivesTargetedUserInvalidation() {
        val active = ChatRenderConfiguration(0, catalogA, false)
        val pendingCatalog = composeChatRenderConfiguration(active, null, 1, indexes = catalogB)

        val afterUserInvalidation = composeChatRenderConfiguration(active, pendingCatalog, 2)

        assertSame(catalogB, afterUserInvalidation.indexes)
        assertFalse(afterUserInvalidation.translateAllMessages)
    }

    @Test
    fun pendingTranslationIsPreservedWhenCatalogChanges() {
        val active = ChatRenderConfiguration(0, catalogA, false)
        val pendingTranslation = composeChatRenderConfiguration(active, null, 1, translateAllMessages = true)

        val pendingBoth = composeChatRenderConfiguration(active, pendingTranslation, 2, indexes = catalogB)

        assertSame(catalogB, pendingBoth.indexes)
        assertTrue(pendingBoth.translateAllMessages)
    }

    @Test
    fun revertingPendingTranslationUsesTheDesiredStateAsTheBase() {
        val active = ChatRenderConfiguration(0, catalogA, false)
        val pendingTranslation = composeChatRenderConfiguration(active, null, 1, translateAllMessages = true)

        val reverted = composeChatRenderConfiguration(active, pendingTranslation, 2, translateAllMessages = false)

        assertSame(catalogA, reverted.indexes)
        assertFalse(reverted.translateAllMessages)
    }

    @Test
    fun genericChatBadgeLookupKeepsPredictionBadgeVersions() {
        val blueThree = TwitchBadge("predictions", "blue-3", url4x = "https://example.invalid/3")
        val blueTen = TwitchBadge("predictions", "blue-10", url4x = "https://example.invalid/10")
        val indexes = ChatAdapterUtils.ChatCatalogIndexes.create(
            emptyList(), emptyList(), listOf(blueThree, blueTen), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(),
        )

        assertSame(blueThree, indexes.globalBadgesByKey["predictions:blue-3"])
        assertSame(blueTen, indexes.globalBadgesByKey["predictions:blue-10"])
    }
}
