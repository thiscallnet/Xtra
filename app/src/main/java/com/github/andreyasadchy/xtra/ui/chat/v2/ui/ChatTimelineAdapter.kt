package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatTimelineAdapter(
    private val assets: ChatAssetRepository,
    private var textSizeSp: Float,
    private var animateGifs: Boolean,
    private val onMessageLongClick: ((ChatMessageId) -> Unit)? = null,
    private val onEmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
    private val onGifClick: ((ChatGifInteraction) -> Unit)? = null,
    private val onMessageClick: ((ChatMessageId) -> Unit)? = null,
    private val diffDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RecyclerView.Adapter<ChatTimelineAdapter.Holder>() {
    private val rows = ArrayList<ChatRowUiModel>()
    private val diffScope = CoroutineScope(SupervisorJob() + diffDispatcher)
    private var submitGeneration = 0L

    val currentList: List<ChatRowUiModel>
        get() = rows

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ChatMessageTextView(parent.context, assets).also {
            it.setMessageTextSizeSp(textSizeSp)
            it.setAnimateGifs(animateGifs)
            it.setInteractionCallbacks(onMessageLongClick, onEmoteClick, onGifClick)
            it.setMessageClickCallback(onMessageClick)
            it.layoutParams = ViewGroup.LayoutParams(-1, -2)
        },
    )
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.view.setMessageTextSizeSp(textSizeSp)
        holder.view.setAnimateGifs(animateGifs)
        holder.bind(rows[position])
    }

    /**
     * Correctness fallback for reconciliation, session changes, and presentation-wide updates.
     * The common live append path uses [append] and never reaches DiffUtil.
     */
    fun submitList(newRows: List<ChatRowUiModel>, commitCallback: (() -> Unit)? = null) {
        val oldRows = rows.toList()
        val nextRows = newRows.toList()
        val generation = ++submitGeneration
        diffScope.launch {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = nextRows.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldRows[oldItemPosition].id == nextRows[newItemPosition].id

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldRows[oldItemPosition] == nextRows[newItemPosition]
            })
            withContext(Dispatchers.Main.immediate) {
                if (generation != submitGeneration) return@withContext
                rows.clear()
                rows.addAll(nextRows)
                diff.dispatchUpdatesTo(this@ChatTimelineAdapter)
                commitCallback?.invoke()
            }
        }
    }

    /**
     * Applies a stable head eviction followed by a tail append. The complete new snapshot is
     * used for validation, but only the known changed ranges are sent to RecyclerView.
     */
    fun append(
        newRows: List<ChatRowUiModel>,
        evictedHeadCount: Int,
        appendedCount: Int,
    ): Boolean {
        ++submitGeneration
        if (evictedHeadCount < 0 || appendedCount < 0 || appendedCount > newRows.size) return false
        val retainedCount = newRows.size - appendedCount
        if (rows.size != retainedCount + evictedHeadCount || evictedHeadCount > rows.size) return false
        for (index in 0 until retainedCount) {
            if (rows[index + evictedHeadCount] != newRows[index]) return false
        }

        rows.subList(0, evictedHeadCount).clear()
        if (appendedCount > 0) {
            rows.addAll(newRows.subList(retainedCount, newRows.size))
        }
        if (evictedHeadCount > 0) notifyItemRangeRemoved(0, evictedHeadCount)
        if (appendedCount > 0) notifyItemRangeInserted(retainedCount, appendedCount)
        return true
    }

    /** Applies a known tail append without validating or traversing the retained rows. */
    fun appendDelta(
        appendedRows: List<ChatRowUiModel>,
        evictedHeadCount: Int,
        expectedSize: Int,
    ): Boolean {
        ++submitGeneration
        if (evictedHeadCount < 0 || evictedHeadCount > rows.size) return false
        if (rows.size - evictedHeadCount + appendedRows.size != expectedSize) return false

        val retainedCount = rows.size - evictedHeadCount
        rows.subList(0, evictedHeadCount).clear()
        rows.addAll(appendedRows)
        if (evictedHeadCount > 0) notifyItemRangeRemoved(0, evictedHeadCount)
        if (appendedRows.isNotEmpty()) notifyItemRangeInserted(retainedCount, appendedRows.size)
        return true
    }

    /** Clears the adapter synchronously when its RecyclerView is being detached. */
    fun clear() {
        ++submitGeneration
        if (rows.isEmpty()) return
        val oldSize = rows.size
        rows.clear()
        notifyItemRangeRemoved(0, oldSize)
    }

    fun dispose() {
        diffScope.cancel()
        clear()
    }

    fun setMessageTextSizeSp(value: Float) {
        textSizeSp = value
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }
    fun setAnimateGifs(value: Boolean) {
        animateGifs = value
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }
    override fun onViewRecycled(holder: Holder) { holder.view.recycle(); super.onViewRecycled(holder) }

    class Holder(val view: ChatMessageTextView) : RecyclerView.ViewHolder(view) {
        fun bind(row: ChatRowUiModel) = view.bind(row)
    }

}
