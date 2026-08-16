package com.github.andreyasadchy.xtra.ui.statistics

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
import com.github.andreyasadchy.xtra.databinding.FragmentStatisticsBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatisticsViewModel by viewModels { StatisticsViewModel.StatisticsViewModelFactory }
    private lateinit var channelAdapter: StatisticsChannelAdapter
    private lateinit var categoryAdapter: StatisticsCategoryAdapter
    private var initialContentBottomPadding = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        val navController = findNavController()
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.rootGamesFragment,
                R.id.rootTopFragment,
                R.id.followPagerFragment,
                R.id.followMediaFragment,
                R.id.savedPagerFragment,
                R.id.savedMediaFragment,
            )
        )
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        binding.toolbar.menu.findItem(R.id.statistics)?.isVisible = false
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.statistics -> true
                R.id.search -> {
                    navController.navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                    true
                }
                R.id.settings -> {
                    activity.settingsResultLauncher?.launch(
                        Intent(activity, SettingsActivity::class.java)
                    )
                    true
                }
                else -> false
            }
        }

        channelAdapter = StatisticsChannelAdapter { channel ->
            navController.navigate(
                R.id.statisticsDetailFragment,
                StatisticsDetailFragment.argumentsForChannel(
                    channelId = channel.channelId,
                    channelLogin = channel.channelLogin,
                    channelName = channel.channelName ?: channel.channelLogin,
                    channelImage = channel.channelImage,
                ),
            )
        }
        categoryAdapter = StatisticsCategoryAdapter { category ->
            navController.navigate(
                R.id.statisticsDetailFragment,
                StatisticsDetailFragment.argumentsForCategory(
                    categoryKey = category.categoryKey,
                    categoryName = category.categoryName,
                    categoryId = category.categoryId,
                    categoryImage = category.categoryImage,
                ),
            )
        }
        binding.topChannels.adapter = channelAdapter
        binding.topCategories.adapter = categoryAdapter
        binding.topChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.topCategories.layoutManager = LinearLayoutManager(requireContext())
        binding.topChannels.isNestedScrollingEnabled = false
        binding.topCategories.isNestedScrollingEnabled = false
        initialContentBottomPadding = binding.content.paddingBottom

        binding.activityChart.onBucketSelected = { index, _ -> viewModel.selectBucket(index) }
        binding.viewBucketDetails.setOnClickListener {
            val state = viewModel.uiState.value
            val bucket = state.snapshot.timeline.getOrNull(state.selectedBucketIndex) ?: return@setOnClickListener
            navController.navigate(
                R.id.statisticsDetailFragment,
                StatisticsDetailFragment.argumentsForBucket(bucket.startAt, bucket.endAt),
            )
        }
        binding.seeAllChannels.setOnClickListener {
            navController.navigate(R.id.statisticsListFragment, StatisticsListFragment.argumentsForChannels())
        }
        binding.seeAllCategories.setOnClickListener {
            navController.navigate(R.id.statisticsListFragment, StatisticsListFragment.argumentsForCategories())
        }

        binding.rangeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.range7 -> viewModel.selectRange(ViewingStatsRange.LAST_7_DAYS)
                R.id.range30 -> viewModel.selectRange(ViewingStatsRange.LAST_30_DAYS)
                R.id.range90 -> viewModel.selectRange(ViewingStatsRange.LAST_90_DAYS)
                R.id.rangeYear -> viewModel.selectRange(ViewingStatsRange.LAST_YEAR)
                R.id.rangeAll -> viewModel.selectRange(ViewingStatsRange.ALL_TIME)
            }
        }
        binding.resetStatistics.setOnClickListener { showResetConfirmation() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest(::render)
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            binding.content.setPadding(
                binding.content.paddingLeft,
                binding.content.paddingTop,
                binding.content.paddingRight,
                insets.bottom + initialContentBottomPadding,
            )
            windowInsets
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render(state: StatisticsUiState) {
        if (!isAdded || _binding == null) return
        with(binding) {
            progress.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            if (state.isLoading) return@with

            val snapshot = state.snapshot
            rangeGroup.check(
                when (state.range) {
                    ViewingStatsRange.LAST_7_DAYS -> R.id.range7
                    ViewingStatsRange.LAST_30_DAYS -> R.id.range30
                    ViewingStatsRange.LAST_90_DAYS -> R.id.range90
                    ViewingStatsRange.LAST_YEAR -> R.id.rangeYear
                    ViewingStatsRange.ALL_TIME -> R.id.rangeAll
                }
            )
            val hasRecordedStats = snapshot.earliestRecordedAt != null
            comparison.visibility = if (state.range == ViewingStatsRange.ALL_TIME) {
                View.GONE
            } else {
                View.VISIBLE
            }
            emptyStateTitle.setText(
                if (hasRecordedStats) {
                    R.string.statistics_range_empty_title
                } else {
                    R.string.statistics_empty_title
                },
            )
            emptyStateMessage.setText(
                if (hasRecordedStats) {
                    R.string.statistics_range_empty_message
                } else {
                    R.string.statistics_empty_message
                },
            )
            emptyState.visibility = if (snapshot.hasActivity) View.GONE else View.VISIBLE
            statisticsContent.visibility = if (snapshot.hasActivity) View.VISIBLE else View.GONE
            resetStatistics.visibility = if (hasRecordedStats) View.VISIBLE else View.GONE
            if (!snapshot.hasActivity) return@with

            totalWatchTime.text = formatDuration(snapshot.totalWatchMs)
            comparison.text = when {
                snapshot.comparisonPercent == null -> getString(R.string.statistics_no_previous_comparison)
                snapshot.comparisonPercent > 0 -> getString(
                    R.string.statistics_comparison_positive,
                    snapshot.comparisonPercent,
                )
                snapshot.comparisonPercent < 0 -> getString(
                    R.string.statistics_comparison_negative,
                    -snapshot.comparisonPercent,
                )
                else -> getString(R.string.statistics_comparison_unchanged)
            }
            sessionsValue.text = formatCount(snapshot.sessionCount)
            channelsValue.text = formatCount(snapshot.channelCount)
            categoriesValue.text = formatCount(snapshot.categoryCount)
            activeDaysValue.text = formatCount(snapshot.activeDays)
            averageSessionValue.text = formatDuration(snapshot.averageSessionMs)

            activityChart.setBuckets(snapshot.timeline)
            activityChart.setSelectedIndex(state.selectedBucketIndex)
            activitySummary.text = getString(
                R.string.statistics_activity_summary,
                snapshot.activeDays,
                snapshot.dailyTotals.size,
            )
            val selectedBucket = snapshot.timeline.getOrNull(state.selectedBucketIndex)
            selectedBucketSummary.visibility = if (selectedBucket == null) View.GONE else View.VISIBLE
            viewBucketDetails.visibility = if (selectedBucket == null) View.GONE else View.VISIBLE
            selectedBucket?.let { bucket ->
                val detail = state.selectedBucketDetail
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(bucket.startAt))
                val duration = formatDuration(bucket.watchedMs)
                val topChannel = detail?.topChannels?.firstOrNull()
                selectedBucketSummary.text = if (topChannel == null) {
                    getString(R.string.statistics_selected_bucket_summary, date, duration, bucket.sessionCount)
                } else {
                    getString(
                        R.string.statistics_selected_bucket_summary_with_channel,
                        date,
                        duration,
                        bucket.sessionCount,
                        topChannel.channelName ?: topChannel.channelLogin ?: topChannel.channelId,
                        formatDuration(topChannel.watchedMs),
                    )
                }
            }

            channelAdapter.submitChannels(snapshot.topChannels, snapshot.totalWatchMs)
            topChannelsEmpty.visibility = if (snapshot.topChannels.isEmpty()) View.VISIBLE else View.GONE
            topChannels.visibility = if (snapshot.topChannels.isEmpty()) View.GONE else View.VISIBLE
            categoryAdapter.submitCategories(snapshot.topCategories, snapshot.totalWatchMs)
            topCategoriesEmpty.visibility = if (snapshot.topCategories.isEmpty()) View.VISIBLE else View.GONE
            topCategories.visibility = if (snapshot.topCategories.isEmpty()) View.GONE else View.VISIBLE
            contentBreakdown.text = snapshot.contentTypes.joinToString(" · ") { content ->
                val label = when (content.contentType) {
                    "live" -> getString(R.string.statistics_content_live)
                    "vod" -> getString(R.string.statistics_content_vod)
                    "clip" -> getString(R.string.statistics_content_clips)
                    "offline_video" -> getString(R.string.statistics_content_offline)
                    else -> getString(R.string.statistics_content_unknown)
                }
                val share = if (snapshot.totalWatchMs > 0L) {
                    (content.watchedMs * 100.0 / snapshot.totalWatchMs).toInt()
                } else 0
                "$label ${share}%"
            }

            mostActiveDayValue.text = snapshot.mostActiveWeekday?.let { weekdayName(it) }
                ?: getString(R.string.statistics_not_available)
            mostActiveTimeValue.text = snapshot.mostActiveTimeBucket?.let(::timeBucketLabel)
                ?: getString(R.string.statistics_not_available)
            longestSessionValue.text = formatDuration(snapshot.longestSessionMs)
            streakValue.text = resources.getQuantityString(
                R.plurals.statistics_active_day_streak,
                snapshot.longestActiveDayStreak,
                snapshot.longestActiveDayStreak,
            )
            recordedSince.text = snapshot.earliestRecordedAt?.let {
                getString(
                    R.string.statistics_recorded_since,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                )
            } ?: getString(R.string.statistics_recorded_since_unknown)
        }
    }

    private fun showResetConfirmation() {
        requireContext().getAlertDialogBuilder()
            .setTitle(R.string.statistics_reset_title)
            .setMessage(R.string.statistics_reset_message)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.statistics_reset) { _, _ ->
                viewModel.resetStatistics()
                Toast.makeText(requireContext(), R.string.statistics_reset_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun formatDuration(milliseconds: Long): String {
        val safeMilliseconds = milliseconds.coerceAtLeast(0L)
        val totalMinutes = safeMilliseconds / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> getString(R.string.statistics_duration_hours, hours, minutes)
            totalMinutes > 0L -> getString(R.string.statistics_duration_minutes, totalMinutes)
            else -> getString(R.string.statistics_duration_less_than_minute)
        }
    }

    private fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    private fun weekdayName(index: Int): String {
        return resources.getStringArray(R.array.statistics_weekdays).getOrNull(index)
            ?: getString(R.string.statistics_not_available)
    }

    private fun timeBucketLabel(bucket: Int): String {
        val start = (bucket.coerceIn(0, 7) * 3).toString().padStart(2, '0')
        val end = ((bucket.coerceIn(0, 7) + 1) * 3).toString().padStart(2, '0')
        return getString(R.string.statistics_time_bucket, start, end)
    }

    override fun onDestroyView() {
        binding.topChannels.adapter = null
        binding.topCategories.adapter = null
        _binding = null
        super.onDestroyView()
    }

}
