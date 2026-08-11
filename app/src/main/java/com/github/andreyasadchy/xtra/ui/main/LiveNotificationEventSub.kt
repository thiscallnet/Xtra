package com.github.andreyasadchy.xtra.ui.main

import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.repository.EventSubSubscriptionInfo
import com.github.andreyasadchy.xtra.repository.EventSubSubscriptionResult
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

data class LiveStreamOnlineEvent(
    val eventId: String,
    val broadcasterUserId: String,
    val broadcasterUserLogin: String?,
    val broadcasterUserName: String?,
    val startedAt: String,
)

data class LiveEventSubRevocation(
    val subscriptionId: String?,
    val subscriptionType: String?,
    val status: String?,
    val broadcasterUserId: String?,
)

internal enum class LiveEventSubSuspensionReason {
    AUTHENTICATION,
    RATE_LIMIT,
    VERSION_REMOVED,
    CAPACITY_REACHED,
    SESSION_INVALID,
    ALREADY_EXISTS,
    CHANNEL_REJECTED,
    DUPLICATE_CONDITION_LIMIT,
    CONFIGURATION_INVALID,
    TRANSIENT_SERVER_FAILURE,
    NO_ACTIVE_SUBSCRIPTIONS,
}

internal fun classifyEventSubFailure(statusCode: Int, errorMessage: String?): LiveEventSubSuspensionReason? {
    val error = errorMessage.orEmpty().lowercase()
    return when {
        statusCode == 401 || statusCode == 403 -> LiveEventSubSuspensionReason.AUTHENTICATION
        statusCode == 410 ||
            "subscription version removed" in error ||
            "version has been removed" in error -> LiveEventSubSuspensionReason.VERSION_REMOVED
        statusCode == 409 -> LiveEventSubSuspensionReason.ALREADY_EXISTS
        statusCode == 429 && (
                "same type and condition" in error ||
                "same event type and condition" in error ||
                "maximum number of subscriptions" in error &&
                    "same type" in error &&
                    "condition" in error ||
                "subscription limit" in error && "condition" in error ||
                "too many subscriptions" in error && "condition" in error
            ) -> LiveEventSubSuspensionReason.DUPLICATE_CONDITION_LIMIT
        (
            "maximum total cost" in error ||
                "max_total_cost" in error ||
                "subscription cost limit" in error ||
                "maximum number of subscriptions" in error
            ) -> LiveEventSubSuspensionReason.CAPACITY_REACHED
        statusCode == 429 -> LiveEventSubSuspensionReason.RATE_LIMIT
        "invalid session" in error ||
            "session is invalid" in error ||
            ("session" in error && (
                "invalid" in error ||
                    "expired" in error ||
                    "missing" in error
                )) ->
            LiveEventSubSuspensionReason.SESSION_INVALID
        ("condition" in error && "user" in error && (
            "not found" in error ||
                "does not exist" in error
            )) ||
            ("broadcaster" in error && (
                "not found" in error ||
                    "does not exist" in error
                )) -> LiveEventSubSuspensionReason.CHANNEL_REJECTED
        statusCode in 400..499 -> LiveEventSubSuspensionReason.CONFIGURATION_INVALID
        statusCode in 500..599 -> LiveEventSubSuspensionReason.TRANSIENT_SERVER_FAILURE
        else -> null
    }
}

internal enum class LiveEventSubFailureAction {
    SUSPEND,
    RETRY_REMAINING,
    DEFER_CHANNEL,
    RECONNECT,
    STOP_FILLING,
    REJECT_CHANNEL,
    CONTINUE,
}

internal fun eventSubFailureAction(
    reason: LiveEventSubSuspensionReason?,
    hasActiveSubscriptions: Boolean,
): LiveEventSubFailureAction = when (reason) {
    LiveEventSubSuspensionReason.AUTHENTICATION,
    LiveEventSubSuspensionReason.VERSION_REMOVED,
    LiveEventSubSuspensionReason.NO_ACTIVE_SUBSCRIPTIONS,
    -> LiveEventSubFailureAction.SUSPEND
    LiveEventSubSuspensionReason.RATE_LIMIT ->
        if (hasActiveSubscriptions) LiveEventSubFailureAction.RETRY_REMAINING else LiveEventSubFailureAction.SUSPEND
    LiveEventSubSuspensionReason.SESSION_INVALID -> LiveEventSubFailureAction.RECONNECT
    LiveEventSubSuspensionReason.ALREADY_EXISTS -> LiveEventSubFailureAction.DEFER_CHANNEL
    LiveEventSubSuspensionReason.CHANNEL_REJECTED -> LiveEventSubFailureAction.REJECT_CHANNEL
    LiveEventSubSuspensionReason.DUPLICATE_CONDITION_LIMIT -> LiveEventSubFailureAction.DEFER_CHANNEL
    LiveEventSubSuspensionReason.CAPACITY_REACHED ->
        if (hasActiveSubscriptions) LiveEventSubFailureAction.STOP_FILLING else LiveEventSubFailureAction.SUSPEND
    LiveEventSubSuspensionReason.CONFIGURATION_INVALID -> LiveEventSubFailureAction.SUSPEND
    LiveEventSubSuspensionReason.TRANSIENT_SERVER_FAILURE ->
        if (hasActiveSubscriptions) LiveEventSubFailureAction.DEFER_CHANNEL else LiveEventSubFailureAction.SUSPEND
    null -> LiveEventSubFailureAction.CONTINUE
}

internal fun isMatchingEventSubSubscription(
    subscription: EventSubSubscriptionInfo?,
    subscriptionId: String?,
    channelId: String,
    sessionId: String?,
): Boolean = subscription?.let {
    it.statusCode in 200..299 &&
        it.id == subscriptionId &&
        it.subscriptionType == "stream.online" &&
        it.subscriptionStatus == "enabled" &&
        it.broadcasterUserId == channelId &&
        it.transportMethod == "websocket" &&
        it.transportSessionId == sessionId
} == true

