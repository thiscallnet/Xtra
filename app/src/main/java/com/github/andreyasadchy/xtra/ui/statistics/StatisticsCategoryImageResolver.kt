package com.github.andreyasadchy.xtra.ui.statistics

import android.content.Context
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.viewingstats.CategoryWatchTotal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loads missing category art for existing statistics rows from Twitch's Games endpoint. */
class StatisticsCategoryImageResolver(
    private val context: Context,
    private val helixRepository: HelixRepository,
) {
    private val imageByCategoryId = ConcurrentHashMap<String, String>()
    private val resolvedCategoryIds = ConcurrentHashMap.newKeySet<String>()
    private val resolveMutex = Mutex()

    suspend fun resolve(categories: List<CategoryWatchTotal>): List<CategoryWatchTotal> = resolveMutex.withLock {
        val missingIds = categories
            .filter { it.categoryImage.isNullOrBlank() }
            .mapNotNull { it.categoryId?.takeIf(String::isNotBlank) }
            .distinct()
            .filterNot(resolvedCategoryIds::contains)

        missingIds.chunked(MAX_IDS_PER_REQUEST).forEach { ids ->
            try {
                val response = helixRepository.getGames(
                    networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers = TwitchApiHelper.getHelixHeaders(context),
                    ids = ids,
                )
                resolvedCategoryIds.addAll(ids)
                response.data.forEach { game ->
                    val image = game.boxArtURL?.takeIf(String::isNotBlank)
                        ?.let(TwitchApiHelper::getGameBoxArt)
                        ?.takeIf(String::isNotBlank)
                    if (image != null && !game.id.isNullOrBlank()) {
                        imageByCategoryId[game.id] = image
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep categories usable with their normal placeholder when art is temporarily unavailable.
            }
        }

        categories.map { category ->
            if (!category.categoryImage.isNullOrBlank()) return@map category
            val image = category.categoryId?.let(imageByCategoryId::get) ?: return@map category
            category.copy(categoryImage = image)
        }
    }

    private companion object {
        const val MAX_IDS_PER_REQUEST = 100
    }
}
