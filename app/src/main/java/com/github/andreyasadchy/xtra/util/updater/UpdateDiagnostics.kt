package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import com.github.andreyasadchy.xtra.R

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
                is UpdateState.UpToDate,
                -> UpdateStage.CHECK.name
                is UpdateState.Downloading -> UpdateStage.DOWNLOAD.name
                is UpdateState.Downloaded -> "READY"
                is UpdateState.Installing,
                is UpdateState.AwaitingUserAction,
                -> if (state is UpdateState.Installing && state.sessionId == null) "VERIFYING" else UpdateStage.INSTALL.name
                is UpdateState.Error -> state.stage.name
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
        appendLine(context.getString(R.string.update_diagnostics))
        appendLine(context.getString(R.string.update_diagnostics_state, snapshot.state))
        snapshot.stage?.let { appendLine(context.getString(R.string.update_diagnostics_stage, it)) }
        appendLine(context.getString(R.string.update_diagnostics_installed, snapshot.installedVersion))
        snapshot.targetVersion?.let { appendLine(context.getString(R.string.update_diagnostics_target, it)) }
        snapshot.assetName?.let { appendLine(context.getString(R.string.update_diagnostics_asset, it)) }
        val downloaded = snapshot.downloadedBytes
        val total = snapshot.totalBytes
        if (downloaded != null) {
            val progress = total?.let {
                Formatter.formatFileSize(context, downloaded) + " / " + Formatter.formatFileSize(context, it)
            } ?: Formatter.formatFileSize(context, downloaded)
            appendLine(context.getString(R.string.update_diagnostics_progress, progress))
        }
        snapshot.bytesPerSecond?.takeIf { it > 0L }?.let {
            appendLine(
                context.getString(
                    R.string.update_diagnostics_speed,
                    Formatter.formatFileSize(context, it) + "/s",
                ),
            )
        }
        snapshot.downloadManagerStatus?.let {
            appendLine(context.getString(R.string.update_diagnostics_status, downloadStatusName(it)))
        }
        snapshot.downloadManagerReason?.takeIf { it != DownloadManager.ERROR_UNKNOWN }?.let {
            appendLine(context.getString(R.string.update_diagnostics_reason, downloadReasonName(it), it))
        }
        appendLine(
            context.getString(
                R.string.update_diagnostics_last_check,
                formatTimestamp(context, snapshot.lastSuccessfulCheck, snapshot.timestamp),
            ),
        )
        appendLine(
            context.getString(
                R.string.update_diagnostics_last_attempt,
                formatTimestamp(context, snapshot.lastAttemptedCheck, snapshot.timestamp),
            ),
        )
        appendLine(
            context.getString(
                R.string.update_diagnostics_error,
                snapshot.errorType ?: context.getString(R.string.none),
            ),
        )
        appendLine(
            context.getString(
                R.string.update_diagnostics_timestamp,
                formatTimestamp(context, snapshot.timestamp, snapshot.timestamp),
            ),
        )
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

    private fun downloadReasonName(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "PAUSED_WAITING_TO_RETRY"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "PAUSED_WAITING_FOR_NETWORK"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "PAUSED_QUEUED_FOR_WIFI"
        DownloadManager.PAUSED_UNKNOWN -> "PAUSED_UNKNOWN"
        DownloadManager.ERROR_FILE_ERROR -> "ERROR_FILE_ERROR"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "ERROR_UNHANDLED_HTTP_CODE"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "ERROR_HTTP_DATA_ERROR"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "ERROR_TOO_MANY_REDIRECTS"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "ERROR_INSUFFICIENT_SPACE"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "ERROR_DEVICE_NOT_FOUND"
        DownloadManager.ERROR_CANNOT_RESUME -> "ERROR_CANNOT_RESUME"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "ERROR_FILE_ALREADY_EXISTS"
        else -> "UNKNOWN"
    }

    private fun formatTimestamp(context: Context, timestamp: Long?, now: Long): String =
        timestamp?.takeIf { it > 0L }?.let { UpdateTimeFormatter.format(context, it, now) }
            ?: context.getString(R.string.never)
}
