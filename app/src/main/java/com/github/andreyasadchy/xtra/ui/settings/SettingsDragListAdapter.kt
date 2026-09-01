package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.SettingsDragListItemBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.util.isTelevision

class SettingsDragListAdapter : ListAdapter<SettingsDragListItem, SettingsDragListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<SettingsDragListItem>() {
        override fun areItemsTheSame(oldItem: SettingsDragListItem, newItem: SettingsDragListItem): Boolean {
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: SettingsDragListItem, newItem: SettingsDragListItem): Boolean {
            return true
        }
    }
) {
    var itemTouchHelper: ItemTouchHelper? = null
    var setDefault: ((SettingsDragListItem) -> Unit)? = null
    var cycleGroup: ((SettingsDragListItem) -> Unit)? = null
    var showVisibilityToggle = true
    var minimumVisibleItems = 0
    var maximumVisibleItems: Int? = null
    var onItemChanged: (() -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SettingsDragListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: SettingsDragListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: SettingsDragListItem) {
            with(binding) {
                val isTv = root.context.isTelevision()
                if (isTv) {
                    val density = root.resources.displayMetrics.density
                    root.minimumHeight = (64f * density).toInt()
                    root.isFocusable = true
                    root.isFocusableInTouchMode = false
                    setAsDefault.isFocusable = false
                    checkBox.isFocusable = false
                    TvFocusHelper.install(root, focusedScale = 1.02f)
                }
                image.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this@ViewHolder)
                    }
                    false
                }
                text.text = item.text
                if (cycleGroup != null) {
                    setAsDefault.visibility = View.VISIBLE
                    setAsDefault.contentDescription = "Move to ${when (item.group) {
                        "quick" -> "More menu"
                        "menu" -> "Hidden"
                        else -> "Quick controls"
                    }}"
                    setAsDefault.setOnClickListener { cycleGroup!!(item) }
                    setAsDefault.setImageResource(
                        if (item.group == "quick") R.drawable.baseline_home_black_24 else R.drawable.outline_home_black_24
                    )
                } else if (setDefault != null) {
                    setAsDefault.visibility = View.VISIBLE
                    checkBox.visibility = View.GONE
                    setAsDefault.setOnClickListener {
                        setAsDefault.setImageResource(R.drawable.baseline_home_black_24)
                        setAsDefault.isClickable = false
                        setDefault!!(item)
                    }
                    if (item.default) {
                        setAsDefault.setImageResource(R.drawable.baseline_home_black_24)
                        setAsDefault.isClickable = false
                    } else {
                        setAsDefault.setImageResource(R.drawable.outline_home_black_24)
                        setAsDefault.isClickable = true
                    }
                } else {
                    setAsDefault.visibility = View.GONE
                }
                if (cycleGroup == null && showVisibilityToggle) {
                    checkBox.visibility = View.VISIBLE
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isChecked = item.enabled
                    val visibleItemCount = currentList.count { it.enabled }
                    checkBox.isEnabled = canDisableVisibleItem(
                        itemEnabled = item.enabled,
                        visibleItemCount = visibleItemCount,
                        minimumVisibleItems = minimumVisibleItems,
                    ) && (item.enabled || maximumVisibleItems == null || visibleItemCount < maximumVisibleItems!!)
                    checkBox.setOnCheckedChangeListener { _, isChecked ->
                        item.enabled = isChecked
                        onItemChanged?.invoke()
                    }
                    checkBox.contentDescription = root.context.getString(
                        R.string.settings_show_item,
                        item.text,
                    )
                } else {
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isEnabled = true
                    checkBox.visibility = View.GONE
                }
                if (isTv) {
                    val checkboxVisible = checkBox.visibility == View.VISIBLE
                    root.setOnClickListener {
                        if (checkboxVisible && checkBox.isEnabled) {
                            checkBox.performClick()
                        } else if (setAsDefault.visibility == View.VISIBLE && setAsDefault.isClickable) {
                            setAsDefault.performClick()
                        }
                    }
                    checkBox.scaleX = 1.35f
                    checkBox.scaleY = 1.35f
                    setAsDefault.scaleX = 1.25f
                    setAsDefault.scaleY = 1.25f
                } else {
                    root.setOnClickListener(null)
                    checkBox.scaleX = 1f
                    checkBox.scaleY = 1f
                    setAsDefault.scaleX = 1f
                    setAsDefault.scaleY = 1f
                }
            }
        }
    }
}