internal sealed interface ExistingEventSubSubscriptionResolution {
    data class CurrentSession(val subscription: EventSubSubscriptionInfo) : ExistingEventSubSubscriptionResolution
    object OtherSession : ExistingEventSubSubscriptionResolution
    object Retry : ExistingEventSubSubscriptionResolution
}

internal fun classifyExistingEventSubSubscription(
    subscription: EventSubSubscriptionInfo?,
    subscriptionId: String?,
    channelId: String,
    sessionId: String?,
): ExistingEventSubSubscriptionResolution {
    if (subscription == null || subscription.statusCode !in 200..299) {
        return ExistingEventSubSubscriptionResolution.Retry
    }
    if (isMatchingEventSubSubscription(subscription, subscriptionId, channelId, sessionId)) {
        return ExistingEventSubSubscriptionResolution.CurrentSession(subscription)
    }
    val belongsToAnotherSession =
        subscription.id == subscriptionId &&
            subscription.subscriptionType == "stream.online" &&
            subscription.subscriptionStatus == "enabled" &&
            subscription.broadcasterUserId == channelId &&
            subscription.transportMethod == "websocket" &&
            !subscription.transportSessionId.isNullOrBlank() &&
            !sessionId.isNullOrBlank() &&
            subscription.transportSessionId != sessionId
    return if (belongsToAnotherSession) {
        ExistingEventSubSubscriptionResolution.OtherSession
    } else {
        ExistingEventSubSubscriptionResolution.Retry
    }
}

internal fun isEventSubChannelRetryDue(nowElapsedMs: Long, retryAtElapsedMs: Long?): Boolean =
    retryAtElapsedMs == null || nowElapsedMs >= retryAtElapsedMs

private typealias SuspensionReason = LiveEventSubSuspensionReason

internal data class LiveEventSubMessage(
    val messageType: String,
    val messageId: String?,
    val sessionId: String? = null,
    val keepAliveTimeoutSeconds: Int? = null,
    val reconnectUrl: String? = null,
    val subscriptionType: String? = null,
    val subscriptionId: String? = null,
    val subscriptionStatus: String? = null,
    val subscriptionBroadcasterUserId: String? = null,
    val streamOnlineEvent: LiveStreamOnlineEvent? = null,
)

internal object LiveNotificationEventSubProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): LiveEventSubMessage? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val metadata = root["metadata"]?.jsonObject ?: return null
        val messageType = metadata.stringValue("message_type") ?: return null
        val messageId = metadata.stringValue("message_id")
        val payload = root["payload"]?.jsonObject
        val session = payload?.get("session")?.jsonObject
        val subscription = payload?.get("subscription")?.jsonObject
        val event = payload?.get("event")?.jsonObject
        val subscriptionType = metadata.stringValue("subscription_type")
            ?: subscription.stringValue("type")
        val subscriptionId = subscription.stringValue("id")
        val subscriptionStatus = subscription.stringValue("status")
        val subscriptionBroadcasterUserId = subscription?.get("condition")?.jsonObject.stringValue("broadcaster_user_id")
        val streamOnlineEvent = if (messageType == "notification" && subscriptionType == "stream.online") {
            event?.let {
                LiveStreamOnlineEvent(
                    eventId = it.stringValue("id") ?: return@let null,
                    broadcasterUserId = it.stringValue("broadcaster_user_id") ?: return@let null,
                    broadcasterUserLogin = it.stringValue("broadcaster_user_login"),
                    broadcasterUserName = it.stringValue("broadcaster_user_name"),
                    startedAt = it.stringValue("started_at") ?: return@let null,
                )
            }
        } else null
        return LiveEventSubMessage(
            messageType = messageType,
            messageId = messageId,
            sessionId = session.stringValue("id"),
            keepAliveTimeoutSeconds = session.intValue("keepalive_timeout_seconds"),
            reconnectUrl = session.stringValue("reconnect_url"),
            subscriptionType = subscriptionType,
            subscriptionId = subscriptionId,
            subscriptionStatus = subscriptionStatus,
            subscriptionBroadcasterUserId = subscriptionBroadcasterUserId,
            streamOnlineEvent = streamOnlineEvent,
        )
    }

    private fun JsonObject?.stringValue(key: String): String? =
        this?.get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * Opportunistic EventSub stream.online fast lane.
 *
 * Helix polling remains authoritative because EventSub does not replay events
 * lost during a disconnected period and because the subscription cost limit
 * is independent of the number of WebSocket connections.
 */
