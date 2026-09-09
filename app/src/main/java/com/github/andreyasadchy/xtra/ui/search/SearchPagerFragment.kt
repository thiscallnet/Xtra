package com.github.andreyasadchy.xtra.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogUserResultBinding
import com.github.andreyasadchy.xtra.databinding.FragmentSearchBinding
import com.github.andreyasadchy.xtra.model.ui.DropStreamFilter
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.RecyclerViewLiftTargetConnector
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.Companion.SearchPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.search.streams.StreamSearchFragment
import com.github.andreyasadchy.xtra.ui.settings.setTabCustomizationLongPress
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
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

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchPagerViewModel by viewModels { SearchPagerViewModelFactory }
    private var firstLaunch = true
    private var liftTargetConnector: RecyclerViewLiftTargetConnector? = null
    private var dropsFilter: DropStreamFilter? = null
    private var initialQuery: String? = null
    private var initialTab = -1
    private var streamTabPosition = -1
    private var initialQueryPending = false

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
        initialQuery = if (savedInstanceState == null) arguments?.getString(INITIAL_QUERY) else null
        initialTab = if (savedInstanceState == null) arguments?.getInt(INITIAL_TAB, -1) ?: -1 else -1
        dropsFilter = if (savedInstanceState?.getBoolean(DROPS_FILTER_ENABLED) == false) {
            null
        } else {
            arguments?.getString(DROPS_CAMPAIGN_ID)?.takeIf { it.isNotBlank() }?.let { campaignId ->
                DropStreamFilter(
                    campaignId = campaignId,
                    campaignName = arguments?.getString(DROPS_CAMPAIGN_NAME).orEmpty(),
                    gameId = arguments?.getString(DROPS_GAME_ID),
                    gameName = arguments?.getString(DROPS_GAME_NAME).orEmpty(),
                    dropIds = arguments?.getStringArrayList(DROPS_DROP_IDS).orEmpty().toSet(),
                )
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        liftTargetConnector = RecyclerViewLiftTargetConnector(binding.appBar)
        with(binding) {
            val tabList = requireContext().prefs().getString(C.UI_SEARCH_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_SEARCH_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
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
                this@SearchPagerFragment.dropsFilter != null,
            )
            streamTabPosition = tabs.indexOf("1")
            if (tabs.size <= 1) {
                tabLayout.visibility = View.GONE
            } else {
                if (tabs.size >= 5) {
                    tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                    tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                }
            }
            val adapter = SearchPagerAdapter(this@SearchPagerFragment, tabs, this@SearchPagerFragment.dropsFilter)
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
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
                    ?: tabList.find { it.split(':')[1] != "0" }?.split(':')?.get(0)
                    ?: "2"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("2").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.configureForSmoothPaging()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.streams)
                    "2" -> getString(R.string.channels)
                    "3" -> getString(R.string.games)
                    else -> getString(R.string.channels)
                }
            }.attach()
            this@SearchPagerFragment.dropsFilter?.let { filter ->
                binding.dropsFilter.text = getString(R.string.search_drops_filter, filter.campaignName)
                binding.dropsFilter.contentDescription = getString(R.string.search_drops_filter, filter.campaignName)
                binding.dropsFilter.setOnCloseIconClickListener { clearDropsFilter() }
            }
            updateDropsFilterVisibility()
            tabLayout.setTabCustomizationLongPress(requireContext(), C.UI_SEARCH_TABS)
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.searchUser -> {
                        val binding = DialogUserResultBinding.inflate(layoutInflater)
                        requireContext().getAlertDialogBuilder().apply {
                            setView(binding.root)
                            setNegativeButton(getString(android.R.string.cancel), null)
                            setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                                val result = binding.editText.editText?.text?.toString()
                                val checkedId = if (binding.radioButton.isChecked) 0 else 1
                                if (!result.isNullOrBlank()) {
                                    userResult = Pair(checkedId, result)
                                    viewModel.loadUserResult(
                                        checkedId = checkedId,
                                        result = result,
                                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                    )
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                                            viewModel.userResult.collectLatest {
                                                if (it != null) {
                                                    if (!it.first.isNullOrBlank()) {
                                                        requireContext().getAlertDialogBuilder().apply {
                                                            setTitle(it.first)
                                                            setMessage(it.second)
                                                            setNegativeButton(getString(android.R.string.cancel), null)
                                                            setPositiveButton(getString(R.string.view_profile)) { _, _ -> viewUserResult() }
                                                        }.show()
                                                    } else {
                                                        viewUserResult()
                                                    }
                                                    viewModel.userResult.value = null
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            setNeutralButton(getString(R.string.view_profile)) { _, _ ->
                                val result = binding.editText.editText?.text?.toString()
                                val checkedId = if (binding.radioButton.isChecked) 0 else 1
                                if (!result.isNullOrBlank()) {
                                    userResult = Pair(checkedId, result)
                                    viewUserResult()
                                }
                            }
                        }.show()
                        true
                    }
                    else -> false
                }
            }
            searchView.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, searchView).show(WindowInsetsCompat.Type.ime())
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }
    }

    override fun initialize() {
        initialQueryPending = initialQuery?.isNotBlank() == true
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var job: Job? = null

            override fun onQueryTextSubmit(query: String): Boolean {
                searchCurrent(query.trim())
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                job?.cancel()
                val query = newText.trim()
                if (query.isNotEmpty()) {
                    job = lifecycleScope.launch {
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
            lifecycleScope.launch {
                repeat(5) {
                    if (!initialQueryPending) return@launch
                    delay(100L)
                    withResumed {
                        if (initialQueryPending && currentFragment != null) {
                            searchCurrent(query)
                        }
                    }
                }
            }
        }
    }

    private fun searchCurrent(query: String) {
        val fragment = currentFragment ?: return
        val isInitialQuery = initialQueryPending && query == initialQuery
        if (isInitialQuery) {
            (fragment as? StreamSearchFragment)?.searchWithoutSaving(query)
                ?: (fragment as? Searchable)?.search(query)
            initialQueryPending = false
        } else {
            (fragment as? Searchable)?.search(query)
        }
    }

    fun setQuery(query: String?) {
        binding.searchView.setQuery(query, true)
    }

    fun currentDropsFilter(): DropStreamFilter? = dropsFilter

    private fun clearDropsFilter() {
        dropsFilter = null
        binding.dropsFilter.isVisible = false
        childFragmentManager.fragments
            .filterIsInstance<StreamSearchFragment>()
            .forEach(StreamSearchFragment::clearDropsFilter)
    }

    private fun updateDropsFilterVisibility(position: Int = binding.viewPager.currentItem) {
        binding.dropsFilter.isVisible = shouldShowDropsFilter(
            filterActive = dropsFilter != null,
            selectedTabPosition = position,
            streamTabPosition = streamTabPosition,
        )
    }

    private var userResult: Pair<Int?, String?>? = null

    private fun viewUserResult() {
        userResult?.let {
            when (it.first) {
                0 -> findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = it.second
                    )
                )
                1 -> findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelLogin = it.second
                    )
                )
                else -> {}
            }
        }
    }

    override fun onNetworkRestored() {
    }

    override fun onDestroyView() {
        dispatchPagerScrollState(false)
        liftTargetConnector?.disconnect()
        liftTargetConnector = null
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(DROPS_FILTER_ENABLED, dropsFilter != null)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val INITIAL_QUERY = "initial_search_query"
        const val INITIAL_TAB = "initial_search_tab"
        const val DROPS_FILTER_ENABLED = "drops_filter_enabled"
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
        }
    }
}


