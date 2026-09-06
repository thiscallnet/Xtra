package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.use
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.NavOptions
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import coil3.Image
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.target
import coil3.request.transformations
import coil3.target.ImageViewTarget
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.databinding.ViewPinnedChatMessageBinding
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityState
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.selectedVanityBadge
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.PollVoteState
import com.github.andreyasadchy.xtra.model.chat.PinnedChatMessage
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.PredictionBetState
import com.github.andreyasadchy.xtra.model.ui.ChannelPoints
import com.github.andreyasadchy.xtra.model.ui.ChannelPointReward
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRedemptionResult
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.WatchStreak
import com.github.andreyasadchy.xtra.model.ui.WatchStreakShareResult
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.Companion.ChatViewModelFactory
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.restoreDecodedMemoryImage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage as V2ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaintShadow
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatUserDecoration
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatV2RendererController
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatViewportState
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationLabels
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.ChatInputToken
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.EmoteRecommendation
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.EmoteRecommendationEngine
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.ui.player.Media3PlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.view.AutoCompleteAdapter
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DEFAULT_CHAT_BADGE_SIZE_DP
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chatBadgeSizeOrDefault
import com.github.andreyasadchy.xtra.util.chat.PredictionState
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.isChatEnabled
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

private const val TWITCH_LISTENING_ONLY_BADGE_URL =
    "https://static-cdn.jtvnw.net/badges/v1/199a0dba-58f3-494e-a7fc-1fa0a1001fb8/3"

private val chatIdentityBadgeDrawableCache = object : LruCache<String, Drawable.ConstantState>(32) {}

private fun restoreChatIdentityBadgeDrawable(cacheKey: String, imageView: ImageView): Boolean {
    val state = synchronized(chatIdentityBadgeDrawableCache) {
        chatIdentityBadgeDrawableCache.get(cacheKey)
    } ?: return false
    imageView.setImageDrawable(state.newDrawable(imageView.resources))
    return true
}

private class ChatIdentityBadgeImageTarget(
    imageView: ImageView,
    private val cacheKey: String,
    private val isCurrent: () -> Boolean,
) : ImageViewTarget(imageView) {
    override fun onStart(placeholder: Image?) {
        if (isCurrent() && view.drawable != null) return
        super.onStart(placeholder)
    }

    override fun onSuccess(result: Image) {
        if (!isCurrent()) return
        super.onSuccess(result)
        view.drawable?.constantState?.let { state ->
            synchronized(chatIdentityBadgeDrawableCache) {
                chatIdentityBadgeDrawableCache.put(cacheKey, state)
            }
        }
    }

    override fun onError(error: Image?) {
        // Preserve the cached badge or the neutral trigger icon on image failure.
    }
}

internal fun shouldShowChatComposer(
    chatAvailable: Boolean,
    isSlidingPlayerLayout: Boolean,
    chatBarVisible: Boolean,
): Boolean = chatAvailable && (!isSlidingPlayerLayout || chatBarVisible)

internal fun matchesV2MessageUser(
    message: V2ChatMessage,
    selected: V2ChatMessage,
): Boolean {
    val selectedUser = selected.user ?: return false
    fun matches(userId: String?, login: String?): Boolean {
        val selectedId = selectedUser.id
        return if (!selectedId.isNullOrBlank()) {
            userId == selectedId
        } else {
            val selectedLogin = selectedUser.login
            !selectedLogin.isNullOrBlank() && login.equals(selectedLogin, ignoreCase = true)
        }
    }
    return matches(message.user?.id, message.user?.login) ||
            message.reply?.let { reply -> matches(reply.parentUserId, reply.parentUserLogin) } == true
}

internal fun ChatViewModel.v2DecorationSnapshot(): ChatDecorationSnapshot {
    val paints = synchronized(namePaints) {
        namePaints.mapNotNull { paint -> paint.id?.let { it to paint.toV2() } }.toMap()
    }
    val badges = synchronized(stvBadges) {
        stvBadges.mapNotNull { badge -> badge.toV2() }.toMap()
    }
    val users = synchronized(stvUsers) {
        stvUsers.associate { user ->
            user.userId to ChatUserDecoration(user.paintId, user.badgeId, user.emoteSetId)
        }
    }
    return ChatDecorationSnapshot(users = users, paints = paints, badges = badges)
}

private fun NamePaint.toV2() = ChatNamePaint(
    colors = colors?.toList().orEmpty(),
    imageUrl = imageUrl,
    colorPositions = colorPositions?.toList().orEmpty(),
    type = type,
    angle = angle,
    repeat = repeat == true,
    shadows = shadows.orEmpty().map { ChatNamePaintShadow(it.xOffset, it.yOffset, it.radius, it.color) },
)

private fun STVBadge.toV2(): Pair<String, ChatCatalogBadge>? {
    val key = id.takeIf { it.isNotBlank() } ?: return null
    val url = url4x ?: url3x ?: url2x ?: url1x ?: return null
    return key to ChatCatalogBadge(
        name = key,
        asset = ChatAssetSpec(ChatAssetKey(url), 18, 18, 18),
        provider = ChatAssetProvider.SEVEN_TV,
        setId = key,
        versionId = "default",
        info = name,
    )
}

internal data class ComposerOverlaySnapshot<Overlay, RestoreState>(
    val overlay: Overlay,
    val input: String,
    val restoreState: RestoreState,
    val submissionPending: Boolean,
)

internal fun <Overlay, RestoreState> captureComposerOverlaySnapshot(
    overlay: Overlay?,
    existing: ComposerOverlaySnapshot<Overlay, RestoreState>?,
    pendingRestoreState: RestoreState?,
    pendingInput: String?,
    currentInput: String?,
    submissionPending: Boolean,
): ComposerOverlaySnapshot<Overlay, RestoreState>? {
    val retainedOverlay = overlay ?: existing?.overlay ?: return null
    val restoreState = pendingRestoreState ?: existing?.restoreState ?: return null
    val input = if (submissionPending) {
        pendingInput ?: existing?.input.orEmpty()
    } else {
        currentInput ?: existing?.input.orEmpty()
    }
    return ComposerOverlaySnapshot(
        overlay = retainedOverlay,
        input = input,
        restoreState = restoreState,
        submissionPending = submissionPending,
    )
}

internal class ComposerOverlayStateStore<Overlay, RestoreState> {
    var active: ComposerOverlaySnapshot<Overlay, RestoreState>? = null
        private set

    fun open(overlay: Overlay, restoreState: RestoreState) {
        active = ComposerOverlaySnapshot(overlay, "", restoreState, submissionPending = false)
    }

    fun submit(input: String): ComposerOverlaySnapshot<Overlay, RestoreState>? {
        active = active?.copy(input = input, submissionPending = true)
        return active
    }

    fun markFailed(input: String): ComposerOverlaySnapshot<Overlay, RestoreState>? {
        active = active?.copy(input = input, submissionPending = false)
        return active
    }

    fun set(snapshot: ComposerOverlaySnapshot<Overlay, RestoreState>) {
        active = snapshot
    }

    fun clear(): RestoreState? {
        val restoreState = active?.restoreState
        active = null
        return restoreState
    }
}

class ChatFragment : BaseNetworkFragment(), MessageClickedDialog.OnButtonClickListener, ReplyClickedDialog.OnButtonClickListener, ChannelPointsDialog.Listener {

    private sealed interface ComposerOverlayState {
        data class Reward(val reward: ChannelPointReward) : ComposerOverlayState
        data class StreakShare(val streak: WatchStreak) : ComposerOverlayState
    }

    private var _binding: FragmentChatBinding? = null
    private var pinnedMessageBinding: ViewPinnedChatMessageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels { ChatViewModelFactory }
    private var adapter: ChatAdapter? = null
    private var chatV2Renderer: ChatV2RendererController? = null
    private var chatV2ViewportState = ChatViewportState()
    private var useChatV2 = false
    private var chatV2RendererVisible = true
    private var selectedV2Message: V2ChatMessage? = null
    private var selectedPinnedMessage: ChatMessage? = null
    private val v2Translations = mutableMapOf<String, String>()

    internal val isUsingChatV2: Boolean
        get() = useChatV2

    var chatMessageListener: ((ChatMessage) -> Unit)? = null
    var chatHistoryListener: ((List<ChatMessage>) -> Unit)? = null

    private var isChatTouched = false
    private var showChatStatus = false
    private var messagingEnabled = false
    private var messageViewWasVisibleBeforeReplay: Boolean? = null
    private var channelPointsIconUrl: String? = null
    private var channelPointsIconRequest: Disposable? = null
    private var channelPointsIconRequestGeneration = 0
    private var channelPointsIconLoaded = false
    private var channelPointsIconForeground: Int? = null
    private var dropImageUrl: String? = null
    private var dropImageTarget: ImageView? = null
    private var dropImageRequest: Disposable? = null
    private var dropImageRequestGeneration = 0
    private var pinnedBadgeRequests = mutableListOf<Disposable>()
    private var pinnedMessageTimerJob: Job? = null
    private var channelPointsAccessibilityLabel: String? = null
    private var chatIdentityPopup: ChatIdentityPopup? = null
    private var chatIdentityBadgeRequest: Disposable? = null
    private var chatIdentityBadgeUrl: String? = null
    private var composerOverlayState: ComposerOverlayState? = null
    private var pendingComposerText: String? = null
    private var composerSubmissionInProgress = false
    private var pendingChatSendResult: ChatSendResult? = null

    private data class ComposerRestoreState(
        val text: String,
        val selection: Int?,
        val reply: ReplyComposerState?,
    )

    private data class PendingOverlaySubmission(
        val state: ComposerOverlayState,
        val text: String,
        val restoreState: ComposerRestoreState,
    )

    private data class PendingChatSubmission(
        val text: String,
        val replyId: String?,
    )

    private data class ReplyComposerState(
        val replyId: String,
        val userLogin: String?,
        val userName: String?,
        val message: String?,
    )

    private var pendingChatSubmission: PendingChatSubmission? = null
    private var pendingOverlaySubmission: PendingOverlaySubmission? = null
    private val overlayStateStore = ComposerOverlayStateStore<ComposerOverlayState, ComposerRestoreState>()
    private var replyComposerState: ReplyComposerState? = null
    private var seenPinnedMessageId: String? = null
    private var displayedPinnedMessageId: String? = null
    private var pinnedMessageMinimized = false
    private var backPressedCallbackAdded = false
    private var lastSlowModeUiState = SlowModeState()
    private var dismissedDropPresentationKey: String? = null
    private var dropCalloutView: View? = null
    private var dropImageView: ImageView? = null
    private var dropTitleView: TextView? = null
    private var dropSubtitleView: TextView? = null
    private var dropProgressView: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var chatAdapterUpdatePosted = false
    private var chatAdapterReady = false
    private var chatSnapshotSyncPending = false
    private val pendingChatMutations = ArrayDeque<ChatViewModel.ChatMutation>()
    private var chatMutationRevision = 0L
    private var chatMutationGapCount = 0L
    private var chatSnapshotSyncCount = 0L
    private data class ChatViewportAnchor(val stableId: Long, val fallbackPosition: Int, val top: Int)
    private var pendingChatPublicationAnchor: ChatViewportAnchor? = null
    private var pendingChatPublicationFollowBottom = false
    private var userScrollGeneration = 0L
    private var userGestureActive = false
    private var pendingChatPublicationScrollGeneration = 0L

    private val chatAdapterUpdateRunnable = Runnable {
        chatAdapterUpdatePosted = false
        val currentBinding = _binding ?: return@Runnable
        val currentAdapter = adapter ?: return@Runnable
        val recyclerView = currentBinding.recyclerView
        val followBottom = !recyclerView.canScrollVertically(1)
        val anchor = if (followBottom) null else captureChatViewportAnchor(recyclerView, currentAdapter)
        pendingChatPublicationAnchor = anchor
        pendingChatPublicationFollowBottom = followBottom
        pendingChatPublicationScrollGeneration = userScrollGeneration
        while (pendingChatMutations.isNotEmpty()) {
            when (val firstMutation = pendingChatMutations.removeFirst()) {
                is ChatViewModel.ChatMutation.Append -> {
                    val appendMutations = ArrayList<ChatViewModel.ChatMutation.Append>()
                    appendMutations += firstMutation
                    while (pendingChatMutations.firstOrNull() is ChatViewModel.ChatMutation.Append) {
                        appendMutations += pendingChatMutations.removeFirst() as ChatViewModel.ChatMutation.Append
                    }
                    val coalesced = coalesceChatAppendMutations(appendMutations)
                    currentAdapter.appendMessages(coalesced.messages, coalesced.trimCount)
                    chatMutationRevision = coalesced.revision
                }
                is ChatViewModel.ChatMutation.Prepend -> {
                    val batches = ArrayList<List<ChatMessage>>()
                    batches += firstMutation.messages
                    var revision = firstMutation.revision
                    while (pendingChatMutations.firstOrNull() is ChatViewModel.ChatMutation.Prepend) {
                        val next = pendingChatMutations.removeFirst() as ChatViewModel.ChatMutation.Prepend
                        batches += next.messages
                        revision = next.revision
                    }
                    val messages = ArrayList<ChatMessage>(batches.sumOf { it.size })
                    batches.asReversed().forEach(messages::addAll)
                    currentAdapter.prependMessages(messages)
                    chatMutationRevision = revision
                }
                is ChatViewModel.ChatMutation.Clear -> {
                    pendingChatPublicationAnchor = null
                    pendingChatPublicationFollowBottom = false
                    currentAdapter.clearMessages()
                    chatMutationRevision = firstMutation.revision
                }
            }
        }
        // Append/prepend/replace publication is asynchronous. Scroll and anchor restoration are
        // performed by onChatMessagesPublished after the READY dataset mutation.
    }

    private var autoCompleteAdapter: AutoCompleteAdapter<Any>? = null
    private var recommendationAdapter: EmoteRecommendationAdapter? = null
    private val recommendationEngine = EmoteRecommendationEngine()
    private val recommendationInput = MutableStateFlow(RecommendationInput())
    private var currentRecommendations = emptyList<EmoteRecommendation>()
    private var currentRecommendationQuery: String? = null

    private data class RecommendationInput(
        val text: String = "",
        val cursor: Int = 0,
    )

