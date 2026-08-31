package com.github.andreyasadchy.xtra.ui.drops

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MenuItem
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
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
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.repository.mergeDropsWithDashboard
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.main.ProfileMenuBinder
import com.github.andreyasadchy.xtra.ui.main.TwitchInboxMenuBinder
import com.github.andreyasadchy.xtra.util.SettingsUpdateIndicator
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class DropsFragment : Fragment() {
    private var _binding: FragmentDropsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DropsViewModel by viewModels {
        DropsViewModel.factory((requireActivity().application as XtraApp).xtraModule.dropsRepository)
    }
    private lateinit var adapter: DropsAdapter
    private var selectedTab = TAB_INVENTORY
    private var campaignNavigationHandled = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDropsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navController = findNavController()
        val activity = requireActivity() as MainActivity
        ensureToolbarMenu()
        SettingsUpdateIndicator.update(binding.toolbar, activity)
        ProfileMenuBinder.bind(binding.toolbar, activity)
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
                R.id.settings -> {
                    activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        val baseRecyclerBottomPadding = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
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
        binding.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                selectedTab = tab.position
                render(viewModel.uiState.value)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (selectedTab == TAB_INVENTORY) binding.recyclerView.scrollToPosition(0)
            }
        })
        adapter = DropsAdapter(viewModel::claim, viewModel::loadCampaignDetails)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.retryButton.setOnClickListener {
            if (selectedTab == TAB_SOCIAL_BADGE) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TWITCH_DROPS_URL)))
            } else {
                viewModel.refresh()
            }
        }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        arguments?.getString("campaignId")?.takeIf(String::isNotBlank)?.let {
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
            }
        }
    }

    private fun render(state: DropsPageUiState) {
        binding.autoClaim.isVisible = selectedTab == TAB_INVENTORY
        binding.summary.isVisible = selectedTab == TAB_INVENTORY
        binding.swipeRefresh.isRefreshing = state.inventory.refreshing ||
            (selectedTab == TAB_ALL_CAMPAIGNS && state.campaignsRefreshing)
        val safeInventory = if (state.inventory.error == null) {
            state.inventory.drops
        } else {
            // Keep stale progress visible, but never leave a stale private-API claim action live.
            state.inventory.drops.map { it.copy(dropInstanceId = null) }
        }
        val drops = mergeDropsWithDashboard(safeInventory, state.campaigns)
        val rows = when (selectedTab) {
            TAB_ALL_CAMPAIGNS -> campaignRows(state)
            TAB_SOCIAL_BADGE -> emptyList()
            else -> inventoryRows(drops)
        }
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
        adapter.submitList(rows)
        focusRequestedCampaign(rows)
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
                else -> getString(R.string.drops_no_rewards)
            }
        } else if (binding.retryButton.isVisible && !socialBadge) {
            binding.emptyText.isVisible = true
            binding.emptyText.text = getString(R.string.drops_load_failed)
        }
    }

    private fun focusRequestedCampaign(rows: List<DropsRow>) {
        if (campaignNavigationHandled || selectedTab != TAB_ALL_CAMPAIGNS) return
        val requestedId = arguments?.getString("campaignId")?.takeIf(String::isNotBlank) ?: return
        val rowIndex = rows.indexOfFirst { row ->
            row is DropsRow.Campaign &&
                (row.value.id == requestedId || row.value.drops.any { it.id == requestedId })
        }
        if (rowIndex < 0) return
        val campaign = (rows[rowIndex] as DropsRow.Campaign).value
        campaignNavigationHandled = true
        adapter.expandCampaign(campaign.id)
        binding.recyclerView.post { binding.recyclerView.scrollToPosition(rowIndex) }
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

    private fun ensureToolbarMenu() {
        if (binding.toolbar.menu.findItem(R.id.search) == null) {
            binding.toolbar.inflateMenu(R.menu.top_menu)
        }
        binding.toolbar.menu.findItem(R.id.search)?.apply {
            isVisible = true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.toolbar.menu.findItem(R.id.settings)?.apply {
            isVisible = true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.toolbar.invalidate()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAB_INVENTORY = 0
        const val TAB_ALL_CAMPAIGNS = 1
        const val TAB_SOCIAL_BADGE = 2
        const val TWITCH_DROPS_URL = "https://www.twitch.tv/drops"
    }
}
