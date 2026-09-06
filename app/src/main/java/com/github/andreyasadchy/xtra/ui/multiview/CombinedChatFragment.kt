package com.github.andreyasadchy.xtra.ui.multiview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CombinedChatListItemBinding
import com.github.andreyasadchy.xtra.databinding.FragmentCombinedChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage as LegacyChatMessage
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatAdapterConfiguration
import com.github.andreyasadchy.xtra.ui.chat.ChatInteractionAdapterFactory
import com.github.andreyasadchy.xtra.ui.chat.ChatProfilePopoutGesture
import com.github.andreyasadchy.xtra.ui.chat.resolveChatHighlightSettings
import com.github.andreyasadchy.xtra.ui.chat.ImageClickedDialog
import com.github.andreyasadchy.xtra.ui.chat.MessageClickedChatAdapter
import com.github.andreyasadchy.xtra.ui.chat.MessageClickedDialog
import com.github.andreyasadchy.xtra.ui.chat.ReplyClickedChatAdapter
import com.github.andreyasadchy.xtra.ui.chat.ReplyClickedDialog
import com.github.andreyasadchy.xtra.ui.multiview.chat.CombinedChatMessage
import com.github.andreyasadchy.xtra.ui.multiview.chat.CombinedChatViewModel
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage as V2ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationLabels
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DEFAULT_CHAT_BADGE_SIZE_DP
import com.github.andreyasadchy.xtra.util.chatBadgeSizeOrDefault
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class CombinedChatFragment : Fragment(R.layout.fragment_combined_chat),
    MessageClickedDialog.OnButtonClickListener,
    ReplyClickedDialog.OnButtonClickListener {
    private var _binding: FragmentCombinedChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CombinedChatViewModel by viewModels { CombinedChatViewModel.Factory }
    private lateinit var adapter: CombinedChatAdapter
    private var filterIdentity: String? = null
    private var currentStreams: List<Stream> = emptyList()
    private var interactionAdapter: ChatInteractionAdapterFactory? = null
    private var interactionIdentity: String? = null
    private var selectedV2Message: V2ChatMessage? = null
    private var languageIdentifier: LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()
    private val v2Translations = mutableMapOf<String, String>()
    private var renderPosted = false
    private var submitJob: Job? = null
    private data class PendingSubmission(val items: List<CombinedChatMessage>, val forceScroll: Boolean)
    private var pendingSubmission: PendingSubmission? = null
    private val renderRunnable = Runnable {
        renderPosted = false
        val layoutManager = _binding?.combinedChatRecyclerView?.layoutManager as? LinearLayoutManager
        if (layoutManager != null && _binding != null) {
            val wasAtBottom = isAtBottom(layoutManager)
            submitMessages(forceScroll = CombinedChatPresentationPolicy.shouldAutoScroll(wasAtBottom))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCombinedChatBinding.bind(view)
        val streams = requireArguments().parcelableArrayList<Stream>(ARG_STREAMS).orEmpty()
        currentStreams = streams
        viewModel.ensureStreams(streams)
        setupFilters()

        adapter = CombinedChatAdapter(this, viewModel)
        val layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.combinedChatRecyclerView.layoutManager = layoutManager
        binding.combinedChatRecyclerView.adapter = adapter
        binding.combinedChatRecyclerView.itemAnimator = null
        submitMessages(forceScroll = true)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updates.collect {
                    scheduleMessagesRender()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.streamInfoUpdates.collectLatest { updates ->
                    val multiview = parentFragment as? MultiviewFragment
                    updates.values.forEach { update ->
                        multiview?.updateViewingMetadata(
                            channelId = viewModel.channelId(update.identity),
                            channelLogin = currentStreams.firstOrNull {
                                stableIdentity(it) == update.identity
                            }?.channelLogin,
                            title = update.title,
                            categoryId = update.categoryId,
                            categoryName = update.categoryName,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshChatHighlightSettings()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    fun updateStreams(streams: List<Stream>) {
        currentStreams = streams
        viewModel.ensureStreams(streams)
        if (_binding != null) {
            if (filterIdentity !in viewModel.channelNames().map { it.first }) filterIdentity = null
            setupFilters()
            submitMessages(forceScroll = true)
        }
    }

    private fun setupFilters() {
        binding.channelFilters.removeAllViews()
        val all = Chip(requireContext()).apply {
            id = View.generateViewId()
            text = getString(R.string.multiview_all_chats)
            isCheckable = true
            contentDescription = getString(R.string.multiview_all_chats)
        }
        binding.channelFilters.addView(all)
        all.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filterIdentity = null
                submitMessages(forceScroll = true)
            }
        }
        viewModel.channelNames().forEach { (identity, name) ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                tag = identity
                text = name
                isCheckable = true
                contentDescription = name
            }
            binding.channelFilters.addView(chip)
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    filterIdentity = identity
                    submitMessages(forceScroll = true)
                }
            }
        }
        val selectedId = if (filterIdentity == null) {
            all.id
        } else {
            binding.channelFilters.findViewWithTag<Chip>(filterIdentity)?.id ?: all.id
        }
        binding.channelFilters.check(selectedId)
    }

    private fun submitMessages(forceScroll: Boolean) {
        _binding?.combinedChatRecyclerView?.removeCallbacks(renderRunnable)
        renderPosted = false
        if (!::adapter.isInitialized || _binding == null) return
        val submission = PendingSubmission(viewModel.snapshot(filterIdentity), forceScroll)
        pendingSubmission = pendingSubmission?.let {
            submission.copy(forceScroll = it.forceScroll || submission.forceScroll)
        } ?: submission
        if (submitJob?.isActive == true) return
        submitJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val nextSubmission = pendingSubmission ?: break
                pendingSubmission = null
                if (pendingSubmission != null) continue
                if (_binding == null) return@launch
                adapter.submitList(nextSubmission.items) {
                    binding.combinedChatEmpty.isVisible = nextSubmission.items.isEmpty()
                    if (nextSubmission.forceScroll && nextSubmission.items.isNotEmpty()) {
                        binding.combinedChatRecyclerView.scrollToPosition(nextSubmission.items.lastIndex)
                    }
                }
                binding.combinedChatEmpty.isVisible = nextSubmission.items.isEmpty()
            }
            submitJob = null
        }
    }

    private fun scheduleMessagesRender() {
        val recyclerView = _binding?.combinedChatRecyclerView ?: return
        if (renderPosted) return
        renderPosted = true
        recyclerView.postOnAnimation(renderRunnable)
    }

    private fun isAtBottom(layoutManager: LinearLayoutManager): Boolean {
        return adapter.itemCount == 0 ||
            layoutManager.findLastCompletelyVisibleItemPosition() >= adapter.itemCount - 1
    }

    fun openMessageInteraction(identity: String, message: V2ChatMessage) {
        interactionIdentity = identity
        selectedV2Message = message
        interactionAdapter = adapter.createInteractionAdapter(identity, viewModel.snapshot(identity).map { it.message })
        if (childFragmentManager.findFragmentByTag(COMBINED_MESSAGE_DIALOG_TAG) == null) {
            val channelId = viewModel.channelId(identity)
            val channelLogin = currentStreams.firstOrNull { stableIdentity(it) == identity }?.channelLogin
            MessageClickedDialog.newInstance(
                messagingEnabled = false,
                channelId = channelId,
                channelLogin = channelLogin,
            )
                .show(childFragmentManager, COMBINED_MESSAGE_DIALOG_TAG)
        }
    }

    internal fun openReplyInteraction(chatAdapter: ChatInteractionAdapterFactory) {
        interactionAdapter = chatAdapter
        if (childFragmentManager.findFragmentByTag(COMBINED_REPLY_DIALOG_TAG) == null) {
            ReplyClickedDialog.newInstance(false)
                .show(childFragmentManager, COMBINED_REPLY_DIALOG_TAG)
        }
    }

    fun openImageInteraction(
        url: String?,
        name: String?,
        format: String?,
        isAnimated: Boolean?,
        source: Int?,
        thirdParty: Boolean?,
        emoteId: String?,
    ) {
        ImageClickedDialog.newInstance(url, name, format, isAnimated, source, thirdParty, emoteId)
            .show(childFragmentManager, COMBINED_IMAGE_DIALOG_TAG)
    }

    private fun stableIdentity(stream: Stream): String? {
        return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
            ?: stream.channelLogin?.trim()?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
    }

    private fun matchesV2MessageUser(message: V2ChatMessage, selected: V2ChatMessage): Boolean {
        val selectedUser = selected.user ?: return false
        val user = message.user ?: return false
        return (!selectedUser.id.isNullOrBlank() && user.id == selectedUser.id) ||
            (!selectedUser.login.isNullOrBlank() && user.login.equals(selectedUser.login, true))
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter? {
        val identity = interactionIdentity ?: return null
        val selected = selectedV2Message ?: return null
        val renderer = adapter.renderer(identity) ?: return null
        val canonical = viewModel.snapshot(identity).map { it.message }
        val history = if (selected.user != null) {
            canonical.filter { matchesV2MessageUser(it, selected) }
        } else {
            canonical
        }
        val legacyMessages = history.map(renderer::toLegacy)
        val selectedLegacy = legacyMessages.firstOrNull { it.id == selected.id.value } ?: renderer.toLegacy(selected)
        val rows = history.map(renderer::compile)
        return interactionAdapter?.createMessageClickedChatAdapter(
            sourceMessages = legacyMessages,
            selectedMessageOverride = selectedLegacy,
            v2Rows = rows,
            v2Assets = renderer.assets,
            v2EmoteClick = { interaction ->
                openImageInteraction(
                    interaction.url,
                    interaction.name,
                    interaction.url?.substringAfterLast('.', "webp"),
                    interaction.animated,
                    null,
                    interaction.provider != ChatAssetProvider.TWITCH,
                    interaction.id.takeIf { interaction.provider == ChatAssetProvider.TWITCH },
                )
            },
            v2GifClick = { interaction ->
                ImageClickedDialog.newGifInstance(interaction.url, interaction.description)
                    .show(childFragmentManager, COMBINED_IMAGE_DIALOG_TAG)
            },
        )
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter? {
        return interactionAdapter?.createReplyClickedChatAdapter()
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        // Combined chat is intentionally read-only, so the non-messaging dialog
        // action has no composer to open.
    }

    override fun onCopyMessageClicked(message: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.multiview_chat), message))
    }

    override fun onWhisperClicked(userLogin: String) {
        // Combined chat is intentionally read-only and has no message composer.
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelImage: String?) {
        runCatching {
            findNavController().navigate(
                com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
                    .actionGlobalChannelPagerFragment(
                        channelId = id,
                        channelLogin = login,
                        channelName = name,
                        channelImage = channelImage,
                    ),
            )
        }
    }

    override fun onTranslateMessageClicked(chatMessage: LegacyChatMessage, languageTag: String?) {
        translateMessage(chatMessage, languageTag, interactionIdentity)
    }

    fun onRendererTranslateMessage(chatMessage: LegacyChatMessage, languageTag: String?, identity: String) {
        interactionIdentity = identity
        translateMessage(chatMessage, languageTag, identity)
    }

    private fun translateMessage(chatMessage: LegacyChatMessage, languageTag: String?, identity: String?) {
        val message = chatMessage.message ?: chatMessage.systemMsg ?: return
        val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
        if (languageTag == null) {
            val identifier = languageIdentifier ?: LanguageIdentification.getClient().also { languageIdentifier = it }
            identifier.identifyLanguage(message)
                .addOnSuccessListener { detected -> translateMessage(chatMessage, detected, identity) }
                .addOnFailureListener {
                    chatMessage.translatedMessage = getString(R.string.translate_failed_id)
                    chatMessage.translationFailed = true
                    syncV2Translation(chatMessage)
                    viewModel.invalidateRendering(identity)
                }
            return
        }
        if (languageTag == "und" || languageTag == targetLanguage) return
        val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag) ?: return
        val translator = translators[sourceLanguage] ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build(),
        ).also {
            if (translators.size >= 3) {
                val first = translators.entries.firstOrNull()
                first?.value?.close()
                first?.key?.let(translators::remove)
            }
            translators[sourceLanguage] = it
        }
        val previousTranslation = chatMessage.translatedMessage
        translator.translate(message)
            .addOnSuccessListener { translated ->
                val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                chatMessage.translatedMessage = getString(R.string.translated_message, languageName, translated)
                chatMessage.translationFailed = false
                chatMessage.messageLanguage = null
                syncV2Translation(chatMessage)
                viewModel.invalidateRendering(identity)
                (childFragmentManager.findFragmentByTag(COMBINED_MESSAGE_DIALOG_TAG) as? MessageClickedDialog)
                    ?.updateTranslation(chatMessage, previousTranslation)
                (childFragmentManager.findFragmentByTag(COMBINED_REPLY_DIALOG_TAG) as? ReplyClickedDialog)
                    ?.updateTranslation(chatMessage, previousTranslation)
            }
            .addOnFailureListener {
                chatMessage.translatedMessage = getString(R.string.translate_failed, Locale.forLanguageTag(sourceLanguage).displayLanguage)
                chatMessage.translationFailed = true
                syncV2Translation(chatMessage)
                viewModel.invalidateRendering(identity)
            }
    }

    private fun syncV2Translation(chatMessage: LegacyChatMessage) {
        val id = chatMessage.id?.takeIf { it.isNotBlank() } ?: return
        chatMessage.translatedMessage?.let { v2Translations[id] = it }
            ?: v2Translations.remove(id)
    }

    internal fun translationFor(message: V2ChatMessage): String? = v2Translations[message.id.value]

    override fun onDestroyView() {
        _binding?.combinedChatRecyclerView?.removeCallbacks(renderRunnable)
        renderPosted = false
        interactionAdapter = null
        interactionIdentity = null
        languageIdentifier = null
        translators.values.forEach(Translator::close)
        translators.clear()
        pendingSubmission = null
        submitJob?.cancel()
        submitJob = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STREAMS = "combined_chat_streams"
        private const val COMBINED_MESSAGE_DIALOG_TAG = "combined_message_dialog"
        private const val COMBINED_REPLY_DIALOG_TAG = "combined_reply_dialog"
        private const val COMBINED_IMAGE_DIALOG_TAG = "combined_image_dialog"

        fun newInstance(streams: List<Stream>): CombinedChatFragment {
            return CombinedChatFragment().apply {
                arguments = Bundle().apply { putParcelableArrayList(ARG_STREAMS, ArrayList(streams)) }
            }
        }
    }
}

