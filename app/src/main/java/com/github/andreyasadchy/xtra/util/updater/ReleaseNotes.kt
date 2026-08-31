package com.github.andreyasadchy.xtra.util.updater

import kotlinx.serialization.Serializable

@Serializable
enum class ChangeKind { NEW, IMPROVED, FIXED, SECURITY, OTHER }

@Serializable
data class ChangeItem(val text: String, val kind: ChangeKind)

data class StructuredReleaseNotes(val items: List<ChangeItem>)

object ReleaseNotes {
    private data class ParsedReleaseLine(
        val text: String,
        val explicitKind: ChangeKind?,
    )

    private val commitLine = Regex("^\\s*(?:[0-9a-f]{7,40})\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val commitHash = Regex("\\b[0-9a-f]{7,40}\\b", RegexOption.IGNORE_CASE)
    private val mergeCommit = Regex("^merge\\b", RegexOption.IGNORE_CASE)
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$")
    private val markdownPrefix = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)")
    private val conventionalPrefix = Regex("^(feat|feature|add|fix|fixed|perf|performance|improve|improved|update|refactor|chore|docs?)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")
    private val generatedChangelog = Regex("^\\**full changelog\\**\\s*:", RegexOption.IGNORE_CASE)

    fun structured(body: String?, commits: List<String> = emptyList()): StructuredReleaseNotes {
        val lines = body.orEmpty().lineSequence().toList()
        val hasUsefulBody = lines.any {
            val candidate = it.replace(markdownPrefix, "")
            candidate.isNotBlank() && heading.matchEntire(it) == null && !isNoise(candidate)
        }
        val source = if (hasUsefulBody) lines else commits
        var kind = ChangeKind.OTHER
        val items = mutableListOf<ChangeItem>()
        fun parseLines(sourceLines: List<String>) {
            sourceLines.forEach { line ->
                val match = heading.matchEntire(line)
                if (match != null) {
                    val headingText = clean(match.groupValues[1]) ?: return@forEach
                    val headingKind = kindForHeading(headingText)
                    if (headingKind == ChangeKind.OTHER) items += ChangeItem(headingText, ChangeKind.OTHER)
                    kind = headingKind
                    return@forEach
                }
                val parsed = parseLine(line) ?: return@forEach
                val finalKind = if (kind != ChangeKind.OTHER) kind else parsed.explicitKind ?: kindFor(parsed.text)
                items += ChangeItem(parsed.text, finalKind)
            }
        }
        parseLines(source)
        if (items.isEmpty() && source !== commits && commits.isNotEmpty()) parseLines(commits)
        return StructuredReleaseNotes(
            items.asSequence()
                .filterNot { isNoise(it.text) }
                .distinctBy { it.text.lowercase() }
                .toList(),
        )
    }

    fun kindFor(text: String): ChangeKind {
        val value = text.trim().lowercase()
        return when {
            value.startsWith("feat") || value.startsWith("feature") || value.startsWith("add") -> ChangeKind.NEW
            value.startsWith("fix") -> ChangeKind.FIXED
            value.startsWith("perf") || value.startsWith("improv") -> ChangeKind.IMPROVED
            value.startsWith("security") -> ChangeKind.SECURITY
            value.startsWith("update") || value.startsWith("refactor") -> ChangeKind.IMPROVED
            else -> ChangeKind.OTHER
        }
    }

    fun normalize(body: String?, commits: List<String> = emptyList()): List<String> =
        structured(body, commits).items.map(ChangeItem::text)

    private fun kindForHeading(value: String): ChangeKind = when (value.trim().lowercase()) {
        "added", "add", "new", "features", "feature" -> ChangeKind.NEW
        "changed", "changing", "improved", "improvements", "performance", "updated", "updates" -> ChangeKind.IMPROVED
        "fixed", "fixes", "fixed bugs", "bug fixes", "bugfixes" -> ChangeKind.FIXED
        "security", "security fixes" -> ChangeKind.SECURITY
        else -> ChangeKind.OTHER
    }

    private fun parseLine(line: String): ParsedReleaseLine? {
        if (line.isBlank()) return null
        val withoutPrefix = line.replace(markdownPrefix, "")
        if (mergeCommit.containsMatchIn(withoutPrefix)) return null
        val description = commitLine.matchEntire(withoutPrefix)?.groupValues?.get(1) ?: withoutPrefix
        val conventional = conventionalPrefix.find(description)
        val explicitKind = conventional?.groupValues?.getOrNull(1)?.let(::kindForPrefix)
        val cleaned = description
            .replace(commitHash, "")
            .replace(Regex("\\s+by\\s+@[\\w-]+.*$", RegexOption.IGNORE_CASE), "")
            .let { value -> conventional?.let { value.removeRange(it.range) } ?: value }
            .trim(' ', '-', ':', '|')
            .replace(whitespace, " ")
        return cleaned.takeIf { it.isNotBlank() }?.let(::sentenceCase)?.let {
            ParsedReleaseLine(text = it, explicitKind = explicitKind)
        }
    }

