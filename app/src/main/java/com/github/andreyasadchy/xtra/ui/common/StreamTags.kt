package com.github.andreyasadchy.xtra.ui.common

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.view.DirectionalHorizontalScrollView

internal class StreamTagViews(
    private val visibilityHost: View,
    private val scrollView: DirectionalHorizontalScrollView,
    private val tagContainer: LinearLayout,
    initialTagViews: List<TextView>,
    private val styleTag: (TextView) -> Unit = {},
    minimumTouchTargetSizeDp: Int = 0,
) {
    private val minimumTouchTargetSizePx = if (minimumTouchTargetSizeDp > 0) {
        (minimumTouchTargetSizeDp * tagContainer.resources.displayMetrics.density + 0.5f).toInt()
    } else {
        0
    }
    private val tagViews = initialTagViews.toMutableList()
    private val tagTargets = initialTagViews.map { view ->
        if (minimumTouchTargetSizePx > 0) wrapInitialTagView(view) else view
    }.toMutableList()
    private val boundTags = arrayOfNulls<String>(MAX_STREAM_TAGS)
    private var onTagClick: ((String) -> Unit)? = null

    init {
        tagViews.forEachIndexed(::prepareTagView)
    }

    fun setOnTagClickListener(listener: ((String) -> Unit)?) {
        onTagClick = listener
    }

    fun bind(tags: List<String>) {
        val values = tags.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(MAX_STREAM_TAGS)
            .toList()
        ensureTagViews(values.size)
        scrollView.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        scrollView.scrollTo(0, 0)
        tagViews.forEachIndexed { index, view ->
            val tag = values.getOrNull(index)
            boundTags[index] = tag
            view.text = tag.orEmpty()
            view.visibility = if (tag == null) View.GONE else View.VISIBLE
            val target = tagTargets[index]
            target.visibility = if (tag == null) View.GONE else View.VISIBLE
            if (target !== view) target.contentDescription = tag
        }
        visibilityHost.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
    }

    fun clear() {
        scrollView.visibility = View.GONE
        scrollView.scrollTo(0, 0)
        boundTags.fill(null)
        tagViews.forEachIndexed { index, view ->
            view.text = null
            view.visibility = View.GONE
            val target = tagTargets[index]
            target.visibility = View.GONE
            if (target !== view) target.contentDescription = null
        }
        visibilityHost.visibility = View.GONE
    }

    private fun ensureTagViews(count: Int) {
        while (tagViews.size < count) {
            val view = LayoutInflater.from(tagContainer.context)
                .inflate(R.layout.item_stream_tag, tagContainer, false) as TextView
            val target = if (minimumTouchTargetSizePx > 0) createTouchTarget(view) else view
            tagContainer.addView(target)
            styleTag(view)
            tagViews += view
            tagTargets += target
            prepareTagView(tagViews.lastIndex, view)
        }
    }

    private fun prepareTagView(index: Int, view: TextView) {
        view.id = view.id.takeIf { it != View.NO_ID } ?: View.generateViewId()
        view.isSingleLine = true
        val target = tagTargets[index]
        val wrapped = target !== view
        view.importantForAccessibility = if (wrapped) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        view.isClickable = !wrapped
        view.isFocusable = !wrapped
        if (wrapped) view.foreground = null
        target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        target.isClickable = true
        target.isFocusable = true
        (target.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.marginStart = 0
            params.marginEnd = tagContainer.context.resources.getDimensionPixelSize(R.dimen.stream_tag_gap)
            target.layoutParams = params
        }
        target.setOnClickListener {
            boundTags.getOrNull(index)?.let { tag -> onTagClick?.invoke(tag) }
        }
    }

    private fun wrapInitialTagView(view: TextView): FrameLayout {
        val index = tagContainer.indexOfChild(view)
        val params = view.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tagContainer.removeViewAt(index)
        val target = createTouchTarget(view)
        tagContainer.addView(target, index, params)
        return target
    }

    private fun createTouchTarget(view: TextView): FrameLayout {
        val context = tagContainer.context
        val selectableItemBackground = android.util.TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        view.isClickable = false
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.foreground = null
        return FrameLayout(context).apply {
            id = View.generateViewId()
            minimumWidth = minimumTouchTargetSizePx
            minimumHeight = minimumTouchTargetSizePx
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            foreground = if (selectableItemBackground.resourceId != 0) {
                androidx.core.content.ContextCompat.getDrawable(context, selectableItemBackground.resourceId)
            } else {
                null
            }
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }
}

internal fun createStreamTagViews(tagsLayout: ConstraintLayout): StreamTagViews {
    val context = tagsLayout.context
    val tagContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    val scrollView = DirectionalHorizontalScrollView(context).apply {
        id = View.generateViewId()
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        addView(
            tagContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        layoutParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topToTop = tagsLayout.id
            bottomToBottom = tagsLayout.id
            startToStart = tagsLayout.id
            endToEnd = tagsLayout.id
        }
    }
    tagsLayout.addView(scrollView)
    return StreamTagViews(tagsLayout, scrollView, tagContainer, emptyList())
}

internal fun createStreamTagViews(
    scrollView: DirectionalHorizontalScrollView,
    tagContainer: LinearLayout,
    initialTagViews: List<TextView>,
    minimumTouchTargetSizeDp: Int = 0,
    styleTag: (TextView) -> Unit = {},
): StreamTagViews = StreamTagViews(
    scrollView,
    scrollView,
    tagContainer,
    initialTagViews,
    styleTag,
    minimumTouchTargetSizeDp,
)

internal fun bindStreamTags(
    views: StreamTagViews,
    tags: List<String>,
) {
    views.bind(tags)
}

internal fun clearStreamTags(views: StreamTagViews) {
    views.clear()
}

internal const val MAX_STREAM_TAGS = 8
