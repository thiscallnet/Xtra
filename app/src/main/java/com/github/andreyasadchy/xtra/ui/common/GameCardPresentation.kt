package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.util.LruCache
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GameCardPresentation(
    val key: GameCardPresentationKey,
    val boxArt: String?,
    val name: String?,
    val viewerLabel: String?,
    val broadcasterLabel: String?,
    val tags: List<Tag>,
)

internal data class GameCardPresentationKey(
    val id: String?,
    val slug: String?,
    val name: String?,
    val boxArtURL: String?,
    val viewerCount: Int?,
    val broadcasterCount: Int?,
    val tags: List<Tag>?,
    val preferences: FeedUiPreferences,
)

/** Prepares immutable game-card labels away from RecyclerView binding. */
internal object GameCardPresentationCache {
    private const val MAX_ENTRIES = 512
    private const val PREWARM_LIMIT = 32

    private val scope = FeedPresentationDispatcher.scope
    private val lock = Any()
    private val cache = object : LruCache<GameCardPresentationKey, GameCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<GameCardPresentationKey, MutableList<(GameCardPresentation) -> Unit>>()

    fun key(game: Game, preferences: FeedUiPreferences): GameCardPresentationKey =
        GameCardPresentationKey(
            id = game.id,
            slug = game.slug,
            name = game.name,
            boxArtURL = game.boxArtURL,
            viewerCount = game.viewerCount,
            broadcasterCount = game.broadcasterCount,
            tags = game.tags?.toList(),
            preferences = preferences,
        )

    fun get(game: Game, preferences: FeedUiPreferences): GameCardPresentation? =
        synchronized(lock) { cache.get(key(game, preferences)) }

    fun prewarm(context: Context, games: List<Game>, preferences: FeedUiPreferences) {
        games.take(PREWARM_LIMIT).forEach { game ->
            request(context, game, preferences)
        }
    }

    internal fun request(
        context: Context,
        game: Game,
        preferences: FeedUiPreferences,
        callback: ((GameCardPresentation) -> Unit)? = null,
    ): GameCardPresentation? {
        val key = key(game, preferences)
        synchronized(lock) {
            cache.get(key)?.let { return it }
            pending[key]?.let {
                callback?.let(it::add)
                return null
            }
            pending[key] = callback?.let(::mutableListOf) ?: mutableListOf()
        }
        val applicationContext = context.applicationContext
        scope.launch {
            val presentation = build(applicationContext, game, key)
            val callbacks = synchronized(lock) {
                cache.put(key, presentation)
                pending.remove(key).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    callbacks.forEach { it(presentation) }
                }
            }
        }
        return null
    }

    private fun build(
        context: Context,
        game: Game,
        key: GameCardPresentationKey,
    ): GameCardPresentation {
        val viewerLabel = game.viewerCount?.let { count ->
            context.resources.getQuantityString(
                R.plurals.viewers,
                count,
                TwitchApiHelper.formatCount(count, key.preferences.truncateViewCount),
            )
        }
        val broadcasterLabel = if (key.preferences.showBroadcastersCount) {
            game.broadcasterCount?.let { count ->
                context.resources.getQuantityString(
                    R.plurals.broadcasters,
                    count,
                    TwitchApiHelper.formatCount(count, key.preferences.truncateViewCount),
                )
            }
        } else {
            null
        }
        return GameCardPresentation(
            key = key,
            boxArt = game.boxArt,
            name = game.name,
            viewerLabel = viewerLabel,
            broadcasterLabel = broadcasterLabel,
            tags = if (key.preferences.showTags) game.tags.orEmpty().toList() else emptyList(),
        )
    }
}
