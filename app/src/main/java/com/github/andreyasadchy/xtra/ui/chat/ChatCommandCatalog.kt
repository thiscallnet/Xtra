package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.R

internal enum class ChatCommandAvailability {
    EXECUTABLE,
    MODERATOR,
}

internal data class ChatCommandDescriptor(
    val name: String,
    val usage: String,
    val descriptionResource: Int,
    val availability: ChatCommandAvailability,
)

internal object ChatCommandCatalog {
    val commands = listOf(
        ChatCommandDescriptor(
            name = "/help",
            usage = "/help",
            descriptionResource = R.string.chat_command_help_suggestion,
            availability = ChatCommandAvailability.EXECUTABLE,
        ),
        ChatCommandDescriptor(
            name = "/me",
            usage = "/me <message>",
            descriptionResource = R.string.chat_command_me_suggestion,
            availability = ChatCommandAvailability.EXECUTABLE,
        ),
        ChatCommandDescriptor(
            name = "/ban",
            usage = "/ban <user> [reason]",
            descriptionResource = R.string.chat_command_ban_help,
            availability = ChatCommandAvailability.MODERATOR,
        ),
        ChatCommandDescriptor(
            name = "/unban",
            usage = "/unban <user>",
            descriptionResource = R.string.chat_command_unban_help,
            availability = ChatCommandAvailability.MODERATOR,
        ),
        ChatCommandDescriptor(
            name = "/timeout",
            usage = "/timeout <user> [duration] [reason]",
            descriptionResource = R.string.chat_command_timeout_help,
            availability = ChatCommandAvailability.MODERATOR,
        ),
        ChatCommandDescriptor(
            name = "/untimeout",
            usage = "/untimeout <user>",
            descriptionResource = R.string.chat_command_untimeout_help,
            availability = ChatCommandAvailability.MODERATOR,
        ),
    )

    val executable = commands.filter { it.availability == ChatCommandAvailability.EXECUTABLE }
    val moderator = commands.filter { it.availability == ChatCommandAvailability.MODERATOR }

    fun suggestions(input: CharSequence, viewerIsModerator: Boolean): List<ChatCommandDescriptor> {
        val text = input.toString()
        if (!text.startsWith('/') || text.any(Char::isWhitespace)) return emptyList()
        return (executable + moderator.takeIf { viewerIsModerator }.orEmpty())
            .filter { it.name.startsWith(text, ignoreCase = true) }
    }

    fun isModeratorCommand(input: CharSequence): Boolean {
        val command = input.toString().trim().takeWhile { !it.isWhitespace() }.lowercase()
        return moderator.any { it.name == command }
    }
}
