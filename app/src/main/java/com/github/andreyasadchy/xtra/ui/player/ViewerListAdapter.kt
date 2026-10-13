package com.github.andreyasadchy.xtra.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentViewerListItemBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelViewer
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

internal sealed interface ViewerRow {
    data class Header(val group: Int, val title: Int, val count: Int) : ViewerRow
    data class Person(val group: Int, val value: ChannelViewer) : ViewerRow
}

internal class ViewerListAdapter(private val onClick: (ChannelViewer) -> Unit) :
    ListAdapter<ViewerRow, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<ViewerRow>() {
        override fun areItemsTheSame(oldItem: ViewerRow, newItem: ViewerRow): Boolean = when {
            oldItem is ViewerRow.Header && newItem is ViewerRow.Header -> oldItem.group == newItem.group
            oldItem is ViewerRow.Person && newItem is ViewerRow.Person ->
                oldItem.group == newItem.group && oldItem.value.login.equals(newItem.value.login, ignoreCase = true)
            else -> false
        }
        override fun areContentsTheSame(oldItem: ViewerRow, newItem: ViewerRow) = oldItem == newItem
    }) {
    override fun getItemViewType(position: Int) = if (getItem(position) is ViewerRow.Header) HEADER else PERSON

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == HEADER) {
            object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                val density = resources.displayMetrics.density
                setPadding((4 * density).toInt(), (18 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                ViewCompat.setAccessibilityHeading(this, true)
            }) {}
        } else PersonHolder(FragmentViewerListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ViewerRow.Header -> (holder.itemView as TextView).text = holder.itemView.context.getString(
                R.string.viewer_list_section, holder.itemView.context.getString(row.title), row.count,
            )
            is ViewerRow.Person -> (holder as PersonHolder).bind(row.value)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? PersonHolder)?.disposeImage()
        super.onViewRecycled(holder)
    }

    private inner class PersonHolder(private val binding: FragmentViewerListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val placeholderTint = ImageViewCompat.getImageTintList(binding.userImage)
        private var imageUrl: String? = null
        private var imageBound = false
        private var imageRequest: Disposable? = null

        fun bind(viewer: ChannelViewer) = with(binding) {
            val displayName = viewer.displayName?.takeIf { it.isNotBlank() }
                ?.takeUnless { it.equals(viewer.login, ignoreCase = true) } ?: viewer.login
            val mode = root.context.prefs().getString(C.UI_NAME_DISPLAY, "0")
            val shownName = if (mode == "2") viewer.login else displayName
            root.setOnClickListener { onClick(viewer) }
            root.contentDescription = root.context.getString(R.string.player_open_channel, shownName)
            userName.text = shownName
            userLogin.isVisible = mode == "0" && !displayName.equals(viewer.login, ignoreCase = true)
            userLogin.text = "@${viewer.login}"
            val url = viewer.profileImageURL?.takeIf { it.isNotBlank() }
            if (!imageBound || url != imageUrl) {
                imageRequest?.dispose()
                imageRequest = null
                imageUrl = url
                imageBound = true
                userImage.setImageResource(R.drawable.baseline_person_black_24)
                ImageViewCompat.setImageTintList(userImage, placeholderTint)
                if (url != null) {
                    ImageViewCompat.setImageTintList(userImage, null)
                    imageRequest = userImage.context.imageLoader.enqueue(ImageRequest.Builder(userImage.context)
                        .data(url).crossfade(true).transformations(CircleCropTransformation()).target(userImage).build())
                }
            }
        }

        fun disposeImage() {
            imageRequest?.dispose()
            imageRequest = null
            imageBound = false
            imageUrl = null
        }
    }

    private companion object {
        const val HEADER = 0
        const val PERSON = 1
    }
}
