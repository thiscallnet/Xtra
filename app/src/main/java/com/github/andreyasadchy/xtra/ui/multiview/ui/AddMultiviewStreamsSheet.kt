package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentAddMultiviewStreamsBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.following.streams.FollowedStreamsViewModel
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AddMultiviewStreamsSheet : BottomSheetDialogFragment() {
    private var _binding: FragmentAddMultiviewStreamsBinding? = null
    private val binding get() = _binding!!
    private val followedStreamsViewModel: FollowedStreamsViewModel by viewModels {
        FollowedStreamsViewModel.FollowedStreamsViewModelFactory
    }
    private val selected = linkedMapOf<String, Stream>()
    private lateinit var adapter: MultiviewPickerAdapter
    private var excluded: Set<String> = emptySet()
    private var maxSelection = 1
    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        excluded = requireArguments().getStringArrayList(ARG_EXCLUDED).orEmpty().toSet()
        maxSelection = requireArguments().getInt(ARG_MAX_SELECTION, 1).coerceAtLeast(1)
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelableArrayList<Stream>(KEY_SELECTED)?.forEach { stream ->
            stableIdentity(stream)?.let { identity -> selected[identity] = stream }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAddMultiviewStreamsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MultiviewPickerAdapter(
            isExcluded = { stableIdentity(it) in excluded },
            isSelected = { stableIdentity(it) in selected },
            onClick = ::toggleSelection,
        )
        binding.streamList.layoutManager = LinearLayoutManager(requireContext())
        binding.streamList.adapter = adapter
        binding.pickerLimit.text = getString(R.string.multiview_picker_limit, maxSelection)
        binding.cancelButton.setOnClickListener { dismiss() }
        binding.addButton.setOnClickListener { submitSelection() }
        binding.errorState.setOnClickListener { adapter.retry() }
        binding.searchButton.setOnClickListener { lookupChannel() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                lookupChannel()
                true
            } else {
                false
            }
        }
        updateSelectionUi()

        viewLifecycleOwner.lifecycleScope.launch {
            followedStreamsViewModel.flow.collectLatest { adapter.submitData(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.loadStateFlow.collectLatest { states ->
                val loading = states.refresh is LoadState.Loading
                val error = states.refresh is LoadState.Error
                binding.loading.isVisible = loading
                binding.errorState.isVisible = error && adapter.itemCount == 0
                binding.emptyState.isVisible = !loading && !error && states.refresh is LoadState.NotLoading && adapter.itemCount == 0
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet)?.let { sheet ->
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun toggleSelection(stream: Stream) {
        val identity = stableIdentity(stream) ?: return
        if (selected.remove(identity) == null) {
            if (selected.size >= maxSelection) return
            selected[identity] = stream
        }
        adapter.refreshSelection()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (_binding == null) return
        binding.selectedCount.text = getString(R.string.multiview_picker_selected, selected.size)
        binding.addButton.text = getString(R.string.multiview_picker_add, selected.size)
        binding.addButton.isEnabled = selected.isNotEmpty()
    }

    private fun lookupChannel() {
        val login = binding.searchInput.text?.toString()?.trim()?.removePrefix("@").orEmpty().lowercase()
        if (login.isBlank()) {
            binding.searchInputLayout.error = getString(R.string.multiview_picker_manual_error)
            return
        }
        if (selected.size >= maxSelection) return
        binding.searchInputLayout.error = null
        binding.searchButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val stream = (parentFragment as? MultiviewFragment)?.resolveManualStream(login)
            binding.searchButton.isEnabled = true
            if (stream == null) {
                binding.searchInputLayout.error = getString(R.string.multiview_stream_not_found)
            } else {
                val identity = stableIdentity(stream)
                if (identity == null || identity in excluded) {
                    binding.searchInputLayout.error = getString(R.string.multiview_picker_already_added)
                } else {
                    selected[identity] = stream
                    adapter.refreshSelection()
                    updateSelectionUi()
                }
            }
        }
    }

    private fun submitSelection() {
        if (selected.isEmpty()) return
        submitted = true
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
            putParcelableArrayList(RESULT_STREAMS, ArrayList(selected.values))
        })
        dismiss()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (!submitted) {
            parentFragmentManager.setFragmentResult(DISMISSED_KEY, Bundle())
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList(KEY_SELECTED, ArrayList(selected.values))
        super.onSaveInstanceState(outState)
    }

    private fun stableIdentity(stream: Stream): String? {
        return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
            ?: stream.channelLogin?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
    }

    companion object {
        const val TAG = "multiview_add_streams"
        const val RESULT_KEY = "multiview_add_streams_result"
        const val RESULT_STREAMS = "multiview_added_streams"
        const val DISMISSED_KEY = "multiview_add_streams_dismissed"
        private const val ARG_EXCLUDED = "multiview_excluded_streams"
        private const val ARG_MAX_SELECTION = "multiview_max_selection"
        private const val KEY_SELECTED = "multiview_selected_streams"

        fun newInstance(excluded: List<String>, maxSelection: Int): AddMultiviewStreamsSheet {
            return AddMultiviewStreamsSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_EXCLUDED, ArrayList(excluded))
                    putInt(ARG_MAX_SELECTION, maxSelection)
                }
            }
        }
    }
}
