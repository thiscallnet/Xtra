package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.use
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.PollVoteState
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.PredictionBetState
import com.github.andreyasadchy.xtra.model.ui.ChannelPoints
import com.github.andreyasadchy.xtra.model.ui.ChannelPointReward
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRedemptionResult
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.WatchStreak
import com.github.andreyasadchy.xtra.model.ui.WatchStreakShareResult
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.Companion.ChatViewModelFactory
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

class ChatFragment : BaseNetworkFragment(), MessageClickedDialog.OnButtonClickListener, ReplyClickedDialog.OnButtonClickListener, ChannelPointsDialog.Listener {

    private sealed interface ComposerOverlayState {
        data class Reward(val reward: ChannelPointReward) : ComposerOverlayState
        data class StreakShare(val streak: WatchStreak) : ComposerOverlayState
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels { ChatViewModelFactory }
    private var adapter: ChatAdapter? = null

    var chatMessageListener: ((ChatMessage) -> Unit)? = null
    var chatHistoryListener: ((List<ChatMessage>) -> Unit)? = null

    private var isChatTouched = false
    private var showChatStatus = false
    private var messagingEnabled = false
    private var channelPointsIconUrl: String? = null
    private var channelPointsIconRequest: Disposable? = null
    private var channelPointsIconRequestGeneration = 0
    private var channelPointsIconLoaded = false
    private var channelPointsIconForeground: Int? = null
    private var channelPointsAccessibilityLabel: String? = null
    private var composerOverlayState: ComposerOverlayState? = null
    private var pendingComposerText: String? = null
    private var composerSubmissionInProgress = false
    private var composerTextBeforeOverlay: String? = null
    private var composerSelectionBeforeOverlay: Int? = null
    private var messageViewWasVisibleBeforeOverlay: Boolean? = null
    private var lastSlowModeUiState = SlowModeState()
    private var chatScrollPosted = false
    private var chatAdapterUpdatePosted = false
    private val pendingChatMutations = ArrayDeque<ChatViewModel.ChatMutation>()
    private var chatMutationRevision = 0L
    private val chatScrollRunnable = Runnable {
        chatScrollPosted = false
        val currentBinding = _binding ?: return@Runnable
        if (!isChatTouched && currentBinding.btnDown.isGone) {
            val lastIndex = adapter?.itemCount?.minus(1) ?: RecyclerView.NO_POSITION
            currentBinding.recyclerView.scrollToPosition(lastIndex)
        }
    }
    private val chatAdapterUpdateRunnable = Runnable {
        chatAdapterUpdatePosted = false
        val currentBinding = _binding ?: return@Runnable
        val currentAdapter = adapter ?: return@Runnable
        var hasNewMessages = false
        while (pendingChatMutations.isNotEmpty()) {
            when (val firstMutation = pendingChatMutations.removeFirst()) {
                is ChatViewModel.ChatMutation.Append -> {
                    val messages = ArrayList<ChatMessage>(firstMutation.messages.size)
                    messages.addAll(firstMutation.messages)
                    var trimCount = firstMutation.trimCount
                    var revision = firstMutation.revision
                    while (pendingChatMutations.firstOrNull() is ChatViewModel.ChatMutation.Append) {
                        val next = pendingChatMutations.removeFirst() as ChatViewModel.ChatMutation.Append
                        messages.addAll(next.messages)
                        trimCount += next.trimCount
                        revision = next.revision
                    }
                    currentAdapter.appendMessages(messages, trimCount)
                    chatMutationRevision = revision
                    hasNewMessages = true
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
                    currentAdapter.clearMessages()
                    chatMutationRevision = firstMutation.revision
                }
            }
        }
        if (hasNewMessages && !isChatTouched && currentBinding.btnDown.isGone) {
            scheduleChatScrollToEnd()
        }
    }

