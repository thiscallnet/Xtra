package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.ui.following.FollowingTabs

/**
 * Owns migrations for the settings redesign. The old settings version is
 * still advanced by MainActivity's historical migrations. Each redesigned
 * target is populated only when absent, and representation changes have their
 * own marker so a stale global version cannot reinterpret current values.
 */
object SettingsMigration {

    private const val DEFAULT_SPEEDS = "0.25,0.5,0.75,1.0,1.25,1.5,1.75,2.0,3.0,4.0,8.0"
    private const val DEFAULT_CHAT_WIDTH_PERCENT = 30
    private const val TIMESTAMP_FORMAT_SCHEMA_VERSION = 1

    /** Settings reset deliberately names the keys it owns; authentication and app data are separate. */
    internal val RESETTABLE_PREFERENCE_KEYS = setOf(
        C.DOWNLOAD_PLAYLIST_TO_FILE,
        C.DOWNLOAD_WIFI_ONLY,
        C.DOWNLOAD_LIMIT,
        C.DOWNLOAD_STREAM_START_WAIT,
        C.DOWNLOAD_STREAM_END_WAIT,
        C.DOWNLOAD_STORAGE,
        C.DOWNLOAD_SHARED_PATH,
        C.DOWNLOAD_LOCATION,
        C.DOWNLOAD_CHAT,
        C.DOWNLOAD_CHAT_EMOTES,
        C.DOWNLOAD_NOTIFICATION_REQUESTED,
        C.ASPECT_RATIO_LANDSCAPE,
        C.SETTINGS_VERSION,
        C.SETTINGS_THEME_MODE,
        C.SETTINGS_DEVICE_COLORS,
        C.SETTINGS_DENSITY,
        C.SETTINGS_PROFILE_PICTURE_STYLE,
        C.SETTINGS_TIMESTAMP_FORMAT_VERSION,
        C.SETTINGS_CHAT_ENABLED,
        C.SETTINGS_BACKGROUND_PLAYBACK,
        C.SETTINGS_HTTP_PROXY_ENABLED,
        C.SETTINGS_PLAYER_CONTROL_LAYOUT,
        C.SETTINGS_PLAYER_SPEED_OPTIONS,
        C.SETTINGS_DEVELOPER_UNLOCKED,
        C.SETTINGS_DEVELOPER_ENABLED,
        C.CLIP_MAX_DURATION_SECONDS,
        C.CLIP_PREVIEW_SEEK_SECONDS,
        C.CLIP_LIBRARY_AUTOPLAY,
        C.CLIP_LIBRARY_SORT,
        C.CHAT_WIDTH_PERCENT,
        C.LANDSCAPE_CHAT_WIDTH,
        C.KEY_CHAT_OPENED,
        C.KEY_CHAT_BAR_VISIBLE,
        C.SLEEP_TIMER_MINUTES,
        C.SLEEP_TIMER_TIME,
        C.SLEEP_TIMER_LOCK,
        C.SORT_DEFAULT_GAME_VIDEOS,
        C.SORT_DEFAULT_GAME_CLIPS,
        C.SORT_DEFAULT_CHANNEL_VIDEOS,
        C.SORT_DEFAULT_CHANNEL_CLIPS,
        C.SORT_DEFAULT_FOLLOWED_VIDEOS,
        C.SORT_DEFAULT_FOLLOWED_CHANNELS,
        C.THEME,
        C.PORTRAIT_COLUMN_COUNT,
        C.LANDSCAPE_COLUMN_COUNT,
        C.COMPACT_STREAMS,
        C.UI_ROUND_USER_IMAGE,
        C.UI_TRUNCATE_VIEW_COUNT,
        C.UI_BROADCASTERS_COUNT,
        C.UI_START_ON_FOLLOWED,
        C.UI_FOLLOW_DEFAULT_PAGE,
        C.UI_SAVED_DEFAULT_PAGE,
        C.UI_LANGUAGE,
        C.UI_CUTOUT_MODE,
        C.UI_DRAW_BEHIND_CUTOUTS,
        C.UI_FOLLOW_BUTTON,
        C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING,
        C.UI_UPTIME,
        C.UI_TAGS,
        C.UI_SCROLL_TOP,
        C.UI_BOOKMARK_TIME_LEFT,
        C.UI_DEFAULT_PAGE,
        C.UI_NAVIGATION_TABS,
        C.UI_NAVIGATION_TAB_LIST,
        C.UI_FOLLOWING_TABS,
        C.UI_STREAM_SORT,
        C.UI_SAVED_TABS,
        C.UI_CHANNEL_TABS,
        C.UI_GAME_TABS,
        C.UI_SEARCH_TABS,
        C.UI_NAME_DISPLAY,
        C.UI_STORE_RECENT_SEARCHES,
        C.UI_THEME_ROUNDED_CORNERS,
        C.UI_THEME_REDUCED_PADDING,
        C.UI_THEME_COMPACT_TEXT,
        C.UI_THEME_FOLLOW_SYSTEM,
        C.UI_THEME_DARK_ON,
        C.UI_THEME_DARK_OFF,
        C.PLAYER_DOUBLE_TAP,
        C.PLAYER_PAUSE,
        C.PLAYER_MINIMIZE,
        C.PLAYER_DOWNLOAD,
        C.PLAYER_FOLLOW,
        C.PLAYER_SLEEP,
        C.PLAYER_ASPECT,
        C.PLAYER_SPEED_BUTTON,
        C.PLAYER_SETTINGS,
        C.PLAYER_RESTART,
        C.PLAYER_SEEK_LIVE,
        C.PLAYER_CLIP_BUTTON,
        C.PLAYER_MODE,
        C.PLAYER_SUBTITLES,
        C.PLAYER_CHAT_BAR_TOGGLE,
        C.PLAYER_CHAT_TOGGLE,
        C.PLAYER_FULLSCREEN,
        C.PLAYER_VIEWER_ICON,
        C.PLAYER_CHANNEL,
        C.PLAYER_TITLE,
        C.PLAYER_CATEGORY,
        C.PLAYER_VOLUME_BUTTON,
        C.PLAYER_AUDIO_COMPRESSOR_BUTTON,
        C.PLAYER_GAMES_BUTTON,
        C.PLAYER_VIEWER_LIST,
        C.PLAYER_MENU,
        C.PLAYER_MENU_QUALITY,
        C.PLAYER_MENU_SPEED,
        C.PLAYER_MENU_VIEWER_LIST,
        C.PLAYER_MENU_GAMES,
        C.PLAYER_MENU_BOOKMARK,
        C.PLAYER_MENU_DOWNLOAD,
        C.PLAYER_MENU_SHARE,
        C.PLAYER_MENU_FIND_VOD,
        C.PLAYER_MENU_SLEEP,
        C.PLAYER_MENU_ASPECT,
        C.PLAYER_MENU_VOLUME,
        C.PLAYER_MENU_SUBTITLES,
        C.PLAYER_MENU_CHAT_BAR,
        C.PLAYER_MENU_CHAT_TOGGLE,
        C.PLAYER_MENU_CHAT_DISCONNECT,
        C.PLAYER_MENU_RESTART,
        C.PLAYER_MENU_RELOAD_EMOTES,
        C.PLAYER_USE_VIDEO_POSITIONS,
        C.PLAYER_DEFAULT_QUALITY,
        C.PLAYER_DEFAULT_CELLULAR_QUALITY,
        C.PLAYER_QUALITY,
        C.PLAYER_VOLUME,
        C.PLAYER_SPEED,
        C.PLAYER_SPEED_LIST,
        C.PLAYER_AUDIO_COMPRESSOR,
        C.PLAYER_SUBTITLES_ENABLED,
        C.PLAYER_REWIND,
        C.PLAYER_FORWARD,
        C.PLAYER_BACKGROUND_PLAYBACK,
        C.PLAYER_PICTURE_IN_PICTURE,
        C.PLAYER_BACKGROUND_AUDIO,
        C.PLAYER_BACKGROUND_AUDIO_LOCKED,
        C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED,
        C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED,
        C.PLAYER_AUTO_RECOVER_STREAMS,
        C.PLAYER_KEEP_PLAYING_AFTER_TASK_REMOVED,
        C.PLAYER_AUDIO_FOCUS,
        C.PLAYER_ROUNDED_CORNER_PADDING,
        C.PLAYER_MOVE_FREELY,
        C.PLAYER_KEEP_CHAT_OPEN,
        C.PLAYER_AVOID_ADS,
        C.PLAYER_HIDE_ADS,
        C.PLAYER_PROXY,
        C.PLAYER_STREAM_PROXY,
        C.PLAYER_PROXY_URL,
        C.PLAYER_STREAM_HEADERS,
        C.PLAYER_SHOW_UPTIME,
        C.MULTIVIEW_RAIDS,
        C.PROXY_PLAYBACK_ACCESS_TOKEN,
        C.PROXY_MULTIVARIANT_PLAYLIST,
        C.PROXY_MEDIA_PLAYLIST,
        C.PROXY_HOST,
        C.PROXY_PORT,
        C.PROXY_USER,
        C.PROXY_PASSWORD,
        C.ANIMATED_EMOTES,
        C.CHAT_SIZE_MODIFIER,
        C.CHAT_TEXT_SIZE,
        C.CHAT_EMOTE_SIZE,
        C.CHAT_BADGE_SIZE,
        C.CHAT_RANDOM_COLOR,
        C.CHAT_THEME_ADAPTED_USERNAME_COLOR,
        C.CHAT_BOLD_NAMES,
        C.CHAT_ZERO_WIDTH,
        C.CHAT_FIRST_MSG_VISIBILITY,
        C.CHAT_TIMESTAMPS,
        C.CHAT_TIMESTAMP_FORMAT,
        C.CHAT_RECENT,
        C.CHAT_TRANSLATE,
        C.CHAT_TRANSLATE_TARGET,
        C.CHAT_SHOW_USER_NOTICE,
        C.CHAT_SHOW_CLEAR_MSG,
        C.CHAT_SHOW_CLEAR_CHAT,
        C.CHAT_ENABLE_STV,
        C.CHAT_ENABLE_BTTV,
        C.CHAT_ENABLE_FFZ,
        C.CHAT_DISABLE,
        C.CHAT_POINTS_COLLECT,
        C.CHAT_POINTS_NOTIFY,
        C.CHAT_RAIDS_SHOW,
        C.CHAT_RAIDS_AUTO_SWITCH,
        C.CHAT_POLLS_SHOW,
        C.CHAT_PREDICTIONS_SHOW,
        C.CHAT_SYSTEM_MESSAGE_EMOTES,
        C.CHAT_SHOW_PAINTS,
        C.CHAT_SHOW_BADGES,
        C.CHAT_SHOW_STV_BADGES,
        C.CHAT_SHOW_PERSONAL_EMOTES,
        C.TOKEN_X_DEVICE_ID,
        C.TOKEN_RANDOM_DEVICE_ID,
        C.TOKEN_PLAYER_TYPE,
        C.TOKEN_PLAYER_TYPE_VIDEO,
        C.TOKEN_INCLUDE_TOKEN_STREAM,
        C.TOKEN_INCLUDE_TOKEN_VIDEO,
        C.TOKEN_SUPPORTED_CODECS,
        C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN,
        C.TOKEN_SKIP_CLIP_ACCESS_TOKEN,
        C.API_LOGIN,
        C.HELIX_CLIENT_ID,
        C.HELIX_REDIRECT,
        C.GQL_CLIENT_ID,
        C.GQL_REDIRECT,
        C.GQL_CLIENT_ID2,
        C.GQL_REDIRECT2,
        C.UPDATE_URL,
        C.UPDATE_CHECK_ENABLED,
        C.UPDATE_CHECK_FREQUENCY,
        C.UPDATE_USE_BROWSER,
        C.UPDATE_AVAILABLE_VERSION,
        C.UPDATE_AVAILABLE_TITLE,
        C.UPDATE_AVAILABLE_BODY,
        C.UPDATE_AVAILABLE_URL,
        C.UPDATE_AVAILABLE_PUBLISHED_AT,
        C.UPDATE_AVAILABLE_DOWNLOAD_URL,
        C.UPDATE_AVAILABLE_ASSET_NAME,
        C.UPDATE_AVAILABLE_SIZE,
        C.UPDATE_RELEASE_HISTORY,
        C.UPDATE_RELEASE_HISTORY_COMPLETE,
        C.UPDATE_IGNORED_VERSION,
        C.UPDATE_DOWNLOADED_VERSION,
        C.UPDATE_DOWNLOAD_ID,
        C.UPDATE_DOWNLOAD_FILE,
        C.UPDATE_NOT_NOW_VERSION,
        C.UPDATE_NOT_NOW_UNTIL,
        C.LIVE_NOTIFICATIONS_ENABLED,
        C.LIVE_NOTIFICATIONS_MODE,
        C.LIVE_NOTIFICATION_LAST_RUN,
        C.LIVE_NOTIFICATION_LAST_SUCCESS,
        C.LIVE_NOTIFICATION_LAST_ERROR,
        C.LIVE_NOTIFICATION_LAST_ERROR_AT,
        C.LIVE_NOTIFICATION_LAST_API,
        C.LIVE_NOTIFICATION_LAST_EVENT_COUNT,
        C.LIVE_NOTIFICATION_LAST_REVOCATION,
        C.LIVE_NOTIFICATION_LAST_REVOCATION_AT,
        C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS,
        C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT,
        C.LIVE_NOTIFICATION_LAST_SETUP_ATTEMPT,
        C.LIVE_NOTIFICATION_LAST_SETUP_SUCCESS,
        C.LIVE_NOTIFICATION_LAST_SETUP_ERROR_AT,
        C.LIVE_NOTIFICATION_LAST_SETUP_API,
        C.LIVE_NOTIFICATION_CACHED_CHANNEL_COUNT,
        C.LIVE_NOTIFICATION_ENABLE_FAILURE_STAGE,
        C.LIVE_NOTIFICATION_ENABLE_FAILURE_REASON,
        C.LIVE_NOTIFICATION_ENABLE_FAILURE_OPERATION,
        C.LIVE_NOTIFICATION_ENABLE_FAILURE_STATUS,
        C.LIVE_NOTIFICATION_ENABLE_FAILURE_EXCEPTION,
        C.LIVE_NOTIFICATION_ENABLE_FAILURE_MESSAGE,
        C.LIVE_NOTIFICATION_BASELINE_INITIALIZED,
        C.NETWORK_LIBRARY,
        C.PLAYER,
        C.DEBUG_CHAT_FULL_MSG,
        C.DEBUG_API_COMMANDS,
        C.DEBUG_API_CHAT_MESSAGES,
        C.DEBUG_WEBSOCKET_INFO,
        C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE,
        C.DEBUG_EVENT_SUB_CHAT,
        C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS,
        C.ENABLE_INTEGRITY,
        C.USE_WEBVIEW_INTEGRITY,
        "delete_recent_searches",
        "delete_video_positions",
        "import_app_downloads",
        "player_disable_background_video",
        "player_use_background_audio_track",
        "player_background_audio_pip_closed",
        "player_background_audio_pip_locked",
        "player_keep_screen_on_when_paused",
        "player_audio_focus",
        "player_handle_audio_becoming_noisy",
        C.PLAYER_LOW_LATENCY,
        "player_buffer_min",
        "player_buffer_max",
        "player_buffer_playback",
        "player_buffer_rebuffer",
        "player_live_min_speed",
        "player_live_max_speed",
        "player_live_target_offset",
        "chat_limit",
        "chat_recent_limit",
        "chat_recent_messages_url",
        "chat_image_library",
        "chat_use_webp",
        "chat_image_quality",
        "chat_use_websocket",
        "chat_use_ssl",
        "chat_pubsub_enabled",
        "chat_stv_live_updates",
        "request_local_network_permission",
        "download_concurrent_limit",
        "download_stream_live_check",
        "download_stream_offline_check",
        "sleep_timer_use_time_picker",
        "admin_settings",
        "validate_tokens",
    )

