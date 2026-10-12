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

private const val DEFAULT_MAX_LINE_CHARACTERS = 36

/**
 * The newest hypothesis words are still likely to be rewritten by the recognizer, so they are
 * held back until later words confirm them.
 */
private const val UNSTABLE_TAIL_WORDS = 2

/**
 * Small, transcript-free text state used by the manager and JVM tests.
 *
 * Partial ASR results are cumulative hypotheses that the recognizer keeps rewriting. Like
 * YouTube's live captions, the display is append-only: a word is shown once, only after it
 * is stable, and is never changed afterwards. Existing text only moves up when a row rolls.
 */
internal class CaptionTextStateMachine {
    private val displayedLines = ArrayDeque<MutableList<String>>()

    /** Words of the current utterance that are already on screen, in order. */
    private val committedPrefix = mutableListOf<String>()

    /** Whether a candidate row fits the caption window. The view supplies a pixel measurer. */
    private var lineFits: (String) -> Boolean = { it.length <= DEFAULT_MAX_LINE_CHARACTERS }

    var visibleText: String = ""
        private set

    var lineShiftToken: Long = 0L
        private set

    fun updatePartial(text: String) {
        val words = words(text)
        commit(words, (words.size - UNSTABLE_TAIL_WORDS).coerceAtLeast(0))
        updateVisibleText()
    }

    fun finalize(text: String) {
        val words = words(text)
        // A final shorter than the words already shown is ignored rather than re-appended.
        if (words.size >= committedPrefix.size) appendStable(words, words.size)
        committedPrefix.clear()
        updateVisibleText()
    }

    fun apply(event: CaptionRecognitionEvent) {
        when (event) {
            is CaptionRecognitionEvent.Partial -> updatePartial(event.text)
            is CaptionRecognitionEvent.Final -> finalize(event.text)
        }
    }

    /** Re-flows the visible words into rows that fit the new measurer without signalling a roll. */
    fun setLineFits(fits: (String) -> Boolean) {
        lineFits = fits
        val words = displayedLines.flatten()
        if (words.isEmpty()) return
        displayedLines.clear()
        // Rows that no longer fit roll away and bump the token, so the view animates them instead of jumping.
        words.forEach(::appendWord)
        updateVisibleText()
    }

    fun reset() {
        displayedLines.clear()
        committedPrefix.clear()
        visibleText = ""
    }

    /** Appends hypothesis words that are not on screen yet; words already shown are never revised. */
    private fun commit(words: List<String>, stableCount: Int) {
        if (words.size < committedPrefix.size) {
            // A shorter hypothesis that still matches what is shown is a temporary rewrite; keep waiting.
            if (words == committedPrefix.subList(0, words.size)) return
            // Otherwise the recognizer restarted the utterance without a final result.
            committedPrefix.clear()
        }
        appendStable(words, stableCount)
    }

    private fun appendStable(words: List<String>, stableCount: Int) {
        for (index in committedPrefix.size until stableCount) {
            appendWord(words[index])
            committedPrefix += words[index]
        }
    }

    private fun appendWord(text: String) {
        val currentLine = displayedLines.lastOrNull()
        val startsNewLine = currentLine != null &&
            currentLine.isNotEmpty() &&
            !lineFits(currentLine.joinToString(" ") + " " + text)
        val destination = if (currentLine == null || startsNewLine) {
            mutableListOf<String>().also(displayedLines::addLast)
        } else {
            currentLine
        }
        destination += text
        while (displayedLines.size > 2) {
            displayedLines.removeFirst()
            lineShiftToken++
        }
    }

    private fun updateVisibleText() {
        visibleText = displayedLines.joinToString("\n") { it.joinToString(" ") }
    }

    private fun words(text: String): List<String> = text.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
}
