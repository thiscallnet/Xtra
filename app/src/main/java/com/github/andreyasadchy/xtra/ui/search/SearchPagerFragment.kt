package com.github.andreyasadchy.xtra.ui.search

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogUserResultBinding
import com.github.andreyasadchy.xtra.databinding.FragmentSearchBinding
import com.github.andreyasadchy.xtra.model.ui.DropStreamFilter
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.ranked
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.ui.drops.DropFiltersBottomSheet
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.RecyclerViewLiftTargetConnector
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.Companion.SearchPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.UserLookupRequest
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.UserLookupState
import com.github.andreyasadchy.xtra.ui.search.streams.StreamSearchFragment
import com.github.andreyasadchy.xtra.ui.search.streams.SearchStreamSuggestionsHeaderAdapter
import com.github.andreyasadchy.xtra.ui.settings.setTabCustomizationLongPress
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun searchTabsForDropsFilter(
    configuredTabs: List<String>,
    filterActive: Boolean,
): List<String> {
    if (!filterActive || "1" in configuredTabs) return configuredTabs
    return configuredTabs.toMutableList().apply {
        val insertAt = indexOf("0").takeIf { it >= 0 }?.plus(1) ?: size
        add(insertAt, "1")
    }
}

internal fun shouldShowDropsFilter(
    filterActive: Boolean,
    selectedTabPosition: Int,
    streamTabPosition: Int,
): Boolean = filterActive && selectedTabPosition == streamTabPosition

