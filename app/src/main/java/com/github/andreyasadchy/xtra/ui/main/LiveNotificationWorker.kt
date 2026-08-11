package com.github.andreyasadchy.xtra.ui.main

import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import androidx.core.content.edit

class LiveNotificationWorker(
    private val context: android.content.Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val monitor = LiveNotificationMonitor(context)

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        val prefs = context.prefs()
        prefs.edit {
            putLong(C.LIVE_NOTIFICATION_LAST_RUN, startedAt)
        }

        val baselineOnly = inputData.getBoolean(INPUT_BASELINE_ONLY, false)

        try {
            val result = monitor.poll(baselineOnly = baselineOnly)
            recordSuccess(startedAt, result.delivered, result.api)
            Log.d(TAG, "Live notification reconciliation completed in ${SystemClock.elapsedRealtime() - startElapsed}ms; delivered=${result.delivered}")
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(startedAt, e)
            return Result.retry()
        }
    }

    private fun recordSuccess(startedAt: Long, delivered: Int, api: String) {
        context.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_SUCCESS, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_API, api)
            putInt(C.LIVE_NOTIFICATION_LAST_EVENT_COUNT, delivered)
            remove(C.LIVE_NOTIFICATION_LAST_ERROR)
        }
        Log.d(TAG, "Live notification worker succeeded; elapsed=${System.currentTimeMillis() - startedAt}ms; delivered=$delivered")
    }

    private fun recordFailure(startedAt: Long, error: Exception) {
        val message = "${error::class.simpleName}: ${error.message.orEmpty()}".take(500)
        context.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_ERROR_AT, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_ERROR, message)
        }
        Log.w(TAG, "Live notification worker failed after ${System.currentTimeMillis() - startedAt}ms", error)
    }

    companion object {
        private const val TAG = "LiveNotificationWorker"
        const val INPUT_BASELINE_ONLY = "baseline_only"
    }
}
