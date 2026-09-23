package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.provider.DocumentsContract
import android.util.Log
import android.util.JsonReader
import org.json.JSONObject
import androidx.core.content.edit
import androidx.core.net.toUri
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
import com.github.andreyasadchy.xtra.repository.NotificationUserSyncResult
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationSchedulerResult
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationNotifier
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.createOrFindDocument
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.proxyPrefs
import com.github.andreyasadchy.xtra.util.rawPrefs
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.system.exitProcess

internal fun initialNotificationBaselineIncludesFollowedStreams(): Boolean = false

class SettingsViewModel(
    private val applicationContext: Context,
    private val playerRepository: PlayerRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val recentSearchesRepository: RecentSearchesRepository,
    private val notificationsRepository: NotificationsRepository,
    private val appDatabase: AppDatabase,
) : ViewModel() {

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
            LiveNotificationScheduler.refresh(applicationContext)
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

    sealed interface SettingsOperationResult {
        data object BackupCompleted : SettingsOperationResult
        data object RestoreStaged : SettingsOperationResult
        data class Failed(val action: String, val reason: String) : SettingsOperationResult
    }

    val settingsOperationResult = MutableSharedFlow<SettingsOperationResult>(extraBufferCapacity = 1)

    fun backupSettings(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val staging = File(applicationContext.cacheDir, "settings-backup-${UUID.randomUUID()}")
            try {
                check(staging.mkdirs()) { "Unable to create backup staging directory" }
                val proxyPreferences = applicationContext.proxyPrefs()
                val rawPreferences = applicationContext.rawPrefs()
                val stagedProxy = File(staging, SettingsBackup.PROXY_ENTRY)
                if (
                    !proxyPreferences.getString(C.PROXY_HOST, null).isNullOrBlank() ||
                    !proxyPreferences.getString(C.PROXY_PORT, null).isNullOrBlank() ||
                    !proxyPreferences.getString(C.PROXY_USER, null).isNullOrBlank() ||
                    rawPreferences.contains(C.SETTINGS_HTTP_PROXY_ENABLED) ||
                    rawPreferences.contains(C.PROXY_ALLOW_DIRECT_FALLBACK)
                ) {
                    stagedProxy.writeText(JSONObject().apply {
                        put("enabled", rawPreferences.getBoolean(C.SETTINGS_HTTP_PROXY_ENABLED, false))
                        put("allowDirectFallback", rawPreferences.getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
                        proxyPreferences.getString(C.PROXY_HOST, null)?.let { put("host", it) }
                        proxyPreferences.getString(C.PROXY_PORT, null)?.let { put("port", it) }
                        proxyPreferences.getString(C.PROXY_USER, null)?.let { put("user", it) }
                    }.toString())
                    SettingsBackup.validateProxyConfiguration(stagedProxy)
                }

                appDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close()
                val database = applicationContext.getDatabasePath("database")
                val stagedDatabase = File(staging, SettingsBackup.DATABASE_ENTRY)
                appDatabase.runInTransaction { database.copyTo(stagedDatabase) }
                val databaseVersion = appDatabase.openHelper.readableDatabase.version

                val treeUri = url.toUri()
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                val uri = applicationContext.contentResolver.createOrFindDocument(
                    directoryUri,
                    "application/zip",
                    SettingsBackup.ARCHIVE_FILE_NAME,
                )
                val output = applicationContext.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Unable to open the backup archive")
                output.use {
                    SettingsBackup.writeArchive(
                        output = it,
                        preferences = rawPreferences.all,
                        settingsSchemaVersion = C.SETTINGS_SCHEMA_VERSION,
                        database = stagedDatabase,
                        databaseSchemaVersion = databaseVersion,
                        proxy = stagedProxy.takeIf(File::exists),
                        appVersionCode = com.github.andreyasadchy.xtra.BuildConfig.VERSION_CODE,
                    )
                }
                settingsOperationResult.emit(SettingsOperationResult.BackupCompleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Settings backup failed", e)
                settingsOperationResult.emit(SettingsOperationResult.Failed("backup", e.message ?: "Unknown error"))
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    fun restoreSettings(list: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val staging = File(applicationContext.cacheDir, "settings-restore-${UUID.randomUUID()}")
            try {
                check(staging.mkdirs()) { "Unable to create restore staging directory" }
                val contents = stageRestoreInputs(list, staging)
                contents.preferences?.let(SettingsBackup::validatePreferences)
                contents.settings?.let {
                    SettingsBackup.validateTypedPreferences(
                        it,
                        requireNotNull(contents.settingsSchemaVersion),
                    )
                }
                contents.database?.let { validateDatabaseBackup(it, contents.databaseSchemaVersion) }
                contents.proxy?.let(SettingsBackup::validateProxyConfiguration)

                val importedPreferences = when {
                    contents.settings != null -> SettingsBackup.readTypedPreferences(contents.settings)
                    contents.preferences != null -> SettingsBackup.readLegacyPreferences(contents.preferences)
                    else -> null
                }
                val settingsSchemaVersion = contents.settingsSchemaVersion
                    ?: importedPreferences?.let(SettingsBackup::legacySettingsSchemaVersion)
                    ?: 0
                require(settingsSchemaVersion <= C.SETTINGS_SCHEMA_VERSION) {
                    "This backup uses settings schema $settingsSchemaVersion; this Xtra version supports up to ${C.SETTINGS_SCHEMA_VERSION}. Update Xtra before restoring it."
                }
                val preferenceValues = importedPreferences?.filterKeys { it != C.SETTINGS_VERSION }
                val proxy = contents.proxy ?: preferenceValues?.let { extractLegacyProxyConfiguration(it, staging) }
                SettingsRestoreCoordinator.stage(
                    context = applicationContext,
                    settings = preferenceValues,
                    settingsSchemaVersion = settingsSchemaVersion,
                    database = contents.database,
                    proxy = proxy,
                    ignoredLooseFiles = contents.ignoredLooseFiles,
                )
                settingsOperationResult.emit(SettingsOperationResult.RestoreStaged)
                staging.deleteRecursively()
                applicationContext.startActivity(
                    Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                )
                exitProcess(0)
            } catch (e: CancellationException) {
                SettingsRestoreCoordinator.discardStaged(applicationContext)
                throw e
            } catch (e: Exception) {
                SettingsRestoreCoordinator.discardStaged(applicationContext)
                Log.e("SettingsViewModel", "Settings restore failed", e)
                settingsOperationResult.emit(SettingsOperationResult.Failed("restore", e.message ?: "Unknown error"))
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun stageRestoreInputs(urls: List<String>, staging: File): SettingsBackup.Contents {
        require(urls.isNotEmpty()) { "No backup files were selected" }
        require(urls.size <= 4) { "Select one archive or the matching legacy backup files" }
        val files = urls.mapIndexed { index, url ->
            val inputFile = File(staging, "selected-$index")
            inputFile.outputStream().use { output ->
                applicationContext.contentResolver.openInputStream(url.toUri()).use { input ->
                    requireNotNull(input) { "Unable to open a selected backup file" }
                    SettingsBackup.copyLimited(input, output, 1024L * 1024L * 1024L)
                }
            }
            val type = SettingsBackup.detectType(inputFile)
            if (type == SettingsBackup.FileType.PREFERENCES) {
                require(inputFile.length() <= SettingsBackup.MAX_SETTINGS_BYTES) { "Preferences backup is too large" }
            }
            inputFile to type
        }
        val archives = files.filter { it.second == SettingsBackup.FileType.ARCHIVE }
        require(archives.size <= 1) { "Select only one backup archive" }
        if (archives.isNotEmpty()) {
            val archive = SettingsBackup.extractArchive(archives.single().first.inputStream(), staging)
            require(files.none { it.second == SettingsBackup.FileType.UNKNOWN }) {
                "A selected file is not part of an Xtra backup"
            }
            return archive.copy(ignoredLooseFiles = files.size > 1)
        }

        var preferences: File? = null
        var database: File? = null
        files.forEach { (file, type) ->
            when (type) {
                SettingsBackup.FileType.ARCHIVE -> error("Unexpected backup archive")
                SettingsBackup.FileType.PREFERENCES -> {
                    require(preferences == null) { "More than one preferences backup was selected" }
                    preferences = file
                }
                SettingsBackup.FileType.DATABASE -> {
                    require(database == null) { "More than one database backup was selected" }
                    database = file
                }
                SettingsBackup.FileType.UNKNOWN -> error("A selected file is not an Xtra backup")
            }
        }
        require(preferences != null || database != null) { "No Xtra backup files were selected" }
        return SettingsBackup.Contents(
            preferences = preferences,
            settings = null,
            database = database,
            formatVersion = 0,
        )
    }

    private fun extractLegacyProxyConfiguration(values: Map<String, Any>, staging: File): File? {
        val proxyValues = mapOf(
            "host" to C.PROXY_HOST,
            "port" to C.PROXY_PORT,
            "user" to C.PROXY_USER,
        ).mapNotNull { (jsonKey, preferenceKey) -> values[preferenceKey]?.let { jsonKey to it } }
        val hasProxySettings = proxyValues.isNotEmpty() ||
            values.containsKey(C.SETTINGS_HTTP_PROXY_ENABLED) ||
            values.containsKey(C.PROXY_ALLOW_DIRECT_FALLBACK)
        if (!hasProxySettings) return null
        return File(staging, "${SettingsBackup.PROXY_ENTRY}-legacy").apply {
            writeText(JSONObject().apply {
                put("enabled", (values[C.SETTINGS_HTTP_PROXY_ENABLED] as? Boolean) ?: false)
                put("allowDirectFallback", (values[C.PROXY_ALLOW_DIRECT_FALLBACK] as? Boolean) ?: true)
                proxyValues.forEach { (key, value) -> put(key, value.toString()) }
            }.toString())
            SettingsBackup.validateProxyConfiguration(this)
        }
    }

    private fun validateDatabaseBackup(file: File, expectedSchemaVersion: Int? = null) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            expectedSchemaVersion?.let {
                require(database.version == it) { "Backup database version does not match its manifest" }
            }
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Database backup failed its integrity check"
                }
            }
            require(database.version in 1..AppDatabase.VERSION) { "Database backup is from a newer or unsupported Xtra version" }
            if (database.version == AppDatabase.VERSION) {
                database.rawQuery(
                    "SELECT identity_hash FROM room_master_table WHERE id = 42",
                    null,
                ).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0) == AppDatabase.IDENTITY_HASH) {
                        "Database backup does not match the current Xtra schema"
                    }
                }
            }
        }
    }
    fun toggleNotifications(enabled: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!enabled) {
                applicationContext.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                notificationsRepository.clearPendingNotificationEvents()
                LiveNotificationScheduler.refresh(applicationContext)
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
                    val syncResult = notificationsRepository.syncNotificationUsers(
                        networkLibrary = networkLibrary,
                        gqlHeaders = gqlHeaders,
                        helixHeaders = helixHeaders,
                        userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
                    )
                    if (syncResult == NotificationUserSyncResult.SUCCESS) {
                        applicationContext.prefs().edit {
                            putLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, System.currentTimeMillis())
                        }
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
                    // syncNotificationUsers() already populated the authoritative followed
                    // channel set. Do not make setup depend on the optional private GQL
                    // followed-live query, especially when it returns no live channels.
                    includeFollowedStreams = initialNotificationBaselineIncludesFollowedStreams(),
                    preferHelix = gqlHeaders[C.HEADER_TOKEN].isNullOrBlank(),
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
        runCatching { LiveNotificationScheduler.refresh(applicationContext) }
            .onFailure { Log.w(TAG, "Unable to roll back live notification scheduler after setup failure", it) }
    }

    data class LiveNotificationResult(val enabled: Boolean, val failure: LiveNotificationFailure? = null)

    companion object {
        private const val TAG = "SettingsViewModel"

        val SettingsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SettingsViewModel(application.applicationContext, xtraModule.playerRepository, xtraModule.offlineVideosRepository, xtraModule.recentSearchesRepository, xtraModule.notificationsRepository, xtraModule.database)
            }
        }
    }
}
