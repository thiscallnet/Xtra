package com.github.andreyasadchy.xtra.ui.drops

import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MenuItem
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentDropsBinding
import com.github.andreyasadchy.xtra.model.ui.DropStreamFilter
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
import com.github.andreyasadchy.xtra.repository.mergeDropsWithDashboard
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.main.TwitchInboxMenuBinder
import com.github.andreyasadchy.xtra.util.SettingsUpdateIndicator
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isTelevision
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

class DropsFragment : Fragment(), Scrollable {
    private var _binding: FragmentDropsBinding? = null
    private val binding get() = _binding!!
    private var stateContainerObserver: ViewTreeObserver? = null
    private val stateContainerPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        _binding?.let(::updateStateContainerHeight) ?: true
    }
    private val stateContainerAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (isResumed) observeStateContainer(view)
        }

        override fun onViewDetachedFromWindow(view: View) {
            stopObservingStateContainer()
        }
    }
    private val viewModel: DropsViewModel by viewModels {
        DropsViewModel.factory((requireActivity().application as XtraApp).xtraModule.dropsRepository)
    }
    private lateinit var adapter: DropsAdapter
    private var selectedTab = TAB_INVENTORY
    private var dropsQuery = ""
    private var requestedCampaignId: String? = null
    private var campaignNavigationHandled = false
    private var compactHeaderApplied: Boolean? = null
    private var imeWasVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = restoreDropsTab(savedInstanceState?.getInt(SELECTED_TAB))
        dropsQuery = savedInstanceState?.getString(SEARCH_QUERY).orEmpty()
        requestedCampaignId = arguments?.getString(FOCUS_CAMPAIGN_ID)?.takeIf(String::isNotBlank)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDropsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.appBar.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            behavior = AppBarLayout.Behavior().apply {
                // The header can exceed a short viewport. Let it be dragged back into view
                // even when the list behind it still has a nonzero scroll position.
                setDragCallback(object : AppBarLayout.Behavior.DragCallback() {
                    override fun canDrag(appBarLayout: AppBarLayout) = true
                })
            }
        }
        val header = binding.toolbar.parent as LinearLayout
        header.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (isCompactLandscape() && bottom - top != oldBottom - oldTop && oldBottom > oldTop) {
                val behavior = (binding.appBar.layoutParams as CoordinatorLayout.LayoutParams)
                    .behavior as AppBarLayout.Behavior
                val previousRange = (oldBottom - oldTop - header.minimumHeight).coerceAtLeast(0)
                // A newly loaded or wrapped summary changes the range. Keep an already
                // collapsed header anchored to its toolbar, without closing a dragged-open one.
                if (previousRange > 0 && behavior.topAndBottomOffset <= -previousRange) {
                    binding.appBar.setExpanded(false, false)
                }
            }
        }
        // The window's observer survives this view. Register only while attached
        // and remove the listener on both detach and view destruction.
        view.addOnAttachStateChangeListener(stateContainerAttachListener)
        if (view.isAttachedToWindow && isResumed) observeStateContainer(view)
        val navController = findNavController()
        val activity = requireActivity() as MainActivity
        ensureToolbarMenu()
        SettingsUpdateIndicator.update(binding.toolbar, activity)
        TwitchInboxMenuBinder.bind(binding.toolbar, activity)
        binding.toolbar.setupWithNavController(
            navController,
            AppBarConfiguration(
                setOf(
                    R.id.rootGamesFragment,
                    R.id.rootTopFragment,
                    R.id.followPagerFragment,
                    R.id.followMediaFragment,
                    R.id.savedPagerFragment,
                    R.id.savedMediaFragment,
                    R.id.statisticsFragment,
                    R.id.dropsFragment,
                ),
            ),
        )
        ensureToolbarMenu()
        binding.toolbar.title = getString(R.string.drops)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> {
                    navController.navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                    true
                }
                R.id.dropsSearch -> {
                    val currentBinding = binding
                    currentBinding.appBar.setExpanded(true, false)
                    currentBinding.appBar.doOnLayout {
                        if (_binding === currentBinding) {
                            currentBinding.searchView.requestFocus()
                            WindowCompat.getInsetsController(requireActivity().window, currentBinding.searchView)
                                .show(WindowInsetsCompat.Type.ime())
                        }
                    }
                    true
                }
                R.id.settings -> {
                    activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        val baseRecyclerBottomPadding = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            val imeClosed = imeWasVisible && !imeVisible
            imeWasVisible = imeVisible
            activity.setDropsFilterKeyboardVisible(
                isResumed && isCompactLandscape() && imeVisible && binding.searchView.hasFocus(),
            )
            if (imeClosed && isCompactLandscape() && binding.searchView.hasFocus()) {
                binding.searchView.clearFocus()
                binding.root.requestFocus()
                binding.appBar.setExpanded(false, false)
            }
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // Keep the status/cutout area fixed while the header scrolls away.
            view.updatePadding(top = insets.top)
            binding.recyclerView.updatePadding(
                bottom = baseRecyclerBottomPadding + insets.bottom,
            )
            windowInsets
        }
        binding.autoClaim.isChecked = requireContext().prefs().getBoolean(C.CHAT_DROPS_AUTO_CLAIM, false)
        binding.autoClaim.setOnCheckedChangeListener { _, enabled ->
            requireContext().prefs().edit { putBoolean(C.CHAT_DROPS_AUTO_CLAIM, enabled) }
        }
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.drops_inventory_tab))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.drops_all_campaigns_tab))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.drops_social_badge_tab))
        binding.searchView.setQuery(dropsQuery, false)
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (isCompactLandscape()) {
                    binding.searchView.clearFocus()
                    binding.root.requestFocus()
                    binding.appBar.setExpanded(false, true)
                }
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                dropsQuery = newText.trim()
                updateDropsSearchAction()
                render(viewModel.uiState.value)
                return true
            }
        })
        val dropsLiveUpdateManager = (requireActivity().application as XtraApp).xtraModule.dropsLiveUpdateManager
        adapter = DropsAdapter(
            onClaim = viewModel::claim,
            onCampaignClick = viewModel::loadCampaignDetails,
            onFindStreams = ::findStreamsForCampaign,
            onFindStreamsForDrop = ::findStreamsForDrop,
            onTrack = dropsLiveUpdateManager::toggle,
            onImageClick = ::showDropImage,
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                selectedTab = tab.position
                render(viewModel.uiState.value)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (selectedTab == TAB_INVENTORY) scrollToTop()
            }
        })
        parentFragmentManager.setFragmentResultListener(FOCUS_RESULT, viewLifecycleOwner) { _, result ->
            requestedCampaignId = result.getString(FOCUS_CAMPAIGN_ID)?.takeIf(String::isNotBlank)
            campaignNavigationHandled = false
            dropsQuery = ""
            binding.searchView.setQuery("", false)
            binding.tabs.getTabAt(TAB_ALL_CAMPAIGNS)?.select()
            render(viewModel.uiState.value)
        }
        // TabLayout starts with the first tab selected on every view recreation. Restore the
        // fragment's tab state before the first render so the tab label and rows cannot diverge.
        binding.tabs.getTabAt(selectedTab)?.select()
        binding.retryButton.setOnClickListener {
            if (selectedTab == TAB_SOCIAL_BADGE) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TWITCH_DROPS_URL)))
            } else {
                viewModel.refresh()
            }
        }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.recyclerView.canScrollVertically(-1) || binding.appBar.top < binding.root.paddingTop
        }
        requestedCampaignId?.let {
            binding.tabs.getTabAt(TAB_ALL_CAMPAIGNS)?.select()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { render(it) }
                }
                launch {
                    viewModel.claimResults.collect { result ->
                        Snackbar.make(
                            binding.root,
                            if (result.success) R.string.drops_claimed else R.string.drops_claim_failed,
                            Snackbar.LENGTH_SHORT,
                        ).show()
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        dropsLiveUpdateManager.update(state.inventory.drops)
                    }
                }
                launch {
                    dropsLiveUpdateManager.trackedDropId.collect(adapter::setTrackedDropId)
                }
            }
        }
        updateCompactHeader()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            updateCompactHeader()
            if (::adapter.isInitialized) adapter.refreshSectionSpacing()
            ensureToolbarMenu()
        }
    }

    private fun isCompactLandscape(): Boolean = resources.configuration.let {
        !requireContext().isTelevision() &&
            it.orientation == Configuration.ORIENTATION_LANDSCAPE && it.screenHeightDp < 480
    }

    private fun updateCompactHeader() {
        val compact = isCompactLandscape()
        if (compactHeaderApplied == compact) return
        val wasCompact = compactHeaderApplied == true
        compactHeaderApplied = compact
        val currentBinding = binding
        // Give closed filters a non-editable focus destination. Otherwise returning
        // from the image viewer can focus the hidden field and reopen the keyboard.
        currentBinding.root.isFocusableInTouchMode = compact
        currentBinding.root.setDefaultFocusHighlightEnabled(false)
        val header = currentBinding.toolbar.parent as LinearLayout
        header.removeView(currentBinding.toolbar)
        header.removeView(currentBinding.tabs)
        if (compact) {
            header.addView(currentBinding.toolbar)
            header.addView(currentBinding.tabs)
        } else {
            header.addView(currentBinding.toolbar, 0)
            header.addView(currentBinding.tabs, 1)
        }
        header.minimumHeight = 0
        header.updateLayoutParams<AppBarLayout.LayoutParams> {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
                if (compact) AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED else 0
        }
        if (compact) {
            header.doOnLayout {
                if (_binding === currentBinding && compactHeaderApplied == true) {
                    header.minimumHeight = currentBinding.toolbar.height + currentBinding.tabs.height
                    val editing = imeWasVisible && currentBinding.searchView.hasFocus()
                    if (!editing) {
                        currentBinding.searchView.clearFocus()
                        currentBinding.root.requestFocus()
                    }
                    currentBinding.appBar.setExpanded(editing, false)
                }
            }
        } else if (wasCompact) {
            currentBinding.appBar.setExpanded(true, false)
        }
    }

    fun focusNotification(campaignId: String?, dropId: String?) {
        requestedCampaignId = campaignId?.takeIf(String::isNotBlank) ?: dropId?.takeIf(String::isNotBlank)
        campaignNavigationHandled = false
        if (requestedCampaignId != null) {
            selectedTab = TAB_ALL_CAMPAIGNS
            binding.tabs.getTabAt(TAB_ALL_CAMPAIGNS)?.select()
            render(viewModel.uiState.value)
        }
    }

    private fun render(state: DropsPageUiState) {
        binding.autoClaim.isVisible = selectedTab == TAB_INVENTORY
        binding.summary.isVisible =
            selectedTab == TAB_INVENTORY && state.inventory.authenticated
        binding.swipeRefresh.isRefreshing = state.inventory.refreshing ||
            (selectedTab == TAB_ALL_CAMPAIGNS && state.campaignsRefreshing)
        val safeInventory = if (state.inventory.error == null) {
            state.inventory.drops
        } else {
            // Keep stale progress visible, but never leave a stale private-API claim action live.
            state.inventory.drops.map { it.copy(dropInstanceId = null) }
        }
        val drops = mergeDropsWithDashboard(safeInventory, state.campaigns)
        val allRows = when (selectedTab) {
            TAB_ALL_CAMPAIGNS -> campaignRows(state)
            TAB_SOCIAL_BADGE -> emptyList()
            else -> inventoryRows(drops)
        }
        val rows = filterRows(allRows, dropsQuery)
        if (state.inventory.authenticated) {
            binding.summary.text = getString(
                R.string.drops_summary,
                drops.count(TwitchDrop::isClaimable),
                state.campaigns.count { !it.isUpcoming },
            )
        } else {
            binding.summary.text = getString(R.string.drops_sign_in_required)
        }
        adapter.setClaimingDropId(state.claimingDropId)
        adapter.setCampaignDetailsLoading(state.campaignDetailsLoading)
        val pageBinding = binding
        adapter.submitList(rows) {
            if (_binding === pageBinding) focusRequestedCampaign(rows)
        }
        val loading = when (selectedTab) {
            TAB_ALL_CAMPAIGNS -> !state.campaignsLoaded && state.campaignsRefreshing
            else -> !state.inventory.loaded && state.inventory.refreshing
        }
        binding.loading.isVisible = loading
        val socialBadge = selectedTab == TAB_SOCIAL_BADGE
        val relevantError = when (selectedTab) {
            TAB_ALL_CAMPAIGNS -> state.dashboardError
            TAB_SOCIAL_BADGE -> null
            else -> state.inventory.error
        }
        binding.retryButton.isVisible = socialBadge || (!loading &&
            rows.isEmpty() &&
            relevantError != null)
        binding.retryButton.setText(if (socialBadge) R.string.account_open_twitch else R.string.retry)
        binding.emptyText.isVisible = !loading &&
            rows.isEmpty() &&
            relevantError == null
        if (binding.emptyText.isVisible) {
            binding.emptyText.text = when {
                !state.inventory.authenticated -> getString(R.string.drops_sign_in_required)
                selectedTab == TAB_SOCIAL_BADGE -> getString(R.string.drops_social_badge_unavailable)
                dropsQuery.isNotBlank() -> getString(R.string.drops_search_no_matches)
                else -> getString(R.string.drops_no_rewards)
            }
        } else if (binding.retryButton.isVisible && !socialBadge) {
            binding.emptyText.isVisible = true
            binding.emptyText.text = getString(R.string.drops_load_failed)
        }
        binding.stateContainer.isVisible = loading || binding.emptyText.isVisible || binding.retryButton.isVisible
        updateStateContainerHeight(binding)
    }

    private fun focusRequestedCampaign(rows: List<DropsRow>) {
        if (campaignNavigationHandled || selectedTab != TAB_ALL_CAMPAIGNS) return
        val requestedId = requestedCampaignId ?: return
        val rowIndex = rows.indexOfFirst { row ->
            row is DropsRow.Campaign &&
                (row.value.id == requestedId || row.value.drops.any { it.id == requestedId })
        }
        if (rowIndex < 0) return
        val campaign = (rows[rowIndex] as DropsRow.Campaign).value
        campaignNavigationHandled = true
        adapter.expandCampaign(campaign.id)
        val pageBinding = binding
        pageBinding.recyclerView.post {
            if (_binding === pageBinding) pageBinding.recyclerView.scrollToPosition(rowIndex)
        }
    }

    private fun findStreamsForCampaign(campaign: TwitchDropCampaign) {
        if (!campaignCanFindLiveStreams(campaign)) return
        findNavController().navigate(
            R.id.action_global_searchPagerFragment,
            SearchPagerFragment.dropsSearchArguments(campaign),
        )
    }

    private fun findStreamsForDrop(drop: TwitchDrop) {
        val campaignId = drop.campaignId?.takeIf(String::isNotBlank) ?: return
        val gameName = drop.gameName?.takeIf(String::isNotBlank) ?: return
        findNavController().navigate(
            R.id.action_global_searchPagerFragment,
            SearchPagerFragment.dropsSearchArguments(
                listOf(
                    DropStreamFilter(
                        campaignId = campaignId,
                        campaignName = drop.campaignName ?: gameName,
                        gameId = null,
                        gameName = gameName,
                        dropIds = setOf(drop.id),
                        dropNames = listOfNotNull(drop.name ?: drop.rewardName),
                    ),
                ),
            ),
        )
    }

    private fun showDropImage(
        url: String,
        name: String?,
        source: TwitchDropImageSource,
    ) {
        DropImageDialog.newInstance(url, name, source)
            .show(childFragmentManager, DropImageDialog.TAG)
    }

    private fun inventoryRows(drops: List<TwitchDrop>): List<DropsRow> = buildList {
        drops.filter(TwitchDrop::isClaimable).takeIf { it.isNotEmpty() }?.let {
            add(DropsRow.Section(getString(R.string.drops_ready_section).uppercase()))
            it.forEach { drop -> add(DropsRow.Drop(drop)) }
        }
        drops.filter { !it.isClaimed && !it.isClaimable }.takeIf { it.isNotEmpty() }?.let {
            add(DropsRow.Section(getString(R.string.drops_in_progress_section).uppercase()))
            it.forEach { drop -> add(DropsRow.Drop(drop)) }
        }
    }

    private fun campaignRows(state: DropsPageUiState): List<DropsRow> = buildList {
        state.campaigns.filterNot { it.isUpcoming }.takeIf { it.isNotEmpty() }?.let {
            add(DropsRow.Section(getString(R.string.drops_active_campaigns).uppercase()))
            it.forEach { campaign -> add(DropsRow.Campaign(campaign)) }
        }
        state.campaigns.filter { it.isUpcoming }.takeIf { it.isNotEmpty() }?.let {
            add(DropsRow.Section(getString(R.string.drops_upcoming).uppercase()))
            it.forEach { campaign -> add(DropsRow.Campaign(campaign)) }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureToolbarMenu()
        binding.autoClaim.isChecked = requireContext().prefs()
            .getBoolean(C.CHAT_DROPS_AUTO_CLAIM, false)
        // Re-check the session when returning from the account/login flow. The repository keeps
        // its last valid state visible while this reconciliation runs.
        viewModel.refresh(force = false)
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
        binding.appBar.setExpanded(true, true)
    }

    private fun updateStateContainerHeight(binding: FragmentDropsBinding): Boolean {
        if (!binding.stateContainer.isVisible) return true
        val height = (binding.root.height - binding.root.paddingBottom - binding.appBar.bottom).coerceAtLeast(0)
        if (binding.stateContainer.layoutParams.height != height) {
            binding.stateContainer.updateLayoutParams<ViewGroup.LayoutParams> { this.height = height }
            return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (binding.root.isAttachedToWindow) observeStateContainer(binding.root)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setDropsFilterKeyboardVisible(false)
        if (_binding != null && isCompactLandscape()) {
            binding.searchView.clearFocus()
            binding.root.requestFocus()
        }
        stopObservingStateContainer()
        super.onPause()
    }

    private fun observeStateContainer(view: View) {
        stopObservingStateContainer()
        stateContainerObserver = view.viewTreeObserver.also {
            it.addOnPreDrawListener(stateContainerPreDrawListener)
        }
    }

    private fun stopObservingStateContainer() {
        stateContainerObserver?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(stateContainerPreDrawListener)
        stateContainerObserver = null
    }

    private fun ensureToolbarMenu() {
        if (binding.toolbar.menu.findItem(R.id.search) == null) {
            binding.toolbar.inflateMenu(R.menu.top_menu)
        }
        binding.toolbar.menu.findItem(R.id.search)?.apply {
            isVisible = !(requireActivity() as MainActivity).hasBottomSearchShortcut()
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (isCompactLandscape() && binding.toolbar.menu.findItem(R.id.dropsSearch) == null) {
            binding.toolbar.inflateMenu(R.menu.drops_search_menu)
        }
        updateDropsSearchAction()
        binding.toolbar.menu.findItem(R.id.settings)?.apply {
            isVisible = true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.toolbar.invalidate()
    }

    private fun updateDropsSearchAction() {
        binding.toolbar.menu.findItem(R.id.dropsSearch)?.apply {
            isVisible = isCompactLandscape()
            title = if (dropsQuery.isBlank()) getString(R.string.drops_search_hint)
                else getString(R.string.drops_search_active, dropsQuery)
            iconTintList = ColorStateList.valueOf(MaterialColors.getColor(
                binding.toolbar,
                if (dropsQuery.isBlank()) androidx.appcompat.R.attr.colorControlNormal
                else androidx.appcompat.R.attr.colorPrimary,
            ))
        }
    }

    private fun filterRows(rows: List<DropsRow>, query: String): List<DropsRow> {
        if (query.isBlank()) return rows
        val result = mutableListOf<DropsRow>()
        var pendingSection: DropsRow.Section? = null
        rows.forEach { row ->
            when (row) {
                is DropsRow.Section -> pendingSection = row
                is DropsRow.Drop -> if (row.value.searchableText().contains(query, ignoreCase = true)) {
                    pendingSection?.let(result::add)
                    pendingSection = null
                    result += row
                }
                is DropsRow.Campaign -> if (row.value.searchableText().contains(query, ignoreCase = true)) {
                    pendingSection?.let(result::add)
                    pendingSection = null
                    result += row
                }
            }
        }
        return result
    }

    override fun onDestroyView() {
        compactHeaderApplied = null
        imeWasVisible = false
        stopObservingStateContainer()
        _binding?.root?.removeOnAttachStateChangeListener(stateContainerAttachListener)
        _binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTED_TAB, selectedTab)
        outState.putString(SEARCH_QUERY, dropsQuery)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val TAB_INVENTORY = 0
        const val TAB_ALL_CAMPAIGNS = 1
        const val TAB_SOCIAL_BADGE = 2
        const val SELECTED_TAB = "selected_drops_tab"
        const val SEARCH_QUERY = "drops_search_query"
        const val TWITCH_DROPS_URL = "https://www.twitch.tv/drops"
        const val FOCUS_RESULT = "drops-focus-result"
        const val FOCUS_CAMPAIGN_ID = "campaignId"
    }
}

private fun TwitchDrop.searchableText(): String = listOfNotNull(
    campaignName,
    gameName,
    name,
    rewardName,
    benefits.joinToString(" ") { it.name.orEmpty() },
).joinToString(" ")

private fun TwitchDropCampaign.searchableText(): String = listOfNotNull(
    name,
    gameName,
    drops.joinToString(" ") { drop ->
        listOfNotNull(
            drop.name,
            drop.benefits.joinToString(" ") { it.name.orEmpty() },
        ).joinToString(" ")
    },
).joinToString(" ")
