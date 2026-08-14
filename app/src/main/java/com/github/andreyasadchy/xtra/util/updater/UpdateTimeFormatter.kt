package com.github.andreyasadchy.xtra.util.updater

import android.content.Context
import android.text.format.DateUtils
import com.github.andreyasadchy.xtra.R
import java.text.DateFormat
import java.util.Date

object UpdateTimeFormatter {
    fun format(context: Context, timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return context.getString(R.string.never)
        val day = 86_400_000L
        val age = (now - timestamp).coerceAtLeast(0L)
        return when {
            age < 60_000L -> context.getString(R.string.just_now)
            age < day -> DateUtils.getRelativeTimeSpanString(
                timestamp,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                0,
            ).toString()
            age < day * 2 -> context.getString(R.string.yesterday)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
        }
    }
}
