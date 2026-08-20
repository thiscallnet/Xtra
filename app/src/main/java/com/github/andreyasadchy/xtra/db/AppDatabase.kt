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
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.model.ui.TranslatedChannel

@Database(
    entities = [
        OfflineVideo::class,
        RecentEmote::class,
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
        RecentSearch::class,
        PlaybackState::class,
        ViewingSession::class,
        ViewingInterval::class,
        CachedStreamFeedItem::class,
        StreamFeedState::class,
        MetadataCacheEntry::class,
    ],
    version = 48,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun offlineVideos(): OfflineVideosDao
    abstract fun recentEmotes(): RecentEmotesDao
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
    abstract fun recentSearches(): RecentSearchesDao
    abstract fun playbackStates(): PlaybackStatesDao
    abstract fun viewingStats(): ViewingStatsDao
    abstract fun streamFeedDao(): StreamFeedDao
    abstract fun metadataCache(): MetadataCacheDao
}
