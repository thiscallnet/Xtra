package com.github.andreyasadchy.xtra.ui.player.captions

import com.github.andreyasadchy.xtra.ui.player.captions.engine.CaptionRecognitionEvent

data class LiveCaptionState(
    val enabled: Boolean = false,
    val status: Status = Status.OFF,
    val text: String = "",
    val error: String? = null,
) {
    enum class Status {
        OFF,
        STARTING,
        LISTENING,
        ERROR,
    }
}

private const val MAX_CAPTION_CHARACTERS = 72
private const val MAX_CAPTION_WORDS = 14
private const val MAX_DISPLAY_LINE_CHARACTERS = 36

internal fun limitCaptionText(text: String): String {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return ""

    return words
        .takeLast(MAX_CAPTION_WORDS)
        .joinToString(" ")
        .let { candidate ->
            if (candidate.length <= MAX_CAPTION_CHARACTERS) {
                candidate
            } else {
                candidate.takeLast(MAX_CAPTION_CHARACTERS)
                    .substringAfter(' ', "")
                    .ifBlank { candidate.takeLast(MAX_CAPTION_CHARACTERS) }
            }
        }
}

/** Keeps the live window to two short rolling lines, like a live-caption display. */
internal fun formatCaptionTextForDisplay(text: String): String {
    val words = limitCaptionText(text)
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
    if (words.isEmpty()) return ""

    val lines = ArrayDeque<String>()
    var current = ""
    for (word in words.asReversed()) {
        val candidate = if (current.isEmpty()) word else "$word $current"
        if (current.isNotEmpty() && candidate.length > MAX_DISPLAY_LINE_CHARACTERS) {
            lines.addFirst(current)
            current = word
            if (lines.size == 2) break
        } else {
            current = candidate.takeLast(MAX_DISPLAY_LINE_CHARACTERS)
        }
    }
    if (current.isNotEmpty() && lines.size < 2) lines.addFirst(current)
    return lines.takeLast(2).joinToString("\n")
}

/** Small, transcript-free text state used by the manager and JVM tests. */
internal class CaptionTextStateMachine {
    var visibleText: String = ""
        private set

    fun updatePartial(text: String) {
        visibleText = limitCaptionText(text)
    }

    fun finalize(text: String) {
        visibleText = limitCaptionText(text)
    }

    fun apply(event: CaptionRecognitionEvent) {
        when (event) {
            is CaptionRecognitionEvent.Partial -> updatePartial(event.text)
            is CaptionRecognitionEvent.Final -> finalize(event.text)
        }
    }

    fun reset() {
        visibleText = ""
    }
}
