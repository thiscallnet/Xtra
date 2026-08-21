package com.github.andreyasadchy.xtra.model.id

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Int? = null,
    @SerialName("scope")
    val scopes: List<String> = emptyList(),
    @SerialName("token_type")
    val tokenType: String? = null,
    val status: Int? = null,
    val error: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
    val message: String? = null,
    @Transient
    val httpStatusCode: Int = 200,
) {
    val token: String?
        get() = accessToken

    /** Twitch uses `message` for device-flow states such as authorization_pending. */
    val oauthError: String?
        get() = error?.takeIf { it.isNotBlank() } ?: message?.takeIf { it.isNotBlank() }
}
