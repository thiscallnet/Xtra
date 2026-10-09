package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.ChannelStreamStartsDatabase
import com.github.andreyasadchy.xtra.model.ChannelStreamStart
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Instant

/** Local log of when followed channels went live, used to predict their next start. */
class ChannelStreamStartsRepository(
    private val database: ChannelStreamStartsDatabase,
    private val pendingImportFile: File,
) {
    private val dao = database.channelStreamStarts()
    private val importMutex = Mutex()
    private val _cleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after the history is cleared so open screens can drop what they derived from it. */
    val cleared: SharedFlow<Unit> = _cleared

    suspend fun recordLiveStreams(streams: List<Stream>) {
        record(streams.mapNotNull { stream ->
            val channelId = stream.channelId ?: return@mapNotNull null
            val startedAt = stream.createdAt?.let(Instant::parseOrNull)?.toEpochMilliseconds() ?: return@mapNotNull null
            ChannelStreamStart(channelId, roundToMinute(startedAt))
        })
    }

    suspend fun recordStartTimes(channelIdToStartedAtMs: List<Pair<String, Long>>) {
        record(channelIdToStartedAtMs.map { (channelId, startedAt) ->
            ChannelStreamStart(channelId, roundToMinute(startedAt))
        })
    }

    suspend fun startsSince(sinceMs: Long): List<ChannelStreamStart> = withContext(Dispatchers.IO) {
        importPendingRestore()
        dao.getSince(sinceMs)
    }

    suspend fun allStarts(): List<ChannelStreamStart> = withContext(Dispatchers.IO) {
        importPendingRestore()
        dao.getAll()
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                pendingImportFile.delete()
                dao.deleteAll()
            }
        }
        _cleared.tryEmit(Unit)
    }

    private suspend fun record(starts: List<ChannelStreamStart>) = withContext(Dispatchers.IO) {
        if (starts.isEmpty()) return@withContext
        importPendingRestore()
        dao.insertAll(starts)
        dao.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
    }

    /** Replaces the history with a restored backup the first time the repository is used afterwards. */
    private suspend fun importPendingRestore() {
        if (!pendingImportFile.isFile) return
        importMutex.withLock {
            if (!pendingImportFile.isFile) return@withLock
            val starts = try {
                StreamStartsBackup.read(pendingImportFile)
            } catch (_: Exception) {
                null
            }
            if (starts != null) {
                database.runInTransaction {
                    dao.deleteAll()
                    dao.insertAll(starts)
                }
            }
            pendingImportFile.delete()
        }
    }

    private fun roundToMinute(epochMs: Long): Long = epochMs - epochMs % MINUTE_MS

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val RETENTION_MS = 60L * 24 * 60 * MINUTE_MS
    }
}
