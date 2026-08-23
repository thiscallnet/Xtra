package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks", indices = [Index("videoId"), Index("userId")])
class Bookmark(
    val videoId: String? = null,
    val userId: String? = null,
    var userLogin: String? = null,
    var userName: String? = null,
    var userType: String? = null,
    var userBroadcasterType: String? = null,
    var userLogo: String? = null,
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val title: String? = null,
    val createdAt: String? = null,
    val thumbnail: String? = null,
    val type: String? = null,
    val duration: String? = null,
    val animatedPreviewURL: String? = null,
) {

    @PrimaryKey(autoGenerate = true)
    var id = 0
}
