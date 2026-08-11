package com.github.andreyasadchy.xtra.model.gql.chat

import kotlinx.serialization.Serializable

@Serializable
class MakePredictionResponse(
    val errors: List<MakePredictionError>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val makePrediction: Payload? = null,
    ) {
        fun errorCode(): String? = makePrediction?.error?.code

        fun errorMessage(): String? = makePrediction?.error?.message

        fun hasPayload(): Boolean = makePrediction != null
    }

    @Serializable
    class Payload(
        val error: PredictionError? = null,
        val prediction: PredictionResult? = null,
    )

    @Serializable
    class PredictionError(
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    class PredictionResult(
        val id: String? = null,
    )

    @Serializable
    class MakePredictionError(
        val message: String? = null,
    )
}