class SearchPagerFragment : BaseNetworkFragment(), FragmentHost {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchPagerViewModel by viewModels { SearchPagerViewModelFactory }
    private var firstLaunch = true
    private var liftTargetConnector: RecyclerViewLiftTargetConnector? = null
    private var dropsFilters: List<DropStreamFilter> = emptyList()
    private var initialQuery: String? = null
    private var initialTab = -1
    private var streamTabPosition = -1
    private var queryBeforeDropsFilters: String? = null
    private var suppressQuerySearch = false
    private lateinit var streamSuggestionsAdapter: SearchStreamSuggestionsHeaderAdapter
    private lateinit var recentSearchesAdapter: SearchRowAdapter
    private var searchTabKeys: List<String> = emptyList()
    private var showCategories = false
    private var categoriesHidden = false
    private var backToAll: OnBackPressedCallback? = null
    private var lookupDialog: AlertDialog? = null
    private var lookupBinding: DialogUserResultBinding? = null
    private var restoredLookup: UserLookupRequest? = null
    private val searchPageCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (_binding != null && fragment === currentFragment) {
                searchCurrent(binding.searchView.query.toString().trim())
            }
        }
    }

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
        restoredLookup = savedInstanceState?.getString("lookupInput")?.let {
            UserLookupRequest(savedInstanceState.getBoolean("lookupById"), it)
        }
        initialQuery = if (savedInstanceState == null) arguments?.getString(INITIAL_QUERY) else null
        initialTab = if (savedInstanceState == null) arguments?.getInt(INITIAL_TAB, -1) ?: -1 else -1
        queryBeforeDropsFilters = savedInstanceState?.getString(DROPS_QUERY_BEFORE)
        dropsFilters = if (savedInstanceState?.getBoolean(DROPS_FILTER_ENABLED) == false) {
            emptyList()
        } else {
            savedInstanceState?.parcelableArrayList<DropStreamFilter>(DROPS_FILTERS)?.toList()
                ?: arguments?.parcelableArrayList<DropStreamFilter>(DROPS_FILTERS)?.toList()
                ?: arguments?.getString(DROPS_CAMPAIGN_ID)?.takeIf { it.isNotBlank() }?.let { campaignId ->
                    listOf(
                        DropStreamFilter(
                            campaignId = campaignId,
                            campaignName = arguments?.getString(DROPS_CAMPAIGN_NAME).orEmpty(),
                            gameId = arguments?.getString(DROPS_GAME_ID),
                            gameName = arguments?.getString(DROPS_GAME_NAME).orEmpty(),
                            dropIds = arguments?.getStringArrayList(DROPS_DROP_IDS).orEmpty().toSet(),
                        ),
                    )
                }.orEmpty()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(searchPageCallbacks, false)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                restoredLookup?.let { showUserLookupDialog(it) }
                restoredLookup = null
                viewModel.userLookup.collectLatest(::renderUserLookup)
            }
        }
        liftTargetConnector = RecyclerViewLiftTargetConnector(binding.appBar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchLandingScrollView) { scrollView, windowInsets ->
            val navigationRailIsVisible =
                activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false
            val bottomInset = if (navigationRailIsVisible) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            } else {
                0
            }
            scrollView.updatePadding(bottom = bottomInset)
            windowInsets
        }
        childFragmentManager.setFragmentResultListener(
            DropFiltersBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            applyDropsFilters(result.parcelableArrayList<DropStreamFilter>(DropFiltersBottomSheet.FILTERS_KEY).orEmpty())
        }
        with(binding) {
            val tabList = SearchTabs.resolve(requireContext().prefs().getString(C.UI_SEARCH_TABS, null))
            showCategories = requireContext().prefs().getBoolean(C.UI_SEARCH_SHOW_CATEGORIES, false)
            val configuredTabs = tabList.mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[2] != "0"
                if (enabled) {
                    key
                } else {
                    null
                }
            }
            val tabs = searchTabsForDropsFilter(
                configuredTabs,
                this@SearchPagerFragment.dropsFilters.isNotEmpty(),
            )
            streamSuggestionsAdapter = SearchStreamSuggestionsHeaderAdapter(this@SearchPagerFragment)
            searchTabKeys = tabs
            recentSearchesAdapter = SearchRowAdapter(
                onOpen = { SearchHistoryRecorder.open(this@SearchPagerFragment, it.item) },
                onRemove = ::removeHistory,
            )
            recentSearchesClear.setOnClickListener { clearHistory() }
            searchSuggestions.apply {
                layoutManager = LinearLayoutManager(requireContext())
                itemAnimator = null
                isNestedScrollingEnabled = false
                adapter = streamSuggestionsAdapter
            }
            recentSearchesRecyclerView.apply {
                layoutManager = LinearLayoutManager(requireContext())
                itemAnimator = null
                isNestedScrollingEnabled = false
                adapter = recentSearchesAdapter
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.cachedSuggestions.collectLatest {
                        streamSuggestionsAdapter.submitStreams(it)
                        updateLandingContent()
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.history.collectLatest {
                        updateLandingContent()
                        viewModel.refreshLive(it)
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.live.collectLatest { updateLandingContent() }
                }
            }
            streamTabPosition = tabs.indexOf("1")
            val allAvailable = SearchTabs.ALL in tabs
            categoriesHidden = allAvailable && !showCategories
            if (tabs.size <= 1 || categoriesHidden) {
                tabLayout.visibility = View.GONE
            } else {
                if (tabs.size >= 5) {
                    tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                    tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                }
            }
            val adapter = SearchPagerAdapter(this@SearchPagerFragment, tabs, this@SearchPagerFragment.dropsFilters)
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
                    backToAll?.isEnabled = categoriesHidden && tabs.getOrNull(position) != SearchTabs.ALL
                    updateDropsFilterVisibility(position)
                    searchCurrent(binding.searchView.query.toString())
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${position}")?.let { fragment ->
                            fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
                                liftTargetConnector?.connect(it)
                            }
                            if (fragment is Sortable) {
                                fragment.setupSortBar(sortBar)
                            } else {
                                sortBar.root.visibility = View.GONE
                            }
                        }
                    }
                }
            })
            if (firstLaunch) {
                val requestedTab = initialTab.toString().takeIf { it in tabs }
                val defaultItem = requestedTab
                    ?: SearchTabs.ALL.takeIf { categoriesHidden }
                    ?: tabList.find { it.split(':')[1] != "0" }?.split(':')?.get(0)
                    ?: "2"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("2").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.configureForSmoothPaging()
            // Categories are reached from "See all" in All, so swiping between them is off unless shown.
            viewPager.isUserInputEnabled = !categoriesHidden
            backToAll = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    openTab(SearchTabs.ALL)
                }
            }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
            // The restored page is only applied after layout, so read it then.
            viewPager.post {
                backToAll?.isEnabled = categoriesHidden && tabs.getOrNull(viewPager.currentItem) != SearchTabs.ALL
            }
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.streams)
                    "2" -> getString(R.string.channels)
                    "3" -> getString(R.string.games)
                    "4" -> getString(R.string.search_all)
                    else -> getString(R.string.channels)
                }
            }.attach()
            binding.dropsFilterButton.setOnClickListener { showDropsFilterPicker() }
            renderDropsFilters()
            updateDropsFilterVisibility()
            updateSearchLandingVisibility()
            tabLayout.setTabCustomizationLongPress(requireContext(), C.UI_SEARCH_TABS)
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.setNavigationOnClickListener {
                if (backToAll?.isEnabled == true) {
                    openTab(SearchTabs.ALL)
                } else {
                    NavigationUI.navigateUp(navController, appBarConfiguration)
                }
            }
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.searchUser -> {
                        showUserLookupDialog()
                        true
                    }
                    else -> false
                }
            }
            searchView.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, searchView).show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun initialize() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var job: Job? = null

            override fun onQueryTextSubmit(query: String): Boolean {
                if (suppressQuerySearch) return false
                if (dropsFilters.isNotEmpty() && query.isNotBlank()) {
                    clearDropsFiltersForTextSearch()
                }
                updateSearchLandingVisibility()
                searchCurrent(query.trim())
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (suppressQuerySearch) return false
                job?.cancel()
                val query = newText.trim()
                if (dropsFilters.isNotEmpty() && query.isNotBlank()) {
                    clearDropsFiltersForTextSearch()
                }
                updateSearchLandingVisibility()
                if (query.isNotEmpty()) {
                    job = viewLifecycleOwner.lifecycleScope.launch {
                        delay(350L)
                        withResumed {
                            searchCurrent(query)
                        }
                    }
                } else {
                    searchCurrent(query) //might be null on rotation, so as?
                }
                return false
            }
        })
        initialQuery?.takeIf { it.isNotBlank() }?.let { query ->
            binding.searchView.setQuery(query, false)
        }
        updateSearchLandingVisibility()
        binding.viewPager.post {
            if (_binding != null) searchCurrent(binding.searchView.query.toString().trim())
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCachedSuggestions()
        viewModel.refreshLive(viewModel.history.value)
        if (_binding != null) updateLandingContent()
    }

    private fun searchCurrent(query: String) {
        val fragment = currentFragment ?: return
        (fragment as? Searchable)?.search(query)
    }

    /** Whether the user has this search tab turned on ("0" videos, "1" streams, "2" channels, "3" games, "4" all). */
    fun isTabEnabled(key: String) = key in searchTabKeys

    fun openTab(key: String) {
        val position = searchTabKeys.indexOf(key)
        if (_binding != null && position >= 0) binding.viewPager.setCurrentItem(position, !categoriesHidden)
    }

    /**
     * Turns a tab's "nothing here" text into a shortcut to the All tab, so a query typed into the
     * wrong category is one tap from finding what it matches elsewhere.
     */
    fun bindEmptyHint(view: android.widget.TextView, ownKey: String) {
        val canSuggestAll = ownKey != "4" && isTabEnabled("4") &&
            _binding != null && !binding.searchView.query.isNullOrBlank()
        view.setText(if (canSuggestAll) R.string.search_empty_try_all else R.string.nothing_here)
        view.setOnClickListener(if (canSuggestAll) View.OnClickListener { openTab("4") } else null)
        view.isClickable = canSuggestAll
    }

    private fun removeHistory(item: SearchHistoryItem) {
        viewModel.removeHistory(item)
        showHistoryUndo(getString(R.string.search_history_removed, item.title), listOf(item))
    }

    private fun clearHistory() {
        val all = viewModel.history.value
        viewModel.clearHistory()
        showHistoryUndo(getString(R.string.search_history_cleared), all)
    }

    private fun showHistoryUndo(message: String, removed: List<SearchHistoryItem>) {
        // Top-anchored: the floating mini player covers a bottom snackbar's Undo button.
        val snackbar = Snackbar.make(binding.coordinatorLayout, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.search_history_undo) { viewModel.restoreHistory(removed) }
        (snackbar.view.layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            it.gravity = Gravity.TOP
            it.topMargin = binding.appBar.bottom
            snackbar.view.layoutParams = it
        }
        snackbar.show()
    }

    private fun updateLandingContent() {
        if (_binding == null || !::recentSearchesAdapter.isInitialized) return
        val items = if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewModel.history.value.ranked().take(MAX_LANDING_ITEMS)
        } else {
            emptyList()
        }
        val live = viewModel.live.value
        recentSearchesAdapter.submitList(items.map { searchEntry(requireContext(), it, live, removable = true) })
        binding.recentSearchesHeader.isVisible = items.isNotEmpty()
        val showIntro = viewModel.cachedSuggestions.value.isEmpty() && items.isEmpty()
        binding.searchLandingTitle.isVisible = showIntro
        binding.searchLandingSummary.isVisible = showIntro
    }

    private fun updateSearchLandingVisibility() {
        if (_binding == null) return
        val showLanding = binding.searchView.query.isNullOrBlank() && dropsFilters.isEmpty()
        binding.searchLanding.isVisible = showLanding
        binding.viewPager.isVisible = !showLanding
    }

    fun currentDropsFilter(): DropStreamFilter? = dropsFilters.firstOrNull()

    fun currentDropsFilters(): List<DropStreamFilter> = dropsFilters

    private fun clearDropsFilter() {
        applyDropsFilters(emptyList())
    }

    private fun applyDropsFilters(filters: List<DropStreamFilter>) {
        applyDropsFilters(filters, restorePreviousQuery = true)
    }

    private fun clearDropsFiltersForTextSearch() {
        queryBeforeDropsFilters = null
        applyDropsFilters(emptyList(), restorePreviousQuery = false)
    }

    private fun applyDropsFilters(
        filters: List<DropStreamFilter>,
        restorePreviousQuery: Boolean,
    ) {
        val nextFilters = filters.distinctBy { it.campaignId to it.dropIds }
        if (dropsFilters.isEmpty() && nextFilters.isNotEmpty()) {
            queryBeforeDropsFilters = binding.searchView.query.toString().trim()
        }
        dropsFilters = nextFilters
        updateSearchLandingVisibility()
        renderDropsFilters()
        if (dropsFilters.isNotEmpty() && streamTabPosition >= 0 &&
            binding.viewPager.currentItem != streamTabPosition
        ) {
            binding.viewPager.setCurrentItem(streamTabPosition, false)
        }
        childFragmentManager.fragments
            .filterIsInstance<StreamSearchFragment>()
            .forEach { it.applyDropsFilters(dropsFilters) }
        if (dropsFilters.isEmpty() && restorePreviousQuery) {
            queryBeforeDropsFilters?.let(::setSearchQueryWithoutSaving)
            queryBeforeDropsFilters = null
        } else if (dropsFilters.isNotEmpty()) {
            // Drop mode discovers streams from the selected campaigns' games. The old text
            // query would be misleading here, especially when the picker selection changes
            // from one game to another, so the chips become the visible search context.
            setSearchQueryWithoutSaving("")
        }
    }

    private fun setSearchQueryWithoutSaving(query: String) {
        if (binding.searchView.query.toString() != query) {
            suppressQuerySearch = true
            binding.searchView.setQuery(query, false)
            suppressQuerySearch = false
        }
        childFragmentManager.fragments
            .filterIsInstance<StreamSearchFragment>()
            .forEach { it.search(query) }
        updateSearchLandingVisibility()
    }

    private fun renderDropsFilters() {
        if (_binding == null) return
        binding.dropsFilterGroup.removeAllViews()
        dropsFilters.forEach { filter ->
            binding.dropsFilterGroup.addView(
                com.google.android.material.chip.Chip(requireContext()).apply {
                    text = getString(R.string.search_drops_filter, filter.displayName)
                    contentDescription = getString(R.string.search_drops_filter, filter.displayName)
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        applyDropsFilters(dropsFilters - filter)
                    }
                },
            )
        }
        binding.dropsFilterGroup.isVisible = dropsFilters.isNotEmpty() &&
            binding.viewPager.currentItem == streamTabPosition
        binding.dropsFilterButton.isVisible = streamTabPosition >= 0
    }

    private fun updateDropsFilterVisibility(position: Int = binding.viewPager.currentItem) {
        binding.dropsFilterGroup.isVisible = shouldShowDropsFilter(
            filterActive = dropsFilters.isNotEmpty(),
            selectedTabPosition = position,
            streamTabPosition = streamTabPosition,
        )
    }

    private fun showUserLookupDialog(request: UserLookupRequest? = null) {
        if (lookupDialog != null) return
        val input = DialogUserResultBinding.inflate(layoutInflater)
        lookupBinding = input
        request?.let {
            input.radioButton.isChecked = it.byId
            input.radioButton2.isChecked = !it.byId
            input.editText.editText?.setText(it.input)
        }
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(input.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.view_profile, null)
            .create()
        lookupDialog = dialog
        dialog.setOnDismissListener {
            lookupDialog = null
            lookupBinding = null
            viewModel.clearUserLookup()
        }
        dialog.setOnShowListener {
            fun currentRequest(): UserLookupRequest? = input.editText.editText?.text?.toString()
                ?.trim()?.takeIf(String::isNotEmpty)?.let { UserLookupRequest(input.radioButton.isChecked, it) }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                currentRequest()?.let {
                    viewModel.loadUserResult(
                        request = it,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    )
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                currentRequest()?.let {
                    dialog.dismiss()
                    viewUserResult(it)
                }
            }
        }
        dialog.show()
    }

    private fun renderUserLookup(state: UserLookupState) {
        when (state) {
            UserLookupState.Idle -> return
            is UserLookupState.Success -> {
                viewModel.clearUserLookup()
                lookupDialog?.dismiss()
                if (state.type.isNullOrBlank()) {
                    viewUserResult(state.request)
                } else {
                    requireContext().getAlertDialogBuilder()
                        .setTitle(state.type)
                        .setMessage(state.reason)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.view_profile) { _, _ -> viewUserResult(state.request) }
                        .show()
                }
                return
            }
            is UserLookupState.Loading -> showUserLookupDialog(state.request)
            is UserLookupState.Failed -> showUserLookupDialog(state.request)
        }
        val loading = state is UserLookupState.Loading
        lookupBinding?.apply {
            editText.error = if (state is UserLookupState.Failed) getString(R.string.error_loading_user) else null
            editText.helperText = if (loading) getString(R.string.loading) else null
            editText.editText?.isEnabled = !loading
            radioButton.isEnabled = !loading
            radioButton2.isEnabled = !loading
        }
        lookupDialog?.apply {
            getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = !loading
                setText(if (state is UserLookupState.Failed) R.string.retry else android.R.string.ok)
            }
            getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !loading
        }
    }

    private fun viewUserResult(request: UserLookupRequest) {
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = request.input.takeIf { request.byId },
                channelLogin = request.input.takeUnless { request.byId },
            )
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val input = lookupBinding ?: return
        val request = UserLookupRequest(input.radioButton.isChecked, input.editText.editText?.text?.toString().orEmpty())
        val wasDraft = viewModel.userLookup.value == UserLookupState.Idle
        // MainActivity handles rotation itself; floating dialogs retain their old
        // window constraints unless rebuilt for the new configuration.
        dismissLookupForRecreation()
        binding.root.post {
            if (_binding == null || !isResumed || lookupDialog != null) return@post
            val state = viewModel.userLookup.value
            if (state == UserLookupState.Idle) {
                if (wasDraft) showUserLookupDialog(request)
            } else {
                renderUserLookup(state)
            }
        }
    }

    private fun dismissLookupForRecreation() {
        lookupDialog?.setOnDismissListener(null)
        lookupDialog?.dismiss()
        lookupDialog = null
        lookupBinding = null
    }

    override fun onNetworkRestored() {
        searchCurrent(binding.searchView.query.toString().trim())
    }

    override fun onDestroyView() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(searchPageCallbacks)
        dismissLookupForRecreation()
        dispatchPagerScrollState(false)
        liftTargetConnector?.disconnect()
        liftTargetConnector = null
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lookupBinding?.let {
            outState.putString("lookupInput", it.editText.editText?.text?.toString())
            outState.putBoolean("lookupById", it.radioButton.isChecked)
        }
        outState.putBoolean(DROPS_FILTER_ENABLED, dropsFilters.isNotEmpty())
        outState.putParcelableArrayList(DROPS_FILTERS, ArrayList(dropsFilters))
        queryBeforeDropsFilters?.let { outState.putString(DROPS_QUERY_BEFORE, it) }
        super.onSaveInstanceState(outState)
    }

    private fun showDropsFilterPicker() {
        if (!isAdded || childFragmentManager.isStateSaved) return
        DropFiltersBottomSheet.newInstance(dropsFilters)
            .show(childFragmentManager, DropFiltersBottomSheet.TAG)
    }

    companion object {
        private const val MAX_LANDING_ITEMS = 10
        const val INITIAL_QUERY = "initial_search_query"
        const val INITIAL_TAB = "initial_search_tab"
        const val DROPS_FILTER_ENABLED = "drops_filter_enabled"
        const val DROPS_FILTERS = "drops_filters"
        private const val DROPS_QUERY_BEFORE = "drops_query_before"
        const val DROPS_CAMPAIGN_ID = "drops_campaign_id"
        const val DROPS_CAMPAIGN_NAME = "drops_campaign_name"
        const val DROPS_GAME_ID = "drops_game_id"
        const val DROPS_GAME_NAME = "drops_game_name"
        const val DROPS_DROP_IDS = "drops_drop_ids"

        fun dropsSearchArguments(campaign: TwitchDropCampaign) = Bundle().apply {
            putString(INITIAL_QUERY, campaign.gameName)
            putInt(INITIAL_TAB, 1)
            putString(DROPS_CAMPAIGN_ID, campaign.id)
            putString(DROPS_CAMPAIGN_NAME, campaign.name ?: campaign.gameName)
            putString(DROPS_GAME_ID, campaign.gameId)
            putString(DROPS_GAME_NAME, campaign.gameName)
            putStringArrayList(DROPS_DROP_IDS, ArrayList(campaign.drops.map { it.id }))
            putParcelableArrayList(
                DROPS_FILTERS,
                arrayListOf(
                    DropStreamFilter(
                        campaignId = campaign.id,
                        campaignName = campaign.name ?: campaign.gameName.orEmpty(),
                        gameId = campaign.gameId,
                        gameName = campaign.gameName.orEmpty(),
                        dropIds = campaign.drops.map { it.id }.toSet(),
                        dropNames = campaign.drops.mapNotNull { it.name },
                    ),
                ),
            )
        }

        fun dropsSearchArguments(filters: List<DropStreamFilter>) = Bundle().apply {
            val first = filters.firstOrNull() ?: return@apply
            putString(INITIAL_QUERY, "")
            putInt(INITIAL_TAB, 1)
            putParcelableArrayList(DROPS_FILTERS, ArrayList(filters))
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }


