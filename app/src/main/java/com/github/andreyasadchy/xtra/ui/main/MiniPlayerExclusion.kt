package com.github.andreyasadchy.xtra.ui.main

internal fun calculateMiniPlayerBottomExclusion(
    navigationTop: Int,
    playerTop: Int,
    gap: Int,
): Int = (navigationTop - playerTop + gap).coerceAtLeast(0)
