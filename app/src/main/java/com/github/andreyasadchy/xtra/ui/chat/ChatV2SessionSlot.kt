package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Main-thread-owned Multiview tile session slot. The published active session remains stable
 * while a handle is stopped and started again, and changes only when the tile changes channel.
 */
internal class ChatV2SessionSlot {
    private val _activeSession = MutableStateFlow<ActiveChatSession?>(null)
    val activeSession: StateFlow<ActiveChatSession?> = _activeSession.asStateFlow()

    @Volatile
    private var handle: ChatSessionHandle? = null
    @Volatile
    private var generation = 0L
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleCommands = Channel<LifecycleCommand>(Channel.UNLIMITED)

    init {
        lifecycleScope.launch {
            for (command in lifecycleCommands) {
                if (!isCurrent(command.handle, command.generation)) continue
                try {
                    when (command) {
                        is LifecycleCommand.Start -> command.handle.start()
                        is LifecycleCommand.Stop -> command.handle.stop()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    command.onFailure(error)
                }
            }
        }
    }

    fun getOrCreate(
        spec: LiveChatSessionSpec,
        create: (LiveChatSessionSpec) -> ChatSessionHandle,
    ): ChatSessionHandle {
        handle?.takeIf { it.active.spec == spec }?.let { return it }

        generation++
        handle?.closeAsync()
        return create(spec).also {
            handle = it
            _activeSession.value = it.active
        }
    }

    fun current(): ChatSessionHandle? = handle

    fun generation(): Long = generation

    fun isCurrent(expectedHandle: ChatSessionHandle, expectedGeneration: Long): Boolean =
        handle === expectedHandle && generation == expectedGeneration

    /** Queues lifecycle intent in call order; reconnect therefore follows a pending disconnect. */
    fun requestStart(onFailure: (Throwable) -> Unit = {}) {
        val currentHandle = handle ?: return
        lifecycleCommands.trySend(
            LifecycleCommand.Start(currentHandle, generation, onFailure),
        )
    }

    fun requestStop(onFailure: (Throwable) -> Unit = {}) {
        val currentHandle = handle ?: return
        lifecycleCommands.trySend(
            LifecycleCommand.Stop(currentHandle, generation, onFailure),
        )
    }

    fun invalidate() {
        generation++
        val old = handle
        handle = null
        _activeSession.value = null
        old?.closeAsync()
        lifecycleCommands.close()
        lifecycleScope.cancel()
    }

    private sealed interface LifecycleCommand {
        val handle: ChatSessionHandle
        val generation: Long
        val onFailure: (Throwable) -> Unit

        data class Start(
            override val handle: ChatSessionHandle,
            override val generation: Long,
            override val onFailure: (Throwable) -> Unit,
        ) : LifecycleCommand

        data class Stop(
            override val handle: ChatSessionHandle,
            override val generation: Long,
            override val onFailure: (Throwable) -> Unit,
        ) : LifecycleCommand
    }
}
