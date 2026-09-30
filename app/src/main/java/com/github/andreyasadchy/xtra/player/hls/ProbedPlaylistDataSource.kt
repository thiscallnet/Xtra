package com.github.andreyasadchy.xtra.player.hls

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.github.andreyasadchy.xtra.BuildConfig

/** One-use handoff seeds, never a cache for ongoing live playlist refreshes. */
object ProbedPlaylists {
    data class Snapshot(val uri: Uri, val bytes: ByteArray, val elapsedMs: Long)
    private val snapshots = LinkedHashMap<String, Snapshot>()

    @Synchronized
    fun remember(requestUri: String, responseUri: String, text: String) {
        if (!text.startsWith("#EXTM3U") || text.length > 512 * 1024) return
        val now = SystemClock.elapsedRealtime()
        snapshots.entries.removeAll { now - it.value.elapsedMs > 2_000L }
        snapshots[requestUri] = Snapshot(Uri.parse(responseUri), text.toByteArray(Charsets.UTF_8), now)
        while (snapshots.size > 8) snapshots.remove(snapshots.keys.first())
    }

    @Synchronized
    fun take(uri: String): Snapshot? = snapshots.remove(uri)?.takeIf {
        SystemClock.elapsedRealtime() - it.elapsedMs <= 2_000L
    }
}

/** Uses the response already inspected by VAFT for the first load only. */
class ProbedPlaylistDataSource(private val upstream: DataSource) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val seed = if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null) {
            ProbedPlaylists.take(dataSpec.uri.toString())
        } else null
        if (seed != null && BuildConfig.DEBUG) Log.d("XtraVaft", "handoff playlist seed reused ageMs=${SystemClock.elapsedRealtime() - seed.elapsedMs}")
        val source = if (seed != null) ByteArrayDataSource(seed.bytes).also { memory ->
            listeners.forEach(memory::addTransferListener)
        } else upstream
        active = source
        return source.open(if (seed != null) dataSpec.withUri(seed.uri) else dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() {
        try { active?.close() } finally { active = null }
    }
}
