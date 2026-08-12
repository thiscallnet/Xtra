package com.github.andreyasadchy.xtra.ui.multiview

import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutMode

data class MultiviewSessionState(
    val streams: List<Stream> = emptyList(),
    val activeIdentity: String? = null,
    val focusedIdentity: String? = null,
    val layoutMode: MultiviewLayoutMode = MultiviewLayoutMode.AUTO,
    val layoutBeforeFocus: MultiviewLayoutMode? = null,
    val fillVideo: Boolean = false,
    val chatVisible: Boolean = false,
    val combinedChat: Boolean = false,
    val chatIdentity: String? = null,
    val qualityMode: MultiviewQualityMode = MultiviewQualityMode.SMART,
    val qualityOverrides: Map<String, String> = emptyMap(),
) {
    val identities: List<String>
        get() = streams.mapNotNull(MultiviewSessionReducer::stableIdentity)
}

object MultiviewSessionReducer {
    fun add(state: MultiviewSessionState, stream: Stream, maximum: Int = 4): MultiviewSessionState {
        val identity = stableIdentity(stream) ?: return state
        if (state.streams.size >= maximum || state.identities.any { it.equals(identity, true) }) return state
        val streams = state.streams + stream
        return state.copy(
            streams = streams,
            activeIdentity = state.activeIdentity ?: identity,
            chatIdentity = state.chatIdentity ?: identity,
        )
    }

    fun remove(state: MultiviewSessionState, identity: String): MultiviewSessionState {
        val remaining = state.streams.filterNot { stableIdentity(it).equals(identity, true) }
        if (remaining.isEmpty()) return state.copy(streams = emptyList(), activeIdentity = null, focusedIdentity = null, chatIdentity = null)
        val fallback = remaining.firstOrNull()?.let(::stableIdentity)
        return state.copy(
            streams = remaining,
            activeIdentity = state.activeIdentity.takeUnless { it.equals(identity, true) } ?: fallback,
            focusedIdentity = state.focusedIdentity.takeUnless { it.equals(identity, true) },
            chatIdentity = state.chatIdentity.takeUnless { it.equals(identity, true) } ?: fallback,
            qualityOverrides = state.qualityOverrides.filterKeys { !it.equals(identity, true) },
        )
    }

    fun reorder(state: MultiviewSessionState, identity: String, targetIndex: Int): MultiviewSessionState {
        val fromIndex = state.identities.indexOfFirst { it.equals(identity, true) }
        if (fromIndex < 0 || targetIndex !in state.streams.indices) return state
        val mutable = state.streams.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(targetIndex.coerceIn(0, mutable.size), item)
        return state.copy(streams = mutable)
    }

    fun setActive(state: MultiviewSessionState, identity: String): MultiviewSessionState {
        return if (state.identities.any { it.equals(identity, true) }) state.copy(activeIdentity = identity) else state
    }

    fun setFocus(state: MultiviewSessionState, identity: String?): MultiviewSessionState {
        return if (identity == null || state.identities.any { it.equals(identity, true) }) state.copy(focusedIdentity = identity) else state
    }

    fun stableIdentity(stream: Stream): String? {
        return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
            ?: stream.channelLogin?.trim()?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
    }
}
