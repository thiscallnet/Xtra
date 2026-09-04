package com.github.andreyasadchy.xtra.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChatMessageClickBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.chat.MessageClickedViewModel.Companion.MessageClickedViewModelFactory
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Instant

class MessageClickedDialog : BottomSheetDialogFragment() {

    interface OnButtonClickListener {
        fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter?
        fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?)
        fun onCopyMessageClicked(message: String)
        fun onViewProfileClicked(id: String?, login: String?, name: String?, channelImage: String?)
        fun onTranslateMessageClicked(chatMessage: ChatMessage, languageTag: String?)
        fun onWhisperClicked(userLogin: String)
    }

    companion object {
        private const val KEY_MESSAGING = "messaging"
        private const val KEY_CHANNEL_ID = "channelId"
        private const val KEY_CHANNEL_LOGIN = "channelLogin"
        private data class SavedUserCard(
            val user: User,
            val targetId: String?,
            val viewerId: String?,
        )
        private val savedUsers = mutableListOf<SavedUserCard>()
        private var selectedLanguage: String? = null

        fun newInstance(messagingEnabled: Boolean, channelId: String?, channelLogin: String?): MessageClickedDialog {
            return MessageClickedDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_MESSAGING, messagingEnabled)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                }
            }
        }
    }

    private var _binding: DialogChatMessageClickBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MessageClickedViewModel by viewModels { MessageClickedViewModelFactory }

    private lateinit var listener: OnButtonClickListener
    var adapter: MessageClickedChatAdapter? = null
    private var isChatTouched = false
    private var messageLimit: Int? = null
    private val badgeAdapter = UserCardBadgeAdapter()
    private var userCardUser: User? = null
    private var followRequestInFlight = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnButtonClickListener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogChatMessageClickBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            badgesRecyclerView.apply {
                layoutManager = GridLayoutManager(requireContext(), 6)
                adapter = badgeAdapter
                itemAnimator = null
                isNestedScrollingEnabled = false
            }
            adapter = listener.onCreateMessageClickedChatAdapter()
            recyclerView.let {
                it.adapter = adapter
                it.itemAnimator = null
                it.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                it.setOnTouchListener(object : View.OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> behavior.isDraggable = false
                            MotionEvent.ACTION_UP -> behavior.isDraggable = true
                        }
                        return false
                    }
                })
                it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        isChatTouched = newState == RecyclerView.SCROLL_STATE_DRAGGING
                    }
                })
            }
            adapter?.let { adapter ->
                adapter.messageClickListener = { selectedMessage, previousSelectedMessage ->
                    updateButtons(selectedMessage)
                    previousSelectedMessage?.let {
                        synchronized(adapter.messages) {
                            adapter.messages.indexOfFirst { message ->
                                if (!it.id.isNullOrBlank()) message.id == it.id else message === it
                            }.takeIf { it != -1 }
                        }?.let {
                            adapter.notifyItemChanged(it)
                        }
                    }
                }
                adapter.selectedMessage?.let { selectedMessage ->
                    updateButtons(selectedMessage)
                    synchronized(adapter.messages) {
                        adapter.messages.indexOf(selectedMessage).takeIf { it != -1 }
                    }?.let {
                        binding.recyclerView.scrollToPosition(it)
                    }
                    if (selectedMessage.userId != null || selectedMessage.userLogin != null) {
                        val targetId = requireArguments().getString(KEY_CHANNEL_ID)
                        val viewerId = currentViewerId()
                        val item = selectedMessage.userId?.let {
                            synchronized(savedUsers) {
                                savedUsers.find {
                                    it.user.id == selectedMessage.userId &&
                                        it.targetId == targetId &&
                                        it.viewerId == viewerId
                                }
                            }
                        }
                        if (item != null) {
                            userCardUser = item.user
                            updateUserLayout(item.user)
                            item.user.name?.let { channelName ->
                                if (requireArguments().getBoolean(KEY_MESSAGING) &&
                                    !selectedMessage.id.isNullOrBlank() &&
                                    selectedMessage.userName.isNullOrBlank() &&
                                    channelName.isNotBlank()
                                ) {
                                    reply.visibility = View.VISIBLE
                                    reply.setOnClickListener {
                                        listener.onReplyClicked(
                                            selectedMessage.id,
                                            selectedMessage.userLogin,
                                            channelName,
                                            selectedMessage.message
                                        )
                                        dismiss()
                                    }
                                }
                            }
                        } else {
                            previewUserFromMessage(selectedMessage)?.let { previewUser ->
                                userCardUser = previewUser
                                updateUserLayout(previewUser)
                            }
                            viewModel.loadUser(
                                channelId = selectedMessage.userId,
                                channelLogin = selectedMessage.userLogin,
                                targetId = targetId,
                                targetLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                                isSubscribedHint = selectedMessage.badges.orEmpty().any {
                                    it.setId.equals("subscriber", ignoreCase = true)
                                },
                            )
                            viewLifecycleOwner.lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.user.collectLatest { pair ->
                                        if (pair != null) {
                                            val user = pair.first
                                            val error = pair.second
                                            if (user != null) {
                                                userCardUser = user
                                                replaceSavedUser(user, targetId, currentViewerId())
                                                updateUserLayout(user)
                                                adapter.selectedMessage?.let { selectedMessage ->
                                                    if (requireArguments().getBoolean(KEY_MESSAGING) &&
                                                        !selectedMessage.id.isNullOrBlank() &&
                                                        selectedMessage.userName.isNullOrBlank() &&
                                                        !user.name.isNullOrBlank()
                                                    ) {
                                                        reply.visibility = View.VISIBLE
                                                        reply.setOnClickListener {
                                                            listener.onReplyClicked(
                                                                selectedMessage.id,
                                                                selectedMessage.userLogin,
                                                                user.name,
                                                                selectedMessage.message
                                                            )
                                                            dismiss()
                                                        }
                                                    }
                                                }
                                                viewModel.user.value = Pair(null, false)
                                            } else {
                                                if (error == true) {
                                                    viewProfile.visibility = View.VISIBLE
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        viewProfile.setOnClickListener {
                            listener.onViewProfileClicked(selectedMessage.userId, selectedMessage.userLogin, selectedMessage.userName, null)
                            dismiss()
                        }
                    }
                }
            }
            if (requireContext().prefs().getBoolean(C.DEBUG_CHAT_FULL_MSG, false)) {
                copyFullMsg.visibility = View.VISIBLE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.followResult.collectLatest { result ->
                    result?.let(::handleFollowResult)
                }
            }
        }
    }

    private fun previewUserFromMessage(message: ChatMessage): User? {
        if (message.userId.isNullOrBlank() && message.userLogin.isNullOrBlank() && message.userName.isNullOrBlank()) {
            return null
        }
        return User(
            id = message.userId,
            login = message.userLogin,
            name = message.userName,
            isSubscribed = message.badges.orEmpty().any {
                it.setId.equals("subscriber", ignoreCase = true)
            },
        )
    }

    private fun updateButtons(chatMessage: ChatMessage) {
        with(binding) {
            if (requireArguments().getBoolean(KEY_MESSAGING) && (!chatMessage.userId.isNullOrBlank() || !chatMessage.userLogin.isNullOrBlank())) {
                if (!chatMessage.id.isNullOrBlank()) {
                    reply.visibility = View.VISIBLE
                    reply.setOnClickListener {
                        listener.onReplyClicked(chatMessage.id, chatMessage.userLogin, chatMessage.userName, chatMessage.message)
                        dismiss()
                    }
                } else {
                    reply.visibility = View.GONE
                }
                if (!chatMessage.message.isNullOrBlank()) {
                    copyMessage.visibility = View.VISIBLE
                    copyMessage.setOnClickListener {
                        listener.onCopyMessageClicked(chatMessage.message)
                        dismiss()
                    }
                } else {
                    copyMessage.visibility = View.GONE
                }
            }
            val clipboard = getSystemService(requireContext(), ClipboardManager::class.java)
            copyClip.setOnClickListener {
                clipboard?.setPrimaryClip(ClipData.newPlainText("label", chatMessage.message))
                dismiss()
            }
            copyFullMsg.setOnClickListener {
                clipboard?.setPrimaryClip(ClipData.newPlainText("label", chatMessage.fullMsg))
                dismiss()
            }
            if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && (chatMessage.message != null || chatMessage.systemMsg != null) && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                translateMessage.visibility = View.VISIBLE
                translateMessage.setOnClickListener {
                    listener.onTranslateMessageClicked(chatMessage, null)
                }
                translateMessageSelectLanguage.visibility = View.VISIBLE
                translateMessageSelectLanguage.setOnClickListener {
                    val languages = TranslateLanguage.getAllLanguages()
                    val names = languages.map { Locale.forLanguageTag(it).displayName }.toTypedArray()
                    requireContext().getAlertDialogBuilder()
                        .setSingleChoiceItems(names, languages.indexOf(selectedLanguage)) { _, which ->
                            languages.getOrNull(which)?.let { language ->
                                selectedLanguage = language
                            }
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            selectedLanguage?.let {
                                listener.onTranslateMessageClicked(chatMessage, it)
                            }
                        }
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show()
                }
            } else {
                translateMessage.visibility = View.GONE
                translateMessageSelectLanguage.visibility = View.GONE
            }
        }
    }

    private fun updateUserLayout(user: User) {
        with(binding) {
            userLayout.isVisible = true
            viewProfile.isVisible = false

            user.profileImage?.let { imageUrl ->
                userImage.isVisible = true
                userImage.contentDescription = user.name?.let {
                    requireContext().getString(R.string.player_open_channel, it)
                }
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext())
                        .data(imageUrl)
                        .apply {
                            if (requireContext().prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                        }
                        .crossfade(true)
                        .target(userImage)
                        .build(),
                )
                userImage.setOnClickListener {
                    listener.onViewProfileClicked(user.id, user.login, user.name, imageUrl)
                    dismiss()
                }
            } ?: run {
                userImage.isVisible = false
                userImage.contentDescription = null
                userImage.setOnClickListener(null)
            }

            val displayName = when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                "2" -> user.login ?: user.name
                else -> user.name ?: user.login
            }
            userName.isVisible = !displayName.isNullOrBlank()
            userName.text = displayName
            userName.setOnClickListener {
                listener.onViewProfileClicked(user.id, user.login, user.name, user.profileImage)
                dismiss()
            }

            val login = user.login?.takeIf { !it.equals(displayName, true) }
            userLogin.isVisible = !login.isNullOrBlank()
            userLogin.text = login?.let { "@$it" }

            formatTwitchDate(user.createdAt)?.let { date ->
                userCreated.isVisible = true
                userCreated.text = getString(R.string.user_card_created, date)
            } ?: run {
                userCreated.isVisible = false
            }

            formatTwitchDate(user.followedAt)?.let { date ->
                userFollowed.isVisible = true
                userFollowed.text = getString(R.string.user_card_following_since, date)
            } ?: run {
                userFollowed.isVisible = false
            }

            val months = user.subscriptionMonths ?: 0
            userSubscription.isVisible = months > 0 || user.isSubscribed
            if (months > 0) {
                userSubscription.text = resources.getQuantityString(
                    if (user.isSubscribed) {
                        R.plurals.user_card_subscribed_months
                    } else {
                        R.plurals.user_card_previously_subbed_months
                    },
                    months,
                    months,
                )
            } else if (user.isSubscribed) {
                userSubscription.setText(R.string.user_card_subscribed)
            }

            val badges = user.displayBadges
            badgesTitle.isVisible = badges.isNotEmpty()
            badgesRecyclerView.isVisible = badges.isNotEmpty()
            badgesHeader.isVisible = badges.isNotEmpty()
            viewAllBadges.isVisible = badges.size > UserCardBadgeAdapter.COLLAPSED_COUNT
            badgeAdapter.submitBadges(badges)
            updateBadgeToggleText(badges.size)
            viewAllBadges.setOnClickListener {
                badgeAdapter.toggleExpanded()
                updateBadgeToggleText(badges.size)
            }

            renderUserActions(user)
        }
    }

    private fun formatTwitchDate(value: String?): String? {
        return value
            ?.let(Instant::parseOrNull)
            ?.toEpochMilliseconds()
            ?.takeIf { it > 0L }
            ?.let { TwitchApiHelper.formatDate(requireContext(), it) }
    }

    private fun updateBadgeToggleText(total: Int) {
        binding.viewAllBadges.text = if (badgeAdapter.expanded) {
            getString(R.string.user_card_show_less)
        } else {
            getString(R.string.user_card_view_all, total)
        }
    }

    private fun renderUserActions(user: User) = with(binding) {
        userActionRow.isVisible = true
        giftSubButton.setOnClickListener {
            Snackbar.make(binding.root, "TBD", Snackbar.LENGTH_SHORT).show()
        }

        val viewerId = requireContext().tokenPrefs().getString(C.USER_ID, null)
        val isOwnAccount = !viewerId.isNullOrBlank() && viewerId == user.id
        followButton.isVisible = !isOwnAccount
        followButton.text = if (user.viewerFollowsUser) {
            getString(R.string.user_card_unfollow)
        } else {
            getString(R.string.user_card_follow)
        }
        followButton.isEnabled = !followRequestInFlight &&
            (user.viewerFollowsUser || user.viewerCanFollowUser)
        followButton.setOnClickListener {
            if (!followRequestInFlight && !user.id.isNullOrBlank()) {
                followRequestInFlight = true
                followButton.isEnabled = false
                viewModel.toggleFollowUser(
                    user = user,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                )
            }
        }

        val canWhisper = requireArguments().getBoolean(KEY_MESSAGING) &&
            !user.login.isNullOrBlank() &&
            !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
        whisperButton.isEnabled = canWhisper
        whisperButton.setOnClickListener {
            user.login?.takeIf { canWhisper }?.let {
                listener.onWhisperClicked(it)
                dismiss()
            }
        }
    }

    private fun handleFollowResult(result: MessageClickedViewModel.FollowResult) {
        if (result.userId.isBlank() || result.userId != userCardUser?.id) {
            viewModel.followResult.value = null
            return
        }
        followRequestInFlight = false
        val errorMessage = result.errorMessage
        if (result.failed) {
            Snackbar.make(
                binding.root,
                errorMessage ?: getString(R.string.user_card_follow_failed),
                Snackbar.LENGTH_LONG,
            ).show()
            userCardUser?.let(::renderUserActions)
        } else {
            userCardUser?.let { currentUser ->
                val updatedUser = currentUser.withViewerFollowState(result.isFollowing)
                userCardUser = updatedUser
                replaceSavedUser(updatedUser, requireArguments().getString(KEY_CHANNEL_ID), currentViewerId())
                renderUserActions(updatedUser)
            }
        }
        viewModel.followResult.value = null
    }

    private fun User.withViewerFollowState(isFollowing: Boolean): User {
        return User(
            id = id,
            login = login,
            name = name,
            profileImageURL = profileImageURL,
            type = type,
            broadcasterType = broadcasterType,
            createdAt = createdAt,
            followerCount = followerCount,
            bannerImageURL = bannerImageURL,
            lastBroadcast = lastBroadcast,
            isLive = isLive,
            followedAt = followedAt,
            accountFollow = accountFollow,
            localFollow = localFollow,
            displayBadges = displayBadges,
            subscriptionMonths = subscriptionMonths,
            isSubscribed = isSubscribed,
            viewerFollowsUser = isFollowing,
            viewerCanFollowUser = viewerCanFollowUser,
        )
    }

    private fun currentViewerId(): String? {
        return requireContext().tokenPrefs().getString(C.USER_ID, null)
    }

    private fun replaceSavedUser(user: User, targetId: String?, viewerId: String?) {
        synchronized(savedUsers) {
            savedUsers.removeAll {
                it.user.id == user.id && it.targetId == targetId && it.viewerId == viewerId
            }
            savedUsers.add(SavedUserCard(user, targetId, viewerId))
        }
    }

    fun updateUserMessages(userId: String) {
        adapter?.let { adapter ->
            synchronized(adapter.messages) {
                adapter.messages.mapIndexedNotNull { index, message ->
                    if (message.userId != null && message.userId == userId) {
                        index
                    } else null
                }
            }.forEach {
                adapter.notifyItemChanged(it)
            }
        }
    }

    fun updateTranslation(chatMessage: ChatMessage, previousTranslation: String?) {
        adapter?.let { adapter ->
            synchronized(adapter.messages) {
                adapter.messages.indexOf(chatMessage).takeIf { it != -1 }
            }?.let {
                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                } ?: adapter.notifyItemChanged(it)
            }
        }
    }

    fun newMessage(message: ChatMessage) {
        adapter?.let { adapter ->
            if ((!adapter.userId.isNullOrBlank() && (message.userId == adapter.userId || message.replyParent?.userId == adapter.userId)) ||
                (!adapter.userLogin.isNullOrBlank() && (message.userLogin == adapter.userLogin || message.replyParent?.userLogin == adapter.userLogin))) {
                synchronized(adapter.messages) {
                    if (adapter.messages.size >= (messageLimit ?: 600.also { messageLimit = it })) {
                        adapter.messages.removeAt(0)
                        adapter.notifyItemRemoved(0)
                    }
                    adapter.messages.add(message)
                    val lastIndex = adapter.messages.lastIndex
                    adapter.notifyItemInserted(lastIndex)
                    if (!isChatTouched && !shouldShowButton()) {
                        binding.recyclerView.scrollToPosition(lastIndex)
                    }
                }
            }
        }
    }

    fun updateV2Messages(
        messages: List<ChatMessage>,
        rows: List<com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel>,
    ) {
        adapter?.updateV2Messages(messages, rows)
    }

    fun addMessages(messages: List<ChatMessage>) {
        adapter?.let { adapter ->
            synchronized(adapter.messages) {
                    val left = (messageLimit ?: 600.also { messageLimit = it }) - adapter.messages.size
                if (left > 0) {
                    val items = messages.filter { message ->
                        (!message.userId.isNullOrBlank() && (message.userId == adapter.userId || message.replyParent?.userId == adapter.userId)) ||
                                (!message.userLogin.isNullOrBlank() && (message.userLogin == adapter.userLogin || message.replyParent?.userLogin == adapter.userLogin))
                    }.takeLast(left)
                    adapter.messages.addAll(0, items)
                    adapter.notifyItemRangeInserted(0, items.size)
                    if (!isChatTouched && !shouldShowButton()) {
                        binding.recyclerView.scrollToPosition(adapter.messages.lastIndex)
                    }
                }
            }
        }
    }

    private fun shouldShowButton(): Boolean {
        with(binding) {
            val offset = recyclerView.computeVerticalScrollOffset()
            if (offset < 0) {
                return false
            }
            val extent = recyclerView.computeVerticalScrollExtent()
            val range = recyclerView.computeVerticalScrollRange()
            val percentage = (100f * offset / (range - extent).toFloat())
            return percentage < 100f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}




