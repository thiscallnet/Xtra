package com.github.andreyasadchy.xtra.model.ui

data class UpcomingStream(
    val id: String,
    val channelId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val channelImageURL: String?,
    val previewImageURL: String? = null,
    val title: String?,
    val gameName: String?,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val isRecurring: Boolean,
)
