package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class UpdateDownloadMonitorTest {
    @Test
    fun pendingDownloadEventuallyExposesRecovery() = runBlocking {
        val records = ArrayDeque(
            listOf(
                record(DownloadManager.STATUS_PENDING, 0L),
                record(DownloadManager.STATUS_PENDING, 0L),
                record(DownloadManager.STATUS_PENDING, 0L),
                record(DownloadManager.STATUS_SUCCESSFUL, 1L),
            ),
        )
        val events = CopyOnWriteArrayList<UpdateDownloadEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = UpdateDownloadMonitor(
            store = SequenceStore(records),
            scope = scope,
            nowMs = object {
                private var value = 0L
                operator fun invoke(): Long = value.also { value += 1_000L }
            }::invoke,
            pollMillis = 1L,
            pendingRestartAfterMs = 2_000L,
        )

        try {
            monitor.start(1L) { events += it }
            withTimeout(2_000L) {
                while (events.none { it is UpdateDownloadEvent.Completed }) delay(5L)
            }
        } finally {
            monitor.cancel()
            scope.cancel()
        }

        assertEquals(
            true,
            events.filterIsInstance<UpdateDownloadEvent.Progress>().last().telemetry.stalled,
        )
    }

    @Test
    fun pausingAndResumingClearsPreviousRateAndEta() = runBlocking {
        val records = ArrayDeque(
            listOf(
                record(DownloadManager.STATUS_RUNNING, 0L),
                record(DownloadManager.STATUS_RUNNING, 1_000L),
                record(DownloadManager.STATUS_RUNNING, 2_000L),
                record(DownloadManager.STATUS_PAUSED, 2_000L, DownloadManager.PAUSED_WAITING_FOR_NETWORK),
                record(DownloadManager.STATUS_RUNNING, 2_000L),
                record(DownloadManager.STATUS_RUNNING, 3_000L),
                record(DownloadManager.STATUS_SUCCESSFUL, 3_000L),
            ),
        )
        val events = CopyOnWriteArrayList<UpdateDownloadEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = UpdateDownloadMonitor(
            store = SequenceStore(records),
            scope = scope,
            nowMs = object {
                private var value = 0L
                operator fun invoke(): Long = value.also { value += 1_000L }
            }::invoke,
            pollMillis = 1L,
        )

        try {
            monitor.start(1L) { events += it }
            withTimeout(2_000L) {
                while (events.none { it is UpdateDownloadEvent.Completed }) delay(5L)
            }
        } finally {
            monitor.cancel()
            scope.cancel()
        }

        val progress = events.filterIsInstance<UpdateDownloadEvent.Progress>()
        val paused = progress.first { it.record.status == DownloadManager.STATUS_PAUSED }
        val resumed = progress
            .drop(progress.indexOf(paused) + 1)
            .first { it.record.status == DownloadManager.STATUS_RUNNING }

        assertEquals(0L, paused.telemetry.bytesPerSecond)
        assertNull(paused.telemetry.etaSeconds)
        assertEquals(0L, resumed.telemetry.bytesPerSecond)
        assertNull(resumed.telemetry.etaSeconds)
    }

    private fun record(status: Int, bytes: Long, reason: Int? = null) = UpdateDownloadRecord(
        status = status,
        downloadedBytes = bytes,
        totalBytes = 10_000L,
        uri = null,
        reason = reason,
    )

    private class SequenceStore(
        private val records: ArrayDeque<UpdateDownloadRecord>,
    ) : UpdateDownloadStore {
        override fun enqueue(release: UpdateRelease, asset: UpdateAsset, fileName: String): Long = 1L
        override fun remove(id: Long) = Unit
        override fun query(id: Long): UpdateDownloadRecord? = records.removeFirstOrNull()
    }
}
