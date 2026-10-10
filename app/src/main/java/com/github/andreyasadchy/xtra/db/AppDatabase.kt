package com.github.andreyasadchy.xtra.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import com.github.andreyasadchy.xtra.model.chat.EmoteUsage
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.BookmarkIgnoredUser
import com.github.andreyasadchy.xtra.model.ui.ChannelSort
import com.github.andreyasadchy.xtra.model.ui.GameSort
import com.github.andreyasadchy.xtra.model.ui.LocalChannelFollow
import com.github.andreyasadchy.xtra.model.ui.LocalGameFollow
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.model.ui.TranslatedChannel

@Database(
    entities = [
        OfflineVideo::class,
        RecentEmote::class,
        EmoteUsage::class,
        FavoriteEmote::class,
        VideoPosition::class,
        VideoHistory::class,
        LocalChannelFollow::class,
        LocalGameFollow::class,
        Bookmark::class,
        BookmarkIgnoredUser::class,
        ChannelSort::class,
        GameSort::class,
        ShownNotification::class,
        NotificationUser::class,
        NotificationEvent::class,
        TranslatedChannel::class,
        SavedFilter::class,
        SearchHistoryItem::class,
        PlaybackState::class,
        ViewingSession::class,
        ViewingInterval::class,
        CachedStreamFeedItem::class,
        StreamFeedState::class,
        CachedGameFeedItem::class,
        GameFeedState::class,
        MetadataCacheEntry::class,
    ],
    version = 55,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        const val VERSION = 55
        const val IDENTITY_HASH = "f5c1a07061d418a66e64a88784ca33a4"
    }

    abstract fun offlineVideos(): OfflineVideosDao
    abstract fun recentEmotes(): RecentEmotesDao
    abstract fun emoteUsage(): EmoteUsageDao
    abstract fun favoriteEmotes(): FavoriteEmotesDao
    abstract fun videoPositions(): VideoPositionsDao
    abstract fun videoHistory(): VideoHistoryDao
    abstract fun localChannelFollows(): LocalChannelFollowsDao
    abstract fun localGameFollows(): LocalGameFollowsDao
    abstract fun bookmarks(): BookmarksDao
    abstract fun bookmarkIgnoredUsers(): BookmarkIgnoredUsersDao
    abstract fun channelSort(): ChannelSortDao
    abstract fun gameSort(): GameSortDao
    abstract fun shownNotifications(): ShownNotificationsDao
    abstract fun notificationUsers(): NotificationUsersDao
    abstract fun notificationEvents(): NotificationEventsDao
    abstract fun translatedChannels(): TranslatedChannelsDao
    abstract fun savedFilters(): SavedFiltersDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun playbackStates(): PlaybackStatesDao
    abstract fun viewingStats(): ViewingStatsDao
    abstract fun streamFeedDao(): StreamFeedDao
    abstract fun gameFeedDao(): GameFeedDao
    abstract fun metadataCache(): MetadataCacheDao
}
