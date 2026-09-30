package com.github.andreyasadchy.xtra.player.hls

import android.content.Context
import android.os.SystemClock
import com.github.andreyasadchy.xtra.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opt-in debug capture. Raw URLs contain playback tokens; never send these to logcat. */
object VaftPlaylistCapture {
    @Volatile private var directory: File? = null
    private val sequence = AtomicLong()
    private val writer by lazy {
        ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(32),
            { task -> Thread(task, "vaft-playlist-capture").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }
    }

    val isEnabled: Boolean get() = BuildConfig.DEBUG && directory != null

    fun initialize(context: Context) {
        if (BuildConfig.DEBUG && context.getSharedPreferences("vaft_playlist_capture", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)) {
            setDirectory(File(context.cacheDir, "vaft-playlists"))
        }
    }

    fun setDirectory(value: File?) {
        if (BuildConfig.DEBUG) directory = value
    }

    fun record(uri: String, text: String, stage: String, status: Int? = null) {
        if (!BuildConfig.DEBUG) return
        val target = directory ?: return
        if (text.length > 512 * 1024) return
        val wallTime = System.currentTimeMillis()
        val elapsedTime = SystemClock.elapsedRealtime()
        val index = sequence.incrementAndGet()
        writer.execute {
            // Disabling capture also discards queued records.
            if (directory != target) return@execute
            runCatching {
                target.mkdirs()
                val record = JSONObject().put("timeMs", wallTime).put("elapsedMs", elapsedTime)
                    .put("stage", stage).put("uri", uri).put("status", status ?: JSONObject.NULL)
                    .put("playlist", text)
                File(target, "$wallTime-$index.json").writeText(record.toString())
                val files = target.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty()
                var bytes = files.sumOf { it.length() }
                var count = files.size
                for (file in files) {
                    if (count <= 512 && bytes <= 32L * 1024 * 1024) break
                    val size = file.length()
                    if (file.delete()) { bytes -= size; count-- }
                }
            }
        }
    }
}
