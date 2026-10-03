package com.github.andreyasadchy.xtra.repository

import android.util.LruCache
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Instant

/** Completes optional start timestamps omitted by persisted popular-stream responses. */
class StreamCreatedAtHydrator(
    private val graphQLRepository: GraphQLRepository,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val successByStreamId = LruCache<String, String>(2048)
    private val inFlightByStreamId = mutableMapOf<String, CompletableDeferred<String?>>()

    suspend fun hydrateMissingCreatedAt(
        streams: List<Stream>,
        networkLibrary: String?,
        headers: Map<String, String>,
    ): List<Stream> {
        val missing = streams.filter {
            it.createdAt == null && !it.id.isNullOrBlank() && !it.channelId.isNullOrBlank()
        }.distinctBy { it.id }
        if (missing.isEmpty()) return streams

        val resolved = mutableMapOf<String, String>()
        val flights = mutableMapOf<String, CompletableDeferred<String?>>()
        val claimed = mutableListOf<Stream>()
        synchronized(lock) {
            missing.forEach { stream ->
                val id = stream.id!!
                val cached = successByStreamId.get(id)
                if (cached != null) {
                    resolved[id] = cached
                } else {
                    flights[id] = inFlightByStreamId.getOrPut(id) {
                        claimed.add(stream)
                        CompletableDeferred()
                    }
                }
            }
        }

        // Optional metadata must not hold a valid feed hostage to a slow request.
        // A screen owns only its await; cancelling it cannot cancel another screen's lookup.
        if (claimed.isNotEmpty()) {
            scope.launch {
                withTimeoutOrNull(1500L) {
                    claimed.chunked(100).forEach { batch ->
                        val users = try {
                            graphQLRepository.loadQueryUsersStream(
                                networkLibrary, headers, ids = batch.mapNotNull { it.channelId }.distinct(),
                            ).data?.users.orEmpty().filterNotNull().associateBy { it.id }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        batch.forEach { stream ->
                            val user = users[stream.channelId]
                            val timestamp = user?.stream?.takeIf { it.id == stream.id }
                                ?.createdAt?.toString()?.takeIf {
                                    Instant.parseOrNull(it)?.toEpochMilliseconds()?.let { ms -> ms > 0L } == true
                                }
                            finish(stream.id!!, flights.getValue(stream.id!!), timestamp)
                        }
                    }
                }
            }.invokeOnCompletion {
                // Also release flights if the application scope was cancelled before launch.
                claimed.forEach { finish(it.id!!, flights.getValue(it.id!!), null) }
            }
        }

        flights.forEach { (id, flight) -> flight.await()?.let { resolved[id] = it } }
        return streams.map { stream ->
            if (stream.createdAt == null) {
                resolved[stream.id]?.let { stream.withCreatedAt(it) } ?: stream
            } else stream
        }
    }

    private fun finish(id: String, flight: CompletableDeferred<String?>, timestamp: String?) {
        synchronized(lock) {
            if (inFlightByStreamId[id] !== flight) return
            if (timestamp != null) successByStreamId.put(id, timestamp)
            inFlightByStreamId.remove(id)
            flight.complete(timestamp)
        }
    }
}
