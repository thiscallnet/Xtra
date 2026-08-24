package com.github.andreyasadchy.xtra

import android.app.Application
import android.net.Uri
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.room.migration.Migration
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.db.MetadataCacheMigrations
import com.github.andreyasadchy.xtra.db.StreamFeedMigrations
import com.github.andreyasadchy.xtra.db.GameFeedMigrations
import com.github.andreyasadchy.xtra.db.ViewingStatsMigrations
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.ChannelSortRepository
import com.github.andreyasadchy.xtra.repository.GameSortRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.LocalGameFollowsRepository
import com.github.andreyasadchy.xtra.repository.MetadataCache
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
import com.github.andreyasadchy.xtra.repository.preload.StreamMedia3Runtime
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.SavedFiltersRepository
import com.github.andreyasadchy.xtra.repository.ViewingStatsRepository
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionMaintainer
import com.github.andreyasadchy.xtra.repository.auth.TwitchWebSessionManager
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedPager
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedCache
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedPager
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.ui.common.StreamPreviewCoordinator
import com.github.andreyasadchy.xtra.ui.player.PlaybackPersistence
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRecorder
import com.github.andreyasadchy.xtra.util.updater.ReleaseClient
import com.github.andreyasadchy.xtra.util.updater.UpdateRepository
import com.github.andreyasadchy.xtra.util.DatabaseRestoreRecovery
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import org.chromium.net.RequestFinishedInfo
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@OptIn(UnstableApi::class)
class XtraModule(application: Application) {

    val playbackPersistence by lazy {
        PlaybackPersistence(this)
    }

    val streamFeedCache by lazy {
        StreamFeedCache(database)
    }

    val streamFeedRefreshCoordinator by lazy {
        StreamFeedRefreshCoordinator(streamFeedCache)
    }

    val streamFeedPager by lazy {
        StreamFeedPager(streamFeedCache, streamFeedRefreshCoordinator)
    }

    val gameFeedCache by lazy {
        GameFeedCache(database)
    }

    val gameFeedRefreshCoordinator by lazy {
        GameFeedRefreshCoordinator(gameFeedCache)
    }

    val gameFeedPager by lazy {
        GameFeedPager(gameFeedCache, gameFeedRefreshCoordinator)
    }

    val streamMedia3Runtime by lazy {
        StreamMedia3Runtime(application, this)
    }

    private val streamPreloadCoordinatorLazy = lazy {
        StreamPreloadCoordinator(
            context = application,
            playerRepository = playerRepository,
            streamFeedRefreshCoordinator = streamFeedRefreshCoordinator,
            mediaPreloadRuntime = streamMedia3Runtime,
        )
    }

    val streamPreloadCoordinator by streamPreloadCoordinatorLazy

    private val streamPreviewCoordinatorLazy = lazy {
        StreamPreviewCoordinator(
            context = application,
            mediaRuntime = streamMedia3Runtime,
            urlCoordinator = streamPreloadCoordinator,
            streamFeedRefreshCoordinator = streamFeedRefreshCoordinator,
        )
    }

    val streamPreviewCoordinator by streamPreviewCoordinatorLazy

    fun onStreamPreloadAppForeground() {
        if (streamPreloadCoordinatorLazy.isInitialized()) streamPreloadCoordinator.onAppForeground()
        if (streamPreviewCoordinatorLazy.isInitialized()) streamPreviewCoordinator.onAppForeground()
    }

    fun onStreamPreloadAppBackground() {
        if (streamPreloadCoordinatorLazy.isInitialized()) streamPreloadCoordinator.onAppBackground()
        if (streamPreviewCoordinatorLazy.isInitialized()) streamPreviewCoordinator.onAppBackground()
    }

    val metadataCache by lazy {
        MetadataCache(database, json)
    }

