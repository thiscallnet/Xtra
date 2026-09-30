package com.github.andreyasadchy.xtra.util.m3u8

data class DateRange(
    val id: String,
    val rangeClass: String?,
    val startDate: String,
    val endDate: String?,
    val duration: Float?,
    val plannedDuration: Float?,
    val vaftMarker: Boolean
)