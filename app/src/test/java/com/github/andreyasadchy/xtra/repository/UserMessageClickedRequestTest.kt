package com.github.andreyasadchy.xtra.repository

import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.json.writeObject
import com.apollographql.apollo.api.parseResponse
import com.github.andreyasadchy.xtra.graphql.UserMessageClickedBasicQuery
import com.github.andreyasadchy.xtra.graphql.UserMessageClickedQuery
import com.github.andreyasadchy.xtra.ui.chat.messageClickedFollowedAt
import okio.buffer
import okio.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageClickedRequestTest {
    @Test
    fun `blank optional selectors are omitted from the request`() {
        val query = UserMessageClickedQuery(
            id = optionalQueryString("  "),
            login = optionalQueryString(" chatter "),
            targetId = optionalQueryString("\t"),
            userLogin = "viewer",
            targetLogin = "channel",
            useFollowLogin = true,
        )

        val variables = query.serializeVariables()

        assertFalse(variables.contains("\"id\""))
        assertTrue(variables.contains("\"login\":\"chatter\""))
        assertFalse(variables.contains("\"targetId\""))
        assertTrue(variables.contains("\"userLogin\":\"viewer\""))
        assertTrue(variables.contains("\"targetLogin\":\"channel\""))
        assertTrue(variables.contains("\"useFollowLogin\":true"))
        assertTrue(query.document().contains("followByLogin: follow(targetLogin: \$targetLogin)"))
        assertTrue(query.document().contains("followById: follow(targetID: \$targetId)"))
    }

    @Test
    fun `basic query omits missing target identity instead of sending null`() {
        val query = UserMessageClickedBasicQuery(
            id = optionalQueryString(null),
            login = optionalQueryString(" chatter "),
            targetId = optionalQueryString(" channel-id "),
            targetLogin = optionalQueryString(" "),
            useFollowLogin = false,
        )

        val variables = query.serializeVariables()

        assertFalse(variables.contains("\"id\""))
        assertTrue(variables.contains("\"login\":\"chatter\""))
        assertTrue(variables.contains("\"targetId\":\"channel-id\""))
        assertFalse(variables.contains("\"targetLogin\""))
        assertTrue(variables.contains("\"useFollowLogin\":false"))
    }

    @Test
    fun `follow date is retained when the response contains the channel follow relation`() {
        val query = UserMessageClickedBasicQuery(
            login = optionalQueryString("chatter"),
            targetLogin = optionalQueryString("channel"),
            useFollowLogin = true,
        )
        val response = """
            {
              "data": {
                "user": {
                  "__typename": "User",
                  "followByLogin": {
                    "followedAt": "2025-02-03T04:05:06Z"
                  },
                  "id": "chatter-id",
                  "login": "chatter"
                }
              }
            }
        """.trimIndent().byteInputStream().source().buffer().jsonReader().use {
            query.parseResponse(it)
        }

        assertEquals(
            "2025-02-03T04:05:06Z",
            messageClickedFollowedAt(response.data!!.user!!.userMessageClickedUser),
        )
    }

    @Test
    fun `basic query can parse the id follow fallback`() {
        val query = UserMessageClickedBasicQuery(
            targetId = optionalQueryString("channel-id"),
            useFollowLogin = false,
        )
        val response = """
            {
              "data": {
                "user": {
                  "__typename": "User",
                  "followById": {
                    "followedAt": "2025-02-03T04:05:06Z"
                  },
                  "id": "chatter-id",
                  "login": "chatter"
                }
              }
            }
        """.trimIndent().byteInputStream().source().buffer().jsonReader().use {
            query.parseResponse(it)
        }

        assertEquals(
            "2025-02-03T04:05:06Z",
            messageClickedFollowedAt(response.data!!.user!!.userMessageClickedUser),
        )
    }

    private fun UserMessageClickedQuery.serializeVariables(): String =
        buildJsonString {
            writeObject {
                name("variables")
                writeObject {
                    serializeVariables(this, CustomScalarAdapters.Empty, false)
                }
            }
        }

    private fun UserMessageClickedBasicQuery.serializeVariables(): String =
        buildJsonString {
            writeObject {
                name("variables")
                writeObject {
                    serializeVariables(this, CustomScalarAdapters.Empty, false)
                }
            }
        }
}
