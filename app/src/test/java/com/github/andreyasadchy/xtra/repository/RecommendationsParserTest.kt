package com.github.andreyasadchy.xtra.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecommendationsParserTest {

    @Test
    fun parsesOnlyLiveItemsFromRecommendedSection() {
        val response = requireNotNull(javaClass.getResource("/personal_sections_recommended.json"))
            .readText()

        val streams = parsePersonalSections(Json.parseToJsonElement(response).jsonObject)

        assertEquals(1, streams.size)
        assertEquals("42", streams.single().channelId)
        assertEquals("A recommended stream", streams.single().title)
        assertEquals("Example Game", streams.single().gameName)
        assertEquals(listOf("English"), streams.single().tags)
        assertNotNull(streams.single().thumbnailURL)
    }
}
