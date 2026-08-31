package com.github.andreyasadchy.xtra.ui.player.captions

import com.github.andreyasadchy.xtra.ui.player.captions.engine.CaptionRecognitionEvent

data class LiveCaptionState(
    val enabled: Boolean = false,
    val status: Status = Status.OFF,
    val text: String = "",
    val error: String? = null,
    /** Increments when a completed caption line rolls upward. */
    val lineShiftToken: Long = 0L,
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
    val normalized = text.trim()
    if (normalized.isEmpty()) return ""

    // The state machine normally gives us already-laid-out lines. Preserve those
    // line breaks: re-wrapping the whole ASR hypothesis on every partial is what
    // made previously readable words jump between lines.
    if (normalized.contains('\n')) {
        return normalized
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
            .takeLast(2)
            .joinToString("\n")
    }

    val lines = mutableListOf<String>()
    var current = ""
    limitCaptionText(normalized).split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && candidate.length > MAX_DISPLAY_LINE_CHARACTERS) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
    if (current.isNotEmpty()) lines += current
    return lines.takeLast(2).joinToString("\n")
}

/**
 * Small, transcript-free text state used by the manager and JVM tests.
 *
 * Partial ASR results are cumulative hypotheses. The active partial portion is
 * replaceable so decoder corrections are visible, while finalized rolling lines
 * remain fixed until a complete line rolls away.
 */
internal class CaptionTextStateMachine {
    private data class DisplayedWord(
        val text: String,
        val activePartial: Boolean,
    )

    private val displayedLines = ArrayDeque<MutableList<DisplayedWord>>()

    var visibleText: String = ""
        private set

    var lineShiftToken: Long = 0L
        private set

    fun updatePartial(text: String) {
        val words = words(text)
        if (words.isEmpty()) return

        replaceActivePartial(words)
        updateVisibleText()
    }

    fun finalize(text: String) {
        val words = words(text)
        if (words.isNotEmpty()) {
            replaceActivePartial(words)
        }
        markPartialWordsFinal()
        updateVisibleText()
    }

    fun apply(event: CaptionRecognitionEvent) {
        when (event) {
            is CaptionRecognitionEvent.Partial -> updatePartial(event.text)
            is CaptionRecognitionEvent.Final -> finalize(event.text)
        }
    }

    fun reset() {
        displayedLines.clear()
        visibleText = ""
    }

    private fun replaceActivePartial(words: List<String>) {
        displayedLines.forEach { line ->
            line.removeAll { it.activePartial }
        }
        val lines = displayedLines.iterator()
        while (lines.hasNext()) {
            if (lines.next().isEmpty()) lines.remove()
        }
        words.forEach { appendWord(it, activePartial = true) }
    }

    private fun appendWord(text: String, activePartial: Boolean) {
        val currentLine = displayedLines.lastOrNull()
        val startsNewLine = currentLine != null &&
            currentLine.isNotEmpty() &&
            formatWords(currentLine.map { it.text } + text).length > MAX_DISPLAY_LINE_CHARACTERS
        val destination = if (currentLine == null || startsNewLine) {
            mutableListOf<DisplayedWord>().also(displayedLines::addLast)
        } else {
            currentLine
        }
        destination += DisplayedWord(text, activePartial)
        while (displayedLines.size > 2) {
            displayedLines.removeFirst()
            lineShiftToken++
        }
    }

    private fun markPartialWordsFinal() {
        displayedLines.forEach { line ->
            line.replaceAll { word ->
                word.copy(activePartial = false)
            }
        }
    }

    private fun updateVisibleText() {
        visibleText = displayedLines.joinToString("\n") { line ->
            formatWords(line.map { it.text })
        }
    }

    private fun words(text: String): List<String> = text.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)

    private fun formatWords(words: List<String>): String {
        if (words.isEmpty()) return ""
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && candidate.length > MAX_DISPLAY_LINE_CHARACTERS) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.joinToString("\n")
    }
}
