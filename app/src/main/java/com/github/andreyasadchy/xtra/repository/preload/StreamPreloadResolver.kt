package com.github.andreyasadchy.xtra.repository.preload

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class StreamPreloadFlightKey(
    val configurationFingerprint: String,
    val channelLogin: String,
)

/** Owns resolver requests so viewport jobs and playback can share or cancel them. */
internal class StreamPreloadResolver(
    private val scope: CoroutineScope,
    maxConcurrency: Int = StreamPreloadPolicy.MAX_RESOLVER_CONCURRENCY,
    private val failureBackoffMs: Long = StreamPreloadPolicy.FAILURE_BACKOFF_MS,
    private val elapsedRealtimeMs: () -> Long,
    private val canStart: () -> Boolean,
    private val isEligible: (String) -> Boolean,
    private val onResolved: (StreamPreloadFlightKey, String) -> Unit = { _, _ -> },
    private val onFailed: (StreamPreloadFlightKey, Throwable) -> Unit = { _, _ -> },
) {
    private val semaphore = Semaphore(maxConcurrency)
    private val flights = ConcurrentHashMap<StreamPreloadFlightKey, Flight>()
    private val failureUntil = ConcurrentHashMap<StreamPreloadFlightKey, Long>()

    suspend fun preload(
        channelLogin: String,
        streamKey: String,
        configurationFingerprint: String,
        resolve: suspend () -> String?,
    ): String? {
        val key = key(channelLogin, configurationFingerprint)
        if (!canStart() || !isEligible(streamKey) || isCoolingDown(key)) return null
        val flight = flights[key] ?: synchronized(flights) {
            flights[key] ?: createFlight(key, streamKey, resolve).also { flights[key] = it }
        }
        flight.owners.incrementAndGet()
        flight.deferred.start()
        return awaitOwned(flight)
    }

    suspend fun join(channelLogin: String, configurationFingerprint: String): String? {
        val flight = flights[key(channelLogin, configurationFingerprint)] ?: return null
        flight.owners.incrementAndGet()
        flight.deferred.start()
        return awaitOwned(flight)
    }

    fun promoteForPlayback(channelLogin: String, configurationFingerprint: String): Boolean {
        val flight = flights[key(channelLogin, configurationFingerprint)] ?: return false
        if (!flight.promotedForPlayback) {
            flight.promotedForPlayback = true
            flight.pendingPlaybackOwners.incrementAndGet()
            flight.owners.incrementAndGet()
        }
        flight.deferred.start()
        return true
    }

    suspend fun joinForPlayback(channelLogin: String, configurationFingerprint: String): String? {
        val flight = flights[key(channelLogin, configurationFingerprint)] ?: return null
        if (!flight.promotedForPlayback && !promoteForPlayback(channelLogin, configurationFingerprint)) return null
        if (!consumePendingPlaybackOwner(flight)) flight.owners.incrementAndGet()
        flight.playbackConsumers.incrementAndGet()
        flight.deferred.start()
        return try {
            flight.deferred.await()
        } finally {
            flight.playbackConsumers.decrementAndGet()
            if (flight.pendingPlaybackOwners.get() == 0 && flight.playbackConsumers.get() == 0) {
                flight.promotedForPlayback = false
            }
            releaseOwner(flight)
        }
    }

    fun cancelObsolete(
        configurationFingerprint: String,
        activeLogins: Set<String>,
        keepLogins: Set<String> = emptySet(),
    ) {
        flights.entries
            .filter { (key, flight) ->
                key.configurationFingerprint != configurationFingerprint ||
                    (!flight.promotedForPlayback &&
                        (key.channelLogin !in activeLogins && key.channelLogin !in keepLogins))
            }
            .forEach { (key, flight) -> cancel(key, flight) }
        failureUntil.keys.toList()
            .filter { it.configurationFingerprint != configurationFingerprint }
            .forEach(failureUntil::remove)
    }

    fun cancelAll(
        keepLogins: Set<String> = emptySet(),
        configurationFingerprint: String? = null,
    ) {
        flights.entries
            .filter { (key, _) ->
                (configurationFingerprint != null && key.configurationFingerprint != configurationFingerprint) ||
                    key.channelLogin !in keepLogins
            }
            .forEach { (key, flight) -> cancel(key, flight) }
        if (configurationFingerprint != null) {
            failureUntil.keys.toList()
                .filter { it.configurationFingerprint != configurationFingerprint }
                .forEach(failureUntil::remove)
        } else {
            failureUntil.clear()
        }
    }

    fun hasFlight(channelLogin: String, configurationFingerprint: String): Boolean =
        flights.containsKey(key(channelLogin, configurationFingerprint))

    private fun createFlight(
        key: StreamPreloadFlightKey,
        streamKey: String,
        resolve: suspend () -> String?,
    ): Flight {
        lateinit var flight: Flight
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            semaphore.withPermit {
                if (!flight.promotedForPlayback && (!canStart() || !isEligible(streamKey))) return@withPermit null
                val url = try {
                    resolve()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failureUntil[key] = elapsedRealtimeMs() + failureBackoffMs
                    onFailed(key, error)
                    null
                }
                if (url != null) onResolved(key, url)
                url
            }
        }
        flight = Flight(key, deferred)
        deferred.invokeOnCompletion { flights.remove(key, flight) }
        return flight
    }

    private suspend fun awaitOwned(flight: Flight): String? {
        try {
            return flight.deferred.await()
        } finally {
            releaseOwner(flight)
        }
    }

    private fun releaseOwner(flight: Flight) {
        if (flight.owners.decrementAndGet() == 0 &&
            !flight.promotedForPlayback &&
            !flight.deferred.isCompleted
        ) cancel(flight.key, flight)
    }

    private fun consumePendingPlaybackOwner(flight: Flight): Boolean {
        while (true) {
            val pending = flight.pendingPlaybackOwners.get()
            if (pending == 0) return false
            if (flight.pendingPlaybackOwners.compareAndSet(pending, pending - 1)) return true
        }
    }

    private fun cancel(key: StreamPreloadFlightKey, flight: Flight) {
        if (flights.remove(key, flight)) flight.deferred.cancel()
    }

    private fun isCoolingDown(key: StreamPreloadFlightKey): Boolean {
        val until = failureUntil[key] ?: return false
        if (until > elapsedRealtimeMs()) return true
        failureUntil.remove(key, until)
        return false
    }

    private fun key(channelLogin: String, configurationFingerprint: String) =
        StreamPreloadFlightKey(configurationFingerprint, channelLogin.trim().lowercase())

    private class Flight(
        val key: StreamPreloadFlightKey,
        val deferred: Deferred<String?>,
    ) {
        val owners = AtomicInteger()
        val pendingPlaybackOwners = AtomicInteger()
        val playbackConsumers = AtomicInteger()

        @Volatile
        var promotedForPlayback = false
    }
}
