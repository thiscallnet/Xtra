package com.github.andreyasadchy.xtra.util.viewingstats

import android.os.SystemClock

interface ViewingStatsClock {
    fun elapsedRealtime(): Long
    fun currentTimeMillis(): Long
}

object SystemViewingStatsClock : ViewingStatsClock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
