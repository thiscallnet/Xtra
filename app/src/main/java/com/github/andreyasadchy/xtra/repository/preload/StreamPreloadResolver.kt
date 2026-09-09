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
    private val isPreviewEligible: (String) -> Boolean = isEligible,
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
        forPreview: Boolean = false,
        resolve: suspend () -> String?,
    ): String? {
        val key = key(channelLogin, configurationFingerprint)
        val eligible = if (forPreview) isPreviewEligible(streamKey) else isEligible(streamKey)
        if (!canStart() || !eligible || isCoolingDown(key)) return null
        val flight = acquireOrCreateFlight(key, forPreview, resolve)
        flight.deferred.start()
        return awaitOwned(flight, forPreview)
    }

    suspend fun join(
        channelLogin: String,
        configurationFingerprint: String,
        forPreview: Boolean = false,
    ): String? {
        val flight = acquireExistingFlight(key(channelLogin, configurationFingerprint), forPreview) ?: return null
        flight.deferred.start()
        return awaitOwned(flight, forPreview)
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
            releaseOwner(flight, previewOwner = false)
        }
    }

    fun cancelObsolete(
        configurationFingerprint: String,
        activeLogins: Set<String>,
        keepLogins: Set<String> = emptySet(),
    ) {
        flights.entries.forEach { (key, flight) ->
            val configurationObsolete = key.configurationFingerprint != configurationFingerprint
            val loginObsolete = !flight.promotedForPlayback &&
                key.channelLogin !in activeLogins &&
                key.channelLogin !in keepLogins
            if (configurationObsolete || loginObsolete) {
                cancel(
                    key = key,
                    flight = flight,
                    preservePreviewOwners = !configurationObsolete,
                )
            }
        }
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
        resolve: suspend () -> String?,
    ): Flight {
        lateinit var flight: Flight
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            semaphore.withPermit {
                // Eligibility is checked before a flight is created. Once another owner joins
                // the same flight, its ownership must not inherit the creator's preload-only
                // top-N rule. Obsolete speculative flights are cancelled by reconciliation;
                // preview-owned flights are retained there.
                if (!flight.promotedForPlayback && !canStart()) return@withPermit null
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

    private fun acquireOrCreateFlight(
        key: StreamPreloadFlightKey,
        previewOwner: Boolean,
        resolve: suspend () -> String?,
    ): Flight {
        while (true) {
            val flight = synchronized(flights) {
                flights[key] ?: createFlight(key, resolve).also { flights[key] = it }
            }
            var acquired = false
            synchronized(flight.ownershipLock) {
                if (flights[key] === flight) {
                    flight.owners.incrementAndGet()
                    if (previewOwner) flight.previewOwners.incrementAndGet()
                    acquired = true
                }
            }
            if (acquired) return flight
        }
    }

    private fun acquireExistingFlight(
        key: StreamPreloadFlightKey,
        previewOwner: Boolean,
    ): Flight? {
        val flight = flights[key] ?: return null
        synchronized(flight.ownershipLock) {
            if (flights[key] !== flight) return null
            flight.owners.incrementAndGet()
            if (previewOwner) flight.previewOwners.incrementAndGet()
            return flight
        }
    }

    private suspend fun awaitOwned(flight: Flight, previewOwner: Boolean): String? {
        try {
            return flight.deferred.await()
        } finally {
            releaseOwner(flight, previewOwner)
        }
    }

    private fun releaseOwner(flight: Flight, previewOwner: Boolean) {
        synchronized(flight.ownershipLock) {
            if (previewOwner) flight.previewOwners.decrementAndGet()
            if (flight.owners.decrementAndGet() == 0 &&
                !flight.promotedForPlayback &&
                !flight.deferred.isCompleted
            ) {
                // Keep removal under the same lock as the last decrement. A preview cannot
                // acquire this flight between the ownership decision and cancellation.
                if (flights.remove(flight.key, flight)) flight.deferred.cancel()
            }
        }
    }

    private fun consumePendingPlaybackOwner(flight: Flight): Boolean {
        while (true) {
            val pending = flight.pendingPlaybackOwners.get()
            if (pending == 0) return false
            if (flight.pendingPlaybackOwners.compareAndSet(pending, pending - 1)) return true
        }
    }

    private fun cancel(
        key: StreamPreloadFlightKey,
        flight: Flight,
        preservePreviewOwners: Boolean = false,
    ) {
        synchronized(flight.ownershipLock) {
            if (preservePreviewOwners && flight.previewOwners.get() > 0) return
            if (flights.remove(key, flight)) flight.deferred.cancel()
        }
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
        val ownershipLock = Any()
        val owners = AtomicInteger()
        val previewOwners = AtomicInteger()
        val pendingPlaybackOwners = AtomicInteger()
        val playbackConsumers = AtomicInteger()

        @Volatile
        var promotedForPlayback = false
    }
}
