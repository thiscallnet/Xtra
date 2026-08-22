package com.github.andreyasadchy.xtra.model.helix.schedule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class StreamScheduleResponse(
    val data: StreamSchedule? = null,
)

@Serializable
class StreamSchedule(
    @SerialName("broadcaster_id")
    val broadcasterId: String? = null,
    @SerialName("broadcaster_login")
    val broadcasterLogin: String? = null,
    @SerialName("broadcaster_name")
    val broadcasterName: String? = null,
    val segments: List<StreamScheduleSegment> = emptyList(),
)

@Serializable
class StreamScheduleSegment(
    val id: String? = null,
    @SerialName("start_time")
    val startTime: String? = null,
    @SerialName("end_time")
    val endTime: String? = null,
    @SerialName("canceled_until")
    val canceledUntil: String? = null,
    val title: String? = null,
    val category: StreamScheduleCategory? = null,
    @SerialName("is_recurring")
    val isRecurring: Boolean = false,
)

@Serializable
class StreamScheduleCategory(
    val id: String? = null,
    val name: String? = null,
)
