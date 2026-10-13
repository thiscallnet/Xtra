package com.github.andreyasadchy.xtra.repository

import android.net.Uri
import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.OfflineVideosDao
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.io.File

class OfflineVideosRepository(
    private val offlineVideosDao: OfflineVideosDao,
    private val bookmarksDao: BookmarksDao,
    private val filesDir: File,
) {

    // Saved hosts share a repository, so concurrent deletes must inspect shared files in sequence.
    val deletionMutex = Mutex()

    fun getAll() = offlineVideosDao.getAll()

    suspend fun getById(id: Int) = withContext(Dispatchers.IO) {
        offlineVideosDao.getById(id)
    }

    suspend fun getByUrl(url: String) = withContext(Dispatchers.IO) {
        offlineVideosDao.getByUrl(url)
    }

    suspend fun getActiveDownloads() = withContext(Dispatchers.IO) {
        offlineVideosDao.getActiveDownloads()
    }

    suspend fun getWaitingDownloads() = withContext(Dispatchers.IO) {
        offlineVideosDao.getWaitingDownloads()
    }

    suspend fun getByUserId(id: String) = withContext(Dispatchers.IO) {
        offlineVideosDao.getByUserId(id)
    }

    suspend fun getMediaReferenceIds(uri: String) = withContext(Dispatchers.IO) {
        offlineVideosDao.getMediaReferenceIds(uri)
    }

    suspend fun getPlaylists() = withContext(Dispatchers.IO) {
        offlineVideosDao.getPlaylists()
    }

    suspend fun save(video: OfflineVideo) = withContext(Dispatchers.IO) {
        offlineVideosDao.insert(video)
    }

    suspend fun delete(video: OfflineVideo) = withContext(Dispatchers.IO) {
        video.videoId?.let { id ->
            if (id.isNotBlank() && offlineVideosDao.getByVideoId(id).none { it.id != video.id } && bookmarksDao.getByVideoId(id) == null) {
                deleteCachedImage(video.thumbnail, "thumbnails", id)
            }
        }
        video.channelId?.let { id ->
            if (id.isNotBlank() && getByUserId(id).none { it.id != video.id } && bookmarksDao.getByUserId(id).isEmpty()) {
                deleteCachedImage(video.channelLogo, "profile_pics", id)
            }
        }
        offlineVideosDao.delete(video)
    }

    /** A thumbnail may be the video itself or a playlist segment, rather than a cached image. */
    private fun deleteCachedImage(path: String?, directory: String, id: String) {
        if (path.isNullOrBlank()) return
        val image = File(path).canonicalFile
        val cache = File(filesDir, directory).canonicalFile
        if (image.parentFile != cache || image.name != id) return
        // Imported records can reference an existing cache file as media. Keep those bytes too.
        val referencePaths = setOf(path, image.path, File(File(filesDir, directory), id).path)
        if (referencePaths.any { candidate ->
                offlineVideosDao.getMediaReferenceIds(candidate).isNotEmpty() ||
                    offlineVideosDao.getMediaReferenceIds(Uri.fromFile(File(candidate)).toString()).isNotEmpty()
            }
        ) return
        image.delete()
    }

    suspend fun update(video: OfflineVideo) = withContext(Dispatchers.IO) {
        offlineVideosDao.update(video)
    }

    suspend fun updatePosition(id: Int, position: Long) = withContext(Dispatchers.IO) {
        offlineVideosDao.updatePosition(id, position)
    }

    suspend fun deletePositions() = withContext(Dispatchers.IO) {
        offlineVideosDao.deletePositions()
    }
}
