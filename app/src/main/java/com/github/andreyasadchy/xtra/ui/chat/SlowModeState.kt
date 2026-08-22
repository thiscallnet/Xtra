package com.github.andreyasadchy.xtra.ui.chat

enum class SlowModeApplicability {
    APPLIES,
    EXEMPT,
    UNKNOWN,
}

data class SlowModeState(
    val intervalSeconds: Int? = null,
    val remainingSeconds: Int = 0,
    val applicability: SlowModeApplicability = SlowModeApplicability.UNKNOWN,
) {
    val enabled: Boolean
        get() = intervalSeconds != null

    val coolingDown: Boolean
        get() = remainingSeconds > 0

    val blocked: Boolean
        get() = applicability == SlowModeApplicability.APPLIES && coolingDown
}

internal fun slowModeRemainingSeconds(
    intervalSeconds: Int,
    lastMessageElapsedRealtime: Long?,
    nowElapsedRealtime: Long,
): Int {
    if (intervalSeconds <= 0 || lastMessageElapsedRealtime == null) return 0
    val remainingMillis = intervalSeconds * 1000L - (nowElapsedRealtime - lastMessageElapsedRealtime)
    return if (remainingMillis <= 0L) 0 else ((remainingMillis + 999L) / 1000L).toInt()
}

internal data class SlowModeMessageIdentity(
    val messageId: String? = null,
    val message: String? = null,
    val replyId: String? = null,
)

internal class SlowModeMessageDedupe(
    private val windowMillis: Long = 500L,
) {
    private companion object {
        const val MAX_ACCEPTED_MESSAGE_IDS = 64
    }

    private data class Fingerprint(
        val message: String,
        val replyId: String?,
    )

    private val acceptedMessageIds = linkedMapOf<String, Long>()
    private val pendingFingerprints = linkedMapOf<Fingerprint, Long>()

    fun accept(identity: SlowModeMessageIdentity, nowElapsedRealtime: Long): Boolean {
        pruneFingerprints(nowElapsedRealtime)

        val messageId = identity.messageId
        if (messageId != null && acceptedMessageIds.containsKey(messageId)) return false

        val fingerprint = identity.message?.let { Fingerprint(it, identity.replyId) }
        if (fingerprint != null) {
            val acceptedAt = pendingFingerprints.remove(fingerprint)
            if (acceptedAt != null && nowElapsedRealtime - acceptedAt <= windowMillis) {
                messageId?.let { rememberMessageId(it, nowElapsedRealtime) }
                return false
            }
        }

        messageId?.let { rememberMessageId(it, nowElapsedRealtime) }
        if (messageId == null && fingerprint != null) {
            pendingFingerprints[fingerprint] = nowElapsedRealtime
        }
        return true
    }

    fun clear() {
        acceptedMessageIds.clear()
        pendingFingerprints.clear()
    }

    private fun pruneFingerprints(nowElapsedRealtime: Long) {
        pendingFingerprints.entries.removeAll {
            nowElapsedRealtime - it.value > windowMillis
        }
    }

    private fun rememberMessageId(messageId: String, nowElapsedRealtime: Long) {
        acceptedMessageIds[messageId] = nowElapsedRealtime
        while (acceptedMessageIds.size > MAX_ACCEPTED_MESSAGE_IDS) {
            acceptedMessageIds.remove(acceptedMessageIds.keys.first())
        }
    }
}
