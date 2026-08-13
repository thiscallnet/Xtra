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
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStatisticsBinding
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatisticsViewModel by viewModels { StatisticsViewModel.StatisticsViewModelFactory }
    private lateinit var channelAdapter: StatisticsChannelAdapter
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
                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                    channelId = channel.channelId,
                    channelLogin = channel.channelLogin,
                    channelName = channel.channelName ?: channel.channelLogin,
                    channelImage = channel.channelImage,
                )
            )
        }
        binding.topChannels.adapter = channelAdapter
        binding.topChannels.isNestedScrollingEnabled = false
        initialContentBottomPadding = binding.content.paddingBottom

        binding.rangeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.range7 -> viewModel.selectRange(ViewingStatsRange.LAST_7_DAYS)
                R.id.range30 -> viewModel.selectRange(ViewingStatsRange.LAST_30_DAYS)
                R.id.range90 -> viewModel.selectRange(ViewingStatsRange.LAST_90_DAYS)
                R.id.rangeAll -> viewModel.selectRange(ViewingStatsRange.ALL_TIME)
            }
        }
        binding.resetStatistics.setOnClickListener { showResetConfirmation() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest(::render)
                }
                launch {
                    while (isActive) {
                        delay(STATISTICS_REFRESH_INTERVAL_MS)
                        viewModel.refreshSilently()
                    }
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
            sessionsValue.text = snapshot.sessionCount.toString()
            channelsValue.text = snapshot.channelCount.toString()
            activeDaysValue.text = snapshot.activeDays.toString()
            averageSessionValue.text = formatDuration(snapshot.averageSessionMs)

            activityChart.setDailyTotals(snapshot.dailyTotals)
            activitySummary.text = getString(
                R.string.statistics_activity_summary,
                snapshot.activeDays,
                snapshot.dailyTotals.size,
            )

            channelAdapter.submitChannels(snapshot.topChannels, snapshot.totalWatchMs)
            topChannelsEmpty.visibility = if (snapshot.topChannels.isEmpty()) View.VISIBLE else View.GONE
            topChannels.visibility = if (snapshot.topChannels.isEmpty()) View.GONE else View.VISIBLE

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
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STATISTICS_REFRESH_INTERVAL_MS = 45_000L
    }
}
