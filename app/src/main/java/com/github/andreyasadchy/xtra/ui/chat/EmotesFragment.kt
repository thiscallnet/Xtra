package com.github.andreyasadchy.xtra.ui.chat

import android.content.res.Configuration
import android.content.SharedPreferences
import android.os.Bundle
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentEmotesBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.chat.key
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.Companion.ChatViewModelFactory
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun <T> pendingFavoriteItemsToApply(
    pendingItems: List<T>?,
    orderChanged: Boolean,
): List<T>? = if (orderChanged) null else pendingItems

enum class EmotePickerSection {
    FAVORITES,
    RECENTS,
    EMOJI,
    TWITCH,
    THIRD_PARTY,
    ;

    val position: Int
        get() = ordinal

    val supportsFavoriteToggle: Boolean
        get() = this != RECENTS

    companion object {
        fun fromPosition(position: Int): EmotePickerSection = entries.getOrElse(position) { THIRD_PARTY }
    }
}

class EmotesFragment : Fragment() {

    private var _binding: FragmentEmotesBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<ChatViewModel>(ownerProducer = { requireParentFragment() }, factoryProducer = { ChatViewModelFactory })
    private var recentEmotes = emptyList<RecentEmote>()
    private var favoriteDragActive = false
    private var favoriteEditMode = false
    private var pendingFavoriteItems: List<FavoritePickerItem>? = null
    private var thirdPartyPickerState: ChatViewModel.ThirdPartyPickerState? = null
    private var pickerCatalog: ChatViewModel.PickerCatalog? = null
    private var emojiAdapter: EmojiAdapter? = null
    private var compactEmojiAdapter: CompactEmojiAdapter? = null
    private var favoritePickerAdapter: FavoritePickerAdapter? = null
    private var compactLayoutPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val section = requireArguments().getString(KEY_SECTION)
            ?.let { runCatching { EmotePickerSection.valueOf(it) }.getOrNull() }
            ?: EmotePickerSection.THIRD_PARTY
        if (section == EmotePickerSection.FAVORITES) {
            setupFavoritesPicker()
            return
        }
        if (section == EmotePickerSection.EMOJI) {
            setupEmojiPicker()
            return
        }
        val chatFragment = parentFragment as? ChatFragment
        val expectedChannelId = chatFragment?.arguments?.getString(ChatFragment.KEY_CHANNEL_ID)
        val expectedChannelLogin = chatFragment?.arguments?.getString(ChatFragment.KEY_CHANNEL_LOGIN)
        val usesV2 = chatFragment?.isUsingChatV2 == true
        binding.emptyState.setOnClickListener {
            (thirdPartyPickerState as? ChatViewModel.ThirdPartyPickerState.Error)?.retry?.invoke()
        }
        val emotesAdapter = EmotesAdapter(
            this,
            { (parentFragment as? ChatFragment)?.appendEmote(it) },
            "4",
            "0",
            if (section.supportsFavoriteToggle) ::toggleFavorite else null,
            consumeLongPress = section == EmotePickerSection.RECENTS,
        )
        val twitchAdapter = if (section == EmotePickerSection.TWITCH) {
            TwitchEmotesAdapter(
                fragment = this,
                clickListener = { (parentFragment as? ChatFragment)?.appendEmote(it) },
                emoteQuality = "4",
                imageLibrary = "0",
                favoriteToggleListener = ::toggleFavorite,
            )
        } else null
        val preferences = requireContext().prefs()

        fun applyCompactEmoteSize() {
            emotesAdapter.setCompactPickerVisualSizeDp(
                preferences.getBoolean(C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS, false),
                CompactPickerItemSize.fromPreference(
                    preferences.getString(C.CHAT_COMPACT_PICKER_ITEM_SIZE, "medium"),
                ).assetSizeDp,
            )
        }

