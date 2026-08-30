package com.github.andreyasadchy.xtra.ui.update

import android.app.DownloadManager
import com.github.andreyasadchy.xtra.util.updater.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateStatusBinderTest {
    @Test
    fun pausedDownloadDoesNotShowAStaleOrCalculatingRate() {
        assertEquals(
            UpdateStatusBinder.RateLine.HIDDEN,
            UpdateStatusBinder.rateLineFor(
                value = DownloadProgress(
                    downloadedBytes = 18L,
                    totalBytes = 51L,
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                ),
                downloadManagerStatus = DownloadManager.STATUS_PAUSED,
            ),
        )
    }

    @Test
    fun pendingDownloadDoesNotShowAPlaceholderRate() {
        assertEquals(
            UpdateStatusBinder.RateLine.HIDDEN,
            UpdateStatusBinder.rateLineFor(
                value = DownloadProgress(0L, 51L),
                downloadManagerStatus = DownloadManager.STATUS_PENDING,
            ),
        )
    }
}
