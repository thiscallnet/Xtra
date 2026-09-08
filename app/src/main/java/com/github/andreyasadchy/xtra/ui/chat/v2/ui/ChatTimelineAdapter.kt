package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.view.ViewGroup
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel

class ChatTimelineAdapter(
    private val assets: ChatAssetRepository,
    private var textSizeSp: Float,
    private var animateGifs: Boolean,
    private val onMessageLongClick: ((ChatMessageId) -> Unit)? = null,
    private val onEmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
    private val onGifClick: ((ChatGifInteraction) -> Unit)? = null,
    private val onMessageClick: ((ChatMessageId) -> Unit)? = null,
) : RecyclerView.Adapter<ChatTimelineAdapter.Holder>() {
    private var attachedRecyclerView: RecyclerView? = null
    private var animationBudget = Int.MAX_VALUE
    private var animationBudgetUpdatePosted = false
    private val animationBudgetUpdateRunnable = Runnable {
        animationBudgetUpdatePosted = false
        updateAnimationBudget()
    }
    private val animationBudgetScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            scheduleAnimationBudgetUpdate()
        }
    }
    private val rows = ArrayList<ChatRowUiModel>()
    var renderingActive = true

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
        holder.view.setRenderingActive(renderingActive)
        holder.view.setMessageTextSizeSp(textSizeSp)
        holder.view.setAnimateGifs(animateGifs)
        // A recycled holder may have been inside the budget for its previous row. Keep the new
        // row stopped until the visible-child pass assigns the current budget.
        holder.view.setAnimationBudgetAllowed(false)
        holder.bind(rows[position])
        scheduleAnimationBudgetUpdate()
    }

    /** Replaces the complete snapshot for reconciliation and presentation-wide updates. */
    fun replaceAll(newRows: List<ChatRowUiModel>, commitCallback: (() -> Unit)? = null) {
        val nextRows = newRows.toList()
        rows.clear()
        rows.addAll(nextRows)
        notifyDataSetChanged()
        scheduleAnimationBudgetUpdate()
        commitCallback?.invoke()
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
        if (evictedHeadCount < 0 || appendedCount < 0 || appendedCount > newRows.size) return false
        val retainedCount = newRows.size - appendedCount
        if (rows.size != retainedCount + evictedHeadCount || evictedHeadCount > rows.size) return false
        for (index in 0 until retainedCount) {
            if (rows[index + evictedHeadCount] != newRows[index]) return false
        }

        if (evictedHeadCount > 0) {
            rows.subList(0, evictedHeadCount).clear()
            notifyItemRangeRemoved(0, evictedHeadCount)
        }
        if (appendedCount > 0) {
            val insertionPosition = rows.size
            rows.addAll(newRows.subList(retainedCount, newRows.size))
            notifyItemRangeInserted(insertionPosition, appendedCount)
        }
        scheduleAnimationBudgetUpdate()
        return true
    }

    /** Applies a known tail append without validating or traversing the retained rows. */
    fun appendDelta(
        appendedRows: List<ChatRowUiModel>,
        evictedHeadCount: Int,
        expectedSize: Int,
    ): Boolean {
        if (evictedHeadCount < 0 || evictedHeadCount > rows.size) return false
        if (rows.size - evictedHeadCount + appendedRows.size != expectedSize) return false

        if (evictedHeadCount > 0) {
            rows.subList(0, evictedHeadCount).clear()
            notifyItemRangeRemoved(0, evictedHeadCount)
        }
        if (appendedRows.isNotEmpty()) {
            val insertionPosition = rows.size
            rows.addAll(appendedRows)
            notifyItemRangeInserted(insertionPosition, appendedRows.size)
        }
        scheduleAnimationBudgetUpdate()
        return true
    }

    /** Clears the adapter synchronously when its RecyclerView is being detached. */
    fun clear() {
        if (rows.isEmpty()) return
        val oldSize = rows.size
        rows.clear()
        notifyItemRangeRemoved(0, oldSize)
        scheduleAnimationBudgetUpdate()
    }

    fun dispose() {
        clear()
    }

    fun setMessageTextSizeSp(value: Float) {
        if (textSizeSp == value) return
        textSizeSp = value
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }
    fun setAnimateGifs(value: Boolean) {
        if (animateGifs == value) return
        animateGifs = value
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun setAnimationBudget(value: Int) {
        val next = value.coerceAtLeast(0)
        if (animationBudget == next) return
        animationBudget = next
        scheduleAnimationBudgetUpdate()
    }

    private fun scheduleAnimationBudgetUpdate() {
        val recyclerView = attachedRecyclerView ?: return
        if (animationBudgetUpdatePosted) return
        animationBudgetUpdatePosted = true
        recyclerView.post(animationBudgetUpdateRunnable)
    }

    private fun updateAnimationBudget() {
        val recyclerView = attachedRecyclerView ?: return
        // Sorting by adapter position keeps animation on the newest messages while older visible
        // rows retain their current frame, even if RecyclerView changes child order.
        var slots = animationBudget
        recyclerView.children
            .mapNotNull { child ->
                val holder = recyclerView.getChildViewHolder(child) as? Holder ?: return@mapNotNull null
                val position = recyclerView.getChildAdapterPosition(child)
                position.takeIf { it != RecyclerView.NO_POSITION }?.let { it to holder }
            }
            .sortedByDescending { (position, _) -> position }
            .forEach { (_, holder) ->
                val allowed = holder.view.hasAnimatedAssets() && slots > 0
                if (allowed) slots--
                holder.view.setAnimationBudgetAllowed(allowed)
            }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        recyclerView.addOnScrollListener(animationBudgetScrollListener)
        scheduleAnimationBudgetUpdate()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(animationBudgetScrollListener)
        recyclerView.removeCallbacks(animationBudgetUpdateRunnable)
        animationBudgetUpdatePosted = false
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        holder.view.setRenderingActive(renderingActive)
        scheduleAnimationBudgetUpdate()
    }

    override fun onViewRecycled(holder: Holder) { holder.view.recycle(); super.onViewRecycled(holder) }

    class Holder(val view: ChatMessageTextView) : RecyclerView.ViewHolder(view) {
        fun bind(row: ChatRowUiModel) = view.bind(row)
    }

}
