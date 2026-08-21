package com.github.andreyasadchy.xtra.ui.saved

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.util.findChildDocument
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.max

class SavedPagerViewModel(
    private val applicationContext: Context,
    private val offlineVideosRepository: OfflineVideosRepository,
) : ViewModel() {

    private fun openOutputStream(uri: Uri, mode: String = "w") =
        applicationContext.contentResolver.openOutputStream(uri, mode)
            ?: throw IOException("Unable to open saved playlist")

    private fun openInputStream(uri: Uri) =
        applicationContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open saved download")

    fun saveFolders(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val documentId = DocumentsContract.getTreeDocumentId(url.toUri())
            val directoryUri = DocumentsContract.buildDocumentUriUsingTree(url.toUri(), documentId)
            val directoryUris = mutableListOf<Uri>()
            val chatFiles = mutableMapOf<String, String>()
            applicationContext.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, documentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ), null, null, null
            ).use { cursor ->
                while (cursor?.moveToNext() == true) {
                    val documentId = cursor.getString(0)
                    val mimeType = cursor.getString(1)
                    val displayName = cursor.getString(2)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        directoryUris.add(DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId))
                    } else if (mimeType == "application/json" || displayName.endsWith(".json", true)) {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId)
                        if (displayName.endsWith(".json", true)) {
                            val fileName = displayName.removeSuffix(".json").removeSuffix("_chat")
                            chatFiles[fileName] = documentUri.toString()
                        }
                    }
                }
            }
            val playlistFileUris = mutableListOf<Uri>()
            directoryUris.forEach { directoryUri ->
                val directoryId = DocumentsContract.getDocumentId(directoryUri)
                applicationContext.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, directoryId),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null, null, null
                ).use { cursor ->
                    while (cursor?.moveToNext() == true) {
                        val documentId = cursor.getString(0)
                        val mimeType = cursor.getString(1)
                        val displayName = cursor.getString(2)
                        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR && displayName.endsWith(".m3u8", true)) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId)
                            playlistFileUris.add(documentUri)
                        }
                    }
                }
            }
            playlistFileUris.forEach { uri ->
                val existingVideo = offlineVideosRepository.getByUrl(uri.toString())
                if (existingVideo == null) {
                    val videoDirectoryPath = uri.toString().substringBeforeLast("%2F")
                    val videoDirectoryName = videoDirectoryPath.substringAfterLast("%2F").substringAfterLast("%3A")
                    val playlist = openInputStream(uri).use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    var totalDuration = 0L
                    val videoDirectoryUri = uri.toString().substringBeforeLast("%2F").toUri()
                    val segments = ArrayList<Segment>()
                    playlist.segments.forEach { segment ->
                        totalDuration += (segment.duration * 1000f).toLong()
                        val fileName = segment.uri.substringAfterLast("%2F").substringAfterLast("/")
                        segments.add(segment.copy(
                            uri = if (segment.uri.startsWith("content://")) {
                                segment.uri
                            } else {
                                applicationContext.contentResolver.findChildDocument(videoDirectoryUri, fileName, "application/octet-stream")?.toString() ?: segment.uri
                            }
                        ))
                    }
                    val initSegmentUri = playlist.initSegmentUri?.let { segmentUri ->
                        if (segmentUri.startsWith("content://")) {
                            segmentUri
                        } else {
                            val fileName = segmentUri.substringAfterLast("%2F").substringAfterLast("/")
                            applicationContext.contentResolver.findChildDocument(videoDirectoryUri, fileName, "application/octet-stream")?.toString() ?: segmentUri
                        }
                    }
                    openOutputStream(uri, "wt").use {
                        PlaylistUtils.writeMediaPlaylist(playlist.copy(
                            initSegmentUri = initSegmentUri,
                            segments = segments
                        ), it)
                    }
                    val chatFileUri = chatFiles[videoDirectoryName + uri.toString().substringAfterLast("%2F").removeSuffix(".m3u8")]
                    var id: String? = null
                    var title: String? = null
                    var uploadDate: Long? = null
                    var channelId: String? = null
                    var channelLogin: String? = null
                    var channelName: String? = null
                    var gameId: String? = null
                    var gameSlug: String? = null
                    var gameName: String? = null
                    chatFileUri?.let { chatFileUri ->
                        try {
                            openInputStream(chatFileUri.toUri()).bufferedReader().use { fileReader ->
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

                        }
                    }
                    offlineVideosRepository.save(OfflineVideo(
                        url = uri.toString(),
                        name = if (!title.isNullOrBlank()) title else Uri.decode(videoDirectoryName),
                        channelId = if (!channelId.isNullOrBlank()) channelId else null,
                        channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                        channelName = if (!channelName.isNullOrBlank()) channelName else null,
                        thumbnail = segments.getOrNull(max(0, (segments.size / 2) - 1))?.uri,
                        gameId = if (!gameId.isNullOrBlank()) gameId else null,
                        gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                        gameName = if (!gameName.isNullOrBlank()) gameName else null,
                        duration = totalDuration,
                        uploadDate = uploadDate,
                        progress = 100,
                        maxProgress = 100,
                        status = OfflineVideo.STATUS_DOWNLOADED,
                        videoId = if (!id.isNullOrBlank()) id else null,
                        chatUrl = chatFileUri
                    ))
                }
            }
        }
    }

    fun saveVideos(list: List<String>) {
        viewModelScope.launch {
            val chatFiles = mutableMapOf<String, String>()
            list.filter { it.endsWith(".json") }.forEach { url ->
                val fileName = url.substringAfterLast("%2F").substringAfterLast("%3A").removeSuffix(".json").removeSuffix("_chat")
                chatFiles[fileName] = url
            }
            list.filter { !it.endsWith(".json") }.forEach { url ->
                val existingVideo = offlineVideosRepository.getByUrl(url)
                if (existingVideo == null) {
                    val fileName = url.substringAfterLast("%2F").substringAfterLast("%3A").removeSuffix(".mp4").removeSuffix(".ts")
                    val chatFile = chatFiles[fileName]
                    var id: String? = null
                    var title: String? = null
                    var uploadDate: Long? = null
                    var channelId: String? = null
                    var channelLogin: String? = null
                    var channelName: String? = null
                    var gameId: String? = null
                    var gameSlug: String? = null
                    var gameName: String? = null
                    chatFile?.let { uri ->
                        try {
                            applicationContext.contentResolver.openInputStream(uri.toUri())?.bufferedReader()?.use { fileReader ->
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

                        }
                    }
                    offlineVideosRepository.save(
                        OfflineVideo(
                            url = url,
                            name = if (!title.isNullOrBlank()) title else Uri.decode(fileName),
                            channelId = if (!channelId.isNullOrBlank()) channelId else null,
                            channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                            channelName = if (!channelName.isNullOrBlank()) channelName else null,
                            thumbnail = url,
                            gameId = if (!gameId.isNullOrBlank()) gameId else null,
                            gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                            gameName = if (!gameName.isNullOrBlank()) gameName else null,
                            uploadDate = uploadDate,
                            progress = 100,
                            maxProgress = 100,
                            status = OfflineVideo.STATUS_DOWNLOADED,
                            videoId = if (!id.isNullOrBlank()) id else null,
                            chatUrl = chatFile
                        )
                    )
                }
            }
        }
    }

    companion object {
        val SavedPagerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SavedPagerViewModel(application.applicationContext, xtraModule.offlineVideosRepository)
            }
        }
    }
}
