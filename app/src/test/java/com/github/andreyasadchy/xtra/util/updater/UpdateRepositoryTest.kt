package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdateRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun completeHistoryPaginatesStopsAtInstalledBuildAndSurvivesRecovery() {
        val preferences = MemoryPreferences()
        val source = HistoryReleaseSource(
            latest = response("v2.58.6-build.300"),
            pages = listOf(
                Result.success(historyPage(*(300 downTo 201).map(::buildTag).toTypedArray())),
                Result.success(
                    historyPage(
                        *((200 downTo 122).map(::buildTag) +
                            listOf("v2.58.6") +
                            (20 downTo 1).map { "v2.58.4-build.$it" }).toTypedArray(),
                    ),
                ),
            ),
        )
        val repository = UpdateRepository(TestContext(preferences), source, null)

        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available

        assertEquals(2, source.historyCalls)
        assertTrue(repository.releaseHistoryComplete.value)
        assertEquals(179, repository.releasesSinceInstalled(available.release).size)
        assertEquals(5, repository.recentReleases().size)

        val persisted = preferences.getString(C.UPDATE_RELEASE_HISTORY, null).orEmpty()
        assertFalse(persisted.contains("rawBody"))
        assertFalse(persisted.contains("browser_download_url"))

        val recovered = UpdateRepository(TestContext(preferences), QueueReleaseSource(emptyList()), null)
        awaitCondition { recovered.releaseHistoryComplete.value }
        assertTrue(recovered.releaseHistoryComplete.value)
        val recoveredNotes = UpdateReleaseHistory.formatGrouped(
            recovered.releasesSinceInstalled(),
            "No notes",
        )
        assertEquals(179, recovered.releasesSinceInstalled().size)
        assertTrue(recoveredNotes.contains("2.58.6 (build 300)"))
        assertTrue(recoveredNotes.contains("2.58.6 (build 122)"))
        assertFalse(recoveredNotes.contains("2.58.6 (build 121)"))
    }

    @Test
    fun partialHistoryDoesNotPresentAFalseCumulativeChangelog() {
        val source = HistoryReleaseSource(
            latest = response("v2.58.6-build.300"),
            pages = listOf(
                Result.success(historyPage(*(300 downTo 201).map(::buildTag).toTypedArray())),
                Result.failure(IOException("history page failed")),
            ),
        )
        val repository = UpdateRepository(TestContext(MemoryPreferences()), source, null)

        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available

        assertEquals(2, source.historyCalls)
        assertFalse(repository.releaseHistoryComplete.value)
        assertTrue(repository.releasesSinceInstalled(available.release).isEmpty())
        assertEquals(listOf("v2.58.6-build.300"), repository.recentReleases(available.release).map { it.id })
    }

    @Test
    fun failedCheckRetryChecksInsteadOfDownloadingThePersistedRelease() {
        val preferences = MemoryPreferences().apply { persistRelease("v2.58.6-build.124") }
        val remoteRelease = response("v2.58.6-build.132")
        val source = QueueReleaseSource(
            listOf(Result.failure(UnknownHostException()), Result.success(remoteRelease)),
        )
        val repository = UpdateRepository(TestContext(preferences), source, null)

        repository.check(null)
        val error = awaitState(repository) { it is UpdateState.Error }
        assertEquals(UpdateStage.CHECK, (error as UpdateState.Error).stage)
        assertEquals(UpdatePrimaryAction.DOWNLOAD, error.primaryAction())
        assertEquals("v2.58.6-build.124", error.downloadableRelease()?.id)

        repository.retry()
        val available = awaitState(repository) { it is UpdateState.Available }
        assertEquals("v2.58.6-build.132", (available as UpdateState.Available).release.id)
        assertEquals(2, source.calls)
        assertEquals("v2.58.6-build.132", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun expectedReleaseVersionCodeSurvivesPersistedCandidateRecovery() {
        val preferences = MemoryPreferences()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.173", expectedVersionCode = 294L)))),
            null,
        )

        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available
        assertEquals(294L, available.release.expectedVersionCode)

        val recovered = UpdateRepository(TestContext(preferences), QueueReleaseSource(emptyList()), null)
        val recoveredAvailable = awaitState(recovered) { it is UpdateState.Available } as UpdateState.Available
        assertEquals(294L, recoveredAvailable.release.expectedVersionCode)
    }

    @Test
    fun selectedAbiSha256SurvivesPersistedReleaseRecovery() {
        val preferences = MemoryPreferences()
        val universalSha = "a".repeat(64)
        val arm64Sha = "b".repeat(64)
        val arm64Asset = UpdateAsset(
            name = "app-arm64-v8a-release.apk",
            contentType = UpdateRepository.APK_MIME_TYPE,
            downloadUrl = "https://example.test/app-arm64-v8a-release.apk",
            size = 10L,
        )
        val release = UpdateRelease(
            tagName = "v2.58.6-build.999",
            versionName = "2.58.6",
            // Recovery clears candidates that are not newer than the installed build. Keep this
            // fixture newer on both local and CI version-code configurations.
            buildNumber = 999L,
            title = "Xtra 2.58.6",
            releaseNotes = emptyList(),
            rawBody = "",
            releaseUrl = "https://example.test/releases/1",
            publishedAt = null,
            assets = listOf(
                UpdateAsset("app-release.apk", UpdateRepository.APK_MIME_TYPE, "https://example.test/app-release.apk", 10L),
                arm64Asset,
            ),
            prerelease = false,
            draft = false,
            expectedVersionCode = 294L,
            expectedSha256 = universalSha,
            artifactSha256 = mapOf(arm64Asset.name to arm64Sha),
        )
        val repository = UpdateRepository(TestContext(preferences), QueueReleaseSource(emptyList()), null)
        runBlocking { repository.awaitReady() }
        val replace = UpdateRepository::class.java.getDeclaredMethod(
            "replacePersistedRelease",
            UpdateRelease::class.java,
            UpdateAsset::class.java,
        ).apply { isAccessible = true }
        replace.invoke(repository, release, arm64Asset)

        assertEquals(arm64Sha, preferences.getString(C.UPDATE_AVAILABLE_EXPECTED_SHA256, null))

        val recovered = UpdateRepository(TestContext(preferences), QueueReleaseSource(emptyList()), null)
        runBlocking { recovered.awaitReady() }
        val load = UpdateRepository::class.java.getDeclaredMethod("loadPersistedRelease").apply { isAccessible = true }
        val recoveredRelease = load.invoke(recovered) as UpdateRelease
        assertEquals(arm64Sha, recoveredRelease.expectedSha256)
        assertEquals(arm64Sha, recoveredRelease.expectedSha256For(recoveredRelease.assets.single()))
    }

    @Test
    fun checkAndDownloadUseTheSelectedAbiSha256() {
        val universalSha = "a".repeat(64)
        val arm64Sha = "b".repeat(64)
        val downloads = MemoryDownloadStore()
        val repository = UpdateRepository(
            TestContext(MemoryPreferences()),
            QueueReleaseSource(listOf(Result.success(responseWithArtifact("v2.58.7", arm64Sha)))),
            downloads,
            supportedAbis = { listOf("arm64-v8a") },
        )

        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available
        assertEquals(arm64Sha, available.release.expectedSha256)
        assertTrue(available.release.expectedSha256 != universalSha)

        repository.download(available.release)
        val downloading = awaitState(repository) { it is UpdateState.Downloading } as UpdateState.Downloading
        assertEquals(arm64Sha, downloading.release.expectedSha256)
        assertEquals(arm64Sha, downloads.enqueuedRelease?.expectedSha256)
    }

    @Test
    fun assetSelectionFailurePreservesAnOlderDownloadAction() {
        val preferences = MemoryPreferences().apply { persistRelease("v2.58.6-build.124") }
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132", includeApk = false))))
        val repository = UpdateRepository(TestContext(preferences), source, null)

        repository.check(null)
        val error = awaitState(repository) { it is UpdateState.Error } as UpdateState.Error

        assertEquals(UpdateStage.ASSET_SELECTION, error.stage)
        assertEquals(UpdateError.MissingApk, error.cause)
        assertEquals(UpdatePrimaryAction.DOWNLOAD, error.primaryAction())
        assertEquals("v2.58.6-build.124", error.downloadableRelease()?.id)
        assertTrue(error.hasActionableUpdate())
        assertEquals("v2.58.6-build.124", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun downloadedReleaseSurvivesFailedRefresh() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val source = QueueReleaseSource(listOf(Result.failure(UnknownHostException())))
        val repository = UpdateRepository(TestContext(preferences), source, downloadStore = downloads)

        awaitState(repository) { it is UpdateState.Downloaded }
        repository.check(null)
        val error = awaitState(repository) { it is UpdateState.Error } as UpdateState.Error

        assertEquals(UpdateStage.CHECK, error.stage)
        assertEquals("v2.58.6-build.125", error.release?.id)
        assertEquals(UpdatePrimaryAction.INSTALL, error.primaryAction())
        assertTrue(error.hasActionableUpdate())
        assertNotNull(error.artifact)
        assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        assertTrue(downloads.records.containsKey(125L))
    }

    @Test
    fun downloadedReleaseSurvivesNewerReleaseWithMissingApk() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.126", includeApk = false))))
        val repository = UpdateRepository(TestContext(preferences), source, downloadStore = downloads)

        awaitState(repository) { it is UpdateState.Downloaded }
        repository.check(null)
        val error = awaitState(repository) { it is UpdateState.Error } as UpdateState.Error

        assertEquals(UpdateStage.ASSET_SELECTION, error.stage)
        assertEquals(UpdateError.MissingApk, error.cause)
        assertEquals("v2.58.6-build.125", error.release?.id)
        assertEquals(UpdatePrimaryAction.INSTALL, error.primaryAction())
        assertTrue(downloads.records.containsKey(125L))
        assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun validNewerReleaseReplacesDownloadedOlderReleaseAfterAssetValidation() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.126"))))
        val repository = UpdateRepository(TestContext(preferences), source, downloadStore = downloads)

        awaitState(repository) { it is UpdateState.Downloaded }
        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available

        assertEquals("v2.58.6-build.126", available.release.id)
        assertFalse(downloads.records.containsKey(125L))
        assertEquals(listOf(125L), downloads.removed)
        assertEquals("v2.58.6-build.126", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        assertNull(preferences.getString(C.UPDATE_DOWNLOAD_FILE, null))
    }

    @Test
    fun staleReleaseDownloadActionIsRejectedAfterARefresh() {
        val preferences = MemoryPreferences().apply { persistRelease("v2.58.6-build.125") }
        val downloads = MemoryDownloadStore()
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.126"))))
        val repository = UpdateRepository(
            TestContext(preferences),
            source,
            downloadStore = downloads,
        )

        val oldRelease = (awaitState(repository) { it is UpdateState.Available } as UpdateState.Available).release
        repository.check(null)
        awaitState(repository) { it is UpdateState.Available && it.release.id == "v2.58.6-build.126" }

        repository.download(oldRelease)
        Thread.sleep(100)

        assertEquals("v2.58.6-build.126", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        assertTrue(downloads.records.isEmpty())
        assertTrue(downloads.removed.isEmpty())
    }

    @Test
    fun staleSkipAndDeferActionsCannotChangeTheRefreshedCandidate() {
        val preferences = MemoryPreferences().apply { persistRelease("v2.58.6-build.125") }
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.126"))))
        val repository = UpdateRepository(TestContext(preferences), source, null)

        val oldRelease = (awaitState(repository) { it is UpdateState.Available } as UpdateState.Available).release
        repository.check(null)
        awaitState(repository) { it is UpdateState.Available && it.release.id == "v2.58.6-build.126" }

        repository.skip(oldRelease)
        repository.defer(oldRelease)
        Thread.sleep(100)

        assertNull(preferences.getString(C.UPDATE_IGNORED_VERSION, null))
        assertNull(preferences.getString(C.UPDATE_NOT_NOW_VERSION, null))
        assertEquals("v2.58.6-build.126", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        assertTrue(repository.state.value is UpdateState.Available)
    }

    @Test
    fun availableReleaseSurvivesFailedRefreshAndRemainsDownloadable() {
        val preferences = MemoryPreferences().apply { persistRelease("v2.58.6-build.125") }
        val source = QueueReleaseSource(listOf(Result.failure(UnknownHostException())))
        val repository = UpdateRepository(TestContext(preferences), source)

        awaitState(repository) { it is UpdateState.Available }
        repository.check(null)
        val error = awaitState(repository) { it is UpdateState.Error } as UpdateState.Error

        assertEquals(UpdateStage.CHECK, error.stage)
        assertEquals(UpdatePrimaryAction.DOWNLOAD, error.primaryAction())
        assertEquals("v2.58.6-build.125", error.downloadableRelease()?.id)
        assertTrue(error.hasActionableUpdate())
        assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun plainCheckFailureWithoutKnownUpdateIsNotActionable() {
        val repository = UpdateRepository(
            TestContext(MemoryPreferences()),
            QueueReleaseSource(listOf(Result.failure(UnknownHostException()))),
            null,
        )

        repository.check(null)
        val error = awaitState(repository) { it is UpdateState.Error } as UpdateState.Error

        assertFalse(error.hasActionableUpdate())
    }

    @Test
    fun currentReleaseWithMissingApkReportsUpToDate() {
        val repository = UpdateRepository(
            TestContext(MemoryPreferences()),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6", includeApk = false)))),
            null,
        )

        repository.check(null)
        val state = awaitState(repository) { it is UpdateState.UpToDate }

        assertTrue(state is UpdateState.UpToDate)
    }

    @Test
    fun manualCheckOfSkippedReleaseIsDirectlyDownloadable() {
        val releaseId = "v2.58.6-build.132"
        val preferences = MemoryPreferences().apply {
            edit { putString(C.UPDATE_IGNORED_VERSION, releaseId) }
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response(releaseId)))),
            null,
        )

        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available

        assertEquals(releaseId, available.release.id)
        assertEquals(true, available.previouslySkipped)
        assertEquals(releaseId, preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun notNowIsDeferredAutomaticallyButDirectlyDownloadableAfterManualCheck() {
        val releaseId = "v2.58.6-build.132"
        val preferences = MemoryPreferences()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response(releaseId)), Result.success(response(releaseId)))),
            null,
        )

        repository.check(null)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available
        preferences.edit { putString(C.UPDATE_NOTIFIED_VERSION, releaseId) }
        repository.defer(available.release)

        val deferred = awaitState(repository) { it is UpdateState.Deferred } as UpdateState.Deferred
        assertEquals(available.release.id, deferred.release.id)
        assertNull(preferences.getString(C.UPDATE_NOTIFIED_VERSION, null))

        repository.check(null)
        val manuallyAvailable = awaitState(repository) {
            it is UpdateState.Available && it.release.id == releaseId
        } as UpdateState.Available
        assertTrue(manuallyAvailable.previouslyDeferred)
    }

    @Test
    fun automaticCheckFailureWhileDeferredStaysQuiet() {
        val releaseId = "v2.58.6-build.132"
        val preferences = MemoryPreferences().apply {
            persistRelease(releaseId)
            edit {
                putString(C.UPDATE_NOT_NOW_VERSION, releaseId)
                putLong(C.UPDATE_NOT_NOW_UNTIL, System.currentTimeMillis() + 60_000L)
            }
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.failure(UnknownHostException()))),
            null,
        )
        awaitState(repository) { it is UpdateState.Deferred }

        repository.check(null, automatic = true)

        assertTrue(awaitState(repository) { it is UpdateState.Deferred } is UpdateState.Deferred)
        assertEquals(0L, repository.lastSuccessfulCheck)
    }

    @Test
    fun manualCheckDoesNotEmitAnAutomaticPromptEvent() = runBlocking {
        val repository = UpdateRepository(
            TestContext(MemoryPreferences()),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132")))),
            null,
        )
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null)
        awaitState(repository) { it is UpdateState.Available }
        delay(100)
        collector.cancel()

        assertTrue(events.isEmpty())
    }

    @Test
    fun manualCheckDoesNotPersistAnAutomaticPrompt() {
        val preferences = MemoryPreferences()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132")))),
            null,
        )

        repository.check(null)
        awaitState(repository) { it is UpdateState.Available }

        assertNull(preferences.getString(C.UPDATE_AUTOMATIC_PROMPT_VERSION, null))
    }

    @Test
    fun automaticCheckPersistsAnAutomaticPrompt() {
        val releaseId = "v2.58.6-build.132"
        val preferences = MemoryPreferences()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response(releaseId)))),
            null,
        )

        repository.check(null, automatic = true)
        awaitState(repository) { it is UpdateState.Available }

        assertEquals(releaseId, preferences.getString(C.UPDATE_AUTOMATIC_PROMPT_VERSION, null))
    }

    @Test
    fun updateNotificationSuppressionExpiresAfterTheNotNowWindow() {
        val now = System.currentTimeMillis()
        val preferences = MemoryPreferences().apply {
            edit {
                putString(C.UPDATE_NOTIFIED_VERSION, "v2.58.6-build.132")
                putLong(C.UPDATE_NOTIFICATION_SUPPRESSED_UNTIL, now + 24L * 60L * 60L * 1_000L)
            }
        }

        assertTrue(isUpdateNotificationSuppressed(preferences, "v2.58.6-build.132", now))
        assertFalse(isUpdateNotificationSuppressed(preferences, "v2.58.6-build.132", now + 24L * 60L * 60L * 1_000L + 1L))
    }

    @Test
    fun automaticCheckEmitsOnlyTheOneShotPromptEventForANewCandidate() = runBlocking {
        val repository = UpdateRepository(
            TestContext(MemoryPreferences()),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132")))),
            null,
        )
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null, automatic = true)
        awaitState(repository) { it is UpdateState.Available }
        delay(100)
        collector.cancel()

        assertEquals(listOf("v2.58.6-build.132"), events)
    }

    @Test
    fun automaticCheckPromptsAgainAfterDeferredReleaseExpires() = runBlocking {
        val releaseId = "v2.58.6-build.132"
        val preferences = MemoryPreferences()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(
                listOf(
                    Result.success(response(releaseId)),
                    Result.success(response(releaseId)),
                ),
            ),
            null,
        )
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null, automatic = true)
        val available = awaitState(repository) { it is UpdateState.Available } as UpdateState.Available
        awaitCondition { events.size == 1 }

        repository.defer(available.release)
        awaitState(repository) { it is UpdateState.Deferred }
        preferences.edit { putLong(C.UPDATE_NOT_NOW_UNTIL, System.currentTimeMillis() - 1L) }

        repository.check(null, automatic = true)
        awaitState(repository) { it is UpdateState.Available && it.release.id == releaseId }
        awaitCondition { events.size == 2 }
        collector.cancel()

        assertEquals(listOf(releaseId, releaseId), events)
    }

    @Test
    fun automaticCheckWithDownloadedCurrentReleaseDoesNotEmitPrompt() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.125")))),
            downloadStore = downloads,
        )
        awaitState(repository) { it is UpdateState.Downloaded }
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null, automatic = true)
        awaitState(repository) { it is UpdateState.Downloaded }
        delay(100)
        collector.cancel()

        assertTrue(events.isEmpty())
    }

    @Test
    fun automaticCheckWithDownloadedReleaseDiscoversNewerReleaseAndEmitsOnePrompt() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.126")))),
            downloadStore = downloads,
        )
        awaitState(repository) { it is UpdateState.Downloaded }
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null, automatic = true)
        awaitState(repository) { it is UpdateState.Available && it.release.id == "v2.58.6-build.126" }
        delay(100)
        collector.cancel()

        assertEquals(listOf("v2.58.6-build.126"), events)
        assertTrue(downloads.removed.contains(125L))
    }

    @Test
    fun explicitDownloadClearsSuppressionAndSurvivesSubsequentAutomaticChecks() = runBlocking {
        val releaseId = "v2.58.6-build.125"
        val preferences = MemoryPreferences().apply {
            persistRelease(releaseId)
            edit {
                putString(C.UPDATE_IGNORED_VERSION, releaseId)
                putString(C.UPDATE_NOT_NOW_VERSION, releaseId)
                putLong(C.UPDATE_NOT_NOW_UNTIL, System.currentTimeMillis() + 60_000L)
            }
        }
        val downloads = MemoryDownloadStore()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(
                listOf(
                    Result.success(response(releaseId)),
                    Result.success(response(releaseId)),
                ),
            ),
            downloadStore = downloads,
        )
        awaitState(repository) { it is UpdateState.Skipped }

        repository.check(null)
        val available = awaitState(repository) {
            it is UpdateState.Available && it.previouslySkipped
        } as UpdateState.Available
        repository.download(available.release)
        awaitState(repository) { it is UpdateState.Downloading }

        assertNull(preferences.getString(C.UPDATE_IGNORED_VERSION, null))
        assertNull(preferences.getString(C.UPDATE_NOT_NOW_VERSION, null))

        val downloadId = preferences.getLong(C.UPDATE_DOWNLOAD_ID, -1L)
        downloads.records[downloadId] = successfulRecord()
        repository.handleDownloadComplete(downloadId)
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.check(null, automatic = true)
        awaitCheck(repository)
        assertTrue(repository.state.value is UpdateState.Downloaded)
        assertEquals(releaseId, (repository.state.value as UpdateState.Downloaded).release.id)
    }

    @Test
    fun automaticCheckWithFailedPersistedDownloadDoesNotEmitDiscoveryPromptForErrorState() = runBlocking {
        val preferences = MemoryPreferences().apply {
            persistDownloadedRelease("v2.58.6-build.125", 125L)
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.125")))),
            downloadStore = ThrowingDownloadStore(),
        )
        awaitState(repository) { it is UpdateState.Error }
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null, automatic = true)
        awaitState(repository) { it is UpdateState.Error && it.stage == UpdateStage.DOWNLOAD }
        delay(100)
        collector.cancel()

        assertTrue(repository.state.value is UpdateState.Error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun downloadCompletionEntryPointReturnsAfterStateIsDurable() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = UpdateDownloadRecord(
                status = DownloadManager.STATUS_RUNNING,
                downloadedBytes = 5L,
                totalBytes = 10L,
                uri = null,
            )
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
        )
        awaitState(repository) { it is UpdateState.Downloading }

        downloads.records[125L] = successfulRecord()
        repository.handleDownloadComplete(125L)

        assertTrue(repository.state.value is UpdateState.Downloaded)
    }

    @Test
    fun checkWinsAndAnInstallCannotStartAfterItClaimsTheCheckOperation() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val source = BlockingReleaseSource(response("v2.58.6-build.126"))
        val preparer = RecordingInstallPreparer(preferences)
        val repository = UpdateRepository(
            TestContext(preferences),
            source,
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.check(null)
        source.started.await()
        assertTrue(repository.state.value is UpdateState.Checking)

        repository.install()
        source.release.complete(Unit)
        awaitCheck(repository)
        awaitState(repository) { it is UpdateState.Available && it.release.id == "v2.58.6-build.126" }
        delay(100)

        assertEquals(0, preparer.prepareCalls)
        assertEquals("v2.58.6-build.126", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun installWinsAndCheckLeavesItsReleaseAuthoritativeUntilInstallTerminates() = runBlocking {
        val preferences = MemoryPreferences().apply {
            persistDownloadedRelease("v2.58.6-build.125", 125L)
        }
        val downloads = MemoryDownloadStore().apply { records[125L] = successfulRecord() }
        val preparer = BlockingCommitInstallPreparer()
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.126"))))
        val repository = UpdateRepository(
            TestContext(preferences),
            source,
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.install()
        assertTrue(preparer.commitStarted.await(1, TimeUnit.SECONDS))
        assertTrue(repository.state.value is UpdateState.Installing)

        try {
            repository.check(null)
            delay(100)

            assertTrue(repository.state.value is UpdateState.Installing)
            assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
            assertEquals(0, source.calls)
        } finally {
            preparer.releaseCommit.countDown()
        }

        awaitCondition { preparer.commitReturned }
        delay(100)
        assertTrue(repository.state.value is UpdateState.Installing)
        assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        assertEquals(0, source.calls)
    }

    @Test
    fun failedInFlightCheckCannotOverwriteInstallingState() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val source = BlockingReleaseSource(response("v2.58.6-build.126", includeApk = false))
        val preparer = BlockingCommitInstallPreparer()
        val repository = UpdateRepository(
            TestContext(preferences),
            source,
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.check(null)
        source.started.await()
        repository.handleDownloadComplete(125L)
        assertTrue(repository.state.value is UpdateState.Downloaded)

        repository.install()
        assertTrue(preparer.commitStarted.await(1, TimeUnit.SECONDS))
        assertTrue(repository.state.value is UpdateState.Installing)

        try {
            source.release.complete(Unit)
            source.returned.await()
            delay(100)

            assertTrue(repository.state.value is UpdateState.Installing)
            assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        } finally {
            preparer.releaseCommit.countDown()
        }

        awaitCheck(repository)
        assertTrue(repository.state.value is UpdateState.Installing)
        assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
    }

    @Test
    fun cancellationWinsOverAnInFlightDownloadReconciliation() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = BlockingQueryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        downloads.records[125L] = runningRecord()
        downloads.blockNextQuery()
        val completion = launch(Dispatchers.Default) {
            repository.handleDownloadComplete(125L)
        }
        assertTrue(downloads.queryStarted.await(1, TimeUnit.SECONDS))

        repository.cancelDownload()
        delay(100)
        assertTrue(downloads.removed.isEmpty())

        downloads.releaseQuery.countDown()
        completion.join()
        awaitCondition { downloads.removed.contains(125L) }
        awaitState(repository) { it is UpdateState.Available }
        delay(100)

        assertTrue(repository.state.value is UpdateState.Available)
        assertFalse(repository.state.value is UpdateState.Downloading)
    }

    @Test
    fun staleInstallCallbackDoesNotConsumeTheCurrentUpdate() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        setPrivateField(repository, "activeInstallSessionId", 42)
        setPrivateField(repository, "activeInstallReleaseId", "v2.58.6-build.125")
        repository.handleInstallResult(
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,
            callbackReleaseId = "v2.58.4-build.124",
            callbackSessionId = 42,
        )

        assertTrue(repository.state.value is UpdateState.Downloaded)
        assertTrue(downloads.records.containsKey(125L))
    }

    @Test
    fun installSessionOwnershipIsPersistedBeforeCommit() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val preparer = RecordingInstallPreparer(preferences)
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.install()
        awaitState(repository) { preparer.commitSawSessionId != null }

        assertEquals(42, preparer.commitSawSessionId)
        assertEquals("v2.58.6-build.125", preparer.commitSawReleaseId)
        assertTrue(preparer.commitSawCommitStarted)
        assertEquals(42, preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1))
        assertEquals("v2.58.6-build.125", preferences.getString(C.UPDATE_INSTALL_RELEASE_ID, null))
    }

    @Test
    fun commitFailureRestoresTheDownloadedStateAndClearsSessionOwnership() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val preparer = RecordingInstallPreparer(preferences, failCommit = true)
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.install()
        awaitState(repository) { it is UpdateState.Downloaded && preparer.abandoned }

        assertEquals(-1, preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1))
        assertNull(preferences.getString(C.UPDATE_INSTALL_RELEASE_ID, null))
    }

    @Test
    fun prepareFailureDoesNotLeaveInstallOwnershipBehind() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val preparer = RecordingInstallPreparer(preferences, failPrepare = true)
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.install()
        val error = awaitState(repository) { it is UpdateState.Error } as UpdateState.Error

        assertEquals(UpdateStage.INSTALL, error.stage)
        assertEquals(UpdateError.InstallFailed, error.cause)
        assertEquals(-1, preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1))
        assertNull(preferences.getString(C.UPDATE_INSTALL_RELEASE_ID, null))
        assertTrue(downloads.records.containsKey(125L))
    }

    @Test
    fun missingDownloadedArtifactBecomesRetryableDownloadAndRetryQueuesFreshDownload() = runBlocking {
        val preferences = MemoryPreferences().apply {
            persistDownloadedRelease("v2.58.6-build.125", 125L)
        }
        val downloads = MemoryDownloadStore().apply { records[125L] = successfulRecord() }
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installPreparer = MissingArtifactInstallPreparer(),
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.install()
        val error = awaitState(repository) {
            it is UpdateState.Error && it.cause == UpdateError.DownloadedFileMissing
        } as UpdateState.Error

        assertEquals(UpdateStage.DOWNLOAD, error.stage)
        assertTrue(error.retryable)
        assertEquals("v2.58.6-build.125", error.release?.id)
        assertNull(error.artifact)
        assertEquals(UpdateRetryAction.DOWNLOAD, error.retryAction())
        assertTrue(downloads.removed.contains(125L))
        assertEquals(-1L, preferences.getLong(C.UPDATE_DOWNLOAD_ID, -1L))

        repository.retry()
        awaitState(repository) { it is UpdateState.Downloading }
        assertEquals(1, downloads.enqueued.size)
        assertTrue(downloads.records.containsKey(downloads.enqueued.single()))
    }

    @Test
    fun repeatedInstallTapsPrepareOnlyOneSession() {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val preparer = RecordingInstallPreparer(preferences)
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }

        repository.install()
        awaitState(repository) { it is UpdateState.Installing && it.sessionId == 42 }
        repository.install()
        Thread.sleep(100)

        assertEquals(1, preparer.prepareCalls)
    }

    @Test
    fun processDeathAfterCommitKeepsTheOwnedSessionUntilItsCallback() = runBlocking {
        val preferences = MemoryPreferences()
        val downloads = MemoryDownloadStore().apply {
            preferences.persistDownloadedRelease("v2.58.6-build.125", 125L)
            records[125L] = successfulRecord()
        }
        val preparer = RecordingInstallPreparer(preferences)
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installPreparer = preparer,
        )
        awaitState(repository) { it is UpdateState.Downloaded }
        repository.install()
        awaitState(repository) {
            it is UpdateState.Installing && it.sessionId == 42 && preparer.commitSawSessionId != null
        }
        assertTrue(preferences.getBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, false))

        val sessions = RecordingInstallSessionStore(
            UpdateInstallSessionSnapshot(
                committed = false,
                sealed = false,
                terminal = false,
                commitStateKnown = false,
            ),
            recommitResult = true,
        )
        val recovered = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installSessionStore = sessions,
        )
        awaitState(recovered) { it is UpdateState.Installing && it.sessionId == 42 }
        assertEquals(1, sessions.recommitCalls)

        recovered.handleInstallResult(
            android.content.pm.PackageInstaller.STATUS_SUCCESS,
            callbackReleaseId = "v2.58.6-build.125",
            callbackSessionId = 42,
        )

        assertTrue(recovered.state.value is UpdateState.Idle)
        assertTrue(sessions.abandoned.isEmpty())
    }

    @Test
    fun legacyPlatformRecoveryRecommitsWhenCommitWasPersistedBeforeSessionCommit() {
        val preferences = MemoryPreferences().apply {
            persistRelease("v2.58.6-build.125")
            edit {
                putInt(C.UPDATE_INSTALL_SESSION_ID, 42)
                putString(C.UPDATE_INSTALL_RELEASE_ID, "v2.58.6-build.125")
                putBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, true)
            }
        }
        val sessions = RecordingInstallSessionStore(
            UpdateInstallSessionSnapshot(
                committed = false,
                sealed = false,
                terminal = false,
                commitStateKnown = false,
            ),
            recommitResult = true,
        )
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            null,
            installSessionStore = sessions,
        )

        awaitState(repository) { it is UpdateState.Installing && it.sessionId == 42 }

        assertEquals(1, sessions.recommitCalls)
        assertTrue(sessions.abandoned.isEmpty())
    }

    @Test
    fun legacyPlatformRecoveryFallsBackToTheDownloadedApkWhenRecommitFails() {
        val preferences = MemoryPreferences().apply {
            persistDownloadedRelease("v2.58.6-build.125", 125L)
            putInstallOwnership(sessionId = 42, releaseId = "v2.58.6-build.125", commitStarted = true)
        }
        val downloads = MemoryDownloadStore().apply { records[125L] = successfulRecord() }
        val sessions = RecordingInstallSessionStore(
            UpdateInstallSessionSnapshot(
                committed = false,
                sealed = false,
                terminal = false,
                commitStateKnown = false,
            ),
            recommitResult = false,
        )

        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installSessionStore = sessions,
        )

        awaitState(repository) { it is UpdateState.Downloaded }

        assertEquals(1, sessions.recommitCalls)
        assertEquals(listOf(42), sessions.abandoned)
        assertTrue(repository.state.value is UpdateState.Downloaded)
    }

    @Test
    fun resetAbandonsAnActiveInstallSessionBeforeClearingOwnership() {
        val preferences = MemoryPreferences().apply {
            persistRelease("v2.58.6-build.125")
            edit {
                putInt(C.UPDATE_INSTALL_SESSION_ID, 42)
                putString(C.UPDATE_INSTALL_RELEASE_ID, "v2.58.6-build.125")
            }
        }
        val sessions = RecordingInstallSessionStore(
            UpdateInstallSessionSnapshot(committed = true, sealed = true, terminal = false),
        )
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            null,
            installSessionStore = sessions,
        )
        awaitState(repository) { it is UpdateState.Installing }

        repository.reset()
        awaitState(repository) { it is UpdateState.Idle }

        assertEquals(listOf(42), sessions.abandoned)
        assertEquals(-1, preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1))
        assertNull(preferences.getString(C.UPDATE_INSTALL_RELEASE_ID, null))
    }

    @Test
    fun resetSerializesAnInstallFailureCallbackAndEndsIdle() = runBlocking {
        val preferences = MemoryPreferences().apply {
            persistDownloadedRelease("v2.58.6-build.125", 125L)
            putInstallOwnership(sessionId = 42, releaseId = "v2.58.6-build.125", commitStarted = true)
        }
        val downloads = BlockingQueryDownloadStore().apply {
            records[125L] = successfulRecord()
        }
        val sessions = RecordingInstallSessionStore(
            UpdateInstallSessionSnapshot(committed = true, sealed = true, terminal = false),
        )
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            downloadStore = downloads,
            installSessionStore = sessions,
        )
        awaitState(repository) { it is UpdateState.Installing }

        downloads.blockNextQuery()
        val callback = launch(Dispatchers.Default) {
            repository.handleInstallResult(
                android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,
                callbackReleaseId = "v2.58.6-build.125",
                callbackSessionId = 42,
            )
        }
        assertTrue(downloads.queryStarted.await(1, TimeUnit.SECONDS))

        repository.reset()
        delay(100)
        assertNotNull(preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))

        downloads.releaseQuery.countDown()
        callback.join()
        awaitState(repository) { it === UpdateState.Idle }
        assertTrue(repository.state.value === UpdateState.Idle)
    }

    @Test
    fun pendingInstallCanBeExplicitlyContinuedAfterAutomaticResume() = runBlocking {
        val releaseId = "v2.58.6-build.125"
        val preferences = MemoryPreferences().apply {
            persistRelease(releaseId)
            putInstallOwnership(sessionId = 42, releaseId = releaseId, commitStarted = true)
            edit {
                putString(C.UPDATE_INSTALL_PENDING_INTENT, "pending")
            }
        }
        var foreground = true
        val launches = mutableListOf<Intent>()
        val repository = UpdateRepository(
            TestContext(preferences),
            QueueReleaseSource(emptyList()),
            null,
            installSessionStore = RecordingInstallSessionStore(
                UpdateInstallSessionSnapshot(committed = true, sealed = true, terminal = false),
            ),
            foregroundChecker = { foreground },
            pendingInstallStarter = { launches += it },
        )

        val installing = awaitState(repository) { it is UpdateState.Installing } as UpdateState.Installing
        setPrivateField(repository, "pendingInstallIntent", Intent("com.example.UPDATE_PENDING"))
        val stateField = UpdateRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (stateField.get(repository) as MutableStateFlow<UpdateState>).value =
            UpdateState.AwaitingUserAction(installing.release, installing.artifact, 42)
        repository.resumePendingInstall()
        awaitCondition { launches.size == 1 }

        foreground = false
        foreground = true
        repository.resumePendingInstall()
        delay(100)
        assertEquals(1, launches.size)

        repository.launchPendingInstall()
        awaitCondition { launches.size == 2 }

        repository.resumePendingInstall()
        delay(100)
        assertEquals(2, launches.size)
    }

    @Test
    fun automaticChecksRespectTheSettingsPreferenceStore() {
        val tokenPreferences = MemoryPreferences()
        val settingsPreferences = MemoryPreferences().apply {
            edit { putBoolean(C.UPDATE_CHECK_ENABLED, false) }
        }
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132"))))
        val repository = UpdateRepository(TestContext(tokenPreferences, settingsPreferences), source, null)

        repository.checkIfDue(null)
        Thread.sleep(100)
        assertEquals(0, source.calls)

        settingsPreferences.edit { putBoolean(C.UPDATE_CHECK_ENABLED, true) }
        repository.checkIfDue(null)
        awaitState(repository) { it is UpdateState.Available }
        assertEquals(1, source.calls)
    }

    @Test
    fun automaticChecksUseTheConfiguredFrequency() {
        val tokenPreferences = MemoryPreferences().apply {
            edit {
                putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis() - 7L * 60L * 60L * 1_000L)
            }
        }
        val settingsPreferences = MemoryPreferences().apply {
            edit {
                putString(C.UPDATE_CHECK_FREQUENCY, UpdateCheckFrequency.EVERY_6_HOURS.preferenceValue)
            }
        }
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132"))))
        val repository = UpdateRepository(TestContext(tokenPreferences, settingsPreferences), source, null)

        repository.checkIfDue(null)

        awaitState(repository) { it is UpdateState.Available }
        assertEquals(1, source.calls)
    }

    @Test
    fun workerOwnedCheckIsCancelledWithTheCallingCoroutine() = runBlocking {
        val preferences = MemoryPreferences()
        val source = CancellableBlockingReleaseSource(response("v2.58.6-build.132"))
        val repository = UpdateRepository(TestContext(preferences), source, null)

        val workerCheck = launch {
            repository.checkIfDueAndWait(null, force = true)
        }
        source.started.await()

        workerCheck.cancel()
        workerCheck.join()
        val sourceWasCancelled = withTimeoutOrNull(200) {
            source.cancelled.await()
            true
        } ?: false
        source.release.complete(Unit)
        awaitCheck(repository)

        assertTrue(sourceWasCancelled)
        assertTrue(repository.state.value === UpdateState.Idle)
        assertFalse(repository.state.value is UpdateState.Available)
        assertNull(preferences.getString(C.UPDATE_AUTOMATIC_PROMPT_VERSION, null))
    }

    @Test
    fun resetCancelsWorkerOwnedCheck() = runBlocking {
        val preferences = MemoryPreferences()
        val source = CancellableBlockingReleaseSource(response("v2.58.6-build.132"))
        val repository = UpdateRepository(TestContext(preferences), source, null)

        launch {
            repository.checkIfDueAndWait(null, force = true)
        }
        source.started.await()

        repository.reset()
        val sourceWasCancelled = withTimeoutOrNull(200) {
            source.cancelled.await()
            true
        } ?: false
        awaitState(repository) { it === UpdateState.Idle }

        assertTrue(sourceWasCancelled)
        assertNull(preferences.getString(C.UPDATE_AUTOMATIC_PROMPT_VERSION, null))
    }

    @Test
    fun resetCancelsInFlightCheckAndLeavesNoPreResetStateOrPrompt() = runBlocking {
        val preferences = MemoryPreferences()
        val source = BlockingReleaseSource(response("v2.58.6-build.132"))
        val repository = UpdateRepository(TestContext(preferences), source, null)
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            repository.automaticPromptEvents.collect { events += it.id }
        }
        delay(50)

        repository.check(null, automatic = true)
        source.started.await()
        assertTrue(repository.state.value is UpdateState.Checking)

        repository.reset()
        source.release.complete(Unit)
        awaitState(repository) { it === UpdateState.Idle }
        delay(100)
        collector.cancel()

        assertNull(preferences.getString(C.UPDATE_AVAILABLE_VERSION, null))
        assertNull(preferences.getString(C.UPDATE_DOWNLOAD_FILE, null))
        assertEquals(-1, preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1))
        assertTrue(events.isEmpty())
    }

    @Test
    fun resetCancelsAQueuedCheckWithoutLeakingTheCheckLock() {
        val dispatcher = QueuedDispatcher()
        val source = QueueReleaseSource(listOf(Result.success(response("v2.58.6-build.132"))))
        val repository = UpdateRepository(
            TestContext(MemoryPreferences()),
            source,
            null,
            coroutineDispatcher = dispatcher,
        )

        // Finish startup, then queue check before reset. Run reset first so the check coroutine
        // is cancelled before its body gets a chance to acquire checkLock.
        dispatcher.runNext()
        repository.check(null)
        repository.reset()
        dispatcher.runLast()
        dispatcher.runAll()

        assertTrue(repository.state.value === UpdateState.Idle)
        assertEquals(0, source.calls)

        repository.check(null)
        dispatcher.runAll()

        assertTrue(repository.state.value is UpdateState.Available)
        assertEquals(1, source.calls)
    }

    private fun awaitState(repository: UpdateRepository, predicate: (UpdateState) -> Boolean): UpdateState {
        repeat(200) {
            val state = repository.state.value
            if (predicate(state)) return state
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for updater state; got ${repository.state.value}")
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    private suspend fun awaitCheck(repository: UpdateRepository) {
        val field = UpdateRepository::class.java.getDeclaredField("checkJob").apply { isAccessible = true }
        (field.get(repository) as? kotlinx.coroutines.Job)?.join()
    }

    private fun response(tag: String, includeApk: Boolean = true, expectedVersionCode: Long? = null): JsonObject {
        val assets = if (includeApk) {
            "[{\"name\":\"app-release.apk\",\"content_type\":\"application/vnd.android.package-archive\",\"browser_download_url\":\"https://example.test/app-release.apk\",\"size\":10}]"
        } else {
            "[{\"name\":\"notes.txt\",\"content_type\":\"text/plain\",\"browser_download_url\":\"https://example.test/notes.txt\",\"size\":10}]"
        }
        val metadata = expectedVersionCode?.let { ",\n              \"xtra_release_metadata\": {\"versionCode\": $it}" }.orEmpty()
        return json.parseToJsonElement(
            """
            {
              "tag_name": "$tag",
              "name": "Xtra $tag",
              "body": "Fix something",
              "html_url": "https://example.test/releases/1",
              "draft": false,
              "prerelease": false,
              "assets": $assets$metadata
            }
            """.trimIndent()
        ).jsonObject
    }

    private fun responseWithArtifact(tag: String, artifactSha: String): JsonObject {
        return JsonObject(
            response(tag).toMutableMap().apply {
                    put(
                    "xtra_release_metadata",
                    kotlinx.serialization.json.buildJsonObject {
                        put("versionName", "2.58.7")
                        put("versionCode", 294L)
                        put("sha256", "a".repeat(64))
                        put("artifacts", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("name", "app-arm64-v8a-release.apk")
                                put("sha256", artifactSha)
                            })
                        })
                    },
                )
                put(
                    "assets",
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("name", "app-release.apk")
                            put("content_type", UpdateRepository.APK_MIME_TYPE)
                            put("browser_download_url", "https://example.test/app-release.apk")
                            put("size", 10L)
                        })
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("name", "app-arm64-v8a-release.apk")
                            put("content_type", UpdateRepository.APK_MIME_TYPE)
                            put("browser_download_url", "https://example.test/app-arm64-v8a-release.apk")
                            put("size", 10L)
                        })
                    },
                )
            },
        )
    }

    private fun buildTag(build: Int): String = "v2.58.6-build.$build"

    private fun historyPage(vararg tags: String): JsonArray = JsonArray(tags.map(::response))

    private fun MemoryPreferences.persistRelease(tag: String) {
        edit {
            putString(C.UPDATE_AVAILABLE_VERSION, tag)
            putString(C.UPDATE_AVAILABLE_TITLE, "Xtra $tag")
            putString(C.UPDATE_AVAILABLE_BODY, "Old release")
            putString(C.UPDATE_AVAILABLE_URL, "https://example.test/old")
            putString(C.UPDATE_AVAILABLE_DOWNLOAD_URL, "https://example.test/old.apk")
            putString(C.UPDATE_AVAILABLE_ASSET_NAME, "app-release.apk")
            putLong(C.UPDATE_AVAILABLE_SIZE, 10L)
        }
    }

    private fun MemoryPreferences.persistDownloadedRelease(tag: String, downloadId: Long) {
        persistRelease(tag)
        edit {
            putLong(C.UPDATE_DOWNLOAD_ID, downloadId)
            putString(C.UPDATE_DOWNLOAD_FILE, "xtra-update-$downloadId.apk")
        }
    }

    private fun MemoryPreferences.putInstallOwnership(sessionId: Int, releaseId: String, commitStarted: Boolean) {
        edit {
            putInt(C.UPDATE_INSTALL_SESSION_ID, sessionId)
            putString(C.UPDATE_INSTALL_RELEASE_ID, releaseId)
            putBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, commitStarted)
        }
    }

    private fun successfulRecord(): UpdateDownloadRecord = UpdateDownloadRecord(
        status = DownloadManager.STATUS_SUCCESSFUL,
        downloadedBytes = 10L,
        totalBytes = 10L,
        uri = null,
        fileAvailable = true,
    )

    private fun runningRecord(): UpdateDownloadRecord = UpdateDownloadRecord(
        status = DownloadManager.STATUS_RUNNING,
        downloadedBytes = 5L,
        totalBytes = 10L,
        uri = null,
    )

    private fun setPrivateField(repository: UpdateRepository, name: String, value: Any?) {
        UpdateRepository::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(repository, value)
        }
    }

    private class QueueReleaseSource(private val results: List<Result<JsonObject>>) : ReleaseSource {
        var calls = 0
            private set

        override suspend fun fetch(url: String, networkLibrary: String?): JsonObject {
            calls++
            return results.getOrNull(calls - 1)?.getOrThrow()
                ?: throw AssertionError("Unexpected release request")
        }
    }

    private class HistoryReleaseSource(
        private val latest: JsonObject,
        private val pages: List<Result<JsonArray>>,
    ) : ReleaseSource {
        var historyCalls = 0
            private set

        override suspend fun fetch(url: String, networkLibrary: String?): JsonObject = latest

        override suspend fun fetchHistory(url: String, networkLibrary: String?, page: Int): JsonArray {
            historyCalls++
            return pages.getOrNull(page - 1)?.getOrThrow()
                ?: throw AssertionError("Unexpected release history request for page $page")
        }
    }

    private class BlockingReleaseSource(private val result: JsonObject) : ReleaseSource {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()

        override suspend fun fetch(url: String, networkLibrary: String?): JsonObject {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            returned.complete(Unit)
            return result
        }
    }

    private class CancellableBlockingReleaseSource(private val result: JsonObject) : ReleaseSource {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun fetch(url: String, networkLibrary: String?): JsonObject {
            started.complete(Unit)
            try {
                release.await()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
            return result
        }
    }

    private class MemoryDownloadStore : UpdateDownloadStore {
        val records = mutableMapOf<Long, UpdateDownloadRecord>()
        val removed = mutableListOf<Long>()
        val enqueued = mutableListOf<Long>()
        var enqueuedRelease: UpdateRelease? = null
        private var nextId = 1000L

        override fun enqueue(release: UpdateRelease, asset: UpdateAsset, fileName: String): Long {
            enqueuedRelease = release
            val id = nextId++
            enqueued += id
            records[id] = UpdateDownloadRecord(DownloadManager.STATUS_PENDING, 0L, asset.size, null)
            return id
        }

        override fun remove(id: Long) {
            removed += id
            records.remove(id)
        }

        override fun query(id: Long): UpdateDownloadRecord? = records[id]
    }

    private class BlockingQueryDownloadStore : UpdateDownloadStore {
        val records = mutableMapOf<Long, UpdateDownloadRecord>()
        val removed = mutableListOf<Long>()
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        @Volatile private var blockNext = false

        fun blockNextQuery() {
            blockNext = true
        }

        override fun enqueue(release: UpdateRelease, asset: UpdateAsset, fileName: String): Long = error("Unexpected enqueue")

        override fun remove(id: Long) {
            removed += id
            records.remove(id)
        }

        override fun query(id: Long): UpdateDownloadRecord? {
            val result = records[id]
            if (blockNext) {
                blockNext = false
                queryStarted.countDown()
                releaseQuery.await()
            }
            return result
        }
    }

    private class ThrowingDownloadStore : UpdateDownloadStore {
        override fun enqueue(release: UpdateRelease, asset: UpdateAsset, fileName: String): Long = 1L

        override fun remove(id: Long) = Unit

        override fun query(id: Long): UpdateDownloadRecord = throw IOException("download state unavailable")
    }

    private class RecordingInstallPreparer(
        private val preferences: SharedPreferences,
        private val failCommit: Boolean = false,
        private val failPrepare: Boolean = false,
    ) : UpdateInstallPreparer {
        var prepareCalls = 0
            private set
        var commitSawSessionId: Int? = null
            private set
        var commitSawReleaseId: String? = null
            private set
        var commitSawCommitStarted: Boolean = false
            private set
        var abandoned = false
            private set

        override fun prepare(release: UpdateRelease, artifact: DownloadedArtifact): PreparedUpdateInstall {
            prepareCalls++
            if (failPrepare) throw IOException("prepare failed")
            return object : PreparedUpdateInstall {
                override val sessionId: Int = 42

                override fun commit() {
                    commitSawSessionId = preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1)
                        .takeIf { it >= 0 }
                    commitSawReleaseId = preferences.getString(C.UPDATE_INSTALL_RELEASE_ID, null)
                    commitSawCommitStarted = preferences.getBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, false)
                    if (failCommit) throw IOException("commit failed")
                }

                override fun abandon() {
                    abandoned = true
                }
            }
        }
    }

    private class MissingArtifactInstallPreparer : UpdateInstallPreparer {
        override fun prepare(release: UpdateRelease, artifact: DownloadedArtifact): PreparedUpdateInstall =
            throw UpdateException(UpdateError.DownloadedFileMissing)
    }

    private class BlockingCommitInstallPreparer : UpdateInstallPreparer {
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        @Volatile var commitReturned = false

        override fun prepare(release: UpdateRelease, artifact: DownloadedArtifact): PreparedUpdateInstall =
            object : PreparedUpdateInstall {
                override val sessionId: Int = 42

                override fun commit() {
                    commitStarted.countDown()
                    releaseCommit.await()
                    commitReturned = true
                }

                override fun abandon() = Unit
            }
    }

    private class RecordingInstallSessionStore(
        private val snapshot: UpdateInstallSessionSnapshot?,
        private val recommitResult: Boolean = false,
    ) : UpdateInstallSessionStore {
        val abandoned = mutableListOf<Int>()
        var recommitCalls = 0
            private set

        override fun inspect(sessionId: Int): UpdateInstallSessionSnapshot? = snapshot

        override fun recommit(sessionId: Int, releaseId: String): Boolean {
            recommitCalls++
            return recommitResult
        }

        override fun abandon(sessionId: Int) {
            abandoned += sessionId
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runNext() {
            check(queue.isNotEmpty()) { "No queued coroutine" }
            queue.removeFirst().run()
        }

        fun runLast() {
            check(queue.isNotEmpty()) { "No queued coroutine" }
            queue.removeLast().run()
        }

        fun runAll() {
            repeat(20) {
                if (queue.isEmpty()) return
                queue.removeFirst().run()
            }
            error("Coroutine queue did not become idle")
        }
    }

    private class TestContext(
        private val tokenPreferences: SharedPreferences,
        private val settingsPreferences: SharedPreferences = tokenPreferences,
    ) : ContextWrapper(null) {

        override fun getPackageName(): String = "com.github.andreyasadchy.xtra.test"

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            if (name == "prefs2") tokenPreferences else settingsPreferences

        override fun getApplicationContext(): Context = this

    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, value: Set<String>?): SharedPreferences.Editor = put(key, value)
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removals += key
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                values.keys.toList().forEach(removals::add)
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            private fun <T> put(key: String?, value: T): SharedPreferences.Editor {
                if (key != null) updates[key] = value
                return this
            }
        }
    }
}
