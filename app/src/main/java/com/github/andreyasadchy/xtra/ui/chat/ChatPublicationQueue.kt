package com.github.andreyasadchy.xtra.ui.chat

/**
 * Android-independent staging state for chat publication. Entries are not visible until their
 * state is terminal and all earlier publication barriers have committed.
 */
internal enum class PublicationState { QUEUED, PREPARING, READY, REMOVED }

internal data class PublicationEntry<T>(
    val value: T,
    var generation: Long,
    val trimBeforePublish: Int = 0,
    var state: PublicationState = PublicationState.QUEUED,
    var preparationToken: Long = 0L,
)

internal class ChatPublicationQueue<T> {
    private val appendEntries = ArrayDeque<PublicationEntry<T>>()
    private val prependEntries = ArrayDeque<List<PublicationEntry<T>>>()
    var replacementEntries: List<PublicationEntry<T>>? = null
        private set
    val hasPendingPrepends: Boolean get() = prependEntries.isNotEmpty()
    val hasPendingAppends: Boolean get() = appendEntries.isNotEmpty()

    fun enqueueAppend(entries: List<PublicationEntry<T>>) {
        appendEntries.addAll(entries)
    }

    fun enqueuePrepend(entries: List<PublicationEntry<T>>) {
        prependEntries.addLast(entries)
    }

    /** Starts a replacement and discards every mutation staged before this snapshot. */
    fun beginReplacement(entries: List<PublicationEntry<T>>) {
        appendEntries.clear()
        prependEntries.clear()
        replacementEntries = entries
    }

    fun clear() {
        appendEntries.clear()
        prependEntries.clear()
        replacementEntries = null
    }

    fun hasPendingPublications(): Boolean =
        replacementEntries != null || prependEntries.isNotEmpty() || appendEntries.isNotEmpty()

    fun isReplacementEntry(entry: PublicationEntry<T>): Boolean = replacementEntries?.contains(entry) == true

    /** Takes one terminal append segment, respecting replacement and prepend barriers. */
    fun takeReadyAppendSegment(): List<PublicationEntry<T>>? {
        if (replacementEntries != null || prependEntries.isNotEmpty()) return null
        val first = appendEntries.firstOrNull()
        if (first == null || !first.state.isTerminal()) return null
        val result = ArrayList<PublicationEntry<T>>()
        while (true) {
            val entry = appendEntries.firstOrNull() ?: break
            if (!entry.state.isTerminal()) break
            if (result.isNotEmpty() && entry.trimBeforePublish > 0) break
            appendEntries.removeFirst()
            result += entry
        }
        return result.takeIf { it.isNotEmpty() }
    }

    fun takeReadyPrepend(): List<PublicationEntry<T>>? {
        if (replacementEntries != null) return null
        val entries = prependEntries.firstOrNull() ?: return null
        if (entries.any { !it.state.isTerminal() }) return null
        prependEntries.removeFirst()
        return entries
    }

    fun takeReadyReplacement(): List<PublicationEntry<T>>? {
        val entries = replacementEntries ?: return null
        if (entries.any { !it.state.isTerminal() }) return null
        replacementEntries = null
        return entries
    }

    private fun PublicationState.isTerminal(): Boolean =
        this == PublicationState.READY || this == PublicationState.REMOVED
}
