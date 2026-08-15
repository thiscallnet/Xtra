package com.github.andreyasadchy.xtra.repository.streamfeed

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException

internal fun shouldPrewarm(isForeground: Boolean, cacheFresh: Boolean): Boolean {
    return !isForeground && !cacheFresh
}

/** One best-effort refresh of only the feeds useful on the next launch. */
class StreamFeedPrewarmWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext.applicationContext as? XtraApp
            ?: return Result.success()
        if (application.isInForeground) return Result.success()

        val module = application.xtraModule
        val savedTop = module.gameSortRepository.getById("top_streams")
        val top = StreamFeedSpecs.top(
            context = application,
            graphQLRepository = module.graphQLRepository,
            helixRepository = module.helixRepository,
            sort = savedTop?.streamSort ?: StreamsSortDialog.SORT_VIEWERS,
            tags = savedTop?.streamTags?.split(',')?.takeIf { it.isNotEmpty() },
            languages = savedTop?.streamLanguages?.split(',')?.takeIf { it.isNotEmpty() },
        )
        val followed = StreamFeedSpecs.followed(
            context = application,
            userId = application.tokenPrefs().getString(C.USER_ID, null),
            localChannelFollowsRepository = module.localChannelFollowsRepository,
            graphQLRepository = module.graphQLRepository,
            helixRepository = module.helixRepository,
        )

        prewarmIfStale(application, top)
        if (!application.isInForeground) {
            prewarmIfStale(application, followed)
        }
        return Result.success()
    }

    private suspend fun prewarmIfStale(application: XtraApp, spec: StreamFeedSpec) {
        bestEffort {
            val state = application.xtraModule.streamFeedCache.state(spec.key)
            val fresh = StreamFeedFreshnessPolicy.isFresh(
                System.currentTimeMillis(),
                state?.lastSuccessAt,
            )
            if (shouldPrewarm(application.isInForeground, fresh)) {
                application.xtraModule.streamFeedRefreshCoordinator.maybeRefresh(
                    spec,
                    RefreshReason.BACKGROUND_PREWARM,
                )
            }
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Prewarm is opportunistic; foreground SWR remains authoritative.
        }
    }
}
