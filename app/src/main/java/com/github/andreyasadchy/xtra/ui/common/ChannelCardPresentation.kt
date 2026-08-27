package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.util.LruCache
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/** Immutable, bind-ready values for a followed-channel row. */
internal data class ChannelCardPresentation(
    val key: ChannelCardPresentationKey,
    val username: String?,
    val lastBroadcast: String?,
    val followedAt: String?,
)

internal data class ChannelCardPresentationKey(
    val id: String?,
    val login: String?,
    val name: String?,
    val lastBroadcastValue: String?,
    val followedAtValue: String?,
    val preferences: FeedUiPreferences,
)

/** Keeps timestamp and display-name work off the RecyclerView bind path. */
internal object ChannelCardPresentationCache {
    private const val MAX_ENTRIES = 512
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(2),
    )
    private val lock = Any()
    private val cache = object : LruCache<ChannelCardPresentationKey, ChannelCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<ChannelCardPresentationKey, MutableList<(ChannelCardPresentation) -> Unit>>()

    fun key(user: User, preferences: FeedUiPreferences): ChannelCardPresentationKey =
        ChannelCardPresentationKey(
            id = user.id,
            login = user.login,
            name = user.name,
            lastBroadcastValue = user.lastBroadcast,
            followedAtValue = user.followedAt,
            preferences = preferences,
        )

    fun get(user: User, preferences: FeedUiPreferences): ChannelCardPresentation? =
        synchronized(lock) { cache.get(key(user, preferences)) }

    fun request(
        context: Context,
        user: User,
        preferences: FeedUiPreferences,
        callback: (ChannelCardPresentation) -> Unit,
    ): ChannelCardPresentation? {
        val key = key(user, preferences)
        synchronized(lock) {
            cache.get(key)?.let { return it }
            pending[key]?.let {
                it += callback
                return null
            }
            pending[key] = mutableListOf(callback)
        }
        val applicationContext = context.applicationContext
        scope.launch {
            val presentation = build(applicationContext, user, key)
            val callbacks = synchronized(lock) {
                cache.put(key, presentation)
                pending.remove(key).orEmpty()
            }
            withContext(Dispatchers.Main.immediate) {
                callbacks.forEach { it(presentation) }
            }
        }
        return null
    }

    private fun build(
        context: Context,
        user: User,
        key: ChannelCardPresentationKey,
    ): ChannelCardPresentation {
        val username = user.name?.let { name ->
            if (user.login != null && !user.login.equals(name, true)) {
                when (key.preferences.nameDisplay) {
                    "0" -> "$name(${user.login})"
                    "1" -> name
                    else -> user.login
                }
            } else {
                name
            }
        }
        return ChannelCardPresentation(
            key = key,
            username = username,
            lastBroadcast = formatDateLabel(context, user.lastBroadcast, R.string.last_broadcast_date),
            followedAt = formatDateLabel(context, user.followedAt, R.string.followed_at),
        )
    }

    private fun formatDateLabel(context: Context, value: String?, resourceId: Int): String? =
        value?.let { rawValue ->
            Instant.parseOrNull(rawValue)?.toEpochMilliseconds()?.takeIf { it > 0 }?.let { timestamp ->
                context.getString(resourceId, TwitchApiHelper.formatDate(context, timestamp))
            }
        }
}