    private fun clean(line: String): String? = parseLine(line)?.text

    private fun kindForPrefix(prefix: String): ChangeKind = when (prefix.lowercase()) {
        "feat", "feature", "add" -> ChangeKind.NEW
        "fix", "fixed" -> ChangeKind.FIXED
        "perf", "performance", "improve", "improved" -> ChangeKind.IMPROVED
        "update", "refactor" -> ChangeKind.IMPROVED
        else -> ChangeKind.OTHER
    }

    private fun sentenceCase(value: String): String {
        val withArticle = value
            .replace(Regex("^fix(?:es)?\\s+", RegexOption.IGNORE_CASE), "Fixed ")
            .replace(Regex("^add(?:ed)?\\s+", RegexOption.IGNORE_CASE), "Added ")
            .replace(Regex("^update(?:d)?\\s+", RegexOption.IGNORE_CASE), "Updated ")
            .replace(Regex("^improve(?:d)?\\s+", RegexOption.IGNORE_CASE), "Improved ")
        return withArticle.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
    }

    private fun isNoise(value: String): Boolean {
        val text = value.trim()
        return text.isBlank() || mergeCommit.containsMatchIn(text) ||
            generatedChangelog.containsMatchIn(text) ||
            text.equals("what's changed", ignoreCase = true) ||
            text.matches(Regex("(?i)(chore|ci|build)?\\s*[:\\-]?\\s*(release automation|automate master build releases|bump version).*"))
    }
}

object UpdateReleaseHistory {
    const val RECENT_RELEASE_COUNT = 5

    fun merge(releases: List<UpdateRelease>): List<UpdateRelease> = releases.asSequence()
        .filter { !it.draft && !it.prerelease }
        .distinctBy(UpdateRelease::id)
        .sortedWith { left, right -> compareNewestFirst(left, right) }
        .toList()

    fun sinceInstalled(releases: List<UpdateRelease>, installedVersionName: String, installedBuildNumber: Long?, fallbackRelease: UpdateRelease? = null): List<UpdateRelease> =
        merge(releases + listOfNotNull(fallbackRelease)).filter { UpdatePolicy.isNewer(installedVersionName, installedBuildNumber, it) }

    fun recent(releases: List<UpdateRelease>, fallbackRelease: UpdateRelease? = null): List<UpdateRelease> =
        merge(releases + listOfNotNull(fallbackRelease)).take(RECENT_RELEASE_COUNT)

    fun notesForUpdate(
        historyComplete: Boolean,
        cumulativeReleases: List<UpdateRelease>,
        latestRelease: UpdateRelease,
    ): List<UpdateRelease> = if (historyComplete && cumulativeReleases.isNotEmpty()) {
        cumulativeReleases
    } else {
        listOf(latestRelease)
    }

    fun retainForInstalled(releases: List<UpdateRelease>, installedVersionName: String, installedBuildNumber: Long?): List<UpdateRelease> {
        val merged = merge(releases)
        val pending = merged.filter { UpdatePolicy.isNewer(installedVersionName, installedBuildNumber, it) }
        return merge(merged.take(RECENT_RELEASE_COUNT) + pending)
    }

    fun formatGrouped(releases: List<UpdateRelease>, noReleaseNotes: String): String = releases.joinToString("\n\n") { release ->
        val notes = release.releaseNotes.ifEmpty { listOf(noReleaseNotes) }.joinToString("\n") { "\u2022 $it" }
        "${release.displayVersion}\n$notes"
    }

    fun formatForUpdate(historyComplete: Boolean, cumulativeReleases: List<UpdateRelease>, latestRelease: UpdateRelease, noReleaseNotes: String, incompleteHistoryMessage: String): String = if (historyComplete) {
        formatGrouped(cumulativeReleases, noReleaseNotes)
    } else {
        buildString {
            append(formatGrouped(listOf(latestRelease), noReleaseNotes))
            append("\n\n")
            append(incompleteHistoryMessage)
        }
    }

    private fun compareNewestFirst(left: UpdateRelease, right: UpdateRelease): Int {
        val versionComparison = UpdatePolicy.compareSemanticVersions(right.versionName, left.versionName)
        if (versionComparison != 0) return versionComparison
        return compareValues(right.buildNumber ?: -1L, left.buildNumber ?: -1L)
    }
}
