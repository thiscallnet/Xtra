package com.github.andreyasadchy.xtra.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.prefs
import java.io.File
import java.io.FileInputStream
import kotlin.time.Instant

internal class ChatLogFileParser(private val applicationContext: Context) {

    class Result(
        val liveMessages: List<ChatMessage>,
        val messages: List<VideoChatMessage>,
        val startTimeMs: Long,
        val twitchEmotes: List<TwitchEmote>,
        val twitchBadges: List<TwitchBadge>,
        val cheerEmotes: List<CheerEmote>,
        val emotes: List<Emote>,
    )

    /** Reads a saved chat log from a file or content URI. Throws on unreadable or malformed input. */
    fun parse(url: String): Result {
        val nameDisplay = applicationContext.prefs().getString(C.UI_NAME_DISPLAY, "0")
        val liveMessages = mutableListOf<ChatMessage>()
        val messages = mutableListOf<VideoChatMessage>()
        var startTimeMs = 0L
        val twitchEmotes = mutableListOf<TwitchEmote>()
        val twitchBadges = mutableListOf<TwitchBadge>()
        val cheerEmotesList = mutableListOf<CheerEmote>()
        val emotes = mutableListOf<Emote>()
        if (url.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
            applicationContext.contentResolver.openInputStream(url.toUri())?.bufferedReader()
        } else {
            FileInputStream(File(url)).bufferedReader()
        }?.use { fileReader ->
            JsonReader(fileReader).use { reader ->
                reader.isLenient = true
                var position = 0L
                var token: JsonToken
                do {
                    token = reader.peek()
                    when (token) {
                        JsonToken.END_DOCUMENT -> {}
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject().also { position += 1 }
                            while (reader.hasNext()) {
                                when (reader.peek()) {
                                    JsonToken.NAME -> {
                                        when (reader.nextName().also { position += it.length + 3 }) {
                                            "liveStartTime" -> {
                                                val time = reader.nextString().also { position += it.length + 2 }
                                                Instant.parseOrNull(time)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { startTimeMs = it }
                                            }
                                            "liveComments" -> {
                                                reader.beginArray().also { position += 1 }
                                                while (reader.hasNext()) {
                                                    val message = reader.nextString().also { position += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
                                                    val ircMessage = ChatUtils.parseIRCMessage(message)
                                                    when (ircMessage.command) {
                                                        "PRIVMSG", "USERNOTICE" -> {
                                                            val chatMessage = ChatUtils.parseChatMessage(ircMessage)
                                                            if (chatMessage.reply?.message != null) {
                                                                liveMessages.add(ChatMessage(
                                                                    type = ChatMessage.REPLY_MESSAGE,
                                                                    reply = chatMessage.reply,
                                                                    replyParent = chatMessage,
                                                                ))
                                                            }
                                                            liveMessages.add(chatMessage)
                                                        }
                                                        "CLEARMSG" -> {
                                                            val chatMessage = ChatUtils.parseClearMessage(ircMessage)
                                                            val deletedMessage = chatMessage.targetMsgId?.let { targetId ->
                                                                liveMessages.find { it.id == targetId }
                                                            }
                                                            liveMessages.add(buildClearMessage(applicationContext, chatMessage, deletedMessage, nameDisplay))
                                                        }
                                                        "CLEARCHAT" -> liveMessages.add(ChatUtils.parseClearChat(applicationContext, ircMessage))
                                                        "NOTICE" -> liveMessages.add(ChatUtils.parseNotice(ircMessage))
                                                    }
                                                    if (reader.peek() != JsonToken.END_ARRAY) {
                                                        position += 1
                                                    }
                                                }
                                                reader.endArray().also { position += 1 }
                                            }
                                            "comments" -> {
                                                reader.beginArray().also { position += 1 }
                                                while (reader.hasNext()) {
                                                    reader.beginObject().also { position += 1 }
                                                    val message = StringBuilder()
                                                    var id: String? = null
                                                    var offsetSeconds: Int? = null
                                                    var createdAt: String? = null
                                                    var userId: String? = null
                                                    var userLogin: String? = null
                                                    var userName: String? = null
                                                    var color: String? = null
                                                    val emotesList = mutableListOf<TwitchEmote>()
                                                    val badgesList = mutableListOf<Badge>()
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName().also { position += it.length + 3 }) {
                                                            "id" -> id = reader.nextString().also { position += it.length + 2 }
                                                            "commenter" -> {
                                                                reader.beginObject().also { position += 1 }
                                                                while (reader.hasNext()) {
                                                                    when (reader.nextName().also { position += it.length + 3 }) {
                                                                        "id" -> userId = reader.nextString().also { position += it.length + 2 }
                                                                        "login" -> userLogin = reader.nextString().also { position += it.length + 2 }
                                                                        "displayName" -> userName = reader.nextString().also { position += it.length + 2 }
                                                                        else -> position += skipJsonValue(reader)
                                                                    }
                                                                    if (reader.peek() != JsonToken.END_OBJECT) {
                                                                        position += 1
                                                                    }
                                                                }
                                                                reader.endObject().also { position += 1 }
                                                            }
                                                            "contentOffsetSeconds" -> offsetSeconds = reader.nextInt().also { position += it.toString().length }
                                                            "createdAt" -> createdAt = reader.nextString().also { position += it.length + 2 }
                                                            "message" -> {
                                                                reader.beginObject().also { position += 1 }
                                                                while (reader.hasNext()) {
                                                                    when (reader.nextName().also { position += it.length + 3 }) {
                                                                        "fragments" -> {
                                                                            reader.beginArray().also { position += 1 }
                                                                            while (reader.hasNext()) {
                                                                                reader.beginObject().also { position += 1 }
                                                                                var emoteId: String? = null
                                                                                var fragmentText: String? = null
                                                                                while (reader.hasNext()) {
                                                                                    when (reader.nextName().also { position += it.length + 3 }) {
                                                                                        "emote" -> {
                                                                                            when (reader.peek()) {
                                                                                                JsonToken.BEGIN_OBJECT -> {
                                                                                                    reader.beginObject().also { position += 1 }
                                                                                                    while (reader.hasNext()) {
                                                                                                        when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                            "emoteID" -> emoteId = reader.nextString().also { position += it.length + 2 }
                                                                                                            else -> position += skipJsonValue(reader)
                                                                                                        }
                                                                                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                            position += 1
                                                                                                        }
                                                                                                    }
                                                                                                    reader.endObject().also { position += 1 }
                                                                                                }
                                                                                                else -> position += skipJsonValue(reader)
                                                                                            }
                                                                                        }
                                                                                        "text" -> fragmentText = reader.nextString().also { position += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
                                                                                        else -> position += skipJsonValue(reader)
                                                                                    }
                                                                                    if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                        position += 1
                                                                                    }
                                                                                }
                                                                                if (fragmentText != null && !emoteId.isNullOrBlank()) {
                                                                                    emotesList.add(TwitchEmote(
                                                                                        id = emoteId,
                                                                                        begin = message.codePointCount(0, message.length),
                                                                                        end = message.codePointCount(0, message.length) + fragmentText.lastIndex
                                                                                    ))
                                                                                }
                                                                                message.append(fragmentText)
                                                                                reader.endObject().also { position += 1 }
                                                                                if (reader.peek() != JsonToken.END_ARRAY) {
                                                                                    position += 1
                                                                                }
                                                                            }
                                                                            reader.endArray().also { position += 1 }
                                                                        }
                                                                        "userBadges" -> {
                                                                            reader.beginArray().also { position += 1 }
                                                                            while (reader.hasNext()) {
                                                                                reader.beginObject().also { position += 1 }
                                                                                var set: String? = null
                                                                                var version: String? = null
                                                                                while (reader.hasNext()) {
                                                                                    when (reader.nextName().also { position += it.length + 3 }) {
                                                                                        "setID" -> set = reader.nextString().also { position += it.length + 2 }
                                                                                        "version" -> version = reader.nextString().also { position += it.length + 2 }
                                                                                        else -> position += skipJsonValue(reader)
                                                                                    }
                                                                                    if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                        position += 1
                                                                                    }
                                                                                }
                                                                                if (!set.isNullOrBlank() && !version.isNullOrBlank()) {
                                                                                    badgesList.add(Badge(set, version))
                                                                                }
                                                                                reader.endObject().also { position += 1 }
                                                                                if (reader.peek() != JsonToken.END_ARRAY) {
                                                                                    position += 1
                                                                                }
                                                                            }
                                                                            reader.endArray().also { position += 1 }
                                                                        }
                                                                        "userColor" -> {
                                                                            when (reader.peek()) {
                                                                                JsonToken.STRING -> color = reader.nextString().also { position += it.length + 2 }
                                                                                else -> position += skipJsonValue(reader)
                                                                            }
                                                                        }
                                                                        else -> position += skipJsonValue(reader)
                                                                    }
                                                                    if (reader.peek() != JsonToken.END_OBJECT) {
                                                                        position += 1
                                                                    }
                                                                }
                                                                reader.endObject().also { position += 1 }
                                                            }
                                                            else -> position += skipJsonValue(reader)
                                                        }
                                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                                            position += 1
                                                        }
                                                    }
                                                    messages.add(VideoChatMessage(
                                                        id = id,
                                                        offsetSeconds = offsetSeconds,
                                                        createdAt = createdAt,
                                                        userId = userId,
                                                        userLogin = userLogin,
                                                        userName = userName,
                                                        message = message.toString(),
                                                        color = color,
                                                        emotes = emotesList,
                                                        badges = badgesList,
                                                        fullMsg = null
                                                    ))
                                                    reader.endObject().also { position += 1 }
                                                    if (reader.peek() != JsonToken.END_ARRAY) {
                                                        position += 1
                                                    }
                                                }
                                                reader.endArray().also { position += 1 }
                                            }
                                            "twitchEmotes" -> {
                                                reader.beginArray().also { position += 1 }
                                                while (reader.hasNext()) {
                                                    reader.beginObject().also { position += 1 }
                                                    var id: String? = null
                                                    var data: Pair<Long, Int>? = null
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName().also { position += it.length + 3 }) {
                                                            "data" -> {
                                                                position += 1
                                                                val length = reader.nextString().length
                                                                data = Pair(position, length)
                                                                position += length + 1
                                                            }
                                                            "id" -> id = reader.nextString().also { position += it.length + 2 }
                                                            else -> position += skipJsonValue(reader)
                                                        }
                                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                                            position += 1
                                                        }
                                                    }
                                                    if (!id.isNullOrBlank() && data != null) {
                                                        twitchEmotes.add(TwitchEmote(
                                                            id = id,
                                                            localData = data
                                                        ))
                                                    }
                                                    reader.endObject().also { position += 1 }
                                                    if (reader.peek() != JsonToken.END_ARRAY) {
                                                        position += 1
                                                    }
                                                }
                                                reader.endArray().also { position += 1 }
                                            }
                                            "twitchBadges" -> {
                                                reader.beginArray().also { position += 1 }
                                                while (reader.hasNext()) {
                                                    reader.beginObject().also { position += 1 }
                                                    var setId: String? = null
                                                    var version: String? = null
                                                    var data: Pair<Long, Int>? = null
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName().also { position += it.length + 3 }) {
                                                            "data" -> {
                                                                position += 1
                                                                val length = reader.nextString().length
                                                                data = Pair(position, length)
                                                                position += length + 1
                                                            }
                                                            "setId" -> setId = reader.nextString().also { position += it.length + 2 }
                                                            "version" -> version = reader.nextString().also { position += it.length + 2 }
                                                            else -> position += skipJsonValue(reader)
                                                        }
                                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                                            position += 1
                                                        }
                                                    }
                                                    if (!setId.isNullOrBlank() && !version.isNullOrBlank() && data != null) {
                                                        twitchBadges.add(TwitchBadge(
                                                            setId = setId,
                                                            version = version,
                                                            localData = data
                                                        ))
                                                    }
                                                    reader.endObject().also { position += 1 }
                                                    if (reader.peek() != JsonToken.END_ARRAY) {
                                                        position += 1
                                                    }
                                                }
                                                reader.endArray().also { position += 1 }
                                            }
                                            "cheerEmotes" -> {
                                                reader.beginArray().also { position += 1 }
                                                while (reader.hasNext()) {
                                                    reader.beginObject().also { position += 1 }
                                                    var name: String? = null
                                                    var data: Pair<Long, Int>? = null
                                                    var minBits: Int? = null
                                                    var color: String? = null
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName().also { position += it.length + 3 }) {
                                                            "data" -> {
                                                                position += 1
                                                                val length = reader.nextString().length
                                                                data = Pair(position, length)
                                                                position += length + 1
                                                            }
                                                            "name" -> name = reader.nextString().also { position += it.length + 2 }
                                                            "minBits" -> minBits = reader.nextInt().also { position += it.toString().length }
                                                            "color" -> {
                                                                when (reader.peek()) {
                                                                    JsonToken.STRING -> color = reader.nextString().also { position += it.length + 2 }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                            }
                                                            else -> position += skipJsonValue(reader)
                                                        }
                                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                                            position += 1
                                                        }
                                                    }
                                                    if (!name.isNullOrBlank() && minBits != null && data != null) {
                                                        cheerEmotesList.add(CheerEmote(
                                                            name = name,
                                                            localData = data,
                                                            minBits = minBits,
                                                            color = color
                                                        ))
                                                    }
                                                    reader.endObject().also { position += 1 }
                                                    if (reader.peek() != JsonToken.END_ARRAY) {
                                                        position += 1
                                                    }
                                                }
                                                reader.endArray().also { position += 1 }
                                            }
                                            "emotes" -> {
                                                reader.beginArray().also { position += 1 }
                                                while (reader.hasNext()) {
                                                    reader.beginObject().also { position += 1 }
                                                    var data: Pair<Long, Int>? = null
                                                    var name: String? = null
                                                    var isOverlayEmote = false
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName().also { position += it.length + 3 }) {
                                                            "data" -> {
                                                                position += 1
                                                                val length = reader.nextString().length
                                                                data = Pair(position, length)
                                                                position += length + 1
                                                            }
                                                            "name" -> name = reader.nextString().also { position += it.length + 2 }
                                                            "isZeroWidth" -> isOverlayEmote = reader.nextBoolean().also { position += it.toString().length }
                                                            else -> position += skipJsonValue(reader)
                                                        }
                                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                                            position += 1
                                                        }
                                                    }
                                                    if (!name.isNullOrBlank() && data != null) {
                                                        emotes.add(Emote(
                                                            name = name,
                                                            localData = data,
                                                            isOverlayEmote = isOverlayEmote
                                                        ))
                                                    }
                                                    reader.endObject().also { position += 1 }
                                                    if (reader.peek() != JsonToken.END_ARRAY) {
                                                        position += 1
                                                    }
                                                }
                                                reader.endArray().also { position += 1 }
                                            }
                                            "startTime" -> { startTimeMs = reader.nextInt().also { position += it.toString().length }.times(1000L) }
                                            else -> position += skipJsonValue(reader)
                                        }
                                    }
                                    else -> position += skipJsonValue(reader)
                                }
                                if (reader.peek() != JsonToken.END_OBJECT) {
                                    position += 1
                                }
                            }
                            reader.endObject().also { position += 1 }
                        }
                        else -> position += skipJsonValue(reader)
                    }
                } while (token != JsonToken.END_DOCUMENT)
            }
        }
        return Result(liveMessages, messages, startTimeMs, twitchEmotes, twitchBadges, cheerEmotesList, emotes)
    }

    private fun skipJsonValue(reader: JsonReader): Int {
        var length = 0
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray().also { length += 1 }
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.NAME -> length += reader.nextName().length + 3
                        else -> {
                            length += skipJsonValue(reader)
                            if (reader.peek() != JsonToken.END_ARRAY) {
                                length += 1
                            }
                        }
                    }
                }
                reader.endArray().also { length += 1 }
            }
            JsonToken.END_ARRAY -> length += 1
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject().also { length += 1 }
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.NAME -> length += reader.nextName().length + 3
                        else -> {
                            length += skipJsonValue(reader)
                            if (reader.peek() != JsonToken.END_OBJECT) {
                                length += 1
                            }
                        }
                    }
                }
                reader.endObject().also { length += 1 }
            }
            JsonToken.END_OBJECT -> length += 1
            JsonToken.STRING -> reader.nextString().let { length += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
            JsonToken.NUMBER -> length += reader.nextString().length
            JsonToken.BOOLEAN -> length += reader.nextBoolean().toString().length
            else -> reader.skipValue()
        }
        return length
    }
}
