package com.github.andreyasadchy.xtra.ui.following.streams

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import com.github.andreyasadchy.xtra.ui.common.StreamCardPresentationCache
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.streamThumbnailOnlyChanged

/**
 * Keeps Following Live on the process-local feed snapshot instead of making
 * RecyclerView observe the Room-backed PagingSource.
 *
 * The existing row implementations are used as delegates so the compact and
 * regular layouts keep identical interactions and preview behavior.
 */
class FollowingStreamsListAdapter(
    private val fragment: Fragment,
    selectTag: (String) -> Unit,
    compact: Boolean,
) : ListAdapter<Stream, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private val compactDelegate = StreamsCompactAdapter(fragment, selectTag)
    private val shelfDelegate = StreamsShelfPagingAdapter(fragment, selectTag)
    private val compactPreference = compact
    private var singleColumnPresentation = false
    private var attachedRecyclerView: RecyclerView? = null

    private val usesCompactLayout: Boolean
        get() = compactPreference || singleColumnPresentation

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).streamIdentity().hashCode().toLong()

    fun itemAt(position: Int): Stream? = currentList.getOrNull(position)

    /** Expose the snapshot immediately while preparing its visible window off-main. */
    fun submitStreams(streams: List<Stream>) {
        val context = fragment.requireContext()
        val preferences = FeedUiPreferencesStore.current(context)
        StreamCardPresentationCache.prewarm(context, streams.size, streams::getOrNull, preferences)
        submitList(streams)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        attachActiveDelegate(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        detachActiveDelegate()
        attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setSingleColumnPresentation(enabled: Boolean) {
        if (singleColumnPresentation == enabled) return
        val wasCompactLayout = usesCompactLayout
        singleColumnPresentation = enabled
        if (wasCompactLayout == usesCompactLayout) return
        if (wasCompactLayout) compactDelegate.detachImageScheduler()
        else shelfDelegate.detachImageScheduler()
        attachedRecyclerView?.let(::attachActiveDelegate)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (usesCompactLayout) VIEW_TYPE_COMPACT else VIEW_TYPE_SHELF

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_COMPACT) {
            compactDelegate.onCreateViewHolder(parent, viewType)
        } else {
            shelfDelegate.onCreateViewHolder(parent, viewType)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindItem(holder, getItem(position))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = getItem(position)
        if (payloads.isNotEmpty() && payloads.all { it === StreamThumbnailChangedPayload }) {
            when (holder) {
                is StreamsCompactAdapter.PagingViewHolder -> {
                    holder.beginThumbnailRefresh()
                    holder.bindThumbnail(item)
                }
                is StreamsShelfPagingAdapter.ViewHolder -> {
                    holder.beginThumbnailRefresh()
                    holder.bindThumbnail(item)
                }
                else -> bindItem(holder, item)
            }
        } else {
            bindItem(holder, item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is StreamsCompactAdapter.PagingViewHolder -> compactDelegate.recycleViewHolder(holder)
            is StreamsShelfPagingAdapter.ViewHolder -> shelfDelegate.recycleViewHolder(holder)
        }
        super.onViewRecycled(holder)
    }

    private fun bindItem(holder: RecyclerView.ViewHolder, item: Stream?) {
        when (holder) {
            is StreamsCompactAdapter.PagingViewHolder -> {
                holder.beginImageBind(item)
                holder.bind(item)
            }
            is StreamsShelfPagingAdapter.ViewHolder -> {
                holder.beginImageBind(item)
                holder.bind(item)
            }
        }
    }

    private fun attachActiveDelegate(recyclerView: RecyclerView) {
        if (usesCompactLayout) compactDelegate.attachImageScheduler(recyclerView)
        else shelfDelegate.attachImageScheduler(recyclerView)
    }

    private fun detachActiveDelegate() {
        if (usesCompactLayout) compactDelegate.detachImageScheduler()
        else shelfDelegate.detachImageScheduler()
    }

    private companion object {
        const val VIEW_TYPE_COMPACT = 0
        const val VIEW_TYPE_SHELF = 1

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Stream>() {
            override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                oldItem.streamIdentity() == newItem.streamIdentity()

            override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                streamContentsSame(oldItem, newItem)

            override fun getChangePayload(oldItem: Stream, newItem: Stream): Any? =
                if (streamThumbnailOnlyChanged(oldItem, newItem)) StreamThumbnailChangedPayload else null
        }
    }
}
