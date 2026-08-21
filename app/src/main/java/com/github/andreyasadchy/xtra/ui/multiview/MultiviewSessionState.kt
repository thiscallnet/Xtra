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
    val qualityMode: MultiviewQualityMode = MultiviewQualityMode.AUTO,
    val qualityOverrides: Map<String, String> = emptyMap(),
    val audioVolumes: Map<String, Float> = emptyMap(),
) {
    val identities: List<String>
        get() = streams.mapNotNull(MultiviewSessionReducer::stableIdentity)
}

object MultiviewSessionReducer {
    fun add(
        state: MultiviewSessionState,
        stream: Stream,
        maximum: Int = 4,
        initialAudioVolume: Float = 1f,
    ): MultiviewSessionState {
        val identity = stableIdentity(stream) ?: return state
        if (state.streams.size >= maximum || state.identities.any { it.equals(identity, true) }) return state
        val streams = state.streams + stream
        return state.copy(
            streams = streams,
            activeIdentity = state.activeIdentity ?: identity,
            chatIdentity = state.chatIdentity ?: identity,
            audioVolumes = state.audioVolumes + (identity to initialAudioVolume.coerceIn(0f, 1f)),
        )
    }

    fun addOrReplaceLast(
        state: MultiviewSessionState,
        stream: Stream,
        maximum: Int = 4,
        initialAudioVolume: Float = 1f,
    ): MultiviewSessionState {
        val identity = stableIdentity(stream) ?: return state
        if (state.identities.any { it.equals(identity, true) }) {
            return setActive(state, identity)
        }
        if (state.streams.size < maximum) {
            return setActive(add(state, stream, maximum, initialAudioVolume), identity)
        }
        if (state.streams.isEmpty()) return state

        val replacedIdentity = stableIdentity(state.streams.last())
        val remaining = state.streams.dropLast(1)
        val wasFocused = replacedIdentity != null && state.focusedIdentity.equals(replacedIdentity, true)
        val fallbackChatIdentity = remaining.firstOrNull()?.let(::stableIdentity) ?: identity
        return state.copy(
            streams = remaining + stream,
            activeIdentity = identity,
            focusedIdentity = state.focusedIdentity.takeUnless { it.equals(replacedIdentity, true) },
            layoutMode = if (wasFocused) state.layoutBeforeFocus ?: state.layoutMode else state.layoutMode,
            layoutBeforeFocus = if (wasFocused) null else state.layoutBeforeFocus,
            chatIdentity = state.chatIdentity.takeUnless { it.equals(replacedIdentity, true) }
                ?: fallbackChatIdentity,
            qualityOverrides = state.qualityOverrides.filterKeys {
                !it.equals(replacedIdentity, true)
            },
            audioVolumes = state.audioVolumes.filterKeys {
                !it.equals(replacedIdentity, true)
            } + (identity to initialAudioVolume.coerceIn(0f, 1f)),
        )
    }

    fun replace(
        state: MultiviewSessionState,
        identity: String,
        stream: Stream,
        initialAudioVolume: Float = 1f,
    ): MultiviewSessionState {
        val targetIdentity = stableIdentity(stream) ?: return state
        val sourceIndex = state.streams.indexOfFirst { stableIdentity(it).equals(identity, true) }
        if (sourceIndex < 0) return state
        val replacedIdentity = stableIdentity(state.streams[sourceIndex]) ?: return state
        if (targetIdentity.equals(replacedIdentity, true)) {
            return state.copy(activeIdentity = targetIdentity)
        }
        if (state.identities.any { it.equals(targetIdentity, true) }) {
            val remaining = state.streams.filterNot { stableIdentity(it).equals(replacedIdentity, true) }
            return state.copy(
                streams = remaining,
                activeIdentity = targetIdentity,
                focusedIdentity = if (state.focusedIdentity.equals(identity, true)) targetIdentity else state.focusedIdentity,
                chatIdentity = if (state.chatIdentity.equals(identity, true)) targetIdentity else state.chatIdentity,
                qualityOverrides = state.qualityOverrides.filterKeys { !it.equals(replacedIdentity, true) },
                audioVolumes = state.audioVolumes.filterKeys { !it.equals(replacedIdentity, true) },
            )
        }

        val streams = state.streams.toMutableList().apply { this[sourceIndex] = stream }
        val volume = state.audioVolumes[replacedIdentity] ?: initialAudioVolume
        return state.copy(
            streams = streams,
            activeIdentity = targetIdentity,
            focusedIdentity = if (state.focusedIdentity.equals(identity, true)) targetIdentity else state.focusedIdentity,
            chatIdentity = if (state.chatIdentity.equals(identity, true)) targetIdentity else state.chatIdentity,
            qualityOverrides = state.qualityOverrides.filterKeys { !it.equals(replacedIdentity, true) },
            audioVolumes = state.audioVolumes.filterKeys { !it.equals(replacedIdentity, true) } +
                (targetIdentity to volume.coerceIn(0f, 1f)),
        )
    }

    fun remove(state: MultiviewSessionState, identity: String): MultiviewSessionState {
        val remaining = state.streams.filterNot { stableIdentity(it).equals(identity, true) }
        if (remaining.isEmpty()) {
            return state.copy(
                streams = emptyList(),
                activeIdentity = null,
                focusedIdentity = null,
                chatIdentity = null,
                audioVolumes = emptyMap(),
            )
        }
        val fallback = remaining.firstOrNull()?.let(::stableIdentity)
        return state.copy(
            streams = remaining,
            activeIdentity = state.activeIdentity.takeUnless { it.equals(identity, true) } ?: fallback,
            focusedIdentity = state.focusedIdentity.takeUnless { it.equals(identity, true) },
            chatIdentity = state.chatIdentity.takeUnless { it.equals(identity, true) } ?: fallback,
            qualityOverrides = state.qualityOverrides.filterKeys { !it.equals(identity, true) },
            audioVolumes = state.audioVolumes.filterKeys { !it.equals(identity, true) },
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

    fun setAudioVolume(state: MultiviewSessionState, identity: String, volume: Float): MultiviewSessionState {
        return if (state.identities.any { it.equals(identity, true) }) {
            state.copy(audioVolumes = state.audioVolumes + (identity to volume.coerceIn(0f, 1f)))
        } else {
            state
        }
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
