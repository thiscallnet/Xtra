package com.github.andreyasadchy.xtra.util.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GqlPredictionParserTest {
    @Test
    fun parsesFourOutcomePredictionWithBlueBadgesInOrder() {
        val snapshot = GqlPredictionParser.parse(fourOutcomeFixture(), observedAt = 1_000L)
        val prediction = snapshot?.prediction
        val outcomes = prediction?.outcomes

        assertNotNull(snapshot)
        assertNotNull(prediction)
        assertEquals(4, outcomes?.size)
        assertEquals(listOf("o1", "o2", "o3", "o4"), outcomes?.map { it.id })
        assertEquals(listOf("One", "Two", "Three", "Four"), outcomes?.map { it.title })
        assertEquals(listOf("BLUE", "BLUE", "BLUE", "BLUE"), outcomes?.map { it.color })
        assertEquals(listOf(100, 200, 300, 400), outcomes?.map { it.totalPoints })
        assertEquals(listOf(1, 2, 3, 4), outcomes?.map { it.totalUsers })
        assertEquals("https://example.invalid/3", outcomes?.get(2)?.badgeUrl)
        assertTrue(PredictionBetPolicy.isPredictionWagerable(outcomes.orEmpty().map { it.id }))
        assertTrue(snapshot?.authoritative == true)
        assertTrue(snapshot?.hasActiveOrLockedPrediction == true)
        assertTrue(snapshot?.hasUsableOutcomeSet() == true)
    }

    @Test
    fun parsesTenOutcomesAndKeepsTheTenthIdentity() {
        val outcomes = JSONArray().apply {
            (1..10).forEach { index ->
                put(
                    JSONObject()
                        .put("id", "o$index")
                        .put("title", "Outcome $index")
                        .put("color", "BLUE")
                        .put("badge", JSONObject().put("version", "blue-$index")),
                )
            }
        }
        val body = predictionBody(outcomes)

        val snapshot = GqlPredictionParser.parse(body.toString(), observedAt = 1_000L)
        val parsed = snapshot?.prediction?.outcomes

        assertEquals(10, parsed?.size)
        assertEquals((1..10).map { "o$it" }, parsed?.map { it.id })
        assertEquals("o10", parsed?.get(9)?.id)
        assertEquals("blue-10", parsed?.get(9)?.badgeVersion)
        assertTrue(snapshot?.hasUsableOutcomeSet() == true)
    }

    @Test
    fun acceptsRawChannelRootAndConnectionShapedOutcomes() {
        val outcomeNodes = JSONArray().apply {
            put(JSONObject().put("id", "o1").put("title", "One"))
            put(JSONObject().put("id", "o2").put("title", "Two"))
            put(JSONObject().put("id", "o3").put("title", "Three"))
        }
        val prediction = JSONObject()
            .put("id", "p3")
            .put("title", "Choose")
            .put("status", "ACTIVE")
            .put("outcomes", JSONObject().put("nodes", outcomeNodes))
        val channel = JSONObject()
            .put("activePredictionEvents", JSONArray().put(prediction))
            .put("lockedPredictionEvents", JSONArray())
        val body = JSONObject().put("data", JSONObject().put("channel", channel))

        val snapshot = GqlPredictionParser.parse(body.toString(), observedAt = 1_000L)

        assertEquals(listOf("o1", "o2", "o3"), snapshot?.prediction?.outcomes?.map { it.id })
        assertTrue(snapshot?.authoritative == true)
    }

    @Test
    fun acceptsEdgesAndUserChannelRoot() {
        val prediction = JSONObject()
            .put("id", "p3")
            .put("title", "Choose")
            .put("status", "ACTIVE")
            .put(
                "outcomes",
                JSONObject().put(
                    "edges",
                    JSONArray()
                        .put(JSONObject().put("node", JSONObject().put("id", "o1").put("title", "One")))
                        .put(JSONObject().put("node", JSONObject().put("id", "o2").put("title", "Two")))
                        .put(JSONObject().put("node", JSONObject().put("id", "o3").put("title", "Three"))),
                ),
            )
        val channel = JSONObject()
            .put("activePredictionEvents", JSONObject().put("edges", JSONArray().put(JSONObject().put("node", prediction))))
            .put("lockedPredictionEvents", JSONObject().put("nodes", JSONArray()))
        val body = JSONObject().put("data", JSONObject().put("user", JSONObject().put("channel", channel)))

        val snapshot = GqlPredictionParser.parse(body.toString(), observedAt = 1_000L)

        assertEquals(listOf("o1", "o2", "o3"), snapshot?.prediction?.outcomes?.map { it.id })
        assertTrue(snapshot?.authoritative == true)
    }

    @Test
    fun usableAnonymousSnapshotWinsOverIncompleteAuthenticatedSnapshot() {
        val incomplete = GqlPredictionParser.parse(
            predictionBody(JSONObject().put("unexpected", JSONArray())).toString(),
            observedAt = 1_000L,
        )
        val complete = GqlPredictionParser.parse(fourOutcomeFixture(), observedAt = 1_000L)

        assertFalse(incomplete?.hasUsableOutcomeSet() == true)
        assertTrue(complete?.hasUsableOutcomeSet() == true)
        assertSame(complete, chooseGqlPredictionSnapshot(incomplete, complete))
    }

    @Test
    fun anonymousFallbackDecisionKeepsAllFourRecoveryCases() {
        val malformedActive = GqlPredictionParser.parse(
            predictionBody(JSONObject().put("unexpected", JSONArray())).toString(),
            observedAt = 1_000L,
        )
        val validActive = GqlPredictionParser.parse(fourOutcomeFixture(), observedAt = 1_000L)
        val authoritativeEmpty = GqlPredictionSnapshot(
            prediction = null,
            authoritative = true,
            hasActiveOrLockedPrediction = false,
        )

        assertTrue(shouldLoadAnonymousPredictionSnapshot(null))
        assertTrue(shouldLoadAnonymousPredictionSnapshot(authoritativeEmpty))
        assertTrue(shouldLoadAnonymousPredictionSnapshot(malformedActive))
        assertFalse(shouldLoadAnonymousPredictionSnapshot(validActive))
    }

    @Test
    fun validAnonymousPredictionReplacesAuthoritativeEmptyAuthenticatedSnapshot() {
        val authenticated = GqlPredictionSnapshot(
            prediction = null,
            authoritative = true,
            hasActiveOrLockedPrediction = false,
        )
        val anonymous = GqlPredictionParser.parse(fourOutcomeFixture(), observedAt = 1_000L)

        assertTrue(shouldLoadAnonymousPredictionSnapshot(authenticated))
        assertSame(anonymous, chooseGqlPredictionSnapshot(authenticated, anonymous))
    }

    @Test
    fun distinguishesUnknownOutcomeCollectionFromValidEmptyCollection() {
        val unknown = GqlPredictionParser.parse(
            predictionBody(JSONObject().put("unexpected", JSONArray())).toString(),
            observedAt = 1_000L,
        )
        val empty = GqlPredictionParser.parse(
            predictionBody(JSONArray()).toString(),
            observedAt = 1_000L,
        )

        assertNull(unknown?.prediction?.outcomes)
        assertFalse(unknown?.hasUsableOutcomeSet() == true)
        assertNotNull(empty?.prediction?.outcomes)
        assertTrue(empty?.prediction?.outcomes?.isEmpty() == true)
        assertFalse(empty?.hasUsableOutcomeSet() == true)
    }

    @Test
    fun unknownEventCollectionShapeIsNotAuthoritative() {
        val channel = JSONObject()
            .put("activePredictionEvents", JSONObject().put("unexpected", JSONArray()))
            .put("lockedPredictionEvents", JSONArray())
        val body = JSONObject().put(
            "data",
            JSONObject().put("community", JSONObject().put("channel", channel)),
        )

        val snapshot = GqlPredictionParser.parse(body.toString(), observedAt = 1_000L)

        assertFalse(snapshot?.authoritative == true)
        assertFalse(snapshot?.hasActiveOrLockedPrediction == true)
    }

    private fun fourOutcomeFixture(): String =
        """
        {
          "data": {
            "community": {
              "channel": {
                "activePredictionEvents": [
                  {
                    "id": "p4",
                    "title": "Which one?",
                    "status": "ACTIVE",
                    "createdAt": "2026-09-01T08:00:00Z",
                    "predictionWindowSeconds": 300,
                    "outcomes": [
                      {"id":"o1","title":"One","color":"BLUE","totalPoints":100,"totalUsers":1,"badge":{"image4x":"https://example.invalid/1"}},
                      {"id":"o2","title":"Two","color":"BLUE","totalPoints":200,"totalUsers":2,"badge":{"image4x":"https://example.invalid/2"}},
                      {"id":"o3","title":"Three","color":"BLUE","totalPoints":300,"totalUsers":3,"badge":{"image4x":"https://example.invalid/3"}},
                      {"id":"o4","title":"Four","color":"BLUE","totalPoints":400,"totalUsers":4,"badge":{"image4x":"https://example.invalid/4"}}
                    ]
                  }
                ],
                "lockedPredictionEvents": []
              }
            }
          }
        }
        """.trimIndent()

    private fun predictionBody(outcomes: Any): JSONObject {
        val prediction = JSONObject()
            .put("id", "p-test")
            .put("title", "Choose")
            .put("status", "ACTIVE")
            .put("outcomes", outcomes)
        return JSONObject().put(
            "data",
            JSONObject().put(
                "community",
                JSONObject().put(
                    "channel",
                    JSONObject()
                        .put("activePredictionEvents", JSONArray().put(prediction))
                        .put("lockedPredictionEvents", JSONArray()),
                ),
            ),
        )
    }
}
