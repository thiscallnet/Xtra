package com.github.andreyasadchy.xtra.model.helix.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BlockedUser(
    @SerialName("user_id")
    val id: String? = null,
    @SerialName("user_login")
    val login: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
)
