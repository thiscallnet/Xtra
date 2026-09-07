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
import com.github.andreyasadchy.xtra.XtraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object StreamFeedPrewarmScheduler {
    private const val WORK_NAME = "stream-feed-prewarm"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val armController = StreamFeedPrewarmArmController(scope)

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val predictedReturn = appContext.prefs().getLong(C.STREAM_FEED_RETURN_INTERVAL_MS, 0L)
            .takeIf { it > 0L }
        val delayMs = StreamFeedFreshnessPolicy.prewarmDelayMs(predictedReturn)
        armController.schedule(delayMs) {
            if ((appContext as? XtraApp)?.isInForeground != true) {
                enqueueWork(appContext)
            }
        }
    }

    private fun enqueueWork(appContext: Context) {
        val request = OneTimeWorkRequestBuilder<StreamFeedPrewarmWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        armController.cancel()
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

internal class StreamFeedPrewarmArmController(
    private val scope: CoroutineScope,
    private val delayBlock: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var armEpoch = 0L
    private var armJob: Job? = null

    fun schedule(delayMs: Long, onReadyToEnqueue: () -> Unit) {
        synchronized(lock) {
            val epoch = ++armEpoch
            armJob?.cancel()
            armJob = scope.launch {
                delayBlock(delayMs)
                synchronized(lock) {
                    if (epoch != armEpoch) return@launch
                    armJob = null
                    onReadyToEnqueue()
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            ++armEpoch
            armJob?.cancel()
            armJob = null
        }
    }
}
