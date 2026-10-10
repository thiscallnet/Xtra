package com.github.andreyasadchy.xtra.ui.search

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Remembers what the user opened from search. Outlives the screen, so leaving never drops it. */
object SearchHistoryRecorder {

    fun record(context: Context, item: SearchHistoryItem?) {
        item ?: return
        if (!context.prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) return
        val app = context.applicationContext as? XtraApp ?: return
        app.applicationScope.launch {
            try {
                app.xtraModule.searchHistoryRepository.recordOpened(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SearchHistory", "Could not save opened item", e)
            }
        }
    }

    /** Opens whatever a row stands for: a live stream, a video, or a remembered channel/category. */
    fun open(fragment: Fragment, entry: SearchRow.Entry) {
        val context = fragment.requireContext()
        val activity = fragment.activity as? MainActivity
        when {
            entry.stream != null -> {
                channel(context, entry.stream)
                activity?.startStream(entry.stream)
            }
            entry.video != null -> {
                video(context, entry.video)
                activity?.startVideo(entry.video, null)
            }
            else -> open(fragment, entry.item)
        }
    }

    /** Opens a remembered channel/category directly and bumps it in the ranking. */
    fun open(fragment: Fragment, item: SearchHistoryItem) {
        record(fragment.requireContext(), item)  // bumps count and recency to now
        val id = item.refId.takeIf { it != item.slug }
        if (item.isVideo) {
            item.toVideo()?.let { (fragment.activity as? MainActivity)?.startVideo(it, null) }
        } else if (item.isChannel) {
            fragment.findNavController().navigate(
                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                    channelId = id,
                    channelLogin = item.slug,
                    channelName = item.title,
                    channelImage = item.imageUrl,
                )
            )
        } else {
            fragment.findNavController().navigate(
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                    gameId = id,
                    gameSlug = item.slug,
                    gameName = item.title,
                    boxArt = item.imageUrl,
                )
            )
        }
    }

    fun channel(context: Context, user: User) =
        record(context, SearchHistoryItem.channel(user.id, user.login, user.name, user.profileImage))

    fun channel(context: Context, stream: Stream) =
        record(context, SearchHistoryItem.channel(stream.channelId, stream.channelLogin, stream.channelName, stream.channelImage))

    fun channel(context: Context, video: Video) =
        record(context, SearchHistoryItem.channel(video.channelId, video.channelLogin, video.channelName, video.channelImage))

    fun video(context: Context, video: Video) = record(context, SearchHistoryItem.video(video))

    fun game(context: Context, game: Game) =
        record(context, SearchHistoryItem.game(game.id, game.slug, game.name, game.boxArt))

    fun game(context: Context, stream: Stream) =
        record(context, SearchHistoryItem.game(stream.gameId, stream.gameSlug, stream.gameName, null))

    fun game(context: Context, video: Video) =
        record(context, SearchHistoryItem.game(video.gameId, video.gameSlug, video.gameName, null))
}
