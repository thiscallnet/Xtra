package com.github.andreyasadchy.xtra.model.chat

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "favorite_emotes",
    primaryKeys = ["provider", "emote_id"],
)
data class FavoriteEmote(
    val provider: String,
    @ColumnInfo(name = "emote_id")
    val emoteId: String,
    @ColumnInfo(name = "favorited_at")
    val favoritedAt: Long,
)
