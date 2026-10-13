package com.github.andreyasadchy.xtra.ui.saved

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.github.andreyasadchy.xtra.R
import com.google.android.material.card.MaterialCardView

interface SavedSelectablePage {
    fun startSavedSelection()
}

interface SavedSelectionHost {
    val savedSelectionChrome: SavedSelectionChrome?
}

class SavedItemSelection(savedState: Bundle?) {
    var active = savedState?.getBoolean(STATE_ACTIVE) == true
        private set
    private val ids = savedState?.getStringArrayList(STATE_IDS)?.toCollection(linkedSetOf()) ?: linkedSetOf()
    val selectedIds: Set<String> get() = ids
    var loadedIds: () -> List<String> = { emptyList() }
    var hasLoadedItems: () -> Boolean = { false }
    var onChanged: (String?) -> Unit = {}
    var onDelete: (Set<String>) -> Unit = {}
    var description: CharSequence? = null
    private var chrome: SavedSelectionChrome? = null

    fun start(host: SavedSelectionChrome?) {
        if (host == null) return
        chrome = host
        active = true
        onChanged(null)
        host.show(this)
    }

    fun click(id: String, selectable: Boolean = true): Boolean {
        if (!active) return false
        if (selectable) {
            if (!ids.add(id)) ids.remove(id)
            onChanged(id)
            chrome?.refresh()
        }
        return true
    }

    fun unselect(id: String) {
        if (ids.remove(id)) {
            onChanged(id)
            chrome?.refresh()
        }
    }

    fun selectLoaded() {
        ids.addAll(loadedIds())
        onChanged(null)
        chrome?.refresh()
    }

    fun clear() {
        ids.clear()
        onChanged(null)
        chrome?.refresh()
    }

    fun retain(validIds: Set<String>) {
        if (ids.retainAll(validIds)) {
            onChanged(null)
            chrome?.refresh()
        }
    }

    fun refreshLoadedItems() {
        chrome?.refresh()
    }

    fun finish() {
        active = false
        ids.clear()
        onChanged(null)
        chrome?.hide(this)
        chrome = null
    }

    fun save(outState: Bundle) {
        outState.putBoolean(STATE_ACTIVE, active)
        outState.putStringArrayList(STATE_IDS, ArrayList(ids))
    }

    companion object {
        val PAYLOAD = Any()
        private const val STATE_ACTIVE = "saved_selection_active"
        private const val STATE_IDS = "saved_selection_ids"
    }
}

/** Selection changes only the card and its actions, without rebinding images or metadata. */
class SavedSelectionCard(private val card: MaterialCardView) {
    private data class ActionState(val view: View, val clickable: Boolean, val longClickable: Boolean, val focusable: Boolean, val enabled: Boolean)
    private var actions: List<ActionState>? = null
    private var childrenAccessibility: List<Pair<View, Int>> = emptyList()
    private var contentDescription: CharSequence? = null
    private var accessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var cardEnabled = true

    fun prepareForBind() {
        actions?.forEach {
            it.view.isClickable = it.clickable
            it.view.isLongClickable = it.longClickable
            it.view.isFocusable = it.focusable
            it.view.isEnabled = it.enabled
        }
        if (actions != null) {
            childrenAccessibility.forEach { (view, value) -> view.importantForAccessibility = value }
            card.contentDescription = contentDescription
            card.importantForAccessibility = accessibility
            card.isEnabled = cardEnabled
        }
        actions = null
        childrenAccessibility = emptyList()
    }

    fun bind(selection: SavedItemSelection, id: String, selectable: Boolean = true) {
        if (selection.active) {
            if (actions == null) {
                val descendants = descendants(card).toList()
                actions = descendants.filter { it.isClickable || it.isLongClickable || it.isFocusable }.map {
                    ActionState(it, it.isClickable, it.isLongClickable, it.isFocusable, it.isEnabled)
                }.also { states ->
                    states.forEach {
                        it.view.isClickable = false
                        it.view.isLongClickable = false
                        it.view.isFocusable = false
                        it.view.isEnabled = false
                    }
                }
                contentDescription = card.contentDescription
                cardEnabled = card.isEnabled
                accessibility = card.importantForAccessibility
                childrenAccessibility = card.children.map { it to it.importantForAccessibility }.toList()
                childrenAccessibility.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                card.contentDescription = descendants.filterIsInstance<TextView>().filter { text ->
                    text !is android.widget.Button &&
                    text.visibility == View.VISIBLE && generateSequence(text.parent as? View) { it.parent as? View }
                        .takeWhile { it !== card }.all { it.visibility == View.VISIBLE }
                }.mapNotNull {
                    it.text.takeIf { text -> text.isNotBlank() }
                }.joinToString(", ") + if (selectable) "" else ". " + card.context.getString(R.string.saved_download_selection_limit)
                card.checkedIcon = ContextCompat.getDrawable(card.context, R.drawable.ic_saved_selection_check)
            }
            if (!selectable) card.isChecked = false
            card.isEnabled = cardEnabled && selectable
            card.isCheckable = selectable
            card.isChecked = selectable && id in selection.selectedIds
        } else {
            prepareForBind()
            // MaterialCardView ignores setChecked when it is no longer checkable.
            card.isChecked = false
            card.isCheckable = false
        }
    }

    private fun descendants(parent: ViewGroup): Sequence<View> = sequence {
        parent.children.forEach { child ->
            yield(child)
            if (child is ViewGroup) yieldAll(descendants(child))
        }
    }
}
