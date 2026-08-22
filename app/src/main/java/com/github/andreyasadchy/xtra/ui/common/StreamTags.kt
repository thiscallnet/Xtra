package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.andreyasadchy.xtra.R

internal fun bindStreamTags(
    context: Context,
    tagsLayout: ConstraintLayout,
    tags: List<String>,
    onTagClick: (String) -> Unit,
) {
    tagsLayout.removeAllViews()
    if (tags.isEmpty()) {
        tagsLayout.visibility = View.GONE
        return
    }

    tagsLayout.visibility = View.VISIBLE
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

    val tagIds = IntArray(tags.size)
    val inflater = LayoutInflater.from(context)
    tags.forEachIndexed { index, tag ->
        val tagView = inflater.inflate(R.layout.item_stream_tag, tagsLayout, false) as TextView
        tagView.id = View.generateViewId()
        tagView.text = tag
        tagView.setOnClickListener { onTagClick(tag) }
        tagsLayout.addView(tagView)
        tagIds[index] = tagView.id
    }
    flow.referencedIds = tagIds
}
