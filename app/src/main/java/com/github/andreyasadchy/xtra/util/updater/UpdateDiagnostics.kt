package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import com.github.andreyasadchy.xtra.BuildConfig

data class UpdateDiagnosticsSnapshot(
    val state: String,
    val stage: String?,
    val installedVersion: String,
    val targetVersion: String?,
    val assetName: String?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
    val downloadManagerStatus: Int?,
    val downloadManagerReason: Int?,
    val lastSuccessfulCheck: Long?,
    val lastAttemptedCheck: Long?,
    val errorType: String?,
    val timestamp: Long,
)

object UpdateDiagnostics {
    fun snapshot(
        state: UpdateState,
        installedVersion: String,
        assetName: String?,
        lastSuccessfulCheck: Long?,
        lastAttemptedCheck: Long?,
        downloadRecord: UpdateDownloadRecord?,
        now: Long = System.currentTimeMillis(),
    ): UpdateDiagnosticsSnapshot {
        val progress = (state as? UpdateState.Downloading)?.progress
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Skipped -> state.release
            is UpdateState.Deferred -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Downloaded -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.AwaitingUserAction -> state.release
            is UpdateState.Error -> state.release
            is UpdateState.UpToDate -> state.release
            UpdateState.Idle, UpdateState.Checking -> null
        }
        val status = (state as? UpdateState.Downloading)?.downloadManagerStatus
            ?: downloadRecord?.status
        val reason = (state as? UpdateState.Downloading)?.downloadManagerReason
            ?: (state as? UpdateState.Error)?.downloadManagerReason
            ?: downloadRecord?.reason
        return UpdateDiagnosticsSnapshot(
            state = state::class.simpleName ?: "Unknown",
            stage = when (state) {
                UpdateState.Idle -> null
                UpdateState.Checking -> UpdateStage.CHECK.name
                is UpdateState.Available,
                is UpdateState.Skipped,
                is UpdateState.Deferred,
                -> UpdateStage.CHECK.name
                is UpdateState.Downloading -> UpdateStage.DOWNLOAD.name
            is UpdateState.Downloaded -> "READY"
                is UpdateState.Installing,
                is UpdateState.AwaitingUserAction,
                -> if (state is UpdateState.Installing && state.sessionId == null) "VERIFYING" else UpdateStage.INSTALL.name
                is UpdateState.Error -> state.stage.name
                is UpdateState.UpToDate -> UpdateStage.CHECK.name
            },
            installedVersion = installedVersion,
            targetVersion = release?.displayVersion,
            assetName = assetName,
            downloadedBytes = progress?.downloadedBytes ?: downloadRecord?.downloadedBytes,
            totalBytes = progress?.totalBytes ?: downloadRecord?.totalBytes,
            bytesPerSecond = progress?.bytesPerSecond,
            downloadManagerStatus = status,
            downloadManagerReason = reason,
            lastSuccessfulCheck = lastSuccessfulCheck,
            lastAttemptedCheck = lastAttemptedCheck,
            errorType = (state as? UpdateState.Error)?.cause?.let { it::class.simpleName },
            timestamp = now,
        )
    }

    fun format(context: Context, snapshot: UpdateDiagnosticsSnapshot): String = buildString {
        appendLine("Xtra updater diagnostics")
        appendLine("State: ${snapshot.state}")
        snapshot.stage?.let { appendLine("Stage: $it") }
        appendLine("Installed: ${snapshot.installedVersion}")
        snapshot.targetVersion?.let { appendLine("Target: $it") }
        snapshot.assetName?.let { appendLine("Asset: $it") }
        val downloaded = snapshot.downloadedBytes
        val total = snapshot.totalBytes
        if (downloaded != null) {
            val progress = total?.let {
                "${Formatter.formatFileSize(context, downloaded)} / ${Formatter.formatFileSize(context, it)}"
            } ?: Formatter.formatFileSize(context, downloaded)
            appendLine("Progress: $progress")
        }
        snapshot.bytesPerSecond?.takeIf { it > 0L }?.let {
            appendLine("Transfer speed: ${Formatter.formatFileSize(context, it)}/s")
        }
        snapshot.downloadManagerStatus?.let { appendLine("DownloadManager status: ${downloadStatusName(it)}") }
        snapshot.downloadManagerReason?.takeIf { it != DownloadManager.ERROR_UNKNOWN }?.let {
            appendLine("DownloadManager reason: $it")
        }
        appendLine("Last successful check: ${snapshot.lastSuccessfulCheck ?: 0L}")
        appendLine("Last attempted check: ${snapshot.lastAttemptedCheck ?: 0L}")
        appendLine("Error: ${snapshot.errorType ?: "None"}")
        appendLine("Timestamp: ${snapshot.timestamp}")
    }

    fun sanitizeEndpoint(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        buildString {
            uri.scheme?.let { append(it).append("://") }
            uri.host?.let(::append)
            uri.port.takeIf { it >= 0 }?.let { append(':').append(it) }
            uri.encodedPath?.takeIf { it.isNotBlank() }?.let(::append)
        }.takeIf { it.isNotBlank() } ?: "configured endpoint"
    }.getOrDefault("configured endpoint")

    private fun downloadStatusName(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> "Pending"
        DownloadManager.STATUS_RUNNING -> "Running"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_SUCCESSFUL -> "Successful"
        DownloadManager.STATUS_FAILED -> "Failed"
        else -> "Unknown"
    }
}
