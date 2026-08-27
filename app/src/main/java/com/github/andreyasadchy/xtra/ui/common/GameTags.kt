package com.github.andreyasadchy.xtra.ui.common

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.widget.TextViewCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Tag

/** Reuses a fixed tag hierarchy so game row binding never creates or removes views. */
internal class GameTagViews(
    private val tagsLayout: ConstraintLayout,
    private val tagViews: List<TextView>,
) {
    private val boundTags = arrayOfNulls<Tag>(MAX_TAGS)
    private var onTagClick: ((Tag) -> Unit)? = null

    init {
        tagViews.forEachIndexed { index, view ->
            view.setOnClickListener {
                boundTags[index]?.takeIf { tag -> tag.id != null }?.let { tag ->
                    onTagClick?.invoke(tag)
                }
            }
        }
    }

    fun bind(tags: List<Tag>, onTagClick: (Tag) -> Unit) {
        this.onTagClick = onTagClick
        tagViews.forEachIndexed { index, view ->
            val tag = tags.getOrNull(index)
            boundTags[index] = tag
            view.text = tag?.name.orEmpty()
            view.visibility = if (tag == null) View.GONE else View.VISIBLE
            view.isFocusable = tag?.id != null
            view.isClickable = tag?.id != null
        }
        tagsLayout.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
    }

    fun clear() {
        boundTags.fill(null)
        onTagClick = null
        tagViews.forEach { view ->
            view.text = null
            view.visibility = View.GONE
            view.isFocusable = false
            view.isClickable = false
        }
        tagsLayout.visibility = View.GONE
    }

    private companion object {
        const val MAX_TAGS = 10
    }
}

internal fun createGameTagViews(tagsLayout: ConstraintLayout): GameTagViews {
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
    }
    tagsLayout.addView(flow)

    val minHeight = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        48f,
        context.resources.displayMetrics,
    ).toInt()
    val padding = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        5f,
        context.resources.displayMetrics,
    ).toInt()
    val tagViews = List(10) {
        TextView(context).apply {
            id = View.generateViewId()
            setMinHeight(minHeight)
            isFocusable = false
            isClickable = false
            context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
            }
            setPadding(padding, 0, padding, 0)
            tagsLayout.addView(this)
        }
    }
    flow.referencedIds = tagViews.map(TextView::getId).toIntArray()
    return GameTagViews(tagsLayout, tagViews)
}

internal fun bindGameTags(
    views: GameTagViews,
    tags: List<Tag>,
    onTagClick: (Tag) -> Unit,
) {
    views.bind(tags.take(10), onTagClick)
}

internal fun clearGameTags(views: GameTagViews) {
    views.clear()
}
