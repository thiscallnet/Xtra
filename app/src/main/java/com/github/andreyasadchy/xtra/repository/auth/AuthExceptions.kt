package com.github.andreyasadchy.xtra.repository.auth

import java.io.IOException

open class TwitchAuthException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class TwitchAuthHttpException(
    val statusCode: Int,
    val errorCode: String? = null,
    description: String? = null,
    cause: Throwable? = null,
) : TwitchAuthException(
    message = description ?: errorCode ?: "Twitch authentication request failed",
    cause = cause,
)

class TwitchAuthProtocolException(
    message: String,
    cause: Throwable? = null,
) : TwitchAuthException(message, cause)