    private var autoCompleteAdapter: AutoCompleteAdapter<Any>? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            toggleEmoteMenu(false)
        }
    }

    private val messageDialog: MessageClickedDialog?
        get() = childFragmentManager.findFragmentByTag("messageDialog") as? MessageClickedDialog

    private val replyDialog: ReplyClickedDialog?
        get() = childFragmentManager.findFragmentByTag("replyDialog") as? ReplyClickedDialog

    private fun scheduleChatScrollToEnd() {
        if (chatScrollPosted) return
        val recyclerView = _binding?.recyclerView ?: return
        chatScrollPosted = true
        recyclerView.postOnAnimation(chatScrollRunnable)
    }

    private fun scheduleChatAdapterUpdate() {
        if (chatAdapterUpdatePosted) return
        val recyclerView = _binding?.recyclerView ?: return
        chatAdapterUpdatePosted = true
        recyclerView.postDelayed(chatAdapterUpdateRunnable, CHAT_UPDATE_BATCH_MS)
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEmotePickerSizing()
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collectLatest { state ->
                    val showConnectionStatus = state == ChatViewModel.ConnectionState.RECONNECTING
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
            if (requireContext().prefs().isChatEnabled()) {
                val args = requireArguments()
                val channelId = args.getString(KEY_CHANNEL_ID)
                val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
                val isLive = args.getBoolean(KEY_IS_LIVE)
                val accountLogin = requireContext().tokenPrefs().getString(C.USERNAME, null)
                val isLoggedIn = !accountLogin.isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                val chatUrl = args.getString(KEY_CHAT_URL)
                if (isLive || (args.getString(KEY_VIDEO_ID) != null && args.getInt(KEY_START_TIME) != -1) || chatUrl != null) {
                    val enableMessaging = isLive && isLoggedIn
                    val sizeModifier = (requireContext().prefs().getInt(C.CHAT_SIZE_MODIFIER, 100).toFloat() / 100f)
                    adapter = ChatAdapter(
                        initialMessages = viewModel.chatSnapshot().also { chatMutationRevision = it.revision }.messages,
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
                        rewardChatMsg = getString(R.string.chat_reward),
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
                        messageTextSize = (requireContext().prefs().getString(C.CHAT_TEXT_SIZE, "14")?.toFloatOrNull() ?: 14f) * sizeModifier,
                        emoteSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (requireContext().prefs().getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * sizeModifier, resources.displayMetrics).toInt(),
                        badgeSize = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            chatBadgeSizeOrDefault(requireContext().prefs().getString(C.CHAT_BADGE_SIZE, DEFAULT_CHAT_BADGE_SIZE_DP.toString())) * sizeModifier,
                            resources.displayMetrics,
                        ).toInt(),
                        inlineIconSize = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            DEFAULT_CHAT_BADGE_SIZE_DP * sizeModifier,
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
                            MessageClickedDialog.newInstance(enableMessaging, channelId).show(this@ChatFragment.childFragmentManager, "messageDialog")
                        },
                        replyClickListener = {
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ReplyClickedDialog.newInstance(enableMessaging).show(this@ChatFragment.childFragmentManager, "replyDialog")
                        },
                        imageClickListener = { url, name, format, isAnimated, source, thirdParty, emoteId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ImageClickedDialog.newInstance(url, name, format, isAnimated, source, thirdParty, emoteId).show(this@ChatFragment.childFragmentManager, "imageDialog")
                        },
                    )
                    recyclerView.let {
                        it.adapter = adapter
                        it.itemAnimator = null
                        it.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                                isChatTouched = newState != RecyclerView.SCROLL_STATE_IDLE
                                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                                    recyclerView.removeCallbacks(chatAdapterUpdateRunnable)
                                    chatAdapterUpdatePosted = false
                                }
                                adapter?.setAnimationsPaused(newState != RecyclerView.SCROLL_STATE_IDLE)
                                adapter?.setRenderUpdatesPaused(newState != RecyclerView.SCROLL_STATE_IDLE)
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
                                if (newState == RecyclerView.SCROLL_STATE_IDLE && pendingChatMutations.isNotEmpty()) {
                                    scheduleChatAdapterUpdate()
                                }
                            }
                        })
                    }
                    btnDown.setOnClickListener {
                        view.post {
                            val lastIndex = adapter?.itemCount?.minus(1) ?: RecyclerView.NO_POSITION
                            recyclerView.scrollToPosition(lastIndex)
                            it.visibility = View.GONE
                        }
                    }
                    if (enableMessaging) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.channelPoints.collectLatest { points ->
                                    if (points != null) {
                                        val balance = NumberFormat.getInstance().format(points.balance)
                                        channelPointsText.text = balance
                                        channelPointsAccessibilityLabel = getString(
                                            R.string.channel_points_balance,
                                            balance,
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
                        editText.addTextChangedListener(onTextChanged = { text, _, _, _ ->
                            updateComposerButtons()
                        })
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
                        if ((view.parent?.parent?.parent?.parent as? View)?.id == R.id.slidingLayout && !requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                            messageView.visibility = View.GONE
                        } else {
                            messageView.visibility = View.VISIBLE
                        }
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
                            if (childFragmentManager.findFragmentByTag(ChannelPointsDialog.TAG) == null) {
                                ChannelPointsDialog().show(childFragmentManager, ChannelPointsDialog.TAG)
                            }
                        }
                        messagingEnabled = true
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
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.chatMutations.collect { mutation ->
                                if (mutation.revision <= chatMutationRevision) return@collect
                                pendingChatMutations.addLast(mutation)
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
                                if (!isChatTouched) scheduleChatAdapterUpdate()
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.updateUserMessages.collectLatest { userId ->
                                adapter?.let { adapter ->
                                    adapter.notifyUserMessages(userId)
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
        savedInstanceState?.getString(KEY_COMPOSER_DRAFT)?.takeIf { it.isNotEmpty() }?.let { draft ->
            binding.editText.setText(draft)
            binding.editText.setSelection(binding.editText.length())
            updateComposerButtons()
        }
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

    override fun initialize() {
        if (requireContext().prefs().isChatEnabled()) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.startLive(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel",
                    channelId,
                    channelLogin,
                    args.getString(KEY_CHANNEL_NAME),
                    args.getString(KEY_STREAM_ID),
                )
            } else {
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
        }
    }

    override fun onResume() {
        super.onResume()
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID)
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
        if (args.getBoolean(KEY_IS_LIVE)) {
            viewModel.resumeLive(channelId, channelLogin)
        } else {
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
    }

    fun isActive(): Boolean? {
        return viewModel.isActive()
    }

    fun disconnect() {
        viewModel.disconnect()
    }

    fun reconnect() {
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        if (channelLogin != null) {
            viewModel.startLiveChat(
                requireArguments().getString(KEY_CHANNEL_ID),
                channelLogin,
            )
            if (requireContext().prefs().getBoolean(C.CHAT_RECENT, true)) {
                viewModel.loadRecentMessages(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel",
                    channelLogin,
                )
            }
        }
        viewModel.autoReconnect = true
    }

    fun reloadEmotes() {
        viewModel.reloadEmotes(
            requireArguments().getString(KEY_CHANNEL_ID),
            requireArguments().getString(KEY_CHANNEL_LOGIN)
        )
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

    fun toggleEmoteMenu(enable: Boolean) {
        if (enable) {
            binding.emoteMenu.visibility = View.VISIBLE
            binding.emoteMenu.post { updateEmotePickerHeight() }
        } else {
            binding.emoteMenu.visibility = View.GONE
        }
        toggleBackPressedCallback(enable)
    }

    fun toggleBackPressedCallback(enable: Boolean) {
        if (enable) {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        } else {
            backPressedCallback.remove()
        }
    }

    fun appendEmote(emote: Emote) {
        binding.editText.text.append(emote.name).append(' ')
    }

    private fun resetMessageComposerAction() {
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
    }

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

    private data class ActivityVisualState(
        val background: Int?,
        val foreground: Int,
        val description: String,
        val alpha: Float,
    )

    private fun updateComposerDensity() {
        if (_binding == null || binding.messageView.width <= 0) return
        val compactWidth = (320 * resources.displayMetrics.density).toInt()
        binding.channelPointsText.isVisible = binding.channelPoints.isVisible && binding.messageView.width >= compactWidth
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
        if (composerOverlayState == null) {
            composerTextBeforeOverlay = binding.editText.text.toString()
            composerSelectionBeforeOverlay = binding.editText.selectionStart.takeIf { it >= 0 }
            messageViewWasVisibleBeforeOverlay = binding.messageView.isVisible
        }
        composerOverlayState = state
        pendingComposerText = null
        composerSubmissionInProgress = false
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
                    updateComposerOverlayIcon(state.reward.imageUrl, R.drawable.ic_channel_points)
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

    private fun cancelComposerOverlay() {
        val textBeforeOverlay = composerTextBeforeOverlay
        val selectionBeforeOverlay = composerSelectionBeforeOverlay
        val messageViewWasVisible = messageViewWasVisibleBeforeOverlay
        composerOverlayState = null
        pendingComposerText = null
        composerSubmissionInProgress = false
        composerTextBeforeOverlay = null
        composerSelectionBeforeOverlay = null
        messageViewWasVisibleBeforeOverlay = null
        binding.channelPointRewardOverlay.isGone = true
        textBeforeOverlay?.let { text ->
            binding.editText.setText(text)
            binding.editText.setSelection(
                (selectionBeforeOverlay ?: binding.editText.length()).coerceIn(0, binding.editText.length()),
            )
        }
        messageViewWasVisible?.let { binding.messageView.isVisible = it }
        updateComposerButtons()
    }

    private fun restorePendingComposerText() {
        pendingComposerText?.let { text ->
            binding.editText.setText(text)
            binding.editText.setSelection(binding.editText.length())
        }
        pendingComposerText = null
        composerSubmissionInProgress = false
        updateComposerButtons()
    }

    private fun handleChannelPointRedemption(result: ChannelPointRedemptionResult) {
        val overlay = composerOverlayState
        if (overlay is ComposerOverlayState.Reward && overlay.reward.id == result.rewardId) {
            if (result.success) {
                cancelComposerOverlay()
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
        val overlay = composerOverlayState
        if (overlay is ComposerOverlayState.StreakShare && overlay.streak.milestoneId == result.milestoneId) {
            if (result.success) {
                cancelComposerOverlay()
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

    private fun sendMessage(replyId: String? = null): Boolean {
        with(binding) {
            val overlay = composerOverlayState
            if (overlay != null) {
                if (composerSubmissionInProgress) {
                    return false
                }
                val text = editText.text.trim().toString()
                if (overlay is ComposerOverlayState.Reward && text.isBlank()) {
                    return false
                }
                pendingComposerText = text
                composerSubmissionInProgress = true
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
            resetMessageComposerAction()
            val text = editText.text.trim()
            editText.text.clear()
            return if (text.isNotEmpty()) {
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
                )
                val lastIndex = synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.lastIndex
                }
                recyclerView.scrollToPosition(lastIndex)
                true
            } else {
                false
            }
        }
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter? {
        return adapter?.createMessageClickedChatAdapter()
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter? {
        return adapter?.createReplyClickedChatAdapter()
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        with(binding) {
            if (!replyId.isNullOrBlank()) {
                cancelComposerOverlay()
                messageDialog?.dismiss()
                replyView.visibility = View.VISIBLE
                replyText.text = message?.let {
                    val name = if (userName != null && userLogin != null && !userLogin.equals(userName, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${userName}(${userLogin})"
                            "1" -> userName
                            else -> userLogin
                        }
                    } else {
                        userName ?: userLogin
                    }
                    getString(R.string.replying_to_message, name, message)
                }
                replyClose.setOnClickListener {
                    resetMessageComposerAction()
                }
                send.setOnClickListener { sendMessage(replyId) }
                editText.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                        val sent = sendMessage(replyId)
                        sent || viewModel.isSlowModeBlocked()
                    } else {
                        false
                    }
                }
                editText.setOnEditorActionListener { _, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_SEND ||
                        event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                    ) {
                        val sent = sendMessage(replyId)
                        sent || viewModel.isSlowModeBlocked()
                    } else {
                        false
                    }
                }
            }
            editText.apply {
                requestFocus()
                WindowCompat.getInsetsController(this@ChatFragment.requireActivity().window, this).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    override fun onCopyMessageClicked(message: String) {
        binding.editText.setText(message)
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
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
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
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
                    .addOnFailureListener {
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translate_failed, languageName)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = sourceLanguage
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
            }
        } else {
            val previousTranslation = chatMessage.translatedMessage
            chatMessage.translatedMessage = getString(R.string.translate_failed_id)
            chatMessage.translationFailed = true
            chatMessage.messageLanguage = null
            adapter?.let { adapter ->
                synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                }?.let {
                    (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                        adapter.updateTranslation(chatMessage, it, previousTranslation)
                    } ?: adapter.notifyItemChanged(it)
                }
            }
            messageDialog?.updateTranslation(chatMessage, previousTranslation)
            replyDialog?.updateTranslation(chatMessage, previousTranslation)
        }
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
                                    adapter?.let { adapter ->
                                        synchronized(viewModel.chatMessages) {
                                            viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                                        }?.let {
                                            (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                                adapter.updateTranslation(chatMessage, it, previousTranslation)
                                            } ?: adapter.notifyItemChanged(it)
                                        }
                                    }
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
            if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.resumeLive(channelId, channelLogin)
            } else {
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
        }
    }

    override fun onStop() {
        super.onStop()
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)) {
            viewModel.stopLiveChat()
            viewModel.stopReplayChat()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_COMPOSER_DRAFT, _binding?.editText?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        _binding?.recyclerView?.removeCallbacks(chatScrollRunnable)
        _binding?.recyclerView?.removeCallbacks(chatAdapterUpdateRunnable)
        chatScrollPosted = false
        chatAdapterUpdatePosted = false
        pendingChatMutations.clear()
        disposeChannelPointsIconRequest()
        channelPointsIconRequestGeneration++
        channelPointsIconUrl = null
        channelPointsIconLoaded = false
        channelPointsIconForeground = null
        composerOverlayState = null
        pendingComposerText = null
        composerSubmissionInProgress = false
        composerTextBeforeOverlay = null
        composerSelectionBeforeOverlay = null
        messageViewWasVisibleBeforeOverlay = null
        lastSlowModeUiState = SlowModeState()
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
        icon.setImageResource(R.drawable.ic_channel_points)
        updateChannelPointsIconTint()
        if (url.isNullOrBlank()) return

        val context = requireContext()
        channelPointsIconRequest = context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .error(R.drawable.ic_channel_points)
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

    companion object {
        private const val CHAT_UPDATE_BATCH_MS = 32L
        private const val KEY_IS_LIVE = "isLive"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_LOGIN = "channel_login"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_CHAT_URL = "chatUrl"
        private const val KEY_START_TIME = "startTime"
        private const val KEY_COMPOSER_DRAFT = "composerDraft"

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


