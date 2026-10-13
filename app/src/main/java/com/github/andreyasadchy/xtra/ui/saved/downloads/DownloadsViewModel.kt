package com.github.andreyasadchy.xtra.ui.saved.downloads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.JsonReader
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.util.createOrFindDirectory
import com.github.andreyasadchy.xtra.util.createOrFindDocument
import com.github.andreyasadchy.xtra.util.findChildDocument
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

class DownloadsViewModel(
    private val applicationContext: Context,
    private val offlineVideosRepository: OfflineVideosRepository,
) : ViewModel() {

    private fun openOutputStream(uri: Uri, mode: String = "w") =
        applicationContext.contentResolver.openOutputStream(uri, mode)
            ?: throw IOException("Unable to open download output")

    private fun openFileDescriptor(uri: Uri, mode: String) =
        applicationContext.contentResolver.openFileDescriptor(uri, mode)
            ?: throw IOException("Unable to open download file")

    var selectedVideo: OfflineVideo? = null
    private val videosInUse = ConcurrentHashMap.newKeySet<Int>()
    private var bulkDeleteJob: Job? = null
    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed = _deleteFailed.asStateFlow()
    fun acknowledgeDeleteFailure() { _deleteFailed.value = false }

    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30),
    ) {
        offlineVideosRepository.getAll()
    }.flow.cachedIn(viewModelScope)

    suspend fun getActiveDownloads(): List<OfflineVideo> {
        return offlineVideosRepository.getActiveDownloads()
    }

    fun updateDownloadStatus(video: OfflineVideo, waitForWifi: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            offlineVideosRepository.update(video.apply {
                status = if (waitForWifi) {
                    OfflineVideo.STATUS_WAITING_FOR_WIFI
                } else {
                    OfflineVideo.STATUS_PENDING
                }
            })
        }
    }

    fun finishDownload(video: OfflineVideo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                video.chatUrl?.let { url ->
                    val isShared = url.toUri().scheme == ContentResolver.SCHEME_CONTENT
                    if (isShared) {
                        openFileDescriptor(url.toUri(), "rw").use {
                            FileOutputStream(it.fileDescriptor).use { output ->
                                output.channel.truncate(video.chatBytes)
                            }
                        }
                    } else {
                        FileOutputStream(url).use { output ->
                            output.channel.truncate(video.chatBytes)
                        }
                    }
                    if (isShared) {
                        openOutputStream(url.toUri(), "wa").bufferedWriter()
                    } else {
                        FileOutputStream(url, true).bufferedWriter()
                    }.use { writer ->
                        if (video.liveCommentsArrayStarted) {
                            writer.write("]")
                        }
                        writer.write("}")
                    }
                }
                offlineVideosRepository.update(video.apply {
                    status = OfflineVideo.STATUS_DOWNLOADED
                })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                offlineVideosRepository.update(video.apply {
                    status = OfflineVideo.STATUS_FAILED
                })
            }
        }
    }

    fun convertToFile(video: OfflineVideo) {
        val videoUrl = video.url
        if (videoUrl != null && videosInUse.add(video.id)) {
            viewModelScope.launch(Dispatchers.IO) {
                offlineVideosRepository.update(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_CONVERTING
                })
                if (videoUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
                    val oldVideoDirectoryUri = videoUrl.substringBeforeLast("%2F")
                    val oldDirectoryUri = oldVideoDirectoryUri.substringBeforeLast("%2F", oldVideoDirectoryUri.substringBeforeLast("%3A") + "%3A")
                    val oldPlaylist = applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    val videoFileName = "${video.videoId ?: ""}${video.quality ?: ""}${video.downloadDate}.${oldPlaylist.segments.first().uri.substringAfterLast(".")}"
                    val oldDirectoryDocumentUri = oldDirectoryUri.toUri()
                    val existingNewVideoUri = applicationContext.contentResolver.findChildDocument(oldDirectoryDocumentUri, videoFileName, "video/mp4")
                    val newVideoFileUri = (existingNewVideoUri
                        ?: applicationContext.contentResolver.createOrFindDocument(
                            oldDirectoryDocumentUri,
                            "video/mp4",
                            videoFileName,
                        )).toString()
                    val tracksToDelete = mutableListOf<String>()
                    oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                    val playlists = offlineVideosRepository.getPlaylists().mapNotNull { video ->
                        video.url?.takeIf {
                            it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                                    && it.substringBeforeLast("%2F") == oldVideoDirectoryUri
                                    && it != videoUrl
                        }
                    }
                    playlists.forEach { uri ->
                        try {
                            val p = applicationContext.contentResolver.openInputStream(uri.toUri())!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            p.segments.forEach { tracksToDelete.remove(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    }
                    val new = existingNewVideoUri == null
                    if (oldPlaylist.initSegmentUri != null && new) {
                        val oldFileUri = oldPlaylist.initSegmentUri
                        openOutputStream(newVideoFileUri.toUri(), "wa").use { outputStream ->
                            applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    offlineVideosRepository.update(video.apply {
                        maxProgress = tracksToDelete.count()
                    })
                    oldPlaylist.segments.forEach { track ->
                        val oldFileUri = track.uri
                        val oldFileName = oldFileUri.substringAfterLast("%2F").substringAfterLast("/")
                        openOutputStream(newVideoFileUri.toUri(), "wa").use { outputStream ->
                            applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        if (tracksToDelete.contains(oldFileName)) {
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, oldFileUri.toUri())
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e

                            }
                        }
                        offlineVideosRepository.update(video.apply {
                            progress += 1
                        })
                    }
                    offlineVideosRepository.update(video.apply {
                        thumbnail.let {
                            if (it == null || it == url || !File(it).exists()) {
                                thumbnail = newVideoFileUri
                            }
                        }
                        url = newVideoFileUri
                    })
                    if (playlists.isNotEmpty()) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    } else {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldVideoDirectoryUri.toUri())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    }
                } else {
                    val oldPlaylistFile = File(videoUrl)
                    if (oldPlaylistFile.exists()) {
                        val oldVideoDirectory = oldPlaylistFile.parentFile
                        val oldDirectory = oldVideoDirectory?.parentFile
                        if (oldVideoDirectory != null && oldDirectory != null) {
                            val oldPlaylist = FileInputStream(oldPlaylistFile).use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            val videoFileName = "${video.videoId ?: ""}${video.quality ?: ""}${video.downloadDate}.${oldPlaylist.segments.first().uri.substringAfterLast(".")}"
                            val newVideoFileUri = "${oldDirectory.path}${File.separator}$videoFileName"
                            val tracksToDelete = mutableListOf<String>()
                            oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            val playlists = oldVideoDirectory.listFiles { it.extension == "m3u8" && it != oldPlaylistFile }
                            playlists?.forEach { file ->
                                val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                                p.segments.forEach { tracksToDelete.remove(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            }
                            if (oldPlaylist.initSegmentUri != null && File(newVideoFileUri).length() == 0L) {
                                val oldFile = File(oldVideoDirectory.path + File.separator + oldPlaylist.initSegmentUri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    FileOutputStream(newVideoFileUri).use { outputStream ->
                                        oldFile.inputStream().use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                }
                            }
                            offlineVideosRepository.update(video.apply {
                                maxProgress = tracksToDelete.count()
                            })
                            oldPlaylist.segments.forEach { track ->
                                val oldFile = File(oldVideoDirectory.path + File.separator + track.uri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    FileOutputStream(newVideoFileUri).use { outputStream ->
                                        oldFile.inputStream().use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                    if (tracksToDelete.contains(oldFile.name)) {
                                        oldFile.delete()
                                    }
                                }
                                offlineVideosRepository.update(video.apply {
                                    progress += 1
                                })
                            }
                            offlineVideosRepository.update(video.apply {
                                thumbnail.let {
                                    if (it == null || it == url || !File(it).exists()) {
                                        thumbnail = newVideoFileUri
                                    }
                                }
                                url = newVideoFileUri
                            })
                            if (playlists?.isNotEmpty() == true) {
                                oldPlaylistFile.delete()
                            } else {
                                oldVideoDirectory.deleteRecursively()
                            }
                        }
                    }
                }
            }.invokeOnCompletion { error ->
                videosInUse.remove(video.id)
                viewModelScope.launch(Dispatchers.IO) {
                    offlineVideosRepository.update(video.apply {
                        status = if (error == null) OfflineVideo.STATUS_DOWNLOADED else OfflineVideo.STATUS_FAILED
                    })
                }
            }
        }
    }

    fun moveToSharedStorage(newUri: Uri, video: OfflineVideo) {
        val videoUrl = video.url
        if (videoUrl != null && videosInUse.add(video.id)) {
            viewModelScope.launch(Dispatchers.IO) {
                offlineVideosRepository.update(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_MOVING
                })
                if (videoUrl.endsWith(".m3u8")) {
                    val oldPlaylistFile = File(videoUrl)
                    if (oldPlaylistFile.exists()) {
                        val oldVideoDirectory = oldPlaylistFile.parentFile
                        if (oldVideoDirectory != null) {
                            val documentId = DocumentsContract.getTreeDocumentId(newUri)
                            val newDirectoryUri = DocumentsContract.buildDocumentUriUsingTree(newUri, documentId)
                            val newVideoDirectoryUri = applicationContext.contentResolver.createOrFindDirectory(newDirectoryUri, oldVideoDirectory.name)
                            val oldPlaylist = FileInputStream(oldPlaylistFile).use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            val segmentUris = oldPlaylist.segments.associate { segment ->
                                val fileName = segment.uri.substringAfterLast("%2F").substringAfterLast("/")
                                fileName to applicationContext.contentResolver.createOrFindDocument(
                                    newVideoDirectoryUri,
                                    "application/octet-stream",
                                    fileName,
                                )
                            }
                            val segments = oldPlaylist.segments.map { segment ->
                                val fileName = segment.uri.substringAfterLast("%2F").substringAfterLast("/")
                                segment.copy(uri = segmentUris.getValue(fileName).toString())
                            }
                            val initSegmentUri = oldPlaylist.initSegmentUri?.let { uri ->
                                val fileName = uri.substringAfterLast("%2F").substringAfterLast("/")
                                applicationContext.contentResolver.createOrFindDocument(
                                    newVideoDirectoryUri,
                                    "application/octet-stream",
                                    fileName,
                                )
                            }
                            val newPlaylistFileUri = applicationContext.contentResolver.createOrFindDocument(
                                newVideoDirectoryUri,
                                "application/vnd.apple.mpegurl",
                                oldPlaylistFile.name,
                            )
                            openOutputStream(newPlaylistFileUri, "wt").use {
                                PlaylistUtils.writeMediaPlaylist(oldPlaylist.copy(
                                    initSegmentUri = initSegmentUri?.toString(),
                                    segments = segments
                                ), it)
                            }
                            val tracksToDelete = mutableListOf<String>()
                            oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            val playlists = oldVideoDirectory.listFiles { it.extension == "m3u8" && it != oldPlaylistFile }
                            playlists?.forEach { file ->
                                val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                                p.segments.forEach { tracksToDelete.remove(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            }
                            if (oldPlaylist.initSegmentUri != null) {
                                val oldFile = File(oldVideoDirectory.path + File.separator + oldPlaylist.initSegmentUri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    openOutputStream(initSegmentUri!!, "wt").use { outputStream ->
                                        oldFile.inputStream().use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                }
                            }
                            offlineVideosRepository.update(video.apply {
                                maxProgress = tracksToDelete.count()
                            })
                            oldPlaylist.segments.forEach { track ->
                                val oldFile = File(oldVideoDirectory.path + File.separator + track.uri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    openOutputStream(segmentUris.getValue(oldFile.name), "wt").use { outputStream ->
                                        oldFile.inputStream().use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                    if (tracksToDelete.contains(oldFile.name)) {
                                        oldFile.delete()
                                    }
                                }
                                offlineVideosRepository.update(video.apply {
                                    progress += 1
                                })
                            }
                            val oldChatFile = video.chatUrl?.let { uri -> File(uri).takeIf { it.exists() } }
                            val newChatFileUri = oldChatFile?.let {
                                applicationContext.contentResolver.createOrFindDocument(newDirectoryUri, "application/json", it.name)
                            }
                            if (newChatFileUri != null) {
                                openOutputStream(newChatFileUri, "wt").use { outputStream ->
                                    oldChatFile.inputStream().use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                            }
                            offlineVideosRepository.update(video.apply {
                                thumbnail.let {
                                    if (it == null || it == url || !File(it).exists()) {
                                        segments.getOrNull(
                                            max(0, (oldPlaylist.segments.size / 2) - 1)
                                        )?.uri?.let { trackUri ->
                                            thumbnail = trackUri
                                        }
                                    }
                                }
                                url = newPlaylistFileUri.toString()
                                chatUrl = newChatFileUri?.toString()
                            })
                            if (playlists?.isNotEmpty() == true) {
                                oldPlaylistFile.delete()
                            } else {
                                oldVideoDirectory.deleteRecursively()
                            }
                            oldChatFile?.delete()
                        }
                    }
                } else {
                    val oldFile = File(videoUrl)
                    if (oldFile.exists()) {
                        val documentId = DocumentsContract.getTreeDocumentId(newUri)
                        val newDirectoryUri = DocumentsContract.buildDocumentUriUsingTree(newUri, documentId)
                        val newFileUri = applicationContext.contentResolver.createOrFindDocument(
                            newDirectoryUri,
                            "video/mp4",
                            oldFile.name,
                        ).toString()
                        openOutputStream(newFileUri.toUri(), "wt").use { outputStream ->
                            oldFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        val oldChatFile = video.chatUrl?.let { uri -> File(uri).takeIf { it.exists() } }
                        val newChatFileUri = oldChatFile?.let {
                            applicationContext.contentResolver.createOrFindDocument(newDirectoryUri, "application/json", it.name).toString()
                        }
                        if (newChatFileUri != null) {
                            openOutputStream(newChatFileUri.toUri(), "wt").use { outputStream ->
                                oldChatFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                        offlineVideosRepository.update(video.apply {
                            thumbnail.let {
                                if (it == null || it == url || !File(it).exists()) {
                                    thumbnail = newFileUri
                                }
                            }
                            url = newFileUri
                            chatUrl = newChatFileUri
                        })
                        oldFile.delete()
                        oldChatFile?.delete()
                    }
                }
            }.invokeOnCompletion { error ->
                videosInUse.remove(video.id)
                viewModelScope.launch(Dispatchers.IO) {
                    offlineVideosRepository.update(video.apply {
                        status = if (error == null) OfflineVideo.STATUS_DOWNLOADED else OfflineVideo.STATUS_FAILED
                    })
                }
            }
        }
    }

    fun moveToAppStorage(path: String, video: OfflineVideo) {
        val videoUrl = video.url
        if (videoUrl != null && videosInUse.add(video.id)) {
            viewModelScope.launch(Dispatchers.IO) {
                offlineVideosRepository.update(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_MOVING
                })
                if (videoUrl.endsWith(".m3u8")) {
                    val oldPlaylistFileName = Uri.decode(videoUrl.substringAfterLast("%2F"))
                    val oldVideoDirectoryUri = videoUrl.substringBeforeLast("%2F")
                    val oldVideoDirectoryName = Uri.decode(oldVideoDirectoryUri.substringAfterLast("%2F").substringAfterLast("%3A"))
                    val newVideoDirectoryUri = path + File.separator + oldVideoDirectoryName
                    File(newVideoDirectoryUri).mkdir()
                    val newPlaylistFileUri = newVideoDirectoryUri + File.separator + oldPlaylistFileName
                    val oldPlaylist = applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    val segments = ArrayList<Segment>()
                    oldPlaylist.segments.forEach { segment ->
                        segments.add(segment.copy(uri = newVideoDirectoryUri + File.separator + Uri.decode(segment.uri.substringAfterLast("%2F").substringAfterLast("/"))))
                    }
                    FileOutputStream(newPlaylistFileUri).use {
                        PlaylistUtils.writeMediaPlaylist(oldPlaylist.copy(
                            initSegmentUri = oldPlaylist.initSegmentUri?.let { uri -> newVideoDirectoryUri + File.separator + Uri.decode(uri.substringAfterLast("%2F").substringAfterLast("/")) },
                            segments = segments
                        ), it)
                    }
                    val tracksToDelete = mutableListOf<String>()
                    oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                    val playlists = offlineVideosRepository.getPlaylists().mapNotNull { video ->
                        video.url?.takeIf {
                            it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                                    && it.substringBeforeLast("%2F") == oldVideoDirectoryUri
                                    && it != videoUrl
                        }
                    }
                    playlists.forEach { uri ->
                        try {
                            val p = applicationContext.contentResolver.openInputStream(uri.toUri())!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            p.segments.forEach { tracksToDelete.remove(Uri.decode(it.uri.substringAfterLast("%2F").substringAfterLast("/"))) }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    }
                    if (oldPlaylist.initSegmentUri != null) {
                        val oldFileName = oldPlaylist.initSegmentUri.substringAfterLast("%2F").substringAfterLast("/")
                        val oldFileUri = "$oldVideoDirectoryUri%2F$oldFileName"
                        val newFileUri = newVideoDirectoryUri + File.separator + Uri.decode(oldFileName)
                        FileOutputStream(newFileUri).use { outputStream ->
                            applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    offlineVideosRepository.update(video.apply {
                        maxProgress = tracksToDelete.count()
                    })
                    oldPlaylist.segments.forEach { track ->
                        val oldFileName = track.uri.substringAfterLast("%2F").substringAfterLast("/")
                        val oldFileUri = "$oldVideoDirectoryUri%2F$oldFileName"
                        val newFileUri = newVideoDirectoryUri + File.separator + Uri.decode(oldFileName)
                        FileOutputStream(newFileUri).use { outputStream ->
                            applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        if (tracksToDelete.contains(Uri.decode(oldFileName))) {
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, oldFileUri.toUri())
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e

                            }
                        }
                        offlineVideosRepository.update(video.apply {
                            progress += 1
                        })
                    }
                    val oldChatUri = video.chatUrl
                    val oldChatFileName = oldChatUri?.substringAfterLast("%2F")?.substringAfterLast("/")?.substringAfterLast("%3A")?.let { Uri.decode(it) }
                    val newChatFileUri = oldChatFileName?.let { path + File.separator + it }
                    if (oldChatUri != null && newChatFileUri != null) {
                        FileOutputStream(newChatFileUri).use { outputStream ->
                            applicationContext.contentResolver.openInputStream(oldChatUri.toUri())!!.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    offlineVideosRepository.update(video.apply {
                        thumbnail.let {
                            if (it == null || it == url || !File(it).exists()) {
                                thumbnail = newVideoDirectoryUri + File.separator + oldPlaylist.segments.getOrNull(
                                    max(0, (oldPlaylist.segments.size / 2) - 1)
                                )?.uri?.substringAfterLast("%2F")?.substringAfterLast("/")?.let { Uri.decode(it) }
                            }
                        }
                        url = newPlaylistFileUri
                        chatUrl = newChatFileUri
                    })
                    if (playlists.isNotEmpty()) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    } else {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldVideoDirectoryUri.toUri())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    }
                    if (oldChatUri != null) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldChatUri.toUri())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    }
                } else {
                    val oldFileName = Uri.decode(videoUrl.substringAfterLast("%2F").substringAfterLast("/").substringAfterLast("%3A"))
                    val newFileUri = path + File.separator + oldFileName
                    FileOutputStream(newFileUri).use { outputStream ->
                        applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    val oldChatUri = video.chatUrl
                    val oldChatFileName = oldChatUri?.substringAfterLast("%2F")?.substringAfterLast("/")?.substringAfterLast("%3A")?.let { Uri.decode(it) }
                    val newChatFileUri = oldChatFileName?.let { path + File.separator + it }
                    if (oldChatUri != null && newChatFileUri != null) {
                        FileOutputStream(newChatFileUri).use { outputStream ->
                            applicationContext.contentResolver.openInputStream(oldChatUri.toUri())!!.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    offlineVideosRepository.update(video.apply {
                        thumbnail.let {
                            if (it == null || it == url || !File(it).exists()) {
                                thumbnail = newFileUri
                            }
                        }
                        url = newFileUri
                        chatUrl = newChatFileUri
                    })
                    try {
                        DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e

                    }
                    if (oldChatUri != null) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldChatUri.toUri())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e

                        }
                    }
                }
            }.invokeOnCompletion { error ->
                videosInUse.remove(video.id)
                viewModelScope.launch(Dispatchers.IO) {
                    offlineVideosRepository.update(video.apply {
                        status = if (error == null) OfflineVideo.STATUS_DOWNLOADED else OfflineVideo.STATUS_FAILED
                    })
                }
            }
        }
    }

    fun updateChatUrl(newUri: Uri, video: OfflineVideo) {
        if (!videosInUse.contains(video.id)) {
            viewModelScope.launch(Dispatchers.IO) {
                var id: String? = null
                var title: String? = null
                var uploadDate: Long? = null
                var channelId: String? = null
                var channelLogin: String? = null
                var channelName: String? = null
                var gameId: String? = null
                var gameSlug: String? = null
                var gameName: String? = null
                try {
                    applicationContext.contentResolver.openInputStream(newUri)?.bufferedReader()?.use { fileReader ->
                        JsonReader(fileReader).use { reader ->
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "video" -> {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "id" -> id = reader.nextString()
                                                "title" -> title = reader.nextString()
                                                "uploadDate" -> uploadDate = reader.nextLong()
                                                "channelId" -> channelId = reader.nextString()
                                                "channelLogin" -> channelLogin = reader.nextString()
                                                "channelName" -> channelName = reader.nextString()
                                                "gameId" -> gameId = reader.nextString()
                                                "gameSlug" -> gameSlug = reader.nextString()
                                                "gameName" -> gameName = reader.nextString()
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                }
                offlineVideosRepository.update(video.apply {
                    if (!title.isNullOrBlank()) this.name = title
                    if (!channelId.isNullOrBlank()) this.channelId = channelId
                    if (!channelLogin.isNullOrBlank()) this.channelLogin = channelLogin
                    if (!channelName.isNullOrBlank()) this.channelName = channelName
                    if (!gameId.isNullOrBlank()) this.gameId = gameId
                    if (!gameSlug.isNullOrBlank()) this.gameSlug = gameSlug
                    if (!gameName.isNullOrBlank()) this.gameName = gameName
                    if (uploadDate != null) this.uploadDate = uploadDate
                    if (!id.isNullOrBlank()) this.videoId = id
                    chatUrl = newUri.toString()
                })
            }
        }
    }

    fun deleteSelected(ids: Set<String>, keepFiles: Boolean) {
        if (bulkDeleteJob?.isActive == true) return
        bulkDeleteJob = viewModelScope.launch {
            var failed = false
            try {
                for (id in ids) {
                    val videoId = id.toIntOrNull() ?: continue
                    if (!deleteById(videoId, keepFiles, reportFailure = false, eligibleOnly = true).await()) failed = true
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true
            }
            if (failed) _deleteFailed.value = true
        }
    }

    fun delete(video: OfflineVideo, keepFiles: Boolean, reportFailure: Boolean = true) =
        deleteById(video.id, keepFiles, reportFailure, eligibleOnly = false)

    private fun deleteById(id: Int, keepFiles: Boolean, reportFailure: Boolean, eligibleOnly: Boolean) = viewModelScope.async {
        offlineVideosRepository.deletionMutex.withLock {
            val video = try {
                offlineVideosRepository.getById(id) ?: return@withLock true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (reportFailure) _deleteFailed.value = true
                return@withLock false
            }
            if (eligibleOnly && !canSelectForDeletion(video)) return@withLock false
            if (!videosInUse.add(video.id)) {
                if (reportFailure) _deleteFailed.value = true
                return@withLock false
            }
            val previousStatus = video.status
            val previousProgress = video.progress
            val previousMaximum = video.maxProgress
            try {
                withContext(Dispatchers.IO) {
                    offlineVideosRepository.update(video.apply {
                        progress = 0
                        maxProgress = 100
                        status = OfflineVideo.STATUS_DELETING
                    })
                    if (!keepFiles) deleteMediaFiles(video)
                    // A joined delete includes the database removal before another playlist is processed.
                    offlineVideosRepository.delete(video)
                }
                true
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        offlineVideosRepository.update(video.apply {
                            status = previousStatus
                            progress = previousProgress
                            maxProgress = previousMaximum
                        })
                    } catch (_: Exception) { /* The original database row still represents the item. */ }
                }
                if (error is CancellationException) throw error
                if (reportFailure) _deleteFailed.value = true
                false
            } finally {
                videosInUse.remove(video.id)
            }
        }
    }

    private suspend fun deleteMediaFiles(video: OfflineVideo) {
        video.url?.let { url ->
            if (offlineVideosRepository.getMediaReferenceIds(url).none { it != video.id }) {
                if (url.toUri().scheme == ContentResolver.SCHEME_CONTENT) deleteDocumentMedia(video, url)
                else deleteFileMedia(video, localFile(url))
            }
        }
        video.chatUrl?.let { url ->
            if (offlineVideosRepository.getMediaReferenceIds(url).none { it != video.id }) {
                if (url.toUri().scheme == ContentResolver.SCHEME_CONTENT) deleteDocument(url.toUri())
                else deleteFile(localFile(url))
            }
        }
    }

    private suspend fun deleteDocumentMedia(video: OfflineVideo, url: String) {
        val uri = url.toUri()
        if (url.endsWith(".m3u8")) {
            val playlist = try {
                applicationContext.contentResolver.openInputStream(uri)?.use(PlaylistUtils::parseMediaPlaylist)
                    ?: throw FileNotFoundException("Missing download playlist")
            } catch (_: FileNotFoundException) {
                return
            }
            val tracks = playlist.segments.mapTo(linkedSetOf()) { it.uri }
            playlist.initSegmentUri?.let(tracks::add)
            val siblings = offlineVideosRepository.getPlaylists().mapNotNull {
                it.url?.takeIf { other -> other != url && other.toUri().scheme == ContentResolver.SCHEME_CONTENT }
            }
            for (other in siblings) {
                val shared = applicationContext.contentResolver.openInputStream(other.toUri())?.use(PlaylistUtils::parseMediaPlaylist)
                    ?: throw IOException("Unable to inspect shared download playlist")
                tracks.removeAll(shared.segments.map { it.uri }.toSet())
                shared.initSegmentUri?.let(tracks::remove)
            }
            deleteTracks(video, tracks) { deleteDocument(it.toUri()) }
        }
        deleteDocument(uri)
    }

    private suspend fun deleteFileMedia(video: OfflineVideo, file: File) {
        if (!file.exists()) return
        if (file.extension.equals("m3u8", true)) {
            val directory = file.parentFile ?: throw IOException("Missing playlist directory")
            val playlist = file.inputStream().use(PlaylistUtils::parseMediaPlaylist)
            val tracks = playlist.segments.mapTo(linkedSetOf()) { localFile(it.uri, directory) }
            playlist.initSegmentUri?.let { tracks.add(localFile(it, directory)) }
            val siblings = directory.listFiles { other -> other.extension.equals("m3u8", true) && other != file }
                ?: throw IOException("Unable to inspect shared download directory")
            for (other in siblings) {
                val shared = other.inputStream().use(PlaylistUtils::parseMediaPlaylist)
                tracks.removeAll(shared.segments.map { localFile(it.uri, directory) }.toSet())
                shared.initSegmentUri?.let { tracks.remove(localFile(it, directory)) }
            }
            deleteTracks(video, tracks, ::deleteFile)
            deleteFile(file)
            // Only remove an empty directory, never unrelated files beside an imported playlist.
            if (directory.listFiles()?.isEmpty() == true) deleteFile(directory)
        } else deleteFile(file)
    }

    private suspend fun <T> deleteTracks(video: OfflineVideo, tracks: Collection<T>, delete: (T) -> Unit) {
        val jobContext = currentCoroutineContext()
        offlineVideosRepository.update(video.apply { progress = 0; maxProgress = tracks.size.coerceAtLeast(1) })
        var lastWrite = SystemClock.elapsedRealtime()
        for (track in tracks) {
            jobContext.ensureActive()
            delete(track)
            video.progress += 1
            val now = SystemClock.elapsedRealtime()
            if (video.progress == tracks.size || now - lastWrite >= 250L) {
                offlineVideosRepository.update(video)
                lastWrite = now
            }
        }
    }

    private fun localFile(value: String, directory: File? = null): File {
        val uri = value.toUri()
        val file = when (uri.scheme) {
            null -> File(value)
            ContentResolver.SCHEME_FILE -> File(uri.path ?: throw IOException("Missing download path"))
            else -> throw IOException("Unsupported local download path")
        }
        return (if (file.isAbsolute || directory == null) file else File(directory, value)).canonicalFile
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Unable to delete download file")
    }

    private fun deleteDocument(uri: Uri) {
        try {
            if (!DocumentsContract.deleteDocument(applicationContext.contentResolver, uri)) {
                throw IOException("Unable to delete download document")
            }
        } catch (_: FileNotFoundException) { /* Already removed. */ }
    }

    companion object {
        fun canSelectForDeletion(video: OfflineVideo): Boolean =
            video.status == OfflineVideo.STATUS_DOWNLOADED || video.status == OfflineVideo.STATUS_FAILED

        val DownloadsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                DownloadsViewModel(application.applicationContext, xtraModule.offlineVideosRepository)
            }
        }
    }
}