class LiveNotificationEventSub(
    private val okHttpClient: Lazy<OkHttpClient>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: () -> String?,
    private val helixHeaders: () -> Map<String, String>,
    private val channelIds: suspend () -> List<String>,
    private val scope: CoroutineScope,
    private val onStreamOnline: suspend (LiveStreamOnlineEvent) -> Unit,
    private val onRevocation: suspend (LiveEventSubRevocation) -> Unit = {},
) {

    private val stateMutex = Mutex()
    private val messageMutex = Mutex()
    private val handledMessageIds = LinkedHashSet<String>()
    private val socketEvents = Channel<SocketEvent>(Channel.UNLIMITED)
    private var processorJob: Job? = null
    private var activeConnection: Connection? = null
    private var pendingReconnect: Connection? = null
    private var reconnectJob: Job? = null
    private var handoffRetryJob: Job? = null
    private var desiredChannelIds = emptySet<String>()
    private val revokedChannelIds = mutableSetOf<String>()
    private var suspension: EventSubSuspension? = null
    private var started = false
    private var reconnectAttempt = 0

    suspend fun start() {
        ensureProcessor()
        val ids = eventSubChannelIds()
        stateMutex.withLock {
            started = true
            suspension = null
            desiredChannelIds = ids.toSet()
            if (activeConnection == null && pendingReconnect == null && ids.isNotEmpty()) {
                openNormalConnectionLocked()
            }
        }
    }

    suspend fun stop() {
        val sockets = stateMutex.withLock {
            started = false
            suspension = null
            revokedChannelIds.clear()
            reconnectJob?.cancel()
            handoffRetryJob?.cancel()
            reconnectJob = null
            handoffRetryJob = null
            val connections = listOfNotNull(activeConnection, pendingReconnect)
            connections.forEach(::cancelConnectionJobs)
            val sockets = connections.mapNotNull { it.socket }
            activeConnection = null
            pendingReconnect = null
            sockets
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "monitoring stopped") }
    }

    /** Synchronous best-effort close used when the process-scoped engine stops. */
    fun shutdown() {
        val sockets = runBlocking {
            stateMutex.withLock {
                started = false
                suspension = null
                revokedChannelIds.clear()
                reconnectJob?.cancel()
                handoffRetryJob?.cancel()
                reconnectJob = null
                handoffRetryJob = null
                val connections = listOfNotNull(activeConnection, pendingReconnect)
                connections.forEach(::cancelConnectionJobs)
                val sockets = connections.mapNotNull { it.socket }
                activeConnection = null
                pendingReconnect = null
                sockets
            }
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "process stopped") }
        processorJob?.cancel()
        socketEvents.close()
    }

    /** Rebuilds the socket only when the EventSub subset actually changed. */
    suspend fun refreshIfNeeded() {
        val ids = eventSubChannelIds()
        val nextIds = ids.toSet()
        val credentialFingerprint = credentialFingerprint()
        var socketsToClose = emptyList<WebSocket>()
        stateMutex.withLock {
            if (!started) {
                return@withLock
            }
            suspension?.let { currentSuspension ->
                if (!canResume(currentSuspension, credentialFingerprint)) {
                    return@withLock
                }
                suspension = null
            }
            val credentialsChanged = activeConnection?.credentialFingerprint != credentialFingerprint
            if (nextIds == desiredChannelIds && !credentialsChanged) {
                if (activeConnection == null && pendingReconnect == null && nextIds.isNotEmpty()) {
                    openNormalConnectionLocked()
                } else {
                    val connection = activeConnection
                    if (connection != null &&
                        connection.welcomed &&
                        connection.subscriptionJob?.isActive != true &&
                        connection.sessionId != null &&
                        hasPendingSubscriptionsLocked(connection)
                    ) {
                        connection.subscriptionJob = scope.launch { subscribe(connection) }
                    }
                }
                return@withLock
            }
            desiredChannelIds = nextIds
            revokedChannelIds.retainAll(nextIds)
            reconnectJob?.cancel()
            handoffRetryJob?.cancel()
            reconnectJob = null
            handoffRetryJob = null
            val connections = listOfNotNull(activeConnection, pendingReconnect)
            connections.forEach(::cancelConnectionJobs)
            socketsToClose = connections.mapNotNull { it.socket }
            activeConnection = null
            pendingReconnect = null
            if (nextIds.isNotEmpty()) {
                openNormalConnectionLocked()
            }
        }
        socketsToClose.forEach { it.close(NORMAL_CLOSE_CODE, "subscriptions changed") }
    }

    private suspend fun ensureProcessor() {
        stateMutex.withLock {
            if (processorJob?.isActive != true) {
                processorJob = scope.launch {
                    for (event in socketEvents) {
                        try {
                            when (event) {
                                is SocketEvent.Message -> handleMessage(event.connection, event.text)
                                is SocketEvent.Failure -> handleClosed(event.connection, event.reason)
                                is SocketEvent.Closed -> handleClosed(event.connection, event.reason)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to process EventSub socket event", e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun eventSubChannelIds(): List<String> {
        val headers = helixHeaders()
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) {
            return emptyList()
        }
        // Keep the complete deterministic list here. The subscription response
        // reports the server's actual max_total_cost, so subscribe() can stop
        // at the live capacity instead of baking that limit into selection.
        return channelIds().filter { it.isNotBlank() }.distinct()
    }

    private fun credentialFingerprint(): String? =
        helixHeaders()[C.HEADER_TOKEN]?.takeIf { it.isNotBlank() }

    private fun hasPendingSubscriptionsLocked(connection: Connection): Boolean {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val hasPendingChannels = desiredChannelIds.any {
            isPendingChannelLocked(connection, it, nowElapsedMs)
        }
        if (!hasPendingChannels || connection.subscribedChannelIds.size >= MAX_ENABLED_SUBSCRIPTIONS) {
            return false
        }
        val capacityRecheckDue = connection.capacityReached &&
            SystemClock.elapsedRealtime() >= (connection.capacityRetryAtElapsedMs ?: Long.MAX_VALUE)
        return capacityRecheckDue || (
            !connection.capacityReached &&
                connection.totalSubscriptionCost < connection.maxTotalSubscriptionCost
            )
    }

    private fun isPendingChannelLocked(
        connection: Connection,
        channelId: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean =
        channelId !in connection.subscribedChannelIds &&
            channelId !in revokedChannelIds &&
            channelId !in connection.rejectedChannelIds &&
            isEventSubChannelRetryDue(nowElapsedMs, connection.channelRetryAtElapsedMs[channelId])

    private fun canResume(
        currentSuspension: EventSubSuspension,
        nextCredentialFingerprint: String?,
    ): Boolean = when (currentSuspension.reason) {
        SuspensionReason.AUTHENTICATION ->
            nextCredentialFingerprint != null &&
                nextCredentialFingerprint != currentSuspension.credentialFingerprint
        SuspensionReason.RATE_LIMIT,
        SuspensionReason.TRANSIENT_SERVER_FAILURE,
        SuspensionReason.NO_ACTIVE_SUBSCRIPTIONS,
        -> SystemClock.elapsedRealtime() >= (currentSuspension.retryAtElapsedMs ?: Long.MAX_VALUE)
        SuspensionReason.VERSION_REMOVED,
        SuspensionReason.CAPACITY_REACHED,
        SuspensionReason.SESSION_INVALID,
        SuspensionReason.ALREADY_EXISTS,
        SuspensionReason.CHANNEL_REJECTED,
        SuspensionReason.DUPLICATE_CONDITION_LIMIT,
        SuspensionReason.CONFIGURATION_INVALID,
        -> false
    }

    private suspend fun suspendEventSub(
        connection: Connection,
        reason: SuspensionReason,
        retryAtElapsedMs: Long?,
    ) {
        val sockets = stateMutex.withLock {
            if (!started || (activeConnection !== connection && pendingReconnect !== connection)) {
                return@withLock emptyList()
            }
            suspension = EventSubSuspension(
                reason = reason,
                retryAtElapsedMs = retryAtElapsedMs,
                credentialFingerprint = credentialFingerprint(),
            )
            reconnectJob?.cancel()
            handoffRetryJob?.cancel()
            reconnectJob = null
            handoffRetryJob = null
            val connections = listOfNotNull(activeConnection, pendingReconnect)
            connections.forEach(::cancelConnectionJobs)
            activeConnection = null
            pendingReconnect = null
            connections.mapNotNull { it.socket }
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "EventSub suspended: ${reason.name}") }
    }

    private fun openNormalConnectionLocked() {
        if (!started || desiredChannelIds.isEmpty() || suspension != null) {
            return
        }
        val connection = Connection(
            url = EVENTSUB_URL,
            transferredSubscriptions = false,
            credentialFingerprint = credentialFingerprint(),
        )
        activeConnection = connection
        connection.socket = openSocket(connection)
        armWelcomeTimeout(connection)
    }

    private fun openSocket(connection: Connection): WebSocket = okHttpClient.value.newWebSocket(
        Request.Builder().url(connection.url).build(),
        connection,
    )

    private suspend fun handleMessage(connection: Connection, text: String) {
        val isCurrent = stateMutex.withLock {
            started && (activeConnection === connection || pendingReconnect === connection)
        }
        if (!isCurrent) {
            return
        }
        val message = LiveNotificationEventSubProtocol.parse(text) ?: return
        val messageId = message.messageId
        if (!messageId.isNullOrBlank() && isHandledMessage(messageId)) {
            return
        }
        connection.lastMessageAt = SystemClock.elapsedRealtime()
        if (connection.welcomed) {
            armKeepAliveWatchdog(connection)
        }
        var processed = false
        when (message.messageType) {
            "session_welcome" -> {
                handleWelcome(connection, message)
                processed = message.sessionId != null
            }
            "session_keepalive" -> processed = true
            "session_reconnect" -> message.reconnectUrl?.let {
                startReconnectHandoff(connection, it)
                processed = true
            }
            "notification" -> if (message.subscriptionType == "stream.online") {
                message.streamOnlineEvent?.let {
                    onStreamOnline(it)
                    processed = true
                }
            }
            "revocation" -> {
                val revocation = LiveEventSubRevocation(
                    subscriptionId = message.subscriptionId,
                    subscriptionType = message.subscriptionType,
                    status = message.subscriptionStatus,
                    broadcasterUserId = message.subscriptionBroadcasterUserId,
                )
                if (revocation.status == "authorization_revoked" || revocation.status == "user_removed") {
                    stateMutex.withLock {
                        revocation.broadcasterUserId?.let { revokedChannelIds.add(it) }
                    }
                }
                val socketToClose = if (revocation.status == "user_removed") {
                    restartAfterRevocation(connection)
                } else {
                    suspendEventSub(
                        connection = connection,
                        reason = when (revocation.status) {
                            "version_removed" -> SuspensionReason.VERSION_REMOVED
                            else -> SuspensionReason.AUTHENTICATION
                        },
                        retryAtElapsedMs = null,
                    )
                    null
                }
                onRevocation(revocation)
                socketToClose?.close(NORMAL_CLOSE_CODE, "subscription revoked")
                processed = true
            }
        }
        if (processed && !messageId.isNullOrBlank()) {
            markMessageHandled(messageId)
        }
    }

    /** Recreate the connection so a revoked subscription cannot silently remove a fast lane. */
    private suspend fun restartAfterRevocation(connection: Connection): WebSocket? = stateMutex.withLock {
        if (!started || (activeConnection !== connection && pendingReconnect !== connection)) {
            return@withLock null
        }
        val socket = connection.socket
        cancelConnectionJobs(connection)
        if (activeConnection === connection) {
            activeConnection = null
            if (desiredChannelIds.isNotEmpty()) {
                openNormalConnectionLocked()
            }
        } else {
            pendingReconnect = null
            if (activeConnection == null && desiredChannelIds.isNotEmpty()) {
                openNormalConnectionLocked()
            }
        }
        socket
    }

    /** Rebuild the socket when the server and local subscription state diverge. */
    private suspend fun restartAfterConnectionFailure(
        connection: Connection,
        reason: String,
    ): WebSocket? = stateMutex.withLock {
        if (!started || (activeConnection !== connection && pendingReconnect !== connection)) {
            return@withLock null
        }
        val socket = connection.socket
        cancelConnectionJobs(connection)
        if (activeConnection === connection) {
            activeConnection = null
        } else {
            pendingReconnect = null
        }
        if (activeConnection == null && desiredChannelIds.isNotEmpty()) {
            scheduleNormalReconnectLocked(reason)
        }
        socket
    }

    private suspend fun isHandledMessage(messageId: String): Boolean = messageMutex.withLock {
        messageId in handledMessageIds
    }

    private suspend fun markMessageHandled(messageId: String) = messageMutex.withLock {
        handledMessageIds.add(messageId)
        if (handledMessageIds.size > MAX_HANDLED_MESSAGE_IDS) {
            handledMessageIds.remove(handledMessageIds.first())
        }
    }

    private suspend fun handleWelcome(connection: Connection, message: LiveEventSubMessage) {
        val sessionId = message.sessionId ?: return
        val keepAliveTimeoutSeconds = (message.keepAliveTimeoutSeconds ?: DEFAULT_KEEPALIVE_TIMEOUT_SECONDS)
            .coerceIn(MIN_KEEPALIVE_TIMEOUT_SECONDS, MAX_KEEPALIVE_TIMEOUT_SECONDS)
        connection.welcomeJob?.cancel()
        connection.welcomed = true
        connection.sessionId = sessionId
        connection.keepAliveTimeoutMs = keepAliveTimeoutSeconds * 1000L
        armKeepAliveWatchdog(connection)

        if (connection.transferredSubscriptions) {
            val oldConnection = stateMutex.withLock {
                if (pendingReconnect !== connection || !started) {
                    return@withLock null
                }
                pendingReconnect = null
                handoffRetryJob?.cancel()
                handoffRetryJob = null
                val old = activeConnection
                old?.let {
                    connection.subscribedChannelIds += it.subscribedChannelIds
                    connection.rejectedChannelIds += it.rejectedChannelIds
                    connection.channelRetryAtElapsedMs.putAll(it.channelRetryAtElapsedMs)
                    connection.totalSubscriptionCost = it.totalSubscriptionCost
                    connection.maxTotalSubscriptionCost = it.maxTotalSubscriptionCost
                    connection.capacityReached = it.capacityReached
                    connection.capacityRetryAtElapsedMs = it.capacityRetryAtElapsedMs
                    connection.credentialFingerprint = it.credentialFingerprint
                }
                activeConnection = connection
                old
            }
            oldConnection?.let {
                cancelConnectionJobs(it)
                it.socket?.close(NORMAL_CLOSE_CODE, "reconnected")
            }
            reconnectAttempt = 0
            val shouldFill = stateMutex.withLock {
                started &&
                    activeConnection === connection &&
                    hasPendingSubscriptionsLocked(connection)
            }
            if (shouldFill) {
                connection.subscriptionJob = scope.launch { subscribe(connection) }
            }
            return
        }

        val shouldSubscribe = stateMutex.withLock {
            started && activeConnection === connection
        }
        if (shouldSubscribe) {
            connection.subscriptionJob?.cancel()
            connection.subscriptionJob = scope.launch { subscribe(connection) }
        }
    }

    private fun markSubscriptionLocked(
        connection: Connection,
        channelId: String,
        cost: Int?,
        totalCost: Int?,
        maxTotalCost: Int?,
    ): Boolean {
        if (!started || activeConnection !== connection) {
            return false
        }
        connection.subscribedChannelIds += channelId
        connection.rejectedChannelIds.remove(channelId)
        connection.channelRetryAtElapsedMs.remove(channelId)
        connection.totalSubscriptionCost = totalCost
            ?: connection.totalSubscriptionCost + (cost ?: 1)
        connection.maxTotalSubscriptionCost = maxTotalCost ?: connection.maxTotalSubscriptionCost
        connection.capacityReached = connection.subscribedChannelIds.size >= MAX_ENABLED_SUBSCRIPTIONS ||
            connection.totalSubscriptionCost >= connection.maxTotalSubscriptionCost
        connection.capacityRetryAtElapsedMs = if (connection.capacityReached) {
            SystemClock.elapsedRealtime() + CAPACITY_RECHECK_INTERVAL_MS
        } else {
            null
        }
        if (connection.capacityReached) {
            Log.d(TAG, "EventSub capacity reached at ${connection.subscribedChannelIds.size} subscriptions")
        }
        reconnectAttempt = 0
        return connection.capacityReached
    }

    private suspend fun resolveExistingSubscription(
        connection: Connection,
        channelId: String,
        result: EventSubSubscriptionResult,
    ): ExistingEventSubSubscriptionResolution {
        val existingId = result.subscriptionId
        if (existingId.isNullOrBlank()) {
            return ExistingEventSubSubscriptionResolution.Retry
        }
        val existing = try {
            helixRepository.getEventSubSubscription(
                headers = helixHeaders(),
                subscriptionId = existingId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve existing EventSub subscription $existingId", e)
            return ExistingEventSubSubscriptionResolution.Retry
        }
        val currentSessionId = stateMutex.withLock { connection.sessionId }
        return classifyExistingEventSubSubscription(
            subscription = existing,
            subscriptionId = existingId,
            channelId = channelId,
            sessionId = currentSessionId,
        )
    }

    private suspend fun markChannelRetry(
        connection: Connection,
        channelId: String,
        retryDelayMs: Long,
    ) {
        stateMutex.withLock {
            if (activeConnection === connection) {
                connection.channelRetryAtElapsedMs[channelId] =
                    SystemClock.elapsedRealtime() + retryDelayMs
            }
        }
    }

    private suspend fun markChannelRejected(connection: Connection, channelId: String) {
        stateMutex.withLock {
            if (activeConnection === connection) {
                connection.channelRetryAtElapsedMs.remove(channelId)
                connection.rejectedChannelIds += channelId
            }
        }
    }

    private suspend fun adoptExistingSubscription(
        connection: Connection,
        channelId: String,
        subscription: EventSubSubscriptionInfo,
    ) {
        stateMutex.withLock {
            if (activeConnection === connection) {
                markSubscriptionLocked(
                    connection = connection,
                    channelId = channelId,
                    cost = subscription.cost,
                    totalCost = subscription.totalCost,
                    maxTotalCost = subscription.maxTotalCost,
                )
                Log.d(TAG, "Adopted existing EventSub subscription for $channelId")
            }
        }
    }

    private suspend fun handleExistingSubscriptionConflict(
        connection: Connection,
        channelId: String,
        result: EventSubSubscriptionResult,
    ) {
        when (val resolution = resolveExistingSubscription(connection, channelId, result)) {
            is ExistingEventSubSubscriptionResolution.CurrentSession ->
                adoptExistingSubscription(connection, channelId, resolution.subscription)
            ExistingEventSubSubscriptionResolution.OtherSession -> {
                markChannelRetry(connection, channelId, EXTERNAL_SUBSCRIPTION_RETRY_INTERVAL_MS)
                Log.w(TAG, "EventSub subscription for $channelId is owned by another session")
            }
            ExistingEventSubSubscriptionResolution.Retry -> {
                markChannelRetry(connection, channelId, CONFLICT_RETRY_INTERVAL_MS)
                Log.w(TAG, "EventSub subscription conflict for $channelId will be retried")
            }
        }
    }

    private suspend fun subscribe(connection: Connection) {
        while (isCurrent(connection) && scope.isActive) {
            val pendingChannelIds = stateMutex.withLock {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                desiredChannelIds
                    .filter { isPendingChannelLocked(connection, it, nowElapsedMs) }
            }
            if (pendingChannelIds.isEmpty()) {
                val shouldSuspend = stateMutex.withLock {
                    started &&
                        activeConnection === connection &&
                        desiredChannelIds.isNotEmpty() &&
                        connection.subscribedChannelIds.isEmpty()
                }
                if (shouldSuspend) {
                    suspendEventSub(
                        connection = connection,
                        reason = SuspensionReason.NO_ACTIVE_SUBSCRIPTIONS,
                        retryAtElapsedMs = SystemClock.elapsedRealtime() + EMPTY_CONNECTION_RETRY_MS,
                    )
                }
                return
            }

            var retryRemaining = false
            for (channelId in pendingChannelIds) {
                if (!isCurrent(connection, channelId) || !scope.isActive) {
                    return
                }
                val sessionId = stateMutex.withLock { connection.sessionId } ?: return
                try {
                    val result = helixRepository.createEventSubSubscriptionResult(
                        networkLibrary = networkLibrary(),
                        headers = helixHeaders(),
                        userId = null,
                        channelId = channelId,
                        type = "stream.online",
                        sessionId = sessionId,
                    )
                    if (!isCurrent(connection, channelId)) {
                        return
                    }
                    if (result.success) {
                        val reachedCapacity = stateMutex.withLock {
                            markSubscriptionLocked(
                                connection = connection,
                                channelId = channelId,
                                cost = result.cost,
                                totalCost = result.totalCost,
                                maxTotalCost = result.maxTotalCost,
                            )
                        }
                        if (reachedCapacity) {
                            return
                        }
                    } else {
                        Log.w(TAG, "EventSub subscription rejected for $channelId: ${result.errorMessage.orEmpty().take(240)}")
                        val reason = result.suspensionReason()
                        if (reason == SuspensionReason.ALREADY_EXISTS) {
                            handleExistingSubscriptionConflict(connection, channelId, result)
                            continue
                        }
                        val hasActiveSubscriptions = stateMutex.withLock {
                            connection.subscribedChannelIds.isNotEmpty()
                        }
                        when (
                            eventSubFailureAction(
                                reason = reason,
                                hasActiveSubscriptions = hasActiveSubscriptions,
                            )
                        ) {
                            LiveEventSubFailureAction.SUSPEND -> {
                                val suspensionReason = when (reason) {
                                    SuspensionReason.CAPACITY_REACHED,
                                    null,
                                    -> SuspensionReason.NO_ACTIVE_SUBSCRIPTIONS
                                    else -> reason
                                }
                                suspendEventSub(
                                    connection = connection,
                                    reason = suspensionReason,
                                    retryAtElapsedMs = suspensionReason.retryAtElapsedMs(result.retryDelayMs()),
                                )
                                return
                            }
                            LiveEventSubFailureAction.RETRY_REMAINING -> {
                                val retryDelayMs = result.retryDelayMs()
                                val retryAt = SystemClock.elapsedRealtime() + retryDelayMs
                                stateMutex.withLock {
                                    if (activeConnection === connection) {
                                        connection.subscriptionRetryAtElapsedMs = retryAt
                                    }
                                }
                                delay(retryDelayMs)
                                stateMutex.withLock {
                                    if (activeConnection === connection) {
                                        connection.subscriptionRetryAtElapsedMs = null
                                    }
                                }
                                retryRemaining = true
                                break
                            }
                            LiveEventSubFailureAction.DEFER_CHANNEL -> {
                                markChannelRetry(
                                    connection = connection,
                                    channelId = channelId,
                                    retryDelayMs = if (reason == SuspensionReason.DUPLICATE_CONDITION_LIMIT) {
                                        EXTERNAL_SUBSCRIPTION_RETRY_INTERVAL_MS
                                    } else {
                                        TRANSIENT_SUBSCRIPTION_BACKOFF_MS
                                    },
                                )
                            }
                            LiveEventSubFailureAction.RECONNECT -> {
                                val socket = restartAfterConnectionFailure(
                                    connection = connection,
                                    reason = if (reason == SuspensionReason.ALREADY_EXISTS) {
                                        "duplicate EventSub subscription"
                                    } else {
                                        "invalid EventSub session"
                                    },
                                )
                                socket?.close(NORMAL_CLOSE_CODE, "EventSub state divergence")
                                return
                            }
                            LiveEventSubFailureAction.STOP_FILLING -> {
                                stateMutex.withLock {
                                    if (activeConnection === connection) {
                                        connection.capacityReached = true
                                        connection.capacityRetryAtElapsedMs =
                                            SystemClock.elapsedRealtime() + CAPACITY_RECHECK_INTERVAL_MS
                                    }
                                }
                                return
                            }
                            LiveEventSubFailureAction.REJECT_CHANNEL -> {
                                markChannelRejected(connection, channelId)
                            }
                            LiveEventSubFailureAction.CONTINUE -> Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "EventSub subscription failed for $channelId", e)
                    val hasActiveSubscriptions = stateMutex.withLock {
                        connection.subscribedChannelIds.isNotEmpty()
                    }
                    if (hasActiveSubscriptions) {
                        markChannelRetry(
                            connection = connection,
                            channelId = channelId,
                            retryDelayMs = TRANSIENT_SUBSCRIPTION_BACKOFF_MS,
                        )
                    }
                }
            }
            if (!retryRemaining) {
                val shouldSuspend = stateMutex.withLock {
                    started &&
                        activeConnection === connection &&
                        desiredChannelIds.isNotEmpty() &&
                        connection.subscribedChannelIds.isEmpty()
                }
                if (shouldSuspend) {
                    suspendEventSub(
                        connection = connection,
                        reason = SuspensionReason.NO_ACTIVE_SUBSCRIPTIONS,
                        retryAtElapsedMs = SystemClock.elapsedRealtime() + EMPTY_CONNECTION_RETRY_MS,
                    )
                }
                return
            }
        }
    }

    private fun EventSubSubscriptionResult.suspensionReason(): SuspensionReason? =
        classifyEventSubFailure(statusCode, errorMessage)

    private fun EventSubSubscriptionResult.retryDelayMs(): Long = when {
        statusCode == 429 -> rateLimitResetEpochSeconds?.let { resetEpochSeconds ->
            (resetEpochSeconds * 1_000L - System.currentTimeMillis() + RATE_LIMIT_SAFETY_MARGIN_MS)
                .coerceAtLeast(MIN_RATE_LIMIT_RETRY_DELAY_MS)
        } ?: EVENTSUB_RATE_LIMIT_BACKOFF_MS
        statusCode in 500..599 -> TRANSIENT_SUBSCRIPTION_BACKOFF_MS
        else -> EVENTSUB_RATE_LIMIT_BACKOFF_MS
    }

    private fun SuspensionReason.retryAtElapsedMs(retryDelayMs: Long? = null): Long? = when (this) {
        SuspensionReason.RATE_LIMIT,
        SuspensionReason.TRANSIENT_SERVER_FAILURE,
        -> SystemClock.elapsedRealtime() + (retryDelayMs ?: EVENTSUB_RATE_LIMIT_BACKOFF_MS)
        SuspensionReason.NO_ACTIVE_SUBSCRIPTIONS -> SystemClock.elapsedRealtime() + EMPTY_CONNECTION_RETRY_MS
        SuspensionReason.AUTHENTICATION,
        SuspensionReason.VERSION_REMOVED,
        SuspensionReason.CAPACITY_REACHED,
        SuspensionReason.SESSION_INVALID,
        SuspensionReason.ALREADY_EXISTS,
        SuspensionReason.CHANNEL_REJECTED,
        SuspensionReason.DUPLICATE_CONDITION_LIMIT,
        SuspensionReason.CONFIGURATION_INVALID,
        -> null
    }

    private suspend fun isCurrent(connection: Connection, channelId: String? = null): Boolean = stateMutex.withLock {
        started &&
            (activeConnection === connection || pendingReconnect === connection) &&
            (channelId == null || channelId in desiredChannelIds)
    }

    private suspend fun startReconnectHandoff(connection: Connection, reconnectUrl: String) {
        stateMutex.withLock {
            if (!started || activeConnection !== connection || pendingReconnect != null) {
                return@withLock
            }
            val replacement = Connection(
                url = reconnectUrl,
                transferredSubscriptions = true,
                credentialFingerprint = connection.credentialFingerprint,
                handoffDeadlineElapsedMs = SystemClock.elapsedRealtime() + HANDOFF_BUDGET_MS,
            )
            pendingReconnect = replacement
            replacement.socket = openSocket(replacement)
            armWelcomeTimeout(replacement)
        }
    }

    private fun armWelcomeTimeout(connection: Connection) {
        connection.welcomeJob?.cancel()
        connection.welcomeJob = scope.launch {
            delay(WELCOME_TIMEOUT_MS)
            val timedOut = stateMutex.withLock {
                started && !connection.welcomed &&
                    (activeConnection === connection || pendingReconnect === connection)
            }
            if (timedOut) {
                connection.socket?.cancel()
            }
        }
    }

    private fun armKeepAliveWatchdog(connection: Connection) {
        connection.keepAliveJob?.cancel()
        connection.keepAliveJob = scope.launch {
            while (isActive && connection.welcomed) {
                delay(connection.keepAliveTimeoutMs + KEEPALIVE_SAFETY_MARGIN_MS)
                if (SystemClock.elapsedRealtime() - connection.lastMessageAt >= connection.keepAliveTimeoutMs) {
                    connection.socket?.cancel()
                    break
                }
            }
        }
    }

    private suspend fun handleClosed(connection: Connection, reason: String) {
        cancelConnectionJobs(connection)
        var normalReconnect = false
        var handoffUrl: String? = null
        var handoffDeadlineElapsedMs: Long? = null
        stateMutex.withLock {
            when {
                activeConnection === connection -> {
                    activeConnection = null
                    normalReconnect = started
                }
                pendingReconnect === connection -> {
                    pendingReconnect = null
                    if (started) {
                        handoffUrl = connection.url
                        handoffDeadlineElapsedMs = connection.handoffDeadlineElapsedMs
                        normalReconnect = activeConnection == null
                    }
                }
            }
            val active = activeConnection
            val reconnectUrl = handoffUrl
            if (reconnectUrl != null && active != null) {
                scheduleHandoffRetryLocked(active, reconnectUrl, handoffDeadlineElapsedMs, reason)
            } else if (normalReconnect) {
                scheduleNormalReconnectLocked(reason)
            }
        }
    }

    private fun scheduleHandoffRetryLocked(
        active: Connection,
        reconnectUrl: String,
        deadlineElapsedMs: Long?,
        reason: String,
    ) {
        if (handoffRetryJob?.isActive == true || !started || desiredChannelIds.isEmpty()) {
            return
        }
        Log.w(TAG, "EventSub reconnect handoff failed ($reason); retrying the supplied URL")
        handoffRetryJob = scope.launch {
            val retryDelayMs = nextReconnectDelay()
            delay(retryDelayMs)
            stateMutex.withLock {
                handoffRetryJob = null
                if (started && activeConnection === active && pendingReconnect == null) {
                    val deadline = deadlineElapsedMs ?: 0L
                    if (deadline == 0L ||
                        SystemClock.elapsedRealtime() + WELCOME_TIMEOUT_MS + HANDOFF_RETRY_MARGIN_MS >= deadline
                    ) {
                        activeConnection = null
                        cancelConnectionJobs(active)
                        active.socket?.close(NORMAL_CLOSE_CODE, "reconnect handoff expired")
                        scheduleNormalReconnectLocked("reconnect handoff window expired")
                        return@withLock
                    }
                    val replacement = Connection(
                        reconnectUrl,
                        transferredSubscriptions = true,
                        credentialFingerprint = active.credentialFingerprint,
                        handoffDeadlineElapsedMs = deadline,
                    )
                    pendingReconnect = replacement
                    replacement.socket = openSocket(replacement)
                    armWelcomeTimeout(replacement)
                }
            }
        }
    }

    private fun scheduleNormalReconnectLocked(reason: String) {
        if (reconnectJob?.isActive == true || !started || desiredChannelIds.isEmpty()) {
            return
        }
        Log.w(TAG, "EventSub disconnected ($reason); reconnecting with backoff")
        reconnectJob = scope.launch {
            delay(nextReconnectDelay())
            stateMutex.withLock {
                reconnectJob = null
                if (started && activeConnection == null && pendingReconnect == null && desiredChannelIds.isNotEmpty()) {
                    openNormalConnectionLocked()
                }
            }
        }
    }

    private fun nextReconnectDelay(): Long {
        val exponential = 1_000L * (1L shl min(reconnectAttempt, 6))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        return exponential.coerceAtMost(MAX_RECONNECT_DELAY_MS) + Random.nextLong(0L, 1_001L)
    }

    private fun cancelConnectionJobs(connection: Connection) {
        connection.welcomeJob?.cancel()
        connection.keepAliveJob?.cancel()
        connection.subscriptionJob?.cancel()
        connection.welcomeJob = null
        connection.keepAliveJob = null
        connection.subscriptionJob = null
    }

    private data class EventSubSuspension(
        val reason: SuspensionReason,
        val retryAtElapsedMs: Long?,
        val credentialFingerprint: String?,
    )

    private sealed interface SocketEvent {
        data class Message(val connection: Connection, val text: String) : SocketEvent
        data class Failure(val connection: Connection, val reason: String) : SocketEvent
        data class Closed(val connection: Connection, val reason: String) : SocketEvent
    }

    private inner class Connection(
        val url: String,
        val transferredSubscriptions: Boolean,
        val handoffDeadlineElapsedMs: Long? = null,
        var credentialFingerprint: String? = null,
    ) : WebSocketListener() {
        var socket: WebSocket? = null
        var welcomed = false
        var sessionId: String? = null
        val subscribedChannelIds = mutableSetOf<String>()
        val rejectedChannelIds = mutableSetOf<String>()
        val channelRetryAtElapsedMs = mutableMapOf<String, Long>()
        var totalSubscriptionCost = 0
        var maxTotalSubscriptionCost = DEFAULT_MAX_TOTAL_COST
        var capacityReached = false
        var capacityRetryAtElapsedMs: Long? = null
        var subscriptionRetryAtElapsedMs: Long? = null
        var lastMessageAt = SystemClock.elapsedRealtime()
        var keepAliveTimeoutMs = DEFAULT_KEEPALIVE_TIMEOUT_SECONDS * 1_000L
        var welcomeJob: Job? = null
        var keepAliveJob: Job? = null
        var subscriptionJob: Job? = null

        override fun onMessage(webSocket: WebSocket, text: String) {
            socketEvents.trySend(SocketEvent.Message(this, text))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socketEvents.trySend(SocketEvent.Failure(this, t.message ?: "failure"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socketEvents.trySend(SocketEvent.Closed(this, "$code $reason"))
        }
    }

    companion object {
        private const val TAG = "LiveNotificationEventSub"
        private const val EVENTSUB_URL = "wss://eventsub.wss.twitch.tv/ws"
        private const val DEFAULT_MAX_TOTAL_COST = 10
        private const val MAX_ENABLED_SUBSCRIPTIONS = 300
        private const val MAX_HANDLED_MESSAGE_IDS = 200
        private const val DEFAULT_KEEPALIVE_TIMEOUT_SECONDS = 10
        private const val MIN_KEEPALIVE_TIMEOUT_SECONDS = 10
        private const val MAX_KEEPALIVE_TIMEOUT_SECONDS = 600
        private const val KEEPALIVE_SAFETY_MARGIN_MS = 5_000L
        private const val WELCOME_TIMEOUT_MS = 15_000L
        private const val HANDOFF_BUDGET_MS = 27_000L
        private const val HANDOFF_RETRY_MARGIN_MS = 500L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val EVENTSUB_RATE_LIMIT_BACKOFF_MS = 60_000L
        private const val MIN_RATE_LIMIT_RETRY_DELAY_MS = 1_000L
        private const val RATE_LIMIT_SAFETY_MARGIN_MS = 1_000L
        private const val TRANSIENT_SUBSCRIPTION_BACKOFF_MS = 30_000L
        private const val CONFLICT_RETRY_INTERVAL_MS = 15_000L
        private const val EXTERNAL_SUBSCRIPTION_RETRY_INTERVAL_MS = 2 * 60_000L
        private const val CAPACITY_RECHECK_INTERVAL_MS = 60_000L
        private const val EMPTY_CONNECTION_RETRY_MS = 60_000L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
