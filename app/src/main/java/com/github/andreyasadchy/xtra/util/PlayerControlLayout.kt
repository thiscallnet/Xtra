package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding

object PlayerControlLayout {

    const val GROUP_QUICK = "quick"
    const val GROUP_MENU = "menu"
    const val GROUP_HIDDEN = "hidden"

    const val ANCHOR_TOP_START = "top_start"
    const val ANCHOR_TOP_CENTER = "top_center"
    const val ANCHOR_TOP_END = "top_end"
    const val ANCHOR_MIDDLE_START = "middle_start"
    const val ANCHOR_MIDDLE_END = "middle_end"
    const val ANCHOR_BOTTOM_START = "bottom_start"
    const val ANCHOR_BOTTOM_CENTER = "bottom_center"
    const val ANCHOR_BOTTOM_END = "bottom_end"

    val anchors = setOf(
        ANCHOR_TOP_START,
        ANCHOR_TOP_CENTER,
        ANCHOR_TOP_END,
        ANCHOR_MIDDLE_START,
        ANCHOR_MIDDLE_END,
        ANCHOR_BOTTOM_START,
        ANCHOR_BOTTOM_CENTER,
        ANCHOR_BOTTOM_END,
    )

    /**
     * The small set of actions that belongs on the primary TV control surface.
     * Secondary actions remain available through the More menu where one exists.
     */
    private val tvPrimaryActions = setOf(
        "follow",
        "quality",
        "volume",
        "chat",
        "live",
    )

    data class ControlPlacement(
        val action: String,
        var group: String,
        var anchor: String,
    )

    data class ControlDefinition(
        val action: String,
        val quickKey: String?,
        val quickDefault: Boolean,
        val menuKey: String?,
        val menuDefault: Boolean,
        val canQuick: Boolean,
        val canMenu: Boolean,
    )

    internal val controlDefinitions = listOf(
        ControlDefinition("minimize", C.PLAYER_MINIMIZE, true, null, false, canQuick = true, canMenu = false),
        ControlDefinition("download", C.PLAYER_DOWNLOAD, false, C.PLAYER_MENU_DOWNLOAD, true, canQuick = true, canMenu = true),
        ControlDefinition("follow", C.PLAYER_FOLLOW, false, null, false, canQuick = true, canMenu = false),
        ControlDefinition("quality", C.PLAYER_SETTINGS, true, C.PLAYER_MENU_QUALITY, false, canQuick = true, canMenu = true),
        ControlDefinition("speed", C.PLAYER_SPEED_BUTTON, true, C.PLAYER_MENU_SPEED, false, canQuick = true, canMenu = true),
        ControlDefinition("chapters", C.PLAYER_GAMES_BUTTON, true, C.PLAYER_MENU_GAMES, false, canQuick = true, canMenu = true),
        ControlDefinition("restart", C.PLAYER_RESTART, true, C.PLAYER_MENU_RESTART, false, canQuick = true, canMenu = true),
        ControlDefinition("live", C.PLAYER_SEEK_LIVE, false, null, false, canQuick = true, canMenu = false),
        ControlDefinition("clip", C.PLAYER_CLIP_BUTTON, true, null, false, canQuick = true, canMenu = false),
        ControlDefinition("volume", C.PLAYER_VOLUME_BUTTON, true, C.PLAYER_MENU_VOLUME, false, canQuick = true, canMenu = true),
        ControlDefinition("compressor", C.PLAYER_AUDIO_COMPRESSOR_BUTTON, true, null, false, canQuick = true, canMenu = false),
        ControlDefinition("mode", C.PLAYER_MODE, false, null, false, canQuick = true, canMenu = false),
        ControlDefinition("subtitles", C.PLAYER_SUBTITLES, false, C.PLAYER_MENU_SUBTITLES, true, canQuick = true, canMenu = true),
        ControlDefinition("chat_input", C.PLAYER_CHAT_BAR_TOGGLE, false, C.PLAYER_MENU_CHAT_BAR, true, canQuick = true, canMenu = true),
        ControlDefinition("chat", C.PLAYER_CHAT_TOGGLE, true, C.PLAYER_MENU_CHAT_TOGGLE, false, canQuick = true, canMenu = true),
        ControlDefinition("fullscreen", C.PLAYER_FULLSCREEN, true, null, false, canQuick = true, canMenu = false),
        ControlDefinition("viewers", C.PLAYER_VIEWER_LIST, false, C.PLAYER_MENU_VIEWER_LIST, true, canQuick = false, canMenu = true),
        ControlDefinition("bookmark", null, false, C.PLAYER_MENU_BOOKMARK, true, canQuick = false, canMenu = true),
        ControlDefinition("share", null, false, C.PLAYER_MENU_SHARE, true, canQuick = false, canMenu = true),
        ControlDefinition("find_vod", null, false, C.PLAYER_MENU_FIND_VOD, true, canQuick = false, canMenu = true),
        ControlDefinition("sleep", C.PLAYER_SLEEP, false, C.PLAYER_MENU_SLEEP, true, canQuick = true, canMenu = true),
        ControlDefinition("aspect", C.PLAYER_ASPECT, true, C.PLAYER_MENU_ASPECT, false, canQuick = true, canMenu = true),
        ControlDefinition("reload_emotes", null, false, C.PLAYER_MENU_RELOAD_EMOTES, true, canQuick = false, canMenu = true),
        ControlDefinition("disconnect_chat", null, false, C.PLAYER_MENU_CHAT_DISCONNECT, true, canQuick = false, canMenu = true),
    )

