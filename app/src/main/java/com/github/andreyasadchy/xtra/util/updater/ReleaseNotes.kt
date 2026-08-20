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
        return (bodyNotes.ifEmpty { normalizeLines(commits) })
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

object UpdateReleaseHistory {

    const val RECENT_RELEASE_COUNT = 5

    fun merge(releases: List<UpdateRelease>): List<UpdateRelease> = releases
        .asSequence()
        .filter { !it.draft && !it.prerelease }
        .distinctBy(UpdateRelease::id)
        .sortedWith { left, right -> compareNewestFirst(left, right) }
        .toList()

    fun sinceInstalled(
        releases: List<UpdateRelease>,
        installedVersionName: String,
        installedBuildNumber: Long?,
        fallbackRelease: UpdateRelease? = null,
    ): List<UpdateRelease> = merge(releases + listOfNotNull(fallbackRelease))
        .filter { release -> UpdatePolicy.isNewer(installedVersionName, installedBuildNumber, release) }

    fun recent(
        releases: List<UpdateRelease>,
        fallbackRelease: UpdateRelease? = null,
    ): List<UpdateRelease> = merge(releases + listOfNotNull(fallbackRelease))
        .take(RECENT_RELEASE_COUNT)

    fun retainForInstalled(
        releases: List<UpdateRelease>,
        installedVersionName: String,
        installedBuildNumber: Long?,
    ): List<UpdateRelease> {
        val merged = merge(releases)
        val pending = merged.filter { release ->
            UpdatePolicy.isNewer(installedVersionName, installedBuildNumber, release)
        }
        return merge(merged.take(RECENT_RELEASE_COUNT) + pending)
    }

    fun formatGrouped(releases: List<UpdateRelease>, noReleaseNotes: String): String = releases.joinToString("\n\n") { release ->
        val notes = release.releaseNotes
            .ifEmpty { listOf(noReleaseNotes) }
            .joinToString("\n") { "\u2022 $it" }
        "${release.displayVersion}\n$notes"
    }

    fun formatForUpdate(
        historyComplete: Boolean,
        cumulativeReleases: List<UpdateRelease>,
        latestRelease: UpdateRelease,
        noReleaseNotes: String,
        incompleteHistoryMessage: String,
    ): String = if (historyComplete) {
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
