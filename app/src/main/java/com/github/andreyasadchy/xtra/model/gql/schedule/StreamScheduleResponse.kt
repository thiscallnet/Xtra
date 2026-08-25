package com.github.andreyasadchy.xtra.model.gql.schedule

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class StreamScheduleResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val user: User? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val bannerImageURL: String? = null,
        val channel: Channel? = null,
    )

    @Serializable
    class Channel(
        val id: String? = null,
        val schedule: Schedule? = null,
    )

    @Serializable
    class Schedule(
        val segments: List<Segment>? = null,
        val nextSegment: Segment? = null,
    )

    @Serializable
    class Segment(
        val id: String? = null,
        val title: String? = null,
        val startAt: String? = null,
        val endAt: String? = null,
        val isCancelled: Boolean? = null,
        val cancelledUntil: String? = null,
        val repeatEndsAfterCount: Int? = null,
        val categories: List<Category>? = null,
    )

    @Serializable
    class Category(
        val name: String? = null,
    )
}
