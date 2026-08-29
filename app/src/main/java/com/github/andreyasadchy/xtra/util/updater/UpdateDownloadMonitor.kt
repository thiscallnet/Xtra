package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UpdateDownloadEvent {
    data class Progress(
        val record: UpdateDownloadRecord,
        val telemetry: DownloadProgress,
    ) : UpdateDownloadEvent

    data class Completed(val record: UpdateDownloadRecord) : UpdateDownloadEvent
    data class Failed(
        val record: UpdateDownloadRecord?,
        val reason: Int?,
        val queryFailed: Boolean = false,
    ) : UpdateDownloadEvent
    data object Cancelled : UpdateDownloadEvent
}

/** Polls DownloadManager and reports observations. It never owns application update state. */
class UpdateDownloadMonitor(
    private val store: UpdateDownloadStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 400L,
) {
    private var job: Job? = null
    private var monitoredId: Long? = null

    fun start(id: Long, onEvent: suspend (UpdateDownloadEvent) -> Unit) {
        if (job?.isActive == true && monitoredId == id) return
        job?.cancel()
        monitoredId = id
        job = scope.launch {
            val estimator = TransferRateEstimator()
            try {
                while (isActive && monitoredId == id) {
                    val record = runCatching { store.query(id) }.getOrElse {
                        onEvent(UpdateDownloadEvent.Failed(null, null, queryFailed = true))
                        break
                    }
                    if (record == null) {
                        onEvent(UpdateDownloadEvent.Failed(null, null))
                        break
                    }
                    when (record.status) {
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PAUSED,
                        -> {
                            val rate = estimator.sample(record.downloadedBytes, nowMs())
                            onEvent(
                                UpdateDownloadEvent.Progress(
                                    record,
                                    DownloadProgress(
                                        downloadedBytes = record.downloadedBytes,
                                        totalBytes = record.totalBytes,
                                        bytesPerSecond = rate.bytesPerSecond,
                                        etaSeconds = calculateEtaSeconds(
                                            record.downloadedBytes,
                                            record.totalBytes,
                                            rate.bytesPerSecond,
                                        ),
                                        stalled = rate.stalled,
                                    ),
                                ),
                            )
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            onEvent(UpdateDownloadEvent.Completed(record))
                            break
                        }
                        else -> {
                            onEvent(UpdateDownloadEvent.Failed(record, record.reason))
                            break
                        }
                    }
                    delay(pollMillis)
                }
            } finally {
                if (monitoredId == id) {
                    monitoredId = null
                    job = null
                }
            }
        }
    }

    fun cancel() {
        monitoredId = null
        job?.cancel()
        job = null
    }
}
