package com.github.andreyasadchy.xtra.ui.common

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.andreyasadchy.xtra.R

internal class StreamTagViews(
    private val tagsLayout: ConstraintLayout,
    private val tagViews: List<TextView>,
) {
    private val boundTags = arrayOfNulls<String>(MAX_TAGS)
    private var onTagClick: ((String) -> Unit)? = null

    init {
        tagViews.forEachIndexed { index, view ->
            view.setOnClickListener {
                boundTags[index]?.let { tag -> onTagClick?.invoke(tag) }
            }
        }
    }

    fun setOnTagClickListener(listener: ((String) -> Unit)?) {
        onTagClick = listener
    }

    fun bind(tags: List<String>) {
        tagViews.forEachIndexed { index, view ->
            val tag = tags.getOrNull(index)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            boundTags[index] = tag
            view.text = tag.orEmpty()
            view.visibility = if (tag == null) View.GONE else View.VISIBLE
        }
        tagsLayout.visibility = if (tagViews.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
    }

    fun clear() {
        boundTags.fill(null)
        tagViews.forEach { view ->
            view.text = null
            view.visibility = View.GONE
        }
        tagsLayout.visibility = View.GONE
    }

    private companion object {
        const val MAX_TAGS = 8
    }
}

internal fun createStreamTagViews(tagsLayout: ConstraintLayout): StreamTagViews {
    val context = tagsLayout.context
    val flow = Flow(context).apply {
        id = View.generateViewId()
        layoutParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topToTop = tagsLayout.id
            bottomToBottom = tagsLayout.id
            startToStart = tagsLayout.id
            endToEnd = tagsLayout.id
        }
        setWrapMode(Flow.WRAP_CHAIN)
        setHorizontalGap(context.resources.getDimensionPixelSize(R.dimen.stream_tag_gap))
        setVerticalGap(context.resources.getDimensionPixelSize(R.dimen.stream_tag_gap))
    }
    tagsLayout.addView(flow)

    val inflater = LayoutInflater.from(context)
    val tagViews = List(8) {
        (inflater.inflate(R.layout.item_stream_tag, tagsLayout, false) as TextView).apply {
            id = View.generateViewId()
            tagsLayout.addView(this)
        }
    }
    flow.referencedIds = tagViews.map(TextView::getId).toIntArray()
    return StreamTagViews(tagsLayout, tagViews)
}

internal fun bindStreamTags(
    views: StreamTagViews,
    tags: List<String>,
) {
    views.bind(tags)
}

internal fun clearStreamTags(views: StreamTagViews) {
    views.clear()
}