    val httpEngine = lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
            HttpEngine.Builder(application).apply {
                addQuicHint("gql.twitch.tv", 443, 443)
                addQuicHint("www.twitch.tv", 443, 443)
                addQuicHint("7tv.io", 443, 443)
                addQuicHint("cdn.7tv.app", 443, 443)
                addQuicHint("api.betterttv.net", 443, 443)
            }.build()
        } else {
            null
        }
    }

    val cronetExecutor = lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "xtra-cronet").apply {
                isDaemon = true
            }
        }
    }

    val cronetEngine = lazy {
        if (CronetProvider.getAllProviders(application).any { it.isEnabled }) {
            CronetEngine.Builder(application).apply {
                val userAgent = "Cronet/" + defaultUserAgent.substringAfter("Cronet/", "").substringBefore(')')
                setUserAgent(userAgent)
                @QuicOptions.Experimental
                setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
                addQuicHint("gql.twitch.tv", 443, 443)
                addQuicHint("www.twitch.tv", 443, 443)
                addQuicHint("7tv.io", 443, 443)
                addQuicHint("cdn.7tv.app", 443, 443)
                addQuicHint("api.betterttv.net", 443, 443)
            }.build().also {
                if (BuildConfig.DEBUG) {
                    it.addRequestFinishedListener(object : RequestFinishedInfo.Listener(cronetExecutor.value) {
                        override fun onRequestFinished(requestInfo: RequestFinishedInfo) {
                            requestInfo.responseInfo?.let {
                                val safeUrl = runCatching {
                                    Uri.parse(it.url).buildUpon().clearQuery().build().toString()
                                }.getOrDefault("<invalid-url>")
                                Log.i("Cronet", "${it.httpStatusCode} ${it.negotiatedProtocol} $safeUrl")
                                it.allHeadersAsList?.forEach {
                                    val value = if (it.key.equals("authorization", true) ||
                                        it.key.equals("cookie", true) ||
                                        it.key.equals("set-cookie", true)
                                    ) {
                                        "<redacted>"
                                    } else {
                                        it.value
                                    }
                                    Log.i("Cronet", "${it.key}: $value")
                                }
                            }
                        }
                    })
                }
            }
        } else {
            null
        }
    }

    val okHttpClient = lazy {
        OkHttpClient.Builder().apply {
            if (BuildConfig.DEBUG) {
                val sensitiveQueryParameter = Regex(
                    "([?&](?:token|access_token|client_secret|password|code)=)[^&\\s]+",
                    RegexOption.IGNORE_CASE,
                )
                addInterceptor(HttpLoggingInterceptor { message ->
                    Log.d("OkHttp", message.replace(sensitiveQueryParameter, "\$1<redacted>"))
                }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                })
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val sslContext = SSLContext.getInstance("TLSv1.3")
                sslContext.init(null, arrayOf(trustManager.value), null)
                sslSocketFactory(sslContext.socketFactory, trustManager.value)
            }
        }.build()
    }

    val updateRepository by lazy {
        UpdateRepository(
            application,
            ReleaseClient(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json),
        )
    }

    val trustManager = lazy {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        var count = 0
        val certificateFactory = CertificateFactory.getInstance("X.509")
        application.resources.openRawResource(R.raw.isrgrootx1).use {
            val certificate = certificateFactory.generateCertificate(it)
            keyStore.setCertificateEntry("cert_0", certificate)
            count += 1
        }
        val defaultTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        defaultTrustManagerFactory.init(null as KeyStore?)
        val defaultTrustManager = defaultTrustManagerFactory.trustManagers.first() as X509TrustManager
        defaultTrustManager.acceptedIssuers.forEach {
            keyStore.setCertificateEntry("cert_$count", it)
            count += 1
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        trustManagerFactory.trustManagers.first() as X509TrustManager
    }

    val json by lazy {
        Json { ignoreUnknownKeys = true }
    }

    val database by lazy {
        // Recover any interrupted file swap before Room can create a missing
        // database path and accidentally discard both restore candidates.
        DatabaseRestoreRecovery.recoverBeforeDatabaseOpen(application)
        val pendingRestore = DatabaseRestoreRecovery.hasPendingRestore(application)
        val candidate = try {
            buildDatabase(application)
        } catch (error: Exception) {
            if (!pendingRestore) throw error
            DatabaseRestoreRecovery.rollback(application)
            buildDatabase(application)
        }
        if (!pendingRestore) {
            candidate
        } else {
            try {
                // Opening the database forces Room's complete schema validation
                // before the retained pre-restore files are discarded.
                candidate.openHelper.writableDatabase
                DatabaseRestoreRecovery.complete(application)
                candidate
            } catch (error: Exception) {
                candidate.close()
                DatabaseRestoreRecovery.rollback(application)
                buildDatabase(application)
            }
        }
    }

    private fun buildDatabase(application: Application): AppDatabase = Room.databaseBuilder(application, AppDatabase::class.java, "database").apply {
            addMigrations(
                Migration(9, 10) { db ->
                    db.execSQL("DELETE FROM emotes")
                },
                Migration(10, 11) { db ->
                    db.execSQL("ALTER TABLE videos ADD COLUMN videoId TEXT DEFAULT null")
                },
                Migration(11, 12) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games (game_id TEXT NOT NULL, game_name TEXT, boxArt TEXT, PRIMARY KEY (game_id))")
                },
                Migration(12, 13) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT NOT NULL, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER NOT NULL, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(13, 14) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER, type TEXT, videoId TEXT, is_bookmark INTEGER, userType TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id = id, is_vod = is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS vod_bookmark_ignored_users (user_id TEXT NOT NULL, PRIMARY KEY (user_id))")
                },
                Migration(14, 15) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT OR IGNORE INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id = id, is_vod = is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL, userId TEXT, userLogin TEXT, userName TEXT, userLogo TEXT, gameId TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, PRIMARY KEY (id))")
                },
                Migration(15, 16) { db ->
                    db.execSQL("ALTER TABLE bookmarks ADD COLUMN userType TEXT DEFAULT null")
                    db.execSQL("ALTER TABLE bookmarks ADD COLUMN userBroadcasterType TEXT DEFAULT null")
                },
                Migration(16, 17) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_channel (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoType TEXT, clipPeriod TEXT, PRIMARY KEY (id))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_game (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguageIndex INTEGER, clipPeriod TEXT, clipLanguageIndex INTEGER, PRIMARY KEY (id))")
                },
                Migration(17, 18) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_emotes1 (name TEXT NOT NULL, url1x TEXT, url2x TEXT, url3x TEXT, url4x TEXT, used_at INTEGER NOT NULL, PRIMARY KEY (name))")
                    db.execSQL("INSERT INTO recent_emotes1 (name, url1x, used_at) SELECT name, url, used_at FROM recent_emotes")
                    db.execSQL("DROP TABLE recent_emotes")
                    db.execSQL("ALTER TABLE recent_emotes1 RENAME TO recent_emotes")
                },
                Migration(18, 19) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks1 (videoId TEXT, userId TEXT, userLogin TEXT, userName TEXT, userType TEXT, userBroadcasterType TEXT, userLogo TEXT, gameId TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, animatedPreviewURL TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO bookmarks1 (videoId, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration) SELECT id, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration FROM bookmarks")
                    db.execSQL("DROP TABLE bookmarks")
                    db.execSQL("ALTER TABLE bookmarks1 RENAME TO bookmarks")
                    db.execSQL("CREATE TABLE IF NOT EXISTS local_follows1 (userId TEXT, userLogin TEXT, userName TEXT, channelLogo TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO local_follows1 (userId, userLogin, userName, channelLogo) SELECT user_id, user_login, user_name, channelLogo FROM local_follows")
                    db.execSQL("DROP TABLE local_follows")
                    db.execSQL("ALTER TABLE local_follows1 RENAME TO local_follows")
                    db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games1 (gameId TEXT, gameName TEXT, boxArt TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO local_follows_games1 (gameId, gameName, boxArt) SELECT game_id, game_name, boxArt FROM local_follows_games")
                    db.execSQL("DROP TABLE local_follows_games")
                    db.execSQL("ALTER TABLE local_follows_games1 RENAME TO local_follows_games")
                    db.execSQL("CREATE TABLE IF NOT EXISTS requests1 (offline_video_id INTEGER NOT NULL, url TEXT NOT NULL, path TEXT NOT NULL, video_id TEXT, video_type TEXT, segment_from INTEGER, segment_to INTEGER, PRIMARY KEY (offline_video_id), FOREIGN KEY('offline_video_id') REFERENCES videos('id') ON DELETE CASCADE)")
                    db.execSQL("INSERT INTO requests1 (offline_video_id, url, path, video_id, segment_from, segment_to) SELECT offline_video_id, url, path, video_id, segment_from, segment_to FROM requests")
                    db.execSQL("DROP TABLE requests")
                    db.execSQL("ALTER TABLE requests1 RENAME TO requests")
                },
                Migration(19, 20) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_emotes1 (name TEXT NOT NULL, used_at INTEGER NOT NULL, PRIMARY KEY (name))")
                    db.execSQL("INSERT INTO recent_emotes1 (name, used_at) SELECT name, used_at FROM recent_emotes")
                    db.execSQL("DROP TABLE recent_emotes")
                    db.execSQL("ALTER TABLE recent_emotes1 RENAME TO recent_emotes")
                },
                Migration(20, 21) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS requests1 (offline_video_id INTEGER NOT NULL, url TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY (offline_video_id), FOREIGN KEY('offline_video_id') REFERENCES videos('id') ON DELETE CASCADE)")
                    db.execSQL("INSERT INTO requests1 (offline_video_id, url, path) SELECT offline_video_id, url, path FROM requests")
                    db.execSQL("DROP TABLE requests")
                    db.execSQL("ALTER TABLE requests1 RENAME TO requests")
                },
                Migration(21, 22) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks1 (videoId TEXT, userId TEXT, userLogin TEXT, userName TEXT, userType TEXT, userBroadcasterType TEXT, userLogo TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, animatedPreviewURL TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO bookmarks1 (videoId, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration, animatedPreviewURL, id) SELECT videoId, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration, animatedPreviewURL, id FROM bookmarks")
                    db.execSQL("DROP TABLE bookmarks")
                    db.execSQL("ALTER TABLE bookmarks1 RENAME TO bookmarks")
                    db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games1 (gameId TEXT, gameSlug TEXT, gameName TEXT, boxArt TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO local_follows_games1 (gameId, gameName, boxArt, id) SELECT gameId, gameName, boxArt, id FROM local_follows_games")
                    db.execSQL("DROP TABLE local_follows_games")
                    db.execSQL("ALTER TABLE local_follows_games1 RENAME TO local_follows_games")
                },
                Migration(22, 23) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, quality TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(23, 24) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, quality TEXT, downloadChat INTEGER, downloadChatEmotes INTEGER, chatProgress INTEGER, chatUrl TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(24, 25) { db ->
                    db.execSQL("DROP TABLE requests")
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, 0, downloadPath, fromTime, toTime, status, type, videoId, quality, 0, 0, 0, 100, 0, 0, chatUrl, 0, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(25, 26) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, 0, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(26, 27) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS shown_notifications (channelId TEXT NOT NULL, startedAt INTEGER NOT NULL, PRIMARY KEY (channelId))")
                },
                Migration(27, 28) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS notifications (channelId TEXT NOT NULL, PRIMARY KEY (channelId))")
                },
                Migration(28, 29) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS translate_all_messages (channelId TEXT NOT NULL, PRIMARY KEY (channelId))")
                },
                Migration(29, 30) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_game1 (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguages TEXT, clipPeriod TEXT, clipLanguages TEXT, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO sort_game1 (id, saveSort, videoSort, videoPeriod, videoType, clipPeriod) SELECT id, saveSort, videoSort, videoPeriod, videoType, clipPeriod FROM sort_game")
                    db.execSQL("DROP TABLE sort_game")
                    db.execSQL("ALTER TABLE sort_game1 RENAME TO sort_game")
                },
                Migration(30, 31) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_game1 (id TEXT NOT NULL, streamSort TEXT, streamTags TEXT, streamLanguages TEXT, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguages TEXT, clipPeriod TEXT, clipLanguages TEXT, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO sort_game1 (id, videoSort, videoPeriod, videoType, videoLanguages, clipPeriod, clipLanguages) SELECT id, videoSort, videoPeriod, videoType, videoLanguages, clipPeriod, clipLanguages FROM sort_game WHERE saveSort=1")
                    db.execSQL("DROP TABLE sort_game")
                    db.execSQL("ALTER TABLE sort_game1 RENAME TO sort_game")
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_channel1 (id TEXT NOT NULL, videoSort TEXT, videoType TEXT, clipPeriod TEXT, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO sort_channel1 (id, videoSort, videoType, clipPeriod) SELECT id, videoSort, videoType, clipPeriod FROM sort_channel WHERE saveSort=1")
                    db.execSQL("DROP TABLE sort_channel")
                    db.execSQL("ALTER TABLE sort_channel1 RENAME TO sort_channel")
                },
                Migration(31, 32) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS filters (id INTEGER NOT NULL, gameId TEXT, gameSlug TEXT, gameName TEXT, tags TEXT, languages TEXT, PRIMARY KEY (id))")
                },
                Migration(32, 33) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_search (id INTEGER NOT NULL, query TEXT NOT NULL, type TEXT NOT NULL, lastSearched INTEGER NOT NULL, PRIMARY KEY (id))")
                },
                Migration(33, 34) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS playback_states (type TEXT, streamId TEXT, videoId TEXT, clipId TEXT, offlineVideoId INTEGER, channelId TEXT, channelLogin TEXT, channelName TEXT, channelImage TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, thumbnail TEXT, createdAt TEXT, viewerCount INTEGER, durationSeconds INTEGER, videoType TEXT, videoOffsetSeconds INTEGER, videoAnimatedPreviewURL TEXT, position INTEGER, paused INTEGER NOT NULL, qualities TEXT, quality TEXT, previousQuality TEXT, restoreQuality INTEGER NOT NULL, playlistUrl TEXT, restorePlaylist INTEGER NOT NULL, useCustomProxy INTEGER NOT NULL, skipAccessToken INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                },
                Migration(34, 35) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, liveCommentsArrayStarted INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, liveCommentsArrayStarted, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, 0, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(35, 36) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, videoCreatedAt TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, liveCommentsArrayStarted INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, clipId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, liveCommentsArrayStarted, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, clipId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, liveCommentsArrayStarted, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS playback_states1 (type TEXT, streamId TEXT, videoId TEXT, clipId TEXT, offlineVideoId INTEGER, channelId TEXT, channelLogin TEXT, channelName TEXT, channelImage TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, thumbnail TEXT, createdAt TEXT, viewerCount INTEGER, durationSeconds INTEGER, videoType TEXT, videoOffsetSeconds INTEGER, videoCreatedAt TEXT, videoAnimatedPreviewURL TEXT, position INTEGER, paused INTEGER NOT NULL, qualities TEXT, quality TEXT, previousQuality TEXT, restoreQuality INTEGER NOT NULL, playlistUrl TEXT, restorePlaylist INTEGER NOT NULL, useCustomProxy INTEGER NOT NULL, skipAccessToken INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO playback_states1 (type, streamId, videoId, clipId, offlineVideoId, channelId, channelLogin, channelName, channelImage, gameId, gameSlug, gameName, title, thumbnail, createdAt, viewerCount, durationSeconds, videoType, videoOffsetSeconds, videoAnimatedPreviewURL, position, paused, qualities, quality, previousQuality, restoreQuality, playlistUrl, restorePlaylist, useCustomProxy, skipAccessToken, id) SELECT type, streamId, videoId, clipId, offlineVideoId, channelId, channelLogin, channelName, channelImage, gameId, gameSlug, gameName, title, thumbnail, createdAt, viewerCount, durationSeconds, videoType, videoOffsetSeconds, videoAnimatedPreviewURL, position, paused, qualities, quality, previousQuality, restoreQuality, playlistUrl, restorePlaylist, useCustomProxy, skipAccessToken, id FROM playback_states")
                    db.execSQL("DROP TABLE playback_states")
                    db.execSQL("ALTER TABLE playback_states1 RENAME TO playback_states")
                },
                Migration(36, 37) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS playback_states1 (type TEXT, streamId TEXT, videoId TEXT, clipId TEXT, offlineVideoId INTEGER, channelId TEXT, channelLogin TEXT, channelName TEXT, channelImage TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, thumbnail TEXT, createdAt TEXT, viewerCount INTEGER, durationSeconds INTEGER, videoType TEXT, videoOffsetSeconds INTEGER, videoCreatedAt TEXT, videoAnimatedPreviewURL TEXT, videoUrl TEXT, position INTEGER, paused INTEGER NOT NULL, qualities TEXT, quality TEXT, previousQuality TEXT, restoreQuality INTEGER NOT NULL, playlistUrl TEXT, restorePlaylist INTEGER NOT NULL, useCustomProxy INTEGER NOT NULL, skipAccessToken INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO playback_states1 (type, streamId, videoId, clipId, offlineVideoId, channelId, channelLogin, channelName, channelImage, gameId, gameSlug, gameName, title, thumbnail, createdAt, viewerCount, durationSeconds, videoType, videoOffsetSeconds, videoCreatedAt, videoAnimatedPreviewURL, position, paused, qualities, quality, previousQuality, restoreQuality, playlistUrl, restorePlaylist, useCustomProxy, skipAccessToken, id) SELECT type, streamId, videoId, clipId, offlineVideoId, channelId, channelLogin, channelName, channelImage, gameId, gameSlug, gameName, title, thumbnail, createdAt, viewerCount, durationSeconds, videoType, videoOffsetSeconds, videoCreatedAt, videoAnimatedPreviewURL, position, paused, qualities, quality, previousQuality, restoreQuality, playlistUrl, restorePlaylist, useCustomProxy, skipAccessToken, id FROM playback_states")
                    db.execSQL("DROP TABLE playback_states")
                    db.execSQL("ALTER TABLE playback_states1 RENAME TO playback_states")
                },
                Migration(37, 38) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS notification_events (eventId TEXT NOT NULL, channelId TEXT NOT NULL, streamId TEXT, channelLogin TEXT, channelName TEXT, channelImageURL TEXT, gameName TEXT, title TEXT, thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, startedAt INTEGER NOT NULL, queuedAt INTEGER NOT NULL, PRIMARY KEY (eventId))")
                },
                ViewingStatsMigrations.FROM_38,
                ViewingStatsMigrations.FROM_HISTORICAL_39,
                // Version 39 only contained the removed notification log table.
                Migration(39, 37) { db ->
                    db.execSQL("DROP TABLE IF EXISTS live_notification_logs")
                },
                Migration(39, 38) { db ->
                    db.execSQL("DROP TABLE IF EXISTS live_notification_logs")
                    db.execSQL("CREATE TABLE IF NOT EXISTS notification_events (eventId TEXT NOT NULL, channelId TEXT NOT NULL, streamId TEXT, channelLogin TEXT, channelName TEXT, channelImageURL TEXT, gameName TEXT, title TEXT, thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, startedAt INTEGER NOT NULL, queuedAt INTEGER NOT NULL, PRIMARY KEY (eventId))")
                },
                StreamFeedMigrations.FROM_40,
                StreamFeedMigrations.FROM_41,
                MetadataCacheMigrations.FROM_42,
                StreamFeedMigrations.FROM_43,
                ViewingStatsMigrations.FROM_44,
                Migration(45, 46) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS favorite_emotes (provider TEXT NOT NULL, emote_id TEXT NOT NULL, favorited_at INTEGER NOT NULL, PRIMARY KEY (provider, emote_id))")
                },
                Migration(46, 47) { db ->
                    db.execSQL("ALTER TABLE favorite_emotes ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                },
                Migration(47, 48) { db ->
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS video_history (
                            id INTEGER NOT NULL PRIMARY KEY,
                            position INTEGER NOT NULL,
                            durationSeconds INTEGER,
                            channelId TEXT,
                            channelLogin TEXT,
                            channelName TEXT,
                            channelImageURL TEXT,
                            title TEXT,
                            thumbnailURL TEXT,
                            gameId TEXT,
                            gameSlug TEXT,
                            gameName TEXT,
                            createdAt TEXT,
                            updatedAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                },
                Migration(48, 49) { db ->
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_url ON videos(url)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_status ON videos(status)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_videoId ON videos(videoId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_channel_id ON videos(channel_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_video_history_updatedAt ON video_history(updatedAt)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_video_history_channelId ON video_history(channelId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_search_type_lastSearched ON recent_search(type, lastSearched)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_search_query_type ON recent_search(query, type)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_videoId ON bookmarks(videoId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_userId ON bookmarks(userId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_channelId ON notification_events(channelId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_queuedAt ON notification_events(queuedAt)")
                },
                GameFeedMigrations.FROM_49,
            )
        }.build()

    val authRepository by lazy {
        AuthRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json)
    }

    val authSessionMaintainer by lazy {
        AuthSessionMaintainer(application, authRepository)
    }

    val twitchWebSessionManager by lazy {
        TwitchWebSessionManager(application, authRepository, authSessionMaintainer)
    }

    val bookmarksRepository by lazy {
        BookmarksRepository(database.bookmarks(), database.bookmarkIgnoredUsers(), database.offlineVideos())
    }

    val channelSortRepository by lazy {
        ChannelSortRepository(database.channelSort())
    }

    val gameSortRepository by lazy {
        GameSortRepository(database.gameSort())
    }

    val graphQLRepository by lazy {
        GraphQLRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json)
    }

    val helixRepository by lazy {
        HelixRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json)
    }

    val localChannelFollowsRepository by lazy {
        LocalChannelFollowsRepository(
            localChannelFollowsDao = database.localChannelFollows(),
            offlineVideosDao = database.offlineVideos(),
            bookmarksDao = database.bookmarks(),
            onChanged = { streamFeedRefreshCoordinator.invalidateFollowedFeeds() },
        )
    }

    val localGameFollowsRepository by lazy {
        LocalGameFollowsRepository(database.localGameFollows())
    }

    val recommendationsRepository by lazy {
        RecommendationsRepository(application, graphQLRepository, localChannelFollowsRepository)
    }

    val notificationsRepository by lazy {
        NotificationsRepository(database.shownNotifications(), database.notificationUsers(), database.notificationEvents(), graphQLRepository, helixRepository)
    }

    val offlineVideosRepository by lazy {
        OfflineVideosRepository(database.offlineVideos(), database.bookmarks())
    }

    val playerRepository by lazy {
        PlayerRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json, database.recentEmotes(), database.favoriteEmotes(), database.translatedChannels(), database.videoPositions(), database.videoHistory(), database.playbackStates(), graphQLRepository, helixRepository)
    }

    val recentSearchesRepository by lazy {
        RecentSearchesRepository(database.recentSearches())
    }

    val savedFiltersRepository by lazy {
        SavedFiltersRepository(database.savedFilters())
    }

    val viewingStatsRepository by lazy {
        ViewingStatsRepository(database.viewingStats())
    }

    val viewingStatsRecorder by lazy {
        ViewingStatsRecorder(viewingStatsRepository)
    }
}
