package com.github.andreyasadchy.xtra.ui.player.captions

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

private const val MAX_CAPTION_CHARACTERS = 120
private const val MAX_CAPTION_WORDS = 24

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

    fun reset() {
        visibleText = ""
    }
}
