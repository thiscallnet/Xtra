package com.github.andreyasadchy.xtra.model.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TwitchEmoteGroupTest {
    @Test
    fun onlyExplicitGlobalTypesUseTheGlobalSection() {
        listOf("none", "globals", "global", "smilies").forEach { type ->
            assertEquals(TwitchEmoteGroup.GLOBAL, TwitchEmoteGroup.fromRestrictionType(type))
        }
    }

    @Test
    fun entitlementTypesUseTheUnlockedSection() {
        listOf(
            "bitstier",
            "follower",
            "channelpoints",
            "rewards",
            "prime",
            "turbo",
            "twofactor",
            "owl2019",
            "future_entitlement",
        ).forEach { type ->
            assertEquals(TwitchEmoteGroup.UNLOCKED, TwitchEmoteGroup.fromRestrictionType(type))
        }
    }

    @Test
    fun specialSectionsRemainDistinct() {
        assertEquals(
            TwitchEmoteGroup.HYPE_TRAIN,
            TwitchEmoteGroup.fromRestrictionType("hypetrain"),
        )
        assertEquals(
            TwitchEmoteGroup.SUBSCRIBER,
            TwitchEmoteGroup.fromRestrictionType("subscriptions"),
        )
    }
}
