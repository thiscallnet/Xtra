package com.github.andreyasadchy.xtra.util.updater

object ReleaseNotes {

    private val commitLine = Regex("^\\s*(?:[0-9a-f]{7,40})\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val commitHash = Regex("\\b[0-9a-f]{7,40}\\b", RegexOption.IGNORE_CASE)
    private val mergeCommit = Regex("^merge\\b", RegexOption.IGNORE_CASE)
    private val markdownPrefix = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)")
    private val whitespace = Regex("\\s+")

    fun normalize(body: String?, commits: List<String> = emptyList()): List<String> {
        val source = body.orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        val bodyNotes = normalizeLines(source)
        return (bodyNotes + normalizeLines(commits))
            .asSequence()
            .distinctBy { it.lowercase() }
            .toList()
    }

    private fun normalizeLines(lines: List<String>): List<String> = lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { mergeCommit.containsMatchIn(it) }
            .mapNotNull(::normalizeLine)
            .map(::sentenceCase)
            .map { it.replace(whitespace, " ").trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("full changelog", ignoreCase = true) }
            .toList()

    private fun normalizeLine(line: String): String? {
        if (line.startsWith("#")) return null
        val withoutPrefix = line.replace(markdownPrefix, "")
        val description = commitLine.matchEntire(withoutPrefix)?.groupValues?.get(1)
            ?: withoutPrefix
        val cleaned = description
            .replace(commitHash, "")
            .replace(Regex("\\s+by\\s+@[\\w-]+.*$", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', ':', '|')
        return cleaned.takeIf { it.isNotBlank() && !mergeCommit.containsMatchIn(it) }
    }

    private fun sentenceCase(value: String): String {
        val withArticle = value
            .replace(Regex("^fix (.+)$", RegexOption.IGNORE_CASE)) { "Fixed ${it.groupValues[1]}" }
            .replace(Regex("^add (.+)$", RegexOption.IGNORE_CASE)) { "Added ${it.groupValues[1]}" }
            .replace(Regex("^update (.+)$", RegexOption.IGNORE_CASE)) { "Updated ${it.groupValues[1]}" }
        return withArticle.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
