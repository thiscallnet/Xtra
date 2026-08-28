package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserCardBadge(
    val id: String,
    val setId: String,
    val version: String,
    val title: String,
    val description: String,
    val imageUrl: String,
) : Parcelable
