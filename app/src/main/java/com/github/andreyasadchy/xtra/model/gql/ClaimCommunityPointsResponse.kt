package com.github.andreyasadchy.xtra.model.gql

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class ClaimCommunityPointsResponse(
    val data: Data? = null,
    val errors: List<Error>? = null,
) {
    @Serializable
    class Data(
        val claimCommunityPoints: JsonElement? = null,
    )

    val isSuccessful: Boolean
        get() = errors.isNullOrEmpty() && data?.claimCommunityPoints != null
}
