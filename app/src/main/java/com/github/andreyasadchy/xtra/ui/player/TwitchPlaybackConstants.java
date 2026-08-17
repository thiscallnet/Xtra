package com.github.andreyasadchy.xtra.ui.player;

/** Shared Twitch HLS host patterns used by every network backend. */
public final class TwitchPlaybackConstants {
    public static final String MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$";
    public static final String MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$";

    private TwitchPlaybackConstants() {
    }
}