        fun createGridLayoutManager(compactAdapter: TwitchEmotesAdapter?): GridAutofitLayoutManager {
            val columnWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics).toInt()
            val gridLayoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)
            if (compactAdapter != null) {
                gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (compactAdapter.isHeader(position)) gridLayoutManager.spanCount else 1
                }
            }
            return gridLayoutManager
        }

        fun setTwitchLayout(compactEnabled: Boolean) {
            val compactAdapter = twitchAdapter ?: return
            val recyclerView = binding.emotesRecyclerView
            recyclerView.itemAnimator = null
            if (compactEnabled) {
                compactAdapter.setPickerVisualSizeDp(
                    CompactPickerItemSize.fromPreference(
                        preferences.getString(C.CHAT_COMPACT_PICKER_ITEM_SIZE, "medium"),
                    ).assetSizeDp,
                )
                compactAdapter.setCompactEnabled(true)
                recyclerView.adapter = compactAdapter
                recyclerView.layoutManager = createGridLayoutManager(compactAdapter)
            } else {
                // Keep the original AsyncListDiffer-backed adapter attached when the option
                // is off. This preserves the established picker behavior and update path.
                recyclerView.adapter = emotesAdapter
                recyclerView.layoutManager = createGridLayoutManager(null)
            }
        }

        binding.emotesRecyclerView.itemAnimator = null
        if (twitchAdapter != null) {
            setTwitchLayout(preferences.getBoolean(C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS, false))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS || key == C.CHAT_COMPACT_PICKER_ITEM_SIZE) {
                    val compactEnabled = preferences.getBoolean(C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS, false)
                    setTwitchLayout(compactEnabled)
                    if (key == C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS && compactEnabled) {
                        (parentFragment as? ChatFragment)?.reloadEmotes()
                    }
                }
            }
            compactLayoutPreferenceListener = listener
            preferences.registerOnSharedPreferenceChangeListener(listener)
        } else {
            applyCompactEmoteSize()
            binding.emotesRecyclerView.adapter = emotesAdapter
            binding.emotesRecyclerView.layoutManager = createGridLayoutManager(null)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS || key == C.CHAT_COMPACT_PICKER_ITEM_SIZE) {
                    applyCompactEmoteSize()
                }
            }
            compactLayoutPreferenceListener = listener
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recentEmotes.collectLatest {
                        recentEmotes = it
                        updateList(section, emotesAdapter, twitchAdapter)
                    }
                }
                launch {
                    viewModel.userEmotesUpdated.collectLatest {
                        updateList(section, emotesAdapter, twitchAdapter)
                    }
                }
                launch {
                    viewModel.thirdPartyPickerStateFor(expectedChannelId, expectedChannelLogin, usesV2).collectLatest {
                        thirdPartyPickerState = it
                        updateList(section, emotesAdapter, twitchAdapter)
                    }
                }
                launch {
                    viewModel.pickerCatalogFor(expectedChannelId, expectedChannelLogin, usesV2).collectLatest {
                        pickerCatalog = it
                        updateList(section, emotesAdapter, twitchAdapter)
                    }
                }
                launch {
                    viewModel.favoriteKeys.collectLatest {
                        emotesAdapter.setFavoriteKeys(it)
                        twitchAdapter?.setFavoriteKeys(it)
                    }
                }
            }
        }
        emotesAdapter.setFavoriteKeys(viewModel.favoriteKeys.value)
        twitchAdapter?.setFavoriteKeys(viewModel.favoriteKeys.value)
        updateList(section, emotesAdapter, twitchAdapter)
    }

    private fun setupFavoritesPicker() {
        binding.emojiCategories.isVisible = false
        binding.emptyState.setOnClickListener(null)
        val chatFragment = parentFragment as? ChatFragment
        val expectedChannelId = chatFragment?.arguments?.getString(ChatFragment.KEY_CHANNEL_ID)
        val expectedChannelLogin = chatFragment?.arguments?.getString(ChatFragment.KEY_CHANNEL_LOGIN)
        val usesV2 = chatFragment?.isUsingChatV2 == true
        binding.editFavorites.isVisible = false
        val assets = (requireContext().applicationContext as XtraApp).xtraModule.chatAssetRepository
        val preferences = requireContext().prefs()
        var favoriteEmotes = viewModel.favoriteEmotes.value
        var availableEmotes = viewModel.availableFavoriteEmotes.value
        val adapter = FavoritePickerAdapter(
            fragment = this,
            assets = assets,
            emoteClickListener = { (parentFragment as? ChatFragment)?.appendEmote(it) },
            emoteFavoriteToggleListener = ::toggleFavorite,
            emojiClickListener = { (parentFragment as? ChatFragment)?.appendEmoji(it) },
            emojiFavoriteToggleListener = ::toggleEmojiFavorite,
        )
        favoritePickerAdapter = adapter
        fun applyCompactPickerSize() {
            adapter.setCompactPickerVisualSizeDp(
                preferences.getBoolean(C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS, false),
                CompactPickerItemSize.fromPreference(
                    preferences.getString(C.CHAT_COMPACT_PICKER_ITEM_SIZE, "medium"),
                ).assetSizeDp,
            )
        }
        applyCompactPickerSize()
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS || key == C.CHAT_COMPACT_PICKER_ITEM_SIZE) {
                applyCompactPickerSize()
            }
        }
        compactLayoutPreferenceListener = preferenceListener
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        binding.editFavorites.setOnClickListener {
            setFavoriteEditMode(!favoriteEditMode, adapter)
        }
        val itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0,
            ) {
                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        favoriteDragActive = true
                        adapter.setDragging(viewHolder, true)
                    }
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                    return adapter.moveItem(from, to)
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    adapter.setDragging(viewHolder, false)
                    val orderChanged = viewModel.reorderFavoriteKeys(adapter.currentItems().map { it.key })
                    favoriteDragActive = false
                    pendingFavoriteItemsToApply(pendingFavoriteItems, orderChanged)?.let { pendingItems ->
                        adapter.submitList(pendingItems)
                        updateFavoritesEmptyState(pendingItems)
                    }
                    pendingFavoriteItems = null
                }

                override fun isLongPressDragEnabled(): Boolean = false

                override fun getMoveThreshold(viewHolder: RecyclerView.ViewHolder): Float = MOVE_THRESHOLD
            },
        )
        adapter.itemTouchHelper = itemTouchHelper
        adapter.accessibilityMoveListener = { from, to ->
            if (!adapter.moveItem(from, to)) {
                false
            } else {
                viewModel.reorderFavoriteKeys(adapter.currentItems().map { it.key })
                true
            }
        }
        with(binding.emotesRecyclerView) {
            itemAnimator = null
            this.adapter = adapter
            val columnWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                50f,
                resources.displayMetrics,
            ).toInt()
            layoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)
        }
        itemTouchHelper.attachToRecyclerView(binding.emotesRecyclerView)
        fun updateFavorites() {
            val emotes = pickerCatalog?.let(viewModel::availableFavoriteEmotesFor) ?: availableEmotes
            val items = favoritePickerItems(favoriteEmotes, emotes, EmojiPickerCatalog.items)
            if (favoriteDragActive) {
                pendingFavoriteItems = items
                updateFavoriteEditControls(items.isNotEmpty(), adapter)
                updateFavoritesEmptyState(items)
                return
            }
            adapter.submitList(items)
            updateFavoriteEditControls(items.isNotEmpty(), adapter)
            updateFavoritesEmptyState(items)
        }
        adapter.setFavoriteKeys(viewModel.favoriteKeys.value)
        adapter.setFavoriteValues(EmojiFavoritesCatalog.favoriteValues(favoriteEmotes))
        updateFavorites()
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.userEmotesUpdated.collectLatest { updateFavorites() }
                }
                launch {
                    viewModel.availableFavoriteEmotes.collectLatest {
                        availableEmotes = it
                        updateFavorites()
                    }
                }
                launch {
                    viewModel.pickerCatalogFor(expectedChannelId, expectedChannelLogin, usesV2).collectLatest {
                        pickerCatalog = it
                        if (it != null) {
                            availableEmotes = viewModel.availableFavoriteEmotesFor(it)
                        }
                        updateFavorites()
                    }
                }
                launch {
                    viewModel.favoriteEmotes.collectLatest { favorites ->
                        favoriteEmotes = favorites
                        adapter.setFavoriteKeys(favorites.mapNotNull { it.key() }.toSet())
                        adapter.setFavoriteValues(EmojiFavoritesCatalog.favoriteValues(favorites))
                        updateFavorites()
                    }
                }
            }
        }
    }

    private fun setupEmojiPicker() {
        binding.editFavorites.isVisible = false
        val assets = (requireContext().applicationContext as XtraApp).xtraModule.chatAssetRepository
        val preferences = requireContext().prefs()
        // Start on the complete catalog. Favorites load asynchronously from Room, so using
        // the StateFlow's initial value here could make the first screen depend on timing.
        var selectedCategory = EmojiPickerCategory.ALL
        var favoriteEmotes = viewModel.favoriteEmotes.value
        val adapter = EmojiAdapter(
            fragment = this,
            assets = assets,
            clickListener = { emoji ->
                (parentFragment as? ChatFragment)?.appendEmoji(emoji)
            },
            favoriteToggleListener = { emoji -> toggleEmojiFavorite(emoji) },
        )
        val compactAdapter = CompactEmojiAdapter(
            fragment = this,
            assets = assets,
            clickListener = { emoji ->
                (parentFragment as? ChatFragment)?.appendEmoji(emoji)
            },
            favoriteToggleListener = { emoji -> toggleEmojiFavorite(emoji) },
        )
        emojiAdapter = adapter
        compactEmojiAdapter = compactAdapter
        adapter.setFavoriteValues(EmojiFavoritesCatalog.favoriteValues(favoriteEmotes))
        compactAdapter.setFavoriteValues(EmojiFavoritesCatalog.favoriteValues(favoriteEmotes))

        fun createGridLayoutManager(headerAdapter: CompactEmojiAdapter?): GridAutofitLayoutManager {
            val columnWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                50f,
                resources.displayMetrics,
            ).toInt()
            val gridLayoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)
            if (headerAdapter != null) {
                gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (headerAdapter.isHeader(position)) gridLayoutManager.spanCount else 1
                }
            }
            return gridLayoutManager
        }

        fun submitEmojiContent() {
            adapter.submitList(EmojiPickerCatalog.itemsFor(selectedCategory))
            compactAdapter.submitSections(
                EmojiPickerCatalog.categories
                    .filter { it != EmojiPickerCategory.ALL }
                    .map { category -> category to EmojiPickerCatalog.itemsFor(category) },
            )
            binding.emptyState.isVisible = false
        }

        fun setEmojiLayout(compactEnabled: Boolean) {
            binding.emojiCategories.isVisible = !compactEnabled
            val visualSize = CompactPickerItemSize.fromPreference(
                preferences.getString(C.CHAT_COMPACT_PICKER_ITEM_SIZE, "medium"),
            ).assetSizeDp
            compactAdapter.setPickerVisualSizeDp(visualSize)
            with(binding.emotesRecyclerView) {
                itemAnimator = null
                if (compactEnabled) {
                    this.adapter = compactAdapter
                    layoutManager = createGridLayoutManager(compactAdapter)
                } else {
                    // Keep the existing category-tab picker and full-size emoji artwork intact.
                    this.adapter = adapter
                    layoutManager = createGridLayoutManager(null)
                }
            }
        }

        setEmojiLayout(preferences.getBoolean(C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS, false))
        with(binding.emotesRecyclerView) {
            itemAnimator = null
            if (this.adapter == null) this.adapter = adapter
        }
        EmojiPickerCatalog.categories.forEach { category ->
            binding.emojiCategories.addTab(
                binding.emojiCategories.newTab().setText(getString(category.titleRes)),
                false,
            )
        }
        binding.emojiCategories.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                EmojiPickerCatalog.categories.getOrNull(tab.position)?.let { category ->
                    selectedCategory = category
                    submitEmojiContent()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.emojiCategories.getTabAt(selectedCategory.ordinal)?.select()
        submitEmojiContent()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS || key == C.CHAT_COMPACT_PICKER_ITEM_SIZE) {
                setEmojiLayout(preferences.getBoolean(C.CHAT_COMPACT_TWITCH_EMOTE_GROUPS, false))
                submitEmojiContent()
            }
        }
        compactLayoutPreferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favoriteEmotes.collectLatest {
                    favoriteEmotes = it
                    adapter.setFavoriteValues(EmojiFavoritesCatalog.favoriteValues(it))
                    compactAdapter.setFavoriteValues(EmojiFavoritesCatalog.favoriteValues(it))
                    submitEmojiContent()
                }
            }
        }
    }

    private fun toggleEmojiFavorite(emoji: EmojiPickerItem) {
        val added = viewModel.toggleFavorite(emoji)
        Snackbar.make(
            binding.root,
            getString(
                if (added) R.string.added_emoji_to_favorites else R.string.removed_emoji_from_favorites,
                emoji.alias,
            ),
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    private fun updateList(
        section: EmotePickerSection,
        adapter: EmotesAdapter,
        compactTwitchAdapter: TwitchEmotesAdapter? = null,
    ) {
        val list = when (section) {
            EmotePickerSection.RECENTS -> {
                val current = pickerCatalog?.all ?: viewModel.currentPickerEmotes()
                recentEmotes.mapNotNull { recent -> current.find { it.name == recent.name } }
            }
            EmotePickerSection.TWITCH -> pickerCatalog?.twitch ?: viewModel.twitchPickerEmotes()
            EmotePickerSection.THIRD_PARTY -> when (val state = thirdPartyPickerState) {
                is ChatViewModel.ThirdPartyPickerState.Ready -> state.emotes
                null -> viewModel.thirdPartyPickerEmotes()
                else -> emptyList()
            }
            EmotePickerSection.EMOJI -> emptyList()
            EmotePickerSection.FAVORITES -> emptyList()
        }
        adapter.submitList(list)
        compactTwitchAdapter?.submitList(list)
        updateEmptyState(section, list)
    }

    private fun setFavoriteEditMode(enabled: Boolean, adapter: FavoritePickerAdapter) {
        favoriteEditMode = enabled
        adapter.setReorderMode(enabled)
        binding.editFavorites.setText(
            if (enabled) R.string.done_reordering_favorite_emotes else R.string.reorder_favorite_emotes,
        )
    }

    private fun updateFavoriteEditControls(
        visible: Boolean,
        adapter: FavoritePickerAdapter,
    ) {
        if (!visible && favoriteEditMode) {
            setFavoriteEditMode(false, adapter)
        }
        binding.editFavorites.isVisible = visible
        binding.emotesRecyclerView.setPadding(
            binding.emotesRecyclerView.paddingLeft,
            if (visible) resources.getDimensionPixelSize(R.dimen.emote_picker_edit_control_space) else 0,
            binding.emotesRecyclerView.paddingRight,
            binding.emotesRecyclerView.paddingBottom,
        )
    }

    private fun updateEmptyState(
        section: EmotePickerSection,
        list: List<Emote>,
    ) {
        if (section == EmotePickerSection.THIRD_PARTY) {
            if (thirdPartyPickerState == null) {
                binding.emptyState.isVisible = false
                return
            }
            binding.emptyState.text = when (thirdPartyPickerState) {
                ChatViewModel.ThirdPartyPickerState.Loading -> getString(R.string.third_party_emotes_loading)
                ChatViewModel.ThirdPartyPickerState.Empty -> getString(R.string.third_party_emotes_empty)
                is ChatViewModel.ThirdPartyPickerState.Ready -> ""
                is ChatViewModel.ThirdPartyPickerState.Error -> getString(R.string.third_party_emotes_error)
                null -> ""
            }
            binding.emptyState.isVisible = thirdPartyPickerState !is ChatViewModel.ThirdPartyPickerState.Ready
        } else {
            binding.emptyState.isVisible = false
        }
    }

    private fun updateFavoritesEmptyState(items: List<FavoritePickerItem>) {
        if (items.isNotEmpty()) {
            binding.emptyState.isVisible = false
            return
        }
        binding.emptyState.setText(
            if (viewModel.favoriteEmotes.value.isEmpty()) {
                R.string.favorite_emotes_empty
            } else {
                R.string.favorite_emotes_unavailable
            },
        )
        binding.emptyState.isVisible = true
    }

    private fun toggleFavorite(emote: Emote) {
        if (viewModel.isFavorite(emote)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.remove_emote_from_favorites)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.remove_emote_from_favorites) { _, _ ->
                    removeFavoriteImmediately(emote)
                }
                .show()
            return
        }
        toggleFavoriteImmediately(emote)
    }

    private fun removeFavoriteImmediately(emote: Emote) {
        if (viewModel.removeFavorite(emote) == null) return
        Snackbar.make(
            binding.root,
            getString(R.string.removed_emote_from_favorites, emote.name.orEmpty()),
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    private fun toggleFavoriteImmediately(emote: Emote) {
        val added = viewModel.toggleFavorite(emote) ?: return
        Snackbar.make(
            binding.root,
            getString(
                if (added) R.string.added_emote_to_favorites else R.string.removed_emote_from_favorites,
                emote.name.orEmpty(),
            ),
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (binding.emotesRecyclerView.layoutManager as? GridAutofitLayoutManager)?.updateWidth()
    }

    override fun onDestroyView() {
        compactLayoutPreferenceListener?.let { listener ->
            requireContext().prefs().unregisterOnSharedPreferenceChangeListener(listener)
        }
        compactLayoutPreferenceListener = null
        if (emojiAdapter != null || compactEmojiAdapter != null || favoritePickerAdapter != null) {
            binding.emotesRecyclerView.adapter = null
        }
        emojiAdapter?.dispose()
        emojiAdapter = null
        compactEmojiAdapter?.dispose()
        compactEmojiAdapter = null
        favoritePickerAdapter?.dispose()
        favoritePickerAdapter = null
        super.onDestroyView()
        favoriteEditMode = false
        _binding = null
    }

    companion object {
        private const val MOVE_THRESHOLD = 0.60f
        private const val KEY_SECTION = "section"

        fun newInstance(section: EmotePickerSection): EmotesFragment {
            return EmotesFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_SECTION, section.name)
                }
            }
        }
    }
}
