package com.github.andreyasadchy.xtra.ui.multiview

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CombinedChatListItemBinding
import com.github.andreyasadchy.xtra.databinding.FragmentCombinedChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatAdapter
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel
import com.github.andreyasadchy.xtra.ui.multiview.chat.CombinedChatMessage
import com.github.andreyasadchy.xtra.ui.multiview.chat.CombinedChatViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CombinedChatFragment : Fragment(R.layout.fragment_combined_chat) {
    private var _binding: FragmentCombinedChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CombinedChatViewModel by viewModels { CombinedChatViewModel.Factory }
    private lateinit var adapter: CombinedChatAdapter
    private var filterIdentity: String? = null
    private var currentStreams: List<Stream> = emptyList()

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
        submitMessages(scroll = true)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updates.collectLatest { submitMessages(scroll = !isAtBottom(layoutManager)) }
            }
        }
    }

    fun updateStreams(streams: List<Stream>) {
        currentStreams = streams
        viewModel.ensureStreams(streams)
        if (_binding != null) {
            if (filterIdentity !in viewModel.channelNames().map { it.first }) filterIdentity = null
            setupFilters()
            submitMessages(scroll = true)
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
                submitMessages(scroll = true)
            }
        }
        viewModel.channelNames().forEach { (identity, name) ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = name
                isCheckable = true
                contentDescription = name
            }
            binding.channelFilters.addView(chip)
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    filterIdentity = identity
                    submitMessages(scroll = true)
                }
            }
        }
        binding.channelFilters.check(all.id)
    }

    private fun submitMessages(scroll: Boolean) {
        if (!::adapter.isInitialized || _binding == null) return
        val layoutManager = binding.combinedChatRecyclerView.layoutManager as? LinearLayoutManager
        val shouldScroll = scroll || layoutManager?.let(::isAtBottom) == true
        val items = viewModel.snapshot(filterIdentity)
        adapter.submitList(items) {
            binding.combinedChatEmpty.isVisible = items.isEmpty()
            if (shouldScroll && items.isNotEmpty()) {
                binding.combinedChatRecyclerView.scrollToPosition(items.lastIndex)
            }
        }
        binding.combinedChatEmpty.isVisible = items.isEmpty()
    }

    private fun isAtBottom(layoutManager: LinearLayoutManager): Boolean {
        return layoutManager.findLastCompletelyVisibleItemPosition() >= adapter.itemCount - 1
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STREAMS = "combined_chat_streams"

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
    private val renderers = mutableMapOf<String, SessionRenderer>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(CombinedChatListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.channelChip.text = item.channelName
        holder.binding.channelChip.contentDescription = item.channelName
        viewModel.session(item.identity)?.let { session ->
            val renderer = renderers[item.identity]
                ?.takeIf { it.isFor(session) }
                ?: SessionRenderer(fragment, session, item.channelName).also {
                    renderers[item.identity] = it
                }
            renderer.bind(holder.binding.messageText, item.message)
        }
        holder.binding.root.contentDescription = fragment.getString(
            R.string.multiview_combined_message_description,
            item.channelName,
            holder.binding.messageText.contentDescription ?: holder.binding.messageText.text,
        )
    }

    class ViewHolder(val binding: CombinedChatListItemBinding) : RecyclerView.ViewHolder(binding.root)

    private class SessionRenderer(
        fragment: CombinedChatFragment,
        private val session: ChatViewModel,
        streamName: String,
    ) {
        private val singleMessage = mutableListOf<ChatMessage>()
        private val adapter: ChatAdapter

        init {
            val context = fragment.requireContext()
            val preferences = context.prefs()
            val sizeModifier = (preferences.getInt(C.CHAT_SIZE_MODIFIER, 100).toFloat() / 100f)
            val isLightTheme = context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                it.getBoolean(0, false)
            }
            adapter = ChatAdapter(
                messages = singleMessage,
                localTwitchEmotes = session.localTwitchEmotes,
                thirdPartyEmotes = session.thirdPartyEmotes,
                globalBadges = session.globalBadges,
                channelBadges = session.channelBadges,
                cheerEmotes = session.cheerEmotes,
                namePaints = session.namePaints,
                stvBadges = session.stvBadges,
                personalEmoteSets = session.personalEmoteSets,
                stvUsers = session.stvUsers,
                enableTimestamps = preferences.getBoolean(C.CHAT_TIMESTAMPS, false),
                timestampFormat = preferences.getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                firstMsgVisibility = preferences.getString(C.CHAT_FIRST_MSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
                firstChatMsg = fragment.getString(R.string.chat_first),
                redeemedChatMsg = fragment.getString(R.string.redeemed),
                redeemedNoMsg = fragment.getString(R.string.user_redeemed),
                rewardChatMsg = fragment.getString(R.string.chat_reward),
                replyMessage = fragment.getString(R.string.replying_to_message),
                useRandomColors = preferences.getBoolean(C.CHAT_RANDOM_COLOR, true),
                useReadableColors = preferences.getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                isLightTheme = isLightTheme,
                nameDisplay = preferences.getString(C.UI_NAME_DISPLAY, "0"),
                useBoldNames = preferences.getBoolean(C.CHAT_BOLD_NAMES, false),
                showNamePaints = preferences.getBoolean(C.CHAT_SHOW_PAINTS, true),
                showSTVBadges = preferences.getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                showPersonalEmotes = preferences.getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                showSystemMessageEmotes = preferences.getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                chatUrl = null,
                getEmoteBytes = session::getEmoteBytes,
                fragment = fragment,
                backgroundColor = MaterialColors.getColor(fragment.requireView(), com.google.android.material.R.attr.colorSurface),
                dialogBackgroundColor = MaterialColors.getColor(fragment.requireView(), com.google.android.material.R.attr.colorSurfaceContainerLow),
                imageLibrary = preferences.getString(C.CHAT_IMAGE_LIBRARY, "0"),
                messageTextSize = (preferences.getString(C.CHAT_TEXT_SIZE, "14")?.toFloatOrNull() ?: 14f) * sizeModifier,
                emoteSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    (preferences.getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * sizeModifier,
                    fragment.resources.displayMetrics,
                ).toInt(),
                badgeSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    (preferences.getString(C.CHAT_BADGE_SIZE, "18.5")?.toFloatOrNull() ?: 18.5f) * sizeModifier,
                    fragment.resources.displayMetrics,
                ).toInt(),
                emoteQuality = preferences.getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                animateGifs = preferences.getBoolean(C.ANIMATED_EMOTES, true),
                enableOverlayEmotes = preferences.getBoolean(C.CHAT_ZERO_WIDTH, true),
                translateMessage = { _, _ -> },
                showLanguageDownloadDialog = { _, _ -> },
                channelId = null,
                loggedInUser = null,
                messageClickListener = null,
                replyClickListener = null,
                imageClickListener = null,
            )
        }

        fun bind(textView: TextView, message: ChatMessage) {
            synchronized(singleMessage) {
                if (singleMessage.isEmpty()) singleMessage += message else singleMessage[0] = message
            }
            adapter.onBindViewHolder(adapter.ViewHolder(textView), 0)
        }

        fun isFor(session: ChatViewModel): Boolean = this.session === session
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CombinedChatMessage>() {
            override fun areItemsTheSame(oldItem: CombinedChatMessage, newItem: CombinedChatMessage): Boolean {
                return oldItem.sequence == newItem.sequence
            }

            override fun areContentsTheSame(oldItem: CombinedChatMessage, newItem: CombinedChatMessage): Boolean {
                return oldItem == newItem
            }
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
