package com.github.andreyasadchy.xtra.ui.following.overview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.ItemShelfSkeletonBinding

enum class FollowingOverviewLoadingType {
    STREAM,
    VIDEO,
    UPCOMING,
    GAME,
}

class ShelfSkeletonAdapter(
    private val skeletonCount: Int = 6,
) : RecyclerView.Adapter<ShelfSkeletonAdapter.ViewHolder>() {

    private var loadingType = FollowingOverviewLoadingType.STREAM

    override fun getItemCount(): Int = skeletonCount

    fun setLoadingType(type: FollowingOverviewLoadingType) {
        if (loadingType == type) return
        loadingType = type
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShelfSkeletonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        (parent as? RecyclerView)?.let { ShelfCardSizing.apply(binding.root, it) }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.itemView.parent as? RecyclerView)?.let { ShelfCardSizing.apply(holder.itemView, it) }
        holder.bind(loadingType)
    }

    class ViewHolder(
        private val binding: ItemShelfSkeletonBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(type: FollowingOverviewLoadingType) {
            val thumbnailParams = binding.thumbnail.layoutParams as ConstraintLayout.LayoutParams
            thumbnailParams.dimensionRatio = if (type == FollowingOverviewLoadingType.GAME) "H,3:4" else "H,16:9"
            binding.thumbnail.layoutParams = thumbnailParams
            binding.tertiary.visibility = if (type == FollowingOverviewLoadingType.GAME) View.GONE else View.VISIBLE
        }
    }
}
