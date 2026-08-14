package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.R

data class UpdateDownloadRecord(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val uri: Uri?,
    val fileAvailable: Boolean = uri != null,
)

/** Small DownloadManager seam so persisted update transitions can be exercised without a device. */
interface UpdateDownloadStore {
    fun enqueue(release: UpdateRelease, asset: UpdateAsset, fileName: String): Long
    fun remove(id: Long)
    fun query(id: Long): UpdateDownloadRecord?
}

class AndroidUpdateDownloadStore(
    private val context: Context,
    private val downloadManager: DownloadManager,
) : UpdateDownloadStore {
    override fun enqueue(release: UpdateRelease, asset: UpdateAsset, fileName: String): Long =
        downloadManager.enqueue(
            DownloadManager.Request(asset.downloadUrl.toUri())
                .setTitle(context.getString(R.string.update_download_title, release.displayVersion))
                .setDescription(context.getString(R.string.downloading_update))
                .setMimeType(UpdateRepository.APK_MIME_TYPE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, fileName),
        )

    override fun remove(id: Long) {
        downloadManager.remove(id)
    }

    override fun query(id: Long): UpdateDownloadRecord? {
        return downloadManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val uri = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                downloadManager.getUriForDownloadedFile(id)
            } else {
                null
            }
            UpdateDownloadRecord(
                status = status,
                downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    .takeIf { it > 0L },
                uri = uri,
                fileAvailable = uri != null,
            )
        }
    }
}