    fun applyToPlayer(context: Context, binding: FragmentPlayerBinding) {
        val focusedControl = binding.playerControls.root.findFocus()
        val placements = controlPlacements(
            context.prefs().getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
            if (context.isTelevision()) tvControlLayout(context) else legacyControlLayout(context),
        )
        with(binding.playerControls) {
            val containers = mapOf(
                ANCHOR_TOP_START to topStartLayout,
                ANCHOR_TOP_CENTER to topCenterLayout,
                ANCHOR_TOP_END to topRightLayout,
                ANCHOR_MIDDLE_START to middleLeftLayout,
                ANCHOR_MIDDLE_END to middleRightLayout,
                ANCHOR_BOTTOM_START to bottomLeftLayout,
                ANCHOR_BOTTOM_CENTER to bottomCenterLayout,
                ANCHOR_BOTTOM_END to bottomRightLayout,
            )
            val controls = playerControlViews(binding)
            val placementByAction = placements.associateBy { it.action }
            val quickOrderByAnchor = placements
                .filter { it.group == GROUP_QUICK }
                .groupBy { it.anchor }
                .mapValues { (_, items) -> items.map { it.action } }

            controls.forEach { (action, view) ->
                val placement = placementByAction[action]
                val container = containers[placement?.anchor] ?: containers.getValue(defaultAnchor(action))
                if (view.parent !== container) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    container.addView(view)
                }
                val allowedOnTv = !context.isTelevision() || action in tvPrimaryActions
                view.visibility = if (allowedOnTv && placement?.group == GROUP_QUICK && view.hasOnClickListeners()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                if (!allowedOnTv) view.isFocusable = false
            }
            containers.forEach { (anchor, container) ->
                reorderChildren(
                    container,
                    quickOrderByAnchor[anchor].orEmpty(),
                    controls,
                )
            }

            // Live captions are intentionally not part of the customizable control
            // layout, but they must still have a stable position after the other
            // children are reordered. Keep the caption toggle immediately before
            // fullscreen in the bottom-right controls.
            if (liveCaptions.parent === bottomRightLayout) {
                bottomRightLayout.removeView(liveCaptions)
                val fullscreenIndex = bottomRightLayout.indexOfChild(fullscreen)
                if (fullscreenIndex >= 0) {
                    bottomRightLayout.addView(liveCaptions, fullscreenIndex)
                } else {
                    bottomRightLayout.addView(liveCaptions)
                }
            }
        }

        hideSecondaryActionsOnTelevision(context, binding)

        if (context.isTelevision() && focusedControl != null) {
            // Reparenting a focused control clears focus on some Android TV builds. Restore
            // the same control after the layout pass instead of letting focus escape into the
            // hidden navigation destination behind the player.
            focusedControl.post {
                // A remote key may have moved focus to another player control while this
                // deferred layout callback was waiting. In that case preserve the newer user
                // choice; only restore when reparenting left the player controls without focus.
                val currentControlFocus = binding.playerControls.root.findFocus()
                if (currentControlFocus == null &&
                    focusedControl.isShown &&
                    focusedControl.isFocusable &&
                    focusedControl.isEnabled
                ) {
                    focusedControl.requestFocus()
                }
            }
        }
    }

    fun controlPlacements(serialized: String?, fallback: String): List<ControlPlacement> {
        val fallbackItems = fallback.split(',').mapNotNull(::parseControlPlacement)
        val knownActions = fallbackItems.map { it.action }.toSet()
        val savedItems = serialized.orEmpty().split(',').mapNotNull(::parseControlPlacement)
            .filter { it.action in knownActions }
        return (savedItems + fallbackItems).distinctBy { it.action }
    }

    fun serializeControlLayout(items: List<ControlPlacement>): String = items
        .distinctBy { it.action }
        .joinToString(",") { item ->
            val group = normalizedGroup(item.action, item.group)
            val anchor = item.anchor.takeIf { it in anchors } ?: defaultAnchor(item.action)
            "${item.action}:$group:$anchor"
        }

    internal fun defaultControlLayout(): String = controlDefinitions.joinToString(",") { definition ->
        val group = when {
            definition.canQuick && definition.quickDefault -> GROUP_QUICK
            definition.menuDefault -> GROUP_MENU
            else -> GROUP_HIDDEN
        }
        "${definition.action}:$group"
    }

