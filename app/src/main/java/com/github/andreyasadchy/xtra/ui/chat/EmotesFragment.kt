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
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentEmotesBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.Companion.ChatViewModelFactory
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        )
        with(binding.emotesRecyclerView) {
            itemAnimator = null
            this.adapter = adapter
            val columnWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics).toInt()
            layoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)
        }
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
        adapter.submitList(list)
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
