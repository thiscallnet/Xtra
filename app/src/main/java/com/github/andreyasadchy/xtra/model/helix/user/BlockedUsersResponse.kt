package com.github.andreyasadchy.xtra.model.helix.user

import com.github.andreyasadchy.xtra.model.helix.Pagination
import kotlinx.serialization.Serializable

@Serializable
class BlockedUsersResponse(
    val data: List<BlockedUser> = emptyList(),
    val pagination: Pagination? = null,
)
