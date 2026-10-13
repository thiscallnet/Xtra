package com.github.andreyasadchy.xtra.ui.saved

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.github.andreyasadchy.xtra.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

class SavedSelectionChrome(
    host: Fragment,
    private val normalToolbar: Toolbar,
    private val appBar: AppBarLayout,
) {
    private val parent = normalToolbar.parent as ViewGroup
    private val toolbar = LayoutInflater.from(normalToolbar.context)
        .inflate(R.layout.saved_selection_toolbar, parent, false) as MaterialToolbar
    private val description = TextView(normalToolbar.context).apply {
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        val inset = (16 * resources.displayMetrics.density).toInt()
        setPaddingRelative(inset, 0, inset, inset / 2)
        isVisible = false
    }
    private var selection: SavedItemSelection? = null
    private var scrollingParams: AppBarLayout.LayoutParams? = null
    private var scrollFlags = 0
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finish()
    }

    init {
        parent.addView(toolbar, parent.indexOfChild(normalToolbar))
        parent.addView(description, parent.indexOfChild(toolbar) + 1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.requireActivity().onBackPressedDispatcher.addCallback(host.viewLifecycleOwner, back)
        toolbar.setNavigationOnClickListener { finish() }
        val tint = ColorStateList.valueOf(MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface))
        MenuItemCompat.setIconTintList(toolbar.menu.findItem(R.id.savedDeleteSelected), tint)
        toolbar.setOnMenuItemClickListener {
            val current = selection ?: return@setOnMenuItemClickListener false
            when (it.itemId) {
                R.id.savedDeleteSelected -> if (current.selectedIds.isNotEmpty()) current.onDelete(current.selectedIds.toSet())
                R.id.savedSelectLoaded -> current.selectLoaded()
                R.id.savedClearSelection -> current.clear()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    fun show(current: SavedItemSelection) {
        if (selection !== current) finish()
        selection = current
        toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = (normalToolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        }
        if (scrollingParams == null) {
            scrollingParams = (parent.layoutParams as? AppBarLayout.LayoutParams)
            scrollingParams?.let {
                scrollFlags = it.scrollFlags
                it.scrollFlags = 0
                parent.requestLayout()
            }
        }
        normalToolbar.isVisible = false
        toolbar.isVisible = true
        back.isEnabled = true
        appBar.setExpanded(true, false)
        refresh()
    }

    fun refresh() {
        val current = selection ?: return
        toolbar.title = toolbar.context.getString(R.string.saved_selected_count, current.selectedIds.size)
        description.text = current.description
        description.isVisible = !current.description.isNullOrBlank()
        toolbar.menu.findItem(R.id.savedDeleteSelected).apply {
            isEnabled = current.selectedIds.isNotEmpty()
            icon?.alpha = if (isEnabled) 255 else 97
        }
        toolbar.menu.findItem(R.id.savedClearSelection).isEnabled = current.selectedIds.isNotEmpty()
        toolbar.menu.findItem(R.id.savedSelectLoaded).isEnabled = current.hasLoadedItems()
    }

    fun finish() { selection?.finish() }

    fun setTopInset(inset: Int) {
        toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = inset }
    }

    fun hide(current: SavedItemSelection) {
        if (selection !== current) return
        selection = null
        toolbar.isVisible = false
        description.isVisible = false
        normalToolbar.isVisible = true
        back.isEnabled = false
        scrollingParams?.let { it.scrollFlags = scrollFlags; parent.requestLayout() }
        scrollingParams = null
    }

    fun close() {
        finish()
        back.remove()
        parent.removeView(toolbar)
        parent.removeView(description)
    }
}
