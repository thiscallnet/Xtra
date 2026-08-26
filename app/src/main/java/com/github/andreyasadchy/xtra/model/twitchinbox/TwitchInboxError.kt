package com.github.andreyasadchy.xtra.model.twitchinbox

sealed interface TwitchInboxError {
    data object SignedOut : TwitchInboxError
    data object RequiresReauth : TwitchInboxError
    data object Network : TwitchInboxError
    data class RateLimited(val retryAfterMillis: Long? = null) : TwitchInboxError
    data object TwitchServerError : TwitchInboxError
    data class GraphQl(val operation: String, val safeMessage: String? = null) : TwitchInboxError
    data class PrivateApiChanged(val operation: String) : TwitchInboxError
    data object Unknown : TwitchInboxError
}

class TwitchInboxException(val error: TwitchInboxError, cause: Throwable? = null) : Exception(cause)
