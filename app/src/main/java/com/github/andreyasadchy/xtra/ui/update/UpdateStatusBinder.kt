package com.github.andreyasadchy.xtra.ui.update

import android.app.DownloadManager
import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.updater.DownloadProgress
import com.google.android.material.progressindicator.LinearProgressIndicator

object UpdateStatusBinder {
    fun bindDownloadProgress(
        context: Context,
        progress: LinearProgressIndicator,
        bytes: TextView,
        rate: TextView,
        value: DownloadProgress?,
    ) {
        val percent = value?.percent
        progress.isIndeterminate = percent == null
        if (percent != null) progress.setProgressCompat(percent, true)
        bytes.text = when {
            value == null -> context.getString(R.string.update_preparing_download)
            value.totalBytes != null -> context.getString(
                R.string.update_transfer_progress,
                Formatter.formatFileSize(context, value.downloadedBytes),
                Formatter.formatFileSize(context, value.totalBytes),
                percent ?: 0,
            )
            else -> context.getString(
                R.string.update_transfer_downloaded,
                Formatter.formatFileSize(context, value.downloadedBytes),
            )
        }
        rate.text = when {
            value == null -> ""
            value.stalled -> context.getString(R.string.update_transfer_waiting)
            value.bytesPerSecond <= 0L -> context.getString(R.string.update_transfer_calculating_speed)
            value.etaSeconds != null -> context.getString(
                R.string.update_transfer_speed_eta,
                "${Formatter.formatFileSize(context, value.bytesPerSecond)}/s",
                formatEta(context, value.etaSeconds),
            )
            else -> context.getString(
                R.string.update_transfer_speed,
                "${Formatter.formatFileSize(context, value.bytesPerSecond)}/s",
            )
        }
        progress.visibility = View.VISIBLE
        bytes.visibility = View.VISIBLE
        rate.visibility = if (rate.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun downloadStatusText(context: Context, status: Int?, reason: Int?): String = when (status) {
        DownloadManager.STATUS_PENDING -> context.getString(R.string.update_download_starting)
        DownloadManager.STATUS_RUNNING -> context.getString(R.string.downloading_update)
        DownloadManager.STATUS_PAUSED -> when (reason) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> context.getString(R.string.update_download_waiting_network)
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> context.getString(R.string.update_download_waiting_wifi)
            DownloadManager.PAUSED_WAITING_TO_RETRY -> context.getString(R.string.update_download_waiting_retry)
            else -> context.getString(R.string.update_download_paused)
        }
        DownloadManager.STATUS_SUCCESSFUL -> context.getString(R.string.update_download_finished)
        else -> context.getString(R.string.downloading_update)
    }

    private fun formatEta(context: Context, seconds: Long): String = when {
        seconds < 60L -> context.resources.getQuantityString(R.plurals.update_eta_seconds, seconds.toInt(), seconds)
        else -> context.resources.getQuantityString(R.plurals.update_eta_minutes, ((seconds + 59L) / 60L).toInt(), (seconds + 59L) / 60L)
    }
}
