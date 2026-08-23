package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding

object PlayerControlLayout {

    fun applyToPlayer(context: Context, binding: FragmentPlayerBinding) {
        val order = orderedActions(
            context.prefs().getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
            SettingsMigration.defaultControlLayout(),
        )
        with(binding.playerControls) {
            reorderChildren(
                topLeftLayout,
                order,
                mapOf("minimize" to minimize),
            )
            reorderChildren(
                topRightLayout,
                order,
                mapOf(
                    "download" to download,
                    "follow" to follow,
                    "sleep" to sleepTimer,
                    "aspect" to aspectRatio,
                    "speed" to speed,
                    "quality" to quality,
                ),
            )
            reorderChildren(
                bottomLeftLayout,
                order,
                mapOf(
                    "restart" to restart,
                    "live" to seekLive,
                    "clip" to clip,
                    "chapters" to vodGames,
                    "volume" to volume,
                    "compressor" to audioCompressor,
                    "mode" to audioOnly,
                ),
            )
            reorderChildren(
                bottomRightLayout,
                order,
                mapOf(
                    "subtitles" to subtitles,
                    "chat_input" to toggleChatInput,
                    "chat" to toggleChat,
                    "fullscreen" to fullscreen,
                ),
            )
        }
    }

    fun orderedActions(serialized: String?, fallback: String): List<String> {
        val fallbackActions = parseActions(fallback)
        val knownActions = fallbackActions.toSet()
        val savedActions = parseActions(serialized.orEmpty())
            .filter { it in knownActions }

        return (savedActions + fallbackActions).distinct()
    }

    fun reorderChildren(
        container: ViewGroup,
        order: List<String>,
        controls: Map<String, View>,
    ) {
        val orderIndex = order.withIndex().associate { (index, action) -> action to index }
        val childIndex = controls.entries.associate { (action, view) ->
            view to (orderIndex[action] ?: Int.MAX_VALUE)
        }
        val orderedChildren = (0 until container.childCount)
            .map(container::getChildAt)
            .sortedBy { childIndex[it] ?: Int.MAX_VALUE }

        orderedChildren.forEach(container::removeView)
        orderedChildren.forEach(container::addView)
    }

    private fun parseActions(serialized: String): List<String> = serialized
        .split(',')
        .mapNotNull { it.substringBefore(':').trim().takeIf(String::isNotEmpty) }
}
