package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.net.http.HttpEngine
import android.provider.DocumentsContract
import android.util.Log
import android.util.JsonReader
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.sqlite.db.SimpleSQLiteQuery
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationSchedulerResult
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationNotifier
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.UpdateInfo
import com.github.andreyasadchy.xtra.util.UpdateState
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.system.exitProcess

class SettingsViewModel(
    private val applicationContext: Context,
    private val playerRepository: PlayerRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val recentSearchesRepository: RecentSearchesRepository,
    private val notificationsRepository: NotificationsRepository,
    private val appDatabase: AppDatabase,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) : ViewModel() {

    val updateInfo = MutableSharedFlow<UpdateInfo?>()
    val updateCheckFinished = MutableSharedFlow<Boolean>()
    var updateSize: Long? = null
    var updateJob: Job? = null
    private var checkingUpdates = false
    val updateProgress = MutableStateFlow(0L)
    val closeUpdateDialog = MutableSharedFlow<Boolean>()
    val updateDownloadFailed = MutableSharedFlow<Unit>()
    val liveNotificationResult = MutableSharedFlow<LiveNotificationResult>()

    fun deletePositions() {
        viewModelScope.launch {
            playerRepository.deleteVideoPositions()
            offlineVideosRepository.deletePositions()
        }
    }

    fun deleteRecentSearches() {
        viewModelScope.launch {
            recentSearchesRepository.deleteAll()
        }
    }

    fun resetNotificationState() {
        viewModelScope.launch(Dispatchers.IO) {
            notificationsRepository.clearPendingNotificationEvents()
            LiveNotificationScheduler.disable(applicationContext)
        }
    }

    fun importDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val chatFiles = mutableMapOf<String, String>()
            applicationContext.getExternalFilesDirs(".downloads").forEach { storage ->
                storage?.absolutePath?.let { directory ->
                    File(directory).listFiles()?.let { files ->
                        files.filter { it.name.endsWith(".json") }.forEach { chatFile ->
                            chatFiles[chatFile.name.removeSuffix(".json").removeSuffix("_chat")] = chatFile.path
                        }
                        files.filter { !it.name.endsWith(".json") }.forEach { file ->
                            if (file.isDirectory) {
                                file.listFiles()?.filter { it.name.endsWith(".m3u8") }?.forEach { playlistFile ->
                                        val existingVideo = offlineVideosRepository.getByUrl(playlistFile.path)
                                        if (existingVideo == null) {
                                            val playlist = FileInputStream(playlistFile).use {
                                                PlaylistUtils.parseMediaPlaylist(it)
                                            }
                                            var totalDuration = 0L
                                            val segments = ArrayList<Segment>()
                                            playlist.segments.forEach { segment ->
                                                totalDuration += (segment.duration * 1000f).toLong()
                                                segments.add(segment.copy(uri = segment.uri.substringAfterLast("%2F").substringAfterLast("/")))
                                            }
                                            FileOutputStream(playlistFile).use {
                                                PlaylistUtils.writeMediaPlaylist(playlist.copy(
                                                    initSegmentUri = playlist.initSegmentUri?.substringAfterLast("%2F")?.substringAfterLast("/"),
                                                    segments = segments
                                                ), it)
                                            }
                                            val chatFile = chatFiles[file.name + playlistFile.name.removeSuffix(".m3u8")]
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
                                                    FileInputStream(File(uri)).bufferedReader().use { fileReader ->
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
                                                    url = playlistFile.path,
                                                    name = if (!title.isNullOrBlank()) title else Uri.decode(file.name),
                                                    channelId = if (!channelId.isNullOrBlank()) channelId else null,
                                                    channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                                                    channelName = if (!channelName.isNullOrBlank()) channelName else null,
                                                    thumbnail = file.path + File.separator + segments.getOrNull(max(0, (segments.size / 2) - 1))?.uri,
                                                    gameId = if (!gameId.isNullOrBlank()) gameId else null,
                                                    gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                                                    gameName = if (!gameName.isNullOrBlank()) gameName else null,
                                                    duration = totalDuration,
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
                            } else if (file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))) {
                                val existingVideo = offlineVideosRepository.getByUrl(file.path)
                                if (existingVideo == null) {
                                    val fileName = file.name.removeSuffix(".mp4").removeSuffix(".ts")
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
                                            FileInputStream(File(uri)).bufferedReader().use { fileReader ->
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
                                            url = file.path,
                                            name = if (!title.isNullOrBlank()) title else Uri.decode(fileName),
                                            channelId = if (!channelId.isNullOrBlank()) channelId else null,
                                            channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                                            channelName = if (!channelName.isNullOrBlank()) channelName else null,
                                            thumbnail = file.path,
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
                }
            }
        }
    }

    fun checkUpdates(networkLibrary: String?, url: String, notifyNoUpdates: Boolean = false) {
        if (checkingUpdates) return
        checkingUpdates = true
        UpdateState.markAttempted(applicationContext)
        viewModelScope.launch(Dispatchers.IO) {
            var responseSucceeded = false
            try {
                val response = when {
                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.HttpEngineTimeout()
                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                url,
                                cronetExecutor.value,
                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (response.info.httpStatusCode !in 200..299) {
                            throw IOException("Update check failed with HTTP ${response.info.httpStatusCode}")
                        }
                        json.decodeFromString<JsonObject>(response.body.decodeToString())
                    }
                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.CronetTimeout()
                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                url,
                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                cronetExecutor.value
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (response.info.httpStatusCode !in 200..299) {
                            throw IOException("Update check failed with HTTP ${response.info.httpStatusCode}")
                        }
                        json.decodeFromString<JsonObject>(response.body.decodeToString())
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("Update check failed with HTTP ${response.code}")
                            }
                            json.decodeFromString<JsonObject>(response.body.string())
                        }
                    }
                }
                responseSucceeded = true
                UpdateState.markChecked(applicationContext)
                val info = UpdateState.fromResponse(response, url)?.takeIf {
                    UpdateState.isNewerThanInstalled(it.version)
                }
                if (info != null) {
                    updateSize = info.size
                    UpdateState.save(applicationContext, info)
                } else {
                    updateSize = null
                    UpdateState.clear(applicationContext)
                }
            } catch (_: Exception) {
                // Keep the last known release when a scheduled check cannot reach GitHub.
            } finally {
                val visible = UpdateState.isPending(applicationContext)
                updateInfo.emit(if (responseSucceeded && visible) UpdateState.read(applicationContext) else null)
                if (notifyNoUpdates && responseSucceeded) {
                    updateCheckFinished.emit(responseSucceeded && visible)
                }
                checkingUpdates = false
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun downloadUpdate(networkLibrary: String?, info: UpdateInfo) {
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // APK downloads use OkHttp's streaming body regardless of the
                // selected Twitch API transport, so the whole package is never
                // buffered in memory by the updater.
                updateProgress.value = 0L
                val packageInstaller = applicationContext.packageManager.packageInstaller
                val sessionId = packageInstaller.createSession(
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                )
                val session = packageInstaller.openSession(sessionId)
                try {
                    var bytesWritten = 0L
                    okHttpClient.value.newCall(Request.Builder().url(info.downloadUrl).build()).executeAsync().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Update download failed with HTTP ${response.code}")
                        }
                        val body = response.body
                        val length = body.contentLength()
                        body.byteStream().use { input ->
                            session.openWrite("package", 0, length).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    bytesWritten += read
                                    updateProgress.value = bytesWritten
                                }
                            }
                        }
                    }
                    if (bytesWritten <= 0L) {
                        throw IOException("Update download returned an empty APK")
                    }
                    session.commit(
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            Intent(applicationContext, MainActivity::class.java).apply {
                                setAction(MainActivity.INTENT_INSTALL_UPDATE)
                                putExtra(MainActivity.EXTRA_UPDATE_VERSION, info.version)
                            },
                            PendingIntent.FLAG_MUTABLE
                        ).intentSender
                    )
                } catch (e: Exception) {
                    runCatching { session.abandon() }
                    throw e
                } finally {
                    session.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateDownloadFailed.emit(Unit)
            } finally {
                closeUpdateDialog.emit(true)
            }
        }
    }

    fun backupSettings(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val documentId = DocumentsContract.getTreeDocumentId(url.toUri())
            val directoryUri = DocumentsContract.buildDocumentUriUsingTree(url.toUri(), documentId)
            val preferences = File("${applicationContext.applicationInfo.dataDir}/shared_prefs/${applicationContext.packageName}_preferences.xml")
            val preferencesUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + preferences.name
            try {
                applicationContext.contentResolver.openOutputStream(preferencesUri.toUri())!!
            } catch (e: IllegalArgumentException) {
                DocumentsContract.createDocument(applicationContext.contentResolver, directoryUri, "", preferences.name)
                applicationContext.contentResolver.openOutputStream(preferencesUri.toUri())!!
            }.use { outputStream ->
                preferences.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            appDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use {
                it.moveToPosition(-1)
            }
            val database = applicationContext.getDatabasePath("database")
            val databaseUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + database.name
            try {
                applicationContext.contentResolver.openOutputStream(databaseUri.toUri())!!
            } catch (e: IllegalArgumentException) {
                DocumentsContract.createDocument(applicationContext.contentResolver, directoryUri, "", database.name)
                applicationContext.contentResolver.openOutputStream(databaseUri.toUri())!!
            }.use { outputStream ->
                database.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    fun restoreSettings(list: List<String>, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            var restoredSettings = false
            list.take(2).forEach { url ->
                if (url.endsWith(".xml")) {
                    FileOutputStream("${applicationContext.applicationInfo.dataDir}/shared_prefs/${applicationContext.packageName}_preferences.xml").use { outputStream ->
                        applicationContext.contentResolver.openInputStream(url.toUri())!!.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    SettingsMigration.migrate(applicationContext)
                    restoredSettings = true
                    val prefs = applicationContext.contentResolver.openInputStream(url.toUri())!!.bufferedReader().use {
                        it.readText()
                    }
                    toggleNotifications(prefs.contains("name=\"${C.LIVE_NOTIFICATIONS_ENABLED}\" value=\"true\""), networkLibrary, gqlHeaders, helixHeaders)
                    val language = Regex("<string name=\"${C.UI_LANGUAGE}\">(.+?)</string>").find(prefs)?.groups?.get(1)?.value
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.takeIf { it != "auto" }))
                } else {
                    val database = applicationContext.getDatabasePath("database")
                    File(database.parent, "database-shm").delete()
                    File(database.parent, "database-wal").delete()
                    database.outputStream().use { outputStream ->
                        applicationContext.contentResolver.openInputStream(url.toUri())!!.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    applicationContext.startActivity(
                        Intent(applicationContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    exitProcess(0)
                }
            }
            if (restoredSettings) {
                applicationContext.startActivity(
                    Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
                exitProcess(0)
            }
        }
    }

    fun toggleNotifications(enabled: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!enabled) {
                applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                notificationsRepository.clearPendingNotificationEvents()
                LiveNotificationScheduler.disable(applicationContext)
                liveNotificationResult.emit(LiveNotificationResult(enabled = false))
                return@launch
            }

            applicationContext.prefs().edit {
                putLong(C.LIVE_NOTIFICATION_LAST_SETUP_ATTEMPT, System.currentTimeMillis())
            }
            val notificationBlockReason = try {
                LiveNotificationNotifier(applicationContext).notificationBlockReason()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = LiveNotificationFailureClassifier.classify(
                    LiveNotificationSetupStage.NOTIFICATION_PERMISSION_CHANNEL_VALIDATION,
                    e,
                )
                applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                disableSchedulerAfterEnableFailure()
                emitLiveNotificationFailure(failure, e)
                return@launch
            }
            if (notificationBlockReason != null) {
                val failure = LiveNotificationFailure(
                    stage = LiveNotificationSetupStage.NOTIFICATION_PERMISSION_CHANNEL_VALIDATION,
                    reason = LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL,
                    technicalMessage = notificationBlockReason.name,
                    exceptionClass = "NotificationBlockReason",
                )
                applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                disableSchedulerAfterEnableFailure()
                emitLiveNotificationFailure(failure)
                return@launch
            }

            val useLocalFollows = (applicationContext.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0) != 0
            var apiUsed = "none"
            var cachedChannelCount = 0
            var setupFailure: LiveNotificationFailure? = null
            var setupError: Throwable? = null
            var stage = LiveNotificationSetupStage.NOTIFICATION_USER_FOLLOW_SYNC
            try {
                notificationsRepository.clearPendingNotificationEvents()
                if (!useLocalFollows) {
                    applicationContext.prefs().edit {
                        putLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, System.currentTimeMillis())
                    }
                    notificationsRepository.syncNotificationUsers(networkLibrary, gqlHeaders)
                    applicationContext.prefs().edit {
                        putLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, System.currentTimeMillis())
                    }
                }
                stage = LiveNotificationSetupStage.INITIAL_LIVE_STREAM_BASELINE_FETCH
                notificationsRepository.validateLiveNotificationBaselineAuthentication(
                    gqlHeaders = gqlHeaders,
                    helixHeaders = helixHeaders,
                )
                notificationsRepository.getNewStreams(
                    networkLibrary = networkLibrary,
                    gqlHeaders = gqlHeaders,
                    helixHeaders = helixHeaders,
                    includeFollowedStreams = !useLocalFollows,
                    preferHelix = true,
                    onApiUsed = { apiUsed = it },
                )
                cachedChannelCount = notificationsRepository.getNotificationUserIds().size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setupFailure = LiveNotificationFailureClassifier.classify(stage, e)
                setupError = e
            }

            val failure = setupFailure
            if (failure != null) {
                applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                disableSchedulerAfterEnableFailure()
                emitLiveNotificationFailure(failure, setupError)
                return@launch
            }

            applicationContext.prefs().edit {
                putString(C.LIVE_NOTIFICATION_LAST_SETUP_API, apiUsed)
                putInt(C.LIVE_NOTIFICATION_CACHED_CHANNEL_COUNT, cachedChannelCount)
                putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, true)
                putBoolean(C.LIVE_NOTIFICATION_BASELINE_INITIALIZED, true)
            }
            stage = LiveNotificationSetupStage.SCHEDULER_REALTIME_MONITOR_STARTUP
            when (val schedulerResult = LiveNotificationScheduler.enable(applicationContext, baselineOnly = true)) {
                LiveNotificationSchedulerResult.Started -> Unit
                is LiveNotificationSchedulerResult.Blocked -> {
                    val failure = LiveNotificationFailure(
                        stage = stage,
                        reason = LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL,
                        technicalMessage = schedulerResult.reason.name,
                        exceptionClass = "NotificationBlockReason",
                    )
                    applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                    disableSchedulerAfterEnableFailure()
                    emitLiveNotificationFailure(failure)
                    return@launch
                }
                is LiveNotificationSchedulerResult.Failed -> {
                    val failure = LiveNotificationFailureClassifier.classify(stage, schedulerResult.error)
                    applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                    disableSchedulerAfterEnableFailure()
                    emitLiveNotificationFailure(failure, schedulerResult.error)
                    return@launch
                }
                LiveNotificationSchedulerResult.NotEnabled -> {
                    val error = IllegalStateException("Live notification scheduler was not enabled")
                    val failure = LiveNotificationFailureClassifier.classify(stage, error)
                    applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                    disableSchedulerAfterEnableFailure()
                    emitLiveNotificationFailure(failure, error)
                    return@launch
                }
            }

            applicationContext.prefs().edit {
                putLong(C.LIVE_NOTIFICATION_LAST_SETUP_SUCCESS, System.currentTimeMillis())
            }
            liveNotificationResult.emit(LiveNotificationResult(enabled = true))
        }
    }

    fun reportLiveNotificationPermissionDenied() {
        viewModelScope.launch(Dispatchers.IO) {
            val failure = LiveNotificationFailure(
                stage = LiveNotificationSetupStage.NOTIFICATION_PERMISSION_CHANNEL_VALIDATION,
                reason = LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL,
                technicalMessage = "POST_NOTIFICATIONS permission was not granted",
                exceptionClass = "PermissionDenied",
            )
            applicationContext.prefs().edit {
                putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
                putLong(C.LIVE_NOTIFICATION_LAST_SETUP_ATTEMPT, System.currentTimeMillis())
            }
            disableSchedulerAfterEnableFailure()
            emitLiveNotificationFailure(failure)
        }
    }

    private suspend fun emitLiveNotificationFailure(failure: LiveNotificationFailure, error: Throwable? = null) {
        val now = System.currentTimeMillis()
        val diagnosticMessage = listOfNotNull(failure.exceptionClass, failure.technicalMessage)
            .joinToString(": ")
            .let(::sanitizeLiveNotificationTechnicalMessage)
        applicationContext.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_SETUP_ERROR_AT, now)
            putString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_STAGE, failure.stage.name)
            putString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_REASON, failure.reason.name)
            val operation = sanitizeLiveNotificationTechnicalMessage(failure.operation)
            if (operation != null) {
                putString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_OPERATION, operation)
            } else {
                remove(C.LIVE_NOTIFICATION_ENABLE_FAILURE_OPERATION)
            }
            if (failure.httpStatus != null) {
                putInt(C.LIVE_NOTIFICATION_ENABLE_FAILURE_STATUS, failure.httpStatus)
            } else {
                remove(C.LIVE_NOTIFICATION_ENABLE_FAILURE_STATUS)
            }
            putString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_EXCEPTION, failure.exceptionClass)
            putString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_MESSAGE, diagnosticMessage)
        }
        if (error != null) {
            Log.e(TAG, "Live notification setup failed at ${failure.stage}: ${failure.reason}", error)
        } else {
            Log.w(TAG, "Live notification setup failed at ${failure.stage}: ${failure.reason}")
        }
        liveNotificationResult.emit(LiveNotificationResult(enabled = false, failure = failure))
    }

    private fun disableSchedulerAfterEnableFailure() {
        runCatching { LiveNotificationScheduler.disable(applicationContext) }
            .onFailure { Log.w(TAG, "Unable to roll back live notification scheduler after setup failure", it) }
    }

    data class LiveNotificationResult(val enabled: Boolean, val failure: LiveNotificationFailure? = null)

    companion object {
        private const val TAG = "SettingsViewModel"

        val SettingsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SettingsViewModel(application.applicationContext, xtraModule.playerRepository, xtraModule.offlineVideosRepository, xtraModule.recentSearchesRepository, xtraModule.notificationsRepository, xtraModule.database, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.json)
            }
        }
    }
}