    private data class RecommendationResult(
        val query: String,
        val recommendations: List<EmoteRecommendation>,
    )

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (dismissChatIdentityPopup()) return
            toggleEmoteMenu(false)
        }
    }

    private val messageDialog: MessageClickedDialog?
        get() = childFragmentManager.findFragmentByTag("messageDialog") as? MessageClickedDialog

    private val replyDialog: ReplyClickedDialog?
        get() = childFragmentManager.findFragmentByTag("replyDialog") as? ReplyClickedDialog

    private fun scheduleChatAdapterUpdate() {
        if (chatAdapterUpdatePosted) return
        if (!chatAdapterReady) return
        val recyclerView = _binding?.recyclerView ?: return
        chatAdapterUpdatePosted = true
        recyclerView.postOnAnimation(chatAdapterUpdateRunnable)
    }

    private fun onChatMessagesPublished(kind: ChatPublicationKind, hasMorePending: Boolean) {
        val recyclerView = _binding?.recyclerView ?: return
        val currentAdapter = adapter ?: return
        val viewportIsStillCurrent = userScrollGeneration == pendingChatPublicationScrollGeneration
        when (kind) {
            ChatPublicationKind.APPEND -> {
                // Re-check the live viewport. The user may have scrolled up while rendering ran.
                if (pendingChatPublicationFollowBottom && !recyclerView.canScrollVertically(1) && currentAdapter.itemCount > 0) {
                    recyclerView.scrollToPosition(currentAdapter.itemCount - 1)
                } else if (viewportIsStillCurrent && !pendingChatPublicationFollowBottom) {
                    pendingChatPublicationAnchor?.let { restoreChatViewportAnchor(recyclerView, currentAdapter, it) }
                }
            }
            ChatPublicationKind.PREPEND -> {
                if (viewportIsStillCurrent) {
                    pendingChatPublicationAnchor?.let { restoreChatViewportAnchor(recyclerView, currentAdapter, it) }
                }
            }
            ChatPublicationKind.REPLACE -> {
                if (pendingChatPublicationFollowBottom && !recyclerView.canScrollVertically(1) && currentAdapter.itemCount > 0) {
                    recyclerView.scrollToPosition(currentAdapter.itemCount - 1)
                } else if (viewportIsStillCurrent) {
                    pendingChatPublicationAnchor?.let { restoreChatViewportAnchor(recyclerView, currentAdapter, it) }
                }
            }
        }
        if (!hasMorePending) {
            pendingChatPublicationAnchor = null
            pendingChatPublicationFollowBottom = false
            pendingChatPublicationScrollGeneration = userScrollGeneration
        }
    }

    private fun expectedChatMutationRevision(): Long =
        pendingChatMutations.lastOrNull()?.revision ?: chatMutationRevision

    private fun dispatchChatMutationSideEffects(mutation: ChatViewModel.ChatMutation) {
        when (mutation) {
            is ChatViewModel.ChatMutation.Append -> {
                mutation.messages.forEach { message ->
                    chatMessageListener?.invoke(message)
                    messageDialog?.newMessage(message)
                    replyDialog?.newMessage(message)
                }
            }
            is ChatViewModel.ChatMutation.Prepend -> {
                chatHistoryListener?.invoke(mutation.messages)
                messageDialog?.addMessages(mutation.messages)
                replyDialog?.addMessages(mutation.messages)
            }
            is ChatViewModel.ChatMutation.Clear -> Unit
        }
    }

    private suspend fun synchronizeChatAdapterToSnapshot() {
        val currentAdapter = adapter ?: return
        if (!chatAdapterReady) return
        val currentBinding = _binding ?: return
        val snapshot = viewModel.chatSnapshot()
        if (!shouldSynchronizeChatSnapshot(
                chatMutationRevision,
                snapshot.revision,
            )
        ) {
            return
        }

        pendingChatMutations.clear()

        if (_binding !== currentBinding || adapter !== currentAdapter) return
        val syncStartedAt = SystemClock.elapsedRealtime()
        val recyclerView = currentBinding.recyclerView
        val followBottom = !recyclerView.canScrollVertically(1)
        val anchor =
            if (followBottom) null
            else captureChatViewportAnchor(recyclerView, currentAdapter)
        pendingChatPublicationAnchor = anchor
        pendingChatPublicationFollowBottom = followBottom
        pendingChatPublicationScrollGeneration = userScrollGeneration

        currentAdapter.replaceMessages(
            snapshot.messages,
        )
        chatMutationRevision = snapshot.revision
        chatSnapshotSyncCount++
        Log.d(
            "ChatPerf",
            "snapshot sync count=$chatSnapshotSyncCount " +
                "revision=${snapshot.revision} " +
                "messages=${snapshot.messages.size} " +
                "durationMs=${SystemClock.elapsedRealtime() - syncStartedAt}",
        )
        // The replacement remains staged until its complete renders are ready. The adapter
        // callback applies bottom/anchor behavior after the atomic dataset publication.
    }

    private fun captureChatViewportAnchor(
        recyclerView: RecyclerView,
        chatAdapter: ChatAdapter,
    ): ChatViewportAnchor? {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION || firstPosition >= chatAdapter.itemCount) return null
        val firstView = layoutManager.findViewByPosition(firstPosition) ?: return null
        return ChatViewportAnchor(
            stableId = chatAdapter.stableIdAt(firstPosition),
            fallbackPosition = firstPosition,
            top = firstView.top,
        )
    }

    private fun restoreChatViewportAnchor(
        recyclerView: RecyclerView,
        chatAdapter: ChatAdapter,
        anchor: ChatViewportAnchor?,
    ) {
        val savedAnchor = anchor ?: return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = chatAdapter.positionOfStableId(savedAnchor.stableId)
            .takeIf { it >= 0 }
            ?: savedAnchor.fallbackPosition.coerceAtMost(chatAdapter.itemCount - 1)
        if (position >= 0) {
            layoutManager.scrollToPositionWithOffset(position, savedAnchor.top)
        }
    }

    private var languageIdentifier: LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()

    override fun channelPointsFlow(): StateFlow<ChannelPoints?> = viewModel.channelPoints

    override fun watchStreakFlow(): StateFlow<WatchStreak?> = viewModel.watchStreak

    override fun pollFlow(): StateFlow<Poll?> = viewModel.poll

    override fun activePollFlow(): StateFlow<Poll?> = viewModel.activePoll

    override fun pollSecondsLeftFlow(): StateFlow<Int?> = viewModel.pollSecondsLeft

    override fun predictionFlow(): StateFlow<Prediction?> = viewModel.prediction

    override fun ongoingPredictionFlow(): StateFlow<Prediction?> = viewModel.ongoingPrediction

    override fun predictionSecondsLeftFlow(): StateFlow<Int?> = viewModel.predictionSecondsLeft

    override fun pollVoteStateFlow(): StateFlow<PollVoteState> = viewModel.pollVoteState

    override fun predictionBetStateFlow(): StateFlow<PredictionBetState> = viewModel.predictionBetState

    override fun canVotePoll(): Boolean = viewModel.canVotePoll()

    override fun votePoll(choiceId: String) = viewModel.votePoll(choiceId)

    override fun dismissPoll() = viewModel.dismissPoll()

    override fun canBetPrediction(): Boolean = viewModel.canBetPrediction()

    override fun betPrediction(outcomeId: String, points: Int) = viewModel.betPrediction(outcomeId, points)

    private fun showChannelPointsDialog(prediction: Prediction? = null) {
        if (childFragmentManager.findFragmentByTag(ChannelPointsDialog.TAG) == null) {
            ChannelPointsDialog.newInstance(prediction).show(
                childFragmentManager,
                ChannelPointsDialog.TAG,
            )
        }
    }

    override fun channelName(): String? {
        return arguments?.getString(KEY_CHANNEL_NAME)?.takeIf { it.isNotBlank() }
            ?: arguments?.getString(KEY_CHANNEL_LOGIN)
    }

    override fun channelEmotePickerItems(): List<Emote> = viewModel.channelEmotePickerItems()

    override fun channelEmotePickerUpdates(): Flow<Unit> = viewModel.channelEmotePickerUpdates()

    override fun channelPointModifiedEmotePickerItems(): List<Emote> = viewModel.channelPointModifiedEmotePickerItems()

    override fun channelPointModifiedEmotePickerUpdates(): Flow<Unit> = viewModel.channelPointModifiedEmotePickerUpdates()

    override fun redeemChannelPointReward(reward: ChannelPointReward, textInput: String?, emoteId: String?) {
        viewModel.redeemChannelPointReward(reward, textInput, emoteId)
    }

    override fun startChannelPointReward(reward: ChannelPointReward) {
        showComposerOverlay(ComposerOverlayState.Reward(reward))
    }

    override fun startWatchStreakShare(streak: WatchStreak) {
        showComposerOverlay(ComposerOverlayState.StreakShare(streak))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        pinnedMessageBinding = ViewPinnedChatMessageBinding.bind(
            _binding!!.root.findViewById(R.id.pinnedMessageOverlay),
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chatV2ViewportState = restoreChatV2ViewportState(savedInstanceState)
        useChatV2 = false
        seenPinnedMessageId = savedInstanceState?.getString(KEY_SEEN_PINNED_MESSAGE_ID)
        displayedPinnedMessageId = savedInstanceState?.getString(KEY_DISPLAYED_PINNED_MESSAGE_ID)
        pinnedMessageMinimized = savedInstanceState?.getBoolean(KEY_PINNED_MESSAGE_MINIMIZED) ?: false
        setupEmotePickerSizing()
        setupDropCallout()
        binding.chatTopOverlays.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePinnedMessageOverlayWidth()
        }
        updatePinnedMessageOverlayWidth()
        val pinnedBinding = pinnedMessageBinding ?: return
        pinnedBinding.pinnedMessageSeen.setOnClickListener {
            seenPinnedMessageId = displayedPinnedMessageId
            pinnedMessageTimerJob?.cancel()
            pinnedBinding.pinnedMessageOverlay.isGone = true
        }
        pinnedBinding.pinnedMessageMinimize.setOnClickListener {
            pinnedMessageMinimized = !pinnedMessageMinimized
            updatePinnedMessage(viewModel.pinnedChatMessage.value)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pinnedChatMessage.collectLatest(::updatePinnedMessage)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collectLatest { state ->
                    val showConnectionStatus = !useChatV2 && state == ChatViewModel.ConnectionState.RECONNECTING
                    binding.connectionStatus.isVisible = showConnectionStatus
                    if (showConnectionStatus) {
                        binding.connectionStatusText.setText(R.string.chat_reconnecting)
                        binding.connectionStatus.contentDescription =
                            "${binding.connectionStatusText.text}. ${getString(R.string.retry)}"
                    }
                }
            }
        }
        binding.connectionStatus.setOnClickListener { viewModel.retryLiveChat() }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.channelPointRedemption.collectLatest(::handleChannelPointRedemption)
                }
                launch {
                    viewModel.watchStreakShare.collectLatest(::handleWatchStreakShare)
                }
                launch {
                    viewModel.predictionBetResults.collectLatest { result ->
                        val suffix = result.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                        val message = if (result.success) {
                            getString(R.string.prediction_bet_success)
                        } else {
                            getString(R.string.prediction_bet_failed, suffix)
                        }
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.pollVoteResults.collectLatest { result ->
                        val message = if (result.success) {
                            getString(R.string.poll_vote_success)
                        } else {
                            getString(
                                R.string.poll_vote_failed,
                                result.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                            )
                        }
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.dropsUiState.collectLatest(::updateDropCallout)
                }
                launch {
                    viewModel.dropClaimResults.collectLatest(::handleDropClaimResult)
                }
            }
        }
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.slowModeState.collectLatest { state ->
                        updateSlowModeIndicator(state)
                        updateComposerButtons()
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        viewModel.activePoll,
                        viewModel.pollSecondsLeft,
                        viewModel.ongoingPrediction,
                        viewModel.predictionSecondsLeft,
                    ) { poll, pollSeconds, prediction, predictionSeconds ->
                        ChannelPointsActivityState(poll, pollSeconds, prediction, predictionSeconds)
                    }.collectLatest(::updateChannelPointsActivity)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        combine(
                            viewModel.predictionSecondsLeft,
                            viewModel.pollSecondsLeft,
                        ) { predictionSeconds, pollSeconds ->
                            predictionSeconds to pollSeconds
                        }.collect { (predictionSeconds, pollSeconds) ->
                            binding.happeningNow.updateTimers(predictionSeconds, pollSeconds)
                        }
                    }
                    val activeActivities = combine(
                        viewModel.ongoingPrediction,
                        viewModel.predictionSecondsLeft,
                        viewModel.activePoll,
                        viewModel.pollSecondsLeft,
                    ) { prediction, _, poll, _ ->
                        HappeningNowActivityState(
                            prediction = prediction,
                            poll = poll,
                            canBetPrediction = viewModel.canBetPrediction(),
                            canVotePoll = viewModel.canVotePoll(),
                        )
                    }.distinctUntilChanged()

                    combine(
                        activeActivities,
                        viewModel.happeningNowGift,
                        viewModel.happeningNowPredictionResult,
                        viewModel.happeningNowNewIds,
                        viewModel.dismissedHappeningNowIds,
                    ) { activity, gift, result, newIds, dismissedIds ->
                        HappeningNowView.RenderState(
                            gift = gift,
                            activePrediction = activity.prediction,
                            recentPredictionResult = result,
                            activePoll = activity.poll,
                            canBetPrediction = activity.canBetPrediction,
                            canVotePoll = activity.canVotePoll,
                            newIds = newIds,
                            dismissedIds = dismissedIds,
                        )
                    }.collectLatest { state ->
                        binding.happeningNow.render(
                            state = state,
                            onOpenChannelPoints = { showChannelPointsDialog() },
                            onOpenHistoricalPrediction = { prediction ->
                                showChannelPointsDialog(prediction)
                            },
                            onOpenGiftProfile = ::showHappeningNowGiftProfile,
                            onDismiss = viewModel::dismissHappeningNowCard,
                        )
                    }
                }
            }
            if (requireContext().prefs().isChatEnabled()) {
                val args = requireArguments()
                val channelId = args.getString(KEY_CHANNEL_ID)
                val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
                val isLive = args.getBoolean(KEY_IS_LIVE)
                useChatV2 = isLive &&
                        !channelId.isNullOrBlank() &&
                        !channelLogin.isNullOrBlank() &&
                        parentFragment !is MultiviewFragment &&
                        requireContext().prefs().getBoolean(C.CHAT_V2_ENABLED, true)
                val accountLogin = requireContext().tokenPrefs().getString(C.USERNAME, null)
                val isLoggedIn = !accountLogin.isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                val chatUrl = args.getString(KEY_CHAT_URL)
                if (isLive || (args.getString(KEY_VIDEO_ID) != null && args.getInt(KEY_START_TIME) != -1) || chatUrl != null) {
                    // The ViewModel is switched to Live by initialize(), which runs from
                    // BaseNetworkFragment.onResume(). Set up the live composer before that
                    // point as well; otherwise a v2 view can keep the XML-gone composer for
                    // its entire lifetime.
                    // Authentication controls capability, not structural setup. The
                    // account can become available after this view is created.
                    val enableMessaging = isLive && isLoggedIn &&
                            viewModel.activeChatMode is ChatViewModel.ActiveChatMode.Live
                    messagingEnabled = enableMessaging
                    val chatStyle = resolveChatRenderStyle(requireContext())
                    val profilePopoutGesture = ChatProfilePopoutGesture.fromPreference(
                        requireContext().prefs().getString(C.CHAT_PROFILE_POPOUT_GESTURE, "tap"),
                    )
                    val chatSizing = ChatSizing(
                        textSizeSp = chatStyle.textSizeSp,
                        emoteHeightPx = chatStyle.emoteHeightPx,
                        badgeHeightPx = chatStyle.badgeHeightPx,
                    )
                    val initialMessages = if (useChatV2) {
                        emptyList()
                    } else {
                        viewModel.chatSnapshot().also { chatMutationRevision = it.revision }.messages
                    }
                    adapter = ChatAdapter(
                        // The initial snapshot is rendered off-main before the adapter is attached.
                        // This prevents RecyclerView from ever binding an uncached message.
                        initialMessages = emptyList(),
                        localTwitchEmotes = viewModel.localTwitchEmotes,
                        thirdPartyEmotes = viewModel.thirdPartyEmotes,
                        globalBadges = viewModel.globalBadges,
                        channelBadges = viewModel.channelBadges,
                        cheerEmotes = viewModel.cheerEmotes,
                        namePaints = viewModel.namePaints,
                        stvBadges = viewModel.stvBadges,
                        personalEmoteSets = viewModel.personalEmoteSets,
                        stvUsers = viewModel.stvUsers,
                        enableTimestamps = requireContext().prefs().getBoolean(C.CHAT_TIMESTAMPS, false),
                        timestampFormat = requireContext().prefs().getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                        firstMsgVisibility = requireContext().prefs().getString(C.CHAT_FIRST_MSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
                        firstChatMsg = getString(R.string.chat_first),
                        redeemedChatMsg = getString(R.string.redeemed),
                        redeemedNoMsg = getString(R.string.user_redeemed),
                        replyMessage = getString(R.string.replying_to_message),
                        useRandomColors = requireContext().prefs().getBoolean(C.CHAT_RANDOM_COLOR, true),
                        useReadableColors = requireContext().prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                        isLightTheme = requireContext().obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                            it.getBoolean(0, false)
                        },
                        nameDisplay = requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0"),
                        useBoldNames = requireContext().prefs().getBoolean(C.CHAT_BOLD_NAMES, false),
                        showNamePaints = requireContext().prefs().getBoolean(C.CHAT_SHOW_PAINTS, true),
                        showBadges = requireContext().prefs().getBoolean(C.CHAT_SHOW_BADGES, true),
                        showSTVBadges = requireContext().prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                        showPersonalEmotes = requireContext().prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                        showSystemMessageEmotes = requireContext().prefs().getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                        chatUrl = chatUrl,
                        fragment = this@ChatFragment,
                        backgroundColor = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSurface),
                        dialogBackgroundColor = MaterialColors.getColor(
                            requireView(),
                            com.google.android.material.R.attr.colorSurfaceContainerLow
                        ),
                        imageLibrary = "0",
                        messageTextSize = chatSizing.textSizeSp,
                        emoteSize = chatSizing.emoteHeightPx,
                        badgeSize = chatSizing.badgeHeightPx,
                        inlineIconSize = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            DEFAULT_CHAT_BADGE_SIZE_DP * requireContext().prefs().getInt(C.CHAT_SIZE_MODIFIER, 100) / 100f,
                            resources.displayMetrics,
                        ).toInt(),
                        emoteQuality = "4",
                        animateGifs = requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true),
                        enableOverlayEmotes = requireContext().prefs().getBoolean(C.CHAT_ZERO_WIDTH, true),
                        translateMessage = this@ChatFragment::onTranslateMessageClicked,
                        showLanguageDownloadDialog = this@ChatFragment::showLanguageDownloadDialog,
                        channelId = channelId,
                        loggedInUser = if (enableMessaging) accountLogin else null,
                        messageClickListener = { channelId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            MessageClickedDialog.newInstance(
                                messagingEnabled = messagingEnabled,
                                channelId = channelId,
                                channelLogin = channelLogin,
                            ).show(this@ChatFragment.childFragmentManager, "messageDialog")
                        },
                        replyClickListener = {
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ReplyClickedDialog.newInstance(messagingEnabled).show(this@ChatFragment.childFragmentManager, "replyDialog")
                        },
                        imageClickListener = { url, name, format, isAnimated, source, thirdParty, emoteId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ImageClickedDialog.newInstance(url, name, format, isAnimated, source, thirdParty, emoteId).show(this@ChatFragment.childFragmentManager, "imageDialog")
                        },
                        profilePopoutGesture = profilePopoutGesture,
                    )
                    adapter?.onMessagesPublished = ::onChatMessagesPublished
                    if (useChatV2) {
                        val app = requireContext().applicationContext as XtraApp
                        val chatBackground = MaterialColors.getColor(
                            requireView(),
                            com.google.android.material.R.attr.colorSurface,
                        )
                        chatV2Renderer = ChatV2RendererController(
                            recyclerView = recyclerView,
                            manager = app.xtraModule.chatSessionManager,
                            assets = app.xtraModule.chatAssetRepository,
                            expectedChannelId = channelId!!,
                            expectedChannelLogin = channelLogin!!,
                            initialState = chatV2ViewportState,
                            emoteHeightPx = chatStyle.emoteHeightPx,
                            badgeHeightPx = chatStyle.badgeHeightPx,
                            messageTextSizeSp = chatStyle.textSizeSp,
                            animateGifs = chatStyle.animateGifs,
                            gifDisplayMode = chatStyle.gifDisplayMode,
                            showBadges = chatStyle.showBadges,
                            enableOverlayEmotes = chatStyle.enableOverlayEmotes,
                            firstMessageVisibility = chatStyle.firstMessageVisibility,
                            boldNames = chatStyle.boldNames,
                            nameDisplay = requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0") ?: "0",
                            randomUsernameColors = requireContext().prefs().getBoolean(C.CHAT_RANDOM_COLOR, true),
                            showSystemMessageEmotes = requireContext().prefs().getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                            showNamePaints = requireContext().prefs().getBoolean(C.CHAT_SHOW_PAINTS, true),
                            showThirdPartyBadges = requireContext().prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                            showPersonalEmotes = requireContext().prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                            translation = { message -> v2Translations[message.id.value] },
                            onTranslateMessage = ::requestV2Translation,
                            translateAllMessages = viewModel.translateAllMessages.value == true,
                            timestampFormat = chatStyle.timestampFormat,
                            showTimestamps = chatStyle.showTimestamps,
                            readableUsernameColors = requireContext().prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                            backgroundColor = chatBackground,
                            presentationLabels = ChatPresentationLabels(
                                firstChatter = getString(R.string.chat_first),
                                redeemed = { reward -> getString(R.string.redeemed, reward) },
                                userRedeemed = { reward -> getString(R.string.user_redeemed, "", reward).trimStart() },
                                highlightTitle = getString(R.string.chat_highlight_title),
                                highlightRedeemed = { title -> getString(R.string.chat_highlight_redeemed, title) },
                                watchStreakReached = getString(R.string.chat_watch_streak_reached),
                                watchStreakStatus = { user, count -> getString(R.string.chat_watch_streak_status, user, count) },
                                raid = getString(R.string.chat_event_raid),
                                notice = getString(R.string.chat_event_notice),
                                anonymous = getString(R.string.chat_event_anonymous),
                                viewer = getString(R.string.chat_event_viewer),
                                reward = getString(R.string.chat_event_channel_points_reward),
                                subscriptionPrime = getString(R.string.chat_subscription_prime),
                                subscriptionPaid = { tier -> getString(R.string.chat_subscription_paid, tier) },
                                subscriptionUpgrade = { tier -> getString(R.string.chat_subscription_upgrade, tier) },
                                subscriptionGift = { tier, recipient -> getString(R.string.chat_subscription_gift, tier, recipient) },
                                subscriptionCommunityGift = { count, tier -> resources.getQuantityString(R.plurals.chat_subscription_community_gift, count, count, tier) },
                                subscriptionMonths = { months -> resources.getQuantityString(R.plurals.chat_subscription_months, months, months) },
                                subscriptionStreak = { months -> resources.getQuantityString(R.plurals.chat_subscription_streak, months, months) },
                                subscriptionAccessibilityMonths = { months -> resources.getQuantityString(R.plurals.chat_subscription_accessibility_months, months, months) },
                                reply = { user, message -> getString(R.string.replying_to_message, user, message) },
                                moderationSuffix = { moderation ->
                                    when (moderation.reason) {
                                        ChatUserClearReason.TIMEOUT -> getString(
                                            R.string.chat_moderation_timeout,
                                            TwitchApiHelper.getDurationFromSeconds(
                                                requireContext(),
                                                moderation.timeoutSeconds?.toString(),
                                            ).orEmpty(),
                                        )
                                        ChatUserClearReason.BAN -> getString(R.string.chat_moderation_ban)
                                        ChatUserClearReason.MESSAGES_CLEARED -> getString(R.string.chat_moderation_messages_cleared)
                                        ChatUserClearReason.MESSAGE_DELETED -> "(${getString(R.string.chat_message_deleted)})"
                                    }
                                },
                            ),
                            onStateChanged = { state ->
                                btnDown.isVisible = state.followMode == com.github.andreyasadchy.xtra.ui.chat.v2.ui.FollowMode.USER_SCROLLED_UP
                            },
                            profilePopoutGesture = profilePopoutGesture,
                            rewardCatalog = viewModel.channelPoints.map { points ->
                                val rewards = points?.rewards.orEmpty()
                                com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatRewardCatalog(
                                    byId = rewards.associate { reward ->
                                        reward.id to ChatReward(
                                            title = reward.title,
                                            cost = reward.cost,
                                            imageUrl = reward.imageUrl,
                                        )
                                    },
                                    automaticByType = rewards
                                        .filter { it.redemptionType == com.github.andreyasadchy.xtra.model.ui.ChannelPointRewardRedemption.HIGHLIGHTED_MESSAGE }
                                        .associate { reward ->
                                            com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE to ChatReward(
                                                title = reward.title,
                                                cost = reward.cost,
                                                imageUrl = reward.imageUrl,
                                            )
                                        },
                                )
                            },
                            rewardCatalogSettled = viewModel.channelPointCatalogSettled,
                            decorationCatalog = merge(
                                viewModel.thirdPartyEmotesUpdated,
                                viewModel.updateUserMessages.map { Unit },
                            ).onStart { emit(Unit) }.map { viewModel.v2DecorationSnapshot() },
                            onMessageLongClick = ::onV2MessageLongClick,
                            onEmoteClick = ::onV2EmoteClick,
                            onGifClick = ::onV2GifClick,
                            onPublicationChanged = ::onV2PublicationChanged,
                        ).also {
                            it.setVisible(chatV2RendererVisible)
                            it.attach(viewLifecycleOwner)
                        }
                    }
                    recyclerView.let {
                        it.itemAnimator = null
                        it.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                                if (useChatV2) {
                                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                        userGestureActive = true
                                        chatV2Renderer?.onUserScroll()
                                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE && userGestureActive) {
                                        chatV2Renderer?.onUserScroll()
                                        userGestureActive = false
                                    }
                                    return
                                }
                                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                    // Programmatic scrolls caused by publication enter SETTLING,
                                    // not DRAGGING. Only a real user drag invalidates a staged
                                    // viewport restore.
                                    userScrollGeneration++
                                }
                                isChatTouched = newState != RecyclerView.SCROLL_STATE_IDLE
                                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                                    recyclerView.removeCallbacks(chatAdapterUpdateRunnable)
                                    chatAdapterUpdatePosted = false
                                }
                                adapter?.setAnimationsPaused(newState != RecyclerView.SCROLL_STATE_IDLE)
                                val offset = recyclerView.computeVerticalScrollOffset()
                                if (offset < 0) {
                                    btnDown.isVisible = false
                                } else {
                                    val extent = recyclerView.computeVerticalScrollExtent()
                                    val range = recyclerView.computeVerticalScrollRange()
                                    val percentage = (100f * offset / (range - extent).toFloat())
                                    btnDown.isVisible = percentage < 100f
                                }
                                if (showChatStatus && chatStatus.isGone) {
                                    chatStatus.visibility = View.VISIBLE
                                    chatStatus.postDelayed({ chatStatus.visibility = View.GONE }, 5000)
                                }
                                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                    if (chatSnapshotSyncPending && chatAdapterReady) {
                                        chatSnapshotSyncPending = false
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            synchronizeChatAdapterToSnapshot()
                                        }
                                    } else if (pendingChatMutations.isNotEmpty()) {
                                        scheduleChatAdapterUpdate()
                                    }
                                }
                            }
                        })
                    }
                    val chatAdapter = adapter
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (!useChatV2 && _binding?.recyclerView === recyclerView && chatAdapter === adapter) {
                            recyclerView.adapter = chatAdapter
                            chatAdapterReady = true
                            pendingChatPublicationFollowBottom = !recyclerView.canScrollVertically(1)
                            pendingChatPublicationAnchor = null
                            pendingChatPublicationScrollGeneration = userScrollGeneration
                            chatAdapter?.appendMessages(
                                initialMessages,
                                0,
                            )
                            if (chatSnapshotSyncPending) {
                                chatSnapshotSyncPending = false
                                viewLifecycleOwner.lifecycleScope.launch {
                                    synchronizeChatAdapterToSnapshot()
                                }
                            } else if (pendingChatMutations.isNotEmpty() && !isChatTouched) {
                                scheduleChatAdapterUpdate()
                            }
                        }
                    }
                    btnDown.setOnClickListener {
                        view.post {
                            if (useChatV2) {
                                chatV2Renderer?.jumpToNewest()
                            } else {
                                val lastIndex = adapter?.itemCount?.minus(1) ?: RecyclerView.NO_POSITION
                                recyclerView.scrollToPosition(lastIndex)
                            }
                            it.visibility = View.GONE
                        }
                    }
                    if (isLive) {
                        val identityGqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true)
                        val chatIdentityEnabled = !channelId.isNullOrBlank() &&
                                !channelLogin.isNullOrBlank() &&
                                !identityGqlHeaders[C.HEADER_TOKEN].isNullOrBlank()
                        chatIdentity.isVisible = chatIdentityEnabled
                        chatIdentity.isEnabled = chatIdentityEnabled
                        if (chatIdentityEnabled) {
                            viewModel.ensureChatIdentityLoaded(channelId, channelLogin)
                            viewLifecycleOwner.lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    launch {
                                        viewModel.chatIdentityState.collectLatest(::updateChatIdentityTrigger)
                                    }
                                    launch {
                                        viewModel.chatIdentityError.collectLatest { message ->
                                            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            chatIdentity.setOnClickListener { toggleChatIdentityPopup() }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.channelPoints.collectLatest { points ->
                                    if (points != null) {
                                        val balance = TwitchApiHelper.formatCount(points.balance, compact = true)
                                        val accessibleBalance = TwitchApiHelper.formatCount(points.balance, compact = false)
                                        channelPointsText.text = balance
                                        channelPointsAccessibilityLabel = getString(
                                            R.string.channel_points_balance,
                                            accessibleBalance,
                                        )
                                        updateChannelPointsIcon(points.iconUrl)
                                        updateChannelPointsActivity(
                                            ChannelPointsActivityState(
                                                viewModel.activePoll.value,
                                                viewModel.pollSecondsLeft.value,
                                                viewModel.ongoingPrediction.value,
                                                viewModel.predictionSecondsLeft.value,
                                            ),
                                        )
                                        channelPoints.visibility = View.VISIBLE
                                        updateComposerDensity()
                                    } else {
                                        channelPointsAccessibilityLabel = null
                                        updateChannelPointsIcon(null)
                                        channelPoints.visibility = View.GONE
                                        updateComposerDensity()
                                    }
                                }
                            }
                        }
                        autoCompleteAdapter = AutoCompleteAdapter(
                            requireContext(),
                            R.layout.auto_complete_emotes_list_item,
                            R.id.name,
                            viewModel.autoCompleteList,
                        ).apply {
                            setNotifyOnChange(false)
                            editText.setAdapter(this)

                            var previousSize = 0
                            editText.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus && count != previousSize) {
                                    previousSize = count
                                    notifyDataSetChanged()
                                }
                                setNotifyOnChange(hasFocus)
                            }
                        }
                        val app = requireContext().applicationContext as XtraApp
                        recommendationAdapter = EmoteRecommendationAdapter(
                            assets = app.xtraModule.chatAssetRepository,
                            clickListener = ::insertRecommendedEmote,
                        )
                        recommendationStrip.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false,
                        )
                        recommendationStrip.adapter = recommendationAdapter
                        if (useChatV2) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.pickerCatalogFor(channelId, channelLogin, useV2 = true)
                                        .collectLatest { catalog ->
                                            if (catalog != null) {
                                                viewModel.refreshV2AutoCompleteList(catalog)
                                                autoCompleteAdapter?.notifyDataSetChanged()
                                            }
                                    }
                                }
                            }
                            viewLifecycleOwner.lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    combine(
                                        recommendationInput,
                                        viewModel.emoteRecommendationCatalogFor(channelId, channelLogin, useV2 = true),
                                    ) { input, catalog ->
                                        withContext(Dispatchers.Default) {
                                            val token = ChatInputToken.aroundCursor(input.text, input.cursor)
                                            if (token == null) {
                                                RecommendationResult("", emptyList())
                                            } else if (catalog == null) {
                                                RecommendationResult(token.text, emptyList())
                                            } else {
                                                RecommendationResult(
                                                    query = token.text,
                                                    recommendations = recommendationEngine.recommend(
                                                        query = token.text,
                                                        channelId = channelId.orEmpty(),
                                                        catalog = catalog.catalog,
                                                        usage = catalog.usage,
                                                        viewerId = catalog.viewerId,
                                                    ),
                                                )
                                            }
                                        }
                                    }.collectLatest { result ->
                                        val queryChanged = currentRecommendationQuery != result.query
                                        currentRecommendationQuery = result.query
                                        currentRecommendations = result.recommendations
                                        if (queryChanged) {
                                            recommendationStrip.stopScroll()
                                            recommendationStrip.scrollToPosition(0)
                                        }
                                        recommendationAdapter?.submitList(result.recommendations)
                                        updateRecommendationVisibility()
                                    }
                                }
                            }
                        }
                        editText.addTextChangedListener(onTextChanged = { text, _, _, _ ->
                            updateRecommendationInput()
                            updateComposerButtons()
                        })
                        editText.onSelectionChangedListener = { _, _ -> updateRecommendationInput() }
                        updateRecommendationInput()
                        editText.setTokenizer(SpaceTokenizer())
                        editText.setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                                val sent = sendMessage()
                                sent || viewModel.isSlowModeBlocked()
                            } else {
                                false
                            }
                        }
                        editText.setOnEditorActionListener { _, actionId, event ->
                            if (actionId == EditorInfo.IME_ACTION_SEND ||
                                event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                            ) {
                                val sent = sendMessage()
                                sent || viewModel.isSlowModeBlocked()
                            } else {
                                false
                            }
                        }
                        clear.setOnClickListener {
                            val text = editText.text.toString().trimEnd()
                            editText.setText(text.substring(0, max(text.lastIndexOf(' '), 0)))
                            editText.setSelection(editText.length())
                        }
                        clear.setOnLongClickListener {
                            editText.text.clear()
                            true
                        }
                        replyView.visibility = View.GONE
                        send.setOnClickListener { sendMessage() }
                        messageView.isVisible = shouldShowChatComposer(
                            chatAvailable = isLive,
                            isSlidingPlayerLayout = isInSlidingPlayerLayout(binding.root),
                            chatBarVisible = requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true),
                        )
                        updateRecommendationVisibility()
                        editText.isEnabled = enableMessaging && !composerSubmissionInProgress
                        updateSlowModeIndicator(viewModel.slowModeState.value)
                        messageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            updateComposerDensity()
                        }
                        updateComposerDensity()
                        viewPager.adapter = object : FragmentStateAdapter(this@ChatFragment) {
                            override fun getItemCount(): Int = EmotePickerSection.entries.size

                            override fun createFragment(position: Int): Fragment {
                                return EmotesFragment.newInstance(EmotePickerSection.fromPosition(position))
                            }
                        }
                        viewPager.offscreenPageLimit = 2
                        viewPager.reduceDragSensitivity()
                        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                            tab.text = when (EmotePickerSection.fromPosition(position)) {
                                EmotePickerSection.FAVORITES -> getString(R.string.favorite_emotes)
                                EmotePickerSection.RECENTS -> getString(R.string.recent_emotes)
                                EmotePickerSection.TWITCH -> "Twitch"
                                EmotePickerSection.THIRD_PARTY -> "7TV/BTTV/FFZ"
                            }
                        }.attach()
                        emotes.setOnClickListener {
                            //TODO add animation
                            if (emoteMenu.isGone) {
                                val defaultSection = when {
                                    viewModel.hasAvailableFavoriteEmotes.value -> EmotePickerSection.FAVORITES
                                    viewModel.hasRecentEmotes.value -> EmotePickerSection.RECENTS
                                    else -> EmotePickerSection.TWITCH
                                }
                                viewPager.setCurrentItem(defaultSection.position, false)
                                toggleEmoteMenu(true)
                            } else {
                                toggleEmoteMenu(false)
                            }
                        }
                        channelPoints.setOnClickListener {
                            showChannelPointsDialog()
                        }
                        updateSlowModeIndicator(viewModel.slowModeState.value)
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.roomState.collectLatest { roomState ->
                                if (roomState != null) {
                                    when (roomState.emote) {
                                        "0" -> textEmote.visibility = View.GONE
                                        "1" -> textEmote.visibility = View.VISIBLE
                                    }
                                    if (roomState.followers != null) {
                                        when (roomState.followers) {
                                            "-1" -> textFollowers.visibility = View.GONE
                                            "0" -> {
                                                textFollowers.text = getString(R.string.room_followers)
                                                textFollowers.visibility = View.VISIBLE
                                            }
                                            else -> {
                                                textFollowers.text = getString(
                                                    R.string.room_followers_min,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), (roomState.followers.toInt() * 60).toString())
                                                )
                                                textFollowers.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                    when (roomState.unique) {
                                        "0" -> textUnique.visibility = View.GONE
                                        "1" -> textUnique.visibility = View.VISIBLE
                                    }
                                    if (roomState.slow != null) {
                                        when (roomState.slow) {
                                            "0" -> textSlow.visibility = View.GONE
                                            else -> {
                                                textSlow.text = getString(
                                                    R.string.room_slow,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), roomState.slow)
                                                )
                                                textSlow.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                    when (roomState.subs) {
                                        "0" -> textSubs.visibility = View.GONE
                                        "1" -> textSubs.visibility = View.VISIBLE
                                    }
                                    if (textEmote.isGone && textFollowers.isGone && textUnique.isGone && textSlow.isGone && textSubs.isGone) {
                                        showChatStatus = false
                                        chatStatus.visibility = View.GONE
                                    } else {
                                        showChatStatus = true
                                        chatStatus.visibility = View.VISIBLE
                                        chatStatus.postDelayed({ chatStatus.visibility = View.GONE }, 5000)
                                    }
                                    viewModel.roomState.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.reloadMessages.collectLatest {
                                if (it) {
                                    adapter?.let { adapter ->
                                        adapter.notifyCatalogChanged()
                                    }
                                    messageDialog?.adapter?.let { adapter ->
                                        val size = synchronized(adapter.messages) {
                                            adapter.messages.size
                                        }
                                        adapter.notifyItemRangeChanged(0, size)
                                    }
                                    replyDialog?.adapter?.let { adapter ->
                                        val size = synchronized(adapter.messages) {
                                            adapter.messages.size
                                        }
                                        adapter.notifyItemRangeChanged(0, size)
                                    }
                                    viewModel.reloadMessages.value = false
                                    updatePinnedMessage(viewModel.pinnedChatMessage.value)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hideRaid.collectLatest {
                                if (it) {
                                    raidLayout.visibility = View.GONE
                                    viewModel.raidClosed = true
                                    viewModel.hideRaid.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raid.collectLatest { raid ->
                                if (raid != null) {
                                    if (!viewModel.raidClosed) {
                                        if (raid.openStream) {
                                            if (requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, false) &&
                                                (parentFragment is Media3PlayerFragment || parentFragment is PlayerFragment)
                                            ) {
                                                (requireActivity() as? MainActivity)?.startStream(
                                                    Stream(
                                                        channelId = raid.targetId,
                                                        channelLogin = raid.targetLogin,
                                                        channelName = raid.targetName,
                                                        channelImageURL = raid.targetImageURL,
                                                    )
                                                )
                                            }
                                            raidLayout.visibility = View.GONE
                                            viewModel.raidClosed = true
                                        } else {
                                            raidLayout.visibility = View.VISIBLE
                                            raidLayout.setOnClickListener { viewModel.raidClicked.value = raid }
                                            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext()).apply {
                    data(raid.targetImage)
                    diskCachePolicy(CachePolicy.ENABLED)
                                                    if (requireContext().prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                                        transformations(CircleCropTransformation())
                                                    }
                                                    crossfade(true)
                                                    target(raidImage)
                                                }.build()
                                            )
                                            raidClose.setOnClickListener {
                                                raidLayout.visibility = View.GONE
                                                viewModel.raidClosed = true
                                            }
                                            raidText.text = getString(
                                                R.string.raid_text,
                                                if (raid.targetLogin != null && !raid.targetLogin.equals(raid.targetName, true)) {
                                                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                        "0" -> "${raid.targetName}(${raid.targetLogin})"
                                                        "1" -> raid.targetName
                                                        else -> raid.targetLogin
                                                    }
                                                } else {
                                                    raid.targetName
                                                },
                                                raid.viewerCount
                                            )
                                        }
                                    }
                                    viewModel.raid.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raidClicked.collectLatest {
                                if (it != null) {
                                    (requireActivity() as? MainActivity)?.startStream(
                                        Stream(
                                            channelId = it.targetId,
                                            channelLogin = it.targetLogin,
                                            channelName = it.targetName,
                                            channelImageURL = it.targetImageURL,
                                        )
                                    )
                                    viewModel.raidClicked.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.playbackMessage.collectLatest {
                                if (it != null) {
                                    if (it.live != null) {
                                        (parentFragment as? Media3PlayerFragment)?.updateLiveStatus(it.live, it.serverTime, channelLogin) ?: (parentFragment as? PlayerFragment)?.updateLiveStatus(it.live, it.serverTime, channelLogin)
                                    }
                                    (parentFragment as? Media3PlayerFragment)?.updateViewerCount(it.viewers) ?: (parentFragment as? PlayerFragment)?.updateViewerCount(it.viewers)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.streamInfo.collectLatest {
                                if (it != null) {
                                    when (val parent = parentFragment) {
                                        is Media3PlayerFragment -> parent.updateStreamInfo(it.title, it.gameId, null, it.gameName)
                                        is PlayerFragment -> parent.updateStreamInfo(it.title, it.gameId, null, it.gameName)
                                        is MultiviewFragment -> parent.updateViewingMetadata(
                                            channelId = channelId,
                                            channelLogin = channelLogin,
                                            title = it.title,
                                            categoryId = it.gameId,
                                            categoryName = it.gameName,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!useChatV2) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                synchronizeChatAdapterToSnapshot()
                                viewModel.chatMutations.collect { mutation ->
                                    if (chatSnapshotSyncPending) {
                                        dispatchChatMutationSideEffects(mutation)
                                        return@collect
                                    }

                                    val expectedRevision = expectedChatMutationRevision()
                                    when (chatMutationAction(expectedRevision, mutation.revision)) {
                                        ChatMutationAction.IGNORE -> return@collect
                                        ChatMutationAction.SYNCHRONIZE_SNAPSHOT -> {
                                            chatMutationGapCount++
                                            Log.d(
                                                "ChatPerf",
                                                "mutation gap count=$chatMutationGapCount " +
                                                    "displayed=$chatMutationRevision " +
                                                    "expected=$expectedRevision " +
                                                    "incoming=${mutation.revision}",
                                            )
                                            pendingChatMutations.clear()
                                            if (chatAdapterReady && !isChatTouched) {
                                                synchronizeChatAdapterToSnapshot()
                                            } else {
                                                chatSnapshotSyncPending = true
                                            }
                                            return@collect
                                        }
                                        ChatMutationAction.APPLY_INCREMENTAL -> Unit
                                    }
                                    dispatchChatMutationSideEffects(mutation)

                                    if (isChatTouched) {
                                        pendingChatMutations.clear()
                                        chatSnapshotSyncPending = true
                                        return@collect
                                    }

                                    pendingChatMutations.addLast(mutation)
                                    scheduleChatAdapterUpdate()
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.updateUserMessages.collectLatest { userId ->
                                if (!useChatV2) {
                                    adapter?.let { adapter ->
                                        adapter.notifyUserMessages(userId)
                                    }
                                }
                                messageDialog?.updateUserMessages(userId)
                                replyDialog?.updateUserMessages(userId)
                            }
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && channelId != null && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.translateAllMessages.collectLatest {
                                    if (it != null) {
                                        adapter?.translateAllMessages = it
                                        chatV2Renderer?.setTranslateAllMessages(it)
                                    }
                                }
                            }
                        }
                        viewModel.checkTranslateAllMessages(channelId)
                    }
                    if (chatUrl != null) {
                        viewModel.startReplay(
                            channelId = channelId,
                            channelLogin = channelLogin,
                            chatUrl = chatUrl,
                            createdAt = args.getString(KEY_CREATED_AT),
                            getCurrentPosition = if (parentFragment is Media3PlayerFragment) (parentFragment as Media3PlayerFragment)::getCurrentPosition else (parentFragment as PlayerFragment)::getCurrentPosition,
                            getCurrentSpeed = if (parentFragment is Media3PlayerFragment) (parentFragment as Media3PlayerFragment)::getCurrentSpeed else (parentFragment as PlayerFragment)::getCurrentSpeed
                        )
                    }
                } else {
                    chatReplayUnavailable.visibility = View.VISIBLE
                }
            }
            if (!isInsideInsetAwareContainer(view)) {
                ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                    if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            bottomMargin = insets.bottom
                        }
                    }
                    WindowInsetsCompat.CONSUMED
                }
            }
        }
        val pendingSubmission = pendingChatSubmission
        val draft = if (overlayStateStore.active == null && pendingOverlaySubmission == null) {
            pendingSubmission?.text ?: savedInstanceState?.getString(KEY_COMPOSER_DRAFT)
        } else {
            null
        }
        draft?.takeIf { it.isNotEmpty() }?.let {
            binding.editText.setText(it)
            binding.editText.setSelection(binding.editText.length())
            updateComposerButtons()
        }
        if (pendingSubmission != null) {
            binding.editText.isEnabled = false
            pendingChatSendResult?.let { result ->
                pendingChatSendResult = null
                handleChatSendResult(result, pendingSubmission.text, pendingSubmission.replyId)
            }
        }
        val retainedOverlay = overlayStateStore.active ?: pendingOverlaySubmission?.let { pending ->
            ComposerOverlaySnapshot(
                overlay = pending.state,
                input = pending.text,
                restoreState = pending.restoreState,
                submissionPending = true,
            )
        }
        retainedOverlay?.let { overlay ->
            composerOverlayState = overlay.overlay
            if (overlayStateStore.active == null) {
                overlayStateStore.set(overlay)
            }
            if (overlay.submissionPending) {
                composerSubmissionInProgress = true
                pendingOverlaySubmission = PendingOverlaySubmission(
                    state = overlay.overlay,
                    text = overlay.input,
                    restoreState = overlay.restoreState,
                )
            }
            renderComposerOverlay(overlay.overlay)
            binding.editText.setText(overlay.input)
            binding.editText.setSelection(binding.editText.length())
            binding.editText.isEnabled = !overlay.submissionPending
            updateComposerButtons()
        }
        replyComposerState?.let(::configureReplyComposer)
    }

    private fun isInsideInsetAwareContainer(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is View && (parent.id == R.id.slidingLayout || parent.id == R.id.multiviewRoot)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun updatePinnedMessage(message: PinnedChatMessage?) {
        val currentBinding = _binding ?: return
        val pinnedBinding = pinnedMessageBinding ?: return
        val overlay = pinnedBinding.pinnedMessageOverlay
        if (message == null || message.id == seenPinnedMessageId) {
            pinnedMessageTimerJob?.cancel()
            disposePinnedBadgeRequests()
            pinnedBinding.pinnedMessageProgress.isGone = true
            overlay.isGone = true
            return
        }
        if (message.id != displayedPinnedMessageId) {
            displayedPinnedMessageId = message.id
            // The expanded pinned-message card is designed for touch-sized
            // chat surfaces. Keep it compact on TV so it does not cover the
            // chat feed or dominate the viewing area; the existing minimize
            // action still allows expansion when deliberately selected.
            pinnedMessageMinimized = requireContext().isTelevision()
        }
        pinnedBinding.pinnedMessageBy.text = message.pinnedBy
        pinnedBinding.pinnedMessageBy.setOnClickListener {
            showPinnedMessageUserPopout(message, pinner = true)
        }
        pinnedBinding.pinnedMessageBy.isClickable =
            !message.pinnedById.isNullOrBlank() || !message.pinnedByLogin.isNullOrBlank()

        val senderName = message.sender ?: message.pinnedBy
        pinnedBinding.pinnedMessageSender.text = senderName
        val senderColor = message.senderColor?.let { color ->
            runCatching { Color.parseColor(color) }.getOrNull()
        }
        pinnedBinding.pinnedMessageSender.setTextColor(
            senderColor ?: MaterialColors.getColor(
                pinnedBinding.pinnedMessageSender,
                androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        pinnedBinding.pinnedMessageSender.setOnClickListener {
            showPinnedMessageUserPopout(message, pinner = false)
        }
        pinnedBinding.pinnedMessageSender.isClickable =
            !message.senderId.isNullOrBlank() || !message.senderLogin.isNullOrBlank()
        val sentAt = message.sentAt?.let { TwitchApiHelper.getTimestamp(it, "2") }
        pinnedBinding.pinnedMessageSentAt.text = sentAt?.let { getString(R.string.pinned_message_sent_at, it) }.orEmpty()
        pinnedBinding.pinnedMessageSentAt.isVisible = sentAt != null
        pinnedBinding.pinnedMessageText.text = message.text
        pinnedBinding.pinnedMessageText.isVisible = !pinnedMessageMinimized
        pinnedBinding.pinnedMessageCollapsedPreview.text = message.text
        pinnedBinding.pinnedMessageCollapsedPreview.isVisible = pinnedMessageMinimized
        pinnedBinding.pinnedMessageFooter.isVisible = !pinnedMessageMinimized
        disposePinnedBadgeRequests()
        renderPinnedMessageBadges(
            pinnedBinding.pinnedMessagePinnedByBadges,
            listOfNotNull(highestPinnedChatRoleBadge(message.pinnedByBadges)),
        )
        renderPinnedMessageBadges(pinnedBinding.pinnedMessageSenderBadges, message.senderBadges)
        renderPinnedMessageListeningBadge()
        pinnedBinding.pinnedMessageMinimize.setImageResource(
            if (pinnedMessageMinimized) R.drawable.baseline_expand_more_black_24 else R.drawable.ic_expand_less,
        )
        pinnedBinding.pinnedMessageMinimize.contentDescription = getString(
            if (pinnedMessageMinimized) R.string.pinned_message_expand else R.string.pinned_message_minimize,
        )
        overlay.isVisible = true
        schedulePinnedMessageTimer(message)
    }

    private fun disposePinnedBadgeRequests() {
        pinnedBadgeRequests.forEach(Disposable::dispose)
        pinnedBadgeRequests.clear()
    }

    private fun renderPinnedMessageBadges(container: LinearLayout, badges: List<Badge>) {
        container.removeAllViews()
        badges.forEach { badge ->
            val catalogBadge = synchronized(viewModel.globalBadges) {
                viewModel.globalBadges.firstOrNull { it.setId == badge.setId && it.version == badge.version }
            } ?: synchronized(viewModel.channelBadges) {
                viewModel.channelBadges.firstOrNull { it.setId == badge.setId && it.version == badge.version }
            }
            val url = badge.imageUrl?.takeIf { it.isNotBlank() }
                ?: catalogBadge?.url2x
                ?: catalogBadge?.url1x
            if (url.isNullOrBlank()) return@forEach
            val density = resources.displayMetrics.density
            val image = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (18 * density).toInt(),
                    (18 * density).toInt(),
                ).apply {
                    marginEnd = (3 * density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = badge.title ?: catalogBadge?.title ?: badge.setId
            }
            pinnedBadgeRequests += requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false)
                    .target(image)
                    .build(),
            )
            container.addView(image)
        }
        container.isVisible = container.childCount > 0
    }

    private fun renderPinnedMessageListeningBadge() {
        val seenButton = pinnedMessageBinding?.pinnedMessageSeen ?: return
        val badge = synchronized(viewModel.globalBadges) {
            viewModel.globalBadges.firstOrNull {
                it.setId.equals("no_video", ignoreCase = true) ||
                    it.title.equals("Listening only", ignoreCase = true)
            }
        }
        val url = badge?.url4x
            ?: badge?.url3x
            ?: badge?.url2x
            ?: badge?.url1x
            ?: TWITCH_LISTENING_ONLY_BADGE_URL

        seenButton.isVisible = true
        pinnedBadgeRequests += requireContext().imageLoader.enqueue(
            ImageRequest.Builder(requireContext())
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .target(seenButton)
                .build(),
        )
    }

    private fun showPinnedMessageUserPopout(
        message: PinnedChatMessage,
        pinner: Boolean,
    ) {
        val userId = if (pinner) message.pinnedById else message.senderId
        val userLogin = if (pinner) message.pinnedByLogin else message.senderLogin
        val userName = if (pinner) message.pinnedBy else message.sender ?: message.pinnedBy
        if (userId.isNullOrBlank() && userLogin.isNullOrBlank()) return

        selectedV2Message = null
        selectedPinnedMessage = ChatMessage(
            type = ChatMessage.USER_MESSAGE,
            userId = userId,
            userLogin = userLogin,
            userName = userName,
            message = message.text,
            badges = if (pinner) message.pinnedByBadges else message.senderBadges,
            timestamp = message.sentAt,
        )
        hideChatInputForDialog()
        MessageClickedDialog.newInstance(
            messagingEnabled = messagingEnabled,
            channelId = requireArguments().getString(KEY_CHANNEL_ID),
            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
        ).show(childFragmentManager, "messageDialog")
    }

    private fun showHappeningNowGiftProfile(gift: HappeningNowGift) {
        if (gift.isAnonymous) return

        val userId = gift.gifterUserId
        val userLogin = gift.gifterLogin
        if (userId.isNullOrBlank() && userLogin.isNullOrBlank()) return

        if (useChatV2) {
            selectedPinnedMessage = null
            selectedV2Message = V2ChatMessage(
                id = ChatMessageId("happening-now-gift:${gift.stableId}"),
                channelId = requireArguments().getString(KEY_CHANNEL_ID).orEmpty(),
                timestampMs = gift.occurredAt,
                user = ChatUser(
                    id = userId,
                    login = userLogin,
                    displayName = gift.gifterDisplayName ?: userLogin,
                    color = null,
                ),
                badges = emptyList(),
                segments = emptyList(),
                kind = ChatMessageKind.SYSTEM,
            )
        } else {
            selectedV2Message = null
            selectedPinnedMessage = ChatMessage(
                type = ChatMessage.USER_MESSAGE,
                userId = userId,
                userLogin = userLogin,
                userName = gift.gifterDisplayName ?: userLogin,
                message = null,
                timestamp = gift.occurredAt,
            )
        }
        hideChatInputForDialog()
        MessageClickedDialog.newInstance(
            messagingEnabled = messagingEnabled,
            channelId = requireArguments().getString(KEY_CHANNEL_ID),
            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
        ).show(childFragmentManager, "messageDialog")
    }

    private fun schedulePinnedMessageTimer(message: PinnedChatMessage) {
        pinnedMessageTimerJob?.cancel()
        val startsAt = message.startsAt
        val endsAt = message.endsAt
        val progress = pinnedMessageBinding?.pinnedMessageProgress ?: return
        if (startsAt == null || endsAt == null || endsAt <= startsAt) {
            progress.progress = 0
            progress.isGone = true
            return
        }
        val duration = endsAt - startsAt
        pinnedMessageTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && displayedPinnedMessageId == message.id) {
                val remaining = endsAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    pinnedMessageBinding?.pinnedMessageOverlay?.isGone = true
                    pinnedMessageBinding?.pinnedMessageProgress?.isGone = true
                    break
                }
                val currentBinding = pinnedMessageBinding ?: break
                currentBinding.pinnedMessageProgress.progress =
                    ((remaining.toDouble() / duration) * 1000.0)
                        .coerceIn(0.0, 1000.0)
                        .toInt()
                currentBinding.pinnedMessageProgress.isVisible = true
                delay(1_000L)
            }
        }
    }

    private fun updatePinnedMessageOverlayWidth() {
        val currentBinding = _binding ?: return
        val pinnedBinding = pinnedMessageBinding ?: return
        val container = currentBinding.chatTopOverlays
        val availableWidth = container.width - container.paddingLeft - container.paddingRight
        if (availableWidth <= 0) return
        val width = availableWidth
        val layoutParams = pinnedBinding.pinnedMessageOverlay.layoutParams ?: return
        if (layoutParams.width != width) {
            pinnedBinding.pinnedMessageOverlay.updateLayoutParams<ViewGroup.LayoutParams> {
                this.width = width
            }
        }
    }

    private fun currentLiveStreamId(): String? =
        resolveCurrentLiveStreamId(viewModel.streamId, requireArguments().getString(KEY_STREAM_ID))

    /** Requests a process/playback-owned session; this Fragment never owns its lifetime. */
    private fun startChatV2Session(channelId: String, channelLogin: String) {
        val app = requireContext().applicationContext as XtraApp
        app.applicationScope.launch {
            runCatching {
                app.xtraModule.chatSessionManager.start(
                    LiveChatSessionSpec(
                        channelId = channelId,
                        channelLogin = channelLogin,
                        streamId = currentLiveStreamId(),
                        legacySupplementalSockets = true,
                    ),
                )
            }.onFailure { error ->
                Log.e("ChatV2", "Unable to start live v2 chat", error)
            }
        }
    }

    override fun initialize() {
        if (requireContext().prefs().isChatEnabled()) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            when (val mode = viewModel.activeChatMode) {
                ChatViewModel.ActiveChatMode.Live -> if (args.getBoolean(KEY_IS_LIVE)) {
                    if (useChatV2 && channelId != null && channelLogin != null) {
                        startChatV2Session(channelId, channelLogin)
                    }
                    viewModel.startLive(
                        requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        if (useChatV2) null else "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel",
                        channelId,
                        channelLogin,
                        args.getString(KEY_CHANNEL_NAME),
                        currentLiveStreamId(),
                        useChatV2 = useChatV2,
                    )
                }
                is ChatViewModel.ActiveChatMode.VideoReplay -> {
                    viewModel.resumeTemporaryReplay(
                        videoId = mode.videoId,
                        createdAt = mode.createdAt,
                        getCurrentPosition = currentPositionProvider(),
                        getCurrentSpeed = currentSpeedProvider(),
                        channelId = args.getString(KEY_CHANNEL_ID),
                        channelLogin = args.getString(KEY_CHANNEL_LOGIN),
                    )
                }
            }
            if (viewModel.activeChatMode is ChatViewModel.ActiveChatMode.Live && !args.getBoolean(KEY_IS_LIVE)) {
                val videoId = args.getString(KEY_VIDEO_ID)
                val startTime = args.getInt(KEY_START_TIME)
                if (videoId != null && startTime != -1) {
                    viewModel.startReplay(
                        channelId = channelId,
                        channelLogin = channelLogin,
                        videoId = videoId,
                        createdAt = args.getString(KEY_CREATED_AT),
                        startTime = startTime,
                        getCurrentPosition = if (parentFragment is Media3PlayerFragment) (parentFragment as Media3PlayerFragment)::getCurrentPosition else (parentFragment as PlayerFragment)::getCurrentPosition,
                        getCurrentSpeed = if (parentFragment is Media3PlayerFragment) (parentFragment as Media3PlayerFragment)::getCurrentSpeed else (parentFragment as PlayerFragment)::getCurrentSpeed
                    )
                }
            }
            // BaseNetworkFragment may invoke initialize() after this Fragment's onResume()
            // when network state arrives asynchronously. Refresh here as well so the live
            // composer is enabled once the ViewModel has actually entered Live mode.
            if (_binding != null) refreshMessagingEnabled()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter?.refreshChatHighlightSettings()
        if (useChatV2) {
            chatV2Renderer?.refreshStyle(resolveChatRenderStyle(requireContext()))
            (requireContext().applicationContext as XtraApp).xtraModule.chatSessionManager.active.value?.catalog?.refresh(force = false)
        }
        if (useChatV2 && chatV2RendererVisible) chatV2Renderer?.setVisible(true)
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID)
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
        when (val mode = viewModel.activeChatMode) {
            ChatViewModel.ActiveChatMode.Live -> if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.resumeLive(channelId, channelLogin)
            }
            is ChatViewModel.ActiveChatMode.VideoReplay -> {
                viewModel.resumeTemporaryReplay(
                    videoId = mode.videoId,
                    createdAt = mode.createdAt,
                    getCurrentPosition = currentPositionProvider(),
                    getCurrentSpeed = currentSpeedProvider(),
                    channelId = channelId,
                    channelLogin = channelLogin,
                )
            }
        }
        if (viewModel.activeChatMode is ChatViewModel.ActiveChatMode.Live && !args.getBoolean(KEY_IS_LIVE)) {
            viewModel.resumeReplay(
                channelId = channelId,
                channelLogin = channelLogin,
                chatUrl = args.getString(KEY_CHAT_URL),
                videoId = args.getString(KEY_VIDEO_ID),
                createdAt = args.getString(KEY_CREATED_AT),
                startTime = args.getInt(KEY_START_TIME),
                getCurrentPosition = if (parentFragment is Media3PlayerFragment) (parentFragment as Media3PlayerFragment)::getCurrentPosition else (parentFragment as PlayerFragment)::getCurrentPosition,
                getCurrentSpeed = if (parentFragment is Media3PlayerFragment) (parentFragment as Media3PlayerFragment)::getCurrentSpeed else (parentFragment as PlayerFragment)::getCurrentSpeed
            )
        }
        refreshMessagingEnabled()
    }

    private fun currentPositionProvider(): () -> Long? = {
        when (val parent = parentFragment) {
            is Media3PlayerFragment -> parent.getCurrentPosition()
            is PlayerFragment -> parent.getCurrentPosition()
            else -> null
        }
    }

    private fun currentSpeedProvider(): () -> Float? = {
        when (val parent = parentFragment) {
            is Media3PlayerFragment -> parent.getCurrentSpeed()
            is PlayerFragment -> parent.getCurrentSpeed()
            else -> null
        }
    }

    fun isActive(): Boolean? {
        if (useChatV2) {
            val app = requireContext().applicationContext as XtraApp
            return app.xtraModule.chatSessionManager.active.value?.let { active ->
                active.spec.channelId == requireArguments().getString(KEY_CHANNEL_ID) && active.session.isActive
            }
        }
        return viewModel.isActive()
    }

    fun disconnect() {
        if (useChatV2) {
            val app = requireContext().applicationContext as XtraApp
            val active = app.xtraModule.chatSessionManager.active.value
                ?.takeIf { it.spec.channelId == requireArguments().getString(KEY_CHANNEL_ID) }
            active?.let { matching ->
                app.applicationScope.launch {
                    app.xtraModule.chatSessionManager.stop(matching.key)
                    // v2 owns message ingress, but this ViewModel still owns ancillary live
                    // features during the migration. Stop those only when playback explicitly
                    // closes; Fragment/view disappearance must not reach this branch.
                    viewModel.stopLiveChat()
                }
            }
        } else {
            viewModel.disconnect()
        }
    }

    override fun onPause() {
        if (useChatV2) chatV2Renderer?.setVisible(false)
        super.onPause()
    }

    fun setV2RendererVisible(visible: Boolean) {
        chatV2RendererVisible = visible
        if (useChatV2) chatV2Renderer?.setVisible(visible)
    }

    fun reconnect() {
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        val activeMode = viewModel.activeChatMode
        if (activeMode is ChatViewModel.ActiveChatMode.VideoReplay) {
            viewModel.resumeTemporaryReplay(
                videoId = activeMode.videoId,
                createdAt = activeMode.createdAt,
                getCurrentPosition = currentPositionProvider(),
                getCurrentSpeed = currentSpeedProvider(),
                channelId = requireArguments().getString(KEY_CHANNEL_ID),
                channelLogin = channelLogin,
            )
            viewModel.autoReconnect = true
            return
        }
        if (channelLogin != null) {
            if (useChatV2) {
                requireArguments().getString(KEY_CHANNEL_ID)?.let { channelId ->
                    startChatV2Session(channelId, channelLogin)
                    viewModel.startLive(
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        recentMessagesUrl = null,
                        channelId = channelId,
                        channelLogin = channelLogin,
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                        streamId = currentLiveStreamId(),
                        useChatV2 = true,
                    )
                }
            } else {
                viewModel.startLive(
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    recentMessagesUrl = "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel",
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = channelLogin,
                    channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                    streamId = currentLiveStreamId(),
                )
            }
        }
        viewModel.autoReconnect = true
    }

    fun reloadEmotes() {
        if (useChatV2) {
            val app = requireContext().applicationContext as XtraApp
            app.xtraModule.chatSessionManager.active.value?.catalog?.refresh(force = true)
        } else {
            viewModel.reloadEmotes(
                requireArguments().getString(KEY_CHANNEL_ID),
                requireArguments().getString(KEY_CHANNEL_LOGIN)
            )
        }
    }

    fun startReplayChatLoad() {
        viewModel.startReplayChatLoad()
    }

    fun updatePosition(position: Long) {
        viewModel.updatePosition(position)
    }

    fun updateSpeed(speed: Float) {
        viewModel.updateSpeed(speed)
    }

    fun updateStreamId(id: String?) {
        viewModel.streamId = id
    }

    fun enterVideoReplay(videoId: String, createdAt: String?, positionMs: Long) {
        val args = requireArguments()
        val alreadyInReplay = !shouldCaptureReplayComposerState(viewModel.activeChatMode)
        _binding?.let {
            if (!alreadyInReplay) {
                messageViewWasVisibleBeforeReplay = it.messageView.isVisible
            }
            it.messageView.isVisible = false
            it.editText.clearFocus()
            toggleEmoteMenu(false)
            messagingEnabled = false
            updateComposerButtons()
        }
        viewModel.enterVideoReplay(
            videoId = videoId,
            createdAt = createdAt,
            getCurrentPosition = currentPositionProvider(),
            getCurrentSpeed = currentSpeedProvider(),
            channelId = args.getString(KEY_CHANNEL_ID),
            channelLogin = args.getString(KEY_CHANNEL_LOGIN),
        )
        viewModel.updatePosition(positionMs)
    }

    fun returnToLiveChat() {
        val args = requireArguments()
        viewModel.returnToLiveChat(
            channelId = args.getString(KEY_CHANNEL_ID),
            channelLogin = args.getString(KEY_CHANNEL_LOGIN),
            channelName = args.getString(KEY_CHANNEL_NAME),
            streamId = currentLiveStreamId(),
            useChatV2 = useChatV2,
        )
        if (useChatV2) {
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (!channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) {
                startChatV2Session(channelId, channelLogin)
            }
        }
        _binding?.let {
            messageViewWasVisibleBeforeReplay?.let { wasVisible -> it.messageView.isVisible = wasVisible }
            messageViewWasVisibleBeforeReplay = null
            refreshMessagingEnabled()
        }
    }

    fun getTranslateAllMessages(): Boolean {
        return viewModel.translateAllMessages.value == true
    }

    fun saveTranslatedChannel(channelId: String) {
        viewModel.translateAllMessages.value = true
        viewModel.saveTranslatedChannel(channelId)
    }

    fun deleteTranslatedChannel(channelId: String) {
        viewModel.translateAllMessages.value = false
        viewModel.deleteTranslatedChannel(channelId)
    }

    fun emoteMenuIsVisible() = _binding?.emoteMenu?.isVisible == true

    private fun toggleChatIdentityPopup() {
        if (chatIdentityPopup?.isShowing == true) {
            chatIdentityPopup?.dismiss()
            return
        }
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID) ?: return
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN) ?: return
        val channelName = args.getString(KEY_CHANNEL_NAME)?.takeIf { it.isNotBlank() } ?: channelLogin
        toggleEmoteMenu(false)
        chatIdentityPopup = ChatIdentityPopup(
            context = requireContext(),
            rootView = binding.root,
            anchor = binding.chatIdentity,
            lifecycleOwner = viewLifecycleOwner,
            viewModel = viewModel,
            channelDisplayName = channelName,
            channelId = channelId,
            channelLogin = channelLogin,
            onDismissed = {
                chatIdentityPopup = null
                refreshBackPressedCallback()
            },
        ).also { it.show() }
        refreshBackPressedCallback()
    }

    private fun dismissChatIdentityPopup(): Boolean {
        val popup = chatIdentityPopup ?: return false
        if (!popup.isShowing) return false
        popup.dismiss()
        return true
    }

    private fun updateChatIdentityTrigger(state: ChatIdentityState) {
        val icon = _binding?.chatIdentity ?: return
        val badge = resolveChatIdentityTriggerBadge(
            state = state,
            cachedBadge = viewModel.cachedChatIdentityBadge(state.loadedChannelId, state.loadedViewerId),
        )
        if (badge?.imageUrl.isNullOrBlank()) {
            chatIdentityBadgeRequest?.dispose()
            chatIdentityBadgeRequest = null
            chatIdentityBadgeUrl = null
            icon.setImageResource(R.drawable.ic_chat_identity)
            icon.imageTintList = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    icon,
                    androidx.appcompat.R.attr.colorControlNormal,
                ),
            )
            return
        }
        val url = badge.imageUrl
        if (chatIdentityBadgeUrl == url) return
        chatIdentityBadgeRequest?.dispose()
        chatIdentityBadgeRequest = null
        chatIdentityBadgeUrl = url
        icon.imageTintList = null
        val memoryCacheKey = "xtra:chat-identity-badge:$url"
        if (restoreDecodedMemoryImage(memoryCacheKey, icon) ||
            restoreChatIdentityBadgeDrawable(memoryCacheKey, icon)
        ) return
        chatIdentityBadgeRequest = requireContext().imageLoader.enqueue(
            ImageRequest.Builder(requireContext())
                .data(url)
                .memoryCacheKey(memoryCacheKey)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .target(
                    ChatIdentityBadgeImageTarget(
                        imageView = icon,
                        cacheKey = memoryCacheKey,
                        isCurrent = { chatIdentityBadgeUrl == url },
                    ),
                )
                .build(),
        )
    }

    private fun refreshBackPressedCallback() {
        val enabled = _binding?.emoteMenu?.isVisible == true || chatIdentityPopup?.isShowing == true
        if (enabled && !backPressedCallbackAdded) {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
            backPressedCallbackAdded = true
        } else if (!enabled && backPressedCallbackAdded) {
            backPressedCallback.remove()
            backPressedCallbackAdded = false
        }
    }

    fun toggleEmoteMenu(enable: Boolean) {
        if (enable) {
            binding.emoteMenu.visibility = View.VISIBLE
            binding.emoteMenu.post { updateEmotePickerHeight() }
        } else {
            binding.emoteMenu.visibility = View.GONE
        }
        refreshBackPressedCallback()
    }

    fun toggleBackPressedCallback(enable: Boolean) {
        if (!enable && chatIdentityPopup?.isShowing != true) {
            backPressedCallback.remove()
            backPressedCallbackAdded = false
        } else {
            refreshBackPressedCallback()
        }
    }

    fun appendEmote(emote: Emote) {
        binding.editText.text.append(emote.name).append(' ')
    }

    private fun insertRecommendedEmote(recommendation: EmoteRecommendation) {
        val current = binding.editText
        val replacement = ChatInputToken.replace(
            text = current.text,
            cursor = current.selectionStart,
            replacement = recommendation.emote.name,
        ) ?: return
        current.setText(replacement.text)
        current.setSelection(replacement.cursor.coerceIn(0, current.length()))
        updateRecommendationInput()
    }

    private fun updateRecommendationInput() {
        val current = _binding?.editText ?: return
        recommendationInput.value = RecommendationInput(
            text = current.text.toString(),
            cursor = current.selectionStart.coerceAtLeast(0),
        )
    }

    private fun updateRecommendationVisibility() {
        val currentBinding = _binding ?: return
        currentBinding.recommendationStrip.isVisible = currentRecommendations.isNotEmpty() &&
                messagingEnabled && currentBinding.messageView.isVisible
    }

    private fun resetMessageComposerAction() {
        replyComposerState = null
        with(binding) {
            replyView.visibility = View.GONE
            send.setOnClickListener { sendMessage() }
            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    val sent = sendMessage()
                    sent || viewModel.isSlowModeBlocked()
                } else {
                    false
                }
            }
            editText.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                ) {
                    val sent = sendMessage()
                    sent || viewModel.isSlowModeBlocked()
                } else {
                    false
                }
            }
        }
    }

    private fun updateComposerButtons() {
        if (_binding == null) return
        val hasText = binding.editText.text.isNotBlank()
        val canShareWithoutMessage = composerOverlayState is ComposerOverlayState.StreakShare
        val blockedBySlowMode = composerOverlayState == null && viewModel.isSlowModeBlocked()
        binding.send.isVisible = !composerSubmissionInProgress && (hasText || canShareWithoutMessage)
        binding.send.isEnabled = !blockedBySlowMode
        binding.clear.isVisible = !composerSubmissionInProgress && hasText
        updateRecommendationVisibility()
    }

    private fun refreshMessagingEnabled() {
        val args = arguments ?: return
        val accountLogin = requireContext().tokenPrefs().getString(C.USERNAME, null)
        val isLoggedIn = !accountLogin.isNullOrBlank() &&
                (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                        !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
        messagingEnabled = args.getBoolean(KEY_IS_LIVE) &&
                viewModel.activeChatMode is ChatViewModel.ActiveChatMode.Live &&
                isLoggedIn
        binding.messageView.isVisible = shouldShowChatComposer(
            chatAvailable = args.getBoolean(KEY_IS_LIVE) &&
                    viewModel.activeChatMode is ChatViewModel.ActiveChatMode.Live,
            isSlidingPlayerLayout = isInSlidingPlayerLayout(binding.root),
            chatBarVisible = requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true),
        )
        binding.editText.isEnabled = messagingEnabled && !composerSubmissionInProgress
        updateComposerButtons()
        updateRecommendationVisibility()
        updateSlowModeIndicator(viewModel.slowModeState.value)
    }

    private fun isInSlidingPlayerLayout(root: View): Boolean =
        (root.parent?.parent?.parent?.parent as? View)?.id == R.id.slidingLayout

    private fun updateSlowModeIndicator(state: SlowModeState) {
        if (_binding == null) return
        val indicator = binding.slowModeIndicator
        val becameReady = lastSlowModeUiState.coolingDown && state.enabled && !state.coolingDown
        val shouldShow = messagingEnabled && binding.messageView.isVisible && state.enabled && state.blocked
        if (!shouldShow) {
            indicator.animate().cancel()
            indicator.alpha = 1f
            if (becameReady && indicator.isVisible) {
                @Suppress("DEPRECATION")
                indicator.announceForAccessibility(getString(R.string.chat_slow_mode_ready))
            }
            indicator.isVisible = false
            lastSlowModeUiState = state
            return
        }

        val nextText = getString(R.string.chat_slow_mode_remaining, state.remainingSeconds)
        if (indicator.isVisible && indicator.text != nextText) {
            indicator.animate().cancel()
            indicator.animate()
                .alpha(0.72f)
                .setDuration(80L)
                .withEndAction {
                    indicator.text = nextText
                    indicator.animate().alpha(1f).setDuration(120L).start()
                }
                .start()
        } else {
            indicator.animate().cancel()
            indicator.alpha = 1f
            indicator.text = nextText
        }

        val contentDescription = getString(
            R.string.chat_slow_mode_accessibility,
            state.intervalSeconds ?: 0,
        )
        if (indicator.contentDescription != contentDescription) {
            indicator.contentDescription = contentDescription
        }
        indicator.isVisible = true
        lastSlowModeUiState = state
    }

    private fun updateChannelPointsActivity(state: ChannelPointsActivityState) {
        if (_binding == null) return
        val foregroundDefault = MaterialColors.getColor(
            binding.channelPoints,
            androidx.appcompat.R.attr.colorControlNormal,
        )
        val (background, foreground, description, alpha) = when {
            state.poll != null -> {
                val remaining = state.pollSeconds?.takeIf { it > 0 }?.let {
                    DateUtils.formatElapsedTime(it.toLong())
                } ?: getString(R.string.channel_points_activity_unknown_time)
                ActivityVisualState(
                    background = MaterialColors.getColor(
                        binding.channelPoints,
                        com.google.android.material.R.attr.colorPrimaryContainer,
                    ),
                    foreground = MaterialColors.getColor(
                        binding.channelPoints,
                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                    ),
                    description = getString(R.string.channel_points_activity_poll, remaining),
                    alpha = 1f,
                )
            }
            state.prediction != null && PredictionState.isBettingOpen(state.prediction) -> {
                val remaining = state.predictionSeconds?.takeIf { it > 0 }?.let {
                    DateUtils.formatElapsedTime(it.toLong())
                } ?: getString(R.string.channel_points_activity_unknown_time)
                ActivityVisualState(
                    background = MaterialColors.getColor(
                        binding.channelPoints,
                        com.google.android.material.R.attr.colorSecondaryContainer,
                    ),
                    foreground = MaterialColors.getColor(
                        binding.channelPoints,
                        com.google.android.material.R.attr.colorOnSecondaryContainer,
                    ),
                    description = getString(R.string.channel_points_activity_prediction_open, remaining),
                    alpha = 1f,
                )
            }
            state.prediction != null && PredictionState.isOngoing(state.prediction) -> ActivityVisualState(
                background = MaterialColors.getColor(
                    binding.channelPoints,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                ),
                foreground = MaterialColors.getColor(
                    binding.channelPoints,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
                description = getString(R.string.channel_points_activity_prediction_locked),
                alpha = 0.8f,
            )
            else -> ActivityVisualState(
                background = null,
                foreground = foregroundDefault,
                description = getString(R.string.channel_points),
                alpha = 1f,
            )
        }
        binding.channelPoints.backgroundTintList = background?.let(ColorStateList::valueOf)
        channelPointsIconForeground = foreground
        updateChannelPointsIconTint()
        binding.channelPointsIcon.alpha = alpha
        binding.channelPointsText.setTextColor(foreground)
        val accessibilityDescription = if (background == null) {
            channelPointsAccessibilityLabel ?: description
        } else {
            listOfNotNull(channelPointsAccessibilityLabel, description).joinToString(". ")
        }
        binding.channelPoints.contentDescription = accessibilityDescription
        ViewCompat.setStateDescription(binding.channelPoints, accessibilityDescription)
    }

    private data class ChannelPointsActivityState(
        val poll: Poll?,
        val pollSeconds: Int?,
        val prediction: Prediction?,
        val predictionSeconds: Int?,
    )

    private data class HappeningNowActivityState(
        val prediction: Prediction?,
        val poll: Poll?,
        val canBetPrediction: Boolean,
        val canVotePoll: Boolean,
    )

    private data class ActivityVisualState(
        val background: Int?,
        val foreground: Int,
        val description: String,
        val alpha: Float,
    )

    private fun updateComposerDensity() {
        if (_binding == null || binding.messageView.width <= 0) return
        val compactWidth = (320 * resources.displayMetrics.density).toInt()
        val compact = binding.messageView.width < compactWidth
        val controlSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            if (compact) 40f else 48f,
            resources.displayMetrics,
        ).toInt()
        listOf(binding.chatIdentity, binding.clear, binding.emotes, binding.send).forEach { control ->
            control.updateLayoutParams<LinearLayout.LayoutParams> {
                width = controlSize
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            control.minimumWidth = controlSize
        }
        binding.channelPointsText.isVisible = binding.channelPoints.isVisible && !compact
        binding.channelPoints.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (compact) controlSize else ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.channelPoints.minimumWidth = controlSize
    }

    private fun setupEmotePickerSizing() {
        listOf(
            binding.root,
            binding.chatContentColumn,
            binding.emoteMenu,
            binding.tabLayout,
            binding.viewPager,
            binding.messageView,
            binding.replyView,
            binding.channelPointRewardOverlay,
        ).forEach { view ->
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateEmotePickerHeight()
            }
        }
    }

    private fun updateEmotePickerHeight() {
        val currentBinding = _binding ?: return
        if (!currentBinding.emoteMenu.isVisible) return

        val contentColumn = currentBinding.chatContentColumn
        val tabHeight = currentBinding.tabLayout.measuredHeight
        if (contentColumn.measuredHeight <= 0 || tabHeight <= 0) return

        val historyContainer = currentBinding.recyclerView.parent as? View
        var fixedContentHeight = contentColumn.paddingTop + contentColumn.paddingBottom
        for (index in 0 until contentColumn.childCount) {
            val child = contentColumn.getChildAt(index)
            val childMargins = verticalMargins(child)
            if (child === currentBinding.emoteMenu) continue
            if (child === historyContainer) {
                // The history area has a weight and can shrink, but its margins cannot.
                fixedContentHeight += childMargins
                continue
            }
            if (child.visibility != View.GONE) {
                fixedContentHeight += child.measuredHeight + childMargins
            }
        }

        val pickerMargins = verticalMargins(currentBinding.emoteMenu) +
            verticalMargins(currentBinding.tabLayout) +
            verticalMargins(currentBinding.viewPager)
        val targetHeight = calculateEmotePickerPagerHeight(
            hostHeight = contentColumn.measuredHeight,
            fixedContentHeight = fixedContentHeight,
            tabHeight = tabHeight,
            pickerMargins = pickerMargins,
            maxHeight = resources.getDimensionPixelSize(R.dimen.emote_picker_max_height),
        )
        if (currentBinding.viewPager.layoutParams.height != targetHeight) {
            currentBinding.viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                height = targetHeight
            }
        }
    }

    private fun verticalMargins(view: View): Int {
        return (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.topMargin + params.bottomMargin
        } ?: 0
    }

    private fun showComposerOverlay(state: ComposerOverlayState) {
        if (composerSubmissionInProgress) return
        if (composerOverlayState == null) {
            val restoreState = ComposerRestoreState(
                text = binding.editText.text.toString(),
                selection = binding.editText.selectionStart.takeIf { it >= 0 },
                reply = replyComposerState,
            )
            overlayStateStore.open(state, restoreState)
        } else if (overlayStateStore.active == null) {
            overlayStateStore.open(
                overlay = state,
                restoreState = ComposerRestoreState("", null, null),
            )
        }
        composerOverlayState = state
        pendingComposerText = null
        renderComposerOverlay(state)
    }

    private fun renderComposerOverlay(state: ComposerOverlayState) {
        with(binding) {
            resetMessageComposerAction()
            toggleEmoteMenu(false)
            messageView.isVisible = true
            editText.text.clear()
            channelPointRewardOverlay.isVisible = true
            when (state) {
                is ComposerOverlayState.Reward -> {
                    channelPointRewardTitle.text = state.reward.title
                    channelPointRewardSubtitle.text = state.reward.prompt
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.channel_points_reward_message_overlay)
                    updateComposerOverlayIcon(state.reward.imageUrl, R.drawable.ic_channel_points_default)
                }
                is ComposerOverlayState.StreakShare -> {
                    channelPointRewardTitle.text = getString(
                        R.string.channel_points_streak_milestone,
                        state.streak.nextMilestone ?: state.streak.streakCount,
                    )
                    channelPointRewardSubtitle.text = getString(R.string.channel_points_streak_share_prompt)
                    updateComposerOverlayIcon(null, R.drawable.ic_watch_streak)
                }
            }
            channelPointRewardCancel.setOnClickListener { cancelComposerOverlay() }
            updateComposerButtons()
            editText.requestFocus()
            WindowCompat.getInsetsController(this@ChatFragment.requireActivity().window, editText)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun updateComposerOverlayIcon(url: String?, fallback: Int) {
        val icon = binding.channelPointRewardIcon
        icon.setImageResource(fallback)
        if (url.isNullOrBlank()) {
            icon.imageTintList = if (fallback == R.drawable.ic_watch_streak) {
                null
            } else {
                ColorStateList.valueOf(MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorControlNormal))
            }
        } else {
            icon.imageTintList = null
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(true)
                    .target(icon)
                    .build(),
            )
        }
    }

    private fun cancelComposerOverlay(force: Boolean = false) {
        if (composerSubmissionInProgress && !force) return
        val restoreState = pendingOverlaySubmission?.restoreState ?: overlayStateStore.active?.restoreState
        composerOverlayState = null
        pendingComposerText = null
        pendingOverlaySubmission = null
        composerSubmissionInProgress = false
        overlayStateStore.clear()
        binding.channelPointRewardOverlay.isGone = true
        refreshMessagingEnabled()
        restoreState?.let(::restoreComposerState)
        updateComposerButtons()
    }

    private fun restorePendingComposerText() {
        val pending = pendingOverlaySubmission
        pending?.let {
            composerOverlayState = pending.state
            overlayStateStore.markFailed(pending.text)
            renderComposerOverlay(pending.state)
        }
        (pending?.text ?: pendingComposerText)?.let { text ->
            binding.editText.setText(text)
            binding.editText.setSelection(binding.editText.length())
        }
        pendingComposerText = null
        pendingOverlaySubmission = null
        composerSubmissionInProgress = false
        binding.editText.isEnabled = messagingEnabled && !composerSubmissionInProgress
        updateComposerButtons()
    }

    private fun restoreComposerState(state: ComposerRestoreState) {
        replyComposerState = state.reply
        state.reply?.let(::configureReplyComposer)
        binding.editText.setText(state.text)
        binding.editText.setSelection(
            (state.selection ?: binding.editText.length()).coerceIn(0, binding.editText.length()),
        )
    }

    private fun captureActiveOverlayState() {
        val existing = overlayStateStore.active
        val overlay = composerOverlayState ?: existing?.overlay ?: return
        val pending = pendingOverlaySubmission
        captureComposerOverlaySnapshot(
            overlay = overlay,
            existing = existing,
            pendingRestoreState = pending?.restoreState,
            pendingInput = pending?.text,
            currentInput = _binding?.editText?.text?.toString(),
            submissionPending = composerSubmissionInProgress || pending != null,
        )?.let(overlayStateStore::set)
    }

    private fun handleChannelPointRedemption(result: ChannelPointRedemptionResult) {
        val pending = pendingOverlaySubmission
        val overlay = pending?.state
        val matchesSubmission = overlay is ComposerOverlayState.Reward &&
            (overlay.reward.id == result.rewardId ||
                (result.rewardId == null && overlay.reward.title == result.rewardTitle))
        if (matchesSubmission) {
            if (result.success) {
                cancelComposerOverlay(force = true)
            } else {
                restorePendingComposerText()
            }
        }
        val message = if (result.success) {
            getString(R.string.channel_points_reward_redeemed, result.rewardTitle)
        } else {
            getString(R.string.channel_points_reward_failed, result.rewardTitle, result.message.orEmpty())
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun handleWatchStreakShare(result: WatchStreakShareResult) {
        val pending = pendingOverlaySubmission
        val overlay = pending?.state
        val matchesSubmission = overlay is ComposerOverlayState.StreakShare &&
            (overlay.streak.milestoneId == result.milestoneId ||
                (result.milestoneId == null && overlay.streak.milestoneId == null))
        if (matchesSubmission) {
            if (result.success) {
                cancelComposerOverlay(force = true)
            } else {
                restorePendingComposerText()
            }
        }
        val message = if (result.success) {
            getString(R.string.channel_points_streak_shared)
        } else {
            getString(R.string.channel_points_streak_share_failed, result.message.orEmpty())
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun setupDropCallout() {
        val root = binding.root
        dropCalloutView = root.findViewById(R.id.dropCallout)
        dropImageView = root.findViewById(R.id.dropImage)
        dropTitleView = root.findViewById(R.id.dropTitle)
        dropSubtitleView = root.findViewById(R.id.dropSubtitle)
        dropProgressView = root.findViewById(R.id.dropProgress)

        dropCalloutView?.setOnClickListener {
            val state = viewModel.dropsUiState.value
            val drop = state.mostRelevantDrop
            if (state.claimingDropId != null || drop == null) return@setOnClickListener
            if (drop.isClaimable) {
                viewModel.claimDrop(drop)
            } else {
                findNavController().navigate(
                    R.id.action_global_dropsFragment,
                    null,
                    NavOptions.Builder().setLaunchSingleTop(true).build(),
                )
            }
        }
        root.findViewById<View>(R.id.dropClose)?.setOnClickListener {
            viewModel.dropsUiState.value.mostRelevantDrop?.let { drop ->
                dismissedDropPresentationKey = dropPresentationKey(drop)
            }
            dropCalloutView?.isGone = true
        }
    }

    private fun updateDropCallout(state: DropsUiState) {
        val callout = dropCalloutView ?: return
        if (!requireContext().prefs().getBoolean(C.CHAT_DROPS_SHOW, true)) {
            callout.isGone = true
            return
        }

        val drop = state.mostRelevantDrop
        if (drop == null || dismissedDropPresentationKey == dropPresentationKey(drop)) {
            callout.isGone = true
            return
        }

        val isClaiming = state.claimingDropId == drop.id
        val rewardName = drop.rewardName ?: drop.name
        dropTitleView?.text = getString(
            if (drop.isClaimable) R.string.drops_ready_title else R.string.drops_progress_title,
        )
        dropSubtitleView?.text = when {
            isClaiming -> getString(R.string.drops_claiming)
            drop.isClaimable -> listOfNotNull(
                rewardName,
                getString(R.string.drops_tap_to_claim),
            ).joinToString(" · ")
            else -> listOfNotNull(
                rewardName,
                "${drop.progressPercent}%",
                "${drop.currentMinutesWatched}/${drop.requiredMinutesWatched} min",
            ).joinToString(" · ")
        }
        dropProgressView?.progress = drop.progressPercent
        callout.isClickable = !isClaiming
        callout.alpha = if (isClaiming) 0.65f else 1f
        updateDropImage(drop.imageUrl)
        callout.isVisible = true
    }

    private fun updateDropImage(url: String?) {
        val image = dropImageView ?: return
        if (dropImageUrl == url && dropImageTarget === image) return
        disposeDropImageRequest()
        dropImageUrl = url
        dropImageTarget = image
        val requestGeneration = ++dropImageRequestGeneration
        image.setImageDrawable(null)
        image.isVisible = !url.isNullOrBlank()
        if (url.isNullOrBlank()) return

        val context = requireContext()
        dropImageRequest = context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .target(image)
                .listener(object : ImageRequest.Listener {
                    override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                        if (!isCurrentDropImageRequest(url, requestGeneration)) return
                        image.setImageDrawable(null)
                    }
                })
                .build(),
        )
    }

    private fun disposeDropImageRequest() {
        dropImageRequest?.dispose()
        dropImageRequest = null
    }

    private fun isCurrentDropImageRequest(url: String, requestGeneration: Int): Boolean {
        return dropImageView != null &&
            dropImageUrl == url &&
            dropImageRequestGeneration == requestGeneration
    }

    private fun handleDropClaimResult(result: ChatViewModel.DropClaimResult) {
        val message = if (result.success) {
            getString(R.string.drops_claimed)
        } else {
            val safeError = result.message
                ?.takeIf { it.length <= 80 }
                ?.takeUnless {
                    it.contains('{') ||
                        it.contains('}') ||
                        it.contains("OAuth", ignoreCase = true) ||
                        it.contains("Bearer", ignoreCase = true) ||
                        it.contains("dropInstance", ignoreCase = true)
                }
            if (safeError.isNullOrBlank()) {
                getString(R.string.drops_claim_failed)
            } else {
                "${getString(R.string.drops_claim_failed)}: $safeError"
            }
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun dropPresentationKey(drop: TwitchDrop): String =
        "${drop.id}:${drop.isClaimable}"

    private fun sendMessage(replyId: String? = null): Boolean {
        if (!messagingEnabled || composerSubmissionInProgress) return false
        with(binding) {
            val overlay = composerOverlayState
            if (overlay != null) {
                val text = editText.text.trim().toString()
                if (overlay is ComposerOverlayState.Reward && text.isBlank()) {
                    return false
                }
                pendingComposerText = text
                val restoreState = overlayStateStore.active?.restoreState ?: ComposerRestoreState(
                    text = "",
                    selection = null,
                    reply = null,
                )
                overlayStateStore.open(overlay, restoreState)
                overlayStateStore.submit(text)
                pendingOverlaySubmission = PendingOverlaySubmission(
                    state = overlay,
                    text = text,
                    restoreState = restoreState,
                )
                composerSubmissionInProgress = true
                editText.isEnabled = false
                editText.text.clear()
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editText.windowToken, 0)
                editText.clearFocus()
                toggleEmoteMenu(false)
                updateComposerButtons()
                when (overlay) {
                    is ComposerOverlayState.Reward -> viewModel.redeemChannelPointReward(overlay.reward, text, null)
                    is ComposerOverlayState.StreakShare -> viewModel.shareWatchStreak(overlay.streak, text)
                }
                return true
            }
            if (viewModel.isSlowModeBlocked()) {
                return false
            }
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
            editText.clearFocus()
            toggleEmoteMenu(false)
            val text = editText.text.trim().toString()
            return if (text.isNotEmpty()) {
                pendingChatSubmission = PendingChatSubmission(text = text, replyId = replyId)
                composerSubmissionInProgress = true
                editText.isEnabled = false
                // Clear immediately so the message does not linger in the box
                // while the send completes. Failures restore it from the
                // pending submission in handleChatSendResult.
                editText.text.clear()
                viewModel.send(
                    message = text,
                    replyId = replyId,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    accountId = requireContext().tokenPrefs().getString(C.USER_ID, null),
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, true),
                    useApiChatMessages = requireContext().prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, true),
                    onResult = { result -> handleChatSendResult(result, text, replyId) },
                )
                updateComposerButtons()
                true
            } else {
                false
            }
        }
    }

    private fun hideChatInputForDialog() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.editText.windowToken, 0)
        binding.editText.clearFocus()
    }

    private fun onV2MessageLongClick(message: V2ChatMessage) {
        selectedV2Message = message
        hideChatInputForDialog()
        MessageClickedDialog.newInstance(
            messagingEnabled = messagingEnabled,
            channelId = message.channelId,
            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
        ).show(childFragmentManager, "messageDialog")
    }

    private fun onV2PublicationChanged(
        messages: List<V2ChatMessage>,
        rows: List<ChatRowUiModel>,
    ) {
        if (selectedV2Message != null) {
            messageDialog?.updateV2Messages(messages.map(::v2MessageToLegacy), rows)
        }
    }

    private fun handleChatSendResult(result: ChatSendResult, submittedText: String, submittedReplyId: String?) {
        val pending = pendingChatSubmission
        if (pending == null || pending.text != submittedText || pending.replyId != submittedReplyId) return
        val currentBinding = _binding
        if (currentBinding == null) {
            pendingChatSendResult = result
            return
        }
        pendingChatSubmission = null
        composerSubmissionInProgress = false
        when (result) {
            is ChatSendResult.Success -> {
                currentBinding.editText.text.clear()
                currentBinding.editText.isEnabled = messagingEnabled
                resetMessageComposerAction()
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(currentBinding.editText.windowToken, 0)
                currentBinding.editText.clearFocus()
                toggleEmoteMenu(false)
                if (useChatV2) {
                    chatV2Renderer?.jumpToNewest()
                } else {
                    val lastIndex = synchronized(viewModel.chatMessages) { viewModel.chatMessages.lastIndex }
                    if (lastIndex >= 0) currentBinding.recyclerView.scrollToPosition(lastIndex)
                }
            }
            is ChatSendResult.Failure -> {
                if (currentBinding.editText.text.isBlank()) {
                    currentBinding.editText.setText(submittedText)
                    currentBinding.editText.setSelection(currentBinding.editText.length())
                }
                currentBinding.editText.isEnabled = messagingEnabled
                if (messagingEnabled) currentBinding.editText.requestFocus()
                val safeMessage = result.message
                    .takeIf { it.length <= 120 }
                    ?.takeUnless {
                        it.contains('{') || it.contains('}') ||
                                it.contains("OAuth", ignoreCase = true) ||
                                it.contains("Bearer", ignoreCase = true)
                    }
                    ?: getString(R.string.connection_error)
                Snackbar.make(currentBinding.root, getString(R.string.chat_send_msg_error, safeMessage), Snackbar.LENGTH_LONG).show()
            }
        }
        updateComposerButtons()
    }

    private fun onV2EmoteClick(interaction: ChatEmoteInteraction) {
        hideChatInputForDialog()
        val source = when (interaction.provider) {
            ChatAssetProvider.TWITCH -> null
            ChatAssetProvider.SEVEN_TV -> when (interaction.scope) {
                ChatEmoteScope.PERSONAL -> Emote.PERSONAL_STV
                ChatEmoteScope.CHANNEL -> Emote.CHANNEL_STV
                ChatEmoteScope.GLOBAL -> Emote.GLOBAL_STV
                ChatEmoteScope.LEGACY_COMBINED, null -> null
            }
            ChatAssetProvider.BTTV -> when (interaction.scope) {
                ChatEmoteScope.CHANNEL -> Emote.CHANNEL_BTTV
                ChatEmoteScope.GLOBAL -> Emote.GLOBAL_BTTV
                ChatEmoteScope.PERSONAL, ChatEmoteScope.LEGACY_COMBINED, null -> null
            }
            ChatAssetProvider.FFZ -> when (interaction.scope) {
                ChatEmoteScope.CHANNEL -> Emote.CHANNEL_FFZ
                ChatEmoteScope.GLOBAL -> Emote.GLOBAL_FFZ
                ChatEmoteScope.PERSONAL, ChatEmoteScope.LEGACY_COMBINED, null -> null
            }
        }
        val url = interaction.url
        ImageClickedDialog.newInstance(
            url = url,
            name = interaction.name,
            format = url?.substringAfterLast('.', "webp"),
            isAnimated = interaction.animated,
            source = source,
            thirdParty = interaction.provider != ChatAssetProvider.TWITCH,
            emoteId = interaction.id.takeIf { interaction.provider == ChatAssetProvider.TWITCH },
        ).show(childFragmentManager, "imageDialog")
    }

    private fun onV2GifClick(interaction: ChatGifInteraction) {
        hideChatInputForDialog()
        ImageClickedDialog.newGifInstance(
            url = interaction.url,
            description = interaction.description,
        ).show(childFragmentManager, "imageDialog")
    }

    private fun v2MessageToLegacy(message: V2ChatMessage): ChatMessage {
        val text = message.rawText ?: message.segments.joinToString(separator = "") { segment ->
            when (segment) {
                is com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment.Text -> segment.text
                is com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment.Mention -> segment.text
                is com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment.Emote -> segment.fallbackText
                is com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment.Gif -> segment.fallbackText
                is com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment.Cheermote -> segment.text
            }
        }
        val reply = message.reply?.let {
            com.github.andreyasadchy.xtra.model.chat.Reply(
                threadParentId = it.parentMessageId.value,
                userLogin = it.parentUserLogin,
                userName = it.parentUserName,
                message = it.parentMessageBody,
            )
        }
        val replyParent = message.reply?.let {
            ChatMessage(
                type = ChatMessage.USER_MESSAGE,
                id = it.parentMessageId.value,
                userId = it.parentUserId,
                userLogin = it.parentUserLogin,
                userName = it.parentUserName,
                message = it.parentMessageBody,
            )
        }
        return ChatMessage(
            type = if (message.user != null) ChatMessage.USER_MESSAGE else ChatMessage.SYSTEM_MESSAGE,
            id = message.id.value,
            userId = message.user?.id,
            userLogin = message.user?.login,
            userName = message.user?.displayName,
            message = text.takeIf { it.isNotEmpty() },
            isAction = message.kind == com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.ACTION,
            bits = message.bits,
            badges = message.badges.map { badge ->
                com.github.andreyasadchy.xtra.model.chat.Badge(badge.setId, badge.versionId)
            },
            systemMsg = message.systemText,
            msgId = message.noticeType,
            sourceMsgId = message.source?.messageId?.value,
            reply = reply,
            replyParent = replyParent,
            timestamp = message.timestampMs,
        ).apply {
            v2Translations[message.id.value]?.let {
                translatedMessage = it
                translationFailed = it.contains(getString(R.string.translate_failed_id), ignoreCase = true)
            }
        }
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter? {
        selectedPinnedMessage?.let { pinnedMessage ->
            selectedPinnedMessage = null
            return adapter?.createMessageClickedChatAdapter(
                selectedMessageOverride = pinnedMessage,
            )
        }
        val clicked = selectedV2Message
        if (!useChatV2 || clicked == null) return adapter?.createMessageClickedChatAdapter()
        val canonicalMessages = chatV2Renderer?.currentMessages().orEmpty()
        val history = if (clicked.user != null) {
            canonicalMessages.filter { matchesV2MessageUser(it, clicked) }
        } else {
            canonicalMessages
        }
        val messages = history.map(::v2MessageToLegacy)
        val selected = messages.firstOrNull { it.id == clicked.id.value } ?: v2MessageToLegacy(clicked)
        val historyIds = history.mapTo(HashSet()) { it.id }
        val rows = chatV2Renderer?.currentRows().orEmpty().filter { it.id in historyIds }
        val app = requireContext().applicationContext as XtraApp
        return adapter?.createMessageClickedChatAdapter(
            sourceMessages = messages,
            selectedMessageOverride = selected,
            v2Rows = rows,
            v2Assets = app.xtraModule.chatAssetRepository,
            v2EmoteClick = ::onV2EmoteClick,
            v2GifClick = ::onV2GifClick,
        )
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter? {
        return adapter?.createReplyClickedChatAdapter()
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        if (replyId.isNullOrBlank()) return
        cancelComposerOverlay()
        messageDialog?.dismiss()
        replyComposerState = ReplyComposerState(replyId, userLogin, userName, message)
        configureReplyComposer(replyComposerState!!)
    }

    private fun configureReplyComposer(state: ReplyComposerState) {
        with(binding) {
            replyView.visibility = View.VISIBLE
            replyText.text = state.message?.let { message ->
                val name = if (state.userName != null && state.userLogin != null &&
                    !state.userLogin.equals(state.userName, true)
                ) {
                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "${state.userName}(${state.userLogin})"
                        "1" -> state.userName
                        else -> state.userLogin
                    }
                } else {
                    state.userName ?: state.userLogin
                }
                getString(R.string.replying_to_message, name, message)
            }
            replyClose.setOnClickListener { resetMessageComposerAction() }
            send.setOnClickListener { sendMessage(state.replyId) }
            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    val sent = sendMessage(state.replyId)
                    sent || viewModel.isSlowModeBlocked()
                } else {
                    false
                }
            }
            editText.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                ) {
                    val sent = sendMessage(state.replyId)
                    sent || viewModel.isSlowModeBlocked()
                } else {
                    false
                }
            }
            editText.requestFocus()
            WindowCompat.getInsetsController(this@ChatFragment.requireActivity().window, editText)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onCopyMessageClicked(message: String) {
        binding.editText.setText(message)
    }

    override fun onWhisperClicked(userLogin: String) {
        messageDialog?.dismiss()
        resetMessageComposerAction()
        binding.editText.setText("/w $userLogin ")
        binding.editText.setSelection(binding.editText.length())
        binding.editText.requestFocus()
        WindowCompat.getInsetsController(this@ChatFragment.requireActivity().window, binding.editText)
            .show(WindowInsetsCompat.Type.ime())
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelImage: String?) {
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = id,
                channelLogin = login,
                channelName = name,
                channelImage = channelImage
            )
        )
        (parentFragment as? Media3PlayerFragment)?.minimize() ?: (parentFragment as? PlayerFragment)?.minimize()
    }

    override fun onTranslateMessageClicked(chatMessage: ChatMessage, languageTag: String?) {
        val message = chatMessage.message ?: chatMessage.systemMsg
        if (message != null) {
            if (languageTag != null) {
                translateMessage(message, chatMessage, languageTag)
            } else {
                val languageIdentifier = languageIdentifier ?: LanguageIdentification.getClient().also { languageIdentifier = it }
                languageIdentifier.identifyLanguage(message)
                    .addOnSuccessListener { tag ->
                        translateMessage(message, chatMessage, tag)
                    }
                    .addOnFailureListener {
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translate_failed_id)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = null
                        syncV2Translation(chatMessage)
                        adapter?.updateMessageContent(chatMessage)
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
            }
        }
    }

    private fun translateMessage(message: String, chatMessage: ChatMessage, tag: String) {
        val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
        if (tag != "und" && tag != targetLanguage) {
            TranslateLanguage.fromLanguageTag(tag)?.let { sourceLanguage ->
                val translator = translators[sourceLanguage] ?: Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).also {
                    if (translators.size >= 3) {
                        val entry = translators.entries.first()
                        translators.remove(entry.key)
                        entry.value.close()
                    }
                    translators[sourceLanguage] = it
                }
                translator.translate(message)
                    .addOnSuccessListener { text ->
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translated_message, languageName, text)
                        chatMessage.translationFailed = false
                        chatMessage.messageLanguage = null
                        syncV2Translation(chatMessage)
                        adapter?.updateMessageContent(chatMessage)
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
                    .addOnFailureListener {
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translate_failed, languageName)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = sourceLanguage
                        syncV2Translation(chatMessage)
                        adapter?.updateMessageContent(chatMessage)
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
            }
        } else {
            val previousTranslation = chatMessage.translatedMessage
            chatMessage.translatedMessage = getString(R.string.translate_failed_id)
            chatMessage.translationFailed = true
            chatMessage.messageLanguage = null
            syncV2Translation(chatMessage)
            adapter?.updateMessageContent(chatMessage)
            messageDialog?.updateTranslation(chatMessage, previousTranslation)
            replyDialog?.updateTranslation(chatMessage, previousTranslation)
        }
    }

    private fun syncV2Translation(chatMessage: ChatMessage) {
        if (!useChatV2 || chatMessage.id.isNullOrBlank()) return
        chatMessage.translatedMessage?.let { v2Translations[chatMessage.id!!] = it }
            ?: v2Translations.remove(chatMessage.id!!)
        chatV2Renderer?.invalidatePresentation()
    }

    private fun requestV2Translation(message: V2ChatMessage) {
        onTranslateMessageClicked(v2MessageToLegacy(message), null)
    }

    private fun showLanguageDownloadDialog(chatMessage: ChatMessage, sourceLanguage: String) {
        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
        requireContext().getAlertDialogBuilder()
            .setMessage(getString(R.string.download_language_model_message, languageName))
            .setNegativeButton(getString(R.string.no), null)
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
                val translator = translators[sourceLanguage] ?: Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).also {
                    if (translators.size >= 3) {
                        val entry = translators.entries.first()
                        translators.remove(entry.key)
                        entry.value.close()
                    }
                    translators[sourceLanguage] = it
                }
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        val message = chatMessage.message ?: chatMessage.systemMsg
                        if (message != null) {
                            translator.translate(message)
                                .addOnSuccessListener { text ->
                                    val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                                    val previousTranslation = chatMessage.translatedMessage
                                    chatMessage.translatedMessage = getString(R.string.translated_message, languageName, text)
                                    chatMessage.translationFailed = false
                                    chatMessage.messageLanguage = null
                                    syncV2Translation(chatMessage)
                                    adapter?.updateMessageContent(chatMessage)
                                    messageDialog?.updateTranslation(chatMessage, previousTranslation)
                                    replyDialog?.updateTranslation(chatMessage, previousTranslation)
                                }
                        }
                    }
            }
            .show()
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            when (val mode = viewModel.activeChatMode) {
                ChatViewModel.ActiveChatMode.Live -> if (args.getBoolean(KEY_IS_LIVE)) {
                    viewModel.resumeLive(channelId, channelLogin)
                }
                is ChatViewModel.ActiveChatMode.VideoReplay -> {
                    viewModel.resumeTemporaryReplay(
                        videoId = mode.videoId,
                        createdAt = mode.createdAt,
                        getCurrentPosition = currentPositionProvider(),
                        getCurrentSpeed = currentSpeedProvider(),
                        channelId = channelId,
                        channelLogin = channelLogin,
                    )
                }
            }
        }
    }

    override fun onStop() {
        chatIdentityPopup?.dismiss()
        super.onStop()
        if (!useChatV2 && (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false))) {
            viewModel.stopLiveChat()
            viewModel.stopReplayChat()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureActiveOverlayState()
        chatV2ViewportState = chatV2Renderer?.state ?: chatV2ViewportState
        outState.putString(KEY_V2_FOLLOW_MODE, chatV2ViewportState.followMode.name)
        outState.putInt(KEY_V2_NEW_MESSAGE_COUNT, chatV2ViewportState.newMessageCount)
        outState.putString(KEY_V2_ANCHOR_ID, chatV2ViewportState.anchor?.messageId?.value)
        outState.putInt(KEY_V2_ANCHOR_OFFSET, chatV2ViewportState.anchor?.topOffsetPx ?: 0)
        if (overlayStateStore.active == null) {
            outState.putString(KEY_COMPOSER_DRAFT, _binding?.editText?.text?.toString())
        }
        outState.putString(KEY_SEEN_PINNED_MESSAGE_ID, seenPinnedMessageId)
        outState.putString(KEY_DISPLAYED_PINNED_MESSAGE_ID, displayedPinnedMessageId)
        outState.putBoolean(KEY_PINNED_MESSAGE_MINIMIZED, pinnedMessageMinimized)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        pinnedMessageTimerJob?.cancel()
        pinnedMessageTimerJob = null
        pinnedMessageBinding = null
        captureActiveOverlayState()
        chatV2ViewportState = chatV2Renderer?.state ?: chatV2ViewportState
        chatV2Renderer?.detach()
        chatV2Renderer = null
        chatIdentityPopup?.dismiss()
        chatIdentityPopup = null
        chatIdentityBadgeRequest?.dispose()
        chatIdentityBadgeRequest = null
        chatIdentityBadgeUrl = null
        backPressedCallback.remove()
        backPressedCallbackAdded = false
        _binding?.recyclerView?.removeCallbacks(chatAdapterUpdateRunnable)
        _binding?.recommendationStrip?.adapter = null
        recommendationAdapter?.submitList(emptyList())
        recommendationAdapter = null
        currentRecommendations = emptyList()
        currentRecommendationQuery = null
        chatAdapterUpdatePosted = false
        chatAdapterReady = false
        chatSnapshotSyncPending = false
        pendingChatMutations.clear()
        disposeChannelPointsIconRequest()
        channelPointsIconRequestGeneration++
        channelPointsIconUrl = null
        channelPointsIconLoaded = false
        channelPointsIconForeground = null
        disposeDropImageRequest()
        dropImageRequestGeneration++
        dropImageUrl = null
        dropImageTarget = null
        disposePinnedBadgeRequests()
        composerOverlayState = null
        pendingComposerText = null
        lastSlowModeUiState = SlowModeState()
        dropCalloutView = null
        dropImageView = null
        dropTitleView = null
        dropSubtitleView = null
        dropProgressView = null
        super.onDestroyView()
        _binding = null
    }

    private fun updateChannelPointsIcon(url: String?) {
        val icon = binding.channelPointsIcon
        if (channelPointsIconUrl == url) return
        disposeChannelPointsIconRequest()
        channelPointsIconUrl = url
        channelPointsIconLoaded = false
        val requestGeneration = ++channelPointsIconRequestGeneration
        icon.setImageResource(R.drawable.ic_channel_points_default)
        updateChannelPointsIconTint()
        if (url.isNullOrBlank()) return

        val context = requireContext()
        channelPointsIconRequest = context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .error(R.drawable.ic_channel_points_default)
                .target(icon)
                .listener(object : ImageRequest.Listener {
                    override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                        if (!isCurrentChannelPointsIconRequest(url, requestGeneration)) return
                        channelPointsIconLoaded = false
                        updateChannelPointsIconTint()
                    }

                    override fun onSuccess(request: ImageRequest, result: coil3.request.SuccessResult) {
                        if (!isCurrentChannelPointsIconRequest(url, requestGeneration)) return
                        channelPointsIconLoaded = true
                        updateChannelPointsIconTint()
                    }
                })
                .build(),
        )
    }

    private fun disposeChannelPointsIconRequest() {
        channelPointsIconRequest?.dispose()
        channelPointsIconRequest = null
    }

    private fun isCurrentChannelPointsIconRequest(url: String, requestGeneration: Int): Boolean {
        return _binding != null &&
            channelPointsIconUrl == url &&
            channelPointsIconRequestGeneration == requestGeneration
    }

    private fun updateChannelPointsIconTint() {
        val icon = _binding?.channelPointsIcon ?: return
        icon.imageTintList = if (channelPointsIconLoaded) {
            null
        } else if (channelPointsIconUrl.isNullOrBlank()) {
            ColorStateList.valueOf(requireContext().getColor(R.color.channel_points_default))
        } else {
            ColorStateList.valueOf(
                channelPointsIconForeground
                    ?: MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorControlNormal),
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        languageIdentifier?.close()
        translators.forEach {
            it.value.close()
        }
    }

    class SpaceTokenizer : MultiAutoCompleteTextView.Tokenizer {

        override fun findTokenStart(text: CharSequence, cursor: Int): Int {
            var i = cursor

            while (i > 0 && text[i - 1] != ' ') {
                i--
            }
            while (i < cursor && text[i] == ' ') {
                i++
            }

            return i
        }

        override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
            var i = cursor
            val len = text.length

            while (i < len) {
                if (text[i] == ' ') {
                    return i
                } else {
                    i++
                }
            }

            return len
        }

        override fun terminateToken(text: CharSequence): CharSequence {
            return "${if (text.startsWith(':')) text.substring(1) else text} "
        }
    }

    private fun restoreChatV2ViewportState(savedState: Bundle?): ChatViewportState {
        if (savedState == null) return ChatViewportState()
        val followMode = runCatching {
            com.github.andreyasadchy.xtra.ui.chat.v2.ui.FollowMode.valueOf(
                savedState.getString(KEY_V2_FOLLOW_MODE).orEmpty(),
            )
        }.getOrDefault(com.github.andreyasadchy.xtra.ui.chat.v2.ui.FollowMode.FOLLOWING_BOTTOM)
        val anchorId = savedState.getString(KEY_V2_ANCHOR_ID)?.takeIf { it.isNotBlank() }
        return ChatViewportState(
            followMode = followMode,
            newMessageCount = savedState.getInt(KEY_V2_NEW_MESSAGE_COUNT, 0),
            anchor = anchorId?.let {
                com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatViewportAnchor(
                    messageId = ChatMessageId(it),
                    topOffsetPx = savedState.getInt(KEY_V2_ANCHOR_OFFSET, 0),
                )
            },
        )
    }

    companion object {
        private const val KEY_IS_LIVE = "isLive"
        internal const val KEY_CHANNEL_ID = "channel_id"
        internal const val KEY_CHANNEL_LOGIN = "channel_login"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_CHAT_URL = "chatUrl"
        private const val KEY_START_TIME = "startTime"
        private const val KEY_COMPOSER_DRAFT = "composerDraft"
        private const val KEY_SEEN_PINNED_MESSAGE_ID = "seenPinnedMessageId"
        private const val KEY_DISPLAYED_PINNED_MESSAGE_ID = "displayedPinnedMessageId"
        private const val KEY_PINNED_MESSAGE_MINIMIZED = "pinnedMessageMinimized"
        private const val KEY_V2_FOLLOW_MODE = "chatV2FollowMode"
        private const val KEY_V2_NEW_MESSAGE_COUNT = "chatV2NewMessageCount"
        private const val KEY_V2_ANCHOR_ID = "chatV2AnchorId"
        private const val KEY_V2_ANCHOR_OFFSET = "chatV2AnchorOffset"

        fun newInstance(
            channelId: String?,
            channelLogin: String?,
            channelName: String?,
            streamId: String?,
        ): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, true)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CHANNEL_NAME, channelName)
                    putString(KEY_STREAM_ID, streamId)
                }
            }
        }

        fun newInstance(channelId: String?, channelLogin: String?, videoId: String?, createdAt: String?, startTime: Int?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, false)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_VIDEO_ID, videoId)
                    putString(KEY_CREATED_AT, createdAt)
                    putInt(KEY_START_TIME, (startTime ?: -1))
                }
            }
        }

        fun newLocalInstance(channelId: String?, channelLogin: String?, createdAt: String?, chatUrl: String?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CREATED_AT, createdAt)
                    putString(KEY_CHAT_URL, chatUrl)
                }
            }
        }
    }
}

