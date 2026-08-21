package com.github.andreyasadchy.xtra.ui.multiview

import android.content.Context
import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.HermesWebSocket
import com.github.andreyasadchy.xtra.util.chat.PubSubUtils
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.net.ssl.X509TrustManager

internal fun interface MultiviewRaidSubscription {
    fun close()
}

internal typealias MultiviewRaidSubscriptionFactory =
    (channelId: String, onRaid: suspend (Raid) -> Unit) -> MultiviewRaidSubscription

internal class MultiviewRaidMonitor(
    private val scope: CoroutineScope,
    private val subscribe: MultiviewRaidSubscriptionFactory,
    private val resolveChannelId: suspend (Stream) -> String?,
    private val onRaid: (identity: String, raid: Raid) -> Unit,
) {
    private data class ActiveSubscription(
        val channelId: String,
        val subscription: MultiviewRaidSubscription,
    )

    private val subscriptions = linkedMapOf<String, ActiveSubscription>()
    private val resolutionJobs = linkedMapOf<String, Job>()
    private var desiredStreams = emptyMap<String, Stream>()

    fun sync(streams: List<Stream>) {
        val desired = linkedMapOf<String, Stream>()
        streams.forEach { stream ->
            val identity = MultiviewSessionReducer.stableIdentity(stream)
            if (identity != null) desired[identity] = stream
        }
        desiredStreams = desired

        subscriptions.keys.toList()
            .filterNot(desired::containsKey)
            .forEach { identity ->
                subscriptions.remove(identity)?.subscription?.close()
            }
        resolutionJobs.keys.toList()
            .filterNot(desired::containsKey)
            .forEach { identity ->
                resolutionJobs.remove(identity)?.cancel()
            }

        desired.forEach { (identity, stream) ->
            val channelId = stream.channelId?.takeIf { it.isNotBlank() }
            if (channelId != null) {
                resolutionJobs.remove(identity)?.cancel()
                ensureSubscription(identity, channelId)
            } else if (identity !in subscriptions && identity !in resolutionJobs) {
                val job = scope.launch {
                    val resolvedChannelId = try {
                        resolveChannelId(stream)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val channelId = resolvedChannelId?.takeIf { it.isNotBlank() }
                    if (channelId != null && desiredStreams[identity] != null && identity !in subscriptions) {
                        ensureSubscription(identity, channelId)
                    }
                }
                resolutionJobs[identity] = job
                job.invokeOnCompletion {
                    if (resolutionJobs[identity] === job) resolutionJobs.remove(identity)
                }
            }
        }
    }

    fun close() {
        resolutionJobs.values.forEach(Job::cancel)
        resolutionJobs.clear()
        subscriptions.values.forEach { it.subscription.close() }
        subscriptions.clear()
        desiredStreams = emptyMap()
    }

    private fun ensureSubscription(identity: String, channelId: String) {
        val current = subscriptions[identity]
        if (current?.channelId == channelId) return
        current?.subscription?.close()
        subscriptions[identity] = ActiveSubscription(
            channelId = channelId,
            subscription = subscribe(channelId) { raid -> onRaid(identity, raid) },
        )
    }

    companion object {
        fun create(
            context: Context,
            trustManager: Lazy<X509TrustManager>,
            scope: CoroutineScope,
            resolveChannelId: suspend (Stream) -> String?,
            onRaid: (identity: String, raid: Raid) -> Unit,
        ): MultiviewRaidMonitor {
            val preferences = context.prefs()
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
            val enableIntegrity = preferences.getBoolean(C.ENABLE_INTEGRITY, false)
            val gqlClientId = preferences.getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
            val gqlToken = context.tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
            val clientId = if (enableIntegrity) gqlHeaders[C.HEADER_CLIENT_ID] else gqlClientId
            val token = if (enableIntegrity) {
                gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth ")
            } else {
                gqlToken
            }

            return MultiviewRaidMonitor(
                scope = scope,
                subscribe = { channelId, emitRaid ->
                    val socket = HermesWebSocket(
                        channelId = channelId,
                        userId = null,
                        gqlClientId = clientId,
                        gqlToken = token,
                        collectPoints = false,
                        listenForPoints = false,
                        showRaids = true,
                        showPolls = false,
                        showPredictions = false,
                        includeChannelTopics = false,
                        trustManager = trustManager,
                        listener = object : HermesWebSocket.Listener {
                            override suspend fun onRaidUpdate(message: JSONObject, openStream: Boolean) {
                                if (!openStream) return
                                PubSubUtils.onRaidUpdate(message, true)?.let { emitRaid(it) }
                            }
                        },
                    )
                    val job = socket.connect(scope)
                    MultiviewRaidSubscription {
                        job.cancel()
                        scope.launch(Dispatchers.IO) { socket.disconnect(job) }
                    }
                },
                resolveChannelId = resolveChannelId,
                onRaid = onRaid,
            )
        }
    }
}
