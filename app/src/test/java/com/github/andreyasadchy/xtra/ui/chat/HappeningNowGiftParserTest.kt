package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HappeningNowGiftParserTest {

    @Test
    fun `eventsub community gift uses the top-level gift object`() {
        val gift = HappeningNowGiftParser.fromEventSub(
            event = JSONObject(
                """
                {
                  "notice_type":"community_sub_gift",
                  "community_sub_gift":{"id":"abc","total":5},
                  "chatter_user_id":"123",
                  "chatter_user_login":"merc_unleashed",
                  "chatter_user_name":"MeRc_UnLeAsHeD"
                }
                """.trimIndent(),
            ),
            timestamp = null,
            now = 123L,
        )

        assertNotNull(gift)
        assertEquals("abc", gift?.stableId)
        assertEquals(5, gift?.count)
        assertEquals("MeRc_UnLeAsHeD", gift?.gifterDisplayName)
        assertEquals("123", gift?.gifterUserId)
        assertEquals("merc_unleashed", gift?.gifterLogin)
        assertEquals(123L, gift?.occurredAt)
    }

    @Test
    fun `anonymous eventsub community gift uses anonymous`() {
        val gift = HappeningNowGiftParser.fromEventSub(
            event = JSONObject(
                """
                {
                  "notice_type":"community_sub_gift",
                  "community_sub_gift":{"id":"abc","total":5},
                  "chatter_is_anonymous":true,
                  "chatter_user_id":"123",
                  "chatter_user_login":"ignored_login",
                  "chatter_user_name":"ignored"
                }
                """.trimIndent(),
            ),
            timestamp = null,
        )

        assertTrue(gift?.isAnonymous == true)
        assertNull(gift?.gifterDisplayName)
        assertNull(gift?.gifterUserId)
        assertNull(gift?.gifterLogin)
    }

    @Test
    fun `eventsub shared chat community gift uses its top-level gift object`() {
        val gift = HappeningNowGiftParser.fromEventSub(
            event = JSONObject(
                """
                {
                  "notice_type":"shared_chat_community_sub_gift",
                  "shared_chat_community_sub_gift":{"id":"shared-abc","total":5},
                  "chatter_user_id":"123",
                  "chatter_user_login":"merc_unleashed",
                  "chatter_user_name":"MeRc_UnLeAsHeD"
                }
                """.trimIndent(),
            ),
            timestamp = null,
            now = 123L,
        )

        assertNotNull(gift)
        assertEquals("shared-abc", gift?.stableId)
        assertEquals(5, gift?.count)
        assertEquals("MeRc_UnLeAsHeD", gift?.gifterDisplayName)
        assertEquals("123", gift?.gifterUserId)
        assertEquals("merc_unleashed", gift?.gifterLogin)
        assertEquals(123L, gift?.occurredAt)
    }

    @Test
    fun `eventsub individual gift is ignored`() {
        val gift = HappeningNowGiftParser.fromEventSub(
            JSONObject(
                """
                {
                  "notice_type":"sub_gift",
                  "community_sub_gift":{"id":"abc","total":1}
                }
                """.trimIndent(),
            ),
            timestamp = null,
        )

        assertNull(gift)
    }

    @Test
    fun `irc community gift uses mass gift count`() {
        val message = ChatUtils.parseIRCMessage(
            "@display-name=MeRc_UnLeAsHeD;id=gift-id;login=merc_unleashed;user-id=123;msg-id=submysterygift;msg-param-mass-gift-count=5;tmi-sent-ts=1700000000000 :user!user@tmi.twitch.tv USERNOTICE #channel :gift",
        )

        val gift = HappeningNowGiftParser.fromIrc(message)

        assertNotNull(gift)
        assertEquals(5, gift?.count)
        assertEquals("gift-id", gift?.stableId)
        assertEquals("123", gift?.gifterUserId)
        assertEquals("merc_unleashed", gift?.gifterLogin)
    }

    @Test
    fun `irc individual gift is ignored`() {
        val message = ChatUtils.parseIRCMessage(
            "@display-name=Recipient;id=gift-id;login=recipient;msg-id=subgift :user!user@tmi.twitch.tv USERNOTICE #channel :gift",
        )

        assertNull(HappeningNowGiftParser.fromIrc(message))
    }

    @Test
    fun `irc shared chat community gift uses source id`() {
        val message = ChatUtils.parseIRCMessage(
            "@display-name=MeRc_UnLeAsHeD;id=relay-id;login=merc_unleashed;msg-id=sharedchatnotice;msg-param-mass-gift-count=5;source-id=stable-source-id;source-msg-id=submysterygift :user!user@tmi.twitch.tv USERNOTICE #channel :gift",
        )

        val gift = HappeningNowGiftParser.fromIrc(message)

        assertEquals("stable-source-id", gift?.stableId)
    }

    @Test
    fun `two outcome totals are formatted as a compact comparison`() {
        val outcomes = listOf(
            Prediction.PredictionOutcome("a", "A", 305_200, 1),
            Prediction.PredictionOutcome("b", "B", 4_927_000, 1),
        )

        assertEquals(
            "${TwitchApiHelper.formatCount(305_200, true)} vs ${TwitchApiHelper.formatCount(4_927_000, true)}",
            happeningNowPredictionTotals(outcomes),
        )
    }

    @Test
    fun `result winner matches winning outcome id`() {
        val prediction = Prediction(
            id = "prediction-a",
            createdAt = null,
            outcomes = listOf(
                Prediction.PredictionOutcome("a", "A", 1, 1),
                Prediction.PredictionOutcome("b", "B", 2, 1),
            ),
            predictionWindowSeconds = null,
            status = "RESOLVED",
            title = "Title",
            winningOutcomeId = "b",
        )

        assertEquals(1, happeningNowWinnerIndex(prediction))
    }

    @Test
    fun `activity key prefixes do not collide`() {
        val keys = setOf(
            HappeningNowKeys.gift("same"),
            HappeningNowKeys.prediction("same"),
            HappeningNowKeys.predictionResult("same"),
            HappeningNowKeys.poll("same"),
        )

        assertEquals(4, keys.size)
        assertTrue(keys.containsAll(
            listOf(
                HappeningNowKeys.gift("same"),
                HappeningNowKeys.prediction("same"),
                HappeningNowKeys.predictionResult("same"),
                HappeningNowKeys.poll("same"),
            ),
        ))
    }
}
