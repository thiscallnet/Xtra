package com.github.andreyasadchy.xtra.model.gql

import kotlinx.serialization.Serializable

@Serializable
class GqlError(
    val message: String? = null,
    val extensions: Extensions? = null,
) {
    fun code(): String? = extensions?.code

    @Serializable
    class Extensions(
        val code: String? = null,
    )
}
