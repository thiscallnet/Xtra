package com.github.andreyasadchy.xtra.util.updater

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.concurrent.TimeUnit

object UpdateCheckScheduler {
    private const val WORK_NAME = "update-checks"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.prefs()
        if (!preferences.getBoolean(C.UPDATE_CHECK_ENABLED, true)) {
            cancel(appContext)
            return
        }
        val frequency = UpdateCheckFrequency.fromPreference(
            preferences.getString(C.UPDATE_CHECK_FREQUENCY, null),
        )
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            frequency.intervalMillis,
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
