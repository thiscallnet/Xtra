package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.EmoteProvider
import com.github.andreyasadchy.xtra.model.chat.favoriteKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class STVEventApiUtilsTest {

    @Test
    fun emoteSetChangesKeepTheStableEmoteIdAcrossAliases() {
        val body = JSONObject(
            """
            {
              "id": "set-id",
              "updated": [
                {
                  "key": "emotes",
                  "old_value": {
                    "id": "emote-id",
                    "name": "old_alias",
                    "data": {
                      "name": "canonical_name",
                      "animated": false,
                      "host": { "url": "//cdn.example/emote", "files": [] }
                    }
                  },
                  "value": {
                    "id": "emote-id",
                    "name": "new_alias",
                    "data": {
                      "name": "canonical_name",
                      "animated": false,
                      "host": { "url": "//cdn.example/emote", "files": [] }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = STVEventApiUtils.parseEmoteSetUpdate(body, useWebp = true, channelSTVEmoteSetId = "set-id")

        val update = result!!.updated.single()
        assertEquals("emote-id", update.first.id)
        assertEquals("emote-id", update.second.id)
        assertEquals("old_alias", update.first.name)
        assertEquals("new_alias", update.second.name)
        assertEquals(update.first.favoriteKey(), update.second.favoriteKey())
        assertEquals(EmoteProvider.SEVENTV, update.second.favoriteKey()?.provider)
    }
}
