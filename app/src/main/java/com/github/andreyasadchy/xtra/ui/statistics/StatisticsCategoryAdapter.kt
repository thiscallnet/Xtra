package com.github.andreyasadchy.xtra.ui.statistics

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.StatisticsCategoryItemBinding
import com.github.andreyasadchy.xtra.util.viewingstats.CategoryWatchTotal
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class StatisticsCategoryAdapter(
    private val onCategoryClick: (CategoryWatchTotal) -> Unit,
) : ListAdapter<StatisticsCategoryRow, StatisticsCategoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    fun submitCategories(items: List<CategoryWatchTotal>, totalWatchMs: Long) {
        submitList(items.map { category ->
            StatisticsCategoryRow(
                category = category,
                sharePercent = if (totalWatchMs > 0L) {
                    (category.watchedMs.toDouble() * 100.0 / totalWatchMs).roundToInt().coerceIn(0, 100)
                } else 0,
            )
        })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            StatisticsCategoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onCategoryClick,
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: StatisticsCategoryItemBinding,
        private val onCategoryClick: (CategoryWatchTotal) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: StatisticsCategoryRow) {
            val item = row.category
            val context = binding.root.context
            val name = item.categoryName?.takeIf { it.isNotBlank() }
                ?: item.categoryId?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.statistics_unknown_category)
            binding.categoryName.text = name
            binding.categoryDuration.text = context.getString(
                R.string.statistics_category_duration,
                formatDuration(item.watchedMs),
                row.sharePercent,
            )
            binding.progress.progress = row.sharePercent
            binding.root.contentDescription = context.getString(
                R.string.statistics_category_accessibility,
                name,
                formatDuration(item.watchedMs),
                row.sharePercent,
            )
            binding.root.setOnClickListener { onCategoryClick(item) }
            if (item.categoryImage.isNullOrBlank()) {
                // Live stream metadata does not always carry box art. Keep a
                // deliberate category fallback instead of showing an empty
                // faded rectangle.
                binding.categoryImage.setImageResource(R.drawable.ic_games_black_24dp)
                ImageViewCompat.setImageTintList(
                    binding.categoryImage,
                    ColorStateList.valueOf(
                        MaterialColors.getColor(
                            binding.categoryImage,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                        )
                    ),
                )
                binding.categoryImage.alpha = 0.35f
            } else {
                ImageViewCompat.setImageTintList(binding.categoryImage, null)
                binding.categoryImage.alpha = 1f
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(item.categoryImage)
                        .crossfade(true)
                        .target(binding.categoryImage)
                        .build(),
                )
            }
        }

        private fun formatDuration(milliseconds: Long): String {
            val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
            val hours = minutes / 60L
            return when {
                hours > 0L -> binding.root.context.getString(R.string.statistics_duration_hours, hours, minutes % 60L)
                minutes > 0L -> binding.root.context.getString(R.string.statistics_duration_minutes, minutes)
                else -> binding.root.context.getString(R.string.statistics_duration_less_than_minute)
            }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StatisticsCategoryRow>() {
            override fun areItemsTheSame(oldItem: StatisticsCategoryRow, newItem: StatisticsCategoryRow) =
                oldItem.category.categoryKey == newItem.category.categoryKey

            override fun areContentsTheSame(oldItem: StatisticsCategoryRow, newItem: StatisticsCategoryRow) =
                oldItem == newItem
        }
    }
}

data class StatisticsCategoryRow(
    val category: CategoryWatchTotal,
    val sharePercent: Int,
)
