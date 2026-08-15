package com.github.andreyasadchy.xtra.repository.streamfeed

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.concurrent.TimeUnit

object StreamFeedPrewarmScheduler {
    private const val WORK_NAME = "stream-feed-prewarm"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val predictedReturn = appContext.prefs().getLong(C.STREAM_FEED_RETURN_INTERVAL_MS, 0L)
            .takeIf { it > 0L }
        val delay = StreamFeedFreshnessPolicy.prewarmDelayMs(predictedReturn)
        val request = OneTimeWorkRequestBuilder<StreamFeedPrewarmWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun recordBackgroundReturn(context: Context, awayMs: Long) {
        if (awayMs <= 0L) return
        val prefs = context.applicationContext.prefs()
        val previous = prefs.getLong(C.STREAM_FEED_RETURN_INTERVAL_MS, 0L).takeIf { it > 0L }
        val samples = prefs.getInt(C.STREAM_FEED_RETURN_SAMPLE_COUNT, 0)
        prefs.edit {
            putLong(
                C.STREAM_FEED_RETURN_INTERVAL_MS,
                StreamFeedFreshnessPolicy.updateReturnIntervalEwma(previous, awayMs),
            )
            putInt(C.STREAM_FEED_RETURN_SAMPLE_COUNT, (samples + 1).coerceAtMost(Int.MAX_VALUE))
        }
    }
}