    fun resetUserPreferences(context: Context) {
        context.rawPrefs().edit {
            RESETTABLE_PREFERENCE_KEYS.forEach(::remove)
            putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
            putBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, false)
            putBoolean(C.SETTINGS_DEVELOPER_ENABLED, false)
            remove(C.SETTINGS_VERSION)
        }
        migrate(context, freshInstall = true)
    }

    fun migrate(context: Context, freshInstall: Boolean? = null): Boolean {
        migratePreferences(context.rawPrefs(), freshInstall)
        migrateProxyCredentials(context)
        return synchronizeLandscapeChatWidth(context)
    }

    private fun migrateProxyCredentials(context: Context) {
        val legacy = context.rawPrefs()
        val proxy = context.proxyPrefs()
        val keys = listOf(C.PROXY_HOST, C.PROXY_PORT, C.PROXY_USER, C.PROXY_PASSWORD)
        proxy.edit {
            keys.forEach { key ->
                if (!proxy.contains(key)) {
                    legacy.getString(key, null)?.let { putString(key, it) }
                }
            }
        }
        legacy.edit {
            keys.forEach(::remove)
        }
    }

    /** Keeps the player-facing pixel value in sync with the percentage setting. */
    fun synchronizeLandscapeChatWidth(context: Context, percentage: Int? = null): Boolean {
        val preferences = context.rawPrefs()
        val chatWidthPercent = percentage
            ?: preferences.getInt(C.CHAT_WIDTH_PERCENT, DEFAULT_CHAT_WIDTH_PERCENT)
        val displayMetrics = context.resources.displayMetrics
        val landscapePixels =
            (maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels) * (chatWidthPercent / 100f)).toInt()
        if (preferences.getInt(C.LANDSCAPE_CHAT_WIDTH, 0) == landscapePixels) return false

        preferences.edit {
            putInt(C.LANDSCAPE_CHAT_WIDTH, landscapePixels)
        }
        return true
    }

    internal fun migratePreferences(preferences: SharedPreferences, freshInstall: Boolean? = null) {
        if (preferences.getInt(C.SETTINGS_VERSION, 0) >= C.SETTINGS_SCHEMA_VERSION) return
        val isFreshInstall = freshInstall ?: inferFreshInstall(preferences)

        preferences.edit {
            migrateTheme(preferences)
            if (!preferences.contains(C.SETTINGS_DENSITY)) {
                putString(
                    C.SETTINGS_DENSITY,
                    migratedDensity(
                        current = null,
                        reducedPadding = preferences.getBoolean(C.UI_THEME_REDUCED_PADDING, false),
                        compactText = preferences.getBoolean(C.UI_THEME_COMPACT_TEXT, false),
                    ),
                )
            }
            if (!preferences.contains(C.SETTINGS_PROFILE_PICTURE_STYLE)) {
                putString(
                    C.SETTINGS_PROFILE_PICTURE_STYLE,
                    migratedProfilePictureStyle(
                        current = null,
                        roundUserImage = preferences.getBoolean(C.UI_ROUND_USER_IMAGE, true),
                    ),
                )
            }

            if (!preferences.contains(C.SETTINGS_CHAT_ENABLED)) {
                putBoolean(C.SETTINGS_CHAT_ENABLED, !preferences.getBoolean(C.CHAT_DISABLE, false))
            }
            if (!preferences.contains(C.SETTINGS_BACKGROUND_PLAYBACK)) {
                putBoolean(
                    C.SETTINGS_BACKGROUND_PLAYBACK,
                    migratedBackgroundPlayback(
                        normal = preferences.optionalBoolean(C.PLAYER_BACKGROUND_AUDIO),
                        locked = preferences.optionalBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED),
                        pipClosed = preferences.optionalBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED),
                        pipLocked = preferences.optionalBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED),
                    ),
                )
            }
            remove(C.PLAYER_BACKGROUND_AUDIO)
            remove(C.PLAYER_BACKGROUND_AUDIO_LOCKED)
            remove(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED)
            remove(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED)
            remove(C.PLAYER_BACKGROUND_PLAYBACK)
            if (!preferences.contains(C.SETTINGS_HTTP_PROXY_ENABLED)) {
                putBoolean(
                    C.SETTINGS_HTTP_PROXY_ENABLED,
                    !preferences.getString(C.PROXY_HOST, null).isNullOrBlank() &&
                        preferences.getString(C.PROXY_PORT, null)?.toIntOrNull() != null
                )
            }

            if (!preferences.contains(C.UPDATE_CHECK_ENABLED)) {
                putBoolean(C.UPDATE_CHECK_ENABLED, migratedUpdateCheckEnabled(null, isFreshInstall))
            }
            // Custom update endpoints, browser installs and intervals are no longer supported.
            remove(C.UPDATE_URL)
            remove(C.UPDATE_CHECK_FREQUENCY)
            remove(C.UPDATE_USE_BROWSER)

            if (preferences.getString(C.PLAYER_DEFAULT_QUALITY, null) == "chat_only") {
                putString(C.PLAYER_DEFAULT_QUALITY, "auto")
            }
            if (preferences.getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, null) == "chat_only") {
                putString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "auto")
            }

            val oldDownloadLimit = preferences.getInt(C.DOWNLOAD_LIMIT, 2)
            putInt(C.DOWNLOAD_LIMIT, oldDownloadLimit.coerceIn(1, 4))

            migrateTimestampFormat(preferences)

            if (!preferences.contains(C.SETTINGS_PLAYER_SPEED_OPTIONS)) {
                val speeds = preferences.getString(C.PLAYER_SPEED_LIST, null)
                    ?.split('\n')
                    ?.mapNotNull { it.trim().toDoubleOrNull() }
                    ?.filter { it > 0.0 && it <= 16.0 }
                    ?.distinct()
                    ?.joinToString(",")
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_SPEEDS
                putString(C.SETTINGS_PLAYER_SPEED_OPTIONS, speeds)
            }

            val storedFollowingTabs = preferences.getString(C.UI_FOLLOWING_TABS, null)
            val legacyFollowingTabs = storedFollowingTabs ?: LEGACY_DEFAULT_FOLLOWING_TABS
            val migratedFollowingTabs = FollowingTabs.migrateStoredPreference(storedFollowingTabs)
            val migratedNavigationTabs = migrateFollowingNavigationReachability(
                existing = preferences.getString(C.UI_NAVIGATION_TAB_LIST, null),
                enableOverview = FollowingTabs.legacyMovedTabEnabled(legacyFollowingTabs, FollowingTabs.LEGACY_OVERVIEW) == true,
                enableBrowse = FollowingTabs.legacyMovedTabEnabled(legacyFollowingTabs, FollowingTabs.LEGACY_CHANNELS) == true,
            )
            val effectiveNavigationTabs = migratedNavigationTabs ?: C.DEFAULT_NAVIGATION_TAB_LIST
            val followingTabs = if (navigationTabEnabled(effectiveNavigationTabs, "2")) {
                FollowingTabs.ensureLiveTabEnabled(migratedFollowingTabs)
            } else {
                migratedFollowingTabs
            }
            followingTabs?.let {
                putString(C.UI_FOLLOWING_TABS, it)
            }
            if (migratedNavigationTabs != null && migratedNavigationTabs != preferences.getString(C.UI_NAVIGATION_TAB_LIST, null)) {
                putString(C.UI_NAVIGATION_TAB_LIST, migratedNavigationTabs)
            }

            if (preferences.getString(C.UI_NAVIGATION_TAB_LIST, null) == LEGACY_NAVIGATION_TABS) {
                putString(C.UI_NAVIGATION_TAB_LIST, C.DEFAULT_NAVIGATION_TAB_LIST)
            }
            if (preferences.getString(C.UI_NAVIGATION_TAB_LIST, null) == PRE_CLIPS_NAVIGATION_TABS) {
                putString(C.UI_NAVIGATION_TAB_LIST, C.DEFAULT_NAVIGATION_TAB_LIST)
            }

            val legacyControlsAllDisabled = legacyControlPreferencesAllDisabled(preferences)
            val serializedControlLayout = migratedControlLayout(
                existing = preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
                legacyControlsAllDisabled = legacyControlsAllDisabled,
                legacyLayout = controlLayout(preferences),
            )
            if (preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null) != serializedControlLayout) {
                putString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, serializedControlLayout)
            }
            syncLegacyControlVisibility(serializedControlLayout)

            // The old startup toggle was a second default-page mechanism. Fold its
            // intent into the navigation editor only when no edited tab layout exists.
            val oldStartupValue = preferences.getString(C.UI_START_ON_FOLLOWED, null)
            if (!preferences.contains(C.UI_NAVIGATION_TAB_LIST) && oldStartupValue != null) {
                legacyStartupNavigationTab(oldStartupValue)?.let { defaultTab ->
                    putString(C.UI_NAVIGATION_TAB_LIST, navigationTabsWithDefault(defaultTab))
                }
            }
            remove(C.UI_START_ON_FOLLOWED)
            remove(C.PROXY_MEDIA_PLAYLIST)
            remove(C.THEME)
            remove(C.UI_THEME_FOLLOW_SYSTEM)
            remove(C.UI_THEME_DARK_ON)
            remove(C.UI_THEME_DARK_OFF)
            remove(C.UI_THEME_REDUCED_PADDING)
            remove(C.UI_THEME_COMPACT_TEXT)

            putInt(C.SETTINGS_VERSION, C.SETTINGS_SCHEMA_VERSION)
        }
    }

    internal fun migratedBackgroundPlayback(
        normal: Boolean?,
        locked: Boolean?,
        pipClosed: Boolean?,
        pipLocked: Boolean?,
    ): Boolean {
        // A lossy migration must not turn any explicit legacy opt-out back on.
        // An empty list retains the old defaults, which enabled background audio.
        return listOf(normal, locked, pipClosed, pipLocked).all { it != false }
    }

    internal fun migratedUpdateCheckEnabled(existing: Boolean?, freshInstall: Boolean): Boolean =
        existing ?: freshInstall

    internal fun migratedDensity(
        current: String?,
        reducedPadding: Boolean,
        compactText: Boolean,
    ): String = current ?: if (reducedPadding || compactText) "compact" else "comfortable"

    internal fun migratedProfilePictureStyle(current: String?, roundUserImage: Boolean): String =
        current ?: if (roundUserImage) "round" else "rounded_square"

    internal fun migratedTimestampFormat(value: String?, alreadyRedesigned: Boolean): String {
        val numericValue = value?.toIntOrNull()
        if (alreadyRedesigned) {
            return normalizedTimestampFormat(value)
        }
        return when (numericValue) {
            0, 1 -> "0"
            2, 3 -> "1"
            4, 5 -> "2"
            6, 7 -> "3"
            else -> "0"
        }
    }

    private fun SharedPreferences.Editor.migrateTimestampFormat(preferences: SharedPreferences) {
        val storedVersion = preferences.getInt(C.SETTINGS_TIMESTAMP_FORMAT_VERSION, 0)
        if (storedVersion >= TIMESTAMP_FORMAT_SCHEMA_VERSION) {
            if (!preferences.contains(C.CHAT_TIMESTAMP_FORMAT)) {
                putString(C.CHAT_TIMESTAMP_FORMAT, "0")
            } else {
                val currentValue = preferences.getString(C.CHAT_TIMESTAMP_FORMAT, null)
                val normalizedValue = normalizedTimestampFormat(currentValue)
                if (currentValue != normalizedValue) {
                    putString(C.CHAT_TIMESTAMP_FORMAT, normalizedValue)
                }
            }
            return
        }

        // CHAT_TIMESTAMP_FORMAT kept its key while its value set changed. The
        // dedicated marker is written in the same editor transaction as the
        // converted value, so a current-format value is never guessed from the
        // global settings schema on a later migration pass.
        putString(
            C.CHAT_TIMESTAMP_FORMAT,
            migratedTimestampFormat(
                value = preferences.getString(C.CHAT_TIMESTAMP_FORMAT, null),
                alreadyRedesigned = false,
            ),
        )
        putInt(C.SETTINGS_TIMESTAMP_FORMAT_VERSION, TIMESTAMP_FORMAT_SCHEMA_VERSION)
    }

    private fun normalizedTimestampFormat(value: String?): String =
        value?.toIntOrNull()?.takeIf { it in 0..3 }?.toString() ?: "0"

    private fun SharedPreferences.optionalBoolean(key: String): Boolean? =
        if (contains(key)) getBoolean(key, true) else null

    private fun inferFreshInstall(preferences: SharedPreferences): Boolean = preferences.all.isEmpty()

    internal fun legacyStartupNavigationTab(value: String?): String? = when (value) {
        "0", "1" -> "2" // Always and When logged in both expressed an intent to land on Following.
        "2" -> null // Never: preserve the existing/default navigation destination.
        else -> null
    }

    private fun navigationTabsWithDefault(defaultTab: String): String =
        "0:${if (defaultTab == "0") "1" else "0"}:1," +
            "4:${if (defaultTab == "4") "1" else "0"}:1," +
            "1:${if (defaultTab == "1") "1" else "0"}:1," +
            "2:${if (defaultTab == "2") "1" else "0"}:1," +
            "3:${if (defaultTab == "3") "1" else "0"}:1"

    private const val LEGACY_NAVIGATION_TABS = "0:0:1,1:1:1,2:0:1,3:0:1"
    private const val PRE_CLIPS_NAVIGATION_TABS = "1:1:1,2:0:1,0:0:1,3:0:0"
    private const val LEGACY_DEFAULT_FOLLOWING_TABS = "4:1:1,1:0:1,2:0:1,0:0:1,3:0:1"

    private fun migrateFollowingNavigationReachability(
        existing: String?,
        enableOverview: Boolean,
        enableBrowse: Boolean,
    ): String? {
        if (existing == null) return null
        val defaultEntries = C.DEFAULT_NAVIGATION_TAB_LIST.split(',')
        val knownKeys = defaultEntries.mapTo(hashSetOf()) { it.substringBefore(':') }
        val entries = existing.split(',')
            .filter { entry ->
                val parts = entry.split(':')
                parts.size == 3 && parts[0] in knownKeys
            }
            .distinctBy { it.substringBefore(':') }
            .toMutableList()

        defaultEntries.forEachIndexed { index, defaultEntry ->
            if (entries.none { it.substringBefore(':') == defaultEntry.substringBefore(':') }) {
                entries.add(index.coerceAtMost(entries.size), defaultEntry)
            }
        }

        fun enable(key: String, shouldEnable: Boolean) {
            if (!shouldEnable) return
            val index = entries.indexOfFirst { it.substringBefore(':') == key }
            if (index < 0) return
            val parts = entries[index].split(':').toMutableList()
            parts[2] = "1"
            entries[index] = parts.joinToString(":")
        }

        enable("1", enableOverview)
        enable("0", enableBrowse)
        return entries.joinToString(",")
    }

    private fun navigationTabEnabled(serialized: String, key: String): Boolean = serialized
        .split(',')
        .firstOrNull { it.substringBefore(':') == key }
        ?.split(':')
        ?.getOrNull(2) != "0"

    private fun SharedPreferences.Editor.migrateTheme(preferences: SharedPreferences) {
        if (!preferences.contains(C.SETTINGS_THEME_MODE)) {
            val modeAndColors = when {
                preferences.getBoolean(C.UI_THEME_FOLLOW_SYSTEM, false) -> "system,false"
                preferences.getString(C.THEME, null) == null &&
                    !preferences.contains(C.UI_THEME_DARK_ON) &&
                    !preferences.contains(C.UI_THEME_DARK_OFF) -> "system,false"
                preferences.getString(C.THEME, null) in setOf("4", "5", "6") -> {
                    val mode = when (preferences.getString(C.THEME, null)) {
                        "4" -> "dark"
                        "5" -> "light"
                        else -> "amoled"
                    }
                    "$mode,true"
                }
                preferences.getString(C.THEME, null) == "1" -> "amoled,false"
                preferences.getString(C.THEME, null) == "2" -> "light,false"
                preferences.getString(C.THEME, null) == "3" -> "dark,false"
                preferences.getString(C.THEME, null) == "7" -> "dark,false"
                preferences.getString(C.THEME, null) == "8" -> "amoled,false"
                else -> "dark,false"
            }.split(',')
            putString(C.SETTINGS_THEME_MODE, modeAndColors[0])
            putBoolean(C.SETTINGS_DEVICE_COLORS, modeAndColors[1].toBoolean())
        } else if (!preferences.contains(C.SETTINGS_DEVICE_COLORS)) {
            // A partially migrated/current preference set may already have the
            // new mode but not its companion toggle. Preserve the mode and add
            // the production-safe default without reinterpreting legacy theme data.
            putBoolean(C.SETTINGS_DEVICE_COLORS, false)
        }
    }

    private data class ControlSource(
        val action: String,
        val quickKey: String?,
        val quickDefault: Boolean,
        val menuKey: String?,
        val menuDefault: Boolean,
    )

    private val controlSources = listOf(
        ControlSource("minimize", C.PLAYER_MINIMIZE, true, null, false),
        ControlSource("download", C.PLAYER_DOWNLOAD, false, C.PLAYER_MENU_DOWNLOAD, true),
        ControlSource("follow", C.PLAYER_FOLLOW, false, null, false),
        ControlSource("quality", C.PLAYER_SETTINGS, true, C.PLAYER_MENU_QUALITY, false),
        ControlSource("speed", C.PLAYER_SPEED_BUTTON, true, C.PLAYER_MENU_SPEED, false),
        ControlSource("chapters", C.PLAYER_GAMES_BUTTON, true, C.PLAYER_MENU_GAMES, false),
        ControlSource("restart", C.PLAYER_RESTART, true, C.PLAYER_MENU_RESTART, false),
        ControlSource("live", C.PLAYER_SEEK_LIVE, false, null, false),
        ControlSource("clip", C.PLAYER_CLIP_BUTTON, true, null, false),
        ControlSource("volume", C.PLAYER_VOLUME_BUTTON, true, C.PLAYER_MENU_VOLUME, false),
        ControlSource("compressor", C.PLAYER_AUDIO_COMPRESSOR_BUTTON, true, null, false),
        ControlSource("mode", C.PLAYER_MODE, false, null, false),
        ControlSource("subtitles", C.PLAYER_SUBTITLES, false, C.PLAYER_MENU_SUBTITLES, true),
        ControlSource("chat_input", C.PLAYER_CHAT_BAR_TOGGLE, false, C.PLAYER_MENU_CHAT_BAR, true),
        ControlSource("chat", C.PLAYER_CHAT_TOGGLE, true, C.PLAYER_MENU_CHAT_TOGGLE, false),
        ControlSource("fullscreen", C.PLAYER_FULLSCREEN, true, null, false),
        ControlSource("viewers", C.PLAYER_VIEWER_LIST, false, C.PLAYER_MENU_VIEWER_LIST, true),
        ControlSource("bookmark", null, false, C.PLAYER_MENU_BOOKMARK, true),
        ControlSource("share", null, false, C.PLAYER_MENU_SHARE, true),
        ControlSource("find_vod", null, false, C.PLAYER_MENU_FIND_VOD, true),
        ControlSource("sleep", C.PLAYER_SLEEP, false, C.PLAYER_MENU_SLEEP, true),
        ControlSource("aspect", C.PLAYER_ASPECT, true, C.PLAYER_MENU_ASPECT, false),
        ControlSource("reload_emotes", null, false, C.PLAYER_MENU_RELOAD_EMOTES, true),
        ControlSource("disconnect_chat", null, false, C.PLAYER_MENU_CHAT_DISCONNECT, true),
    )

    internal fun controlGroup(quickEnabled: Boolean, menuEnabled: Boolean): String = when {
        quickEnabled -> "quick"
        menuEnabled -> "menu"
        else -> "hidden"
    }

    internal fun migratedControlLayout(
        existing: String?,
        legacyControlsAllDisabled: Boolean,
        legacyLayout: String,
    ): String = when {
        existing == null && legacyControlsAllDisabled -> defaultControlLayout()
        existing == null -> legacyLayout
        existing.isBlank() -> defaultControlLayout()
        else -> existing.addMissingClipAction()
    }

    /** New controls must be added to saved layouts without changing existing choices. */
    private fun String.addMissingClipAction(): String =
        if (split(',').any { it.substringBefore(':') == "clip" }) this else "$this,clip:quick"

    internal fun defaultControlLayout(): String = controlSources.joinToString(",") {
        "${it.action}:${controlGroup(it.quickDefault, it.menuDefault)}"
    }

    private fun controlLayout(preferences: SharedPreferences): String = controlSources.joinToString(",") { source ->
        val quick = source.quickKey?.let { preferences.getBoolean(it, source.quickDefault) } ?: false
        val menu = source.menuKey?.let { preferences.getBoolean(it, source.menuDefault) } ?: false
        "${source.action}:${controlGroup(quick, menu)}"
    }

    /**
     * The first redesign migration could persist every old visibility key as false when
     * there was no previous preference value. Treat that unusable all-hidden state as a
     * migration artifact, while retaining deliberate partial customizations.
     */
    private fun legacyControlPreferencesAllDisabled(preferences: SharedPreferences): Boolean = controlSources.all { source ->
        val quickDisabled = source.quickKey?.let { preferences.contains(it) && !preferences.getBoolean(it, false) } ?: true
        val menuDisabled = source.menuKey?.let { preferences.contains(it) && !preferences.getBoolean(it, false) } ?: true
        quickDisabled && menuDisabled
    }

    private fun SharedPreferences.Editor.syncLegacyControlVisibility(serializedLayout: String) {
        val groups = serializedLayout.split(',').mapNotNull { item ->
            val parts = item.split(':', limit = 2)
            parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { action ->
                action to (parts.getOrNull(1) ?: "hidden")
            }
        }.toMap()
        controlSources.forEach { source ->
            val group = groups[source.action] ?: "hidden"
            source.quickKey?.let { putBoolean(it, group == "quick") }
            source.menuKey?.let { putBoolean(it, group == "menu") }
        }
        putBoolean(C.PLAYER_MENU, controlSources.any { groups[it.action] == "menu" })
    }
}
