package com.github.andreyasadchy.xtra.model.id

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code")
    val deviceCode: String? = null,
    @SerialName("user_code")
    val userCode: String? = null,
    @SerialName("verification_uri")
    val verificationUri: String? = null,
    @SerialName("verification_uri_complete")
    val verificationUriComplete: String? = null,
    @SerialName("expires_in")
    val expiresIn: Int? = null,
    val interval: Int? = null,
    val error: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
    val message: String? = null,
    @Transient
    val httpStatusCode: Int = 200,
)
