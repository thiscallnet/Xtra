package com.github.andreyasadchy.xtra.ui.chat

import android.content.res.Configuration
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
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentEmotesBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.Companion.ChatViewModelFactory
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun pendingFavoriteItemsToApply(
    pendingItems: List<Emote>?,
    orderChanged: Boolean,
): List<Emote>? = if (orderChanged) null else pendingItems

enum class EmotePickerSection {
    FAVORITES,
    RECENTS,
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
    private var pendingFavoriteItems: List<Emote>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val section = requireArguments().getString(KEY_SECTION)
            ?.let { runCatching { EmotePickerSection.valueOf(it) }.getOrNull() }
            ?: EmotePickerSection.THIRD_PARTY
        val adapter = EmotesAdapter(
            this,
            { (parentFragment as? ChatFragment)?.appendEmote(it) },
            "4",
            "0",
            if (section.supportsFavoriteToggle) ::toggleFavorite else null,
            consumeLongPress = section == EmotePickerSection.RECENTS,
            reorderable = section == EmotePickerSection.FAVORITES,
        )
        binding.editFavorites.setOnClickListener {
            setFavoriteEditMode(!favoriteEditMode, adapter)
        }
        val itemTouchHelper = if (section == EmotePickerSection.FAVORITES) {
            ItemTouchHelper(
                object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                    0,
                ) {
                    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                        super.onSelectedChanged(viewHolder, actionState)
                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                            favoriteDragActive = true
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
                        val orderChanged = viewModel.reorderFavorites(adapter.currentItems())
                        favoriteDragActive = false
                        pendingFavoriteItemsToApply(pendingFavoriteItems, orderChanged)?.let { pendingItems ->
                            adapter.submitList(pendingItems)
                            updateEmptyState(section, pendingItems)
                        }
                        pendingFavoriteItems = null
                    }

                    override fun isLongPressDragEnabled(): Boolean = false
                },
            )
        } else {
            null
        }
        adapter.itemTouchHelper = itemTouchHelper
        adapter.accessibilityMoveListener = { from, to ->
            if (!adapter.moveItem(from, to)) {
                false
            } else {
                viewModel.reorderFavorites(adapter.currentItems())
                true
            }
        }
        with(binding.emotesRecyclerView) {
            itemAnimator = null
            this.adapter = adapter
            val columnWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics).toInt()
            layoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)
        }
        itemTouchHelper?.attachToRecyclerView(binding.emotesRecyclerView)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recentEmotes.collectLatest {
                        recentEmotes = it
                        updateList(section, adapter)
                    }
                }
                launch {
                    viewModel.userEmotesUpdated.collectLatest {
                        updateList(section, adapter)
                    }
                }
                launch {
                    viewModel.thirdPartyEmotesUpdated.collectLatest {
                        updateList(section, adapter)
                    }
                }
                launch {
                    viewModel.availableFavoriteEmotes.collectLatest {
                        if (section == EmotePickerSection.FAVORITES) {
                            updateList(section, adapter)
                        }
                    }
                }
                launch {
                    viewModel.favoriteKeys.collectLatest {
                        adapter.setFavoriteKeys(it)
                        if (section == EmotePickerSection.FAVORITES) {
                            updateList(section, adapter)
                        }
                    }
                }
            }
        }
        adapter.setFavoriteKeys(viewModel.favoriteKeys.value)
        updateList(section, adapter)
    }

    private fun updateList(section: EmotePickerSection, adapter: EmotesAdapter) {
        val list = when (section) {
            EmotePickerSection.FAVORITES -> viewModel.availableFavoriteEmotes.value
            EmotePickerSection.RECENTS -> {
                val current = viewModel.currentPickerEmotes()
                recentEmotes.mapNotNull { recent -> current.find { it.name == recent.name } }
            }
            EmotePickerSection.TWITCH -> synchronized(viewModel.userEmotes) {
                viewModel.userEmotes.toList()
            }
            EmotePickerSection.THIRD_PARTY -> viewModel.thirdPartyPickerEmotes()
        }
        if (section == EmotePickerSection.FAVORITES && favoriteDragActive) {
            pendingFavoriteItems = list.toList()
            updateFavoriteEditControls(section, list, adapter)
            updateEmptyState(section, list)
            return
        }
        adapter.submitList(list)
        updateFavoriteEditControls(section, list, adapter)
        updateEmptyState(section, list)
    }

    private fun setFavoriteEditMode(enabled: Boolean, adapter: EmotesAdapter) {
        favoriteEditMode = enabled
        adapter.setReorderMode(enabled)
        binding.editFavorites.setText(
            if (enabled) R.string.done_reordering_favorite_emotes else R.string.reorder_favorite_emotes,
        )
    }

    private fun updateFavoriteEditControls(
        section: EmotePickerSection,
        list: List<Emote>,
        adapter: EmotesAdapter,
    ) {
        val visible = section == EmotePickerSection.FAVORITES && list.isNotEmpty()
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

    private fun updateEmptyState(section: EmotePickerSection, list: List<Emote>) {
        if (section == EmotePickerSection.FAVORITES && list.isEmpty()) {
            binding.emptyState.setText(
                if (viewModel.favoriteEmotes.value.isEmpty()) {
                    R.string.favorite_emotes_empty
                } else {
                    R.string.favorite_emotes_unavailable
                },
            )
            binding.emptyState.isVisible = true
        } else {
            binding.emptyState.isVisible = false
        }
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
        super.onDestroyView()
        favoriteEditMode = false
        _binding = null
    }

    companion object {
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