internal fun shouldCaptureReplayComposerState(mode: ChatViewModel.ActiveChatMode): Boolean =
    mode !is ChatViewModel.ActiveChatMode.VideoReplay

internal enum class ChatMutationAction {
    IGNORE,
    APPLY_INCREMENTAL,
    SYNCHRONIZE_SNAPSHOT,
}

internal fun expectedChatMutationRevision(
    displayedRevision: Long,
    pendingRevision: Long?,
): Long = pendingRevision ?: displayedRevision

internal fun coalesceChatAppendMutations(
    mutations: List<ChatViewModel.ChatMutation.Append>,
): ChatViewModel.ChatMutation.Append {
    require(mutations.isNotEmpty())
    return ChatViewModel.ChatMutation.Append(
        revision = mutations.last().revision,
        messages = mutations.flatMap { it.messages },
        trimCount = mutations.sumOf { it.trimCount },
    )
}

internal fun chatMutationAction(displayedRevision: Long, mutationRevision: Long): ChatMutationAction =
    when {
        mutationRevision <= displayedRevision -> ChatMutationAction.IGNORE
        mutationRevision == displayedRevision + 1 -> ChatMutationAction.APPLY_INCREMENTAL
        else -> ChatMutationAction.SYNCHRONIZE_SNAPSHOT
    }

internal fun shouldSynchronizeChatSnapshot(displayedRevision: Long, snapshotRevision: Long): Boolean =
    snapshotRevision > displayedRevision


