package com.github.andreyasadchy.xtra.ui.update

import com.github.andreyasadchy.xtra.util.updater.DownloadProgress
import com.github.andreyasadchy.xtra.util.updater.UpdateError
import com.github.andreyasadchy.xtra.util.updater.UpdateRelease
import com.github.andreyasadchy.xtra.util.updater.UpdateStage
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiMapperTest {
    private val release = UpdateRelease(
        tagName = "v2.59.0",
        versionName = "2.59.0",
        buildNumber = 314L,
        title = "Xtra 2.59.0",
        releaseNotes = listOf("A new feature"),
        rawBody = "",
        releaseUrl = "https://example.test/release",
        publishedAt = null,
        assets = emptyList(),
        prerelease = false,
        draft = false,
    )

    @Test
    fun importantStatesHaveOnePrimaryAction() {
        assertEquals(UpdateUiAction.Check, UpdateState.Idle.toUiModel().primaryAction)
        assertNull(UpdateState.Checking.toUiModel().primaryAction)
        assertEquals(UpdateUiAction.Download, UpdateState.Available(release).toUiModel().primaryAction)
        assertEquals(UpdateUiAction.CancelDownload, UpdateState.Downloading(release, DownloadProgress(1L, 2L)).toUiModel().primaryAction)
        assertEquals(UpdateUiAction.Install, UpdateState.Downloaded(release, artifact()).toUiModel().primaryAction)
        assertEquals(UpdateUiAction.ContinueInstall, UpdateState.AwaitingUserAction(release, artifact(), 7).toUiModel().primaryAction)
    }

    @Test
    fun terminalAndErrorStatesKeepTheirRecoveryAction() {
        assertEquals(UpdateUiStatus.CURRENT, UpdateState.UpToDate(release).toUiModel().status)
        assertEquals(UpdateUiAction.Retry, UpdateState.Error(UpdateStage.DOWNLOAD, UpdateError.DownloadFailed, true, release).toUiModel().primaryAction)
        assertEquals(UpdateUiStatus.SKIPPED, UpdateState.Skipped(release).toUiModel().status)
        assertEquals(UpdateUiStatus.DEFERRED, UpdateState.Deferred(release).toUiModel().status)
    }

    @Test
    fun availableUpdateExposesSecondaryAndOverflowActionsSeparately() {
        val model = UpdateState.Available(release).toUiModel()

        assertEquals(UpdateUiAction.Download, model.primaryAction)
        assertEquals(UpdateUiAction.NotNow, model.secondaryAction)
        assertEquals(listOf(UpdateUiAction.SkipVersion), model.overflowActions)
    }

    @Test
    fun previouslySkippedUpdateOffersUndoWithoutLosingTheDownloadAction() {
        val model = UpdateState.Available(release, previouslySkipped = true).toUiModel()

        assertEquals(UpdateUiAction.Download, model.primaryAction)
        assertEquals(UpdateUiAction.UndoSkip, model.secondaryAction)
        assertTrue(model.overflowActions.isEmpty())
    }

    @Test
    fun downloadingStateExposesDiagnostics() {
        assertTrue(UpdateState.Downloading(release, DownloadProgress(1L, 2L)).toUiModel().showDiagnostics)
    }

    private fun artifact() = com.github.andreyasadchy.xtra.util.updater.DownloadedArtifact(
        downloadId = 1L,
        uri = null,
        fileName = "update.apk",
        size = 10L,
    )
}
