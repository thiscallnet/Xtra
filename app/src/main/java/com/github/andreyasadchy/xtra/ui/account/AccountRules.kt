package com.github.andreyasadchy.xtra.ui.account

data class TwitchChatColorOption(
    val name: String,
    val apiValue: String,
    val hex: String,
)

internal val TWITCH_CHAT_COLOR_OPTIONS = listOf(
    TwitchChatColorOption("Blue", "blue", "#0000FF"),
    TwitchChatColorOption("Blue Violet", "blue_violet", "#8A2BE2"),
    TwitchChatColorOption("Cadet Blue", "cadet_blue", "#5F9EA0"),
    TwitchChatColorOption("Chocolate", "chocolate", "#D2691E"),
    TwitchChatColorOption("Coral", "coral", "#FF7F50"),
    TwitchChatColorOption("Dodger Blue", "dodger_blue", "#1E90FF"),
    TwitchChatColorOption("Firebrick", "firebrick", "#B22222"),
    TwitchChatColorOption("Golden Rod", "golden_rod", "#DAA520"),
    TwitchChatColorOption("Green", "green", "#008000"),
    TwitchChatColorOption("Hot Pink", "hot_pink", "#FF69B4"),
    TwitchChatColorOption("Orange Red", "orange_red", "#FF4500"),
    TwitchChatColorOption("Red", "red", "#FF0000"),
    TwitchChatColorOption("Sea Green", "sea_green", "#2E8B57"),
    TwitchChatColorOption("Spring Green", "spring_green", "#00FF7F"),
    TwitchChatColorOption("Yellow Green", "yellow_green", "#9ACD32"),
)

internal val ACCOUNT_TAG_PATTERN = Regex("^[\\p{L}\\p{N}]+$")
private val ACCOUNT_HEX_COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

internal fun isValidCustomChatColor(value: String): Boolean = ACCOUNT_HEX_COLOR_PATTERN.matches(value)

internal fun isCanonicalChatColor(value: String?): Boolean = value != null && ACCOUNT_HEX_COLOR_PATTERN.matches(value)

internal fun isValidStreamTitle(value: String): Boolean = value.isNotEmpty() && value.length <= 140

internal fun isValidAccountTag(value: String): Boolean = value.isNotEmpty() && value.length <= 25 && ACCOUNT_TAG_PATTERN.matches(value)

internal data class ChatSettingsUpdate(
    val emote: Boolean? = null,
    val followers: Boolean? = null,
    val followersDuration: Int? = null,
    val slow: Boolean? = null,
    val slowDuration: Int? = null,
    val subs: Boolean? = null,
    val unique: Boolean? = null,
)

internal fun normalizeChatSettingsUpdate(
    emote: Boolean? = null,
    followers: Boolean? = null,
    followersDuration: Int? = null,
    slow: Boolean? = null,
    slowDuration: Int? = null,
    subs: Boolean? = null,
    unique: Boolean? = null,
): ChatSettingsUpdate = ChatSettingsUpdate(
    emote = emote,
    followers = if (followersDuration != null) true else followers,
    followersDuration = followersDuration,
    slow = if (slowDuration != null) true else slow,
    slowDuration = slowDuration,
    subs = subs,
    unique = unique,
)
