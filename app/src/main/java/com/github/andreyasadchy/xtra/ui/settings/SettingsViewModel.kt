package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.provider.DocumentsContract
import android.util.Log
import android.util.JsonReader
import org.json.JSONObject
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
import com.github.andreyasadchy.xtra.repository.NotificationUserSyncResult
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationSchedulerResult
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationNotifier
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DatabaseRestoreRecovery
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.createOrFindDocument
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.proxyPrefs
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

private class RestoreRequiresRestart(cause: Throwable) : RuntimeException(cause)

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

    fun backupSettings(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val staging = File(applicationContext.cacheDir, "settings-backup-${UUID.randomUUID()}")
            try {
                check(staging.mkdirs()) { "Unable to create backup staging directory" }
                val preferences = preferencesFile()
                val stagedPreferences = File(staging, SettingsBackup.PREFERENCES_ENTRY)
                preferences.copyTo(stagedPreferences)
                val stagedProxy = File(staging, SettingsBackup.PROXY_ENTRY)
                val proxyPreferences = applicationContext.proxyPrefs()
                val rawPreferences = applicationContext.prefs()
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
                }

                appDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use {
                    it.moveToPosition(-1)
                }
                val database = applicationContext.getDatabasePath("database")
                val stagedDatabase = File(staging, SettingsBackup.DATABASE_ENTRY)
                appDatabase.runInTransaction {
                    database.copyTo(stagedDatabase)
                }

                val treeUri = url.toUri()
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                writeBackupDocument(directoryUri, SettingsBackup.ARCHIVE_FILE_NAME, "application/zip") { output ->
                    SettingsBackup.writeArchive(output, stagedPreferences, stagedDatabase, stagedProxy.takeIf(File::exists))
                }

                // Keep the legacy files for users and older Xtra versions that rely on them.
                writeBackupDocument(directoryUri, preferences.name, "application/xml") { output ->
                    stagedPreferences.inputStream().use { it.copyTo(output) }
                }
                writeBackupDocument(directoryUri, database.name, "application/vnd.sqlite3") { output ->
                    stagedDatabase.inputStream().use { it.copyTo(output) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Settings backup failed", e)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    fun restoreSettings(list: List<String>, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val staging = File(applicationContext.cacheDir, "settings-restore-${UUID.randomUUID()}")
            var databaseRestoreInstalled = false
            try {
                check(staging.mkdirs()) { "Unable to create restore staging directory" }
                val contents = stageRestoreInputs(list, staging)
                contents.preferences?.let(SettingsBackup::validatePreferences)
                contents.database?.let(::validateDatabaseBackup)
                contents.proxy?.let(SettingsBackup::validateProxyConfiguration)
                require(contents.preferences != null || contents.database != null) { "No Xtra backup files were selected" }

                installRestore(contents)
                databaseRestoreInstalled = contents.database != null
                contents.proxy?.let(::restoreProxyConfiguration)
                contents.preferences?.let {
                    // Keep the legacy restore side effects: migrate old preference
                    // keys, restore the notification baseline, and apply language
                    // before the activity is recreated.
                    SettingsMigration.migrate(applicationContext)
                    val restoredPreferences = preferencesFile().readText()
                    toggleNotifications(
                        restoredPreferences.contains("name=\"${C.LIVE_NOTIFICATIONS_ENABLED}\" value=\"true\""),
                        networkLibrary,
                        gqlHeaders,
                        helixHeaders,
                    )
                    val language = Regex("<string name=\"${C.UI_LANGUAGE}\">(.+?)</string>")
                        .find(restoredPreferences)?.groups?.get(1)?.value
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(language.takeIf { it != "auto" }),
                    )
                }
                applicationContext.startActivity(
                    Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
                staging.deleteRecursively()
                exitProcess(0)
            } catch (e: CancellationException) {
                if (databaseRestoreInstalled) {
                    staging.deleteRecursively()
                    exitProcess(1)
                }
                throw e
            } catch (e: RestoreRequiresRestart) {
                staging.deleteRecursively()
                exitProcess(1)
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Settings restore failed", e)
                if (databaseRestoreInstalled) {
                    // Room was closed before the file swap. Let the startup
                    // recovery path validate the replacement or roll it back.
                    staging.deleteRecursively()
                    exitProcess(1)
                }
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun preferencesFile() = File(
        "${applicationContext.applicationInfo.dataDir}/shared_prefs/${applicationContext.packageName}_preferences.xml",
    )

    private fun writeBackupDocument(directoryUri: Uri, name: String, mimeType: String, write: (java.io.OutputStream) -> Unit) {
        val uri = applicationContext.contentResolver.createOrFindDocument(directoryUri, mimeType, name)
        val output = applicationContext.contentResolver.openOutputStream(uri, "wt")
            ?: error("Unable to open $name")
        output.use(write)
    }

    private fun stageRestoreInputs(urls: List<String>, staging: File): SettingsBackup.Contents {
        var preferences: File? = null
        var database: File? = null
        var proxy: File? = null
        urls.forEachIndexed { index, url ->
            val inputFile = File(staging, "selected-$index")
            inputFile.outputStream().use { output ->
                applicationContext.contentResolver.openInputStream(url.toUri()).use { input ->
                    requireNotNull(input) { "Unable to open selected backup" }
                    SettingsBackup.copyLimited(input, output, 1024L * 1024L * 1024L)
                }
            }
            when (SettingsBackup.detectType(inputFile)) {
                SettingsBackup.FileType.ARCHIVE -> {
                    require(preferences == null && database == null) { "Select either one archive or the legacy backup files" }
                    val archive = SettingsBackup.extractArchive(inputFile.inputStream(), staging)
                    preferences = archive.preferences
                    database = archive.database
                    proxy = archive.proxy
                }
                SettingsBackup.FileType.PREFERENCES -> {
                    require(preferences == null) { "More than one preferences backup was selected" }
                    preferences = inputFile
                }
                SettingsBackup.FileType.DATABASE -> {
                    require(database == null) { "More than one database backup was selected" }
                    database = inputFile
                }
                SettingsBackup.FileType.UNKNOWN -> error("Selected file is not an Xtra backup")
            }
        }
        return SettingsBackup.Contents(preferences, database, proxy)
    }

    private fun restoreProxyConfiguration(file: File) {
        val json = JSONObject(file.readText())
        applicationContext.prefs().edit {
            if (json.has("enabled")) putBoolean(C.SETTINGS_HTTP_PROXY_ENABLED, json.getBoolean("enabled"))
            if (json.has("allowDirectFallback")) putBoolean(
                C.PROXY_ALLOW_DIRECT_FALLBACK,
                json.getBoolean("allowDirectFallback"),
            )
        }
        applicationContext.proxyPrefs().edit {
            // The password is intentionally never exported. Clear every old
            // credential before applying the backed-up non-secret fields so a
            // password for proxy A can never be sent to restored proxy B.
            remove(C.PROXY_HOST)
            remove(C.PROXY_PORT)
            remove(C.PROXY_USER)
            remove(C.PROXY_PASSWORD)
            if (json.has("host")) putString(C.PROXY_HOST, json.getString("host"))
            if (json.has("port")) putString(C.PROXY_PORT, json.getString("port"))
            if (json.has("user")) putString(C.PROXY_USER, json.getString("user"))
        }
    }

    private fun validateDatabaseBackup(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
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

    private fun installRestore(contents: SettingsBackup.Contents) {
        val replacements = buildList {
            contents.preferences?.let { add(preferencesFile() to it) }
            contents.database?.let { add(applicationContext.getDatabasePath("database") to it) }
        }
        val prepared = mutableListOf<Triple<File, File, File>>()
        var databaseClosed = false
        var restoreTransactionStarted = false
        try {
            val databaseTarget = applicationContext.getDatabasePath("database")
            val preferencesTarget = preferencesFile()
            // Persist the transaction before creating any staging files. The
            // recovery coordinator treats untracked .restore-new files as
            // orphaned artifacts, so the plan must exist first.
            DatabaseRestoreRecovery.begin(
                applicationContext,
                databaseSelected = contents.database != null,
                databaseExisted = databaseTarget.exists(),
                preferencesSelected = contents.preferences != null,
                preferencesExisted = preferencesTarget.exists(),
            )
            restoreTransactionStarted = true
            // Stage every replacement while Room is still usable. A disk-full
            // or SAF I/O failure here must not leave the singleton database
            // closed in the running process.
            replacements.forEach { (target, source) ->
                target.parentFile?.mkdirs()
                val next = File(target.parentFile, "${target.name}.restore-new")
                val previous = File(target.parentFile, "${target.name}.restore-old")
                next.delete()
                previous.delete()
                prepared += Triple(target, next, previous)
                source.copyTo(next)
            }
            if (contents.database != null) {
                appDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close()
                databaseClosed = true
                appDatabase.close()
            }
            DatabaseRestoreRecovery.markSwapping(applicationContext)
            prepared.forEach { (target, _, previous) ->
                if (target.exists()) check(target.renameTo(previous)) { "Unable to stage ${target.name}" }
            }
            prepared.forEach { (target, next, _) ->
                check(next.renameTo(target)) { "Unable to install ${target.name}" }
            }
            if (contents.database != null) {
                val database = databaseTarget
                File(database.parentFile, "database-shm").delete()
                File(database.parentFile, "database-wal").delete()
            }
            // Keep the previous files until the next process has opened the
            // restored files and validated the database through Room.
            DatabaseRestoreRecovery.markInstalled(applicationContext)
        } catch (e: Exception) {
            if (restoreTransactionStarted) {
                runCatching { DatabaseRestoreRecovery.rollback(applicationContext) }
                    .exceptionOrNull()
                    ?.let(e::addSuppressed)
            } else {
                prepared.asReversed().forEach { (target, next, previous) ->
                    next.delete()
                    if (previous.exists()) {
                        target.delete()
                        previous.renameTo(target)
                    }
                }
            }
            if (databaseClosed) {
                // The singleton cannot be reopened safely after close(). The
                // outer restore handler will clean staging and restart so the
                // startup recovery path can validate or roll back the swap.
                throw RestoreRequiresRestart(e)
            }
            throw e
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
                SettingsViewModel(application.applicationContext, xtraModule.playerRepository, xtraModule.offlineVideosRepository, xtraModule.recentSearchesRepository, xtraModule.notificationsRepository, xtraModule.database)
            }
        }
    }
}
