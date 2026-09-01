package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import kotlinx.coroutines.flow.Flow

interface ChatTransport {
    /** Collecting owns connection setup and teardown. */
    fun events(session: ChatSessionKey): Flow<ChatEvent>
}
