package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import kotlinx.coroutines.flow.map

/** Builds the notice shown in place of a message removed by a moderator. */
internal fun buildClearMessage(applicationContext: Context, chatMessage: ChatMessage, deletedMessage: ChatMessage?, nameDisplay: String?): ChatMessage {
    val login = deletedMessage?.userLogin ?: chatMessage.userLogin
    val userName = if (deletedMessage?.userName != null && login != null && !login.equals(deletedMessage.userName, true)) {
        when (nameDisplay) {
            "0" -> "${deletedMessage.userName}(${login})"
            "1" -> deletedMessage.userName
            else -> login
        }
    } else {
        deletedMessage?.userName ?: login
    }
    val message = ContextCompat.getString(applicationContext, R.string.chat_clearmsg).format(userName, deletedMessage?.message ?: chatMessage.message)
    val messageIndex = message.indexOf(": ") + 2
    return ChatMessage(
        type = ChatMessage.USER_MESSAGE,
        userId = deletedMessage?.userId,
        userLogin = login,
        userName = deletedMessage?.userName,
        systemMsg = message,
        emotes = deletedMessage?.emotes?.map {
            TwitchEmote(
                id = it.id,
                begin = it.begin + messageIndex,
                end = it.end + messageIndex
            )
        },
        timestamp = chatMessage.timestamp,
        fullMsg = chatMessage.fullMsg
    )
}
