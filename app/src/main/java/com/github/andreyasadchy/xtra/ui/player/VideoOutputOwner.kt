package com.github.andreyasadchy.xtra.ui.player

/**
 * Owns one player's video target and transfers it deterministically.
 *
 * The callbacks are deliberately supplied by the caller so this state machine
 * can be tested without constructing Android video views.
 */
internal class VideoOutputOwner<Player : Any, Target : Any>(
    private val attachTarget: (Player, Target) -> Unit,
    private val detachTarget: (Player, Target) -> Unit,
) {
    private var binding: Binding<Player, Target>? = null

    fun attach(player: Player, target: Target) {
        val current = binding
        if (current?.player === player && current.target === target) return

        binding = null
        current?.let { detachTarget(it.player, it.target) }

        val next = Binding(player, target)
        binding = next
        try {
            attachTarget(player, target)
        } catch (error: Throwable) {
            if (binding === next) binding = null
            throw error
        }
    }

    fun detach(player: Player, target: Target) {
        val current = binding ?: return
        if (current.player !== player || current.target !== target) return

        binding = null
        detachTarget(current.player, current.target)
    }

    fun clear() {
        val current = binding ?: return
        binding = null
        detachTarget(current.player, current.target)
    }

    internal fun attachedPlayer(): Player? = binding?.player

    private data class Binding<Player : Any, Target : Any>(
        val player: Player,
        val target: Target,
    )
}
