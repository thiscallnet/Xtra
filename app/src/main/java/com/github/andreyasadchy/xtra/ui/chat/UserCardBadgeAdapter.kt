package com.github.andreyasadchy.xtra.ui.chat

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.UserCardBadge

class UserCardBadgeAdapter : RecyclerView.Adapter<UserCardBadgeAdapter.ViewHolder>() {

    private var allBadges: List<UserCardBadge> = emptyList()

    var expanded: Boolean = false
        private set

    private val visibleBadges: List<UserCardBadge>
        get() = if (expanded) allBadges else allBadges.take(COLLAPSED_COUNT)

    fun submitBadges(items: List<UserCardBadge>) {
        allBadges = items
        if (expanded && items.size <= COLLAPSED_COUNT) expanded = false
        notifyDataSetChanged()
    }

    fun toggleExpanded() {
        expanded = !expanded
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = visibleBadges.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_card_badge, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(visibleBadges[position])
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image = itemView.findViewById<ImageView>(R.id.badgeImage)

        fun bind(item: UserCardBadge) {
            image.contentDescription = item.title
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) itemView.tooltipText = item.title
            itemView.context.imageLoader.enqueue(
                ImageRequest.Builder(itemView.context)
                    .data(item.imageUrl)
                    .crossfade(true)
                    .target(image)
                    .build(),
            )
        }
    }

    companion object {
        const val COLLAPSED_COUNT = 8
    }
}
