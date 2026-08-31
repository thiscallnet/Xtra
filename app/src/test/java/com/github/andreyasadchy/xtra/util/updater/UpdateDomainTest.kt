package com.github.andreyasadchy.xtra.util.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class UpdateDomainTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun semanticVersionAndBuildOrderingDoNotDowngrade() {
        val newer = parse("v2.58.5-build.125")
        val same = parse("v2.58.5-build.125")
        val older = parse("v2.58.5-build.125")

        assertEquals(UpdateDecision.Available, UpdatePolicy.decide("2.58.5", 124, newer, null, automatic = true))
        assertEquals(UpdateDecision.Current, UpdatePolicy.decide("2.58.5", 125, same, null, automatic = true))
        assertEquals(UpdateDecision.Current, UpdatePolicy.decide("2.58.5", 126, older, null, automatic = true))
        assertEquals(
            UpdateDecision.Available,
            UpdatePolicy.decide("2.58.4", 999, parse("v2.58.5-build.1"), null, automatic = true),
        )
    }

    @Test
    fun ignoredReleaseIsSuppressedAutomaticallyButVisibleManually() {
        val release = parse("v2.58.5-build.125")
        val later = parse("v2.58.5-build.126")

        assertEquals(UpdateDecision.Ignored, UpdatePolicy.decide("2.58.4", 124, release, release.id, automatic = true))
        assertEquals(UpdateDecision.Available, UpdatePolicy.decide("2.58.4", 124, release, release.id, automatic = false))
        assertTrue(UpdatePolicy.decide("2.58.4", 124, later, release.id, automatic = true) is UpdateDecision.Available)
    }

    @Test
    fun deferredReleaseIsExplicitAndNotUpToDate() {
        val release = parse("v2.58.5-build.125")

        assertEquals(UpdateDecision.Deferred, UpdatePolicy.decide("2.58.4", 124, release, null, automatic = true, deferred = true))
        assertEquals(UpdateDecision.Available, UpdatePolicy.decide("2.58.4", 124, release, null, automatic = false, deferred = true))
        assertEquals(release, UpdateState.Deferred(release).release)
        assertEquals(release, UpdateState.Deferred(release).downloadableRelease())
    }

    @Test
    fun buildTagIsParsedAndMalformedTagIsRejected() {
        val release = parse("v2.58.5-build.125")

        assertEquals("v2.58.5-build.125", release.tagName)
        assertEquals("2.58.5", release.versionName)
        assertEquals(125L, release.buildNumber)
        assertTrue(ReleaseParser.parse(json.parseToJsonElement(response("not-a-release")).jsonObject, "https://example.test") is ReleaseParseResult.Failure)
    }

    @Test
    fun releaseMetadataCarriesExactArchiveVersionCode() {
        val response = JsonObject(
            json.parseToJsonElement(response("v2.58.5-build.173")).jsonObject +
                (RELEASE_METADATA_RESPONSE_KEY to buildJsonObject {
                    put("versionName", "2.58.5")
                    put("versionCode", 294L)
                }),
        )

        val release = (ReleaseParser.parse(response, "https://example.test") as ReleaseParseResult.Success).release

        assertEquals(294L, release.expectedVersionCode)
        assertTrue(release.releaseNotes.none { it.contains("metadata", ignoreCase = true) })
    }

    @Test
    fun missingAndAmbiguousApkAssetsFailDeliberately() {
        val noApk = parse("v2.58.5-build.125", assets = listOf("notes.txt"))
        assertEquals(UpdateError.MissingApk, (UpdatePolicy.selectAsset(noApk).exceptionOrNull() as? UpdateException)?.error)

        val ambiguous = parse(
            "v2.58.5-build.125",
            assets = listOf("xtra-arm64-release.apk", "xtra-x86-release.apk"),
        )
        assertEquals(UpdateError.AmbiguousApk, (UpdatePolicy.selectAsset(ambiguous).exceptionOrNull() as? UpdateException)?.error)

        val deterministic = parse(
            "v2.58.5-build.125",
            assets = listOf("xtra-arm64-release.apk", "app-release.apk"),
        )
        assertEquals("app-release.apk", UpdatePolicy.selectAsset(deterministic).getOrThrow().name)
    }

    @Test
    fun currentReleaseDoesNotNeedAnApkToBeUpToDate() {
        val currentWithoutApk = parse("v2.58.5-build.125", assets = listOf("notes.txt"))

        assertEquals(
            UpdateDecision.Current,
            UpdatePolicy.decide("2.58.5", 125, currentWithoutApk, null, automatic = false),
        )
        assertEquals(
            UpdateError.MissingApk,
            (UpdatePolicy.selectAsset(currentWithoutApk).exceptionOrNull() as? UpdateException)?.error,
        )
    }

    @Test
    fun releaseNotesRemoveHashesAndDeduplicate() {
        val release = parse(
            "v2.58.5-build.125",
            body = """
                b6fde121 Fix tiny emotes in picker
                ed8de793 Fix tiny emotes in picker

                ## Improvements
                """.trimIndent(),
        )

        assertEquals(listOf("Fixed tiny emotes in picker"), release.releaseNotes)
        assertTrue(release.releaseNotes.none { it.contains("b6fde121") || it.contains("ed8de793") })
    }

    @Test
    fun releaseNotesIncludeReleaseBodyAndAllCompareCommits() {
        assertEquals(
            listOf("Fixed first issue", "Added second issue", "Updated third issue"),
            ReleaseNotes.normalize(
                body = "* Fix first issue",
                commits = listOf("abc1234 Add second issue", "def5678 Update third issue"),
            ),
        )
    }

    @Test
    fun meaninglessReleaseBodyFallsBackToCommitDescriptions() {
        assertEquals(
            listOf("Fixed chat spacing"),
            ReleaseNotes.normalize("## Changes", listOf("b6fde121 Fix chat spacing")),
        )
    }

    @Test
    fun mergeCommitsAreNotUserReleaseNotes() {
        assertEquals(
            listOf("Fixed chat spacing"),
            ReleaseNotes.normalize(
                body = "- Merge master into the release",
                commits = listOf("Merge branch 'master'", "b6fde121 Fix chat spacing"),
            ),
        )
    }

    @Test
    fun retryRoutesCheckStagesToCheckEvenWhenStaleMetadataExists() {
        val stale = parse("v2.58.4-build.124")
        val error = UpdateState.Error(
            stage = UpdateStage.CHECK,
            cause = UpdateError.NoConnection,
            retryable = true,
            release = stale,
        )

        assertEquals(UpdateRetryAction.CHECK, error.retryAction())
        assertNull(error.downloadableRelease())
    }

    @Test
    fun assetSelectionFailureCannotExposeAnOldDownloadAction() {
        val oldRelease = parse("v2.58.4-build.124")
        val error = UpdateState.Error(
            stage = UpdateStage.ASSET_SELECTION,
            cause = UpdateError.MissingApk,
            retryable = true,
            release = oldRelease,
        )

        assertEquals(UpdateRetryAction.CHECK, error.retryAction())
        assertNull(error.downloadableRelease())
    }

    @Test
    fun permissionRecoveryRestoresTheInstallAction() {
        val release = parse("v2.58.5-build.125")
        val artifact = DownloadedArtifact(1L, null, "app-release.apk", 10L)
        val denied = UpdateState.Error(
            stage = UpdateStage.INSTALL,
            cause = UpdateError.InstallPermissionDenied,
            retryable = true,
            release = release,
            artifact = artifact,
        )

        assertTrue(UpdatePolicy.restoreAfterInstallPermission(denied, true) is UpdateState.Downloaded)
        assertFalse(UpdatePolicy.restoreAfterInstallPermission(denied, false) is UpdateState.Downloaded)
    }

    @Test
    fun installCallbacksRequireTheCurrentReleaseAndSession() {
        assertTrue(UpdatePolicy.installCallbackMatches("v2.58.5-build.125", 17, "v2.58.5-build.125", 17))
        assertFalse(UpdatePolicy.installCallbackMatches("v2.58.5-build.125", 17, "v2.58.4-build.124", 17))
        assertFalse(UpdatePolicy.installCallbackMatches("v2.58.5-build.125", 17, "v2.58.5-build.125", 16))
    }

    @Test
    fun repeatedInstallRequestsHaveOneSessionOwner() {
        val gate = UpdateInstallGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        gate.markCommitted(17)
        assertFalse(gate.tryBegin())
        gate.finish(17)
        assertTrue(gate.tryBegin())
    }

    @Test
    fun remoteBuildNumberIsIndependentFromAndroidVersionCode() {
        val release = parse("v2.58.5-build.132")

        assertEquals(132L, release.buildNumber)
        assertEquals("2.58.5 (build 132)", release.displayVersion)
        assertEquals("2.58.5 (build 132)", UpdateVersionDisplay.installed("2.58.5", 253L, 121L))
        assertEquals(132L, UpdateVersionDisplay.installedBuildNumber(253L, 121L))
        assertTrue(UpdatePolicy.isNewer("2.58.5", 132L, parse("v2.59.0-build.1")))
        assertTrue(UpdatePolicy.isNewer("2.58.5", 132L, parse("v2.58.5-build.133")))
        // The future APK uses a different version-code base; only its actual archive code matters.
        assertTrue(UpdatePolicy.isCompatibleArchive("com.github.andreyasadchy.xtra", 1001L, "com.github.andreyasadchy.xtra", 253L))
        assertFalse(UpdatePolicy.isCompatibleArchive("com.other.app", 1001L, "com.github.andreyasadchy.xtra", 253L))
        assertTrue(
            UpdatePolicy.isCompatibleArchive(
                archivePackageName = "com.github.andreyasadchy.xtra",
                archiveVersionName = "2.59.0",
                archiveVersionCode = 1001L,
                installedPackageName = "com.github.andreyasadchy.xtra",
                installedVersionCode = 253L,
                releaseVersionName = "2.59.0",
                expectedVersionCode = 1001L,
            ),
        )
        assertFalse(
            UpdatePolicy.isCompatibleArchive(
                archivePackageName = "com.github.andreyasadchy.xtra",
                archiveVersionName = "2.58.5",
                archiveVersionCode = 1001L,
                installedPackageName = "com.github.andreyasadchy.xtra",
                installedVersionCode = 253L,
                releaseVersionName = "2.59.0",
                expectedVersionCode = 1002L,
            ),
        )
    }

    @Test
    fun skippedStateKeepsTheRemoteBuildVisibleAndNotNowIsImmediate() {
        val release = parse("v2.58.5-build.132")

        assertEquals("2.58.5 (build 132)", UpdateState.Skipped(release).release.displayVersion)
        assertEquals(release, UpdateState.Available(release, previouslySkipped = true).downloadableRelease())
        assertFalse(UpdateState.Deferred(release).equals(UpdateState.UpToDate(release)))
    }

    @Test
    fun newerCheckDiscardsACompletedOlderDownload() {
        val oldRelease = parse("v2.58.5-build.125")
        val newRelease = parse("v2.58.5-build.126")

        assertTrue(UpdatePolicy.shouldDiscardPersistedRelease(oldRelease.id, newRelease.id))
        assertFalse(UpdatePolicy.shouldDiscardPersistedRelease(newRelease.id, newRelease.id))
        assertEquals(newRelease, UpdateState.Available(newRelease).release)
    }

    @Test
    fun pendingInstallerRecoveryDistinguishesCommittedPreparedAndTerminalSessions() {
        val committed = UpdateInstallSessionSnapshot(committed = true, sealed = true, terminal = false)
        val prepared = UpdateInstallSessionSnapshot(committed = false, sealed = false, terminal = false)
        val uncertain = UpdateInstallSessionSnapshot(
            committed = false,
            sealed = false,
            terminal = false,
            commitStateKnown = false,
        )
        val terminal = UpdateInstallSessionSnapshot(committed = true, sealed = true, terminal = true)

        assertEquals(
            UpdateInstallRecoveryState.COMMITTED,
            UpdatePolicy.installRecoveryState(committed, pendingIntentPersisted = false, commitStarted = false),
        )
        assertEquals(
            UpdateInstallRecoveryAction.RESUME,
            UpdatePolicy.installRecoveryAction(committed, pendingIntentPersisted = false, commitStarted = false),
        )
        assertEquals(
            UpdateInstallRecoveryState.AWAITING_USER_ACTION,
            UpdatePolicy.installRecoveryState(committed, pendingIntentPersisted = true, commitStarted = false),
        )
        assertEquals(
            UpdateInstallRecoveryAction.RETRY,
            UpdatePolicy.installRecoveryAction(prepared, pendingIntentPersisted = false, commitStarted = false),
        )
        assertEquals(
            UpdateInstallRecoveryAction.RETRY,
            UpdatePolicy.installRecoveryAction(terminal, pendingIntentPersisted = true, commitStarted = false),
        )
        assertEquals(
            UpdateInstallRecoveryAction.RETRY,
            UpdatePolicy.installRecoveryAction(snapshot = null, pendingIntentPersisted = true, commitStarted = true),
        )
        assertEquals(
            UpdateInstallRecoveryState.COMMIT_UNCERTAIN,
            UpdatePolicy.installRecoveryState(prepared, pendingIntentPersisted = false, commitStarted = true),
        )
        assertEquals(
            UpdateInstallRecoveryAction.RECOMMIT,
            UpdatePolicy.installRecoveryAction(prepared, pendingIntentPersisted = false, commitStarted = true),
        )
        assertEquals(
            UpdateInstallRecoveryState.COMMIT_UNCERTAIN,
            UpdatePolicy.installRecoveryState(uncertain, pendingIntentPersisted = false, commitStarted = true),
        )
        assertEquals(
            UpdateInstallRecoveryAction.RECOMMIT,
            UpdatePolicy.installRecoveryAction(uncertain, pendingIntentPersisted = false, commitStarted = true),
        )
    }

    @Test
    fun stageSpecificRetryActionsHonorRetryable() {
        assertEquals(
            UpdateRetryAction.CHECK,
            UpdateState.Error(UpdateStage.PARSE, UpdateError.InvalidResponse, true).retryAction(),
        )
        assertEquals(
            UpdateRetryAction.DOWNLOAD,
            UpdateState.Error(UpdateStage.DOWNLOAD, UpdateError.DownloadFailed, true, release = parse("v2.58.5-build.125")).retryAction(),
        )
        assertEquals(
            UpdateRetryAction.INSTALL,
            UpdateState.Error(
                UpdateStage.INSTALL,
                UpdateError.InstallFailed,
                true,
                release = parse("v2.58.5-build.125"),
                artifact = DownloadedArtifact(1L, null, "app-release.apk", 10L),
            ).retryAction(),
        )
        assertNull(UpdateState.Error(UpdateStage.DOWNLOAD, UpdateError.DownloadFailed, false).retryAction())
    }

    @Test
    fun installPrimaryActionsDoNotDuplicateRemediationAndRetry() {
        val release = parse("v2.58.5-build.125")
        val artifact = DownloadedArtifact(1L, null, "app-release.apk", 10L)

        assertEquals(
            UpdatePrimaryAction.ALLOW_INSTALL,
            UpdateState.Error(UpdateStage.INSTALL, UpdateError.InstallPermissionDenied, true, release, artifact).primaryAction(),
        )
        assertEquals(
            UpdatePrimaryAction.INSTALL,
            UpdateState.Error(UpdateStage.INSTALL, UpdateError.InstallCancelled, true, release, artifact).primaryAction(),
        )
        assertNull(
            UpdateState.Error(UpdateStage.INSTALL, UpdateError.IncompatibleApk, false, release, artifact).primaryAction(),
        )
        assertNull(
            UpdateState.Error(UpdateStage.INSTALL, UpdateError.InstallPermissionDenied, true, release).primaryAction(),
        )
    }

    @Test
    fun updateIndicatorOnlyCoversActionableStates() {
        val release = parse("v2.58.5-build.125")
        val artifact = DownloadedArtifact(1L, null, "app-release.apk", 10L)

        assertTrue(UpdateState.Available(release).hasActionableUpdate())
        assertTrue(
            UpdateState.Error(
                UpdateStage.CHECK,
                UpdateError.NoConnection,
                retryable = true,
                release = release,
                preservedAction = UpdatePrimaryAction.DOWNLOAD,
            ).hasActionableUpdate(),
        )
        assertTrue(
            UpdateState.Error(
                UpdateStage.CHECK,
                UpdateError.NoConnection,
                retryable = true,
                release = release,
                artifact = artifact,
                preservedAction = UpdatePrimaryAction.INSTALL,
            ).hasActionableUpdate(),
        )
        assertTrue(
            UpdateState.Error(
                UpdateStage.DOWNLOAD,
                UpdateError.DownloadFailed,
                retryable = true,
                release = release,
            ).hasActionableUpdate(),
        )
        val downloadError = UpdateState.Error(
            UpdateStage.DOWNLOAD,
            UpdateError.DownloadedFileMissing,
            retryable = true,
            release = release,
        )
        assertEquals(UpdateRetryAction.DOWNLOAD, downloadError.retryAction())
        assertNull(downloadError.downloadableRelease())
        assertFalse(UpdateState.Error(UpdateStage.CHECK, UpdateError.NoConnection, true).hasActionableUpdate())
        assertFalse(UpdateState.Error(UpdateStage.PARSE, UpdateError.InvalidResponse, true).hasActionableUpdate())
        assertFalse(UpdateState.Skipped(release).hasActionableUpdate())
        assertFalse(UpdateState.Deferred(release).hasActionableUpdate())
    }

    @Test
    fun errorTitlesFollowTheOperationStage() {
        assertEquals(UpdateErrorTitle.CHECK, UpdateStage.CHECK.errorTitle())
        assertEquals(UpdateErrorTitle.PARSE, UpdateStage.PARSE.errorTitle())
        assertEquals(UpdateErrorTitle.ASSET_SELECTION, UpdateStage.ASSET_SELECTION.errorTitle())
        assertEquals(UpdateErrorTitle.DOWNLOAD, UpdateStage.DOWNLOAD.errorTitle())
        assertEquals(UpdateErrorTitle.INSTALL, UpdateStage.INSTALL.errorTitle())
    }

    @Test
    fun apiFailuresMapToSpecificRecoverableErrors() {
        assertEquals(UpdateError.NoConnection, UpdateErrorMapper.fromThrowable(java.net.UnknownHostException()))
        assertEquals(UpdateError.Timeout, UpdateErrorMapper.fromThrowable(java.net.SocketTimeoutException()))
        assertEquals(UpdateError.RateLimited, UpdateErrorMapper.fromHttpCode(403))
        assertEquals(UpdateError.NotFound, UpdateErrorMapper.fromHttpCode(404))
        assertEquals(UpdateError.Server, UpdateErrorMapper.fromHttpCode(503))
        assertEquals(UpdateError.InvalidResponse, UpdateErrorMapper.fromThrowable(IllegalArgumentException()))
        assertEquals(UpdateError.InvalidResponse, UpdateErrorMapper.fromJson("{"))
        assertEquals(UpdateError.UnexpectedResponse, (UpdatePolicy.selectAsset(null).exceptionOrNull() as? UpdateException)?.error)
    }

    @Test
    fun localFailuresUseDownloadAndInstallErrorMappings() {
        assertEquals(UpdateError.DownloadFailed, UpdateErrorMapper.fromDownloadThrowable(IOException()))
        assertEquals(UpdateError.DownloadedFileMissing, UpdateErrorMapper.fromDownloadThrowable(FileNotFoundException()))
        assertEquals(UpdateError.InstallFailed, UpdateErrorMapper.fromInstallThrowable(IOException()))
        assertEquals(UpdateError.DownloadedFileMissing, UpdateErrorMapper.fromInstallThrowable(FileNotFoundException()))
    }

    private fun parse(
        tag: String,
        assets: List<String> = listOf("app-release.apk"),
        body: String = "Fix something",
    ): UpdateRelease {
        val result = ReleaseParser.parse(
            json.parseToJsonElement(response(tag, assets, body)).jsonObject,
            "https://example.test/releases/1",
        )
        assertTrue(result is ReleaseParseResult.Success)
        return (result as ReleaseParseResult.Success).release
    }

    private fun response(tag: String, assets: List<String> = listOf("app-release.apk"), body: String = "Fix something"): String {
        val assetJson = assets.joinToString(",") { name ->
            val mime = if (name.endsWith(".apk")) "application/vnd.android.package-archive" else "text/plain"
            asset(name, mime)
        }
        return """
            {
              "tag_name": "$tag",
              "name": "Xtra 2.58.5",
              "body": ${JsonPrimitive(body)},
              "html_url": "https://example.test/releases/1",
              "draft": false,
              "prerelease": false,
              "assets": [$assetJson]
            }
        """.trimIndent()
    }

    private fun asset(name: String, contentType: String = "application/vnd.android.package-archive"): String =
        """{"name":"$name","content_type":"$contentType","browser_download_url":"https://example.test/$name","size":10}"""
}