    /**
     * Rebuild the runtime visibility baseline without deciding where a control belongs.
     * A click listener is the player-specific signal that the control is currently eligible.
     */
    fun refreshAvailableControls(binding: FragmentPlayerBinding) {
        playerControlViews(binding).values.forEach { view ->
            view.visibility = if (view.hasOnClickListeners()) View.VISIBLE else View.GONE
        }
        hideSecondaryActionsOnTelevision(binding.root.context, binding)
    }

    /** Keep phone quick-control preferences from crowding the TV player surface. */
    fun hideSecondaryActionsOnTelevision(context: Context, binding: FragmentPlayerBinding) {
        if (!context.isTelevision()) return
        playerControlViews(binding).forEach { (action, view) ->
            if (action !in tvPrimaryActions) {
                view.visibility = View.GONE
                view.isFocusable = false
            }
        }
    }

    internal fun isTvPrimaryAction(action: String): Boolean = action in tvPrimaryActions

    private fun playerControlViews(binding: FragmentPlayerBinding): Map<String, View> = with(binding.playerControls) {
        mapOf(
            "minimize" to minimize,
            "download" to download,
            "follow" to follow,
            "sleep" to sleepTimer,
            "aspect" to aspectRatio,
            "speed" to speed,
            "quality" to quality,
            "restart" to restart,
            "live" to seekLive,
            "clip" to clip,
            "chapters" to vodGames,
            "volume" to volume,
            "compressor" to audioCompressor,
            "mode" to audioOnly,
            "subtitles" to subtitles,
            "chat_input" to toggleChatInput,
            "chat" to toggleChat,
            "fullscreen" to fullscreen,
        )
    }

    // Keep pre-editor installations faithful to their old visibility preferences until
    // they have a serialized layout of their own.
    private fun legacyControlLayout(context: Context): String = controlDefinitions.joinToString(",") { definition ->
        val quick = definition.canQuick && definition.quickKey?.let {
            context.prefs().getBoolean(it, definition.quickDefault)
        } == true
        val menu = definition.canMenu && definition.menuKey?.let {
            context.prefs().getBoolean(it, definition.menuDefault)
        } == true
        val group = when {
            quick -> GROUP_QUICK
            menu -> GROUP_MENU
            else -> GROUP_HIDDEN
        }
        "${definition.action}:$group"
    }

    private fun tvControlLayout(context: Context): String = controlDefinitions.joinToString(",") { definition ->
        val quick = definition.action in tvPrimaryActions && definition.quickKey?.let {
            // TV has its own defaults for the primary surface, while a stored
            // preference still lets a user intentionally hide one of them.
            context.prefs().getBoolean(it, true)
        } == true
        val menu = definition.canMenu && definition.menuKey?.let {
            context.prefs().getBoolean(it, definition.menuDefault)
        } == true
        val group = when {
            quick -> GROUP_QUICK
            menu -> GROUP_MENU
            else -> GROUP_HIDDEN
        }
        "${definition.action}:$group"
    }

    internal fun canQuick(action: String): Boolean = controlDefinitions
        .firstOrNull { it.action == action }
        ?.canQuick == true

    internal fun canMenu(action: String): Boolean = controlDefinitions
        .firstOrNull { it.action == action }
        ?.canMenu == true

    fun defaultAnchor(action: String): String = when (action) {
        "minimize" -> ANCHOR_TOP_START
        "download", "follow", "quality", "speed", "sleep", "aspect" -> ANCHOR_TOP_END
        "chapters", "restart", "live", "clip", "volume", "compressor", "mode" -> ANCHOR_BOTTOM_START
        "subtitles", "chat_input", "chat", "fullscreen" -> ANCHOR_BOTTOM_END
        else -> ANCHOR_TOP_END
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

    internal fun parseControlPlacement(serialized: String): ControlPlacement? {
        val parts = serialized.split(':')
        val action = parts.getOrNull(0)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val group = parts.getOrNull(1)?.trim()?.takeIf {
            it in setOf(GROUP_QUICK, GROUP_MENU, GROUP_HIDDEN)
        } ?: GROUP_HIDDEN
        val anchor = parts.getOrNull(2)?.trim()?.takeIf { it in anchors } ?: defaultAnchor(action)
        return ControlPlacement(action, normalizedGroup(action, group), anchor)
    }

    private fun normalizedGroup(action: String, group: String): String = when {
        group == GROUP_QUICK && !canQuick(action) -> if (canMenu(action)) GROUP_MENU else GROUP_HIDDEN
        group == GROUP_MENU && !canMenu(action) -> if (canQuick(action)) GROUP_QUICK else GROUP_HIDDEN
        group in setOf(GROUP_QUICK, GROUP_MENU, GROUP_HIDDEN) -> group
        else -> GROUP_HIDDEN
    }

    private fun parseActions(serialized: String): List<String> = serialized
        .split(',')
        .mapNotNull { it.substringBefore(':').trim().takeIf(String::isNotEmpty) }
}
