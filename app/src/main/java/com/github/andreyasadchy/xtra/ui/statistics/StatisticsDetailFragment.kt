package com.github.andreyasadchy.xtra.ui.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.github.andreyasadchy.xtra.databinding.FragmentStatisticsDetailBinding
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.repository.ViewingStatsDetailSnapshot
import com.github.andreyasadchy.xtra.util.viewingstats.CategoryWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ContentTypeWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class StatisticsDetailFragment : Fragment() {

    private var _binding: FragmentStatisticsDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: StatisticsDetailViewModel
    private lateinit var channelAdapter: StatisticsChannelAdapter
    private lateinit var categoryAdapter: StatisticsCategoryAdapter
    private var initialBottomPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        val app = requireContext().applicationContext as XtraApp
        viewModel = ViewModelProvider(
            this,
            StatisticsDetailViewModel.factory(
                repository = app.xtraModule.viewingStatsRepository,
                type = args.getString(ARG_TYPE).orEmpty(),
                channelId = args.getString(ARG_CHANNEL_ID),
                categoryKey = args.getString(ARG_CATEGORY_KEY),
                title = args.getString(ARG_TITLE),
                bucketFrom = args.getLong(ARG_BUCKET_FROM).takeIf { args.containsKey(ARG_BUCKET_FROM) },
                bucketTo = args.getLong(ARG_BUCKET_TO).takeIf { args.containsKey(ARG_BUCKET_TO) },
            ),
        )[StatisticsDetailViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatisticsDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navController = findNavController()
        binding.toolbar.setupWithNavController(navController, AppBarConfiguration(setOf(R.id.statisticsFragment)))
        initialBottomPadding = binding.content.paddingBottom

        channelAdapter = StatisticsChannelAdapter { channel ->
            navController.navigate(
                R.id.statisticsDetailFragment,
                argumentsForChannel(channel.channelId, channel.channelLogin, channel.channelName, channel.channelImage),
            )
        }
        categoryAdapter = StatisticsCategoryAdapter { category ->
            navController.navigate(
                R.id.statisticsDetailFragment,
                argumentsForCategory(category.categoryKey, category.categoryName, category.categoryId, category.categoryImage),
            )
        }
        binding.topChannels.adapter = channelAdapter
        binding.topCategories.adapter = categoryAdapter
        binding.topChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.topCategories.layoutManager = LinearLayoutManager(requireContext())
        binding.topChannels.isNestedScrollingEnabled = false
        binding.topCategories.isNestedScrollingEnabled = false
        val isBucketDetail = requireArguments().getString(ARG_TYPE) == StatisticsDetailViewModel.TYPE_BUCKET
        if (isBucketDetail) binding.rangeGroup.visibility = View.GONE
        binding.rangeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.range7 -> viewModel.selectRange(ViewingStatsRange.LAST_7_DAYS)
                R.id.range30 -> viewModel.selectRange(ViewingStatsRange.LAST_30_DAYS)
                R.id.range90 -> viewModel.selectRange(ViewingStatsRange.LAST_90_DAYS)
                R.id.rangeYear -> viewModel.selectRange(ViewingStatsRange.LAST_YEAR)
                R.id.rangeAll -> viewModel.selectRange(ViewingStatsRange.ALL_TIME)
            }
        }
        binding.activityChart.onBucketSelected = { index, _ -> viewModel.selectBucket(index) }
        binding.openEntity.setOnClickListener { openEntity() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest(::render)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.content.setPadding(
                binding.content.paddingLeft,
                binding.content.paddingTop,
                binding.content.paddingRight,
                bars.bottom + initialBottomPadding,
            )
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            insets
        }
    }

    private fun render(state: StatisticsDetailUiState) {
        if (_binding == null) return
        binding.progress.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        if (state.isLoading) return
        val snapshot = state.snapshot ?: return
        binding.detailContent.visibility = View.VISIBLE
        val detailType = requireArguments().getString(ARG_TYPE)
        binding.toolbar.title = state.title?.takeIf { it.isNotBlank() }
            ?: if (detailType == StatisticsDetailViewModel.TYPE_BUCKET) {
                getString(R.string.statistics_viewing_details)
            } else {
                getString(R.string.statistics)
            }
        if (detailType != StatisticsDetailViewModel.TYPE_BUCKET) {
            binding.rangeGroup.check(rangeChip(state.range))
        }
        binding.totalWatchTime.text = formatDuration(snapshot.totalWatchMs)
        binding.detailSubtitle.text = getString(
            R.string.statistics_detail_summary,
            snapshot.sessionCount,
            formatDuration(snapshot.averageSessionMs),
        )
        binding.sessionsValue.text = getString(R.string.statistics_detail_sessions, snapshot.sessionCount)
        binding.averageSessionValue.text = getString(R.string.statistics_detail_average, formatDuration(snapshot.averageSessionMs))
        binding.lastWatchedValue.text = getString(
            R.string.statistics_detail_last_watched,
            snapshot.lastWatchedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                ?: getString(R.string.statistics_not_available),
        )
        binding.activityChart.setBuckets(snapshot.timeline)
        binding.activityChart.setSelectedIndex(state.selectedBucketIndex)
        binding.activitySummary.text = getString(R.string.statistics_detail_timeline_summary, snapshot.timeline.size)
        channelAdapter.submitChannels(snapshot.topChannels, snapshot.totalWatchMs)
        categoryAdapter.submitCategories(snapshot.topCategories, snapshot.totalWatchMs)
        val showChannels = detailType != StatisticsDetailViewModel.TYPE_CHANNEL && snapshot.topChannels.isNotEmpty()
        val showCategories = detailType != StatisticsDetailViewModel.TYPE_CATEGORY && snapshot.topCategories.isNotEmpty()
        binding.topChannels.visibility = if (showChannels) View.VISIBLE else View.GONE
        binding.topCategories.visibility = if (showCategories) View.VISIBLE else View.GONE
        binding.channelsHeading.visibility = if (showChannels) View.VISIBLE else View.GONE
        binding.categoriesHeading.visibility = if (showCategories) View.VISIBLE else View.GONE
        binding.contentBreakdown.text = formatContentBreakdown(snapshot.contentTypes, snapshot.totalWatchMs)
        binding.recentContainer.removeAllViews()
        snapshot.recentIntervals.forEach { interval ->
            val channelName = interval.channelName ?: interval.channelLogin ?: interval.channelId
            val categoryName = interval.categoryName ?: getString(R.string.statistics_unknown_category)
            val row = TextView(requireContext()).apply {
                text = getString(
                    R.string.statistics_recent_interval,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(interval.startAt)),
                    formatDuration(interval.watchedMs),
                    channelName,
                    categoryName,
                )
                setPadding(0, 12, 0, 12)
                isClickable = true
                isFocusable = true
                contentDescription = text
                setOnClickListener {
                    findNavController().navigate(
                        R.id.statisticsDetailFragment,
                        argumentsForChannel(
                            channelId = interval.channelId,
                            channelLogin = interval.channelLogin,
                            channelName = interval.channelName ?: interval.channelLogin,
                            channelImage = interval.channelImage,
                        ),
                    )
                }
            }
            binding.recentContainer.addView(row)
        }
        val canOpenEntity = when (detailType) {
            StatisticsDetailViewModel.TYPE_CHANNEL -> true
            StatisticsDetailViewModel.TYPE_CATEGORY -> requireArguments().getString(ARG_CATEGORY_ID) != null
            else -> false
        }
        binding.openEntity.visibility = if (canOpenEntity) View.VISIBLE else View.GONE
        binding.openEntity.setText(
            if (detailType == StatisticsDetailViewModel.TYPE_CATEGORY) {
                R.string.statistics_open_category
            } else R.string.statistics_open_channel,
        )
    }

    private fun openEntity() {
        val type = requireArguments().getString(ARG_TYPE)
        when (type) {
            StatisticsDetailViewModel.TYPE_CHANNEL -> {
                findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = requireArguments().getString(ARG_CHANNEL_ID),
                        channelLogin = requireArguments().getString(ARG_CHANNEL_LOGIN),
                        channelName = requireArguments().getString(ARG_TITLE),
                        channelImage = requireArguments().getString(ARG_CHANNEL_IMAGE),
                    )
                )
            }
            StatisticsDetailViewModel.TYPE_CATEGORY -> {
                findNavController().navigate(
                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                        gameId = requireArguments().getString(ARG_CATEGORY_ID),
                        gameSlug = null,
                        gameName = requireArguments().getString(ARG_TITLE),
                    )
                )
            }
        }
    }

    private fun rangeChip(range: ViewingStatsRange): Int = when (range) {
        ViewingStatsRange.LAST_7_DAYS -> R.id.range7
        ViewingStatsRange.LAST_30_DAYS -> R.id.range30
        ViewingStatsRange.LAST_90_DAYS -> R.id.range90
        ViewingStatsRange.LAST_YEAR -> R.id.rangeYear
        ViewingStatsRange.ALL_TIME -> R.id.rangeAll
    }

    private fun formatContentBreakdown(items: List<ContentTypeWatchTotal>, total: Long): String {
        return items.joinToString(" · ") { item ->
            val label = when (item.contentType) {
                "live" -> getString(R.string.statistics_content_live)
                "vod" -> getString(R.string.statistics_content_vod)
                "clip" -> getString(R.string.statistics_content_clips)
                "offline_video" -> getString(R.string.statistics_content_offline)
                else -> getString(R.string.statistics_content_unknown)
            }
            val share = if (total > 0L) (item.watchedMs * 100.0 / total).roundToInt() else 0
            "$label $share%"
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
        val hours = minutes / 60L
        return when {
            hours > 0L -> getString(R.string.statistics_duration_hours, hours, minutes % 60L)
            minutes > 0L -> getString(R.string.statistics_duration_minutes, minutes)
            else -> getString(R.string.statistics_duration_less_than_minute)
        }
    }

    override fun onDestroyView() {
        binding.topChannels.adapter = null
        binding.topCategories.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TYPE = "statistics_detail_type"
        private const val ARG_CHANNEL_ID = "statistics_channel_id"
        private const val ARG_CHANNEL_LOGIN = "statistics_channel_login"
        private const val ARG_CHANNEL_IMAGE = "statistics_channel_image"
        private const val ARG_CATEGORY_KEY = "statistics_category_key"
        private const val ARG_CATEGORY_ID = "statistics_category_id"
        private const val ARG_CATEGORY_IMAGE = "statistics_category_image"
        private const val ARG_TITLE = "statistics_detail_title"
        private const val ARG_BUCKET_FROM = "statistics_bucket_from"
        private const val ARG_BUCKET_TO = "statistics_bucket_to"

        fun argumentsForChannel(channelId: String, channelLogin: String?, channelName: String?, channelImage: String?) = bundleOf(
            ARG_TYPE to StatisticsDetailViewModel.TYPE_CHANNEL,
            ARG_CHANNEL_ID to channelId,
            ARG_CHANNEL_LOGIN to channelLogin,
            ARG_CHANNEL_IMAGE to channelImage,
            ARG_TITLE to channelName,
        )

        fun argumentsForCategory(categoryKey: String, categoryName: String?, categoryId: String?, categoryImage: String?) = bundleOf(
            ARG_TYPE to StatisticsDetailViewModel.TYPE_CATEGORY,
            ARG_CATEGORY_KEY to categoryKey,
            ARG_CATEGORY_ID to categoryId,
            ARG_CATEGORY_IMAGE to categoryImage,
            ARG_TITLE to categoryName,
        )

        fun argumentsForBucket(fromInclusive: Long, toExclusive: Long) = bundleOf(
            ARG_TYPE to StatisticsDetailViewModel.TYPE_BUCKET,
            ARG_BUCKET_FROM to fromInclusive,
            ARG_BUCKET_TO to toExclusive,
        )
    }
}
