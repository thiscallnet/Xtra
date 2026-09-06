package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.EmoteUsageDao
import com.github.andreyasadchy.xtra.model.chat.EmoteUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class EmoteUsageIncrement(
    val viewerId: String,
    val usageKey: String,
    val provider: String,
    val emoteId: String,
    val scope: String,
    val channelId: String?,
    val count: Long,
    val lastUsedAt: Long,
)

class EmoteUsageRepository(
    private val dao: EmoteUsageDao,
) {
    fun observeForChannel(viewerId: String, channelId: String): Flow<List<EmoteUsage>> =
        dao.observeForChannel(viewerId, channelId)

    suspend fun loadForChannel(viewerId: String, channelId: String): List<EmoteUsage> = withContext(Dispatchers.IO) {
        dao.getForChannel(viewerId, channelId)
    }

    suspend fun record(increments: Collection<EmoteUsageIncrement>) = withContext(Dispatchers.IO) {
        increments.forEach { increment ->
            dao.increment(
                viewerId = increment.viewerId,
                usageKey = increment.usageKey,
                provider = increment.provider,
                emoteId = increment.emoteId,
                scope = increment.scope,
                channelId = increment.channelId,
                count = increment.count,
                lastUsedAt = increment.lastUsedAt,
            )
        }
    }
}
