package com.github.andreyasadchy.xtra.ui.common

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.paging.LoadState
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogSearchTagsBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.common.SearchTagsViewModel.Companion.SearchTagsViewModelFactory
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class SearchTagsDialog : DialogFragment() {

    interface OnTagSelectedListener {
        fun onTagSelected(tag: Tag)
    }

    companion object {
        private const val GET_GAME_TAGS = "getGameTags"
        private const val QUERY = "query"

        fun newInstance(getGameTags: Boolean): SearchTagsDialog {
            return SearchTagsDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(GET_GAME_TAGS, getGameTags)
                }
            }
        }
    }

    private var _binding: DialogSearchTagsBinding? = null
    private val viewModel: SearchTagsViewModel by viewModels { SearchTagsViewModelFactory }
    private var listener: OnTagSelectedListener? = null
    private var queryJob: Job? = null
    private var dialogJob: Job? = null
    private var requestKeyboard = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? OnTagSelectedListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogSearchTagsBinding.inflate(layoutInflater)
        _binding = binding
        requestKeyboard = true
        val builder = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
        with(binding) {
            viewModel.getGameTags = requireArguments().getBoolean(GET_GAME_TAGS)
            val pagingAdapter = SearchTagsAdapter { tag ->
                listener?.onTagSelected(tag)
                dismiss()
            }
            recyclerView.adapter = pagingAdapter
            var awaitingQuery = false
            var submittedQuery: String? = null
            var resultsQuery: String? = null
            var refresh: LoadState = LoadState.Loading
            fun renderStatus() {
                val query = searchView.query.toString()
                val idle = query.isBlank()
                val pending = awaitingQuery || query != viewModel.query.value
                val loading = !idle && (pending || refresh is LoadState.Loading)
                val failed = !idle && !pending && refresh is LoadState.Error
                val hasResults = !idle && !pending && resultsQuery == query && pagingAdapter.itemCount > 0
                recyclerView.isVisible = hasResults
                searchStatus.root.isVisible = !hasResults || loading || failed
                searchStatus.sectionProgress.isVisible = loading
                searchStatus.sectionRetry.isVisible = failed
                searchStatus.sectionMessage.setText(when {
                    idle -> R.string.search_tags_prompt
                    loading -> R.string.section_loading
                    failed && hasResults -> R.string.section_stale
                    failed -> R.string.list_load_error
                    else -> R.string.search_all_empty
                })
            }
            fun submitQuery(query: String) {
                awaitingQuery = query != viewModel.query.value
                viewModel.setQuery(query)
                renderStatus()
            }
            searchStatus.sectionMessage.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            searchStatus.sectionRetry.contentDescription = getString(R.string.section_retry, getString(R.string.search_tags))
            searchStatus.sectionRetry.setOnClickListener { pagingAdapter.retry() }
            pagingAdapter.addOnPagesUpdatedListener {
                if (resultsQuery != submittedQuery) recyclerView.scrollToPosition(0)
                resultsQuery = submittedQuery
                renderStatus()
            }
            // Restore even an unfinished, debounced query after process death.
            val query = savedInstanceState?.getString(QUERY) ?: viewModel.query.value
            searchView.setQuery(query, false)
            viewModel.setQuery(query)
            searchView.requestFocus()
            renderStatus()
            dialogJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.flow.collectLatest { page ->
                            awaitingQuery = page.query != viewModel.query.value
                            submittedQuery = page.query
                            pagingAdapter.submitData(page.data)
                        }
                    }
                    launch {
                        pagingAdapter.loadStateFlow.collect {
                            refresh = it.refresh
                            renderStatus()
                        }
                    }
                }
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    queryJob?.cancel()
                    submitQuery(query)
                    return false
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    queryJob?.cancel()
                    if (newText.isNotEmpty()) {
                        queryJob = lifecycleScope.launch {
                            delay(750.milliseconds)
                            withResumed {
                                submitQuery(newText)
                            }
                        }
                    } else {
                        submitQuery(newText)
                    }
                    renderStatus()
                    return false
                }
            })
        }
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        if (!requestKeyboard) return
        requestKeyboard = false
        val searchView = _binding?.searchView ?: return
        dialog?.window?.let { window ->
            WindowCompat.getInsetsController(window, searchView).show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(QUERY, _binding?.searchView?.query?.toString() ?: viewModel.query.value)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        queryJob?.cancel()
        queryJob = null
        dialogJob?.cancel()
        dialogJob = null
        _binding?.searchView?.setOnQueryTextListener(null)
        _binding?.recyclerView?.adapter = null
        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }
}