private class CombinedChatAdapter(
    private val fragment: CombinedChatFragment,
    private val viewModel: CombinedChatViewModel,
) : ListAdapter<CombinedChatMessage, CombinedChatAdapter.ViewHolder>(DIFF_CALLBACK) {
    private val assets = (fragment.requireContext().applicationContext as com.github.andreyasadchy.xtra.XtraApp)
        .xtraModule.chatAssetRepository
    private val profilePopoutGesture = ChatProfilePopoutGesture.fromPreference(
        fragment.requireContext().prefs().getString(C.CHAT_PROFILE_POPOUT_GESTURE, "tap"),
    )
    private val renderers = mutableMapOf<String, SessionRenderer>()

    fun renderer(identity: String): SessionRenderer? {
        val active = viewModel.session(identity) ?: return null
        return renderers[identity]
            ?.takeIf { it.isFor(active) }
            ?: SessionRenderer(fragment, active, identity, assets, viewModel::rewardCatalog, profilePopoutGesture).also {
                renderers[identity] = it
            }
    }

    fun refreshChatHighlightSettings() {
        if (!renderers.values.map { it.refreshChatHighlightSettings() }.any { it }) return
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun createInteractionAdapter(identity: String, messages: List<V2ChatMessage>): ChatInteractionAdapterFactory? {
        val renderer = renderer(identity) ?: return null
        return renderer.createInteractionAdapter(messages.map(renderer::toLegacy))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        CombinedChatListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.channelChip.text = item.channelName
        holder.binding.channelChip.contentDescription = item.channelName
        renderer(item.identity)?.let { renderer ->
            holder.bind(item, renderer, profilePopoutGesture)
        } ?: holder.clear()
        holder.binding.root.contentDescription = fragment.getString(
            R.string.multiview_combined_message_description,
            item.channelName,
            holder.binding.messageText.contentDescription ?: holder.binding.messageText.text,
        )
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.release()
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.binding.messageText.setRenderingActive(true)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.binding.messageText.setRenderingActive(false)
        super.onViewDetachedFromWindow(holder)
    }

    class ViewHolder(val binding: CombinedChatListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CombinedChatMessage, renderer: SessionRenderer, gesture: ChatProfilePopoutGesture) {
            val openProfile = { _: ChatMessageId ->
                renderer.fragment.openMessageInteraction(item.identity, item.message)
            }
            binding.messageText.setInteractionCallbacks(
                onMessageLongClick = openProfile.takeIf { gesture.allowsHold },
                onEmoteClick = { interaction ->
                    renderer.fragment.openImageInteraction(
                        interaction.url,
                        interaction.name,
                        interaction.url?.substringAfterLast('.', "webp"),
                        interaction.animated,
                        null,
                        interaction.provider != ChatAssetProvider.TWITCH,
                        interaction.id.takeIf { interaction.provider == ChatAssetProvider.TWITCH },
                    )
                },
                onGifClick = { interaction ->
                    ImageClickedDialog.newGifInstance(interaction.url, interaction.description)
                        .show(renderer.fragment.childFragmentManager, "combinedImageDialog")
                },
            )
            binding.messageText.setMessageClickCallback(openProfile.takeIf { gesture.allowsTap })
            binding.messageText.bind(renderer.compile(item.message))
        }

        fun clear() {
            binding.messageText.recycle()
            binding.messageText.setMessageClickCallback(null)
        }

        fun release() = clear()
    }

    class SessionRenderer(
        val fragment: CombinedChatFragment,
        private val active: com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession,
        private val identity: String,
        val assets: ChatAssetRepository,
        private val rewards: (String) -> com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatRewardCatalog,
        private val profileGesture: ChatProfilePopoutGesture,
    ) {
        private val context = fragment.requireContext()
        private val preferences = context.prefs()
        private val style = com.github.andreyasadchy.xtra.ui.chat.resolveChatRenderStyle(context)
        private val surface = MaterialColors.getColor(fragment.requireView(), com.google.android.material.R.attr.colorSurface)
        private var highlightSettings = resolveChatHighlightSettings(context)
        private fun createCompiler() = ChatRowCompiler(
            colors = ChatColorResolver(
                    readable = preferences.getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                    randomFallback = preferences.getBoolean(C.CHAT_RANDOM_COLOR, true),
                    neutralFallback = !preferences.getBoolean(C.CHAT_RANDOM_COLOR, true),
                    background = surface,
            ),
            emoteHeightPx = style.emoteHeightPx,
            badgeHeightPx = style.badgeHeightPx,
            showBadges = style.showBadges,
            enableOverlayEmotes = style.enableOverlayEmotes,
            firstMessageVisibility = style.firstMessageVisibility,
            boldNames = style.boldNames,
            nameDisplay = preferences.getString(C.UI_NAME_DISPLAY, "0") ?: "0",
            showSystemMessageEmotes = preferences.getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
            showNamePaints = preferences.getBoolean(C.CHAT_SHOW_PAINTS, true),
            showThirdPartyBadges = preferences.getBoolean(C.CHAT_SHOW_STV_BADGES, true),
            showPersonalEmotes = preferences.getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
            timestampText = if (style.showTimestamps) {
                { timestamp -> com.github.andreyasadchy.xtra.util.TwitchApiHelper.getTimestamp(timestamp, style.timestampFormat) }
            } else {
                { null }
            },
            translation = fragment::translationFor,
            background = { surface },
            labels = ChatPresentationLabels(
                firstChatter = fragment.getString(R.string.chat_first),
                redeemed = { reward -> fragment.getString(R.string.redeemed, reward) },
                userRedeemed = { reward -> fragment.getString(R.string.user_redeemed, "", reward).trimStart() },
                highlightTitle = fragment.getString(R.string.chat_highlight_title),
                highlightRedeemed = { title -> fragment.getString(R.string.chat_highlight_redeemed, title) },
                watchStreakReached = fragment.getString(R.string.chat_watch_streak_reached),
                watchStreakStatus = { user, count -> fragment.getString(R.string.chat_watch_streak_status, user, count) },
                raid = fragment.getString(R.string.chat_event_raid),
                notice = fragment.getString(R.string.chat_event_notice),
                anonymous = fragment.getString(R.string.chat_event_anonymous),
                viewer = fragment.getString(R.string.chat_event_viewer),
                reward = fragment.getString(R.string.chat_event_channel_points_reward),
                subscriptionPrime = fragment.getString(R.string.chat_subscription_prime),
                subscriptionPaid = { tier -> fragment.getString(R.string.chat_subscription_paid, tier) },
                subscriptionUpgrade = { tier -> fragment.getString(R.string.chat_subscription_upgrade, tier) },
                subscriptionGift = { tier, recipient -> fragment.getString(R.string.chat_subscription_gift, tier, recipient) },
                subscriptionCommunityGift = { count, tier -> fragment.resources.getQuantityString(R.plurals.chat_subscription_community_gift, count, count, tier) },
                subscriptionMonths = { months -> fragment.resources.getQuantityString(R.plurals.chat_subscription_months, months, months) },
                subscriptionStreak = { months -> fragment.resources.getQuantityString(R.plurals.chat_subscription_streak, months, months) },
                subscriptionAccessibilityMonths = { months -> fragment.resources.getQuantityString(R.plurals.chat_subscription_accessibility_months, months, months) },
                reply = { user, message -> fragment.getString(R.string.replying_to_message, user, message) },
                moderationSuffix = { moderation ->
                    when (moderation.reason) {
                        ChatUserClearReason.TIMEOUT -> fragment.getString(
                            R.string.chat_moderation_timeout,
                            com.github.andreyasadchy.xtra.util.TwitchApiHelper.getDurationFromSeconds(
                                fragment.requireContext(),
                                moderation.timeoutSeconds?.toString(),
                            ).orEmpty(),
                        )
                        ChatUserClearReason.BAN -> fragment.getString(R.string.chat_moderation_ban)
                        ChatUserClearReason.MESSAGES_CLEARED -> fragment.getString(R.string.chat_moderation_messages_cleared)
                        ChatUserClearReason.MESSAGE_DELETED -> "(${fragment.getString(R.string.chat_message_deleted)})"
                    }
                },
            ),
            gifDisplayMode = style.gifDisplayMode,
            highlightSettings = highlightSettings,
        )
        private val presentation = ChatPresentationResolver(createCompiler())

        fun refreshChatHighlightSettings(): Boolean {
            val next = resolveChatHighlightSettings(context)
            if (next == highlightSettings) return false
            highlightSettings = next
            presentation.replaceCompiler(createCompiler())
            return true
        }

        fun compile(message: V2ChatMessage): ChatRowUiModel = presentation.resolve(
            message = message,
            catalog = catalog(),
            // Translation state lives in the Fragment compatibility bridge while the
            // Multiview rows are being migrated. Include it in the row key so a completed
            // translation invalidates only that message's cached presentation.
            presentationRevision = fragment.translationFor(message)?.hashCode()?.toLong() ?: 0L,
        )

        private fun catalog() = rewards(identity).let { rewardCatalog ->
            active.catalog.state.value.snapshot.copy(
                channelPointRewards = rewardCatalog.byId,
                automaticChannelPointRewards = rewardCatalog.automaticByType,
                channelPointRewardsRevision = rewardCatalog.hashCode(),
            )
        }

        fun isFor(other: com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession): Boolean = active === other

        fun toLegacy(message: V2ChatMessage): LegacyChatMessage {
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
                LegacyChatMessage(
                    type = LegacyChatMessage.USER_MESSAGE,
                    id = it.parentMessageId.value,
                    userId = it.parentUserId,
                    userLogin = it.parentUserLogin,
                    userName = it.parentUserName,
                    message = it.parentMessageBody,
                )
            }
            val rewardCatalog = rewards(identity)
            val reward = rewardCatalog.rewardFor(message)?.let {
                com.github.andreyasadchy.xtra.model.chat.ChannelPointReward(
                    id = message.rewardId,
                    title = it.title,
                    cost = it.cost,
                    url1x = it.imageUrl,
                    url2x = it.imageUrl,
                    url4x = it.imageUrl,
                )
            }
            return LegacyChatMessage(
                type = if (message.user != null) LegacyChatMessage.USER_MESSAGE else LegacyChatMessage.SYSTEM_MESSAGE,
                id = message.id.value,
                userId = message.user?.id,
                userLogin = message.user?.login,
                userName = message.user?.displayName,
                message = text.takeIf { it.isNotEmpty() },
                isAction = message.kind == com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.ACTION,
                isFirst = message.isFirst,
                bits = message.bits,
                badges = message.badges.map { badge -> com.github.andreyasadchy.xtra.model.chat.Badge(badge.setId, badge.versionId) },
                systemMsg = message.systemText,
                msgId = message.noticeType,
                sourceMsgId = message.source?.messageId?.value,
                reward = reward,
                reply = reply,
                replyParent = replyParent,
                timestamp = message.timestampMs,
            ).apply {
                fragment.translationFor(message)?.let {
                    translatedMessage = it
                    translationFailed = it.contains(fragment.getString(R.string.translate_failed_id), ignoreCase = true)
                }
            }
        }

        fun createInteractionAdapter(initialMessages: List<LegacyChatMessage>): ChatInteractionAdapterFactory {
            val size = context.resources.displayMetrics.density
            val isLightTheme = context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).let { attributes ->
                try { attributes.getBoolean(0, false) } finally { attributes.recycle() }
            }
            return ChatInteractionAdapterFactory(ChatAdapterConfiguration(
                localTwitchEmotes = emptyList(),
                thirdPartyEmotes = emptyList(),
                globalBadges = emptyList(),
                channelBadges = emptyList(),
                cheerEmotes = emptyList(),
                namePaints = emptyList(),
                stvBadges = emptyList(),
                personalEmoteSets = emptyMap(),
                stvUsers = emptyList(),
                enableTimestamps = preferences.getBoolean(C.CHAT_TIMESTAMPS, false),
                timestampFormat = preferences.getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                firstMsgVisibility = style.firstMessageVisibility,
                firstChatMsg = fragment.getString(R.string.chat_first),
                redeemedChatMsg = fragment.getString(R.string.redeemed),
                redeemedNoMsg = fragment.getString(R.string.user_redeemed),
                replyMessage = fragment.getString(R.string.replying_to_message),
                useRandomColors = preferences.getBoolean(C.CHAT_RANDOM_COLOR, true),
                useReadableColors = preferences.getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                isLightTheme = isLightTheme,
                nameDisplay = preferences.getString(C.UI_NAME_DISPLAY, "0"),
                useBoldNames = preferences.getBoolean(C.CHAT_BOLD_NAMES, false),
                showNamePaints = preferences.getBoolean(C.CHAT_SHOW_PAINTS, true),
                showBadges = style.showBadges,
                showSTVBadges = preferences.getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                showPersonalEmotes = preferences.getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                showSystemMessageEmotes = preferences.getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                chatUrl = null,
                fragment = fragment,
                backgroundColor = surface,
                dialogBackgroundColor = MaterialColors.getColor(fragment.requireView(), com.google.android.material.R.attr.colorSurfaceContainerLow),
                imageLibrary = "0",
                messageTextSize = style.textSizeSp,
                emoteSize = style.emoteHeightPx,
                badgeSize = style.badgeHeightPx,
                inlineIconSize = (DEFAULT_CHAT_BADGE_SIZE_DP * size).toInt(),
                emoteQuality = "4",
                animateGifs = preferences.getBoolean(C.ANIMATED_EMOTES, true),
                enableOverlayEmotes = style.enableOverlayEmotes,
                translateMessage = { message, language -> fragment.onRendererTranslateMessage(message, language, identity) },
                showLanguageDownloadDialog = { _, _ -> },
                channelId = active.spec.channelId,
                loggedInUser = null,
                messageClickListener = null,
                replyClickListener = null,
                imageClickListener = { url, name, format, animated, source, thirdParty, emoteId ->
                    fragment.openImageInteraction(url, name, format, animated, source, thirdParty, emoteId)
                },
                profilePopoutGesture = profileGesture,
            ), initialMessages)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CombinedChatMessage>() {
            override fun areItemsTheSame(oldItem: CombinedChatMessage, newItem: CombinedChatMessage): Boolean =
                oldItem.sequence == newItem.sequence

            override fun areContentsTheSame(oldItem: CombinedChatMessage, newItem: CombinedChatMessage): Boolean =
                oldItem == newItem
        }
    }
}

private inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}
