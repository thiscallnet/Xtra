package com.github.andreyasadchy.xtra.model.ui

data class ChannelViewer(
    val login: String,
    val id: String? = null,
    val displayName: String? = null,
    val profileImageURL: String? = null,
)

class ChannelViewerList(
    val broadcasters: List<ChannelViewer>,
    val moderators: List<ChannelViewer>,
    val vips: List<ChannelViewer>,
    val viewers: List<ChannelViewer>,
    val count: Int?,
)
