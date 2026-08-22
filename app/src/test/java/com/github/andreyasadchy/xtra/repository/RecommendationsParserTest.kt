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

    @Test
    fun ignoresDuplicateRecommendedChannelsAndMalformedItems() {
        val response = Json.parseToJsonElement(
            """
            {
              "data": {
                "personalSections": [
                  {
                    "type": "FOLLOWED_SECTION",
                    "items": [
                      {"user": {"id": "followed"}, "content": {"__typename": "Stream", "id": "ignored"}}
                    ]
                  },
                  {
                    "type": "RECOMMENDED_SECTION",
                    "items": [
                      {
                        "user": {"id": "42"},
                        "content": {"__typename": "Stream", "id": "stream-1"}
                      },
                      {
                        "user": {"id": "42"},
                        "content": {"__typename": "Stream", "id": "stream-2"}
                      },
                      {"user": {"id": "missing-content"}},
                      {"content": {"__typename": "Stream", "id": "missing-user"}},
                      "not-an-item"
                    ]
                  },
                  {
                    "type": "RECOMMENDED_SECTION",
                    "items": [
                      {"user": {"id": "offline"}, "content": {"__typename": "User", "id": "offline"}}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val streams = parsePersonalSections(response)

        assertEquals(1, streams.size)
        assertEquals("42", streams.single().channelId)
        assertEquals("stream-1", streams.single().id)
    }
}
