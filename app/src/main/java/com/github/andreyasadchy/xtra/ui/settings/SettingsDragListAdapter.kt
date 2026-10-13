package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
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
            return oldItem.text == newItem.text &&
                oldItem.default == newItem.default &&
                oldItem.enabled == newItem.enabled &&
                oldItem.group == newItem.group
        }
    }
) {
    var itemTouchHelper: ItemTouchHelper? = null
    var setDefault: ((SettingsDragListItem) -> Unit)? = null
    var cycleGroup: ((SettingsDragListItem) -> Unit)? = null
    var showVisibilityToggle = true
    var minimumVisibleItems = 0
    var onItemChanged: (() -> Unit)? = null
    var onMoveItem: ((Int, Int) -> Unit)? = null

    fun moveItem(from: Int, to: Int): Boolean {
        val move = onMoveItem ?: return false
        if (from !in 0 until itemCount || to !in 0 until itemCount || from == to) return false
        move(from, to)
        notifyItemMoved(from, to)
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1, REORDER_PAYLOAD)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SettingsDragListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == REORDER_PAYLOAD }) {
            holder.bindReorderActions(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
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
                val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                var dragging = false
                image.setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            dragging = false
                            view.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!dragging && (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop)) {
                                dragging = true
                                view.isPressed = false
                                view.parent.requestDisallowInterceptTouchEvent(false)
                                itemTouchHelper?.startDrag(this@ViewHolder)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    dragging
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
                    )
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
                root.setOnClickListener {
                    if (checkBox.visibility == View.VISIBLE && checkBox.isEnabled) {
                        checkBox.performClick()
                    } else if (setAsDefault.visibility == View.VISIBLE && setAsDefault.isClickable) {
                        setAsDefault.performClick()
                    }
                }
                bindReorderActions(item)
                if (isTv) {
                    checkBox.scaleX = 1.35f
                    checkBox.scaleY = 1.35f
                    setAsDefault.scaleX = 1.25f
                    setAsDefault.scaleY = 1.25f
                } else {
                    checkBox.scaleX = 1f
                    checkBox.scaleY = 1f
                    setAsDefault.scaleX = 1f
                    setAsDefault.scaleY = 1f
                }
            }
        }

        fun bindReorderActions(item: SettingsDragListItem) = with(binding) {
            val canMove = onMoveItem != null
            image.isFocusable = canMove
            image.importantForAccessibility = if (canMove) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            image.contentDescription = if (canMove) root.context.getString(R.string.reorder_favorite_item, item.text) else null
            image.setOnClickListener(if (canMove) View.OnClickListener { showMoveMenu() } else null)
            image.isClickable = canMove
            root.setOnLongClickListener(if (canMove) View.OnLongClickListener { showMoveMenu(); true } else null)
            root.isLongClickable = canMove
            ViewCompat.removeAccessibilityAction(root, R.id.settings_move_up)
            ViewCompat.removeAccessibilityAction(root, R.id.settings_move_down)
            val position = bindingAdapterPosition
            if (canMove && position > 0) {
                ViewCompat.replaceAccessibilityAction(
                    root,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.settings_move_up, null),
                    root.context.getString(R.string.settings_move_up),
                    AccessibilityViewCommand { _, _ -> moveBy(-1) },
                )
            }
            if (canMove && position != RecyclerView.NO_POSITION && position < itemCount - 1) {
                ViewCompat.replaceAccessibilityAction(
                    root,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.settings_move_down, null),
                    root.context.getString(R.string.settings_move_down),
                    AccessibilityViewCommand { _, _ -> moveBy(1) },
                )
            }
        }

        private fun showMoveMenu() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || onMoveItem == null) return
            PopupMenu(binding.root.context, binding.image).apply {
                menu.add(0, R.id.settings_move_up, 0, R.string.settings_move_up).isEnabled = position > 0
                menu.add(0, R.id.settings_move_down, 1, R.string.settings_move_down).isEnabled = position < itemCount - 1
                setOnMenuItemClickListener { item ->
                    moveBy(if (item.itemId == R.id.settings_move_up) -1 else 1, focusHandle = true)
                }
                show()
            }
        }

        private fun moveBy(offset: Int, focusHandle: Boolean = false): Boolean {
            val recycler = binding.root.parent as? RecyclerView
            val layout = recycler?.layoutManager as? LinearLayoutManager
            val first = layout?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val top = layout?.findViewByPosition(first)?.let { it.top - recycler.paddingTop }
            val from = bindingAdapterPosition
            if (!moveItem(from, from + offset)) return false
            // Retain the viewport, rather than anchoring the previously focused row
            // at its old screen position and hiding the item that replaced it.
            if (first != RecyclerView.NO_POSITION && top != null) layout.scrollToPositionWithOffset(first, top)
            if (focusHandle) binding.image.post { binding.image.requestFocus() }
            return true
        }
    }

    companion object {
        private const val REORDER_PAYLOAD = "reorder"
    }
}
