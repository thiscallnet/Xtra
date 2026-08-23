package com.github.andreyasadchy.xtra.ui.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.RandomAccessFile

internal object DownloadIo {
    fun resizeLocalFile(path: String, length: Long) {
        require(length >= 0) { "Download length cannot be negative" }
        RandomAccessFile(path, "rw").use { file ->
            file.setLength(length)
            file.seek(length)
        }
    }

    suspend fun <T, R> forEachParallelOrdered(
        items: Iterable<T>,
        concurrency: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        fetch: suspend (T) -> R,
        consume: suspend (T, R) -> Unit,
    ) = coroutineScope {
        require(concurrency > 0) { "Concurrency must be positive" }
        val pending = ArrayDeque<Pair<T, kotlinx.coroutines.Deferred<R>>>(concurrency)

        suspend fun consumeFirst() {
            val (item, result) = pending.removeFirst()
            consume(item, result.await())
        }

        for (item in items) {
            pending.addLast(item to async(dispatcher) { fetch(item) })
            if (pending.size >= concurrency) {
                consumeFirst()
            }
        }
        while (pending.isNotEmpty()) {
            consumeFirst()
        }
    }
}
