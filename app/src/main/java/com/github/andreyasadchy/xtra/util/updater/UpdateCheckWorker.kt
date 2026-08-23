package com.github.andreyasadchy.xtra.util.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext.applicationContext as? XtraApp
            ?: return Result.success()
        if (application.isInForeground) return Result.success()

        val repository = application.xtraModule.updateRepository
        repository.checkIfDueAndWait(
            application.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            C.DEFAULT_UPDATE_URL,
            force = runAttemptCount > 0,
        )
        return if (repository.state.value is UpdateState.Error &&
            (repository.state.value as UpdateState.Error).retryable
        ) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
