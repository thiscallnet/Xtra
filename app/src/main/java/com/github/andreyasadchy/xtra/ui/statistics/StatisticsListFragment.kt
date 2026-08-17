package com.github.andreyasadchy.xtra.ui.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentStatisticsListBinding
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StatisticsListFragment : Fragment() {
    private var _binding: FragmentStatisticsListBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: StatisticsListViewModel
    private lateinit var channelAdapter: StatisticsChannelAdapter
    private lateinit var categoryAdapter: StatisticsCategoryAdapter
    private var bottomPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireContext().applicationContext as XtraApp
        viewModel = ViewModelProvider(
            this,
            StatisticsListViewModel.factory(
                app.xtraModule.viewingStatsRepository,
                requireArguments().getString(ARG_TYPE, StatisticsListViewModel.TYPE_CHANNEL),
            ),
        )[StatisticsListViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatisticsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val isCategory = requireArguments().getString(ARG_TYPE) == StatisticsListViewModel.TYPE_CATEGORY
        binding.toolbar.setupWithNavController(findNavController(), AppBarConfiguration(setOf(R.id.statisticsFragment)))
        binding.toolbar.title = if (isCategory) getString(R.string.statistics_top_categories) else getString(R.string.statistics_top_channels)
        bottomPadding = binding.content.paddingBottom
        channelAdapter = StatisticsChannelAdapter { item ->
            findNavController().navigate(R.id.statisticsDetailFragment, StatisticsDetailFragment.argumentsForChannel(item.channelId, item.channelLogin, item.channelName, item.channelImage))
        }
        categoryAdapter = StatisticsCategoryAdapter { item ->
            findNavController().navigate(R.id.statisticsDetailFragment, StatisticsDetailFragment.argumentsForCategory(item.categoryKey, item.categoryName, item.categoryId, item.categoryImage))
        }
        binding.channels.adapter = channelAdapter
        binding.categories.adapter = categoryAdapter
        binding.channels.layoutManager = LinearLayoutManager(requireContext())
        binding.categories.layoutManager = LinearLayoutManager(requireContext())
        binding.rangeGroup.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.range7 -> viewModel.selectRange(ViewingStatsRange.LAST_7_DAYS)
                R.id.range30 -> viewModel.selectRange(ViewingStatsRange.LAST_30_DAYS)
                R.id.range90 -> viewModel.selectRange(ViewingStatsRange.LAST_90_DAYS)
                R.id.rangeYear -> viewModel.selectRange(ViewingStatsRange.LAST_YEAR)
                R.id.rangeAll -> viewModel.selectRange(ViewingStatsRange.ALL_TIME)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest(::render)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            binding.content.setPadding(binding.content.paddingLeft, binding.content.paddingTop, binding.content.paddingRight, bars.bottom + bottomPadding)
            insets
        }
    }

    private fun render(state: StatisticsListUiState) {
        val result = state.result ?: return
        binding.progress.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.rangeGroup.check(
            when (state.range) {
                ViewingStatsRange.LAST_7_DAYS -> R.id.range7
                ViewingStatsRange.LAST_30_DAYS -> R.id.range30
                ViewingStatsRange.LAST_90_DAYS -> R.id.range90
                ViewingStatsRange.LAST_YEAR -> R.id.rangeYear
                ViewingStatsRange.ALL_TIME -> R.id.rangeAll
            },
        )
        when (result) {
            is StatisticsListResult.Channels -> {
                channelAdapter.submitChannels(result.items, result.totalWatchMs)
                binding.channels.visibility = if (result.items.isEmpty()) View.GONE else View.VISIBLE
                binding.categories.visibility = View.GONE
                binding.empty.setText(R.string.statistics_no_channels)
                binding.empty.visibility = if (result.items.isEmpty()) View.VISIBLE else View.GONE
            }
            is StatisticsListResult.Categories -> {
                categoryAdapter.submitCategories(result.items, result.totalWatchMs)
                binding.categories.visibility = if (result.items.isEmpty()) View.GONE else View.VISIBLE
                binding.channels.visibility = View.GONE
                binding.empty.setText(R.string.statistics_no_categories)
                binding.empty.visibility = if (result.items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        binding.channels.adapter = null
        binding.categories.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TYPE = "statistics_list_type"
        fun argumentsForChannels() = bundleOf(ARG_TYPE to StatisticsListViewModel.TYPE_CHANNEL)
        fun argumentsForCategories() = bundleOf(ARG_TYPE to StatisticsListViewModel.TYPE_CATEGORY)
    }
}
